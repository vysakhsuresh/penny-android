package app.penny.core.insights

import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.core.search.NaturalQueryParser
import app.penny.core.search.Row
import app.penny.core.search.TxSearch

enum class CoachTone { GENTLE, DIRECT, ROAST }

enum class CoachIntent { TOTAL, AVERAGE, BIGGEST, COUNT, COMPARE, WHERE_MONEY_WENT, CAN_I_AFFORD, UNKNOWN }

/** An answer always lists the transactions behind it (IN-08). Text is assembled by the app in the user's language. */
data class CoachAnswer(
    val intent: CoachIntent,
    val valueMinor: Long?,
    val secondMinor: Long?,
    val understood: List<String>,
    val evidence: List<Row>,
    val tone: CoachTone,
    val label: String? = null,
)

/**
 * IN-08 on-device coach: classifies a question into an intent, turns the rest into a filter with the natural-language
 * parser and answers from the user's own rows. No network, no model download, no product suggestions.
 */
class Coach(private val parser: NaturalQueryParser, private val tone: CoachTone) {
    fun ask(question: String, rows: List<Row>, safeToSpendMinor: Long? = null, previousPeriodRows: List<Row> = emptyList()): CoachAnswer {
        val q = question.lowercase()
        val intent = when {
            Regex("\\b(afford|can i (buy|spend)|enough for)\\b").containsMatchIn(q) -> CoachIntent.CAN_I_AFFORD
            Regex("\\b(compare|vs|versus|more than last|less than last|than last)\\b").containsMatchIn(q) -> CoachIntent.COMPARE
            Regex("\\b(biggest|largest|most expensive|top)\\b").containsMatchIn(q) -> CoachIntent.BIGGEST
            Regex("\\b(average|avg|usually|typical)\\b").containsMatchIn(q) -> CoachIntent.AVERAGE
            Regex("\\b(how many|count|times)\\b").containsMatchIn(q) -> CoachIntent.COUNT
            Regex("\\b(where did|where does|what did i spend|breakdown)\\b").containsMatchIn(q) -> CoachIntent.WHERE_MONEY_WENT
            Regex("\\b(how much|total|spent|spend|earned|income)\\b").containsMatchIn(q) -> CoachIntent.TOTAL
            else -> CoachIntent.UNKNOWN
        }
        val cleaned = q.replace(Regex("\\b(how much|did i|do i|have i|what|was|is|my|can i|afford|biggest|largest|average|how many|times|compare|where|money|go|went|breakdown|total|spend|spent)\\b"), " ")
        val parsed = parser.parse(cleaned)
        val hits = TxSearch.filter(rows, parsed.filter)
        val spendHits = hits.filter { it.tx.type == TxType.EXPENSE || it.tx.type == TxType.INCOME && parsed.filter.types.contains(TxType.INCOME) }
        fun sum(list: List<Row>) = list.sumOf { if (it.tx.type == TxType.REFUND) -it.tx.amountMinor else it.tx.amountMinor }
        return when (intent) {
            CoachIntent.TOTAL, CoachIntent.UNKNOWN -> CoachAnswer(intent, sum(spendHits), null, parsed.understood, spendHits.take(50), tone)
            CoachIntent.AVERAGE -> CoachAnswer(intent, if (spendHits.isEmpty()) 0 else sum(spendHits) / spendHits.size, spendHits.size.toLong(), parsed.understood, spendHits.take(50), tone)
            CoachIntent.COUNT -> CoachAnswer(intent, spendHits.size.toLong(), sum(spendHits), parsed.understood, spendHits.take(50), tone)
            CoachIntent.BIGGEST -> {
                val top = spendHits.sortedByDescending { it.tx.amountMinor }.take(5)
                CoachAnswer(intent, top.firstOrNull()?.tx?.amountMinor, null, parsed.understood, top, tone, top.firstOrNull()?.tx?.payee)
            }
            CoachIntent.COMPARE -> {
                val prev = TxSearch.filter(previousPeriodRows, parsed.filter.copy(from = null, to = null))
                CoachAnswer(intent, sum(spendHits), sum(prev), parsed.understood, spendHits.take(50), tone)
            }
            CoachIntent.WHERE_MONEY_WENT -> {
                val top = spendHits.groupBy { it.tx.categoryId }.maxByOrNull { (_, l) -> l.sumOf { it.tx.amountMinor } }
                CoachAnswer(intent, top?.value?.sumOf { it.tx.amountMinor }, sum(spendHits), parsed.understood, top?.value.orEmpty().take(50), tone, top?.key)
            }
            CoachIntent.CAN_I_AFFORD -> {
                val ask = Regex("([0-9][0-9.,]*)").find(q)?.value?.let { app.penny.core.capture.AmountText.toMinor(it, rows.firstOrNull()?.tx?.currency ?: "USD") }
                CoachAnswer(intent, safeToSpendMinor, ask, parsed.understood, emptyList(), tone)
            }
        }
    }

    companion object {
        fun spendOnly(t: Transaction) = t.type == TxType.EXPENSE || t.type == TxType.REFUND
    }
}
