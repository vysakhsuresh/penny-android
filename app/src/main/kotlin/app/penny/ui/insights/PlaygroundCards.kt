package app.penny.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.core.insights.Insights
import app.penny.core.insights.Playground
import app.penny.core.insights.PeriodSummary
import app.penny.core.period.CycleRule
import app.penny.core.period.Period
import app.penny.ui.common.Ctx
import app.penny.ui.common.shortDate
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.SectionHeader
import app.penny.ui.theme.Penny
import java.time.format.TextStyle
import java.util.Locale

/**
 * "Money playground": friendly facts, the week's rhythm, this period against the last one and a small what-if,
 * so Insights is somewhere to explore, not only a report. Everything respects hidden amounts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaygroundCards(ctx: Ctx, ins: Insights, p: Period, s: PeriodSummary, rule: CycleRule, label: (String) -> String, onDay: (java.time.LocalDate) -> Unit) {
    val b = ctx.book
    val today = b.today()
    val facts = remember(b, p) { Playground.facts(ins.daily(p), today) }
    val prev = remember(p) { Playground.previous(p) }
    val prevSummary = remember(b, prev) { ins.summary(prev, rule, b.periodContext) }
    val locale = Locale.getDefault()

    SectionHeader(stringResource(R.string.playground), info = stringResource(R.string.info_playground))
    if (facts.daysCounted == 0 || s.spentMinor == 0L) {
        PennyCard { Text(stringResource(R.string.playground_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
        return
    }

    // Fun facts: two tiles a row, each a big figure with a short label.
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
        val tile = Modifier.weight(1f)
        FactTile(Icons.Outlined.Today, ctx.money(facts.avgPerDayMinor), stringResource(R.string.fact_avg_day), tile)
        facts.biggestDay?.let { d -> FactTile(Icons.Outlined.EmojiEvents, ctx.money(facts.biggestDayMinor), stringResource(R.string.fact_biggest_day, shortDate(d)), tile, onClick = { onDay(d) }) }
        facts.busiestWeekday?.let { w -> FactTile(Icons.Outlined.CalendarMonth, w.getDisplayName(TextStyle.FULL, locale), stringResource(R.string.fact_busiest_day), tile) }
        FactTile(Icons.Outlined.LocalFireDepartment, pluralStringResource(R.plurals.days_n, facts.currentStreak, facts.currentStreak), stringResource(R.string.fact_streak, facts.bestStreak), tile, accent = facts.currentStreak > 0)
        s.byPayee.firstOrNull()?.let { top -> FactTile(Icons.Outlined.Storefront, top.key, stringResource(R.string.fact_top_place, ctx.money(top.amountMinor)), tile) }
        if (s.incomeMinor > 0) {
            val rate = ((s.incomeMinor - s.spentMinor) * 100 / s.incomeMinor).toInt()
            FactTile(Icons.Outlined.Savings, if (ctx.hidden) "••" else "$rate%", stringResource(R.string.fact_kept), tile, accent = rate > 0)
        }
    }

    // The week's rhythm: average spend per weekday; the busiest day in the accent colour. Tap a bar to read it.
    SectionHeader(stringResource(R.string.week_rhythm))
    PennyCard {
        var picked by remember(p) { mutableStateOf(facts.busiestWeekday) }
        val order = java.time.DayOfWeek.entries.let { all -> val first = java.time.temporal.WeekFields.of(locale).firstDayOfWeek; all.drop(first.ordinal) + all.take(first.ordinal) }
        val max = (facts.weekdayAvgMinor.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
        Column(Modifier.padding(16.dp)) {
            picked?.let { w -> Text(stringResource(R.string.week_rhythm_value, w.getDisplayName(TextStyle.FULL, locale), ctx.money(facts.weekdayAvgMinor[w] ?: 0)), style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                order.forEach { w ->
                    val v = facts.weekdayAvgMinor[w] ?: 0L
                    val name = w.getDisplayName(TextStyle.SHORT, locale)
                    val desc = stringResource(R.string.week_rhythm_value, w.getDisplayName(TextStyle.FULL, locale), ctx.money(v))
                    Column(Modifier.weight(1f).fillMaxHeight().clickable { picked = w }.semantics { contentDescription = desc }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Box(
                            Modifier.fillMaxWidth().height((88 * v / max).toInt().coerceAtLeast(4).dp).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(if (w == facts.busiestWeekday) MaterialTheme.colorScheme.primary else if (w == picked) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else Penny.status.track),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(name, style = MaterialTheme.typography.labelSmall, color = if (w == picked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }

    // This period against the one before it, per category: down is good news (green), up is worth a look (amber).
    val prevBy = prevSummary.byCategory.associate { it.key to it.amountMinor }
    val rows = s.byCategory.take(6)
    if (rows.isNotEmpty() && prevSummary.spentMinor > 0) {
        SectionHeader(stringResource(R.string.vs_last_period))
        PennyCard {
            Column(Modifier.padding(vertical = 8.dp)) {
                val totalDelta = s.spentMinor - prevSummary.spentMinor
                Text(
                    stringResource(if (totalDelta <= 0) R.string.vs_total_down else R.string.vs_total_up, ctx.money(kotlin.math.abs(totalDelta))),
                    style = MaterialTheme.typography.titleSmall, color = if (totalDelta <= 0) Penny.status.positive else Penny.status.near,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                rows.forEach { c ->
                    val before = prevBy[c.key] ?: 0L
                    val delta = c.amountMinor - before
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label(c.key), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ctx.money(c.amountMinor), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(10.dp))
                        val down = delta <= 0
                        val tone = if (delta == 0L) MaterialTheme.colorScheme.onSurfaceVariant else if (down) Penny.status.positive else Penny.status.near
                        Icon(if (down) Icons.AutoMirrored.Outlined.TrendingDown else Icons.AutoMirrored.Outlined.TrendingUp, null, tint = tone, modifier = Modifier.size(18.dp))
                        Text(
                            if (before == 0L) stringResource(R.string.vs_new) else ctx.money(kotlin.math.abs(delta)),
                            style = MaterialTheme.typography.labelMedium, color = tone, modifier = Modifier.padding(start = 4.dp).width(88.dp), maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    // A small what-if: put a little aside every day and see it grow.
    SectionHeader(stringResource(R.string.small_change))
    PennyCard {
        // Whole currency units only (₹10, not ₹1.16): one unit per step, up to the daily average (at least 100 units).
        val unit = Math.pow(10.0, app.penny.core.money.CurrencyUnit.of(ctx.base).minorUnits.toDouble()).toLong()
        val maxPerDay = ((facts.avgPerDayMinor / unit) * unit).coerceAtLeast(unit * 100)
        var perDay by rememberSaveable(maxPerDay) { mutableStateOf(((facts.avgPerDayMinor / 5 / unit) * unit).coerceIn(unit, maxPerDay).toFloat()) }
        val v = ((perDay.toLong() + unit / 2) / unit * unit).coerceAtLeast(unit)
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.small_change_q, ctx.money(v)), style = MaterialTheme.typography.bodyLarge)
            Slider(perDay, { perDay = it }, valueRange = unit.toFloat()..maxPerDay.toFloat())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(30 to R.string.in_a_month, 182 to R.string.in_six_months, 365 to R.string.in_a_year).forEach { (days, l) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        app.penny.ui.kit.MoneyLabel(ctx.money(Playground.setAside(v, days)), style = MaterialTheme.typography.titleMedium, color = Penny.status.positive)
                        Text(stringResource(l), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun FactTile(icon: ImageVector, value: String, label: String, modifier: Modifier, accent: Boolean = false, onClick: (() -> Unit)? = null) {
    val edge = androidx.compose.foundation.BorderStroke(1.dp, if (Penny.extras.isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.06f))
    val colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Penny.card)
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = if (accent) Penny.status.positive else MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
    if (onClick != null) androidx.compose.material3.Card(onClick = onClick, modifier = modifier, colors = colors, shape = MaterialTheme.shapes.large, border = edge) { body() }
    else androidx.compose.material3.Card(modifier = modifier, colors = colors, shape = MaterialTheme.shapes.large, border = edge) { body() }
}
