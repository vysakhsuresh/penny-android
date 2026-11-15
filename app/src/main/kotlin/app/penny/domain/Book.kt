package app.penny.domain

import app.penny.core.budget.FxConverter
import app.penny.core.budget.FxTable
import app.penny.core.event.Entities
import app.penny.core.event.EntityState
import app.penny.core.event.PennyJson
import app.penny.core.event.Stored
import app.penny.core.model.AdvisorLink
import app.penny.core.model.AlertRecord
import app.penny.core.model.Allocation
import app.penny.core.model.Attachment
import app.penny.core.model.BudgetMove
import app.penny.core.model.CaptureItem
import app.penny.core.model.CaptureSource
import app.penny.core.model.Category
import app.penny.core.model.CategoryKind
import app.penny.core.model.Circle
import app.penny.core.model.CircleContribution
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
import app.penny.core.period.PeriodContext
import app.penny.core.search.Row
import app.penny.data.db.EntityRow
import app.penny.data.toState
import kotlinx.serialization.KSerializer
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** Decode cache shared by all snapshots: rows are decoded once per (entity, id, version). */
class DecodeCache {
    private val map = ConcurrentHashMap<Triple<String, String, Int>, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(row: EntityState, s: KSerializer<T>): T? {
        val key = Triple(row.entity, row.id, row.version)
        map[key]?.let { return it as T }
        val v = runCatching { PennyJson.decodeFromJsonElement(s, row.data) }.getOrNull() ?: return null
        if (map.size > 200_000) map.clear()
        map[key] = v as Any
        return v
    }
}

/**
 * One profile (or space) decoded in memory: every list a screen needs. Built from the materialised `entities` table,
 * which is itself rebuilt from events. Deleted items are kept in [deleted] for Trash and reassign flows.
 */
class Book(
    val profileId: String,
    val profile: Profile,
    rows: List<EntityRow>,
    private val cache: DecodeCache,
    /** Device-local reference rates (ECB); manual rates in [fxRates] override them for the same date. */
    val cachedRates: List<FxRate> = emptyList(),
) {
    private val live: Map<String, List<EntityState>>
    val deleted: List<EntityState>

    init {
        val states = rows.map { it.toState() }
        live = states.filter { it.deletedAt == null }.groupBy { it.entity }
        deleted = states.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }
    }

    fun <T> list(entity: String, s: KSerializer<T>): List<Stored<T>> =
        live[entity].orEmpty().mapNotNull { st -> cache.get(st, s)?.let { Stored(it, st) } }

    fun <T> decode(st: EntityState, s: KSerializer<T>): T? = cache.get(st, s)

    val accounts by lazy { list(Entities.ACCOUNT, FinancialAccount.serializer()).sortedWith(compareBy({ it.value.sort }, { it.value.name.lowercase() })) }
    val accountMap by lazy { accounts.associateBy { it.state.id } }
    val activeAccounts by lazy { accounts.filter { !it.value.archived } }
    val categories by lazy { list(Entities.CATEGORY, Category.serializer()).sortedBy { it.value.sort } }
    val categoryMap by lazy { categories.associateBy { it.state.id } }
    val transactions by lazy { list(Entities.TRANSACTION, Transaction.serializer()) }
    val txList by lazy { transactions.map { it.value } }
    val txMap by lazy { transactions.associateBy { it.state.id } }
    val tags by lazy { list(Entities.TAG, Tag.serializer()).sortedWith(compareBy({ it.value.sort }, { it.value.name.lowercase() })) }
    val payees by lazy { list(Entities.PAYEE, Payee.serializer()).sortedBy { it.value.name.lowercase() } }
    val rules by lazy { list(Entities.RULE, Rule.serializer()).sortedBy { it.value.order } }
    val envelopes by lazy { list(Entities.ENVELOPE, Envelope.serializer()).sortedWith(compareBy({ it.value.sort }, { it.value.name.lowercase() })) }
    val allocations by lazy { list(Entities.ALLOCATION, Allocation.serializer()) }
    val moves by lazy { list(Entities.BUDGET_MOVE, BudgetMove.serializer()) }
    val goals by lazy { list(Entities.GOAL, Goal.serializer()).sortedBy { it.value.sort } }
    val goalEntries by lazy { list(Entities.GOAL_ENTRY, GoalEntry.serializer()) }
    val recurring by lazy { list(Entities.RECURRING, Recurring.serializer()).sortedBy { it.value.name.lowercase() } }
    val occurrences by lazy { list(Entities.OCCURRENCE, Occurrence.serializer()) }
    val templates by lazy { list(Entities.TEMPLATE, Template.serializer()).sortedBy { it.value.sort } }
    val savedSearches by lazy { list(Entities.SAVED_SEARCH, SavedSearch.serializer()).sortedBy { it.value.sort } }
    val attachments by lazy { list(Entities.ATTACHMENT, Attachment.serializer()).sortedBy { it.value.sort } }
    val valuations by lazy { list(Entities.VALUATION, Valuation.serializer()) }
    val fxRates by lazy { list(Entities.FX_RATE, FxRate.serializer()) }
    val people by lazy { list(Entities.PERSON, Person.serializer()).sortedBy { it.value.name.lowercase() } }
    val peopleMap by lazy { people.associateBy { it.state.id } }
    val lending by lazy { list(Entities.LENDING, LendingEntry.serializer()).sortedByDescending { it.value.date } }
    val groups by lazy { list(Entities.GROUP, Group.serializer()).sortedBy { it.value.name.lowercase() } }
    val groupExpenses by lazy { list(Entities.GROUP_EXPENSE, GroupExpense.serializer()).sortedByDescending { it.value.date } }
    val settlements by lazy { list(Entities.SETTLEMENT, Settlement.serializer()) }
    val loans by lazy { list(Entities.LOAN, Loan.serializer()).sortedBy { it.value.order } }
    val remittances by lazy { list(Entities.REMITTANCE, Remittance.serializer()).sortedByDescending { it.value.date } }
    val circles by lazy { list(Entities.CIRCLE, Circle.serializer()) }
    val circleContributions by lazy { list(Entities.CIRCLE_CONTRIBUTION, CircleContribution.serializer()) }
    val alerts by lazy { list(Entities.ALERT, AlertRecord.serializer()).sortedByDescending { it.value.firedAtMillis } }
    val watchlists by lazy { list(Entities.WATCHLIST, Watchlist.serializer()) }
    val captures by lazy { list(Entities.CAPTURE, CaptureItem.serializer()).sortedByDescending { it.state.createdAt } }
    val captureSources by lazy { list(Entities.CAPTURE_SOURCE, CaptureSource.serializer()) }
    val syncLinks by lazy { list(Entities.SYNC_LINK, SyncLink.serializer()) }
    val spaces by lazy { list(Entities.SPACE, Space.serializer()) }
    val members by lazy { list(Entities.MEMBER, Member.serializer()) }
    val invitations by lazy { list(Entities.INVITATION, Invitation.serializer()) }
    val devices by lazy { list(Entities.DEVICE, Device.serializer()) }
    val advisorLinks by lazy { list(Entities.ADVISOR_LINK, AdvisorLink.serializer()) }

    val zone: ZoneId by lazy { runCatching { ZoneId.of(profile.timezone) }.getOrDefault(ZoneId.systemDefault()) }
    fun today(): LocalDate = LocalDate.now(zone)

    val fx: FxConverter by lazy { FxTable(cachedRates + fxRates.map { it.value }) }

    /** Category ids excluded from spend, including children of excluded parents (CAT-04). */
    val excludedCategoryIds: Set<String> by lazy {
        fun excluded(id: String?): Boolean {
            var c = id?.let(categoryMap::get)
            var guard = 0
            while (c != null && guard++ < 10) {
                if (c.value.excludedFromSpend) return true
                c = c.value.parentId?.let(categoryMap::get)
            }
            return false
        }
        categories.map { it.state.id }.filter(::excluded).toSet()
    }

    fun categoryName(id: String?): String {
        val c = id?.let(categoryMap::get) ?: return ""
        val parent = c.value.parentId?.let(categoryMap::get)
        return if (parent != null) "${parent.value.name} › ${c.value.name}" else c.value.name
    }

    fun topLevel(id: String?): String? {
        var c = id?.let { categoryMap[it] } ?: return id
        var cid: String = id
        var guard = 0
        while (c.value.parentId != null && guard++ < 10) {
            cid = c.value.parentId!!
            c = categoryMap[cid] ?: break
        }
        return cid
    }

    fun categoriesOf(kind: CategoryKind) = categories.filter { it.value.kind == kind && !it.value.archived }

    val periodContext: PeriodContext by lazy {
        val rule = profile.cycle as? CycleRule.OnIncome ?: return@lazy PeriodContext.EMPTY
        PeriodContext(txList.filter { it.type == app.penny.core.model.TxType.INCOME && it.currency == profile.baseCurrency && it.amountMinor >= rule.thresholdMinor }.map { it.date })
    }

    val rows: List<Row> by lazy { transactions.map { Row(it.state.id, it.value, it.state.createdAt) } }

    fun accountName(id: String?) = id?.let { accountMap[it]?.value?.name }.orEmpty()

    fun personName(id: String?) = when (id) {
        null -> ""
        app.penny.core.model.Group.ME -> ""
        else -> peopleMap[id]?.value?.name.orEmpty()
    }
}
