package app.penny.domain.ops

import kotlinx.coroutines.sync.withLock
import app.penny.core.budget.BudgetEngine
import app.penny.core.budget.BudgetInput
import app.penny.core.budget.EnvelopeStatus
import app.penny.core.budget.SafeToSpend
import app.penny.core.budget.SafeToSpendCalculator
import app.penny.core.model.Allocation
import app.penny.core.model.BudgetMove
import app.penny.core.model.Envelope
import app.penny.core.model.Goal
import app.penny.core.model.GoalEntry
import app.penny.core.model.GoalStatus
import app.penny.core.model.Occurrence
import app.penny.core.model.OccurrenceStatus
import app.penny.core.model.Recurring
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.core.period.Period
import app.penny.core.period.Periods
import app.penny.core.planning.Bills
import app.penny.core.planning.Goals
import app.penny.core.planning.Upcoming
import app.penny.core.planning.occurrenceId
import app.penny.domain.Book
import app.penny.domain.PennyRepository
import java.time.LocalDate

// ------------------------------------------------------------------ Budget (BU)

fun Book.budgetInput() = BudgetInput(
    envelopes = envelopes.associate { it.state.id to it.value },
    allocations = allocations.map { it.value },
    moves = moves.map { it.value },
    transactions = txList,
    categories = categoryMap.mapValues { it.value.value },
    cycle = profile.cycle,
    baseCurrency = profile.baseCurrency,
    ctx = periodContext,
    fx = fx,
    excludedCategoryIds = excludedCategoryIds,
)

fun Book.period(offset: Int = 0, at: LocalDate = today()): Period =
    Periods.shift(Periods.periodFor(at, profile.cycle, periodContext), offset, profile.cycle, periodContext)

fun Book.envelopeStatuses(at: LocalDate, includeArchived: Boolean = false): List<EnvelopeStatus> {
    val input = budgetInput()
    return envelopes.filter { includeArchived || !it.value.archived }.map { BudgetEngine.status(it.state.id, at, input, today()) }
}

/** Upcoming occurrences with learned estimates (RC-07). */
fun Book.upcoming(from: LocalDate, to: LocalDate): List<Upcoming> {
    val actuals = transactions.filter { it.value.recurringId != null }.groupBy({ it.value.recurringId!! }, { it.value }).mapValues { (_, l) -> l.sortedBy { it.date }.map { it.amountMinor } }
    return Bills.occurrences(recurring.map { it.state.id to it.value }, occurrences.map { it.value }, from, to, actuals)
}

/** BU-07 with scheduled bills, expected income and goal auto-contributions for the period. */
fun Book.safeToSpend(p: Period): SafeToSpend {
    val today = today()
    val (committed, expected) = Bills.committedAndExpected(upcoming(p.start, p.endInclusive), p, today, profile.baseCurrency, fx)
    val savings = goals.filter { it.value.status == GoalStatus.ACTIVE && it.value.autoMinor != null }.sumOf { g ->
        val contributed = goalEntries.filter { e -> e.value.goalId == g.state.id && e.value.date in p && e.value.amountMinor > 0 }.sumOf { it.value.amountMinor }
        val left = maxOf(0, (g.value.autoMinor ?: 0) - contributed)
        fx.convert(left, g.value.currency, profile.baseCurrency, p.start) ?: 0
    }
    return SafeToSpendCalculator.compute(txList, p, today, profile.baseCurrency, excludedCategoryIds, fx, expected, committed, savings)
}

fun allocationId(envelopeId: String, periodStart: LocalDate) = "$envelopeId@$periodStart"

suspend fun PennyRepository.assign(book: Book, envelopeId: String, period: Period, amount: Long, group: String = allocations.newGroup()): String {
    allocations.upsert(allocationId(envelopeId, period.start), book.profileId, Allocation(envelopeId, period.start, amount), group)
    return group
}

/** BU-10 / AL-03 move money (cover from) with history. */
suspend fun PennyRepository.moveMoney(book: Book, from: String?, to: String?, period: Period, amount: Long, reason: String = ""): String {
    val g = moves.newGroup()
    moves.create(newId(), book.profileId, BudgetMove(from, to, period.start, amount, reason), g)
    return g
}

suspend fun PennyRepository.autoAssign(book: Book, period: Period): String {
    val g = allocations.newGroup()
    val input = book.budgetInput()
    // Only envelopes on the book's own period and currency share the pool; others are funded on their own schedule.
    val shared = book.envelopes.filter { it.value.periodOverride == null && it.value.currency == book.profile.baseCurrency }.map { it.state.id }.toSet()
    val statuses = book.envelopeStatuses(period.start).filter { it.id in shared }
    val plan = BudgetEngine.autoAssign(statuses, BudgetEngine.availableToAssign(period, input))
    plan.forEach { (id, add) ->
        val s = statuses.first { it.id == id }
        assign(book, id, period, s.assignedMinor + add, g)
    }
    return g
}

/** BU-09 copy last period / start from zero. */
suspend fun PennyRepository.copyLastPeriod(book: Book, period: Period): String {
    val g = allocations.newGroup()
    BudgetEngine.copyLast(book.budgetInput(), period).forEach { a -> allocations.upsert(allocationId(a.envelopeId, a.periodStart), book.profileId, a, g) }
    return g
}

suspend fun PennyRepository.zeroPeriod(book: Book, period: Period): String {
    val g = allocations.newGroup()
    book.envelopes.filter { !it.value.archived }.forEach { assign(book, it.state.id, period, 0, g) }
    return g
}

suspend fun PennyRepository.saveEnvelope(book: Book, id: String?, e: Envelope): String {
    val g = envelopes.newGroup()
    if (id == null) envelopes.create(newId(), book.profileId, e.copy(sort = book.envelopes.size, startedOn = e.startedOn ?: book.period().start), g)
    else envelopes.update(id, e, groupId = g)
    return g
}

suspend fun PennyRepository.reorderEnvelopes(book: Book, ids: List<String>) =
    envelopes.updateMany(ids.mapIndexedNotNull { i, id -> book.envelopes.firstOrNull { it.state.id == id }?.let { id to it.value.copy(sort = i) } })

/**
 * BU-09 regional templates: envelopes created from category keys with a share of income. Shares are basis points of
 * [incomeMinor]; the user edits everything after.
 */
val BUDGET_TEMPLATES: Map<String, List<Pair<String, Int>>> = mapOf(
    "salaried" to listOf("home" to 3000, "food" to 1500, "util" to 800, "trans" to 800, "eat" to 600, "health" to 500, "shop" to 500, "fun" to 400, "save" to 1900),
    "gig" to listOf("home" to 3000, "food" to 1500, "util" to 800, "trans" to 1200, "health" to 500, "save" to 1000),
    "student" to listOf("home" to 3500, "food" to 2000, "trans" to 1000, "edu" to 1500, "fun" to 800, "util" to 500),
    "household" to listOf("home" to 2800, "food" to 1800, "util" to 900, "fam" to 1200, "trans" to 800, "health" to 700, "save" to 1500),
    "ofw_family" to listOf("food" to 2500, "fam" to 2500, "util" to 1000, "edu" to 1500, "health" to 800, "save" to 1500),
    "small_business" to listOf("home" to 2000, "util" to 1000, "trans" to 1000, "fin" to 1500, "food" to 1200, "save" to 2000),
)

suspend fun PennyRepository.applyBudgetTemplate(book: Book, key: String, incomeMinor: Long): String {
    val g = envelopes.newGroup()
    val p = book.period()
    BUDGET_TEMPLATES[key].orEmpty().forEachIndexed { i, (catKey, bp) ->
        val cid = catId(book.profileId, catKey)
        val name = book.categoryMap[cid]?.value?.name ?: return@forEachIndexed
        if (book.envelopes.any { cid in it.value.categoryIds }) return@forEachIndexed
        envelopes.create(newId(), book.profileId, Envelope(name, listOf(cid), limitMinor = incomeMinor * bp / 10_000, currency = book.profile.baseCurrency, sort = book.envelopes.size + i, startedOn = p.start), g)
    }
    return g
}

// ------------------------------------------------------------------ Goals (GO)

fun Book.goalSaved(id: String): Long {
    val g = goals.firstOrNull { it.state.id == id }?.value ?: return 0
    val linked = g.linkedAccountIds.sumOf { balance(it) }
    return Goals.saved(id, goalEntries.map { it.value }, linked)
}

suspend fun PennyRepository.goalEntry(book: Book, goalId: String, amount: Long, note: String = "", date: LocalDate = book.today()): String {
    val g = goalEntries.newGroup()
    goalEntries.create(newId(), book.profileId, GoalEntry(goalId, amount, date, note), g)
    val goal = book.goals.firstOrNull { it.state.id == goalId } ?: return g
    // GO-05: a calm completed state when the target is reached.
    if (amount > 0 && book.goalSaved(goalId) + amount >= goal.value.targetMinor && goal.value.status == GoalStatus.ACTIVE) {
        goals.update(goalId, goal.value.copy(status = GoalStatus.COMPLETED), groupId = g)
    }
    return g
}

suspend fun PennyRepository.setGoalStatus(book: Book, id: String, status: GoalStatus): String? =
    book.goals.firstOrNull { it.state.id == id }?.let { goals.update(id, it.value.copy(status = status)) }

/** GO-02 delete returns the balance to available: entries are deleted with the goal, in one undo. */
suspend fun PennyRepository.deleteGoal(book: Book, id: String): String {
    val g = goals.newGroup()
    goalEntries.deleteMany(book.goalEntries.filter { it.value.goalId == id }.map { it.state.id }, g)
    goals.delete(id, g)
    return g
}

suspend fun PennyRepository.saveGoal(book: Book, id: String?, goal: Goal): String {
    val g = goals.newGroup()
    if (id == null) goals.create(newId(), book.profileId, goal.copy(sort = book.goals.size), g) else goals.update(id, goal, groupId = g)
    return g
}

// ------------------------------------------------------------------ Recurring (RC)

/** RC-02 mark paid: creates the transaction and the PAID occurrence in one undo group. */
suspend fun PennyRepository.payOccurrence(book: Book, u: Upcoming, amount: Long = u.amountMinor, date: LocalDate = book.today()): String {
    val g = transactions.newGroup()
    val txId = newId()
    transactions.create(txId, book.profileId, Bills.toTransaction(u.copy(amountMinor = amount, date = date), book.profile.timezone, u.recurringId), g)
    occurrences.upsert(u.key, book.profileId, Occurrence(u.recurringId, u.due, OccurrenceStatus.PAID, txId, amount), g)
    return g
}

suspend fun PennyRepository.skipOccurrence(book: Book, u: Upcoming): String =
    occurrences.newGroup().also { occurrences.upsert(u.key, book.profileId, Occurrence(u.recurringId, u.due, OccurrenceStatus.SKIPPED), it) }

/** Unskip / unpay: deletes the occurrence state (a paid one also soft-deletes its transaction). */
suspend fun PennyRepository.reopenOccurrence(book: Book, u: Upcoming): String {
    val g = occurrences.newGroup()
    occurrences.delete(u.key, g)
    u.transactionId?.let { if (book.txMap.containsKey(it)) transactions.delete(it, g) }
    return g
}

suspend fun PennyRepository.editOccurrence(book: Book, u: Upcoming, amount: Long, date: LocalDate): String =
    occurrences.newGroup().also { occurrences.upsert(u.key, book.profileId, Occurrence(u.recurringId, u.due, OccurrenceStatus.EDITED, amountMinor = amount, movedTo = date), it) }

/** RC-02 "this and future": ends the series before [from] and starts a new series with the edits. */
suspend fun PennyRepository.editFuture(book: Book, id: String, from: LocalDate, edited: Recurring): String {
    val g = recurring.newGroup()
    val old = book.recurring.firstOrNull { it.state.id == id }?.value ?: return g
    if (!from.isAfter(old.schedule.start)) {
        recurring.update(id, edited.copy(schedule = edited.schedule.copy(start = old.schedule.start)), groupId = g)
        return g
    }
    val (ended, next) = Bills.splitSeries(old, id, from, edited)
    recurring.update(id, ended, groupId = g)
    recurring.create(newId(), book.profileId, next, g)
    return g
}

suspend fun PennyRepository.saveRecurring(book: Book, id: String?, r: Recurring): String {
    val g = recurring.newGroup()
    if (id == null) recurring.create(newId(), book.profileId, r, g) else recurring.update(id, r, groupId = g)
    return g
}

/** One scheduled run at a time across the process (Home and the daily worker can both start one). */
private val scheduleLock = kotlinx.coroutines.sync.Mutex()

/**
 * Runs on app open and from the daily worker: auto-posts due occurrences (RC-01 auto-post) and goal auto-contributions
 * at period start (GO-01).
 *
 * Idempotent and safe to repeat: serialised by a lock, ids are deterministic, and existence is checked against the
 * event store including deleted rows, so something the user deleted or reopened is never posted again.
 */
suspend fun PennyRepository.runScheduled(book: Book): Int = scheduleLock.withLock {
    val today = book.today()
    var n = 0
    book.upcoming(today.minusDays(60), today).filter { it.open && it.recurring.autoPost && !it.date.isAfter(today) }.forEach { u ->
        if (occurrences.get(u.key) != null) return@forEach // paid, skipped or reopened before: the user decides now
        val txId = "${u.key}#tx"
        if (transactions.get(txId) != null) return@forEach
        val g = transactions.newGroup()
        transactions.create(txId, book.profileId, Bills.toTransaction(u.copy(date = u.date), book.profile.timezone, u.recurringId), g)
        occurrences.upsert(u.key, book.profileId, Occurrence(u.recurringId, u.due, OccurrenceStatus.PAID, txId, u.amountMinor), g)
        n++
    }
    val p = book.period()
    book.goals.filter { it.value.status == GoalStatus.ACTIVE && (it.value.autoMinor ?: 0) > 0 }.forEach { g ->
        val key = "auto:${p.start}"
        val entryId = "auto:${g.state.id}:${p.start}"
        if (goalEntries.get(entryId) == null && book.goalEntries.none { it.value.goalId == g.state.id && it.value.note == key }) {
            goalEntries.create(entryId, book.profileId, GoalEntry(g.state.id, g.value.autoMinor!!, p.start, key)); n++
        }
    }
    n
}

/** Average daily spend over the last [days] days in base currency (AL-04 safe-to-spend low). */
fun Book.avgDailySpend(days: Int = 30): Long {
    val today = today()
    val from = today.minusDays(days.toLong())
    return txList.filter { it.type == TxType.EXPENSE && it.date.isAfter(from) && !it.date.isAfter(today) && it.categoryId !in excludedCategoryIds }
        .sumOf { fx.convert(it.amountMinor, it.currency, profile.baseCurrency, it.date) ?: 0 } / days
}

fun Book.categoryAverage(categoryId: String?): Long? {
    val list = txList.filter { it.type == TxType.EXPENSE && it.categoryId == categoryId }
    return if (list.size < 3) null else list.sumOf { it.amountMinor } / list.size
}

@Suppress("unused")
private fun keep(o: Occurrence, t: Transaction) = o to t

fun occurrenceKey(r: String, d: LocalDate) = occurrenceId(r, d)
