package app.penny.core.insights

import app.penny.core.budget.FxConverter
import app.penny.core.budget.Ledger
import app.penny.core.model.FinancialAccount
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.core.model.Valuation
import app.penny.core.period.CycleRule
import app.penny.core.period.Period
import app.penny.core.period.PeriodContext
import app.penny.core.period.Periods
import java.time.LocalDate

data class Slice(val key: String, val amountMinor: Long)

data class PeriodSummary(
    val period: Period,
    val incomeMinor: Long,
    val spentMinor: Long,
    val netMinor: Long,
    val byCategory: List<Slice>,
    val byPayee: List<Slice>,
    val byTag: List<Slice>,
    val byMember: List<Slice>,
    val previousSpentMinor: Long,
    val lastYearSpentMinor: Long,
    val count: Int,
)

data class Flow(val from: String, val to: String, val amountMinor: Long)

/**
 * IN-01…IN-07. Spend = expenses − refunds, excluding transfers, excluded categories and "leave out of reports".
 * Amounts are converted to [base] on each transaction date; unconvertible ones are skipped and counted.
 */
class Insights(
    private val txs: List<Transaction>,
    private val base: String,
    private val excludedCategoryIds: Set<String>,
    private val fx: FxConverter,
    /** Maps a category id to its top-level parent id for rolled-up views. */
    private val topLevel: (String?) -> String? = { it },
) {
    private fun reportable(t: Transaction) = !t.excludeFromReports

    private fun spendLines(t: Transaction): List<Pair<String?, Long>> {
        val sign = when (t.type) {
            TxType.EXPENSE -> 1L
            TxType.REFUND -> -1L
            else -> return emptyList()
        }
        val lines = if (t.splits.isNotEmpty()) t.splits.map { it.categoryId to it.amountMinor } else listOf(t.categoryId to t.amountMinor)
        return lines.filter { it.first == null || it.first !in excludedCategoryIds }.mapNotNull { (c, a) ->
            fx.convert(a, t.currency, base, t.date)?.let { c to sign * it }
        }
    }

    fun spent(p: Period): Long = txs.filter { it.date in p && reportable(it) }.sumOf { t -> spendLines(t).sumOf { it.second } }

    fun income(p: Period): Long = txs.filter { it.date in p && reportable(it) && it.type == TxType.INCOME && it.categoryId !in excludedCategoryIds }
        .sumOf { fx.convert(it.amountMinor, it.currency, base, it.date) ?: 0 }

    fun summary(p: Period, rule: CycleRule, ctx: PeriodContext = PeriodContext.EMPTY, rollUp: Boolean = true): PeriodSummary {
        val inP = txs.filter { it.date in p && reportable(it) }
        val cat = linkedMapOf<String, Long>()
        val payee = linkedMapOf<String, Long>()
        val tag = linkedMapOf<String, Long>()
        val member = linkedMapOf<String, Long>()
        inP.forEach { t ->
            spendLines(t).forEach { (c, a) ->
                val key = (if (rollUp) topLevel(c) else c) ?: UNCATEGORISED
                cat[key] = (cat[key] ?: 0) + a
                t.payee?.takeIf { it.isNotBlank() }?.let { payee[it] = (payee[it] ?: 0) + a }
                t.tags.forEach { tg -> tag[tg] = (tag[tg] ?: 0) + a }
                val m = t.memberId ?: ME
                member[m] = (member[m] ?: 0) + a
            }
        }
        val prev = Periods.previous(p, rule, ctx)
        val ly = Period(p.start.minusYears(1), p.endInclusive.minusYears(1))
        val inc = income(p)
        val sp = spent(p)
        fun top(m: Map<String, Long>) = m.filter { it.value != 0L }.map { Slice(it.key, it.value) }.sortedByDescending { it.amountMinor }
        return PeriodSummary(p, inc, sp, inc - sp, top(cat), top(payee), top(tag), top(member), spent(prev), spent(ly), inP.size)
    }

    /** IN-02 trend bars: spend and income for the last [n] periods, oldest first. */
    fun trend(current: Period, rule: CycleRule, n: Int, ctx: PeriodContext = PeriodContext.EMPTY): List<Triple<Period, Long, Long>> {
        val out = ArrayDeque<Triple<Period, Long, Long>>()
        var p = current
        repeat(n) {
            out.addFirst(Triple(p, spent(p), income(p)))
            p = Periods.previous(p, rule, ctx)
        }
        return out.toList()
    }

    /** IN-02 calendar heatmap: spend per day. */
    fun daily(p: Period): Map<LocalDate, Long> {
        val out = linkedMapOf<LocalDate, Long>()
        var d = p.start
        while (!d.isAfter(p.endInclusive)) { out[d] = 0; d = d.plusDays(1) }
        txs.filter { it.date in p && reportable(it) }.forEach { t -> out[t.date] = (out[t.date] ?: 0) + spendLines(t).sumOf { it.second } }
        return out
    }

    /** IN-02 Sankey: income sources → spend categories, with "Saved" as the remainder (or "From savings" when negative). */
    fun flows(p: Period, incomeLabel: (String?) -> String, categoryLabel: (String?) -> String): List<Flow> {
        val inc = txs.filter { it.date in p && reportable(it) && it.type == TxType.INCOME }
            .groupBy { incomeLabel(it.categoryId) }.mapValues { (_, l) -> l.sumOf { fx.convert(it.amountMinor, it.currency, base, it.date) ?: 0 } }
        val spend = summary(p, CycleRule.Monthly(1)).byCategory
        val totalIn = inc.values.sum()
        val out = mutableListOf<Flow>()
        inc.forEach { (k, v) -> out += Flow(k, BUDGET, v) }
        spend.forEach { s -> if (s.amountMinor > 0) out += Flow(BUDGET, categoryLabel(s.key.takeIf { it != UNCATEGORISED }), s.amountMinor) }
        val saved = totalIn - spend.sumOf { it.amountMinor }
        if (saved > 0) out += Flow(BUDGET, SAVED, saved) else if (saved < 0) out += Flow(FROM_SAVINGS, BUDGET, -saved)
        return out
    }

    companion object {
        const val UNCATEGORISED = "_uncat"
        const val ME = "_me"
        const val BUDGET = "_budget"
        const val SAVED = "_saved"
        const val FROM_SAVINGS = "_from_savings"
    }
}

/** IN-05 / AC-03 net worth and balance history. */
object NetWorth {
    /** Account value on a date: ledger balance, or latest valuation (assets), or grams × price (gold). */
    fun value(
        id: String,
        a: FinancialAccount,
        txs: List<Transaction>,
        on: LocalDate,
        valuations: List<Valuation> = emptyList(),
        goldPricePerGramMinor: Long? = null,
    ): Long {
        val v = valuations.filter { it.accountId == id && !it.date.isAfter(on) }.maxByOrNull { it.date }
        if (a.weightGrams != null && goldPricePerGramMinor != null) return (a.weightGrams * goldPricePerGramMinor).toLong()
        val ledger = Ledger.balance(id, a, txs, on)
        return if (v != null) v.valueMinor + txs.filter { it.date.isAfter(v.date) && !it.date.isAfter(on) }.flatMap(Ledger::effects).filter { it.first == id }.sumOf { it.second } else ledger
    }

    fun total(
        accounts: List<Pair<String, FinancialAccount>>,
        txs: List<Transaction>,
        on: LocalDate,
        base: String,
        fx: FxConverter,
        valuations: List<Valuation> = emptyList(),
    ): Pair<Long, Int> {
        var sum = 0L
        var missing = 0
        accounts.filter { it.second.includeInNetWorth && (it.second.openingDate == null || !it.second.openingDate!!.isAfter(on)) }.forEach { (id, a) ->
            val v = value(id, a, txs, on, valuations)
            val c = fx.convert(v, a.currency, base, on)
            if (c == null) missing++ else sum = Math.addExact(sum, c)
        }
        return sum to missing
    }

    /** Net worth at the end of each of the last [months] months, oldest first. */
    fun history(accounts: List<Pair<String, FinancialAccount>>, txs: List<Transaction>, today: LocalDate, months: Int, base: String, fx: FxConverter, valuations: List<Valuation> = emptyList()): List<Pair<LocalDate, Long>> =
        (months - 1 downTo 0).map { i ->
            val d = if (i == 0) today else today.minusMonths(i.toLong()).withDayOfMonth(1).plusMonths(1).minusDays(1)
            d to total(accounts, txs, d, base, fx, valuations).first
        }
}

/** IN-06 forecast: balance projection from scheduled items and run-rate, with scenario cuts per category. */
object Forecast {
    data class Point(val date: LocalDate, val balanceMinor: Long)

    /**
     * Starts at [startBalance] today; each day subtracts the average daily discretionary spend (last 90 days, less any
     * scenario cut by category in basis points) and applies scheduled items on their dates.
     */
    fun project(
        startBalance: Long,
        today: LocalDate,
        days: Int,
        history: List<Transaction>,
        scheduled: List<Pair<LocalDate, Long>>,
        excludedCategoryIds: Set<String>,
        cutsBp: Map<String, Int> = emptyMap(),
        recurringIds: Set<String> = emptySet(),
    ): List<Point> {
        val from = today.minusDays(90)
        val discretionary = history.filter {
            it.type == TxType.EXPENSE && !it.date.isBefore(from) && it.date.isBefore(today) &&
                it.recurringId == null && (it.categoryId == null || it.categoryId !in excludedCategoryIds)
        }
        val perDay = discretionary.sumOf { t -> t.amountMinor * (10_000 - (cutsBp[t.categoryId] ?: 0)) / 10_000 } / 90
        val bySched = scheduled.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        var bal = startBalance
        return (1..days).map { i ->
            val d = today.plusDays(i.toLong())
            bal += (bySched[d] ?: 0) - perDay
            Point(d, bal)
        }
    }

    /** AL-09: projected shortfall at period end at the current pace (spent so far scaled to the full period). */
    fun endOfPeriodShort(spentSoFar: Long, budget: Long, elapsedDays: Int, totalDays: Int): Long {
        if (elapsedDays <= 0) return 0
        val projected = spentSoFar * totalDays / elapsedDays
        return maxOf(0, projected - budget)
    }
}
