package app.penny.domain

import app.penny.core.event.Entities
import app.penny.core.event.EventSourcedRepository
import app.penny.core.event.PennyJson
import app.penny.core.event.Reasons
import app.penny.core.event.Stored
import app.penny.core.model.AccountType
import app.penny.core.model.Category
import app.penny.core.model.DefaultCategories
import app.penny.core.model.FinancialAccount
import app.penny.core.model.Profile
import app.penny.core.model.Transaction
import app.penny.core.period.CycleRule
import app.penny.data.RoomEventStore
import app.penny.data.db.EntityRow
import app.penny.data.db.PennyDao
import app.penny.data.db.TransactionRow
import app.penny.data.toState
import app.penny.packs.PackRepository
import app.penny.packs.RegionalPack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class TxItem(val id: String, val tx: Transaction, val updatedAt: Long, val deletedAt: Long?)

/** App-facing use cases. Every write goes through an [EventSourcedRepository], so every write is an Event. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PennyRepository @Inject constructor(
    store: RoomEventStore,
    private val dao: PennyDao,
    val packs: PackRepository,
    private val prefs: AppPrefs,
) {
    val profiles = EventSourcedRepository(Entities.PROFILE, Profile.serializer(), store, prefs::actor)
    val accounts = EventSourcedRepository(Entities.ACCOUNT, FinancialAccount.serializer(), store, prefs::actor)
    val categories = EventSourcedRepository(Entities.CATEGORY, Category.serializer(), store, prefs::actor)
    val transactions = EventSourcedRepository(Entities.TRANSACTION, Transaction.serializer(), store, prefs::actor)

    val currentProfileId = prefs.currentProfileId
    val localAccountId get() = prefs.localAccountId

    fun observeProfile(): Flow<Stored<Profile>?> = currentProfileId.flatMapLatest { pid ->
        if (pid == null) flowOf(null) else dao.observeAllOf(Entities.PROFILE).map { rows ->
            rows.firstOrNull { it.id == pid }?.let { decodeStored(it, Profile.serializer()) }
        }
    }

    fun observeAccounts(pid: String): Flow<List<Stored<FinancialAccount>>> =
        dao.observeEntities(Entities.ACCOUNT, pid, false).map { rows ->
            rows.map { decodeStored(it, FinancialAccount.serializer()) }.sortedWith(compareBy({ it.value.sort }, { it.value.name }))
        }

    fun observeCategories(pid: String): Flow<List<Stored<Category>>> =
        dao.observeEntities(Entities.CATEGORY, pid, false).map { rows ->
            rows.map { decodeStored(it, Category.serializer()) }.filter { !it.value.archived }
        }

    fun observeTransactions(pid: String): Flow<List<TxItem>> = dao.observeAllTransactions(pid).map { it.map(::toItem) }

    fun observeTrash(pid: String): Flow<List<TxItem>> = dao.observeTrash(pid).map { it.map(::toItem) }

    private fun toItem(r: TransactionRow) =
        TxItem(r.id, PennyJson.decodeFromString(Transaction.serializer(), r.json), r.updatedAt, r.deletedAt)

    private fun <T> decodeStored(row: EntityRow, s: KSerializer<T>): Stored<T> {
        val state = row.toState()
        return Stored(PennyJson.decodeFromJsonElement(s, state.data), state)
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** OB-01: region, pay cycle and first cash account. Seeds categories from the universal tree. */
    suspend fun completeOnboarding(pack: RegionalPack, cycle: CycleRule, cashName: String, openingMinor: Long): String {
        val pid = newId()
        val accountId = newId()
        val profile = profileFor(pack, "Personal", cycle).copy(defaultAccountId = accountId)
        profiles.create(pid, pid, profile, reason = Reasons.SEED)

        val keyToId = DefaultCategories.tree.associate { it.key to "$pid:${it.key}" }
        categories.createAll(
            pid,
            DefaultCategories.tree.mapIndexed { i, c ->
                keyToId.getValue(c.key) to Category(
                    key = c.key, kind = c.kind, name = c.name, parentId = c.parentKey?.let(keyToId::getValue),
                    excludedFromSpend = c.excluded, sort = i,
                )
            },
            reason = Reasons.SEED,
        )
        accounts.create(
            accountId, pid,
            FinancialAccount(cashName, AccountType.CASH, pack.currency, openingMinor, LocalDate.now(ZoneId.of(pack.timezone))),
            reason = Reasons.SEED,
        )
        prefs.setCurrentProfile(pid)
        return pid
    }

    fun profileFor(pack: RegionalPack, name: String, cycle: CycleRule = pack.payCycleDefault) = Profile(
        name = name,
        regionPack = pack.id,
        baseCurrency = pack.currency,
        localeTag = pack.primaryLocale,
        timezone = runCatching { ZoneId.systemDefault().id }.getOrDefault(pack.timezone),
        cycle = cycle,
        firstDayOfWeek = pack.firstDayOfWeek,
        fiscalYearStart = pack.fiscalYearStart,
    )

    /**
     * Switches the regional pack (OB-02): base currency, number format, pay cycle and calendar defaults follow the
     * new pack. Accounts with no activity and the opening cash account switch currency too; transactions keep theirs.
     * Returns the undo group ids.
     */
    suspend fun switchRegion(pid: String, packId: String): List<String> {
        val cur = profiles.get(pid) ?: return emptyList()
        val pack = packs.get(packId)
        val groups = mutableListOf<String>()
        profiles.update(
            pid,
            cur.value.copy(
                regionPack = pack.id, baseCurrency = pack.currency, localeTag = pack.primaryLocale, cycle = pack.payCycleDefault,
                firstDayOfWeek = pack.firstDayOfWeek, fiscalYearStart = pack.fiscalYearStart, groupingOverride = null,
            ),
        )?.let(groups::add)
        val usedAccounts = transactions.list(pid).flatMap { listOfNotNull(it.value.accountId, it.value.toAccountId) }.toSet()
        accounts.list(pid).filter { it.state.id !in usedAccounts && it.value.currency != pack.currency }.forEach { a ->
            accounts.update(a.state.id, a.value.copy(currency = pack.currency, openingBalanceMinor = 0))?.let(groups::add)
        }
        return groups
    }

    /** Undo works on any entity: compensating events keep each original event's entity. */
    suspend fun undoAll(groups: List<String>) {
        groups.asReversed().forEach { g -> transactions.undo(g) }
    }
}
