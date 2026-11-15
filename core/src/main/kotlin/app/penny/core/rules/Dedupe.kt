package app.penny.core.rules

import app.penny.core.model.Transaction
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * TX-10 / CP-11: same account, same amount, within 24 hours, similar payee. Similarity is a token Jaccard on
 * normalised payees (empty payees count as similar so cash entries still warn).
 */
object Dedupe {
    const val WINDOW_HOURS = 24L

    fun candidates(new: Transaction, existing: List<Pair<String, Transaction>>, excludeId: String? = null): List<String> =
        existing.filter { (id, t) -> id != excludeId && isDuplicate(new, t) }.map { it.first }

    fun isDuplicate(a: Transaction, b: Transaction): Boolean {
        if (a.accountId != b.accountId || a.amountMinor != b.amountMinor || a.currency != b.currency || a.type != b.type) return false
        if (a.externalIds.isNotEmpty() && b.externalIds.isNotEmpty() && a.externalIds.intersect(b.externalIds.toSet()).isNotEmpty()) return true
        val ta = LocalDateTime.of(a.date, a.time ?: LocalTime.NOON)
        val tb = LocalDateTime.of(b.date, b.time ?: LocalTime.NOON)
        val hours = kotlin.math.abs(ChronoUnit.MINUTES.between(ta, tb)) / 60.0
        val limit = if (a.time == null || b.time == null) WINDOW_HOURS + 12 else WINDOW_HOURS
        if (hours > limit) return false
        return similar(a.payee, b.payee)
    }

    fun similar(a: String?, b: String?): Boolean {
        val x = tokens(a)
        val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return true
        val inter = x.intersect(y).size.toDouble()
        val union = x.union(y).size.toDouble()
        return inter / union >= 0.5 || x.joinToString("").contains(y.joinToString("")) || y.joinToString("").contains(x.joinToString(""))
    }

    private fun tokens(s: String?) = RuleEngine.normalise(s.orEmpty())
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .split(' ').filter { it.length > 1 && it !in NOISE }.toSet()

    private val NOISE = setOf("pvt", "ltd", "inc", "llc", "the", "upi", "pos", "card", "payment", "to", "from", "via")

    /** Merges duplicates: keeps [keep], unions tags, externals and notes so every source stays in history (CP-11). */
    fun merge(keep: Transaction, drop: Transaction): Transaction = keep.copy(
        tags = (keep.tags + drop.tags).distinct(),
        externalIds = (keep.externalIds + drop.externalIds).distinct(),
        note = listOf(keep.note, drop.note).filter { it.isNotBlank() }.distinct().joinToString(" · "),
        attachmentIds = (keep.attachmentIds + drop.attachmentIds).distinct(),
        payee = keep.payee ?: drop.payee,
        categoryId = keep.categoryId ?: drop.categoryId,
        time = keep.time ?: drop.time,
    )
}
