package app.penny.core.alerts

import app.penny.core.budget.EnvelopeState
import app.penny.core.budget.EnvelopeStatus
import app.penny.core.budget.SafeToSpend
import app.penny.core.model.AlertKind
import app.penny.core.model.AlertMode
import app.penny.core.model.AlertRecord
import app.penny.core.model.AlertState
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.core.model.WatchTarget
import app.penny.core.model.Watchlist
import app.penny.core.planning.Upcoming
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** A proposed alert. Text is built by the app from [kind] and [args] so it is localised; the engine stays pure. */
data class AlertDraft(
    val kind: AlertKind,
    /** De-duplication key: one firing per key (per period, per day for daily alerts). */
    val key: String,
    val objectEntity: String?,
    val objectId: String?,
    val args: List<String>,
    val amountMinor: Long? = null,
    /** AL-01: 50% goes to the digest only, never an OS notification. */
    val digestOnly: Boolean = false,
)

data class AlertSettings(
    val quietStart: LocalTime = LocalTime.of(21, 0),
    val quietEnd: LocalTime = LocalTime.of(9, 0),
    val paceEnabled: Boolean = true,
    val billsEnabled: Boolean = true,
    val largeEnabled: Boolean = true,
    val safeLowEnabled: Boolean = true,
    val unusualEnabled: Boolean = true,
    val forecastEnabled: Boolean = true,
)

/**
 * AL-01…AL-09. Inputs are computed state; output is drafts not yet fired (keys already in [history] are skipped).
 * Delivery decisions (quiet hours, batching, back-off) are separate so they can be tested on their own.
 */
object AlertEngine {
    fun evaluate(
        today: LocalDate,
        envelopes: List<EnvelopeStatus>,
        safe: SafeToSpend?,
        avgDailySpendMinor: Long,
        upcoming: List<Upcoming>,
        recent: List<Pair<String, Transaction>>,
        categoryAverage: (String?) -> Long?,
        knownPayees: Set<String>,
        watchlists: List<Pair<String, Watchlist>>,
        watchSpent: (Watchlist) -> Long,
        history: List<AlertRecord>,
        settings: AlertSettings = AlertSettings(),
    ): List<AlertDraft> {
        val out = mutableListOf<AlertDraft>()
        val fired = history.map { it.key }.toSet()
        fun add(d: AlertDraft) { if (d.key !in fired && out.none { it.key == d.key }) out += d }

        envelopes.filter { it.envelope.alertMode != AlertMode.OFF && !it.envelope.archived }.forEach { s ->
            val p = s.period.start
            val name = s.envelope.name
            when (s.state) {
                EnvelopeState.HALF -> add(AlertDraft(AlertKind.ENVELOPE_HALF, "half:${s.id}:$p", "envelope", s.id, listOf(name), s.availableMinor, digestOnly = true))
                EnvelopeState.NEAR -> add(AlertDraft(AlertKind.ENVELOPE_NEAR, "near:${s.id}:$p", "envelope", s.id, listOf(name), s.availableMinor))
                EnvelopeState.AT -> if (s.envelope.alertMode == AlertMode.NEAR_AND_OVER) add(AlertDraft(AlertKind.ENVELOPE_AT, "at:${s.id}:$p", "envelope", s.id, listOf(name), 0))
                EnvelopeState.OVER -> if (s.envelope.alertMode == AlertMode.NEAR_AND_OVER) {
                    // Daily while over (AL-01), with back-off after two ignored alerts this period (AL-06).
                    val ignored = history.count { it.objectId == s.id && it.kind == AlertKind.ENVELOPE_OVER && it.key.contains(":$p:") && it.state != AlertState.ACTED }
                    if (ignored < 2) add(AlertDraft(AlertKind.ENVELOPE_OVER, "over:${s.id}:$p:$today", "envelope", s.id, listOf(name), -s.availableMinor))
                }
                EnvelopeState.OK -> Unit
            }
        }

        if (safe != null && settings.paceEnabled && safe.aheadOfPace) {
            add(AlertDraft(AlertKind.PACE, "pace:$today", null, null, listOf(safe.daysLeft.toString()), safe.safeMinor))
        }
        if (safe != null && settings.safeLowEnabled && avgDailySpendMinor > 0 && safe.safeMinor in 0 until avgDailySpendMinor * 3 && safe.daysLeft > 3) {
            add(AlertDraft(AlertKind.SAFE_LOW, "safelow:${safe.period.start}", null, null, listOf(safe.daysLeft.toString()), safe.safeMinor))
        }
        if (safe != null && settings.forecastEnabled && safe.daysElapsed == 5) {
            val budget = safe.incomeMinor + safe.expectedIncomeMinor - safe.committedMinor - safe.savingsMinor
            val short = app.penny.core.insights.Forecast.endOfPeriodShort(safe.spentMinor, budget, safe.daysElapsed, safe.period.days)
            if (short > 0) add(AlertDraft(AlertKind.FORECAST_SHORT, "forecast:${safe.period.start}", null, null, emptyList(), short))
        }
        if (settings.billsEnabled) upcoming.filter { it.open && it.recurring.type == TxType.EXPENSE }.forEach { u ->
            val days = ChronoUnit.DAYS.between(today, u.date)
            if (days in 0..u.recurring.leadDays.toLong()) {
                val covered = safe == null || safe.safeMinor >= 0
                add(AlertDraft(AlertKind.BILL_DUE, "bill:${u.key}", "recurring", u.recurringId, listOf(u.recurring.name, days.toString(), covered.toString()), u.amountMinor))
            }
        }
        recent.filter { it.second.date == today && it.second.type == TxType.EXPENSE }.forEach { (id, t) ->
            val avg = categoryAverage(t.categoryId)
            if (settings.largeEnabled && avg != null && avg > 0 && t.amountMinor > avg * 3) {
                add(AlertDraft(AlertKind.LARGE_TX, "large:$id", "transaction", id, listOf(t.payee.orEmpty()), t.amountMinor))
            }
            val p = t.payee?.lowercase()?.trim()
            if (settings.unusualEnabled && p != null && p.isNotEmpty() && knownPayees.size >= 20 && p !in knownPayees && t.source != "manual") {
                add(AlertDraft(AlertKind.UNUSUAL_MERCHANT, "unusual:$id", "transaction", id, listOf(t.payee), t.amountMinor))
            }
        }
        watchlists.filter { !it.second.muted }.forEach { (id, w) ->
            val spent = watchSpent(w)
            if (spent >= w.limitMinor) add(AlertDraft(AlertKind.WATCHLIST, "watch:$id:${safe?.period?.start ?: today}", "watchlist", id, listOf(w.value, w.target.name), spent))
        }
        return out
    }

    fun inQuietHours(now: LocalTime, s: AlertSettings): Boolean =
        if (s.quietStart <= s.quietEnd) now >= s.quietStart && now < s.quietEnd else now >= s.quietStart || now < s.quietEnd

    /**
     * AL-06 delivery: digest-only drafts never notify; during quiet hours nothing notifies (held for the next window);
     * several crossings at once are batched into one notification. Returns (notifyNow, heldOrDigest).
     */
    fun plan(drafts: List<AlertDraft>, now: LocalTime, s: AlertSettings): Pair<List<AlertDraft>, List<AlertDraft>> {
        val notifiable = drafts.filter { !it.digestOnly }
        return if (inQuietHours(now, s)) emptyList<AlertDraft>() to drafts else notifiable to drafts.filter { it.digestOnly }
    }

    /** Spend for a watchlist in the window. */
    fun watchSpent(w: Watchlist, txs: List<Transaction>, from: LocalDate, to: LocalDate, categoryCovers: (String?) -> Boolean): Long =
        txs.filter { it.type == TxType.EXPENSE && !it.date.isBefore(from) && !it.date.isAfter(to) && it.currency == w.currency }.filter { t ->
            when (w.target) {
                WatchTarget.PAYEE -> t.payee?.contains(w.value, ignoreCase = true) == true
                WatchTarget.TAG -> w.value in t.tags
                WatchTarget.CATEGORY -> categoryCovers(t.categoryId)
            }
        }.sumOf { it.amountMinor }
}
