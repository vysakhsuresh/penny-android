package app.penny.core.capture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** CP-03: a shared UPI debit message yields the amount and a readable payee from the VPA. */
class UpiPayeeTest : FunSpec({
    test("VPA handle becomes the payee") {
        val p = NotificationParser.generic("Rs.250.00 debited from A/c XX1234 on 16-11-26 to VPA swiggy@icici (UPI Ref 631234567890). Not you? Call 1800", "INR")!!
        p.amountMinor shouldBe 25_000
        p.payee shouldBe "Swiggy"
        p.income shouldBe false
    }
    test("handles with dots read as words") {
        NotificationParser.generic("Paid Rs 99 to blue.tokai@okaxis via UPI", "INR")!!.payee shouldBe "Blue Tokai"
    }
})
