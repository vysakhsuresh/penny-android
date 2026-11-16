package app.penny.glance

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.penny.R
import app.penny.core.Brand
import app.penny.core.budget.EnvelopeState
import app.penny.domain.BookStore
import app.penny.domain.ops.envelopeStatuses
import app.penny.domain.ops.period
import app.penny.domain.ops.safeToSpend
import app.penny.domain.ops.upcoming
import app.penny.ui.common.MoneyFmt
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.withTimeoutOrNull

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetDeps { fun store(): BookStore; fun prefs(): app.penny.domain.AppPrefs }

/** What the widgets show; computed once per update from the same book every screen uses. */
data class WidgetData(
    val safe: String,
    val perDay: String,
    val daysLeft: Int,
    val ahead: Boolean,
    val envelopes: List<Triple<String, Int, Boolean>>,
    val bills: List<Pair<String, String>>,
    val templates: List<Triple<String, Long?, String?>>,
    val hidden: Boolean,
)

private suspend fun load(context: Context): WidgetData? {
    val deps = EntryPointAccessors.fromApplication(context.applicationContext, WidgetDeps::class.java)
    val ready = withTimeoutOrNull(4_000) { deps.store().state.filterIsInstance<app.penny.domain.BookLoad.Ready>().first() } ?: return null
    val b = ready.book
    val fmt: MoneyFmt = ready.fmt
    val p = b.period()
    val s = b.safeToSpend(p)
    val today = b.today()
    return WidgetData(
        safe = fmt.format(s.safeMinor, b.profile.baseCurrency),
        perDay = fmt.format(s.perDayMinor, b.profile.baseCurrency),
        daysLeft = s.daysLeft + 1, // includes today, matching the per-day amount
        ahead = s.aheadOfPace,
        envelopes = b.envelopeStatuses(today).sortedByDescending { it.usedBp }.take(4).map { Triple(it.envelope.name, it.usedBp, it.state == EnvelopeState.OVER || it.state == EnvelopeState.NEAR) },
        bills = b.upcoming(today, today.plusDays(14)).filter { it.open }.take(4).map { it.recurring.name to fmt.format(it.amountMinor, it.recurring.currency) },
        templates = b.templates.filter { it.value.favourite }.take(4).map { Triple(it.value.name, it.value.amountMinor, it.value.categoryId) },
        hidden = deps.prefs().appearance.value.hideAmounts || deps.prefs().security.value.lockEnabled,
    )
}

/** Explicit intent to our own activity: an implicit penny:// link could be caught by another app (or a chooser). */
@androidx.compose.runtime.Composable
private fun open(uri: String) = actionStartActivity(
    Intent(Intent.ACTION_VIEW, Uri.parse(uri), androidx.glance.LocalContext.current, app.penny.MainActivity::class.java),
)

/** WG-01 safe-to-spend widget (small/medium): the one number, per day, days left; tap opens Home. */
class SafeWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(120.dp, 60.dp), DpSize(220.dp, 120.dp)))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = load(context)
        provideContent {
            GlanceTheme {
                val wide = LocalSize.current.width >= 200.dp
                Column(
                    GlanceModifier.fillMaxSize().background(GlanceTheme.colors.primaryContainer).cornerRadius(20.dp).padding(14.dp)
                        .clickable(open("${Brand.DEEP_LINK_SCHEME}://home")),
                ) {
                    Text(context.getString(R.string.safe_to_spend), style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp))
                    Text(if (d == null) "—" else if (d.hidden) "••••" else d.safe, style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = if (wide) 30.sp else 22.sp, fontWeight = FontWeight.Bold))
                    if (d != null && wide) {
                        Text(
                            context.getString(R.string.per_day, if (d.hidden) "••" else d.perDay) + " · " + context.resources.getQuantityString(R.plurals.days_left, d.daysLeft, d.daysLeft),
                            style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp),
                        )
                        if (d.ahead) Text(context.getString(R.string.ahead_of_pace), style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 11.sp))
                    }
                }
            }
        }
    }
}

/** WG-01 quick-add chips: favourite templates fire a prefilled add screen in one tap (TX-16). */
class QuickAddWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = load(context)
        provideContent {
            GlanceTheme {
                Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).cornerRadius(20.dp).padding(10.dp)) {
                    Row(GlanceModifier.fillMaxWidth().clickable(open("${Brand.DEEP_LINK_SCHEME}://add")), verticalAlignment = Alignment.CenterVertically) {
                        Text("+ " + context.getString(R.string.add_transaction), style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp))
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    d?.templates.orEmpty().forEach { (name, amount, cat) ->
                        val q = buildString {
                            append("${Brand.DEEP_LINK_SCHEME}://add?payee=").append(Uri.encode(name))
                            amount?.let { append("&minor=").append(it) }
                            cat?.let { append("&category=").append(Uri.encode(it)) }
                        }
                        Text(name, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp).background(GlanceTheme.colors.secondaryContainer).cornerRadius(12.dp).clickable(open(q)))
                        Spacer(GlanceModifier.height(4.dp))
                    }
                }
            }
        }
    }
}

/** WG-01 envelope progress and upcoming bills. */
class EnvelopesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = load(context)
        provideContent {
            GlanceTheme {
                Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).cornerRadius(20.dp).padding(12.dp).clickable(open("${Brand.DEEP_LINK_SCHEME}://home?tab=budgets"))) {
                    Text(context.getString(R.string.budgets), style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp))
                    d?.envelopes.orEmpty().forEach { (name, bp, warn) ->
                        Spacer(GlanceModifier.height(6.dp))
                        Text(name, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp))
                        LinearProgressIndicator((bp / 10_000f).coerceIn(0f, 1f), GlanceModifier.fillMaxWidth().height(6.dp),
                            color = if (warn) GlanceTheme.colors.tertiary else GlanceTheme.colors.primary, backgroundColor = GlanceTheme.colors.surfaceVariant)
                    }
                    if (d?.bills?.isNotEmpty() == true) {
                        Spacer(GlanceModifier.height(10.dp))
                        Text(context.getString(R.string.upcoming), style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp))
                        d.bills.forEach { (n, a) ->
                            Row(GlanceModifier.fillMaxWidth()) {
                                Text(n, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp), modifier = GlanceModifier.defaultWeight())
                                Spacer(GlanceModifier.width(6.dp))
                                Text(if (d.hidden) "••" else a, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp))
                            }
                        }
                    }
                }
            }
        }
    }
}

class SafeWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SafeWidget() }
class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = QuickAddWidget() }
class EnvelopesWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = EnvelopesWidget() }

object Widgets {
    suspend fun refresh(context: Context) {
        runCatching {
            SafeWidget().updateAll(context)
            QuickAddWidget().updateAll(context)
            EnvelopesWidget().updateAll(context)
        }
    }

    suspend fun count(context: Context) = GlanceAppWidgetManager(context).getGlanceIds(SafeWidget::class.java).size
}
