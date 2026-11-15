package app.penny.core.rules

import app.penny.core.model.Rule
import app.penny.core.model.RuleActions
import app.penny.core.model.RuleCondition
import app.penny.core.model.RuleField
import app.penny.core.model.RuleOp
import app.penny.core.model.Split
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

/** Extra facts about a captured transaction that rules can match on (RU-01). */
data class RuleContext(val app: String? = null, val accountName: (String) -> String? = { null })

data class RuleResult(val tx: Transaction, val matched: List<String>, val autoPost: Boolean?, val ignore: Boolean)

/** RU-01/RU-02: ordered rules, first match wins unless the rule says continue. Pure: same input, same output. */
object RuleEngine {

    fun apply(tx: Transaction, rules: List<Pair<String, Rule>>, ctx: RuleContext = RuleContext()): RuleResult {
        var t = tx
        val matched = mutableListOf<String>()
        var autoPost: Boolean? = null
        var ignore = false
        for ((id, r) in rules.filter { it.second.enabled }.sortedBy { it.second.order }) {
            if (!matches(t, r, ctx)) continue
            matched += id
            t = act(t, r.actions)
            r.actions.autoPost?.let { autoPost = it }
            if (r.actions.ignore) ignore = true
            if (!r.continueAfter) break
        }
        return RuleResult(t, matched, autoPost, ignore)
    }

    fun matches(t: Transaction, r: Rule, ctx: RuleContext = RuleContext()): Boolean {
        if (r.conditions.isEmpty()) return false
        val results = r.conditions.asSequence().map { cond(t, it, ctx) }
        return if (r.matchAll) results.all { it } else results.any { it }
    }

    private fun cond(t: Transaction, c: RuleCondition, ctx: RuleContext): Boolean = when (c.field) {
        RuleField.PAYEE -> text(t.payee.orEmpty(), c)
        RuleField.NOTE -> text(t.note, c)
        RuleField.SOURCE -> text(t.source, c)
        RuleField.APP -> text(ctx.app.orEmpty(), c)
        RuleField.CURRENCY -> text(t.currency, c)
        RuleField.ACCOUNT -> c.op == RuleOp.IN && t.accountId in c.value.split(',') ||
            c.op == RuleOp.EQUALS && t.accountId == c.value || text(ctx.accountName(t.accountId).orEmpty(), c)
        RuleField.CATEGORY -> t.categoryId == c.value || (c.op == RuleOp.IN && t.categoryId in c.value.split(','))
        RuleField.TYPE -> t.type.name.equals(c.value, true) || (c.op == RuleOp.IN && c.value.split(',').any { it.equals(t.type.name, true) })
        RuleField.AMOUNT -> number(t.amountMinor, c)
        RuleField.WEEKDAY -> c.value.split(',').any { it.trim().equals(t.date.dayOfWeek.name, true) }
        RuleField.HOUR -> t.time?.let { number(it.hour.toLong(), c) } ?: false
    }

    fun normalise(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()

    private fun text(v: String, c: RuleCondition): Boolean {
        val a = normalise(v)
        val b = normalise(c.value)
        return when (c.op) {
            RuleOp.CONTAINS -> b.isNotEmpty() && a.contains(b)
            RuleOp.EQUALS -> a == b
            RuleOp.STARTS_WITH -> a.startsWith(b)
            RuleOp.ENDS_WITH -> a.endsWith(b)
            RuleOp.REGEX -> runCatching { Regex(c.value, RegexOption.IGNORE_CASE).containsMatchIn(v) }.getOrDefault(false)
            RuleOp.IN -> c.value.split(',').map(::normalise).any { it == a }
            else -> false
        }
    }

    private fun number(v: Long, c: RuleCondition): Boolean {
        val a = c.value.toLongOrNull() ?: return false
        return when (c.op) {
            RuleOp.EQUALS -> v == a
            RuleOp.GT -> v > a
            RuleOp.LT -> v < a
            RuleOp.BETWEEN -> v >= a && v <= (c.value2.toLongOrNull() ?: Long.MAX_VALUE)
            else -> false
        }
    }

    fun act(t: Transaction, a: RuleActions): Transaction {
        var out = t
        a.categoryId?.let { out = out.copy(categoryId = it) }
        if (a.addTags.isNotEmpty()) out = out.copy(tags = (out.tags + a.addTags).distinct())
        a.renamePayee?.let { out = out.copy(payee = it) }
        a.accountId?.let { out = out.copy(accountId = it) }
        a.transferToAccountId?.let { if (out.type == TxType.EXPENSE) out = out.copy(type = TxType.TRANSFER, toAccountId = it, categoryId = null) }
        a.excludeFromBudget?.let { out = out.copy(excludeFromBudget = it) }
        a.excludeFromReports?.let { out = out.copy(excludeFromReports = it) }
        a.isPrivate?.let { out = out.copy(isPrivate = it) }
        if (a.splitTemplate.isNotEmpty() && out.splits.isEmpty()) out = out.copy(splits = splitByBp(out.amountMinor, a.splitTemplate.map { it.bp to it.categoryId }))
        return out
    }

    /** Splits by basis points with the rounding remainder on the last line, so the sum always equals the total. */
    fun splitByBp(total: Long, lines: List<Pair<Int, String?>>): List<Split> {
        val sumBp = lines.sumOf { it.first }.takeIf { it > 0 } ?: return emptyList()
        var used = 0L
        return lines.mapIndexed { i, (bp, cat) ->
            val amt = if (i == lines.lastIndex) total - used else BigDecimal.valueOf(total).multiply(BigDecimal(bp)).divide(BigDecimal(sumBp), 0, java.math.RoundingMode.DOWN).toLong()
            used += amt
            Split(amt, cat)
        }
    }

    /** CAT-08 / RU-02 dry run: transactions whose result would change. */
    fun preview(rule: Rule, txs: List<Pair<String, Transaction>>): List<Pair<String, Transaction>> =
        txs.filter { (_, t) -> matches(t, rule) && act(t, rule.actions) != t }

    /** RU-05: pairs of enabled rules that both match at least one of the sample transactions. */
    fun conflicts(rules: List<Pair<String, Rule>>, sample: List<Transaction>): List<Pair<String, String>> {
        val enabled = rules.filter { it.second.enabled }
        val out = mutableListOf<Pair<String, String>>()
        for (i in enabled.indices) for (j in i + 1 until enabled.size) {
            val (a, ra) = enabled[i]
            val (b, rb) = enabled[j]
            if (ra.actions == rb.actions) continue
            if (sample.any { matches(it, ra) && matches(it, rb) }) out += a to b
        }
        return out
    }
}

/**
 * RU-03: a correction is "payee X → category Y". Two identical corrections create a draft rule; three promote it.
 * Returns the rule to write (new or updated) or null when nothing changes.
 */
object RuleLearner {
    const val DRAFT_AT = 2
    const val PROMOTE_AT = 3

    fun observe(payee: String?, categoryId: String, existing: List<Pair<String, Rule>>): Pair<String?, Rule>? {
        val p = payee?.let(RuleEngine::normalise)?.takeIf { it.isNotEmpty() } ?: return null
        val hit = existing.firstOrNull { (_, r) ->
            r.learned && r.conditions.singleOrNull()?.let { it.field == RuleField.PAYEE && RuleEngine.normalise(it.value) == p } == true &&
                r.actions.categoryId == categoryId
        }
        if (hit != null) {
            val ev = hit.second.evidence + 1
            return hit.first to hit.second.copy(evidence = ev, enabled = ev >= PROMOTE_AT)
        }
        val signal = Rule(
            name = payee.trim(),
            conditions = listOf(RuleCondition(RuleField.PAYEE, RuleOp.EQUALS, payee.trim())),
            actions = RuleActions(categoryId = categoryId),
            order = 10_000,
            enabled = false,
            learned = true,
            evidence = 1,
        )
        return null to signal
    }

    fun isDraft(r: Rule) = r.learned && !r.enabled && r.evidence >= DRAFT_AT
    fun isVisible(r: Rule) = !r.learned || r.evidence >= DRAFT_AT
}
