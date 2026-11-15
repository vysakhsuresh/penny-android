package app.penny.core.search

import app.penny.core.model.SortBy
import app.penny.core.model.Transaction
import app.penny.core.model.TxFilter
import app.penny.core.model.TxType
import app.penny.core.period.CycleRule
import app.penny.core.period.Periods
import app.penny.core.rules.RuleEngine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Names the filter needs to search by text (SR-01): categories, accounts, OCR text of attachments. */
data class SearchIndex(
    val categoryName: (String?) -> String = { "" },
    val accountName: (String) -> String = { "" },
    val attachmentText: (String) -> String = { "" },
    val hasAttachment: (String) -> Boolean = { false },
)

data class Row(val id: String, val tx: Transaction, val createdAt: Long)

/** SR-01/02/05: applies a filter offline over decoded transactions. */
object TxSearch {
    fun filter(rows: List<Row>, f: TxFilter, idx: SearchIndex = SearchIndex()): List<Row> {
        val words = RuleEngine.normalise(f.text).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = rows.filter { r ->
            val t = r.tx
            (f.from == null || !t.date.isBefore(f.from)) &&
                (f.to == null || !t.date.isAfter(f.to)) &&
                (f.accountIds.isEmpty() || t.accountId in f.accountIds || t.toAccountId in f.accountIds) &&
                (f.categoryIds.isEmpty() || t.categoryId in f.categoryIds || t.splits.any { it.categoryId in f.categoryIds }) &&
                (f.tags.isEmpty() || f.tags.any { tag -> tag in t.tags || t.splits.any { tag in it.tags } }) &&
                (f.types.isEmpty() || t.type in f.types) &&
                (f.statuses.isEmpty() || t.status in f.statuses) &&
                (f.sources.isEmpty() || t.source in f.sources) &&
                (f.personIds.isEmpty() || t.splits.any { it.personId in f.personIds }) &&
                (f.minMinor == null || t.amountMinor >= f.minMinor) &&
                (f.maxMinor == null || t.amountMinor <= f.maxMinor) &&
                (f.currency == null || t.currency == f.currency) &&
                (f.hasAttachment == null || (t.attachmentIds.isNotEmpty() || idx.hasAttachment(r.id)) == f.hasAttachment) &&
                (f.isPrivate == null || t.isPrivate == f.isPrivate) &&
                (!f.uncategorised || (t.categoryId == null && t.type in listOf(TxType.EXPENSE, TxType.INCOME)) || t.categoryId?.endsWith(":uncat") == true) &&
                (words.isEmpty() || words.all { w -> haystack(r, idx).contains(w) })
        }
        val cmp: Comparator<Row> = when (f.sort) {
            SortBy.DATE -> compareBy<Row>({ it.tx.date }, { it.tx.time }, { it.createdAt })
            SortBy.AMOUNT -> compareBy { it.tx.amountMinor }
            SortBy.PAYEE -> compareBy { it.tx.payee?.lowercase().orEmpty() }
            SortBy.CATEGORY -> compareBy { idx.categoryName(it.tx.categoryId).lowercase() }
            SortBy.CREATED -> compareBy { it.createdAt }
        }
        return out.sortedWith(if (f.ascending) cmp else cmp.reversed())
    }

    private fun haystack(r: Row, idx: SearchIndex): String {
        val t = r.tx
        return RuleEngine.normalise(
            listOf(
                t.payee.orEmpty(), t.note, t.tags.joinToString(" "), idx.categoryName(t.categoryId), idx.accountName(t.accountId),
                t.splits.joinToString(" ") { it.note + " " + it.tags.joinToString(" ") + " " + idx.categoryName(it.categoryId) },
                idx.attachmentText(r.id), t.amountMinor.toString(),
            ).joinToString(" "),
        )
    }
}

/** Result of parsing natural language: the filter plus the words that were understood, shown for correction (SR-04). */
data class ParsedQuery(val filter: TxFilter, val understood: List<String>)

/**
 * SR-04: on-device parser. English keywords plus the vocabulary a pack or locale adds ([extraPeriodWords]);
 * amounts in major units of [currencyDecimals]. Examples: "coffee last month over 200", "jeep rides last kinsenas",
 * "groceries this week under 50", "uber in march", "income yesterday".
 */
class NaturalQueryParser(
    private val today: LocalDate,
    private val cycle: CycleRule,
    private val currencyDecimals: Int,
    private val categoryByName: Map<String, String> = emptyMap(),
    private val accountByName: Map<String, String> = emptyMap(),
    private val extraPeriodWords: Map<String, String> = mapOf("kinsenas" to "period", "quincena" to "period", "fortnight" to "period", "payday" to "period"),
) {
    private val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    fun parse(input: String): ParsedQuery {
        var f = TxFilter()
        val understood = mutableListOf<String>()
        val tokens = RuleEngine.normalise(input).split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        val rest = mutableListOf<String>()
        var i = 0
        fun mult(): Long { var m = 1L; repeat(currencyDecimals) { m *= 10 }; return m }
        fun amount(s: String?): Long? = s?.replace(",", "")?.toBigDecimalOrNull()?.multiply(java.math.BigDecimal(mult()))?.toLong()
        while (i < tokens.size) {
            val w = tokens[i]
            // "more than 200" / "less than 50": skip the "than" and read the number after it.
            val than = tokens.getOrNull(i + 1) == "than"
            val n = tokens.getOrNull(if (than) i + 2 else i + 1)
            val used = if (than) 3 else 2
            when {
                (w == "over" || w == "above" || w == "more" || w == ">") && amount(n) != null -> {
                    f = f.copy(minMinor = amount(n)); understood += "$w $n"; i += used; continue
                }
                (w == "under" || w == "below" || w == "less" || w == "<") && amount(n) != null -> {
                    f = f.copy(maxMinor = amount(n)); understood += "$w $n"; i += used; continue
                }
                w == "today" -> { f = f.copy(from = today, to = today); understood += w }
                w == "yesterday" -> { val d = today.minusDays(1); f = f.copy(from = d, to = d); understood += w }
                (w == "this" || w == "last") && n != null && (n in listOf("week", "month", "year", "period") || n in extraPeriodWords) -> {
                    val unit = extraPeriodWords[n] ?: n
                    val back = if (w == "last") 1L else 0L
                    val (a, b) = when (unit) {
                        "week" -> {
                            val s = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(back)
                            s to s.plusDays(6)
                        }
                        "month" -> {
                            val s = today.withDayOfMonth(1).minusMonths(back)
                            s to s.plusMonths(1).minusDays(1)
                        }
                        "year" -> {
                            val s = today.withDayOfYear(1).minusYears(back)
                            s to s.plusYears(1).minusDays(1)
                        }
                        else -> {
                            val p = Periods.shift(Periods.periodFor(today, cycle), -back.toInt(), cycle)
                            p.start to p.endInclusive
                        }
                    }
                    f = f.copy(from = a, to = b); understood += "$w $n"; i += 2; continue
                }
                w == "in" && n != null && months.any { it.startsWith(n) && n.length >= 3 } -> {
                    val m = months.indexOfFirst { it.startsWith(n) } + 1
                    val y = if (m > today.monthValue) today.year - 1 else today.year
                    val s = LocalDate.of(y, m, 1)
                    f = f.copy(from = s, to = s.plusMonths(1).minusDays(1)); understood += "$w $n"; i += 2; continue
                }
                w == "income" || w == "earned" -> { f = f.copy(types = listOf(TxType.INCOME)); understood += w }
                w == "transfers" || w == "transfer" -> { f = f.copy(types = listOf(TxType.TRANSFER)); understood += w }
                w == "refunds" || w == "refund" -> { f = f.copy(types = listOf(TxType.REFUND)); understood += w }
                w == "uncategorised" || w == "uncategorized" -> { f = f.copy(uncategorised = true); understood += w }
                w == "pending" -> { f = f.copy(statuses = listOf(app.penny.core.model.TxStatus.PENDING)); understood += w }
                w.startsWith("#") && w.length > 1 -> { f = f.copy(tags = f.tags + w.drop(1)); understood += w }
                categoryByName[w] != null -> { f = f.copy(categoryIds = f.categoryIds + categoryByName.getValue(w)); understood += w }
                accountByName[w] != null -> { f = f.copy(accountIds = f.accountIds + accountByName.getValue(w)); understood += w }
                w in STOP -> Unit
                else -> rest += w
            }
            i++
        }
        // Plural nouns like "rides" still match "ride" in payees and notes.
        f = f.copy(text = rest.joinToString(" ") { if (it.length > 3 && it.endsWith("s")) it.dropLast(1) else it })
        return ParsedQuery(f, understood)
    }

    private companion object {
        val STOP = setOf("on", "for", "at", "the", "a", "an", "of", "spent", "spend", "spending", "my", "than", "and")
    }
}
