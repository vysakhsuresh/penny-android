package app.penny.core.planning

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** BU-07: money put aside for goals this period is not safe to spend, whether or not it has been recorded yet. */
class GoalReserveTest : FunSpec({
    test("a plan with nothing recorded yet reserves the planned amount") {
        Goals.reservedThisPeriod(20_000, emptyList()) shouldBe 20_000
    }
    test("once the planned amount is recorded it is still reserved (it was counted as spendable before)") {
        Goals.reservedThisPeriod(20_000, listOf(20_000)) shouldBe 20_000
    }
    test("extra contributions on top of the plan are reserved too") {
        Goals.reservedThisPeriod(20_000, listOf(20_000, 5_000)) shouldBe 25_000
    }
    test("part of the plan recorded: the rest is still reserved") {
        Goals.reservedThisPeriod(20_000, listOf(8_000)) shouldBe 20_000
    }
    test("a withdrawal gives money back to spend") {
        Goals.reservedThisPeriod(20_000, listOf(20_000, -20_000)) shouldBe 0
    }
    test("no plan: only what was put aside, never below zero") {
        Goals.reservedThisPeriod(null, listOf(3_000)) shouldBe 3_000
        Goals.reservedThisPeriod(null, listOf(-3_000)) shouldBe 0
        Goals.reservedThisPeriod(null, emptyList()) shouldBe 0
    }
})
