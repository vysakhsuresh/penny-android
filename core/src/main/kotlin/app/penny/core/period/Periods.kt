package app.penny.core.period

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** A budget period: calendar dates in the profile's timezone, both ends inclusive. */
data class Period(val start: LocalDate, val endInclusive: LocalDate) {
    init { require(!endInclusive.isBefore(start)) { "Period end before start: $start..$endInclusive" } }

    val days: Int get() = (ChronoUnit.DAYS.between(start, endInclusive) + 1).toInt()
    val key: String get() = start.toString()
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)
}

/** Inputs some rules need: income dates (already filtered by threshold, in the profile timezone) for OnIncome. */
data class PeriodContext(val incomeDates: List<LocalDate> = emptyList()) {
    companion object { val EMPTY = PeriodContext() }
}

/**
 * Period math (BU-01, BU-03). Pure and deterministic: the same date, rule and context always give the same period,
 * so changing the cycle re-attributes history deterministically.
 */
object Periods {

    fun periodFor(date: LocalDate, rule: CycleRule, ctx: PeriodContext = PeriodContext.EMPTY): Period = when (rule) {
        is CycleRule.Monthly -> fromStartPoints(date) { ym -> listOf(clampDay(ym, rule.startDay)) }
        is CycleRule.SemiMonthly -> fromStartPoints(date) { ym ->
            listOf(clampDay(ym, rule.firstDay), clampDay(ym, rule.secondDay)).distinct().sorted()
        }
        is CycleRule.Weekly -> {
            val start = date.with(TemporalAdjusters.previousOrSame(rule.startsOn))
            Period(start, start.plusDays(6))
        }
        is CycleRule.Biweekly -> fixedLength(date, rule.anchor, 14)
        is CycleRule.FourWeekly -> fixedLength(date, rule.anchor, 28)
        is CycleRule.Quarterly -> fiscal(date, rule.fiscalMonth, rule.fiscalDay, spanMonths = 3)
        is CycleRule.Yearly -> fiscal(date, rule.fiscalMonth, rule.fiscalDay, spanMonths = 12)
        is CycleRule.OnIncome -> onIncome(date, rule, ctx)
    }

    fun next(p: Period, rule: CycleRule, ctx: PeriodContext = PeriodContext.EMPTY): Period =
        periodFor(p.endInclusive.plusDays(1), rule, ctx)

    fun previous(p: Period, rule: CycleRule, ctx: PeriodContext = PeriodContext.EMPTY): Period =
        periodFor(p.start.minusDays(1), rule, ctx)

    fun shift(p: Period, n: Int, rule: CycleRule, ctx: PeriodContext = PeriodContext.EMPTY): Period {
        var cur = p
        repeat(kotlin.math.abs(n)) { cur = if (n > 0) next(cur, rule, ctx) else previous(cur, rule, ctx) }
        return cur
    }

    /** The calendar date an instant falls on in [zone]. Travel never re-attributes: callers pass the profile zone. */
    fun localDate(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()

    /** Instant bounds [start, endExclusive) of a period in [zone]; handles days that are 23 or 25 hours long. */
    fun bounds(p: Period, zone: ZoneId): Pair<Instant, Instant> =
        p.start.atStartOfDay(zone).toInstant() to p.endInclusive.plusDays(1).atStartOfDay(zone).toInstant()

    fun periodForInstant(instant: Instant, zone: ZoneId, rule: CycleRule, ctx: PeriodContext = PeriodContext.EMPTY): Period =
        periodFor(localDate(instant, zone), rule, ctx)

    /** Days elapsed including [today] and days left after it, clamped to the period. */
    fun progress(p: Period, today: LocalDate): Pair<Int, Int> {
        val elapsed = (ChronoUnit.DAYS.between(p.start, today) + 1).toInt().coerceIn(0, p.days)
        val left = ChronoUnit.DAYS.between(today, p.endInclusive).toInt().coerceIn(0, p.days)
        return elapsed to left
    }

    private fun clampDay(ym: YearMonth, day: Int): LocalDate = ym.atDay(minOf(day, ym.lengthOfMonth()))

    /** Rules whose periods start on fixed days of each month. */
    private inline fun fromStartPoints(date: LocalDate, points: (YearMonth) -> List<LocalDate>): Period {
        val ym = YearMonth.from(date)
        val candidates = points(ym.minusMonths(1)) + points(ym) + points(ym.plusMonths(1)) + points(ym.plusMonths(2))
        val start = candidates.last { !it.isAfter(date) }
        val nextStart = candidates.first { it.isAfter(start) }
        return Period(start, nextStart.minusDays(1))
    }

    private fun fixedLength(date: LocalDate, anchor: LocalDate, length: Long): Period {
        val n = Math.floorDiv(ChronoUnit.DAYS.between(anchor, date), length)
        val start = anchor.plusDays(n * length)
        return Period(start, start.plusDays(length - 1))
    }

    private fun fiscal(date: LocalDate, month: Int, day: Int, spanMonths: Int): Period {
        val points = (date.year - 2..date.year + 1).flatMap { y ->
            (0 until 12 step spanMonths).map { k -> clampDay(YearMonth.of(y, month).plusMonths(k.toLong()), day) }
        }.distinct().sorted()
        val start = points.last { !it.isAfter(date) }
        val nextStart = points.first { it.isAfter(start) }
        return Period(start, nextStart.minusDays(1))
    }

    private fun onIncome(date: LocalDate, rule: CycleRule.OnIncome, ctx: PeriodContext): Period {
        val incomes = ctx.incomeDates.distinct().sorted()
        if (incomes.isEmpty()) return periodFor(date, CycleRule.Monthly(1))
        val max = rule.maxDays.toLong()
        val first = incomes.first()
        if (date.isBefore(first)) {
            val gap = ChronoUnit.DAYS.between(date, first)
            val k = (gap + max - 1) / max
            val start = first.minusDays(k * max)
            return Period(start, start.plusDays(max - 1))
        }
        val i = incomes.indexOfLast { !it.isAfter(date) }
        val base = incomes[i]
        val nextIncome = incomes.getOrNull(i + 1)
        val offset = ChronoUnit.DAYS.between(base, date) / max
        val start = base.plusDays(offset * max)
        val candidateEnd = start.plusDays(max - 1)
        val end = if (nextIncome != null && !candidateEnd.isBefore(nextIncome)) nextIncome.minusDays(1) else candidateEnd
        return Period(start, end)
    }
}
