package app.penny.core.io

import app.penny.core.capture.AmountText
import app.penny.core.capture.NotificationParser
import app.penny.core.capture.NotificationTemplate
import app.penny.core.capture.ReceiptParser
import app.penny.core.capture.VoiceGrammar
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.LocalDate

class IoCaptureTest : FunSpec({
    val today = LocalDate.of(2026, 11, 15)

    test("csv round trip with quotes, delimiters, newlines; formula injection neutralised") {
        val rows = listOf(listOf("a,b", "say \"hi\"", "line\nbreak"), listOf("=SUM(A1)", "-12.5", "x"))
        val back = Csv.parse(Csv.write(rows))
        back[0] shouldContainExactly rows[0]
        back[1] shouldContainExactly listOf("'=SUM(A1)", "-12.5", "x")
        Csv.detectDelimiter("Datum;Betrag;Text\n") shouldBe ';'
    }

    test("csv import with German numbers and debit/credit columns") {
        val text = "Datum;Soll;Haben;Beschreibung\n01.11.2026;1.234,50;;Miete\n02.11.2026;;2.000,00;Gehalt\n"
        val m = CsvMapping("Bank", dateCol = 0, debitCol = 1, creditCol = 2, payeeCol = 3, datePattern = "dd.MM.yyyy", numberLocale = "de-DE")
        val r = Importers.csv(text, m, "EUR")
        r.errors shouldBe emptyList()
        r.rows.map { it.amountMinor } shouldContainExactly listOf(-123_450L, 200_000L)
        r.rows[0].payee shouldBe "Miete"
    }

    test("preset detection: YNAB register") {
        val header = listOf("Account", "Flag", "Date", "Payee", "Category Group/Category", "Memo", "Outflow", "Inflow", "Cleared")
        val (p, m) = Importers.detectPreset(header)!!
        p.app shouldBe "YNAB"
        m.debitCol shouldBe 6
        m.creditCol shouldBe 7
    }

    test("ofx, qif, camt.053 and mt940") {
        val ofx = "<OFX><CURDEF>USD<STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261101120000<TRNAMT>-42.10<FITID>abc<NAME>Shell</STMTTRN></OFX>"
        Importers.ofx(ofx, "EUR").rows.single().let { it.amountMinor shouldBe -4_210; it.currency shouldBe "USD"; it.externalId shouldBe "ofx:abc" }
        val qif = "!Type:Bank\nD11/02/2026\nT-15.00\nPCafe\nLFood\n^\nD11/03/2026\nT-100.00\nL[Savings]\n^\n"
        val q = Importers.qif(qif, "USD")
        q.rows.size shouldBe 2
        q.rows[1].transferAccount shouldBe "Savings"
        val camt = "<Ntry><Amt Ccy=\"EUR\">12.30</Amt><CdtDbtInd>DBIT</CdtDbtInd><BookgDt><Dt>2026-11-04</Dt></BookgDt><AcctSvcrRef>R1</AcctSvcrRef><Nm>Bakery</Nm></Ntry>"
        Importers.camt053(camt).rows.single().amountMinor shouldBe -1_230
        val mt = ":60F:C261101EUR1000,00\n:61:2611051105D25,50NTRFNONREF\n:86:Coffee shop\n"
        Importers.mt940(mt, "EUR").rows.single().let { it.amountMinor shouldBe -2_550; it.payee shouldBe "Coffee shop" }
    }

    test("amount text handles lakh, European and plain formats") {
        AmountText.toMinor("12,34,567.50", "INR") shouldBe 1_234_567_50
        AmountText.toMinor("1.234,56", "EUR") shouldBe 123_456
        AmountText.toMinor("1,234", "JPY") shouldBe 1_234
        AmountText.toMinor("12.345", "KWD") shouldBe 12_345
        AmountText.toMinor("1.500", "IDR") shouldBe 1_500_00 // IDR has 2 ISO minor units; "1.500" is one thousand five hundred
    }

    test("notification: template first, generic fallback, OTP ignored") {
        val t = NotificationTemplate("gpay_in", listOf("com.google.android.apps.nbu.paisa.user"), "(?:Paid|Sent) ₹(?<amount>[0-9,.]+) to (?<payee>[^\\n]+)", currency = "INR")
        val p = NotificationParser.parse("Google Pay", "Paid ₹1,250.00 to Fresh Mart", "com.google.android.apps.nbu.paisa.user", listOf(t), "INR")!!
        p.amountMinor shouldBe 125_000
        p.payee shouldBe "Fresh Mart"
        p.templateId shouldBe "gpay_in"
        val g = NotificationParser.parse("GCash", "You have sent PHP 500.00 to JUAN DELA CRUZ on 11-15-2026.", "com.globe.gcash.android", emptyList(), "PHP")!!
        g.amountMinor shouldBe 50_000
        g.income shouldBe false
        g.payee shouldBe "JUAN DELA CRUZ"
        NotificationParser.parse("Bank", "Your OTP is 482913. Do not share.", "x", emptyList(), "INR") shouldBe null
        NotificationParser.parse("Monzo", "You received £20.00 from Alex", "co.uk.getmondo", emptyList(), "GBP")!!.income shouldBe true
    }

    test("receipt OCR text: total, tax, date, merchant") {
        val text = """
            FRESH MART
            123 Main Road
            Date: 14/11/2026
            Milk          60.00
            Bread         45.00
            Subtotal     105.00
            GST 5%         5.25
            TOTAL        110.25
        """.trimIndent()
        val r = ReceiptParser.parse(text, "INR", today)!!
        r.amountMinor shouldBe 11_025
        r.tax shouldBe 525
        r.date shouldBe LocalDate.of(2026, 11, 14)
        r.payee shouldBe "FRESH MART"
        r.lines.map { it.first } shouldContainExactly listOf("Milk", "Bread")
    }

    test("voice: digits and words, yesterday, category, account, people") {
        val g = VoiceGrammar("INR", today, mapOf("groceries" to "cat-groc"), mapOf("hdfc" to "acct-hdfc"))
        val r = g.parse("spent 250 on groceries at DMart yesterday from HDFC split with Ana and Ben")
        r.amountMinor shouldBe 25_000
        r.categoryId shouldBe "cat-groc"
        r.accountId shouldBe "acct-hdfc"
        r.date shouldBe today.minusDays(1)
        r.payee shouldBe "Dmart"
        r.people shouldContainExactly listOf("Ana", "Ben")
        g.parse("paanch sau rupaye chai").amountMinor shouldBe 50_000
        g.parse("two thousand five hundred rent").amountMinor shouldBe 2_500_00
        g.parse("got salary 50k").income shouldBe true
        g.parse("got salary 50k").amountMinor shouldNotBe null
    }
})
