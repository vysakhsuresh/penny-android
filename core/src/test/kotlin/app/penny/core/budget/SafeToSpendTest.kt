package app.penny.core.budget

import app.penny.core.model.Direction
import app.penny.core.model.FinancialAccount
import app.penny.core.model.AccountType
import app.penny.core.model.Split
import app.penny.core.model.Transaction
import app.penny.core.model.TransactionValidator
import app.penny.core.model.TxError
import app.penny.core.model.TxType
import app.penny.core.period.CycleRule
import app.penny.core.period.Periods
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class SafeToSpendTest : FunSpec({
    fun tx(type: TxType, amt: Long, day: Int, cat: String? = null, acct: String = "cash", to: String? = null) = Transaction(
        type = type, amountMinor = amt, currency = "INR", accountId = acct, toAccountId = to, categoryId = cat,
        date = LocalDate.of(2026, 11, day), tz = "Asia/Kolkata", direction = if (type == TxType.ADJUSTMENT) Direction.OUT else null,
    )
    val period = Periods.periodFor(LocalDate.of(2026, 11, 10), CycleRule.Monthly(1))

    test("income minus spend, refunds reduce spend, transfers and excluded categories are ignored") {
        val txs = listOf(
            tx(TxType.INCOME, 50_000_00, 1),
            tx(TxType.EXPENSE, 10_000_00, 2, "food"),
            tx(TxType.REFUND, 1_000_00, 3, "food"),
            tx(TxType.TRANSFER, 5_000_00, 4, acct = "bank", to = "cash"),
            tx(TxType.EXPENSE, 2_000_00, 5, "save"),
            tx(TxType.ADJUSTMENT, 300_00, 5),
            tx(TxType.EXPENSE, 9_999_00, 1).copy(date = LocalDate.of(2026, 10, 31)),
        )
        val s = SafeToSpendCalculator.compute(txs, period, LocalDate.of(2026, 11, 10), "INR", setOf("save"))
        s.incomeMinor shouldBe 50_000_00
        s.spentMinor shouldBe 9_000_00
        s.safeMinor shouldBe 41_000_00
        s.daysLeft shouldBe 20
        s.perDayMinor shouldBe 41_000_00 / 21
    }

    test("split lines count separately; the part in an excluded category (lent to friends) is not spend") {
        val group = tx(TxType.EXPENSE, 1000, 2, "food").copy(splits = listOf(Split(250, "food"), Split(750, "loan.given")))
        val s = SafeToSpendCalculator.compute(listOf(tx(TxType.INCOME, 5000, 1), group), period, LocalDate.of(2026, 11, 10), "INR", setOf("loan.given"))
        s.spentMinor shouldBe 250
        s.safeMinor shouldBe 4750
    }

    test("income in an excluded category does not count as income") {
        val txs = listOf(tx(TxType.INCOME, 5000, 1), tx(TxType.INCOME, 900, 2, "loan.given"))
        SafeToSpendCalculator.compute(txs, period, LocalDate.of(2026, 11, 10), "INR", setOf("loan.given")).incomeMinor shouldBe 5000
    }

    test("pace turns on only after day 3 when spend share is 15 points ahead") {
        val txs = listOf(tx(TxType.INCOME, 30_000, 1), tx(TxType.EXPENSE, 15_000, 2))
        SafeToSpendCalculator.compute(txs, period, LocalDate.of(2026, 11, 3), "INR", emptySet()).aheadOfPace shouldBe false
        SafeToSpendCalculator.compute(txs, period, LocalDate.of(2026, 11, 5), "INR", emptySet()).aheadOfPace shouldBe true
        SafeToSpendCalculator.compute(txs, period, LocalDate.of(2026, 11, 20), "INR", emptySet()).aheadOfPace shouldBe false
    }

    test("foreign-currency spend without a rate is counted as unconverted, never guessed") {
        val txs = listOf(tx(TxType.EXPENSE, 500, 2).copy(currency = "USD"))
        SafeToSpendCalculator.compute(txs, period, LocalDate.of(2026, 11, 5), "INR", emptySet()).unconvertedCount shouldBe 1
    }

    test("transfers move money between both accounts and never double count") {
        val cash = FinancialAccount("Cash", AccountType.CASH, "INR", openingBalanceMinor = 1000)
        val bank = FinancialAccount("Bank", AccountType.BANK, "INR", openingBalanceMinor = 5000)
        val t = listOf(tx(TxType.TRANSFER, 700, 3, acct = "bank", to = "cash"))
        Ledger.balance("cash", cash, t) shouldBe 1700
        Ledger.balance("bank", bank, t) shouldBe 4300
    }

    test("validation: positive amount, account, transfer target, splits sum") {
        TransactionValidator.validate(tx(TxType.EXPENSE, 0, 1)) shouldContain TxError.AMOUNT_NOT_POSITIVE
        TransactionValidator.validate(tx(TxType.TRANSFER, 10, 1)) shouldContain TxError.TRANSFER_TARGET_REQUIRED
        TransactionValidator.validate(tx(TxType.TRANSFER, 10, 1, to = "cash")) shouldContain TxError.TRANSFER_SAME_ACCOUNT
        TransactionValidator.validate(tx(TxType.EXPENSE, 10, 1).copy(splits = listOf(Split(4), Split(5)))) shouldContain
            TxError.SPLITS_DO_NOT_SUM
        TransactionValidator.validate(
            tx(TxType.TRANSFER, 10, 1, to = "usd").copy(toCurrency = "USD"),
        ) shouldContain TxError.TRANSFER_TO_AMOUNT_REQUIRED
        TransactionValidator.validate(tx(TxType.EXPENSE, 10, 1)).shouldBeEmpty()
    }
})
