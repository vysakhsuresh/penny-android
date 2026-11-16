package app.penny.core.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** CP-08: imported bank narrations read as payees. */
class CleanPayeeTest : FunSpec({
    test("UPI narration") { Importers.cleanPayee("UPI-ZOMATO-zomato@hdfc") shouldBe "Zomato" }
    test("UPI with reference") { Importers.cleanPayee("UPI/631234567890/SWIGGY LTD/swiggy@icici") shouldBe "Swiggy Ltd" }
    test("NEFT credit") { Importers.cleanPayee("NEFT CR-ACME PAYROLL") shouldBe "Acme Payroll" }
    test("POS purchase") { Importers.cleanPayee("POS AMAZON PAY") shouldBe "Amazon Pay" }
    test("a clean name passes through") { Importers.cleanPayee("Blue Tokai Coffee") shouldBe "Blue Tokai Coffee" }
    test("nothing readable keeps the original") { Importers.cleanPayee("UPI-1234567") shouldBe "UPI-1234567" }
})
