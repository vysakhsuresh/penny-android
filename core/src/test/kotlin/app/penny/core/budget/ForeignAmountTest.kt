package app.penny.core.budget

import app.penny.core.model.AccountType
import app.penny.core.model.FinancialAccount
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/** AC-04: a foreign-currency spend moves the account by its amount in the account's currency. */
class ForeignAmountTest : FunSpec({
    val cash = FinancialAccount("Cash", AccountType.CASH, "INR")
    val d = LocalDate.of(2026, 11, 16)

    test("uses the account-currency amount when present") {
        val t = Transaction(TxType.EXPENSE, 101_500, "AED", "c", date = d, tz = "UTC", accountAmountMinor = 2_263_450)
        Ledger.balance("c", cash, listOf(t)) shouldBe -2_263_450
    }
    test("future-dated transactions do not move today's balance") {
        val t = Transaction(TxType.EXPENSE, 1_200_000, "INR", "c", date = d.plusDays(14), tz = "UTC")
        Ledger.balance("c", cash, listOf(t), d) shouldBe 0
    }
})
