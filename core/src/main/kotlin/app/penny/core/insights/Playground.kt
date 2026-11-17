package app.penny.core.insights

import app.penny.core.period.Period
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Light, friendly facts about a period's spending, from the per-day totals (days after today are ignored). */
data class FunFacts(
    val daysCounted: Int,
    val avgPerDayMinor: Long,
    val biggestDay: LocalDate?,
    val biggestDayMinor: Long,
    /** Average spend per weekday over the counted days (weekdays with no counted day are absent). */
    val weekdayAvgMinor: Map<DayOfWeek, Long>,
    val busiestWeekday: DayOfWeek?,
    val noSpendDays: Int,
    /** Days in a row without spending, ending today (or the last counted day). */
    val currentStreak: Int,
    val bestStreak: Int,
)

object Playground {
    fun facts(daily: Map<LocalDate, Long>, today: LocalDate): FunFacts {
        val days = daily.filterKeys { !it.isAfter(today) }.toSortedMap()
        val n = days.size
        val total = days.values.sum()
        val biggest = days.entries.filter { it.value > 0 }.maxByOrNull { it.value }
        val byWeekday = days.entries.groupBy { it.key.dayOfWeek }.mapValues { (_, l) -> avg(l.sumOf { it.value }, l.size) }
        var best = 0
        var run = 0
        days.values.forEach { v -> if (v <= 0) { run++; best = maxOf(best, run) } else run = 0 }
        return FunFacts(
            daysCounted = n,
            avgPerDayMinor = avg(total, n),
            biggestDay = biggest?.key,
            biggestDayMinor = biggest?.value ?: 0,
            weekdayAvgMinor = byWeekday,
            busiestWeekday = byWeekday.filter { it.value > 0 }.maxByOrNull { it.value }?.key,
            noSpendDays = days.values.count { it <= 0 },
            currentStreak = run,
            bestStreak = best,
        )
    }

    /**
     * The period just before [p] with the same shape: whole months step back by months (so February compares with
     * January, not with 28 days of January), anything else by its number of days.
     */
    fun previous(p: Period): Period {
        val endExclusive = p.endInclusive.plusDays(1)
        val months = ChronoUnit.MONTHS.between(p.start, endExclusive)
        return if (months > 0 && p.start.plusMonths(months) == endExclusive) {
            val s = p.start.minusMonths(months)
            Period(s, p.start.minusDays(1))
        } else {
            val len = ChronoUnit.DAYS.between(p.start, endExclusive)
            Period(p.start.minusDays(len), p.start.minusDays(1))
        }
    }

    /** [total] / [n] rounded half-even to the minor unit (plain Long division truncated 66.666 to 66.66). */
    internal fun avg(total: Long, n: Int): Long =
        if (n == 0) 0 else java.math.BigDecimal(total).divide(java.math.BigDecimal(n), 0, java.math.RoundingMode.HALF_EVEN).toLong()

    /** What putting aside [perDayMinor] every day adds up to over [days] days (no interest assumed). */
    fun setAside(perDayMinor: Long, days: Int): Long = perDayMinor * days
}
