package app.penny.core.money

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class AmountCalculatorTest : FunSpec({
    val us = NumberFormatSpec.Default
    val de = NumberFormatSpec.forLocale("de-DE")
    val usd = CurrencyUnit.of("USD")
    val jpy = CurrencyUnit.of("JPY")
    val kwd = CurrencyUnit.of("KWD")

    fun ok(s: String, c: CurrencyUnit = usd, spec: NumberFormatSpec = us) =
        (AmountCalculator.evaluate(s, c, spec) as AmountResult.Ok).minor
    fun err(s: String, c: CurrencyUnit = usd, spec: NumberFormatSpec = us) =
        (AmountCalculator.evaluate(s, c, spec) as AmountResult.Invalid).error

    test("calculator handles + - × ÷ % and parentheses") {
        ok("120+80") shouldBe 20000
        ok("(10+5)×2") shouldBe 3000
        ok("100÷4") shouldBe 2500
        ok("200-10%*200") shouldBe 18000
        ok("50%") shouldBe 50
    }

    test("division that does not terminate rounds half-even and is flagged") {
        val r = AmountCalculator.evaluate("100/3", usd, us)
        r.shouldBeInstanceOf<AmountResult.Ok>()
        r.minor shouldBe 3333
        r.rounded shouldBe true
    }

    test("typed literals must respect currency decimals") {
        err("12.5", jpy) shouldBe AmountError.TOO_MANY_DECIMALS
        ok("1250", jpy) shouldBe 1250
        ok("1.234", kwd) shouldBe 1234
        err("1.2345", kwd) shouldBe AmountError.TOO_MANY_DECIMALS
        err("1.234", usd) shouldBe AmountError.TOO_MANY_DECIMALS
    }

    test("zero, negative, empty, garbage and too large are rejected") {
        err("0") shouldBe AmountError.NOT_POSITIVE
        err("5-10") shouldBe AmountError.NOT_POSITIVE
        err("") shouldBe AmountError.EMPTY
        err("12abc") shouldBe AmountError.INVALID
        err("(1+2") shouldBe AmountError.INVALID
        err("1/0") shouldBe AmountError.DIVIDE_BY_ZERO
        err("10000000000000") shouldBe AmountError.TOO_LARGE
        ok("9999999999999.99") shouldBe 999_999_999_999_999L
    }

    test("locale decimal and grouping separators are honoured") {
        ok("12,50", spec = de) shouldBe 1250
        ok("1.234,5", spec = de) shouldBe 123450
        ok("1,234.5") shouldBe 123450
        ok("1 234.5") shouldBe 123450
    }

    test("a misplaced group separator is rejected, not silently dropped") {
        err("1,5") shouldBe AmountError.INVALID
        err("12.50", spec = de) shouldBe AmountError.INVALID
        err("1.5,2") shouldBe AmountError.INVALID
        err(",500") shouldBe AmountError.INVALID
        err("1,5000") shouldBe AmountError.INVALID
        ok("1,500") shouldBe 150000
        ok("1,500,000.25") shouldBe 150000025
        ok("1,00,000", CurrencyUnit.of("INR")) shouldBe 10000000
        ok("1,500+2,250") shouldBe 375000
    }
})
