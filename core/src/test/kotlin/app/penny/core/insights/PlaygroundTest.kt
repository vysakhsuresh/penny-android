package app.penny.core.insights

import app.penny.core.period.Period
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.LocalDate

class PlaygroundTest : FunSpec({
    val d = { day: Int -> LocalDate.of(2026, 11, day) }

    test("facts count only days up to today, with streaks and the biggest day") {
        // Nov 2026: 1 Sun, 2 Mon ... 10 Tue. Spend on 2, 3 and 7; nothing on 1, 4, 5, 6, 8, 9; 10 is in the future.
        val daily = (1..10).associate { d(it) to when (it) { 2 -> 500L; 3 -> 1_500L; 7 -> 1_000L; 10 -> 9_999L; else -> 0L } }
        val f = Playground.facts(daily, today = d(9))
        f.daysCounted shouldBe 9
        f.avgPerDayMinor shouldBe 3_000L / 9
        f.biggestDay shouldBe d(3)
        f.biggestDayMinor shouldBe 1_500L
        f.noSpendDays shouldBe 6
        f.currentStreak shouldBe 2
        f.bestStreak shouldBe 3
        f.busiestWeekday shouldBe DayOfWeek.TUESDAY
    }

    test("an empty period has no biggest day and no busiest weekday") {
        val f = Playground.facts(emptyMap(), d(5))
        f.daysCounted shouldBe 0
        f.avgPerDayMinor shouldBe 0
        f.biggestDay shouldBe null
        f.busiestWeekday shouldBe null
    }

    test("previous period keeps the shape: months by months, other ranges by days") {
        Playground.previous(Period(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))) shouldBe Period(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28))
        Playground.previous(Period(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30))) shouldBe Period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
        Playground.previous(Period(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 24))) shouldBe Period(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 24))
        Playground.previous(Period(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 8))) shouldBe Period(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1))
    }

    test("averages round to the nearest minor unit instead of truncating") {
        // Three Sundays (1, 8, 15 Nov 2026) with 200.00 spent on one: 20000 / 3 = 6666.67 -> 6667.
        val daily = mapOf(d(1) to 0L, d(8) to 0L, d(15) to 20_000L)
        Playground.facts(daily, d(15)).weekdayAvgMinor[DayOfWeek.SUNDAY] shouldBe 6_667L
        Playground.avg(2, 4) shouldBe 0L // 0.5 rounds half-even to 0
        Playground.avg(6, 4) shouldBe 2L // 1.5 -> 2
        Playground.avg(-20_000, 3) shouldBe -6_667L
    }

    test("setting a little aside each day") {
        Playground.setAside(5_000, 365) shouldBe 1_825_000
    }
})
