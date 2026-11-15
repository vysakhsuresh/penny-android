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
)

object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor = PrimitiveSerialDescriptor("LocalTimeHm", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.withSecond(0).withNano(0).toString())
    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString())
}
