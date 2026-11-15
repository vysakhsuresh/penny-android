package app.penny.core.money

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Why an amount could not be accepted (TX-08). The UI maps each to a string resource. */
enum class AmountError { EMPTY, INVALID, NOT_POSITIVE, TOO_MANY_DECIMALS, TOO_LARGE, DIVIDE_BY_ZERO }

sealed interface AmountResult {
    /** [rounded] is true when an expression (not a typed literal) produced more decimals than the currency allows. */
    data class Ok(val minor: Long, val rounded: Boolean, val isExpression: Boolean) : AmountResult
    data class Invalid(val error: AmountError) : AmountResult
}

/**
 * Amount field calculator (TX-09): + - × ÷ * / %, parentheses, unary minus, locale decimal separator.
 * Evaluated with BigDecimal; never floating point. Validation follows TX-08: amount > 0, minor-unit precision
 * per currency, at most 15 digits.
 */
object AmountCalculator {
    private val MC = MathContext.DECIMAL128
    private val IGNORED_GROUP_CHARS = setOf(' ', ' ', ' ', '\'', '’')

    fun evaluate(input: String, currency: CurrencyUnit, spec: NumberFormatSpec): AmountResult {
        val text = normalise(input, spec) ?: return AmountResult.Invalid(AmountError.INVALID)
        if (text.isEmpty()) return AmountResult.Invalid(AmountError.EMPTY)
        val tokens = tokenize(text) ?: return AmountResult.Invalid(AmountError.INVALID)
        val parser = Parser(tokens)
        val value = try {
            parser.parseAll()
        } catch (_: ArithmeticException) {
            return AmountResult.Invalid(AmountError.DIVIDE_BY_ZERO)
        } catch (_: IllegalStateException) {
            return AmountResult.Invalid(AmountError.INVALID)
        }
        val isExpression = tokens.size > 1
        return validate(value, currency, isExpression)
    }

    fun validate(value: BigDecimal, currency: CurrencyUnit, isExpression: Boolean): AmountResult {
        if (value.signum() <= 0) return AmountResult.Invalid(AmountError.NOT_POSITIVE)
        val stripped = value.stripTrailingZeros()
        val tooPrecise = stripped.scale() > currency.minorUnits
        if (tooPrecise && !isExpression) return AmountResult.Invalid(AmountError.TOO_MANY_DECIMALS)
        val scaled = value.setScale(currency.minorUnits, RoundingMode.HALF_EVEN)
        if (scaled.signum() <= 0) return AmountResult.Invalid(AmountError.NOT_POSITIVE)
        val minor = scaled.movePointRight(currency.minorUnits)
        if (minor > BigDecimal.valueOf(MAX_MINOR)) return AmountResult.Invalid(AmountError.TOO_LARGE)
        return AmountResult.Ok(minor.longValueExact(), rounded = tooPrecise, isExpression = isExpression)
    }

    /** Strips grouping, maps the locale decimal separator and calculator glyphs to ASCII. Null if unusable. */
    internal fun normalise(input: String, spec: NumberFormatSpec): String? {
        val sb = StringBuilder()
        for (c in input.trim()) {
            when {
                c == spec.decimalSeparator -> sb.append('.')
                c == spec.groupSeparator || c in IGNORED_GROUP_CHARS -> Unit
                c == '×' || c == 'x' || c == 'X' -> sb.append('*')
                c == '÷' -> sb.append('/')
                c == MoneyFormatter.MINUS || c == '–' -> sb.append('-')
                c.isDigit() -> sb.append(Character.getNumericValue(c).toString())
                c in "+-*/%()." -> sb.append(c)
                else -> return null
            }
        }
        return sb.toString()
    }

    private sealed interface Tok {
        data class Num(val v: BigDecimal) : Tok
        data class Op(val c: Char) : Tok
    }

    private fun tokenize(s: String): List<Tok>? {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isDigit() || c == '.') {
                var j = i
                while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                val literal = s.substring(i, j)
                if (literal.count { it == '.' } > 1 || literal == ".") return null
                out += Tok.Num(BigDecimal(literal))
                i = j
            } else {
                out += Tok.Op(c)
                i++
            }
        }
        return out
    }

    private class Parser(val t: List<Tok>) {
        var p = 0
        fun parseAll(): BigDecimal {
            val v = expr()
            check(p == t.size) { "trailing tokens" }
            return v
        }

        private fun peekOp(): Char? = (t.getOrNull(p) as? Tok.Op)?.c

        private fun expr(): BigDecimal {
            var v = term()
            while (peekOp() == '+' || peekOp() == '-') {
                val op = peekOp(); p++
                val r = term()
                v = if (op == '+') v.add(r, MC) else v.subtract(r, MC)
            }
            return v
        }

        private fun term(): BigDecimal {
            var v = unary()
            while (peekOp() == '*' || peekOp() == '/') {
                val op = peekOp(); p++
                val r = unary()
                v = if (op == '*') v.multiply(r, MC) else v.divide(r, MC)
            }
            return v
        }

        private fun unary(): BigDecimal = when (peekOp()) {
            '-' -> { p++; unary().negate() }
            '+' -> { p++; unary() }
            else -> postfix(primary())
        }

        private fun primary(): BigDecimal {
            val tok = t.getOrNull(p) ?: error("unexpected end")
            p++
            return when (tok) {
                is Tok.Num -> tok.v
                is Tok.Op -> {
                    check(tok.c == '(') { "unexpected ${tok.c}" }
                    val v = expr()
                    check(peekOp() == ')') { "missing )" }
                    p++
                    v
                }
            }
        }

        private fun postfix(v: BigDecimal): BigDecimal {
            var r = v
            while (peekOp() == '%') { p++; r = r.divide(BigDecimal(100), MC) }
            return r
        }
    }
}
