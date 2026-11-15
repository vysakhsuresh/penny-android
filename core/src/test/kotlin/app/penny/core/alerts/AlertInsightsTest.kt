package app.penny.core.alerts

import app.penny.core.budget.BudgetEngine
import app.penny.core.budget.BudgetInput
import app.penny.core.budget.FxConverter
import app.penny.core.insights.Insights
import app.penny.core.model.AlertKind
import app.penny.core.model.AlertRecord
import app.penny.core.model.Category
import app.penny.core.model.CategoryKind
import app.penny.core.model.Envelope
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.core.period.CycleRule
import app.penny.core.period.Periods
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.LocalTime

class AlertInsightsTest : FunSpec({
    val today = LocalDate.of(2026, 11, 15)
    fun tx(type: TxType, amt: Long, cat: String?, d: LocalDate = today) = Transaction(type = type, amountMinor = amt, currency = "INR", accountId = "a", categoryId = cat, date = d, tz = "UTC")
    val env = Envelope("Food", listOf("food"), limitMinor = 1_000, currency = "INR")
    fun status(spent: Long) = BudgetEngine.status("e", today, BudgetInput(mapOf("e" to env), emptyList(), emptyList(), listOf(tx(TxType.EXPENSE, spent, "food")),
        mapOf("food" to Category(kind = CategoryKind.EXPENSE, name = "Food")), CycleRule.Monthly(1), "INR"))

    fun eval(spent: Long, history: List<AlertRecord> = emptyList()) = AlertEngine.evaluate(today, listOf(status(spent)), null, 0, emptyList(), emptyList(), { null }, emptySet(), emptyList(), { 0 }, history)

    test("50% is digest only, 80% near, over fires") {
        eval(500).single().let { it.kind shouldBe AlertKind.ENVELOPE_HALF; it.digestOnly shouldBe true }
        eval(850).single().kind shouldBe AlertKind.ENVELOPE_NEAR
        eval(1_200).single().kind shouldBe AlertKind.ENVELOPE_OVER
    }

    test("already fired keys do not repeat; over backs off after two ignored") {
        val near = eval(850).single()
        eval(850, listOf(AlertRecord(near.kind, near.key, title = "", body = "", firedAtMillis = 0))).shouldBeEmpty()
        val p = Periods.periodFor(today, CycleRule.Monthly(1)).start
        val ignored = (1..2).map { AlertRecord(AlertKind.ENVELOPE_OVER, "over:e:$p:d$it", "envelope", "e", "", "", 0) }
        eval(1_200, ignored).shouldBeEmpty()
    }

    test("quiet hours wrap midnight") {
        val s = AlertSettings()
        AlertEngine.inQuietHours(LocalTime.of(22, 0), s) shouldBe true
        AlertEngine.inQuietHours(LocalTime.of(8, 59), s) shouldBe true
        AlertEngine.inQuietHours(LocalTime.of(12, 0), s) shouldBe false
    }

    test("insights: spend excludes transfers and excluded categories; by category rolls up") {
        val txs = listOf(
            tx(TxType.EXPENSE, 300, "groc"), tx(TxType.EXPENSE, 200, "rest"), tx(TxType.REFUND, 50, "groc"),
            tx(TxType.TRANSFER, 1_000, null), tx(TxType.EXPENSE, 400, "save"), tx(TxType.INCOME, 5_000, "salary"),
            tx(TxType.EXPENSE, 999, "groc").copy(excludeFromReports = true),
        )
        val ins = Insights(txs, "INR", setOf("save"), FxConverter.SameCurrencyOnly) { if (it == "groc" || it == "rest") "food" else it }
        val p = Periods.periodFor(today, CycleRule.Monthly(1))
        val s = ins.summary(p, CycleRule.Monthly(1))
        s.spentMinor shouldBe 450
        s.incomeMinor shouldBe 5_000
        s.byCategory.single().let { it.key shouldBe "food"; it.amountMinor shouldBe 450 }
        ins.daily(p)[today] shouldBe 450
    }
})
