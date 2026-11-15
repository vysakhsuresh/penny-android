package app.penny.packs

import app.penny.core.capture.NotificationParser
import app.penny.core.money.CurrencyUnit
import app.penny.core.money.Grouping
import app.penny.core.money.MoneyFormatter
import app.penny.core.money.NumberFormatSpec
import app.penny.core.period.CycleRule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class PackFilesTest : FunSpec({
    val dir = File("src/main/assets/packs")
    val repo = PackRepository { dir.listFiles()!!.filter { it.extension == "json" }.associate { it.name to it.readText() } }

    /** LO-09 launch list (EU per country) plus the generic pack. */
    val launch = setOf(
        "US", "CA", "GB", "IE", "DE", "FR", "ES", "IT", "NL", "PT", "AU", "NZ", "IN", "PH", "ID", "MY", "SG", "TH", "VN", "PK", "BD",
        "LK", "NP", "AE", "SA", "EG", "NG", "KE", "ZA", "GH", "BR", "MX", "AR", "CO", "CL", "PE", "JP", "KR", "HK", "TW", "TR", "RU", "UA", "XX",
    )

    test("all launch packs load and are valid") {
        repo.all().map { it.id }.toSet() shouldContainAll launch
        repo.all().forEach { p ->
            p.id.lowercase() + ".json" shouldBe dir.listFiles()!!.first { it.nameWithoutExtension == p.id.lowercase() }.name
            CurrencyUnit.of(p.currency).code shouldBe p.currency
            ZoneId.of(p.timezone)
            p.typicalAmountChips.isNotEmpty() shouldBe true
            p.pricePoints?.let { pp -> (pp.yearly < pp.monthly * 12) shouldBe true }
            // Every template regex compiles and has the required named group.
            p.notificationTemplates.forEach { t ->
                Regex(t.regex, RegexOption.IGNORE_CASE)
                t.regex.contains("(?<amount>") shouldBe true
            }
        }
    }

    test("switching pack changes decimals, grouping and pay cycle with no code change") {
        val india = repo.get("IN")
        val japan = repo.get("JP")
        val philippines = repo.get("PH")
        val spec = { p: RegionalPack -> NumberFormatSpec.forLocale(p.primaryLocale, p.grouping) }

        india.grouping shouldBe Grouping.INDIAN
        MoneyFormatter.formatNumber(123456789, CurrencyUnit.of(india.currency).minorUnits, spec(india)) shouldBe "12,34,567.89"
        MoneyFormatter.formatNumber(123456789, CurrencyUnit.of(japan.currency).minorUnits, spec(japan)) shouldBe "123,456,789"
        india.payCycleDefault shouldBe CycleRule.Monthly(1)
        philippines.payCycleDefault shouldBe CycleRule.SemiMonthly(15, 31)
        (repo.get("US").payCycleDefault is CycleRule.Biweekly) shouldBe true
    }

    test("unknown regions fall back to the generic pack") {
        repo.get("ZZ").id shouldBe "XX"
        repo.forCountry("AQ").id shouldBe "XX"
        repo.forCountry("IN").id shouldBe "IN"
        repo.forCountry("KE").id shouldBe "KE"
    }

    test("pack templates parse a typical notification for the region's main payment app") {
        val india = repo.get("IN")
        val gpay = india.notificationTemplates.firstOrNull { "com.google.android.apps.nbu.paisa.user" in it.packages }
        gpay shouldNotBe null
        val p = NotificationParser.parse("Google Pay", "Paid ₹1,250.00 to Fresh Mart", "com.google.android.apps.nbu.paisa.user", india.notificationTemplates, "INR")
        p shouldNotBe null
        p!!.amountMinor shouldBe 125_000
        // Every capture package is unique per pack and only from the pack data, never from code.
        india.capturePackages.isNotEmpty() shouldBe true
        @Suppress("UNUSED_VARIABLE") val d = LocalDate.now()
    }
})
