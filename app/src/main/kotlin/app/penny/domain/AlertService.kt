package app.penny.domain

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.penny.R
import app.penny.core.Brand
import app.penny.core.alerts.AlertDraft
import app.penny.core.alerts.AlertEngine
import app.penny.core.alerts.AlertSettings
import app.penny.core.budget.BudgetEngine
import app.penny.core.model.AlertKind
import app.penny.core.model.AlertRecord
import app.penny.core.rules.RuleEngine
import app.penny.domain.ops.avgDailySpend
import app.penny.domain.ops.budgetInput
import app.penny.domain.ops.categoryAverage
import app.penny.domain.ops.envelopeStatuses
import app.penny.domain.ops.period
import app.penny.domain.ops.safeToSpend
import app.penny.domain.ops.upcoming
import app.penny.ui.common.MoneyFmt
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AL-01…AL-09 runner: evaluates the engine for the open book, stores each new alert as an entity (history, dismiss,
 * mute), and notifies outside quiet hours only. Text states the number, days left and one action (AL-08). No sound by
 * default; one channel the user can tune in system settings.
 */
@Singleton
class AlertService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: PennyRepository,
    private val prefs: AppPrefs,
) {
    fun settings(): AlertSettings {
        fun t(k: String, d: String) = runCatching { LocalTime.parse(prefs.string(k) ?: d) }.getOrDefault(LocalTime.parse(d))
        return AlertSettings(
            quietStart = t("alerts.quietStart", "21:00"), quietEnd = t("alerts.quietEnd", "09:00"),
            paceEnabled = !prefs.flag("alerts.pace.off"), billsEnabled = !prefs.flag("alerts.bills.off"), largeEnabled = !prefs.flag("alerts.large.off"),
            safeLowEnabled = !prefs.flag("alerts.safe.off"), unusualEnabled = !prefs.flag("alerts.unusual.off"), forecastEnabled = !prefs.flag("alerts.forecast.off"),
        )
    }

    val notificationsOn: Boolean get() = prefs.flag("alerts.os")

    suspend fun run(book: Book): List<AlertRecord> {
        val today = book.today()
        val period = book.period()
        val fmt = MoneyFmt.of(book.profile, repo.packs.get(book.profile.regionPack))
        val drafts = AlertEngine.evaluate(
            today = today,
            envelopes = book.envelopeStatuses(today),
            safe = book.safeToSpend(period),
            avgDailySpendMinor = book.avgDailySpend(),
            upcoming = book.upcoming(today, today.plusDays(7)),
            recent = book.transactions.filter { it.value.date == today }.map { it.state.id to it.value },
            categoryAverage = book::categoryAverage,
            knownPayees = book.txList.filter { it.date.isBefore(today) }.mapNotNull { it.payee?.let(RuleEngine::normalise) }.toSet(),
            watchlists = book.watchlists.map { it.state.id to it.value },
            watchSpent = { w ->
                val cats = book.categories.filter { it.value.name.equals(w.value, true) }.map { it.state.id }.toSet()
                AlertEngine.watchSpent(w, book.txList, period.start, period.endInclusive) { c -> c in cats }
            },
            history = book.alerts.map { it.value },
            settings = settings(),
        )
        if (drafts.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val records = drafts.map { d -> AlertRecord(d.kind, d.key, d.objectEntity, d.objectId, title(d, fmt, book), body(d, fmt, book), now, amountMinor = d.amountMinor) }
        val g = repo.alerts.newGroup()
        repo.alerts.createAll(book.profileId, records.map { repo.newId() to it }, groupId = g)
        val (notifyNow, _) = AlertEngine.plan(drafts, LocalTime.now(book.zone), settings())
        if (notificationsOn && notifyNow.isNotEmpty()) notify(records.filter { r -> notifyNow.any { it.key == r.key } })
        return records
    }

    private fun money(fmt: MoneyFmt, v: Long?, book: Book) = fmt.format(v ?: 0, book.profile.baseCurrency)

    private fun title(d: AlertDraft, fmt: MoneyFmt, book: Book): String = when (d.kind) {
        AlertKind.ENVELOPE_HALF -> context.getString(R.string.al_half, d.args[0])
        AlertKind.ENVELOPE_NEAR -> context.getString(R.string.al_near, d.args[0])
        AlertKind.ENVELOPE_AT -> context.getString(R.string.al_at, d.args[0])
        AlertKind.ENVELOPE_OVER -> context.getString(R.string.al_over, d.args[0], money(fmt, d.amountMinor, book))
        AlertKind.PACE -> context.getString(R.string.al_pace)
        AlertKind.SAFE_LOW -> context.getString(R.string.al_safe_low, money(fmt, d.amountMinor, book))
        AlertKind.BILL_DUE -> context.getString(R.string.al_bill, d.args[0], money(fmt, d.amountMinor, book))
        AlertKind.LARGE_TX -> context.getString(R.string.al_large, money(fmt, d.amountMinor, book))
        AlertKind.UNUSUAL_MERCHANT -> context.getString(R.string.al_unusual, d.args[0])
        AlertKind.FORECAST_SHORT -> context.getString(R.string.al_forecast, money(fmt, d.amountMinor, book))
        AlertKind.WATCHLIST -> context.getString(R.string.al_watch, d.args[0])
        else -> context.getString(R.string.alerts)
    }

    private fun body(d: AlertDraft, fmt: MoneyFmt, book: Book): String = when (d.kind) {
        AlertKind.ENVELOPE_HALF, AlertKind.ENVELOPE_NEAR -> context.getString(R.string.al_left_body, money(fmt, d.amountMinor, book), book.period().let { java.time.temporal.ChronoUnit.DAYS.between(book.today(), it.endInclusive).toInt() })
        AlertKind.ENVELOPE_AT, AlertKind.ENVELOPE_OVER -> context.getString(R.string.al_cover_body)
        AlertKind.PACE -> context.getString(R.string.al_pace_body, d.args.firstOrNull() ?: "")
        AlertKind.SAFE_LOW -> context.getString(R.string.al_safe_low_body)
        AlertKind.BILL_DUE -> context.getString(if (d.args.getOrNull(2) == "true") R.string.al_bill_covered else R.string.al_bill_short, d.args.getOrNull(1) ?: "")
        AlertKind.FORECAST_SHORT -> context.getString(R.string.al_forecast_body)
        else -> context.getString(R.string.al_review_body)
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, context.getString(R.string.alerts), NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(false) }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun notify(records: List<AlertRecord>) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val pi = PendingIntent.getActivity(context, 0, Intent(Intent.ACTION_VIEW, Uri.parse("${Brand.DEEP_LINK_SCHEME}://home")).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        val nm = NotificationManagerCompat.from(context)
        // AL-06 batching: several crossings become one notification.
        val n = if (records.size == 1) {
            NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_penny).setContentTitle(records[0].title).setContentText(records[0].body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(records[0].body)).setContentIntent(pi).setAutoCancel(true).setSilent(true).build()
        } else {
            val style = NotificationCompat.InboxStyle()
            records.take(6).forEach { style.addLine(it.title) }
            NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_penny).setContentTitle(context.resources.getQuantityString(R.plurals.alerts_n, records.size, records.size))
                .setStyle(style).setContentIntent(pi).setAutoCancel(true).setSilent(true).build()
        }
        runCatching { nm.notify(NOTIFY_ID, n) }
    }

    companion object {
        const val CHANNEL = "alerts"
        const val NOTIFY_ID = 4201
    }
}
