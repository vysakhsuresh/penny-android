package app.penny.core.budget

import app.penny.core.model.Direction
import app.penny.core.model.FinancialAccount
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.core.period.Period
import app.penny.core.period.Periods
import java.time.LocalDate

/** Converts between currencies in minor units; returns null when no rate is known (the amount is then reported, not guessed). */
fun interface FxConverter {
    fun convert(minor: Long, from: String, to: String, on: LocalDate): Long?

    companion object {
        val SameCurrencyOnly = FxConverter { minor, from, to, _ -> if (from == to) minor else null }
    }
}

object Ledger {
    /** Balance effects of one transaction: (accountId, signed minor units in that account's currency). */
    fun effects(t: Transaction): List<Pair<String, Long>> = when (t.type) {
        TxType.EXPENSE -> listOf(t.accountId to -t.amountMinor)
        TxType.INCOME, TxType.REFUND, TxType.OPENING_BALANCE -> listOf(t.accountId to t.amountMinor)
        TxType.ADJUSTMENT -> listOf(t.accountId to if (t.direction == Direction.OUT) -t.amountMinor else t.amountMinor)
        TxType.TRANSFER -> listOfNotNull(
            t.accountId to -t.amountMinor,
            t.toAccountId?.let { it to (t.toAmountMinor ?: t.amountMinor) },
        )
    }

    fun balance(accountId: String, account: FinancialAccount, txs: Iterable<Transaction>, asOf: LocalDate? = null): Long =
        txs.filter { asOf == null || !it.date.isAfter(asOf) }
            .flatMap(::effects)
            .filter { it.first == accountId }
            .fold(account.openingBalanceMinor) { acc, (_, d) -> Math.addExact(acc, d) }
}

data class SafeToSpend(
    val period: Period,
    val currency: String,
    val incomeMinor: Long,
    val expectedIncomeMinor: Long,
    val committedMinor: Long,
    val savingsMinor: Long,
    val spentMinor: Long,
    val safeMinor: Long,
    val perDayMinor: Long,
    val daysLeft: Int,
    val daysElapsed: Int,
    /** AL-02: after day 3, spend share exceeds elapsed share by 15 points or more. Shown amber, never red. */
    val aheadOfPace: Boolean,
    /** Transactions skipped because no exchange rate was available; surfaced, never silently dropped. */
    val unconvertedCount: Int,
)

/**
 * BU-07: safe-to-spend = income (actual + expected scheduled) − committed scheduled bills − assigned savings − spent.
 * M0 has no recurring or goals, so expected, committed and savings are inputs that default to zero.
 */
object SafeToSpendCalculator {
    fun compute(
        txs: List<Transaction>,
        period: Period,
        today: LocalDate,
        baseCurrency: String,
        excludedCategoryIds: Set<String>,
        fx: FxConverter = FxConverter.SameCurrencyOnly,
        expectedIncomeMinor: Long = 0,
        committedMinor: Long = 0,
        savingsMinor: Long = 0,
    ): SafeToSpend {
        var income = 0L
        var spent = 0L
        var unconverted = 0
        txs.filter { it.date in period && !it.excludeFromBudget }.forEach { t ->
            val sign = when (t.type) {
                TxType.INCOME -> 0
                TxType.EXPENSE -> 1
                TxType.REFUND -> -1
                else -> return@forEach
            }
            if (sign != 0 && t.categoryId != null && t.categoryId in excludedCategoryIds) return@forEach
            val base = fx.convert(t.amountMinor, t.currency, baseCurrency, t.date)
            if (base == null) { unconverted++; return@forEach }
            if (t.type == TxType.INCOME) income = Math.addExact(income, base) else spent = Math.addExact(spent, sign * base)
        }
        val totalIncome = income + expectedIncomeMinor
        val safe = totalIncome - committedMinor - savingsMinor - spent
        val (elapsed, left) = Periods.progress(period, today)
        val daysForBudget = if (today.isBefore(period.start)) period.days else left + 1
        val perDay = if (safe <= 0) 0 else safe / maxOf(1, daysForBudget)
        val ahead = elapsed > 3 && totalIncome > 0 &&
            spent.toDouble() / totalIncome > elapsed.toDouble() / period.days + 0.15
        return SafeToSpend(
            period, baseCurrency, income, expectedIncomeMinor, committedMinor, savingsMinor, spent, safe, perDay,
            daysLeft = left, daysElapsed = elapsed, aheadOfPace = ahead, unconvertedCount = unconverted,
        )
    }
}
