@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package app.penny.core.period

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.localDate
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val MIN = LocalDate.of(1995, 1, 1)
private val MAX = LocalDate.of(2095, 12, 31)
private val dates = Arb.localDate(MIN, MAX)
private val dayOfMonth = Arb.int(1..31)
private val config = PropTestConfig(iterations = 2000)

private val incomeDates: Arb<List<LocalDate>> = Arb.list(Arb.localDate(LocalDate.of(2020, 1, 1), LocalDate.of(2030, 12, 31)), 0..40)

private val rules: Arb<CycleRule> = Arb.choice(
    dayOfMonth.map { CycleRule.Monthly(it) },
    Arb.int(1..31).map { a -> CycleRule.SemiMonthly(minOf(a, 28).coerceAtLeast(1), LAST_DAY_OF_MONTH) },
    Arb.element(DayOfWeek.entries).map { CycleRule.Weekly(it) },
    dates.map { CycleRule.Biweekly(it) },
    dates.map { CycleRule.FourWeekly(it) },
    Arb.int(1..12).map { m -> CycleRule.Quarterly(m, 1) },
    Arb.int(1..12).map { m -> CycleRule.Yearly(m, if (m == 4) 6 else 1) },
    Arb.int(7..62).map { CycleRule.OnIncome(0, it) },
)

private fun lengthRange(rule: CycleRule): IntRange = when (rule) {
    is CycleRule.Monthly -> 28..31
    is CycleRule.SemiMonthly -> 1..31
    is CycleRule.Weekly -> 7..7
    is CycleRule.Biweekly -> 14..14
    is CycleRule.FourWeekly -> 28..28
    is CycleRule.Quarterly -> 89..92
    is CycleRule.Yearly -> 365..366
    is CycleRule.OnIncome -> 1..rule.maxDays.coerceAtLeast(31)
}

class PeriodPropertyTest : FunSpec({

    test("every date belongs to exactly one period: contained, idempotent, contiguous with neighbours") {
        checkAll(config, rules, dates, incomeDates) { rule, date, incomes ->
            val ctx = PeriodContext(incomes)
            val p = Periods.periodFor(date, rule, ctx)
            (date in p) shouldBe true
            Periods.periodFor(p.start, rule, ctx) shouldBe p
            Periods.periodFor(p.endInclusive, rule, ctx) shouldBe p
            Periods.next(p, rule, ctx).start shouldBe p.endInclusive.plusDays(1)
            Periods.previous(p, rule, ctx).endInclusive shouldBe p.start.minusDays(1)
            p.days shouldBeInRange lengthRange(rule)
        }
    }

    test("shift forward then back returns to the same period") {
        checkAll(config, rules, dates, Arb.int(0..30)) { rule, date, n ->
            val p = Periods.periodFor(date, rule)
            Periods.shift(Periods.shift(p, n, rule), -n, rule) shouldBe p
        }
    }

    test("monthly start days 29, 30 and 31 clamp to month ends in February, leap and common years") {
        listOf(29, 30, 31).forEach { day ->
            val rule = CycleRule.Monthly(day)
            Periods.periodFor(LocalDate.of(2024, 3, 1), rule).start shouldBe LocalDate.of(2024, 2, 29)
            Periods.periodFor(LocalDate.of(2025, 3, 1), rule).start shouldBe LocalDate.of(2025, 2, 28)
        }
        Periods.periodFor(LocalDate.of(2025, 4, 30), CycleRule.Monthly(31)) shouldBe
            Period(LocalDate.of(2025, 4, 30), LocalDate.of(2025, 5, 30))
    }

    test("monthly periods always start on the requested day or the month's last day") {
        checkAll(config, dayOfMonth, dates) { day, date ->
            val p = Periods.periodFor(date, CycleRule.Monthly(day))
            p.start.dayOfMonth shouldBe minOf(day, p.start.lengthOfMonth())
        }
    }

    test("semi-monthly 15 and last day (quincena, kinsenas)") {
        val rule = CycleRule.SemiMonthly(15, LAST_DAY_OF_MONTH)
        Periods.periodFor(LocalDate.of(2026, 2, 20), rule) shouldBe Period(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 2, 27))
        Periods.periodFor(LocalDate.of(2026, 2, 28), rule) shouldBe Period(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 14))
        Periods.periodFor(LocalDate.of(2026, 3, 1), rule) shouldBe Period(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 14))
        checkAll(config, dates) { d ->
            val p = Periods.periodFor(d, rule)
            (p.start.dayOfMonth == 15 || p.start.dayOfMonth == p.start.lengthOfMonth()) shouldBe true
        }
    }

    test("biweekly and four-weekly are anchored, also before the anchor") {
        val anchor = LocalDate.of(2026, 1, 2)
        Periods.periodFor(LocalDate.of(2026, 1, 1), CycleRule.Biweekly(anchor)).start shouldBe LocalDate.of(2025, 12, 19)
        Periods.periodFor(LocalDate.of(2026, 1, 15), CycleRule.Biweekly(anchor)).start shouldBe LocalDate.of(2026, 1, 2)
        Periods.periodFor(LocalDate.of(2026, 1, 30), CycleRule.FourWeekly(anchor)).start shouldBe LocalDate.of(2026, 1, 30)
    }

    test("fiscal years: UK 6 April, India and Japan 1 April, Australia 1 July") {
        Periods.periodFor(LocalDate.of(2026, 4, 5), CycleRule.Yearly(4, 6)) shouldBe Period(LocalDate.of(2025, 4, 6), LocalDate.of(2026, 4, 5))
        Periods.periodFor(LocalDate.of(2026, 3, 31), CycleRule.Yearly(4, 1)).start shouldBe LocalDate.of(2025, 4, 1)
        Periods.periodFor(LocalDate.of(2026, 7, 1), CycleRule.Yearly(7, 1)).start shouldBe LocalDate.of(2026, 7, 1)
        Periods.periodFor(LocalDate.of(2026, 3, 15), CycleRule.Quarterly(4, 1)) shouldBe Period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
    }

    test("when money lands: periods start on income dates and roll over after maxDays") {
        val incomes = listOf(LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 20), LocalDate.of(2026, 6, 1))
        val ctx = PeriodContext(incomes)
        val rule = CycleRule.OnIncome(0, 31)
        Periods.periodFor(LocalDate.of(2026, 3, 10), rule, ctx) shouldBe Period(LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 19))
        Periods.periodFor(LocalDate.of(2026, 4, 25), rule, ctx).start shouldBe LocalDate.of(2026, 4, 20)
        Periods.periodFor(LocalDate.of(2026, 5, 31), rule, ctx).endInclusive shouldBe LocalDate.of(2026, 5, 31)
        Periods.periodFor(LocalDate.of(2026, 3, 1), rule, ctx).endInclusive shouldBe LocalDate.of(2026, 3, 2)
        Periods.periodFor(LocalDate.of(2026, 3, 1), rule, PeriodContext.EMPTY) shouldBe
            Period(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))
    }

    context("DST transitions") {
        val zones = listOf("America/New_York", "Europe/London", "Australia/Sydney", "America/Sao_Paulo", "Asia/Kolkata", "Pacific/Apia")
            .map(ZoneId::of)
        // Instants within two days of the 2010-2030 DST transitions, plus random instants.
        val transitions = zones.flatMap { z ->
            z.rules.transitions.map { it.instant } +
                (2010..2030).flatMap { y -> listOfNotNull(z.rules.nextTransition(Instant.parse("$y-01-01T00:00:00Z"))?.instant) }
        }.filter { it.isAfter(Instant.parse("2005-01-01T00:00:00Z")) && it.isBefore(Instant.parse("2035-01-01T00:00:00Z")) }
        val nearTransitions = Arb.element(transitions).map { it }
        val offsets = Arb.long(-172_800L..172_800L)

        test("an instant's local date lies in its period and inside the period's instant bounds") {
            checkAll(config, rules, Arb.element(zones), nearTransitions, offsets) { rule, zone, t, off ->
                val instant = t.plusSeconds(off)
                val p = Periods.periodForInstant(instant, zone, rule)
                (Periods.localDate(instant, zone) in p) shouldBe true
                val (start, end) = Periods.bounds(p, zone)
                (!instant.isBefore(start) && instant.isBefore(end)) shouldBe true
                Periods.bounds(Periods.next(p, rule), zone).first shouldBe end
            }
        }

        test("a weekly period across spring-forward is one hour shorter, across fall-back one hour longer") {
            val ny = ZoneId.of("America/New_York")
            val rule = CycleRule.Weekly(DayOfWeek.MONDAY)
            val spring = Periods.periodFor(LocalDate.of(2026, 3, 8), rule)
            val (s1, e1) = Periods.bounds(spring, ny)
            Duration.between(s1, e1) shouldBe Duration.ofHours(7 * 24 - 1L)
            val fall = Periods.periodFor(LocalDate.of(2026, 11, 1), rule)
            val (s2, e2) = Periods.bounds(fall, ny)
            Duration.between(s2, e2) shouldBe Duration.ofHours(7 * 24 + 1L)
        }

        test("a day skipped by the date line (Samoa 2011-12-30) still maps every instant to a period") {
            val apia = ZoneId.of("Pacific/Apia")
            val rule = CycleRule.Monthly(1)
            val p = Periods.periodForInstant(Instant.parse("2011-12-30T12:00:00Z"), apia, rule)
            p shouldBe Period(LocalDate.of(2011, 12, 1), LocalDate.of(2011, 12, 31))
        }
    }
})
