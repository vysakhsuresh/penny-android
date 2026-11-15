package app.penny.packs

import app.penny.core.money.CurrencyUnit
import app.penny.core.money.Grouping
import app.penny.core.money.MoneyFormatter
import app.penny.core.money.NumberFormatSpec
import app.penny.core.period.CycleRule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.ZoneId

class PackFilesTest : FunSpec({
    val dir = File("src/main/assets/packs")
    val repo = PackRepository { dir.listFiles()!!.filter { it.extension == "json" }.associate { it.name to it.readText() } }

    test("all launch packs load and are valid") {
        repo.all().map { it.id }.toSet() shouldBe setOf("IN", "PH", "US", "GB", "DE", "BR", "AE", "JP", "ID", "NG", "MX", "AU", "SG", "XX")
        repo.all().forEach { p ->
            p.id.lowercase() + ".json" shouldBe dir.listFiles()!!.first { it.nameWithoutExtension == p.id.lowercase() }.name
            CurrencyUnit.of(p.currency).code shouldBe p.currency
            ZoneId.of(p.timezone)
            p.typicalAmountChips.isNotEmpty() shouldBe true
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
        repo.forCountry("KE").id shouldBe "XX"
        repo.forCountry("IN").id shouldBe "IN"
    }
})
