package app.penny.core.budget

import app.penny.core.model.Allocation
import app.penny.core.model.BudgetMove
import app.penny.core.model.Category
import app.penny.core.model.Envelope
import app.penny.core.model.RolloverMode
import app.penny.core.model.TargetKind
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.core.period.CycleRule
import app.penny.core.period.Period
import app.penny.core.period.PeriodContext
import app.penny.core.period.Periods
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class EnvelopeState { OK, HALF, NEAR, AT, OVER }

data class EnvelopeStatus(
    val id: String,
    val envelope: Envelope,
    val period: Period,
    val assignedMinor: Long,
    val carriedMinor: Long,
    val spentMinor: Long,
    val availableMinor: Long,
    val state: EnvelopeState,
    /** Assignment still needed this period to meet the target (BU-06); 0 when met or no target. */
    val neededMinor: Long,
    /** Share spent of (assigned + carried), 0..∞, as basis points for display without floats. */
    val usedBp: Int,
)

/** Everything the budget engine needs about a profile, already decoded. */
data class BudgetInput(
    val envelopes: Map<String, Envelope>,
    val allocations: List<Allocation>,
    val moves: List<BudgetMove>,
    val transactions: List<Transaction>,
    val categories: Map<String, Category>,
    val cycle: CycleRule,
    val baseCurrency: String,
    val ctx: PeriodContext = PeriodContext.EMPTY,
    val fx: FxConverter = FxConverter.SameCurrencyOnly,
    val excludedCategoryIds: Set<String> = emptySet(),
)

/**
 * BU-02…BU-10. Pure functions; every number is reproducible from the event log.
 *
 * Available(p) = assigned(p) + moves in − moves out + carried(p) − spent(p).
 * carried(p+1) follows the rollover mode applied to available(p).
 */
object BudgetEngine {
    private const val MAX_ROLL_PERIODS = 240

    fun periodFor(env: Envelope, date: LocalDate, input: BudgetInput): Period =
        Periods.periodFor(date, env.periodOverride ?: input.cycle, if (env.periodOverride == null) input.ctx else PeriodContext.EMPTY)

    /** Categories an envelope covers, children included (CAT-01 three levels). */
    fun covered(env: Envelope, cats: Map<String, Category>): Set<String> {
        val out = env.categoryIds.toMutableSet()
        var grew = true
        while (grew) {
            grew = false
            cats.forEach { (id, c) -> if (c.parentId in out && out.add(id)) grew = true }
        }
        return out
    }

    /** Spend attributed to the envelope in the period; refunds reduce it; splits count line by line (TX-07). */
    fun spent(env: Envelope, period: Period, input: BudgetInput, covered: Set<String> = covered(env, input.categories)): Long {
        var total = 0L
        for (t in input.transactions) {
            if (t.date !in period || t.excludeFromBudget) continue
            val sign = when (t.type) {
                TxType.EXPENSE -> 1L
                TxType.REFUND -> -1L
                else -> continue
            }
            val lines = if (t.splits.isNotEmpty()) t.splits.map { Triple(it.amountMinor, it.categoryId, it.tags + t.tags) }
            else listOf(Triple(t.amountMinor, t.categoryId, t.tags))
            for ((amount, cat, tags) in lines) {
                val hit = (cat != null && cat in covered) || (env.tags.isNotEmpty() && tags.any { it in env.tags })
                if (!hit) continue
                val conv = input.fx.convert(amount, t.currency, env.currency, t.date) ?: continue
                total = Math.addExact(total, sign * conv)
            }
        }
        return total
    }

    fun assigned(envId: String, env: Envelope, periodStart: LocalDate, input: BudgetInput): Long {
        val base = input.allocations.lastOrNull { it.envelopeId == envId && it.periodStart == periodStart }?.assignedMinor ?: env.limitMinor
        val moves = input.moves.filter { it.periodStart == periodStart }
        val inflow = moves.filter { it.toId == envId }.sumOf { it.amountMinor }
        val outflow = moves.filter { it.fromId == envId }.sumOf { it.amountMinor }
        return base + inflow - outflow
    }

    private fun roll(mode: RolloverMode, cap: Long?, available: Long): Long = when (mode) {
        RolloverMode.NONE -> 0
        RolloverMode.POSITIVE -> maxOf(0, available)
        RolloverMode.BOTH -> available
        RolloverMode.CAPPED -> minOf(maxOf(0, available), cap ?: Long.MAX_VALUE)
    }

    fun status(envId: String, date: LocalDate, input: BudgetInput, today: LocalDate = date): EnvelopeStatus {
        val env = input.envelopes.getValue(envId)
        val rule = env.periodOverride ?: input.cycle
        val ctx = if (env.periodOverride == null) input.ctx else PeriodContext.EMPTY
        val target = Periods.periodFor(date, rule, ctx)
        val cov = covered(env, input.categories)
        val firstStart = listOfNotNull(
            env.startedOn,
            input.allocations.filter { it.envelopeId == envId }.minOfOrNull { it.periodStart },
        ).minOrNull()
        var carried = 0L
        if (env.rollover != RolloverMode.NONE && firstStart != null && firstStart.isBefore(target.start)) {
            var p = Periods.periodFor(firstStart, rule, ctx)
            var i = 0
            while (p.start.isBefore(target.start) && i < MAX_ROLL_PERIODS) {
                val avail = assigned(envId, env, p.start, input) + carried - spent(env, p, input, cov)
                carried = roll(env.rollover, env.rolloverCapMinor, avail)
                p = Periods.next(p, rule, ctx)
                i++
            }
        }
        val assigned = assigned(envId, env, target.start, input)
        val spent = spent(env, target, input, cov)
        val budget = assigned + carried
        val available = budget - spent
        val usedBp = if (budget <= 0) (if (spent > 0) 10_001 else 0) else ((spent * 10_000) / budget).toInt()
        val state = when {
            budget <= 0 && spent <= 0 -> EnvelopeState.OK
            spent > budget -> EnvelopeState.OVER
            spent == budget -> EnvelopeState.AT
            usedBp >= 8_000 -> EnvelopeState.NEAR
            usedBp >= 5_000 -> EnvelopeState.HALF
            else -> EnvelopeState.OK
        }
        return EnvelopeStatus(envId, env, target, assigned, carried, spent, available, state, needed(env, target, assigned, carried, today), usedBp)
    }

    /** BU-06: how much more to assign this period to meet the target. */
    fun needed(env: Envelope, period: Period, assigned: Long, carried: Long, today: LocalDate): Long {
        val t = env.target ?: return 0
        return when (t.kind) {
            TargetKind.FIXED -> maxOf(0, t.amountMinor - assigned)
            TargetKind.REFILL_UP_TO -> maxOf(0, t.amountMinor - (assigned + carried))
            TargetKind.SPEND_AT_MOST -> 0
            TargetKind.SAVE_BY_DATE -> {
                val by = t.by ?: return maxOf(0, t.amountMinor - (assigned + carried))
                val remaining = t.amountMinor - carried
                if (remaining <= 0) return 0
                val periodsLeft = maxOf(1L, ChronoUnit.DAYS.between(maxOf(today, period.start), by) / maxOf(1, period.days) + 1)
                val perPeriod = ceilDiv(remaining, periodsLeft)
                maxOf(0, perPeriod - assigned)
            }
        }
    }

    /** Income in the period in base currency (excluded categories and flagged transactions left out). */
    fun income(period: Period, input: BudgetInput): Long = input.transactions
        .filter { it.type == TxType.INCOME && it.date in period && !it.excludeFromBudget && it.categoryId !in input.excludedCategoryIds }
        .sumOf { input.fx.convert(it.amountMinor, it.currency, input.baseCurrency, it.date) ?: 0 }

    /**
     * BU-05: income for the period, plus income-smoothing releases, minus money assigned to envelopes that run on the
     * profile cycle. Moves to or from "available" (null id) adjust it as well.
     */
    fun availableToAssign(period: Period, input: BudgetInput): Long {
        val active = input.envelopes.filter { !it.value.archived && it.value.periodOverride == null && it.value.smoothingMinor == null }
        val assigned = active.entries.sumOf { (id, e) ->
            input.fx.convert(assigned(id, e, period.start, input), e.currency, input.baseCurrency, period.start) ?: 0
        }
        val smoothing = input.envelopes.values.filter { !it.archived }.sumOf { it.smoothingMinor ?: 0 }
        // Moves are already inside each envelope's assigned amount, so a move to or from "available" is counted once.
        return income(period, input) + smoothing - assigned
    }

    /** BU-06 auto-assign: fund needs in order, underfunded first (largest gap share first), until money runs out. */
    fun autoAssign(statuses: List<EnvelopeStatus>, available: Long): List<Pair<String, Long>> {
        var left = available
        val out = mutableListOf<Pair<String, Long>>()
        statuses.filter { it.neededMinor > 0 && !it.envelope.archived }
            .sortedWith(compareByDescending<EnvelopeStatus> { it.neededMinor * 10_000 / maxOf(1, it.neededMinor + it.assignedMinor) }.thenBy { it.envelope.sort })
            .forEach { s ->
                if (left <= 0) return@forEach
                val give = minOf(left, s.neededMinor)
                out += s.id to give
                left -= give
            }
        return out
    }

    /** AL-03 "cover from": the envelope with the most unused money, excluding [overId]. */
    fun coverFrom(statuses: List<EnvelopeStatus>, overId: String): EnvelopeStatus? =
        statuses.filter { it.id != overId && it.availableMinor > 0 && !it.envelope.archived && it.envelope.currency == statuses.firstOrNull { s -> s.id == overId }?.envelope?.currency }
            .maxByOrNull { it.availableMinor }

    /** BU-09 "copy last period": allocations equal to last period's assigned amounts. */
    fun copyLast(input: BudgetInput, current: Period): List<Allocation> {
        val prev = Periods.previous(current, input.cycle, input.ctx)
        return input.envelopes.filter { !it.value.archived && it.value.periodOverride == null }.map { (id, e) ->
            Allocation(id, current.start, assigned(id, e, prev.start, input))
        }
    }

    /** BU-11: festival pot target sized from last year's spend in the festival month for the covered categories. */
    fun festivalTarget(month: Int, year: Int, txs: List<Transaction>, fx: FxConverter, currency: String): Long {
        val from = LocalDate.of(year - 1, month, 1)
        val window = Period(from.minusDays(14), from.plusMonths(1).minusDays(1))
        return txs.filter { it.type == TxType.EXPENSE && it.date in window }
            .sumOf { fx.convert(it.amountMinor, it.currency, currency, it.date) ?: 0 }
    }

    fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)
}
