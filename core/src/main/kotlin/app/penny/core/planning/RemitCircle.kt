package app.penny.core.planning

import app.penny.core.budget.FxTable
import app.penny.core.model.Circle
import app.penny.core.model.CircleContribution
import app.penny.core.model.PayoutOrder
import app.penny.core.model.Remittance
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

data class RemitCost(
    /** Fee as basis points of the amount sent. */
    val feeBp: Int,
    /** Rate spread versus mid-market in basis points; null when the mid rate is unknown. */
    val spreadBp: Int?,
    /** Total cost in basis points (fee + spread). */
    val totalBp: Int?,
    /** What the receiver would have got at mid-market with no fee, minus what they got, in received currency. */
    val lostMinor: Long?,
)

/** RM-02 true cost; CI-02 circle rounds and payouts. */
object RemitCircle {
    private val MC = MathContext(20, RoundingMode.HALF_EVEN)

    fun cost(r: Remittance): RemitCost {
        val feeBp = if (r.sentMinor <= 0) 0 else (r.feeMinor * 10_000 / r.sentMinor).toInt()
        val mid = r.midRate?.toBigDecimalOrNull()
        val applied = r.rate.toBigDecimalOrNull()
        if (mid == null || applied == null || mid.signum() <= 0) return RemitCost(feeBp, null, null, null)
        val spread = mid.subtract(applied).divide(mid, MC).multiply(BigDecimal(10_000)).setScale(0, RoundingMode.HALF_EVEN).toInt()
        val ideal = FxTable.convertWith(r.sentMinor + r.feeMinor, r.sentCurrency, r.receivedCurrency, mid)
        return RemitCost(feeBp, spread, feeBp + spread, ideal - r.receivedMinor)
    }

    /** Average total cost per provider across remittances with a known mid rate (RM-02 comparison over time). */
    fun byProvider(list: List<Remittance>): Map<String, Int> = list.mapNotNull { r -> cost(r).totalBp?.let { r.provider to it } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.average().toInt() }

    data class Round(val n: Int, val date: LocalDate, val recipient: String, val potMinor: Long, val paidBy: Set<String>, val isMine: Boolean)

    /**
     * Rounds with dates from the schedule; the recipient follows member turns (FIXED) or the recorded payout for
     * LOTTERY/BID circles. Pot = contribution × members − fee.
     */
    fun rounds(c: Circle, contributions: List<CircleContribution>): List<Round> {
        val members = c.members.sortedBy { it.turn }
        val dates = c.schedule.sequence().take(members.size).toList()
        val pot = c.contributionMinor * members.size - c.feeMinor
        return dates.mapIndexed { i, d ->
            val n = i + 1
            val recorded = contributions.firstOrNull { it.round == n && it.payout }?.memberName
            val recipient = when (c.payoutOrder) {
                PayoutOrder.FIXED -> members.getOrNull(i)?.name
                else -> recorded
            }.orEmpty()
            val paid = contributions.filter { it.round == n && !it.payout && it.paid }.map { it.memberName }.toSet()
            Round(n, d, recipient, pot, paid, members.firstOrNull { it.name == recipient }?.isMe == true)
        }
    }

    /** CI-02: my payout round number, or null when not yet known (lottery/bid). */
    fun myTurn(c: Circle, contributions: List<CircleContribution>): Int? = rounds(c, contributions).firstOrNull { it.isMine }?.n

    /** Net position so far for me: payouts received minus contributions paid. */
    fun myNet(c: Circle, contributions: List<CircleContribution>): Long {
        val me = c.members.firstOrNull { it.isMe }?.name ?: return 0
        val paid = contributions.filter { it.memberName == me && !it.payout && it.paid }.sumOf { it.amountMinor }
        val got = contributions.filter { it.memberName == me && it.payout }.sumOf { it.amountMinor }
        return got - paid
    }
}
