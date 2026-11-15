package app.penny.core.model

import app.penny.core.period.LocalDateIsoSerializer
import app.penny.core.schedule.Recurrence
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** PE-05: a counterparty. Contact link is optional and permission-gated; free text otherwise. */
@Serializable
data class Person(
    val name: String,
    val phone: String = "",
    val email: String = "",
    val contactRef: String? = null,
    val note: String = "",
    /** Payment handle for settle-up deep links (UPI id, GCash number, PayPal.me...). */
    val payHandle: String = "",
    val archived: Boolean = false,
    /** Linked Penny account id when the person is a user (groups and spaces). */
    val accountId: String? = null,
)

@Serializable
enum class LendDirection { GAVE, RECEIVED }

/**
 * PE-01: one lending entry. GAVE = money went to the person (they owe more); RECEIVED = money came from them.
 * A settlement is an entry in the opposite direction; [settles] links it to the entry it pays down.
 */
@Serializable
data class LendingEntry(
    val personId: String,
    val direction: LendDirection,
    val amountMinor: Long,
    val currency: String,
    @Serializable(with = LocalDateIsoSerializer::class) val date: LocalDate,
    @Serializable(with = LocalDateIsoSerializer::class) val due: LocalDate? = null,
    /** Simple annual interest in basis points. */
    val interestBp: Int = 0,
    val note: String = "",
    val attachmentIds: List<String> = emptyList(),
    val accountId: String? = null,
    val transactionId: String? = null,
    val settles: String? = null,
    val closed: Boolean = false,
)

@Serializable
enum class SplitMode { EQUAL, EXACT, PERCENT, SHARES, ITEMS }

/** PE-03 / US-10. Member ids are [Person] ids; [ME] is the profile owner. */
@Serializable
data class Group(
    val name: String,
    val baseCurrency: String,
    val memberIds: List<String>,
    val archived: Boolean = false,
    val simplify: Boolean = true,
    val icon: String = "",
) {
    companion object { const val ME = "me" }
}

@Serializable
data class ShareLine(val memberId: String, val value: Long)

@Serializable
data class ItemLine(val label: String, val amountMinor: Long, val memberIds: List<String>)

@Serializable
data class GroupExpense(
    val groupId: String,
    val description: String,
    val payerId: String,
    val amountMinor: Long,
    val currency: String,
    @Serializable(with = LocalDateIsoSerializer::class) val date: LocalDate,
    val mode: SplitMode = SplitMode.EQUAL,
    /** EQUAL: participants with value 1; EXACT: minor units; PERCENT: basis points; SHARES: share counts. */
    val shares: List<ShareLine>,
    val items: List<ItemLine> = emptyList(),
    val categoryId: String? = null,
    val note: String = "",
    val disputed: Boolean = false,
    val disputeNote: String = "",
    val recurrence: Recurrence? = null,
    /** FX rate to the group base currency as a decimal string, when the currency differs. */
    val rateToBase: String? = null,
    val transactionId: String? = null,
)

@Serializable
data class Settlement(
    val groupId: String?,
    val fromId: String,
    val toId: String,
    val amountMinor: Long,
    val currency: String,
    @Serializable(with = LocalDateIsoSerializer::class) val date: LocalDate,
    val method: String = "",
    val transactionId: String? = null,
    val note: String = "",
)

// ---------------------------------------------------------------- Loans and debt (RC-05, DB)

@Serializable
enum class LoanKind { EMI, PERSONAL, MORTGAGE, VEHICLE, EDUCATION, PAY_LATER, INFORMAL, CARD }

@Serializable
data class Loan(
    val name: String,
    val kind: LoanKind = LoanKind.EMI,
    val principalMinor: Long,
    val currency: String,
    val aprBp: Int,
    val tenureMonths: Int,
    @Serializable(with = LocalDateIsoSerializer::class) val start: LocalDate,
    val lender: String = "",
    val accountId: String? = null,
    val paidFromAccountId: String? = null,
    /** DB-02 fixed extra paid each month. */
    val extraMinor: Long = 0,
    val prepayments: List<Prepayment> = emptyList(),
    val penaltyNote: String = "",
    val closed: Boolean = false,
    /** Payoff planner order for the custom strategy. */
    val order: Int = 0,
)

@Serializable
data class Prepayment(
    @Serializable(with = LocalDateIsoSerializer::class) val date: LocalDate,
    val amountMinor: Long,
)

// ---------------------------------------------------------------- Remittances and circles (RM, CI)

@Serializable
enum class RemitStatus { SENT, RECEIVED, CONFIRMED }

@Serializable
data class Remittance(
    val sender: String,
    val receiver: String,
    val receiverPersonId: String? = null,
    val sentMinor: Long,
    val sentCurrency: String,
    val feeMinor: Long = 0,
    /** Provider's applied rate as a decimal string (received per 1 unit sent). */
    val rate: String,
    val receivedMinor: Long,
    val receivedCurrency: String,
    /** Mid-market rate of the day for true cost (RM-02); null when unknown. */
    val midRate: String? = null,
    val provider: String,
    val purpose: String = "",
    @Serializable(with = LocalDateIsoSerializer::class) val date: LocalDate,
    val status: RemitStatus = RemitStatus.SENT,
    val outgoing: Boolean = true,
    val transactionId: String? = null,
    val spaceId: String? = null,
)

@Serializable
enum class PayoutOrder { FIXED, LOTTERY, BID }

@Serializable
data class CircleMember(val name: String, val personId: String? = null, val turn: Int, val isMe: Boolean = false)

@Serializable
data class Circle(
    val name: String,
    /** CI-01 the regional name ("Paluwagan", "Chit fund"…) from the pack. */
    val localName: String = "",
    val members: List<CircleMember>,
    val contributionMinor: Long,
    val currency: String,
    val schedule: Recurrence,
    val payoutOrder: PayoutOrder = PayoutOrder.FIXED,
    val feeMinor: Long = 0,
    val accountId: String? = null,
    val closed: Boolean = false,
    val goalId: String? = null,
)

/** One contribution by a member for a round, or the payout ([payout] = true) to the member whose turn it is. */
@Serializable
data class CircleContribution(
    val circleId: String,
    val round: Int,
    val memberName: String,
    val amountMinor: Long,
    @Serializable(with = LocalDateIsoSerializer::class) val date: LocalDate,
    val paid: Boolean = true,
    val payout: Boolean = false,
    val transactionId: String? = null,
)
