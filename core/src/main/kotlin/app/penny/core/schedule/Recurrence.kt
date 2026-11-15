package app.penny.core.schedule

import app.penny.core.period.LocalDateIsoSerializer
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

@Serializable
enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * RC-01: the RRULE subset people use for bills and incomes. Daily/weekly/monthly/yearly every [interval];
 * weekly on [byWeekday]; monthly on [byMonthDay] (−1 = last day, clamped to month end) or on the [setPos]-th
 * [byWeekday] (e.g. 2nd Tuesday, −1 = last Friday); ends at [until] or after [count] occurrences.
 */
@Serializable
data class Recurrence(
    val freq: Freq = Freq.MONTHLY,
    val interval: Int = 1,
    @Serializable(with = LocalDateIsoSerializer::class) val start: LocalDate,
    @Serializable(with = LocalDateIsoSerializer::class) val until: LocalDate? = null,
    val count: Int? = null,
    val byWeekday: List<DayOfWeek> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val setPos: Int? = null,
) {
    init {
        require(interval >= 1) { "interval must be >= 1" }
        require(count == null || count >= 1) { "count must be >= 1" }
        require(byMonthDay.all { it in -1..31 && it != 0 }) { "byMonthDay must be 1..31 or -1" }
        require(setPos == null || setPos in listOf(-1, 1, 2, 3, 4, 5)) { "setPos must be 1..5 or -1" }
    }

    /** Occurrence dates in [from, to] inclusive. Counting always starts from [start], so [count] is stable. */
    fun between(from: LocalDate, to: LocalDate): List<LocalDate> {
        if (to.isBefore(from)) return emptyList()
        val out = mutableListOf<LocalDate>()
        var n = 0
        for (d in sequence()) {
            if (until != null && d.isAfter(until)) break
            if (d.isAfter(to)) break
            n++
            if (count != null && n > count) break
            if (!d.isBefore(from)) out += d
        }
        return out
    }

    fun next(after: LocalDate): LocalDate? {
        var n = 0
        for (d in sequence()) {
            if (until != null && d.isAfter(until)) return null
            n++
            if (count != null && n > count) return null
            if (d.isAfter(after)) return d
        }
        return null
    }

    /** All candidate dates from [start] in order, unbounded (callers stop). Hard cap guards against bad rules. */
    fun sequence(): Sequence<LocalDate> = sequence {
        var guard = 0
        var k = 0L
        while (guard < MAX_STEPS) {
            val dates = when (freq) {
                Freq.DAILY -> listOf(start.plusDays(k * interval))
                Freq.WEEKLY -> {
                    val weekStart = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(k * interval)
                    val days = byWeekday.ifEmpty { listOf(start.dayOfWeek) }.sorted()
                    days.map { weekStart.plusDays((it.value - 1).toLong()) }
                }
                Freq.MONTHLY -> monthDates(start.withDayOfMonth(1).plusMonths(k * interval))
                Freq.YEARLY -> {
                    val y = start.plusYears(k * interval)
                    if (byMonthDay.isEmpty() && setPos == null) {
                        listOf(clampDay(y.year, start.monthValue, start.dayOfMonth))
                    } else {
                        monthDates(y.withDayOfMonth(1))
                    }
                }
            }
            for (d in dates.sorted()) if (!d.isBefore(start)) yield(d)
            k++
            guard++
        }
    }

    private fun monthDates(monthStart: LocalDate): List<LocalDate> {
        if (setPos != null && byWeekday.isNotEmpty()) {
            return byWeekday.mapNotNull { nthWeekday(monthStart, it, setPos) }
        }
        val days = byMonthDay.ifEmpty { listOf(start.dayOfMonth) }
        return days.map { d ->
            val len = monthStart.lengthOfMonth()
            monthStart.withDayOfMonth(if (d == -1) len else minOf(d, len))
        }.distinct()
    }

    companion object {
        private const val MAX_STEPS = 20_000

        fun monthly(start: LocalDate, day: Int = start.dayOfMonth) = Recurrence(Freq.MONTHLY, start = start, byMonthDay = listOf(day))
        fun weekly(start: LocalDate) = Recurrence(Freq.WEEKLY, start = start, byWeekday = listOf(start.dayOfWeek))

        fun clampDay(year: Int, month: Int, day: Int): LocalDate {
            val first = LocalDate.of(year, month, 1)
            return first.withDayOfMonth(minOf(day, first.lengthOfMonth()))
        }

        fun nthWeekday(monthStart: LocalDate, dow: DayOfWeek, pos: Int): LocalDate? = if (pos == -1) {
            monthStart.with(TemporalAdjusters.lastInMonth(dow))
        } else {
            val first = monthStart.with(TemporalAdjusters.firstInMonth(dow))
            first.plusWeeks((pos - 1).toLong()).takeIf { it.month == monthStart.month }
        }

        /** Approximate occurrences per year, used to annualise subscriptions (RC-04). */
        fun perYear(r: Recurrence): Double = when (r.freq) {
            Freq.DAILY -> 365.0 / r.interval
            Freq.WEEKLY -> 52.0 * r.byWeekday.size.coerceAtLeast(1) / r.interval
            Freq.MONTHLY -> 12.0 * r.byMonthDay.size.coerceAtLeast(1) / r.interval
            Freq.YEARLY -> 1.0 / r.interval
        }

        fun daysBetween(a: LocalDate, b: LocalDate) = ChronoUnit.DAYS.between(a, b)
    }
}
