package app.penny.domain

import app.penny.core.model.Remittance
import app.penny.domain.ops.remittanceInAccount
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/** RM-01: money sent abroad from an account in the other currency moves it at the transfer's own rate. */
class RemittanceAccountTest : FunSpec({
    val r = Remittance("Me", "Amma", sentMinor = 100_000, sentCurrency = "AED", feeMinor = 1_500, rate = "22.3", receivedMinor = 2_230_000, receivedCurrency = "INR", provider = "", date = LocalDate.of(2026, 11, 16))

    test("AED sent from a rupee account takes sent plus fee at 22.3") { remittanceInAccount(r, "INR") shouldBe 2_263_450 }
    test("same currency needs no conversion") { remittanceInAccount(r, "AED") shouldBe null }
})
