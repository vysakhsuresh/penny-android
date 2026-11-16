package app.penny.core.io

import app.penny.core.capture.ReceiptParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/** CP-07: a supermarket receipt with VAT reads total, tax, date and shop. */
class ReceiptVatTest : FunSpec({
    test("total beats subtotal, VAT is the tax, date and shop are read") {
        val text = "FRESH MART SUPERMARKET\nAl Barsha, Dubai\nDate: 14/11/2026 18:42\nMilk 1L 12.50\nBread 6.00\nSubtotal 42.50\nVAT 5% 2.13\nTOTAL 44.63\nPaid by card ****4421"
        val r = ReceiptParser.parse(text, "AED", LocalDate.of(2026, 11, 16))!!
        r.amountMinor shouldBe 4_463
        r.tax shouldBe 213
        r.date shouldBe LocalDate.of(2026, 11, 14)
        r.payee shouldBe "FRESH MART SUPERMARKET"
    }
})
