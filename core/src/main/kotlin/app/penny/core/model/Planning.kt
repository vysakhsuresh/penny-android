package app.penny.core.model

import app.penny.core.period.CycleRule
import app.penny.core.period.LocalDateIsoSerializer
import app.penny.core.schedule.Recurrence
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** CAT-07. [smart] marks the built-in smart tags (reimbursable, tax, trip, gift, business) that have reports. */
@Serializable
data class Tag(
    val name: String,
    val color: Long = 0,
    val archived: Boolean = false,
    val smart: String? = null,
    val sort: Int = 0,
)

/** PY-01. [aliases] are raw strings seen in captures and imports, kept for matching after rename or merge (PY-02). */
@Serializable
data class Payee(
    val name: String,
    val aliases: List<String> = emptyList(),
    val defaultCategoryId: String? = null,
    val defaultAccountId: String? = null,
    val website: String = "",
    val notes: String = "",
    val logoRef: String? = null,
)

// ---------------------------------------------------------------- Rules (RU)

@Serializable
enum class RuleField { PAYEE, NOTE, AMOUNT, ACCOUNT, SOURCE, CURRENCY, WEEKDAY, HOUR, APP, TYPE, CATEGORY }

@Serializable
enum class RuleOp { CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, REGEX, BETWEEN, GT, LT, IN }

@Serializable
data class RuleCondition(val field: RuleField, val op: RuleOp, val value: String, val value2: String = "")

@Serializable
data class SplitShare(val bp: Int, val categoryId: String?)

@Serializable
data class RuleActions(
    val categoryId: String? = null,
    val addTags: List<String> = emptyList(),
    val renamePayee: String? = null,
    val accountId: String? = null,
    /** Turns a matching spend into a transfer to this account (e.g. card payments). */
    val transferToAccountId: String? = null,
    val excludeFromBudget: Boolean? = null,
    val excludeFromReports: Boolean? = null,
    val isPrivate: Boolean? = null,
    val autoPost: Boolean? = null,
    val splitTemplate: List<SplitShare> = emptyList(),
    val ignore: Boolean = false,
)

@Serializable
data class Rule(
    val name: String,
    val conditions: List<RuleCondition>,
    val matchAll: Boolean = true,
    val actions: RuleActions,
    val order: Int = 0,
    /** RU-02: first match wins unless this is set. */
    val continueAfter: Boolean = false,
    val enabled: Boolean = true,
    /** RU-03: created from corrections; [evidence] counts identical corrections seen. */
    val learned: Boolean = false,
    val evidence: Int = 0,
)

// ---------------------------------------------------------------- Budgets (BU)

@Serializable
enum class RolloverMode { NONE, POSITIVE, BOTH, CAPPED }

@Serializable
enum class TargetKind { FIXED, SAVE_BY_DATE, REFILL_UP_TO, SPEND_AT_MOST }

@Serializable
data class Target(
    val kind: TargetKind,
    val amountMinor: Long,
    @Serializable(with = LocalDateIsoSerializer::class) val by: LocalDate? = null,
)

@Serializable
enum class AlertMode { OFF, NEAR, NEAR_AND_OVER }

/**
 * BU-02: an envelope covers categories (children included) and/or tags. [limitMinor] is the default assignment per
 * period; an [Allocation] for a period replaces it, and [BudgetMove]s shift money between envelopes (BU-10).
 */
@Serializable
data class Envelope(
    val name: String,
    val categoryIds: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val limitMinor: Long = 0,
    val currency: String,
    val rollover: RolloverMode = RolloverMode.NONE,
    val rolloverCapMinor: Long? = null,
    /** BU-01 per-envelope period override (e.g. yearly insurance in a monthly budget). */
    val periodOverride: CycleRule? = null,
    val target: Target? = null,
    val alertMode: AlertMode = AlertMode.NEAR_AND_OVER,
    val archived: Boolean = false,
    val sort: Int = 0,
    val icon: String = "",
    val color: Long = 0,
    /** BU-11 festival pot: the pack festival this envelope saves for. */
    val festival: String? = null,
    /** BU-12 income smoothing buffer: fixed amount released per period into available to assign. */
    val smoothingMinor: Long? = null,
    @Serializable(with = LocalDateIsoSerializer::class) val startedOn: LocalDate? = null,
)

/** Assignment for one envelope in one period. Id convention: "<envelopeId>@<periodStart>". */
@Serializable
data class Allocation(
    val envelopeId: String,
    @Serializable(with = LocalDateIsoSerializer::class) val periodStart: LocalDate,
    val assignedMinor: Long,
    val note: String = "",
)

/** BU-10 "cover from": moves assigned money between envelopes in a period, with history. Null [fromId] = available to assign. */
@Serializable
data class BudgetMove(
    val fromId: String?,
    val toId: String?,
    @Serializable(with = LocalDateIsoSerializer::class) val periodStart: LocalDate,
    val amountMinor: Long,
    val reason: String = "",
)

// ---------------------------------------------------------------- Goals (GO)

@Serializable
enum class GoalStatus { ACTIVE, PAUSED, COMPLETED, ARCHIVED }

@Serializable
enum class GoalKind { STANDARD, EMERGENCY, FESTIVAL, CIRCLE_TURN, DEBT_FREE }

@Serializable
data class Goal(
    val name: String,
    val targetMinor: Long,
    val currency: String,
    @Serializable(with = LocalDateIsoSerializer::class) val due: LocalDate? = null,
    val linkedAccountIds: List<String> = emptyList(),
    /** GO-01 auto-contribution per budget period; posts as a goal entry on period start. */
    val autoMinor: Long? = null,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val kind: GoalKind = GoalKind.STANDARD,
    val icon: String = "",
    val notes: String = "",
    val sort: Int = 0,
    /** GO-06: months of essential spend for emergency funds. */
    val months: Int? = null,
    val spaceId: String? = null,
)

/** Contribution (positive) or withdrawal (negative) to a goal. Optional [transactionId] when money moved accounts. */
@Serializable
data class GoalEntry(
    val goalId: String,
    val amountMinor: Long,
    @Serializable(with = LocalDateIsoSerializer::class) val date: LocalDate,
    val note: String = "",
    val transactionId: String? = null,
    val memberId: String? = null,
)

// ---------------------------------------------------------------- Recurring (RC)

@Serializable
enum class AmountKind { FIXED, VARIABLE, RANGE }

@Serializable
enum class RecurringKind { BILL, SUBSCRIPTION, INCOME, EMI, TRANSFER, SAVINGS }

@Serializable
data class Recurring(
    val name: String,
    val kind: RecurringKind = RecurringKind.BILL,
    val type: TxType = TxType.EXPENSE,
    val amountMinor: Long,
    val amountKind: AmountKind = AmountKind.FIXED,
    val amountMaxMinor: Long? = null,
    val currency: String,
    val accountId: String,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val payee: String? = null,
    val note: String = "",
    val tags: List<String> = emptyList(),
    val schedule: Recurrence,
    val autoPost: Boolean = false,
    val leadDays: Int = 2,
    val paused: Boolean = false,
    /** RC-04 subscriptions. */
    @Serializable(with = LocalDateIsoSerializer::class) val lastUsed: LocalDate? = null,
    val cancelUrl: String = "",
    val loanId: String? = null,
    val payerMemberId: String? = null,
    val spaceId: String? = null,
    /** When this series was split by "edit this and future", the series it continues. */
    val continues: String? = null,
)

@Serializable
enum class OccurrenceStatus { PAID, SKIPPED, EDITED }

/**
 * RC-02: state for one occurrence. Id convention "<recurringId>@<dueDate>". Missing rows mean "upcoming".
 * EDITED overrides amount or date for this occurrence only.
 */
@Serializable
data class Occurrence(
    val recurringId: String,
    @Serializable(with = LocalDateIsoSerializer::class) val due: LocalDate,
    val status: OccurrenceStatus,
    val transactionId: String? = null,
    val amountMinor: Long? = null,
    @Serializable(with = LocalDateIsoSerializer::class) val movedTo: LocalDate? = null,
)

/** TX-16: a saved transaction shape; widgets and quick add fire it in one tap. */
@Serializable
data class Template(
    val name: String,
    val type: TxType = TxType.EXPENSE,
    val amountMinor: Long? = null,
    val currency: String,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val payee: String? = null,
    val note: String = "",
    val tags: List<String> = emptyList(),
    val favourite: Boolean = true,
    val sort: Int = 0,
)

// ---------------------------------------------------------------- Search (SR)

@Serializable
enum class SortBy { DATE, AMOUNT, PAYEE, CATEGORY, CREATED }

@Serializable
data class TxFilter(
    val text: String = "",
    @Serializable(with = LocalDateIsoSerializer::class) val from: LocalDate? = null,
    @Serializable(with = LocalDateIsoSerializer::class) val to: LocalDate? = null,
    val accountIds: List<String> = emptyList(),
    val categoryIds: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val types: List<TxType> = emptyList(),
    val statuses: List<TxStatus> = emptyList(),
    val sources: List<String> = emptyList(),
    val personIds: List<String> = emptyList(),
    val minMinor: Long? = null,
    val maxMinor: Long? = null,
    val currency: String? = null,
    val hasAttachment: Boolean? = null,
    val isPrivate: Boolean? = null,
    val uncategorised: Boolean = false,
    val sort: SortBy = SortBy.DATE,
    val ascending: Boolean = false,
) {
    val isEmpty: Boolean get() = this == TxFilter(sort = sort, ascending = ascending)
}

@Serializable
data class SavedSearch(
    val name: String,
    val filter: TxFilter,
    val pinned: Boolean = true,
    val sort: Int = 0,
    /** IN-04: chart type when used as a report ("list", "donut", "bars", "line"). */
    val chart: String = "list",
    val digest: Boolean = false,
)

// ---------------------------------------------------------------- Attachments and valuations

/** TX-11: file stored on device (encrypted directory); [blobRef] is the relative path or remote ciphertext ref. */
@Serializable
data class Attachment(
    val transactionId: String,
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    val blobRef: String,
    val ocrText: String = "",
    val sort: Int = 0,
)

/** IN-05: manual valuation history for assets (property, vehicle, gold). */
@Serializable
data class Valuation(
    val accountId: String,
    @Serializable(with = LocalDateIsoSerializer::class) val date: LocalDate,
    val valueMinor: Long,
    val note: String = "",
)

/** AC-04: manual or cached daily exchange rate, stored as a decimal string to avoid floating point in money math. */
@Serializable
data class FxRate(
    val from: String,
    val to: String,
    @Serializable(with = LocalDateIsoSerializer::class) val date: LocalDate,
    val rate: String,
    val manual: Boolean = true,
)
