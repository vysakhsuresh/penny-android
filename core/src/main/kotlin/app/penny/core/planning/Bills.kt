package app.penny.core.planning

import app.penny.core.model.AmountKind
import app.penny.core.model.Occurrence
import app.penny.core.model.OccurrenceStatus
import app.penny.core.model.Recurring
import app.penny.core.model.RecurringKind
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.core.period.Period
import app.penny.core.rules.RuleEngine
import app.penny.core.schedule.Freq
import app.penny.core.schedule.Recurrence
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Upcoming(
    val recurringId: String,
    val recurring: Recurring,
    /** Scheduled date from the rule (the occurrence key). */
    val due: LocalDate,
    /** Date shown and used (moved for this occurrence only). */
    val date: LocalDate,
    val amountMinor: Long,
    val status: OccurrenceStatus?,
    val transactionId: String?,
) {
    val key: String get() = occurrenceId(recurringId, due)
    val open: Boolean get() = status == null || status == OccurrenceStatus.EDITED
}

fun occurrenceId(recurringId: String, due: LocalDate) = "$recurringId@$due"

/** RC-02, RC-06, RC-07, RC-03, RC-04. */
object Bills {
    fun occurrences(
        items: List<Pair<String, Recurring>>,
        occ: List<Occurrence>,
        from: LocalDate,
        to: LocalDate,
        actuals: Map<String, List<Long>> = emptyMap(),
    ): List<Upcoming> {
        val byKey = occ.associateBy { occurrenceId(it.recurringId, it.due) }
        return items.filter { !it.second.paused }.flatMap { (id, r) ->
            // Look back a little so occurrences moved into the window from earlier due dates still show.
            r.schedule.between(from.minusDays(31), to.plusDays(31)).mapNotNull { due ->
                val o = byKey[occurrenceId(id, due)]
                val date = o?.movedTo ?: due
                if (date.isBefore(from) || date.isAfter(to)) return@mapNotNull null
                val amt = o?.amountMinor ?: estimate(r, actuals[id].orEmpty())
                Upcoming(id, r, due, date, amt, o?.status, o?.transactionId)
            }
        }.sortedWith(compareBy({ it.date }, { it.recurring.name }))
    }

    /** RC-07: variable bills estimate the median of the last 6 actuals; ranges use the midpoint. */
    fun estimate(r: Recurring, actuals: List<Long>): Long = when (r.amountKind) {
        AmountKind.FIXED -> r.amountMinor
        AmountKind.RANGE -> (r.amountMinor + (r.amountMaxMinor ?: r.amountMinor)) / 2
        AmountKind.VARIABLE -> actuals.takeLast(6).sorted().let { s ->
            if (s.isEmpty()) r.amountMinor else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }

    fun toTransaction(u: Upcoming, tz: String, recurringId: String): Transaction {
        val r = u.recurring
        return Transaction(
            type = r.type, amountMinor = u.amountMinor, currency = r.currency, accountId = r.accountId,
            toAccountId = r.toAccountId.takeIf { r.type == TxType.TRANSFER }, categoryId = r.categoryId.takeIf { r.type != TxType.TRANSFER },
            payee = r.payee ?: r.name, date = u.date, tz = tz, note = r.note, tags = r.tags, recurringId = recurringId,
            loanId = r.loanId, source = "recurring",
        )
    }

    /** Sums for safe-to-spend (BU-07): open bills and expected income from today to period end. */
    fun committedAndExpected(up: List<Upcoming>, period: Period, today: LocalDate): Pair<Long, Long> {
        val open = up.filter { it.open && it.date in period && !it.date.isBefore(today) }
        val committed = open.filter { it.recurring.type == TxType.EXPENSE || it.recurring.kind == RecurringKind.SAVINGS }.sumOf { it.amountMinor }
        val expected = open.filter { it.recurring.type == TxType.INCOME }.sumOf { it.amountMinor }
        return committed to expected
    }

    /** "This and future" edit (RC-02): ends the old series the day before [from] and returns the new series. */
    fun splitSeries(old: Recurring, oldId: String, from: LocalDate, edited: Recurring): Pair<Recurring, Recurring> {
        val ended = old.copy(schedule = old.schedule.copy(until = from.minusDays(1), count = null))
        val next = edited.copy(schedule = edited.schedule.copy(start = from), continues = oldId)
        return ended to next
    }

    /** RC-04: yearly cost of a subscription. */
    fun yearlyCost(r: Recurring): Long = (r.amountMinor * Recurrence.perYear(r.schedule)).toLong()

    data class Detected(val payee: String, val amountMinor: Long, val currency: String, val accountId: String, val categoryId: String?, val schedule: Recurrence, val count: Int)

    /**
     * RC-03: proposes recurring items from 3+ expenses with the same payee and amount (±5%) at a steady interval
     * (weekly, monthly or yearly within tolerance). Excludes payees already covered by [known].
     */
    fun detect(txs: List<Transaction>, known: List<Recurring>): List<Detected> {
        val knownPayees = known.mapNotNull { (it.payee ?: it.name).let(RuleEngine::normalise).takeIf(String::isNotEmpty) }.toSet()
        return txs.filter { it.type == TxType.EXPENSE && !it.payee.isNullOrBlank() && it.recurringId == null }
            .groupBy { RuleEngine.normalise(it.payee!!) }
            .filterKeys { it !in knownPayees }
            .mapNotNull { (_, list) ->
                val sorted = list.sortedBy { it.date }
                if (sorted.size < 3) return@mapNotNull null
                val last = sorted.takeLast(6)
                val median = last.map { it.amountMinor }.sorted()[last.size / 2]
                if (last.any { kotlin.math.abs(it.amountMinor - median) * 20 > median }) return@mapNotNull null
                val gaps = last.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
                val avg = gaps.average()
                val freq = when {
                    avg in 6.0..8.0 && gaps.all { it in 5..9 } -> Freq.WEEKLY
                    avg in 27.0..33.0 && gaps.all { it in 25..35 } -> Freq.MONTHLY
                    avg in 355.0..375.0 -> Freq.YEARLY
                    else -> return@mapNotNull null
                }
                val l = last.last()
                val start = when (freq) {
                    Freq.WEEKLY -> l.date.plusWeeks(1)
                    Freq.MONTHLY -> l.date.plusMonths(1)
                    else -> l.date.plusYears(1)
                }
                val sched = when (freq) {
                    Freq.MONTHLY -> Recurrence.monthly(start, l.date.dayOfMonth)
                    Freq.WEEKLY -> Recurrence.weekly(start)
                    else -> Recurrence(Freq.YEARLY, start = start)
                }
                Detected(l.payee!!, median, l.currency, l.accountId, l.categoryId, sched, sorted.size)
            }
    }

    /** AL-04 price increase: last two actuals for the series differ upward. */
    fun priceIncrease(actuals: List<Long>): Long? = if (actuals.size >= 2 && actuals.last() > actuals[actuals.size - 2]) actuals.last() - actuals[actuals.size - 2] else null
}
