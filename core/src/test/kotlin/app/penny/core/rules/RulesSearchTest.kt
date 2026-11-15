package app.penny.core.rules

import app.penny.core.model.Rule
import app.penny.core.model.RuleActions
import app.penny.core.model.RuleCondition
import app.penny.core.model.RuleField
import app.penny.core.model.RuleOp
import app.penny.core.model.SplitShare
import app.penny.core.model.Transaction
import app.penny.core.model.TxFilter
import app.penny.core.model.TxType
import app.penny.core.period.CycleRule
import app.penny.core.search.NaturalQueryParser
import app.penny.core.search.Row
import app.penny.core.search.TxSearch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.LocalDate
import java.time.LocalTime

class RulesSearchTest : FunSpec({
    val today = LocalDate.of(2026, 11, 15)
    fun tx(payee: String?, amt: Long, date: LocalDate = today, cat: String? = null, acct: String = "a") =
        Transaction(type = TxType.EXPENSE, amountMinor = amt, currency = "INR", accountId = acct, categoryId = cat, payee = payee, date = date, tz = "UTC")

    test("first match wins unless continue; accents and case ignored") {
        val rules = listOf(
            "r1" to Rule("Cafe", listOf(RuleCondition(RuleField.PAYEE, RuleOp.CONTAINS, "café")), actions = RuleActions(categoryId = "eat"), order = 1, continueAfter = true),
            "r2" to Rule("Tag", listOf(RuleCondition(RuleField.AMOUNT, RuleOp.GT, "100")), actions = RuleActions(addTags = listOf("big")), order = 2),
            "r3" to Rule("Never", listOf(RuleCondition(RuleField.PAYEE, RuleOp.CONTAINS, "cafe")), actions = RuleActions(categoryId = "x"), order = 3),
        )
        val r = RuleEngine.apply(tx("BLUE CAFE", 500), rules)
        r.tx.categoryId shouldBe "eat"
        r.tx.tags shouldContainExactly listOf("big")
        r.matched shouldContainExactly listOf("r1", "r2")
    }

    test("transfer action turns a card payment into a transfer") {
        val rule = Rule("Card", listOf(RuleCondition(RuleField.PAYEE, RuleOp.STARTS_WITH, "card payment")), actions = RuleActions(transferToAccountId = "card"))
        val out = RuleEngine.apply(tx("Card payment HDFC", 1000, cat = "x"), listOf("r" to rule)).tx
        out.type shouldBe TxType.TRANSFER
        out.toAccountId shouldBe "card"
        out.categoryId shouldBe null
    }

    test("property: split template always sums to the total") {
        checkAll(500, Arb.long(1, 10_000_000_000), Arb.list(Arb.int(1, 10_000), 1..6)) { total, bps ->
            val splits = RuleEngine.splitByBp(total, bps.map { it to null })
            splits.sumOf { it.amountMinor } shouldBe total
        }
    }

    test("learner: draft after 2 identical corrections, promoted after 3") {
        val first = RuleLearner.observe("Zomato", "eat", emptyList())!!
        first.second.evidence shouldBe 1
        RuleLearner.isVisible(first.second) shouldBe false
        val second = RuleLearner.observe("zomato ", "eat", listOf("id" to first.second))!!
        RuleLearner.isDraft(second.second) shouldBe true
        val third = RuleLearner.observe("ZOMATO", "eat", listOf("id" to second.second))!!
        third.second.enabled shouldBe true
    }

    test("dedupe: same account, amount, within 24h, similar payee") {
        val a = tx("Starbucks Coffee", 450).copy(time = LocalTime.of(9, 0))
        Dedupe.isDuplicate(a, tx("STARBUCKS", 450, today).copy(time = LocalTime.of(20, 0))) shouldBe true
        Dedupe.isDuplicate(a, tx("Starbucks", 450, today.plusDays(2)).copy(time = LocalTime.of(9, 0))) shouldBe false
        Dedupe.isDuplicate(a, tx("Shell", 450).copy(time = LocalTime.of(9, 30))) shouldBe false
        Dedupe.isDuplicate(a, tx("Starbucks", 451).copy(time = LocalTime.of(9, 30))) shouldBe false
    }

    test("natural language: coffee last month over 200") {
        val p = NaturalQueryParser(today, CycleRule.Monthly(1), 2)
        val q = p.parse("coffee last month over 200")
        q.filter.text shouldBe "coffee"
        q.filter.from shouldBe LocalDate.of(2026, 10, 1)
        q.filter.to shouldBe LocalDate.of(2026, 10, 31)
        q.filter.minMinor shouldBe 20_000
    }

    test("natural language: jeep rides last kinsenas uses the pay cycle") {
        val p = NaturalQueryParser(today, CycleRule.SemiMonthly(15), 2)
        val q = p.parse("jeep rides last kinsenas")
        q.filter.text shouldBe "jeep ride"
        q.filter.from shouldBe LocalDate.of(2026, 10, 31)
        q.filter.to shouldBe LocalDate.of(2026, 11, 14)
    }

    test("search filters by text across payee and category names, amount range and sort") {
        val rows = listOf(
            Row("1", tx("Jeep", 3000, cat = "trans"), 1),
            Row("2", tx("Grab", 15000, cat = "trans"), 2),
            Row("3", tx("SM Market", 50000, cat = "food"), 3),
        )
        val hits = TxSearch.filter(rows, TxFilter(text = "transport", maxMinor = 20000, sort = app.penny.core.model.SortBy.AMOUNT), app.penny.core.search.SearchIndex(categoryName = { if (it == "trans") "Transport" else "Food" }))
        hits.map { it.id } shouldContainExactly listOf("2", "1")
    }
})
