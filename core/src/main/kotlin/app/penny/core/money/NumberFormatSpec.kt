package app.penny.core.money

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/** How digits are grouped: 1,234,567 (STANDARD), 12,34,567 (INDIAN, lakh and crore) or no grouping. */
enum class Grouping { STANDARD, INDIAN, NONE }

/**
 * Locale-derived number display rules. Digits are always Latin; separators, grouping and the currency
 * symbol position come from the locale and the regional pack (LO-02, LO-03, LO-04).
 */
data class NumberFormatSpec(
    val decimalSeparator: Char,
    val groupSeparator: Char,
    val grouping: Grouping,
    val symbolFirst: Boolean,
    val symbolSpaced: Boolean,
    val localeTag: String,
) {
    companion object {
        fun forLocale(localeTag: String, grouping: Grouping? = null): NumberFormatSpec {
            val locale = latinDigits(Locale.forLanguageTag(localeTag))
            val symbols = DecimalFormatSymbols.getInstance(locale)
            val pattern = (NumberFormat.getCurrencyInstance(locale) as? DecimalFormat)?.toPattern().orEmpty()
            val positive = pattern.substringBefore(';')
            val symbolIndex = positive.indexOf('¤')
            val firstDigit = positive.indexOfFirst { it == '#' || it == '0' }
            val symbolFirst = symbolIndex in 0 until maxOf(firstDigit, 0) || (symbolIndex >= 0 && firstDigit < 0)
            val spaced = if (symbolFirst) {
                positive.getOrNull(symbolIndex + 1)?.isWhitespace() == true
            } else {
                positive.getOrNull(symbolIndex - 1)?.isWhitespace() == true
            }
            return NumberFormatSpec(
                decimalSeparator = symbols.monetaryDecimalSeparator,
                groupSeparator = symbols.groupingSeparator,
                grouping = grouping ?: Grouping.STANDARD,
                symbolFirst = symbolIndex < 0 || symbolFirst,
                symbolSpaced = spaced,
                localeTag = localeTag,
            )
        }

        private fun latinDigits(locale: Locale): Locale =
            Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("nu", "latn").build()

        /** Neutral spec used where no locale is known yet: 1,234.56. */
        val Default = NumberFormatSpec('.', ',', Grouping.STANDARD, symbolFirst = true, symbolSpaced = false, localeTag = "en-US")
    }
}

object CurrencySymbols {
    /** Locale-appropriate symbol, falling back to the ISO code. */
    fun symbol(currency: String, localeTag: String): String =
        runCatching { java.util.Currency.getInstance(currency).getSymbol(Locale.forLanguageTag(localeTag)) }
            .getOrDefault(currency)
}
