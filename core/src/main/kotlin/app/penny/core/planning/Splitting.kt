package app.penny.core.planning

import app.penny.core.budget.FxTable
import app.penny.core.model.GroupExpense
import app.penny.core.model.LendDirection
import app.penny.core.model.LendingEntry
import app.penny.core.model.Settlement
import app.penny.core.model.SplitMode
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Debt3(val from: String, val to: String, val amountMinor: Long)

/** PE-01, PE-03, US-10. All amounts are minor units; remainders go to the earliest members so totals always match. */
object Splitting {

    /** Each member's share of an expense in the expense currency. Sum equals [GroupExpense.amountMinor] exactly. */
    fun shares(e: GroupExpense): Map<String, Long> {
        val total = e.amountMinor
        val lines = e.shares.filter { it.value > 0 }
        return when (e.mode) {
            SplitMode.EQUAL -> distribute(total, lines.map { it.memberId to 1L })
            SplitMode.SHARES -> distribute(total, lines.map { it.memberId to it.value })
            SplitMode.PERCENT -> distribute(total, lines.map { it.memberId to it.value })
            SplitMode.EXACT -> lines.associate { it.memberId to it.value }
            SplitMode.ITEMS -> {
                val acc = linkedMapOf<String, Long>()
                e.items.forEach { item ->
                    distribute(item.amountMinor, item.memberIds.map { it to 1L }).forEach { (m, v) -> acc[m] = (acc[m] ?: 0) + v }
                }
                val itemsTotal = acc.values.sum()
                // Tax/tip not itemised: spread proportionally.
                if (itemsTotal in 1 until total) {
                    distribute(total - itemsTotal, acc.map { it.key to it.value }).forEach { (m, v) -> acc[m] = acc.getValue(m) + v }
                }
                acc
            }
        }
    }

    fun validate(e: GroupExpense): Boolean = when (e.mode) {
        SplitMode.EXACT -> e.shares.sumOf { it.value } == e.amountMinor
        SplitMode.PERCENT -> e.shares.sumOf { it.value } == 10_000L
        SplitMode.ITEMS -> e.items.isNotEmpty() && e.items.sumOf { it.amountMinor } <= e.amountMinor && e.items.all { it.memberIds.isNotEmpty() }
        else -> e.shares.any { it.value > 0 }
    }

    /** Largest-remainder distribution by weights. */
    fun distribute(total: Long, weights: List<Pair<String, Long>>): Map<String, Long> {
        val sum = weights.sumOf { it.second }
        if (sum <= 0 || weights.isEmpty()) return emptyMap()
        val base = weights.map { (id, w) ->
            id to java.math.BigInteger.valueOf(total).multiply(java.math.BigInteger.valueOf(w)).divide(java.math.BigInteger.valueOf(sum)).toLong()
        }
        var rest = total - base.sumOf { it.second }
        val out = linkedMapOf<String, Long>()
        base.forEach { (id, v) -> out[id] = (out[id] ?: 0) + v }
        val ids = weights.map { it.first }
        var i = 0
        while (rest > 0 && ids.isNotEmpty()) { out[ids[i % ids.size]] = out.getValue(ids[i % ids.size]) + 1; rest--; i++ }
        return out
    }

    /**
     * Net balance per member in the group's base currency: positive = is owed, negative = owes.
     * Expenses in other currencies use [GroupExpense.rateToBase]; settlements reduce balances.
     */
    fun balances(expenses: List<GroupExpense>, settlements: List<Settlement>, base: String): Map<String, Long> {
        val bal = linkedMapOf<String, Long>()
        fun add(id: String, v: Long) { bal[id] = (bal[id] ?: 0) + v }
        // Disputed expenses still count (PE-07: the flag notifies; no money moves inside Penny).
        expenses.forEach { e ->
            val rate = e.rateToBase?.toBigDecimalOrNull()
            fun conv(v: Long) = if (e.currency == base || rate == null) v else FxTable.convertWith(v, e.currency, base, rate)
            val sh = shares(e)
            add(e.payerId, conv(e.amountMinor))
            sh.forEach { (m, v) -> add(m, -conv(v)) }
            // Rounding of conversions can leave a unit: give it to the payer so the sum is zero.
            val drift = bal.values.sum()
            if (drift != 0L) add(e.payerId, -drift)
        }
        settlements.forEach { s -> add(s.fromId, s.amountMinor); add(s.toId, -s.amountMinor) }
        return bal
    }

    /** Simplify debts: greedy match of largest debtor with largest creditor; at most n−1 transfers. */
    fun simplify(balances: Map<String, Long>): List<Debt3> {
        val cred = balances.filter { it.value > 0 }.map { it.key to it.value }.toMutableList()
        val debt = balances.filter { it.value < 0 }.map { it.key to -it.value }.toMutableList()
        val out = mutableListOf<Debt3>()
        while (cred.isNotEmpty() && debt.isNotEmpty()) {
            cred.sortByDescending { it.second }
            debt.sortByDescending { it.second }
            val (c, cv) = cred[0]
            val (d, dv) = debt[0]
            val x = minOf(cv, dv)
            out += Debt3(d, c, x)
            if (cv == x) cred.removeAt(0) else cred[0] = c to cv - x
            if (dv == x) debt.removeAt(0) else debt[0] = d to dv - x
        }
        return out
    }

    /** Pairwise debts without simplification (who owes whom directly from each expense). */
    fun pairwise(expenses: List<GroupExpense>, settlements: List<Settlement>, base: String): List<Debt3> {
        val m = linkedMapOf<Pair<String, String>, Long>()
        expenses.forEach { e ->
            val rate = e.rateToBase?.toBigDecimalOrNull()
            shares(e).forEach { (who, v) ->
                if (who == e.payerId) return@forEach
                val c = if (e.currency == base || rate == null) v else FxTable.convertWith(v, e.currency, base, rate)
                m[who to e.payerId] = (m[who to e.payerId] ?: 0) + c
            }
        }
        settlements.forEach { s -> m[s.fromId to s.toId] = (m[s.fromId to s.toId] ?: 0) - s.amountMinor }
        val net = linkedMapOf<Pair<String, String>, Long>()
        m.forEach { (k, v) ->
            val rev = k.second to k.first
            if (rev in net) net[rev] = net.getValue(rev) - v else net[k] = v
        }
        return net.mapNotNull { (k, v) ->
            when {
                v > 0 -> Debt3(k.first, k.second, v)
                v < 0 -> Debt3(k.second, k.first, -v)
                else -> null
            }
        }
    }

    // ------------------------------------------------------------ Lending (PE-01, PE-02)

    /** Balance with one person per currency: positive = to receive, negative = to give. Interest is simple, to [today]. */
    fun lendingBalance(entries: List<LendingEntry>, today: LocalDate): Map<String, Long> =
        entries.groupBy { it.currency }.mapValues { (_, list) ->
            list.sumOf { e ->
                val sign = if (e.direction == LendDirection.GAVE) 1 else -1
                sign * (e.amountMinor + simpleInterest(e, today))
            }
        }

    fun simpleInterest(e: LendingEntry, today: LocalDate): Long {
        if (e.interestBp <= 0 || e.settles != null) return 0
        val days = maxOf(0, ChronoUnit.DAYS.between(e.date, today))
        return BigDecimal(e.amountMinor).multiply(BigDecimal(e.interestBp)).multiply(BigDecimal(days))
            .divide(BigDecimal(10_000L * 365), 0, RoundingMode.HALF_EVEN).toLong()
    }

    /**
     * US-10 / PE-03 settle-up deep link from pack schemes. Supported: upi, gcash/maya (copy), paynow, pix, paypal,
     * venmo, sepa. Unknown schemes return null and the UI offers "mark as paid" and share-sheet text instead.
     */
    /** How a pack's settlement scheme is written for people: the lowercase link keys get their proper names. */
    fun schemeLabel(scheme: String): String = when (scheme.lowercase()) {
        "upi" -> "UPI"
        "paypal" -> "PayPal"
        "venmo" -> "Venmo"
        "cashapp" -> "Cash App"
        "revolut" -> "Revolut"
        "monzo" -> "Monzo"
        else -> scheme
    }

    fun settleLink(scheme: String, handle: String, name: String, amountMajor: String, currency: String, note: String): String? {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        if (handle.isBlank()) return null
        return when (scheme.lowercase()) {
            "upi" -> "upi://pay?pa=${enc(handle)}&pn=${enc(name)}&am=$amountMajor&cu=$currency&tn=${enc(note)}"
            "paypal" -> "https://www.paypal.me/${enc(handle)}/$amountMajor$currency"
            "venmo" -> "venmo://paycharge?txn=pay&recipients=${enc(handle)}&amount=$amountMajor&note=${enc(note)}"
            "cashapp" -> "https://cash.app/\$${enc(handle)}/$amountMajor"
            "revolut" -> "https://revolut.me/${enc(handle)}"
            "monzo" -> "https://monzo.me/${enc(handle)}/$amountMajor"
            else -> null
        }
    }
}
