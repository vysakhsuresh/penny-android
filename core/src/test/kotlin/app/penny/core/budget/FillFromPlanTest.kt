package app.penny.core.budget

import app.penny.core.model.Envelope
import app.penny.core.period.Period
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/** BU-09: "Fill from targets" refills an envelope without a target up to its planned amount. */
class FillFromPlanTest : FunSpec({
    val p = Period(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))
    val env = Envelope("Housing", limitMinor = 1_260_000, currency = "INR")

    test("after start from zero the planned amount is needed") {
        BudgetEngine.needed(env, p, assigned = 0, carried = 0, today = p.start) shouldBe 1_260_000
    }
    test("nothing is needed once the plan is assigned") {
        BudgetEngine.needed(env, p, assigned = 1_260_000, carried = 0, today = p.start) shouldBe 0
    }
})
