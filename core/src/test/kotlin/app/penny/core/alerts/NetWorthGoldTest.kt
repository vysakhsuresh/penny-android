package app.penny.core.alerts

import app.penny.core.insights.NetWorth
import app.penny.core.model.AccountType
import app.penny.core.model.FinancialAccount
import app.penny.core.model.Valuation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/** AC-01 / IN-05: gold valued by grams uses the reference price until the user records a value. */
class NetWorthGoldTest : FunSpec({
    val today = LocalDate.of(2026, 11, 16)
    val gold = FinancialAccount("Gold chain", AccountType.ASSET, "INR", weightGrams = 10.0)

    test("grams times the reference price when nothing is recorded") {
        NetWorth.value("g", gold, emptyList(), today, emptyList(), 1_190_000) shouldBe 11_900_000
    }

    test("a recorded value wins over the reference price") {
        val v = listOf(Valuation("g", today.minusDays(1), 12_500_000))
        NetWorth.value("g", gold, emptyList(), today, v, 1_190_000) shouldBe 12_500_000
    }
})
