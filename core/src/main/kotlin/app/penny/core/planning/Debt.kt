package app.penny.core.planning

import app.penny.core.model.Loan
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

data class Installment(
    val n: Int,
    val date: LocalDate,
    val paymentMinor: Long,
    val principalMinor: Long,
    val interestMinor: Long,
    val balanceMinor: Long,
)

data class LoanSummary(
    val emiMinor: Long,
    val schedule: List<Installment>,
    val totalInterestMinor: Long,
    val interestPaidToDateMinor: Long,
    val principalPaidToDateMinor: Long,
    val remainingMinor: Long,
    val endDate: LocalDate?,
)

/** RC-05 amortisation and DB-02 payoff planning. Interest per month = balance × APR / 12, rounded half-even to minor units. */
object Debt {
    private val MC = MathContext(20, RoundingMode.HALF_EVEN)

    /** Standard EMI = P·r(1+r)^n / ((1+r)^n − 1); zero-rate loans split evenly. */
    fun emi(principal: Long, aprBp: Int, months: Int): Long {
        if (months <= 0) return principal
        if (aprBp == 0) return -Math.floorDiv(-principal, months.toLong())
        val r = BigDecimal(aprBp).divide(BigDecimal(120_000), MC)
        val f = BigDecimal.ONE.add(r).pow(months, MC)
        val emi = BigDecimal(principal).multiply(r, MC).multiply(f, MC).divide(f.subtract(BigDecimal.ONE), MC)
        return emi.setScale(0, RoundingMode.CEILING).toLong()
    }

    private fun interest(balance: Long, aprBp: Int): Long =
        BigDecimal(balance).multiply(BigDecimal(aprBp)).divide(BigDecimal(120_000), 0, RoundingMode.HALF_EVEN).toLong()

    fun schedule(loan: Loan, extraOverride: Long? = null, maxMonths: Int = 1_200): List<Installment> {
        val pay = emi(loan.principalMinor, loan.aprBp, loan.tenureMonths)
        val extra = extraOverride ?: loan.extraMinor
        var bal = loan.principalMinor
        val out = mutableListOf<Installment>()
        var n = 1
        while (bal > 0 && n <= maxMonths) {
            val date = loan.start.plusMonths(n.toLong())
            loan.prepayments.filter { !it.date.isAfter(date) && it.date.isAfter(date.minusMonths(1)) }.forEach { bal = maxOf(0, bal - it.amountMinor) }
            if (bal == 0L) break
            val i = interest(bal, loan.aprBp)
            var principal = minOf(bal, pay + extra - i)
            if (principal <= 0) principal = minOf(bal, 1) // negative amortisation guard: the plan always ends
            bal -= principal
            out += Installment(n, date, principal + i, principal, i, bal)
            n++
        }
        return out
    }

    fun summary(loan: Loan, today: LocalDate): LoanSummary {
        val s = schedule(loan)
        val paid = s.filter { !it.date.isAfter(today) }
        return LoanSummary(
            emiMinor = emi(loan.principalMinor, loan.aprBp, loan.tenureMonths),
            schedule = s,
            totalInterestMinor = s.sumOf { it.interestMinor },
            interestPaidToDateMinor = paid.sumOf { it.interestMinor },
            principalPaidToDateMinor = paid.sumOf { it.principalMinor } + loan.prepayments.filter { !it.date.isAfter(today) }.sumOf { it.amountMinor },
            remainingMinor = paid.lastOrNull()?.balanceMinor ?: loan.principalMinor,
            endDate = s.lastOrNull()?.date,
        )
    }

    /** RC-05 prepayment what-if: months and interest saved by paying [amount] once on [date]. */
    fun prepaymentWhatIf(loan: Loan, date: LocalDate, amount: Long): Pair<Int, Long> {
        val base = schedule(loan)
        val withPre = schedule(loan.copy(prepayments = loan.prepayments + app.penny.core.model.Prepayment(date, amount)))
        return (base.size - withPre.size) to (base.sumOf { it.interestMinor } - withPre.sumOf { it.interestMinor })
    }

    enum class Strategy { AVALANCHE, SNOWBALL, CUSTOM }

    data class Debtor(val id: String, val name: String, val balanceMinor: Long, val aprBp: Int, val minimumMinor: Long, val order: Int = 0)

    data class PayoffPlan(val months: Int, val totalInterestMinor: Long, val debtFreeDate: LocalDate, val payoffOrder: List<String>, val balances: List<Long>)

    /**
     * DB-02: pay minimums on all, then put [extra] (plus minimums freed from paid-off debts) on the target debt chosen
     * by the strategy. Returns months, interest, debt-free date and the total balance per month for the chart.
     */
    fun payoff(debts: List<Debtor>, extra: Long, strategy: Strategy, start: LocalDate, maxMonths: Int = 600): PayoffPlan {
        val bal = debts.associate { it.id to it.balanceMinor }.toMutableMap()
        val order = when (strategy) {
            Strategy.AVALANCHE -> debts.sortedWith(compareByDescending<Debtor> { it.aprBp }.thenBy { it.balanceMinor })
            Strategy.SNOWBALL -> debts.sortedWith(compareBy<Debtor> { it.balanceMinor }.thenByDescending { it.aprBp })
            Strategy.CUSTOM -> debts.sortedBy { it.order }
        }
        val paidOff = mutableListOf<String>()
        val totals = mutableListOf<Long>()
        var interest = 0L
        var m = 0
        while (bal.values.any { it > 0 } && m < maxMonths) {
            m++
            var pool = extra
            for (d in debts) {
                val b = bal.getValue(d.id)
                if (b <= 0) { pool += d.minimumMinor; continue }
                val i = interest(b, d.aprBp)
                interest += i
                val nb = b + i
                val pay = minOf(nb, maxOf(d.minimumMinor, 1))
                bal[d.id] = nb - pay
                if (pay < d.minimumMinor) pool += d.minimumMinor - pay
            }
            for (d in order) {
                if (pool <= 0) break
                val b = bal.getValue(d.id)
                if (b <= 0) continue
                val pay = minOf(b, pool)
                bal[d.id] = b - pay
                pool -= pay
            }
            order.filter { bal.getValue(it.id) <= 0 && it.id !in paidOff }.forEach { paidOff += it.id }
            totals += bal.values.sum()
        }
        return PayoffPlan(m, interest, start.plusMonths(m.toLong()), paidOff, totals)
    }

    /** AC-05 card cycle: statement window and due date for a card with statement day S and due day D. */
    data class CardCycle(val statementDate: LocalDate, val dueDate: LocalDate, val nextStatement: LocalDate)

    fun cardCycle(statementDay: Int, dueDay: Int, today: LocalDate): CardCycle {
        fun on(month: LocalDate, day: Int) = month.withDayOfMonth(minOf(day, month.lengthOfMonth()))
        val thisMonth = on(today, statementDay)
        val lastStatement = if (!today.isBefore(thisMonth)) thisMonth else on(today.minusMonths(1), statementDay)
        val due = on(if (dueDay > statementDay) lastStatement else lastStatement.plusMonths(1), dueDay)
        return CardCycle(lastStatement, due, on(lastStatement.plusMonths(1), statementDay))
    }
}
