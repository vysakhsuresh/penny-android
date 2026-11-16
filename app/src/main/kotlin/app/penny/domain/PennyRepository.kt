package app.penny.domain

import app.penny.core.event.Entities
import app.penny.core.event.EventSourcedRepository
import app.penny.core.event.PennyJson
import app.penny.core.event.Reasons
import app.penny.core.event.Stored
import app.penny.core.model.AccountType
import app.penny.core.model.AdvisorLink
import app.penny.core.model.AlertRecord
import app.penny.core.model.Allocation
import app.penny.core.model.Attachment
import app.penny.core.model.BudgetMove
import app.penny.core.model.CaptureItem
import app.penny.core.model.CaptureSource
import app.penny.core.model.Category
import app.penny.core.model.Circle
import app.penny.core.model.CircleContribution
import app.penny.core.model.DefaultCategories
import app.penny.core.model.Device
import app.penny.core.model.Envelope
import app.penny.core.model.FinancialAccount
import app.penny.core.model.FxRate
import app.penny.core.model.Goal
import app.penny.core.model.GoalEntry
import app.penny.core.model.Group
import app.penny.core.model.GroupExpense
import app.penny.core.model.Invitation
import app.penny.core.model.LendingEntry
import app.penny.core.model.Loan
import app.penny.core.model.Member
import app.penny.core.model.Occurrence
import app.penny.core.model.Payee
import app.penny.core.model.Person
import app.penny.core.model.Profile
import app.penny.core.model.ProfileKind
import app.penny.core.model.Recurring
import app.penny.core.model.Remittance
import app.penny.core.model.Rule
import app.penny.core.model.SavedSearch
import app.penny.core.model.Settlement
import app.penny.core.model.Space
import app.penny.core.model.SyncLink
import app.penny.core.model.Tag
import app.penny.core.model.Template
import app.penny.core.model.Transaction
import app.penny.core.model.Valuation
import app.penny.core.model.Watchlist
import app.penny.core.period.CycleRule
import app.penny.data.RoomEventStore
import app.penny.data.db.PennyDao
import app.penny.data.db.TransactionRow
import app.penny.data.toState
import app.penny.packs.PackRepository
import app.penny.packs.RegionalPack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val store: RoomEventStore,
    val dao: PennyDao,
    val packs: PackRepository,
    val prefs: AppPrefs,
    val rates: RatesCache,
) {
    private fun <T> repo(entity: String, s: KSerializer<T>) = EventSourcedRepository(entity, s, store, prefs::actor)

    /**
     * AC-04: a transaction in another currency than its account also records the amount in the account's currency, at
     * that day's rate (manual rates first, then reference rates), unless the writer already supplied it.
     */
    private suspend fun inAccountCurrency(t: Transaction): Transaction {
        val acctCur = accounts.get(t.accountId)?.value?.currency ?: return t
        if (acctCur == t.currency) return if (t.accountAmountMinor != null) t.copy(accountAmountMinor = null) else t
        if (t.accountAmountMinor != null) return t // set by the writer from a known rate (e.g. a remittance)
        val pid = currentProfileId.value
        val manual = pid?.let { p -> fxRates.list(p).map { it.value } }.orEmpty()
        val converted = app.penny.core.budget.FxTable(rates.rates.value + manual).convert(t.amountMinor, t.currency, acctCur, t.date)
        return t.copy(accountAmountMinor = converted ?: t.accountAmountMinor)
    }

    private suspend fun routeViaWallet(t: Transaction): Transaction {
        val bank = accounts.get(t.accountId)?.takeIf { it.state.deletedAt == null }?.value?.aliasOf ?: return t
        if (bank == t.toAccountId || accounts.get(bank)?.state?.deletedAt != null) return t
        return t.copy(accountId = bank, viaAccountId = t.viaAccountId ?: t.accountId)
    }

    val profiles = repo(Entities.PROFILE, Profile.serializer())
    val accounts = repo(Entities.ACCOUNT, FinancialAccount.serializer())
    val categories = repo(Entities.CATEGORY, Category.serializer())
    /** AC-07: a spend from a pass-through wallet (e.g. a UPI app) posts to its bank with the wallet as "via". */
    val transactions = EventSourcedRepository(Entities.TRANSACTION, Transaction.serializer(), store, prefs::actor, normalize = { t -> inAccountCurrency(routeViaWallet(t)) })
    val tags = repo(Entities.TAG, Tag.serializer())
    val payees = repo(Entities.PAYEE, Payee.serializer())
    val rules = repo(Entities.RULE, Rule.serializer())
    val envelopes = repo(Entities.ENVELOPE, Envelope.serializer())
    val allocations = repo(Entities.ALLOCATION, Allocation.serializer())
    val moves = repo(Entities.BUDGET_MOVE, BudgetMove.serializer())
    val goals = repo(Entities.GOAL, Goal.serializer())
    val goalEntries = repo(Entities.GOAL_ENTRY, GoalEntry.serializer())
    val recurring = repo(Entities.RECURRING, Recurring.serializer())
    val occurrences = repo(Entities.OCCURRENCE, Occurrence.serializer())
    val templates = repo(Entities.TEMPLATE, Template.serializer())
    val savedSearches = repo(Entities.SAVED_SEARCH, SavedSearch.serializer())
    val attachments = repo(Entities.ATTACHMENT, Attachment.serializer())
    val valuations = repo(Entities.VALUATION, Valuation.serializer())
    val fxRates = repo(Entities.FX_RATE, FxRate.serializer())
    val people = repo(Entities.PERSON, Person.serializer())
    val lending = repo(Entities.LENDING, LendingEntry.serializer())
    val groups = repo(Entities.GROUP, Group.serializer())
    val groupExpenses = repo(Entities.GROUP_EXPENSE, GroupExpense.serializer())
    val settlements = repo(Entities.SETTLEMENT, Settlement.serializer())
    val loans = repo(Entities.LOAN, Loan.serializer())
    val remittances = repo(Entities.REMITTANCE, Remittance.serializer())
    val circles = repo(Entities.CIRCLE, Circle.serializer())
    val circleContributions = repo(Entities.CIRCLE_CONTRIBUTION, CircleContribution.serializer())
    val alerts = repo(Entities.ALERT, AlertRecord.serializer())
    val watchlists = repo(Entities.WATCHLIST, Watchlist.serializer())
    val captures = repo(Entities.CAPTURE, CaptureItem.serializer())
    val captureSources = repo(Entities.CAPTURE_SOURCE, CaptureSource.serializer())
    val syncLinks = repo(Entities.SYNC_LINK, SyncLink.serializer())
    val spaces = repo(Entities.SPACE, Space.serializer())
    val members = repo(Entities.MEMBER, Member.serializer())
    val invitations = repo(Entities.INVITATION, Invitation.serializer())
    val devices = repo(Entities.DEVICE, Device.serializer())
    val advisorLinks = repo(Entities.ADVISOR_LINK, AdvisorLink.serializer())

    /** Any repository can undo any group: compensating events keep each original event's entity. */
    val undoer get() = transactions

    val byEntity: Map<String, EventSourcedRepository<*>> = mapOf(
        Entities.PROFILE to profiles, Entities.ACCOUNT to accounts, Entities.CATEGORY to categories, Entities.TRANSACTION to transactions,
        Entities.TAG to tags, Entities.PAYEE to payees, Entities.RULE to rules, Entities.ENVELOPE to envelopes,
        Entities.ALLOCATION to allocations, Entities.BUDGET_MOVE to moves, Entities.GOAL to goals, Entities.GOAL_ENTRY to goalEntries,
        Entities.RECURRING to recurring, Entities.OCCURRENCE to occurrences, Entities.TEMPLATE to templates,
        Entities.SAVED_SEARCH to savedSearches, Entities.ATTACHMENT to attachments, Entities.VALUATION to valuations,
        Entities.FX_RATE to fxRates, Entities.PERSON to people, Entities.LENDING to lending, Entities.GROUP to groups,
        Entities.GROUP_EXPENSE to groupExpenses, Entities.SETTLEMENT to settlements, Entities.LOAN to loans,
        Entities.REMITTANCE to remittances, Entities.CIRCLE to circles, Entities.CIRCLE_CONTRIBUTION to circleContributions,
        Entities.ALERT to alerts, Entities.WATCHLIST to watchlists, Entities.CAPTURE to captures, Entities.CAPTURE_SOURCE to captureSources,
        Entities.SYNC_LINK to syncLinks, Entities.SPACE to spaces, Entities.MEMBER to members, Entities.INVITATION to invitations,
        Entities.DEVICE to devices, Entities.ADVISOR_LINK to advisorLinks,
    )

    fun repoFor(entity: String): EventSourcedRepository<*> = byEntity[entity] ?: error("No repository for $entity")

    val currentProfileId = prefs.currentProfileId
    val localAccountId get() = prefs.localAccountId
    fun pid(): String = currentProfileId.value ?: error("No profile open")

    private val cache = DecodeCache()

    /** The open profile as one decoded snapshot; re-emitted on every change to that profile. */
    fun observeBook(): Flow<Book?> = currentProfileId.flatMapLatest { pid ->
        if (pid == null) flowOf(null) else observeBook(pid)
    }

    fun observeBook(pid: String): Flow<Book?> = kotlinx.coroutines.flow.combine(dao.observeProfileEntities(pid), rates.rates) { rows, cached ->
        val profileRow = rows.firstOrNull { it.entity == Entities.PROFILE && it.id == pid && it.deletedAt == null } ?: return@combine null
        val profile = runCatching { PennyJson.decodeFromString(Profile.serializer(), profileRow.json) }.getOrNull() ?: return@combine null
        Book(pid, profile, rows, cache, cached)
    }

    /** All profiles on this device (US-02 switcher); decoys are hidden unless [includeDecoy]. */
    /** The first real (non-decoy) book, for leaving a duress session when the earlier book was not recorded. */
    suspend fun firstRealProfileId(): String? = dao.observeAllOf(Entities.PROFILE).first().mapNotNull { r ->
        val st = r.toState()
        runCatching { Stored(PennyJson.decodeFromJsonElement(Profile.serializer(), st.data), st) }.getOrNull()
    }.filter { !it.value.decoy && it.state.deletedAt == null }.sortedWith(compareBy({ it.value.sort }, { it.value.name })).firstOrNull()?.state?.id

    fun observeProfiles(includeDecoy: Boolean = false): Flow<List<Stored<Profile>>> = dao.observeAllOf(Entities.PROFILE).map { rows ->
        rows.mapNotNull { r ->
            val st = r.toState()
            runCatching { Stored(PennyJson.decodeFromJsonElement(Profile.serializer(), st.data), st) }.getOrNull()
        }.filter { p ->
            // In a duress session only the decoy book exists; otherwise the decoy is hidden.
            if (prefs.inDuress()) p.state.id == prefs.currentProfileId.value else includeDecoy || !p.value.decoy
        }.sortedWith(compareBy({ it.value.sort }, { it.value.name }))
    }.distinctUntilChanged()

    fun observeProfile(): Flow<Stored<Profile>?> = observeBook().map { b -> b?.let { Stored(it.profile, it.profileStateOrNull()) } }

    private fun Book.profileStateOrNull() = app.penny.core.event.EntityState(
        app.penny.core.event.Entities.PROFILE, profileId, profileId, kotlinx.serialization.json.JsonObject(emptyMap()), 0, 0, 0, null, "",
    )

    fun observeTrash(pid: String): Flow<List<TxItem>> = dao.observeTrash(pid).map { it.map(::toItem) }

    private fun toItem(r: TransactionRow) =
        TxItem(r.id, PennyJson.decodeFromString(Transaction.serializer(), r.json), r.updatedAt, r.deletedAt)

    fun newId(): String = UUID.randomUUID().toString()

    /** OB-01: region, pay cycle and first cash account. Seeds categories from the universal tree and smart tags. */
    suspend fun completeOnboarding(pack: RegionalPack, cycle: CycleRule, cashName: String, openingMinor: Long, profileName: String = "Personal"): String {
        val pid = createProfile(pack, profileName, cycle, ProfileKind.PERSONAL, cashName, openingMinor)
        prefs.setCurrentProfile(pid)
        return pid
    }

    suspend fun createProfile(
        pack: RegionalPack,
        name: String,
        cycle: CycleRule,
        kind: ProfileKind,
        cashName: String,
        openingMinor: Long,
        decoy: Boolean = false,
    ): String {
        val pid = newId()
        val accountId = newId()
        val profile = profileFor(pack, name, cycle).copy(defaultAccountId = accountId, kind = kind, decoy = decoy,
            business = if (kind == ProfileKind.BUSINESS) app.penny.core.model.BusinessSettings() else null)
        profiles.create(pid, pid, profile, reason = Reasons.SEED)
        seedCategories(pid)
        accounts.create(
            accountId, pid,
            FinancialAccount(cashName, AccountType.CASH, pack.currency, openingMinor, LocalDate.now(ZoneId.of(pack.timezone))),
            reason = Reasons.SEED,
        )
        tags.createAll(pid, SMART_TAGS.mapIndexed { i, (key, label) -> "$pid:tag:$key" to Tag(label, smart = key, sort = i) }, reason = Reasons.SEED)
        return pid
    }

    /** CAT-02: seeds missing defaults by key; never touches custom or renamed categories. Returns the undo group. */
    suspend fun seedCategories(pid: String): String? {
        val all = categories.list(pid, includeDeleted = true)
        val defaultKeys = DefaultCategories.tree.map { it.key }.toSet()
        val live = all.filter { it.state.deletedAt == null }.mapNotNull { it.value.key }.toSet()
        // A deleted default comes back from Trash (same id, history kept); one that never existed is created.
        val toRestore = all.filter { it.state.deletedAt != null && it.value.key in defaultKeys && it.value.key !in live }.distinctBy { it.value.key }
        val existing = all.mapNotNull { it.value.key }.toSet()
        val keyToId = DefaultCategories.tree.associate { it.key to "$pid:${it.key}" }
        val missing = DefaultCategories.tree.withIndex().filter { it.value.key !in existing }
        if (missing.isEmpty() && toRestore.isEmpty()) return null
        val group = java.util.UUID.randomUUID().toString()
        toRestore.forEach { categories.restore(it.state.id, group) }
        if (missing.isEmpty()) return group
        return categories.createAll(
            pid,
            missing.map { (i, c) ->
                keyToId.getValue(c.key) to Category(
                    key = c.key, kind = c.kind, name = c.name, parentId = c.parentKey?.let(keyToId::getValue),
                    excludedFromSpend = c.excluded, sort = i, tax = c.key.startsWith("fin.tax"),
                )
            },
            reason = Reasons.SEED,
            groupId = group,
        )
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
     * new pack. Accounts with no activity switch currency too; transactions keep theirs. Returns the undo group.
     */
    suspend fun switchRegion(pid: String, packId: String): String {
        val g = profiles.newGroup()
        val cur = profiles.get(pid) ?: return g
        val pack = packs.get(packId)
        profiles.update(
            pid,
            cur.value.copy(
                regionPack = pack.id, baseCurrency = pack.currency, localeTag = pack.primaryLocale, cycle = pack.payCycleDefault,
                firstDayOfWeek = pack.firstDayOfWeek, fiscalYearStart = pack.fiscalYearStart, groupingOverride = null,
            ),
            groupId = g,
        )
        val usedAccounts = transactions.list(pid).flatMap { listOfNotNull(it.value.accountId, it.value.toAccountId) }.toSet()
        accounts.list(pid).filter { it.state.id !in usedAccounts && it.value.currency != pack.currency }.forEach { a ->
            accounts.update(a.state.id, a.value.copy(currency = pack.currency, openingBalanceMinor = 0), groupId = g)
        }
        return g
    }

    suspend fun undo(group: String?) { if (group != null) undoer.undo(group) }

    suspend fun undoAll(groups: List<String>) { groups.asReversed().forEach { g -> undoer.undo(g) } }

    companion object {
        val SMART_TAGS = listOf("reimbursable" to "Reimbursable", "tax" to "Tax-deductible", "trip" to "Trip", "gift" to "Gift", "business" to "Business")
    }
}
