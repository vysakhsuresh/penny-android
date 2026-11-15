package app.penny.core.planning

import app.penny.core.budget.BudgetEngine
import app.penny.core.model.Goal
import app.penny.core.model.GoalEntry
import app.penny.core.model.GoalStatus
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.core.period.CycleRule
import app.penny.core.period.Periods
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class GoalProgress(
    val savedMinor: Long,
    val remainingMinor: Long,
    val progressBp: Int,
    val onTrack: Boolean,
    /** GO-03: amount per budget period needed to reach the target by the due date; null without a due date. */
    val perPeriodMinor: Long?,
    val periodsLeft: Int?,
    /** Projected completion with the average contribution so far; null when nothing was saved yet. */
    val projectedDate: LocalDate?,
    val completed: Boolean,
)

object Goals {
    fun saved(goalId: String, entries: List<GoalEntry>, linkedBalance: Long = 0): Long =
        entries.filter { it.goalId == goalId }.sumOf { it.amountMinor } + linkedBalance

    fun progress(goal: Goal, saved: Long, entries: List<GoalEntry>, today: LocalDate, cycle: CycleRule): GoalProgress {
        val remaining = maxOf(0, goal.targetMinor - saved)
        val bp = if (goal.targetMinor <= 0) 10_000 else minOf(10_000L, saved * 10_000 / goal.targetMinor).toInt()
        var perPeriod: Long? = null
        var periodsLeft: Int? = null
        if (goal.due != null) {
            var p = Periods.periodFor(today, cycle)
            var n = 0
            while (!p.start.isAfter(goal.due) && n < 1_000) { n++; p = Periods.next(p, cycle) }
            periodsLeft = maxOf(1, n)
            perPeriod = BudgetEngine.ceilDiv(remaining, periodsLeft.toLong())
        }
        val dated = entries.filter { it.amountMinor > 0 }.sortedBy { it.date }
        val projected = if (dated.isNotEmpty() && remaining > 0) {
            val days = maxOf(1L, ChronoUnit.DAYS.between(dated.first().date, today))
            val perDay = saved.toDouble() / days
            if (perDay > 0) today.plusDays(kotlin.math.ceil(remaining / perDay).toLong().coerceAtMost(365L * 100)) else null
        } else if (remaining == 0L) today else null
        val onTrack = when {
            remaining == 0L -> true
            goal.due == null -> true
            projected == null -> false
            else -> !projected.isAfter(goal.due)
        }
        return GoalProgress(saved, remaining, bp, onTrack, perPeriod, periodsLeft, projected, remaining == 0L || goal.status == GoalStatus.COMPLETED)
    }

    /**
     * GO-06: N months of average essential spend over the last [lookbackMonths] full months. Essentials are the
     * category ids passed in (housing, utilities, food, health, transport, family by default in the UI).
     */
    fun emergencyTarget(txs: List<Transaction>, essentials: Set<String>, months: Int, today: LocalDate, lookbackMonths: Int = 6): Long {
        val end = today.withDayOfMonth(1).minusDays(1)
        val start = end.withDayOfMonth(1).minusMonths((lookbackMonths - 1).toLong())
        val total = txs.filter { it.type == TxType.EXPENSE && it.categoryId in essentials && !it.date.isBefore(start) && !it.date.isAfter(end) }
            .sumOf { it.amountMinor }
        return total / lookbackMonths * months
    }
}
