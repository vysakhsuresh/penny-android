package app.penny.core.budget

import app.penny.core.model.FxRate
import app.penny.core.money.CurrencyUnit
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/**
 * AC-04: converts with the rate on (or the latest before) the transaction date. Inverse rates and one cross via any
 * shared currency are derived. Rates are decimal strings; all math is BigDecimal, results round half-even to minor units.
 */
class FxTable(rates: List<FxRate>) : FxConverter {
    private val byPair: Map<Pair<String, String>, List<Pair<LocalDate, BigDecimal>>> = buildMap<Pair<String, String>, MutableList<Pair<LocalDate, BigDecimal>>> {
        rates.forEach { r ->
            val v = r.rate.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@forEach
            getOrPut(r.from to r.to) { mutableListOf() }.add(r.date to v)
            getOrPut(r.to to r.from) { mutableListOf() }.add(r.date to BigDecimal.ONE.divide(v, MC))
        }
    }.mapValues { (_, v) -> v.sortedBy { it.first } }

    private val currencies = byPair.keys.flatMap { listOf(it.first, it.second) }.toSet()

    fun rate(from: String, to: String, on: LocalDate): BigDecimal? {
        if (from == to) return BigDecimal.ONE
        direct(from, to, on)?.let { return it }
        for (via in currencies) {
            if (via == from || via == to) continue
            val a = direct(from, via, on) ?: continue
            val b = direct(via, to, on) ?: continue
            return a.multiply(b, MC)
        }
        return null
    }

    private fun direct(from: String, to: String, on: LocalDate): BigDecimal? {
        val list = byPair[from to to] ?: return null
        // Latest rate on or before the date; fall back to the earliest known rate for dates before any rate.
        return list.lastOrNull { !it.first.isAfter(on) }?.second ?: list.firstOrNull()?.second
    }

    override fun convert(minor: Long, from: String, to: String, on: LocalDate): Long? {
        if (from == to) return minor
        val r = rate(from, to, on) ?: return null
        return convertWith(minor, from, to, r)
    }

    companion object {
        private val MC = MathContext(20, RoundingMode.HALF_EVEN)

        fun convertWith(minor: Long, from: String, to: String, rate: BigDecimal): Long {
            val fu = CurrencyUnit.of(from).minorUnits
            val tu = CurrencyUnit.of(to).minorUnits
            val major = BigDecimal.valueOf(minor, fu)
            return major.multiply(rate, MC).setScale(tu, RoundingMode.HALF_EVEN).unscaledValue().longValueExact()
        }

        /** Implied rate of a cross-currency transfer: received per one unit sent (TX-01). */
        fun impliedRate(sentMinor: Long, sentCurrency: String, receivedMinor: Long, receivedCurrency: String): BigDecimal? {
            if (sentMinor <= 0) return null
            val s = BigDecimal.valueOf(sentMinor, CurrencyUnit.of(sentCurrency).minorUnits)
            val r = BigDecimal.valueOf(receivedMinor, CurrencyUnit.of(receivedCurrency).minorUnits)
            return r.divide(s, MathContext(10, RoundingMode.HALF_EVEN))
        }
    }
}
