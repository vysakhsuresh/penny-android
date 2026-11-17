package app.penny.core.money

/** Formats minor units for display. The only way amounts reach the UI (CLAUDE.md: numbers through core formatters). */
object MoneyFormatter {
    const val MINUS = '−'

    fun format(
        minor: Long,
        currency: String,
        spec: NumberFormatSpec,
        showSymbol: Boolean = true,
        symbol: String = CurrencySymbols.symbol(currency, spec.localeTag),
        signForPositive: Boolean = false,
    ): String {
        val unit = CurrencyUnit.of(currency)
        val number = formatNumber(minor, unit.minorUnits, spec)
        val sign = when {
            minor < 0 -> MINUS.toString()
            signForPositive && minor > 0 -> "+"
            else -> ""
        }
        if (!showSymbol) return sign + number
        val gap = if (spec.symbolSpaced) " " else ""
        return if (spec.symbolFirst) "$sign$symbol$gap$number" else "$sign$number$gap$symbol"
    }

    /** Absolute value of [minor] with [decimals] fraction digits, grouped per [spec]. */
    fun formatNumber(minor: Long, decimals: Int, spec: NumberFormatSpec): String {
        val digits = if (minor == Long.MIN_VALUE) "9223372036854775808" else kotlin.math.abs(minor).toString()
        val padded = digits.padStart(decimals + 1, '0')
        val integer = padded.substring(0, padded.length - decimals)
        val fraction = padded.substring(padded.length - decimals)
        val grouped = group(integer, spec)
        return if (decimals == 0) grouped else grouped + spec.decimalSeparator + fraction
    }

    fun group(integer: String, spec: NumberFormatSpec): String {
        if (spec.grouping == Grouping.NONE || integer.length <= 3) return integer
        val sep = spec.groupSeparator
        val head = integer.substring(0, integer.length - 3)
        val tail = integer.substring(integer.length - 3)
        val size = if (spec.grouping == Grouping.INDIAN) 2 else 3
        val parts = ArrayDeque<String>()
        var end = head.length
        while (end > 0) {
            val start = maxOf(0, end - size)
            parts.addFirst(head.substring(start, end))
            end = start
        }
        return parts.joinToString(sep.toString()) + sep + tail
    }

    /**
     * An amount as it is being typed ("12500+3000.5"), with each number's whole part grouped for reading
     * ("12,500+3,000.5"; lakh grouping where the region uses it). The fraction and operators are left as typed.
     */
    fun groupTyped(typed: String, spec: NumberFormatSpec): String {
        val out = StringBuilder()
        var i = 0
        while (i < typed.length) {
            if (!typed[i].isDigit()) { out.append(typed[i]); i++; continue }
            var j = i
            while (j < typed.length && typed[j].isDigit()) j++
            // Digits right after the decimal separator are a fraction: never grouped.
            val afterDecimal = i > 0 && typed[i - 1] == spec.decimalSeparator
            out.append(if (afterDecimal) typed.substring(i, j) else group(typed.substring(i, j), spec))
            i = j
        }
        return out.toString()
    }

    /** Plain editable text for an amount, e.g. "1234.5" becomes "1234,50" for a comma locale. No grouping. */
    fun toEditable(minor: Long, currency: String, spec: NumberFormatSpec): String {
        val unit = CurrencyUnit.of(currency)
        return formatNumber(minor, unit.minorUnits, spec.copy(grouping = Grouping.NONE)).let {
            if (minor < 0) "-$it" else it
        }
    }
}
