package app.penny.core.money

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MoneyFormatterTest : FunSpec({
    val us = NumberFormatSpec.Default

    test("typed amounts are grouped as they are typed, fractions and operators untouched") {
        MoneyFormatter.groupTyped("1200", us) shouldBe "1,200"
        MoneyFormatter.groupTyped("12500+3000.5", us) shouldBe "12,500+3,000.5"
        MoneyFormatter.groupTyped("0.12345", us) shouldBe "0.12345"
        MoneyFormatter.groupTyped("1234567", us.copy(grouping = Grouping.INDIAN)) shouldBe "12,34,567"
        val de = NumberFormatSpec.forLocale("de-DE")
        MoneyFormatter.groupTyped("1234,5", de) shouldBe "1.234,5"
        MoneyFormatter.groupTyped("", us) shouldBe ""
    }
    val indian = us.copy(grouping = Grouping.INDIAN, localeTag = "en-IN")

    test("minor units come from ISO 4217: JPY 0, USD 2, KWD 3") {
        CurrencyUnit.of("JPY").minorUnits shouldBe 0
        CurrencyUnit.of("USD").minorUnits shouldBe 2
        CurrencyUnit.of("KWD").minorUnits shouldBe 3
        CurrencyUnit.of("INR").minorUnits shouldBe 2
    }

    test("JPY has no decimals") {
        MoneyFormatter.format(123456, "JPY", us, symbol = "¥") shouldBe "¥123,456"
        MoneyFormatter.formatNumber(5, 0, us) shouldBe "5"
    }

    test("symbols in a right-to-left script show as the ISO code; others keep their symbol") {
        CurrencySymbols.symbol("AED", "ar-AE") shouldBe "AED"
        CurrencySymbols.symbol("SAR", "ar-SA") shouldBe "SAR"
        CurrencySymbols.symbol("INR", "en-IN") shouldBe "₹"
    }

    test("KWD has three decimals") {
        MoneyFormatter.format(1234567, "KWD", us.copy(symbolSpaced = true), symbol = "KD") shouldBe "KD 1,234.567"
        MoneyFormatter.formatNumber(5, 3, us) shouldBe "0.005"
    }

    test("INR uses lakh and crore grouping") {
        MoneyFormatter.formatNumber(123456789, 2, indian) shouldBe "12,34,567.89"
        MoneyFormatter.formatNumber(1234567890123, 2, indian) shouldBe "12,34,56,78,901.23"
        MoneyFormatter.formatNumber(99900, 2, indian) shouldBe "999.00"
        MoneyFormatter.formatNumber(100000, 2, indian) shouldBe "1,000.00"
        MoneyFormatter.formatNumber(10000000, 2, indian) shouldBe "1,00,000.00"
    }

    test("the same amount with standard grouping") {
        MoneyFormatter.formatNumber(123456789, 2, us) shouldBe "1,234,567.89"
    }

    test("negative amounts use a true minus sign and positive can show plus") {
        MoneyFormatter.format(-1050, "USD", us, symbol = "$") shouldBe "−$10.50"
        MoneyFormatter.format(1050, "USD", us, symbol = "$", signForPositive = true) shouldBe "+$10.50"
    }

    test("German locale swaps separators and puts the symbol after") {
        val de = NumberFormatSpec.forLocale("de-DE")
        de.decimalSeparator shouldBe ','
        de.groupSeparator shouldBe '.'
        de.symbolFirst shouldBe false
        MoneyFormatter.format(123456, "EUR", de, symbol = "€") shouldBe "1.234,56 €"
    }

    test("Arabic (UAE) locale keeps Latin digits for RTL display") {
        val ar = NumberFormatSpec.forLocale("ar-AE")
        MoneyFormatter.formatNumber(123456, 2, ar).all { it.isDigit().not() || it in '0'..'9' } shouldBe true
    }

    test("editable text has no grouping and uses the locale decimal") {
        val de = NumberFormatSpec.forLocale("de-DE")
        MoneyFormatter.toEditable(123456, "EUR", de) shouldBe "1234,56"
        MoneyFormatter.toEditable(5000, "JPY", de) shouldBe "5000"
    }

    test("money arithmetic is exact and refuses mixed currencies") {
        (Money(10, "USD") + Money(5, "USD")) shouldBe Money(15, "USD")
        runCatching { Money(1, "USD") + Money(1, "EUR") }.isFailure shouldBe true
    }
})
