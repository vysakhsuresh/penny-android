package app.penny.core.model

import app.penny.core.money.Grouping
import app.penny.core.period.CycleRule
import app.penny.core.period.LocalDateIsoSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@Serializable
enum class ProfileKind { PERSONAL, BUSINESS, SUPERVISED }

/** A book (US-02). Regional behaviour comes from [regionPack]; every value it seeds stays editable (OB-02, ST-01). */
@Serializable
data class Profile(
    val name: String,
    val kind: ProfileKind = ProfileKind.PERSONAL,
    val regionPack: String,
    val baseCurrency: String,
    val localeTag: String,
    val timezone: String,
    val cycle: CycleRule,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val fiscalYearStart: String = "01-01",
    /** Null follows the regional pack; set to override (e.g. lakh grouping outside India). */
    val groupingOverride: Grouping? = null,
    val defaultAccountId: String? = null,
    /** ST-01: locale date pattern override ("" follows the device), default type for quick add, decimals shown. */
    val dateFormat: String = "",
    val defaultTxType: TxType = TxType.EXPENSE,
    val showCurrencyCode: Boolean = false,
    /** BU-05: zero-based budgeting shows "available to assign" on top. */
    val zeroBased: Boolean = false,
    /** LO-06 travel mode: suggested currency in quick add; null when off. */
    val travelCurrency: String? = null,
    val avatar: String = "",
    val sort: Int = 0,
    /** US-03: supervisor's local account id for supervised books. */
    val supervisorId: String? = null,
    /** US-04: business-book switches. */
    val business: BusinessSettings? = null,
    /** SE-05: a decoy profile opened by the duress PIN; never listed in the normal switcher. */
    val decoy: Boolean = false,
)

@Serializable
data class BusinessSettings(
    val taxTags: Boolean = true,
    val clientTags: Boolean = true,
    val mileage: Boolean = true,
    /** GST/VAT rate in basis points used to show the tax share of business spend. */
    val vatRateBp: Int = 0,
    val taxIdLabel: String = "",
)

@Serializable
enum class AccountType { CASH, BANK, E_WALLET, CREDIT_CARD, PREPAID, LOAN, RECEIVABLE, INVESTMENT, ASSET, LIABILITY, OTHER }

@Serializable
data class FinancialAccount(
    val name: String,
    val type: AccountType,
    val currency: String,
    val openingBalanceMinor: Long = 0,
    @Serializable(with = LocalDateIsoSerializer::class) val openingDate: LocalDate? = null,
    val archived: Boolean = false,
    val includeInNetWorth: Boolean = true,
    val includeInBudget: Boolean = true,
    val sort: Int = 0,
    /** AC-02: custom group name; null groups by type. */
    val group: String? = null,
    val institution: String = "",
    val note: String = "",
    /** AC-05. */
    val card: CardFields? = null,
    /** AC-07: a pass-through wallet that posts to this real account ("via"). */
    val aliasOf: String? = null,
    /** AC-09 / CP-10: link to a bank connection; null is a manual account. */
    val syncLinkId: String? = null,
    /** IN-05: assets valued by weight (gold) use grams × daily pack price. */
    val weightGrams: Double? = null,
    val icon: String = "",
)

/** AC-05: credit card details. Days are 1..31 (clamped to month end); rates are basis points (1% = 100). */
@Serializable
data class CardFields(
    val limitMinor: Long = 0,
    val statementDay: Int = 1,
    val dueDay: Int = 20,
    val minDueBp: Int = 500,
    val aprBp: Int = 0,
)

@Serializable
enum class CategoryKind { EXPENSE, INCOME }

@Serializable
data class Category(
    /** Stable key from the default tree (e.g. "food.groc"); null for user-created categories. */
    val key: String? = null,
    val kind: CategoryKind,
    val name: String,
    val parentId: String? = null,
    /** Excluded from spend (Transfers, Adjustments, Savings), CAT-04. */
    val excludedFromSpend: Boolean = false,
    val budgetable: Boolean = true,
    val archived: Boolean = false,
    val sort: Int = 0,
    val icon: String = "",
    /** ARGB colour; 0 lets the UI derive a stable colour from the id (entity colours stay consistent, IN-02). */
    val color: Long = 0,
    /** CAT-05 per-category defaults. */
    val defaultAccountId: String? = null,
    val defaultTags: List<String> = emptyList(),
    val tax: Boolean = false,
)

@Serializable
enum class TxType { EXPENSE, INCOME, TRANSFER, REFUND, ADJUSTMENT, OPENING_BALANCE }

@Serializable
enum class TxStatus { PENDING, CLEARED, RECONCILED }

@Serializable
enum class Direction { IN, OUT }

@Serializable
data class Split(
    val amountMinor: Long,
    val categoryId: String? = null,
    val tags: List<String> = emptyList(),
    val note: String = "",
    val personId: String? = null,
)

@Serializable
data class GeoPoint(val lat: Double, val lon: Double, val label: String? = null)

/**
 * Transaction (SPEC §6, TX-01, TX-02). Every field is editable after creation. [date] is the calendar date in the
 * profile timezone [tz] when it was entered; period attribution uses it, so travel never re-attributes (LO-06).
 */
@Serializable
data class Transaction(
    val type: TxType,
    val amountMinor: Long,
    val currency: String,
    val accountId: String,
    val toAccountId: String? = null,
    val toAmountMinor: Long? = null,
    val toCurrency: String? = null,
    val categoryId: String? = null,
    val payee: String? = null,
    @Serializable(with = LocalDateIsoSerializer::class) val date: LocalDate,
    @Serializable(with = LocalTimeSerializer::class) val time: LocalTime? = null,
    val tz: String,
    val note: String = "",
    val tags: List<String> = emptyList(),
    val status: TxStatus = TxStatus.CLEARED,
    /** Adjustments only: whether the correction adds to or takes from the balance. */
    val direction: Direction? = null,
    /** Refunds only: the original expense. */
    val refundOf: String? = null,
    val splits: List<Split> = emptyList(),
    val attachmentIds: List<String> = emptyList(),
    val location: GeoPoint? = null,
    val recurringId: String? = null,
    val groupExpenseId: String? = null,
    val lendingId: String? = null,
    val remittanceId: String? = null,
    val circleId: String? = null,
    val isPrivate: Boolean = false,
    val excludeFromBudget: Boolean = false,
    val excludeFromReports: Boolean = false,
    val source: String = "manual",
    /** PY-01: the payee entity; [payee] keeps the display text as seen. */
    val payeeId: String? = null,
    /** AC-07: the wallet the money moved through when it posts to a real account. */
    val viaAccountId: String? = null,
    /**
     * AC-04: when [currency] differs from the account's currency (a foreign spend on a card, money sent abroad), the
     * amount in the account's currency. Balances use it; without it a foreign amount was added as if it were local.
     */
    val accountAmountMinor: Long? = null,
    /** CP-04: capture confidence 0..100; null for manual entries. */
    val confidence: Int? = null,
    /** CP-11: ids from capture sources (notification key, bank id, import row hash) kept for dedupe. */
    val externalIds: List<String> = emptyList(),
    /** US-09: mirrored to a space envelope. */
    val spaceId: String? = null,
    /** Business book (US-04): kilometres or miles travelled. */
    val distance: Double? = null,
    /** Space member who made the spend (IN-07); null is the profile owner. */
    val memberId: String? = null,
    val goalId: String? = null,
    val loanId: String? = null,
)

object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor = PrimitiveSerialDescriptor("LocalTimeHm", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.withSecond(0).withNano(0).toString())
    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString())
}
