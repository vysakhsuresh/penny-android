package app.penny.core.budget

import app.penny.core.model.Allocation
import app.penny.core.model.BudgetMove
import app.penny.core.model.Category
import app.penny.core.model.CategoryKind
import app.penny.core.model.Envelope
import app.penny.core.model.FxRate
import app.penny.core.model.RolloverMode
import app.penny.core.model.Split
import app.penny.core.model.Target
import app.penny.core.model.TargetKind
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.core.period.CycleRule
import app.penny.core.period.Periods
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class BudgetEngineTest : FunSpec({
    val cats = mapOf(
        "food" to Category(kind = CategoryKind.EXPENSE, name = "Food"),
        "groc" to Category(kind = CategoryKind.EXPENSE, name = "Groceries", parentId = "food"),
        "fun" to Category(kind = CategoryKind.EXPENSE, name = "Fun"),
    )
    fun tx(type: TxType, amt: Long, date: LocalDate, cat: String? = null, tags: List<String> = emptyList()) =
        Transaction(type = type, amountMinor = amt, currency = "INR", accountId = "a", categoryId = cat, date = date, tz = "UTC", tags = tags)

    fun input(env: Envelope, txs: List<Transaction>, allocs: List<Allocation> = emptyList(), moves: List<BudgetMove> = emptyList()) = BudgetInput(
        envelopes = mapOf("e" to env), allocations = allocs, moves = moves, transactions = txs, categories = cats,
        cycle = CycleRule.Monthly(1), baseCurrency = "INR",
    )

    val oct = LocalDate.of(2026, 10, 5)
    val nov = LocalDate.of(2026, 11, 5)
    val food = Envelope("Food", categoryIds = listOf("food"), limitMinor = 10_000, currency = "INR", startedOn = LocalDate.of(2026, 10, 1))

    test("children of covered categories count; refunds reduce; splits count line by line") {
        val txs = listOf(
            tx(TxType.EXPENSE, 3_000, nov, "groc"),
            tx(TxType.REFUND, 500, nov, "groc"),
            tx(TxType.EXPENSE, 1_000, nov, "fun"),
            tx(TxType.EXPENSE, 2_000, nov, null).copy(splits = listOf(Split(1_200, "groc"), Split(800, "fun"))),
        )
        val s = BudgetEngine.status("e", nov, input(food, txs))
        s.spentMinor shouldBe 3_700
        s.availableMinor shouldBe 6_300
        s.state shouldBe EnvelopeState.OK
    }

    test("rollover modes") {
        val txs = listOf(tx(TxType.EXPENSE, 12_000, oct, "groc"))
        BudgetEngine.status("e", nov, input(food.copy(rollover = RolloverMode.NONE), txs)).carriedMinor shouldBe 0
        BudgetEngine.status("e", nov, input(food.copy(rollover = RolloverMode.POSITIVE), txs)).carriedMinor shouldBe 0
        BudgetEngine.status("e", nov, input(food.copy(rollover = RolloverMode.BOTH), txs)).carriedMinor shouldBe -2_000
        val under = listOf(tx(TxType.EXPENSE, 4_000, oct, "groc"))
        BudgetEngine.status("e", nov, input(food.copy(rollover = RolloverMode.POSITIVE), under)).carriedMinor shouldBe 6_000
        BudgetEngine.status("e", nov, input(food.copy(rollover = RolloverMode.CAPPED, rolloverCapMinor = 2_500), under)).carriedMinor shouldBe 2_500
    }

    test("allocations and moves change assigned; states at thresholds") {
        val p = Periods.periodFor(nov, CycleRule.Monthly(1)).start
        val txs = listOf(tx(TxType.EXPENSE, 8_000, nov, "groc"))
        val s = BudgetEngine.status("e", nov, input(food, txs, listOf(Allocation("e", p, 9_000)), listOf(BudgetMove(null, "e", p, 1_000))))
        s.assignedMinor shouldBe 10_000
        s.state shouldBe EnvelopeState.NEAR
        BudgetEngine.status("e", nov, input(food, listOf(tx(TxType.EXPENSE, 10_000, nov, "groc")))).state shouldBe EnvelopeState.AT
        BudgetEngine.status("e", nov, input(food, listOf(tx(TxType.EXPENSE, 10_001, nov, "groc")))).state shouldBe EnvelopeState.OVER
        BudgetEngine.status("e", nov, input(food, listOf(tx(TxType.EXPENSE, 5_000, nov, "groc")))).state shouldBe EnvelopeState.HALF
    }

    test("available to assign = income − assigned; moves from available reduce it") {
        val p = Periods.periodFor(nov, CycleRule.Monthly(1))
        val txs = listOf(tx(TxType.INCOME, 50_000, nov))
        BudgetEngine.availableToAssign(p, input(food, txs)) shouldBe 40_000
        BudgetEngine.availableToAssign(p, input(food, txs, moves = listOf(BudgetMove(null, "e", p.start, 5_000)))) shouldBe 35_000
    }

    test("targets: fixed and refill") {
        val fixed = food.copy(limitMinor = 2_000, target = Target(TargetKind.FIXED, 5_000))
        BudgetEngine.status("e", nov, input(fixed, emptyList())).neededMinor shouldBe 3_000
        val refill = food.copy(limitMinor = 0, rollover = RolloverMode.POSITIVE, target = Target(TargetKind.REFILL_UP_TO, 5_000))
        BudgetEngine.status("e", nov, input(refill, emptyList(), listOf(Allocation("e", LocalDate.of(2026, 10, 1), 4_000)))).neededMinor shouldBe 1_000
    }

    test("auto-assign never exceeds available and funds the most underfunded first") {
        val a = BudgetEngine.status("e", nov, input(food.copy(limitMinor = 0, target = Target(TargetKind.FIXED, 5_000)), emptyList()))
        val b = a.copy(id = "f", neededMinor = 1_000, assignedMinor = 9_000)
        val plan = BudgetEngine.autoAssign(listOf(b, a), 5_500)
        plan.first().first shouldBe "e"
        plan.sumOf { it.second } shouldBe 5_500
    }

    test("fx table converts with the latest rate on or before the date, inverse and cross") {
        val fx = FxTable(listOf(FxRate("USD", "INR", LocalDate.of(2026, 11, 1), "83.50"), FxRate("USD", "PHP", LocalDate.of(2026, 11, 1), "58")))
        fx.convert(100, "USD", "INR", nov) shouldBe 8_350
        fx.convert(8_350, "INR", "USD", nov) shouldBe 100
        fx.convert(100, "USD", "JPY", nov) shouldBe null
        fx.convert(5_800, "PHP", "INR", nov) shouldBe 8_350
    }
})
