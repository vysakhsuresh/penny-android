package app.penny.core.schedule

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.localDate
import io.kotest.property.checkAll
import java.time.DayOfWeek
import java.time.LocalDate

class RecurrenceTest : FunSpec({
    test("monthly on the 31st clamps to month end, never skips a month") {
        val r = Recurrence.monthly(LocalDate.of(2026, 1, 31), 31)
        r.between(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 31)) shouldContainExactly listOf(
            LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30), LocalDate.of(2026, 5, 31),
        )
    }

    test("last day of month (-1) handles leap years") {
        val r = Recurrence(Freq.MONTHLY, start = LocalDate.of(2028, 1, 1), byMonthDay = listOf(-1))
        r.between(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29)) shouldContainExactly listOf(LocalDate.of(2028, 2, 29))
    }

    test("semi-monthly 15th and last day") {
        val r = Recurrence(Freq.MONTHLY, start = LocalDate.of(2026, 11, 1), byMonthDay = listOf(15, -1))
        r.between(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31)) shouldContainExactly listOf(
            LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 15), LocalDate.of(2026, 12, 31),
        )
    }

    test("second Tuesday and last Friday") {
        val r = Recurrence(Freq.MONTHLY, start = LocalDate.of(2026, 11, 1), byWeekday = listOf(DayOfWeek.TUESDAY), setPos = 2)
        r.between(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31)) shouldContainExactly listOf(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 12, 8))
        val last = Recurrence(Freq.MONTHLY, start = LocalDate.of(2026, 11, 1), byWeekday = listOf(DayOfWeek.FRIDAY), setPos = -1)
        last.next(LocalDate.of(2026, 11, 1)) shouldBe LocalDate.of(2026, 11, 27)
    }

    test("count and until stop the series; count is stable regardless of window") {
        val r = Recurrence(Freq.WEEKLY, start = LocalDate.of(2026, 11, 2), count = 3)
        r.between(LocalDate.of(2026, 11, 10), LocalDate.of(2027, 1, 1)) shouldContainExactly listOf(LocalDate.of(2026, 11, 16))
        val u = Recurrence(Freq.DAILY, interval = 2, start = LocalDate.of(2026, 11, 1), until = LocalDate.of(2026, 11, 6))
        u.between(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)) shouldContainExactly listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 5))
    }

    test("yearly on Feb 29 falls on Feb 28 in common years") {
        val r = Recurrence(Freq.YEARLY, start = LocalDate.of(2028, 2, 29))
        r.next(LocalDate.of(2028, 3, 1)) shouldBe LocalDate.of(2029, 2, 28)
    }

    test("property: occurrences are sorted, inside the window, never before start") {
        checkAll(
            300,
            Arb.localDate(LocalDate.of(2020, 1, 1), LocalDate.of(2030, 12, 31)),
            Arb.int(1, 31),
            Arb.int(1, 4),
            Arb.element(Freq.entries),
        ) { start, day, interval, freq ->
            val r = Recurrence(freq, interval, start, byMonthDay = if (freq == Freq.MONTHLY) listOf(day) else emptyList())
            val from = start.minusDays(10)
            val to = start.plusDays(800)
            val dates = r.between(from, to)
            dates.shouldBeSortedWith(naturalOrder())
            dates.all { !it.isBefore(start) && !it.isAfter(to) } shouldBe true
            dates.distinct().size shouldBe dates.size
        }
    }
})
