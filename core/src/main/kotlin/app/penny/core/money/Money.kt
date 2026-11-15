package app.penny.core.money

import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** ISO 4217 currency with its minor-unit exponent (JPY 0, USD 2, KWD 3). */
@Serializable
data class CurrencyUnit(val code: String, val minorUnits: Int) {
    init {
        require(code.length == 3 && code.all { it in 'A'..'Z' }) { "Currency code must be three upper-case letters: $code" }
        require(minorUnits in 0..4) { "Unsupported minor units $minorUnits for $code" }
    }

    companion object {
        /** Minor units from the ISO 4217 table shipped with the runtime; currencies with no minor unit (XAU) use 0. */
        fun of(code: String): CurrencyUnit {
            val upper = code.uppercase()
            val digits = runCatching { java.util.Currency.getInstance(upper).defaultFractionDigits }.getOrDefault(2)
            return CurrencyUnit(upper, if (digits < 0) 0 else digits)
        }
    }
}

/** Money as integer minor units. Never a floating point value anywhere in money math (SPEC §6 invariant 5). */
@Serializable
data class Money(val minor: Long, val currency: String) : Comparable<Money> {
    operator fun plus(other: Money): Money = Money(Math.addExact(minor, sameCurrency(other).minor), currency)
    operator fun minus(other: Money): Money = Money(Math.subtractExact(minor, sameCurrency(other).minor), currency)
    operator fun unaryMinus(): Money = Money(Math.negateExact(minor), currency)
    override fun compareTo(other: Money): Int = minor.compareTo(sameCurrency(other).minor)

    fun toDecimal(unit: CurrencyUnit = CurrencyUnit.of(currency)): BigDecimal = BigDecimal.valueOf(minor, unit.minorUnits)

    private fun sameCurrency(other: Money): Money {
        require(other.currency == currency) { "Currency mismatch: $currency vs ${other.currency}" }
        return other
    }

    companion object {
        fun zero(currency: String) = Money(0, currency)
    }
}

/** Largest amount accepted: 15 significant digits in minor units (TX-08). */
const val MAX_MINOR_DIGITS = 15
const val MAX_MINOR: Long = 999_999_999_999_999L
