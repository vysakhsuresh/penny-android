package app.penny.core.planning

import app.penny.core.model.Circle
import app.penny.core.model.CircleContribution
import app.penny.core.model.CircleMember
import app.penny.core.model.Goal
import app.penny.core.model.GoalEntry
import app.penny.core.model.GroupExpense
import app.penny.core.model.ItemLine
import app.penny.core.model.LendDirection
import app.penny.core.model.LendingEntry
import app.penny.core.model.Loan
import app.penny.core.model.Occurrence
import app.penny.core.model.OccurrenceStatus
import app.penny.core.model.Recurring
import app.penny.core.model.Remittance
import app.penny.core.model.Settlement
import app.penny.core.model.ShareLine
import app.penny.core.model.SplitMode
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.core.period.CycleRule
import app.penny.core.schedule.Recurrence
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.LocalDate

class PlanningTest : FunSpec({
    val today = LocalDate.of(2026, 11, 15)

    test("EMI matches the standard formula and the schedule repays the principal exactly") {
        Debt.emi(10_000_000, 1_000, 12) shouldBe 879_159 // 1,00,000.00 at 10% for 12 months = 8,791.59
        val loan = Loan("Car", principalMinor = 10_000_000, currency = "INR", aprBp = 1_000, tenureMonths = 12, start = LocalDate.of(2026, 1, 1))
        val s = Debt.schedule(loan)
        s.size shouldBe 12
        s.sumOf { it.principalMinor } shouldBe 10_000_000
        s.last().balanceMinor shouldBe 0
        Debt.emi(1_200, 0, 12) shouldBe 100
    }

    test("prepayment saves months and interest") {
        val loan = Loan("Home", principalMinor = 500_000_000, currency = "INR", aprBp = 850, tenureMonths = 240, start = LocalDate.of(2026, 1, 1))
        val (months, interest) = Debt.prepaymentWhatIf(loan, LocalDate.of(2027, 1, 15), 50_000_000)
        months shouldNotBe 0
        interest shouldBeGreaterThan 0
    }

    test("payoff: avalanche never pays more interest than snowball") {
        val debts = listOf(
            Debt.Debtor("card", "Card", 5_000_00, 3_600, 250_00),
            Debt.Debtor("loan", "Loan", 2_000_00, 1_200, 100_00),
            Debt.Debtor("bnpl", "BNPL", 800_00, 0, 80_00),
        )
        val a = Debt.payoff(debts, 200_00, Debt.Strategy.AVALANCHE, today)
        val s = Debt.payoff(debts, 200_00, Debt.Strategy.SNOWBALL, today)
        (a.totalInterestMinor <= s.totalInterestMinor) shouldBe true
        s.payoffOrder.first() shouldBe "bnpl"
        a.balances.last() shouldBe 0
    }

    test("card cycle: statement on 5th, due on 25th") {
        val c = Debt.cardCycle(5, 25, LocalDate.of(2026, 11, 15))
        c.statementDate shouldBe LocalDate.of(2026, 11, 5)
        c.dueDate shouldBe LocalDate.of(2026, 11, 25)
        val c2 = Debt.cardCycle(20, 10, LocalDate.of(2026, 11, 15))
        c2.statementDate shouldBe LocalDate.of(2026, 10, 20)
        c2.dueDate shouldBe LocalDate.of(2026, 11, 10)
    }

    test("property: every split mode sums to the expense amount") {
        checkAll(400, Arb.long(1, 5_000_000_00), Arb.list(Arb.int(1, 9), 1..8)) { amount, weights ->
            val members = weights.indices.map { "m$it" }
            for (mode in listOf(SplitMode.EQUAL, SplitMode.SHARES)) {
                val e = GroupExpense("g", "x", "m0", amount, "INR", today, mode, members.zip(weights).map { ShareLine(it.first, it.second.toLong()) })
                Splitting.shares(e).values.sum() shouldBe amount
            }
        }
    }

    test("item split spreads tax proportionally; balances sum to zero; simplify uses at most n-1 transfers") {
        val e = GroupExpense("g", "Dinner", "me", 1_100, "PHP", today, SplitMode.ITEMS, emptyList(),
            items = listOf(ItemLine("Pasta", 600, listOf("me", "ana")), ItemLine("Steak", 400, listOf("ben"))))
        Splitting.shares(e).values.sum() shouldBe 1_100
        val e2 = GroupExpense("g", "Taxi", "ana", 900, "PHP", today, SplitMode.EQUAL, listOf(ShareLine("me", 1), ShareLine("ana", 1), ShareLine("ben", 1)))
        val bal = Splitting.balances(listOf(e, e2), listOf(Settlement("g", "ben", "me", 100, "PHP", today)), "PHP")
        bal.values.sum() shouldBe 0
        val plan = Splitting.simplify(bal)
        plan.size shouldBeLessThan 3
        // After applying the plan everyone is square.
        val after = bal.toMutableMap()
        plan.forEach { after[it.from] = after.getValue(it.from) + it.amountMinor; after[it.to] = after.getValue(it.to) - it.amountMinor }
        after.values.all { it == 0L } shouldBe true
    }

    test("lending balance with partial settlement and simple interest") {
        val gave = LendingEntry("p", LendDirection.GAVE, 10_000_00, "INR", LocalDate.of(2025, 11, 15), interestBp = 1_200)
        val back = LendingEntry("p", LendDirection.RECEIVED, 4_000_00, "INR", LocalDate.of(2026, 5, 15), settles = "x")
        Splitting.lendingBalance(listOf(gave, back), today)["INR"] shouldBe 6_000_00 + 1_200_00
    }

    test("settle links: UPI includes payee, amount and currency") {
        Splitting.settleLink("upi", "ana@okaxis", "Ana", "250.00", "INR", "Goa trip")!!.startsWith("upi://pay?pa=ana%40okaxis") shouldBe true
        Splitting.schemeLabel("upi") shouldBe "UPI"
        Splitting.schemeLabel("cashapp") shouldBe "Cash App"
        Splitting.schemeLabel("Pix") shouldBe "Pix"
        Splitting.settleLink("unknown", "x", "y", "1", "USD", "") shouldBe null
    }

    test("bills: paid and skipped occurrences, moved date, variable estimate is the median") {
        val r = Recurring("Power", amountMinor = 1_000, amountKind = app.penny.core.model.AmountKind.VARIABLE, currency = "INR", accountId = "a",
            schedule = Recurrence.monthly(LocalDate.of(2026, 9, 10), 10))
        val occ = listOf(
            Occurrence("r", LocalDate.of(2026, 10, 10), OccurrenceStatus.PAID, "t1"),
            Occurrence("r", LocalDate.of(2026, 11, 10), OccurrenceStatus.EDITED, movedTo = LocalDate.of(2026, 11, 20)),
        )
        val up = Bills.occurrences(listOf("r" to r), occ, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31), mapOf("r" to listOf(900, 1_500, 1_100)))
        up.map { it.date } shouldBe listOf(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 11, 20), LocalDate.of(2026, 12, 10))
        up[0].open shouldBe false
        up[2].amountMinor shouldBe 1_100
    }

    test("detects a monthly subscription from three steady charges") {
        val txs = (8..10).map { m -> Transaction(type = TxType.EXPENSE, amountMinor = 649_00, currency = "INR", accountId = "a", payee = "Netflix", date = LocalDate.of(2026, m, 3), tz = "UTC") }
        val found = Bills.detect(txs, emptyList())
        found.size shouldBe 1
        found[0].schedule.next(LocalDate.of(2026, 10, 3)) shouldBe LocalDate.of(2026, 11, 3)
    }

    test("emergency target averages over the months the book covers") {
        val t = app.penny.core.model.Transaction(app.penny.core.model.TxType.EXPENSE, 20_000_00, "INR", "a", categoryId = "rent", date = LocalDate.of(2026, 10, 5), tz = "UTC")
        Goals.emergencyTarget(listOf(t), setOf("rent"), 6, LocalDate.of(2026, 11, 16)) shouldBe 120_000_00
    }

    test("a prepayment on the loan start date shortens the loan and saves interest") {
        val loan = app.penny.core.model.Loan("Bike", app.penny.core.model.LoanKind.EMI, 100_000_00, "INR", 1000, 12, LocalDate.of(2026, 11, 16))
        val (months, saved) = Debt.prepaymentWhatIf(loan, LocalDate.of(2026, 11, 16), 20_000_00)
        (months > 0) shouldBe true
        (saved > 0) shouldBe true
    }

    test("a new goal with nothing saved is not behind before its date") {
        val g = Goal("Laptop", 60_000_00, "INR", due = LocalDate.of(2027, 4, 15))
        Goals.progress(g, 0, emptyList(), today, CycleRule.Monthly(1)).onTrack shouldBe true
        Goals.progress(g, 0, emptyList(), LocalDate.of(2027, 5, 1), CycleRule.Monthly(1)).onTrack shouldBe false
    }

    test("goal progress and per-period need") {
        val g = Goal("Laptop", 60_000_00, "INR", due = LocalDate.of(2027, 4, 30))
        val entries = listOf(GoalEntry("g", 10_000_00, LocalDate.of(2026, 9, 1)), GoalEntry("g", -1_000_00, LocalDate.of(2026, 10, 1)))
        val p = Goals.progress(g, Goals.saved("g", entries), entries, today, CycleRule.Monthly(1))
        p.savedMinor shouldBe 9_000_00
        p.remainingMinor shouldBe 51_000_00
        p.periodsLeft shouldBe 6
        p.perPeriodMinor shouldBe 8_500_00
    }

    test("remittance true cost: fee plus spread") {
        val r = Remittance("Me", "Mom", sentMinor = 100_000, sentCurrency = "AED", feeMinor = 1_500, rate = "22.30", receivedMinor = 22_300_00,
            receivedCurrency = "INR", midRate = "22.70", provider = "X", date = today)
        val c = RemitCircle.cost(r)
        c.feeBp shouldBe 150
        c.spreadBp shouldBe 176
        c.totalBp shouldBe 326
    }

    test("circle rounds follow turns and track who paid") {
        val c = Circle("Paluwagan", members = listOf(CircleMember("Ana", turn = 2), CircleMember("Me", turn = 1, isMe = true), CircleMember("Ben", turn = 3)),
            contributionMinor = 1_000_00, currency = "PHP", schedule = Recurrence.monthly(LocalDate.of(2026, 11, 1), 1))
        val contribs = listOf(CircleContribution("c", 1, "Ana", 1_000_00, today), CircleContribution("c", 1, "Me", 3_000_00, today, payout = true))
        val rounds = RemitCircle.rounds(c, contribs)
        rounds.map { it.recipient } shouldBe listOf("Me", "Ana", "Ben")
        rounds[0].potMinor shouldBe 3_000_00
        RemitCircle.myTurn(c, contribs) shouldBe 1
        RemitCircle.myNet(c, contribs) shouldBe 3_000_00
    }
})
