package app.penny.ui.insights

import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Edit
import app.penny.ui.common.safeStart
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.Routes
import app.penny.core.event.PennyJson
import app.penny.core.insights.Coach
import app.penny.core.insights.CoachAnswer
import app.penny.core.insights.CoachIntent
import app.penny.core.insights.CoachTone
import app.penny.core.insights.Forecast
import app.penny.core.insights.Insights
import app.penny.core.insights.NetWorth
import app.penny.core.model.TxFilter
import app.penny.core.model.TxType
import app.penny.core.money.CurrencyUnit
import app.penny.core.period.Period
import app.penny.core.period.Periods
import app.penny.core.search.NaturalQueryParser
import app.penny.core.search.Row as SRow
import app.penny.domain.ops.balance
import app.penny.domain.ops.period
import app.penny.domain.ops.safeToSpend
import app.penny.domain.ops.upcoming
import app.penny.ui.common.Ctx
import app.penny.ui.common.TxRow
import app.penny.ui.common.WithBook
import app.penny.ui.common.shortDate
import app.penny.ui.kit.Bars
import app.penny.ui.kit.Choice
import app.penny.ui.kit.Datum
import app.penny.ui.kit.Donut
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.FlowBars
import app.penny.ui.kit.Heatmap
import app.penny.ui.kit.LineChart
import app.penny.ui.kit.ListRow
import app.penny.ui.kit.MoneyLabel
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.Segmented
import app.penny.ui.kit.StatBlock
import app.penny.ui.kit.topWithOther
import app.penny.ui.theme.Penny
import java.time.LocalDate

private enum class Range { PERIOD, MONTH, QUARTER, YEAR }

/** IN-01…IN-10 on one screen; every mark drills down to the transactions behind it (IN-03). */
@Composable
fun InsightsScreen(padding: PaddingValues, go: (String) -> Unit) {
    WithBook { ctx, _ ->
        val b = ctx.book
        val context = LocalContext.current
        var range by rememberSaveable { mutableStateOf(Range.PERIOD) }
        var offset by rememberSaveable { mutableIntStateOf(0) }
        var table by rememberSaveable { mutableStateOf(false) }
        val today = b.today()
        val p: Period = when (range) {
            Range.PERIOD -> b.period(offset)
            Range.MONTH -> today.withDayOfMonth(1).plusMonths(offset.toLong()).let { Period(it, it.plusMonths(1).minusDays(1)) }
            Range.QUARTER -> today.withDayOfMonth(1).minusMonths(((today.monthValue - 1) % 3).toLong()).plusMonths(offset * 3L).let { Period(it, it.plusMonths(3).minusDays(1)) }
            // The year follows the profile's tax year ("Tax year starts"), so the tax report covers the right months.
            Range.YEAR -> {
                val (m, d) = b.profile.fiscalYearStart.split('-').let { (it.getOrNull(0)?.toIntOrNull() ?: 1) to (it.getOrNull(1)?.toIntOrNull() ?: 1) }
                val thisYear = runCatching { java.time.LocalDate.of(today.year, m, d) }.getOrDefault(today.withDayOfYear(1))
                val start = (if (today.isBefore(thisYear)) thisYear.minusYears(1) else thisYear).plusYears(offset.toLong())
                Period(start, start.plusYears(1).minusDays(1))
            }
        }
        val ins = remember(b) { Insights(b.txList, ctx.base, b.excludedCategoryIds, b.fx, b::topLevel) }
        val s = ins.summary(p, b.profile.cycle, b.periodContext)
        val other = stringResource(R.string.other)
        fun drill(f: TxFilter) = go(Routes.activity(PennyJson.encodeToString(TxFilter.serializer(), f)))
        fun label(key: String) = if (key == Insights.UNCATEGORISED) context.getString(R.string.uncategorised) else b.categoryMap[key]?.value?.name ?: context.getString(R.string.uncategorised)

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tab_insights), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    app.penny.ui.kit.RevealAmountsButton()
                    IconButton(onClick = { table = !table }) { Icon(Icons.Outlined.TableRows, stringResource(if (table) R.string.show_charts else R.string.show_table)) }
                    IconButton(onClick = {
                        // IN-09 digest: plain text summary to share with the household.
                        val text = context.getString(R.string.digest_text, shortDate(p.start), shortDate(p.endInclusive), ctx.fmt.format(s.incomeMinor, ctx.base), ctx.fmt.format(s.spentMinor, ctx.base), s.byCategory.take(3).joinToString { label(it.key) + " " + ctx.fmt.format(it.amountMinor, ctx.base) })
                        // Shared as a designed card (the text rides along as the caption).
                        val net = s.incomeMinor - s.spentMinor
                        app.penny.ui.common.ShareCard.share(
                            context, context.getString(R.string.share_card_summary_title), app.penny.ui.common.periodRange(context, p.start, p.endInclusive),
                            context.getString(R.string.spent), ctx.fmt.format(s.spentMinor, ctx.base),
                            listOf(
                                app.penny.ui.common.ShareRow(context.getString(R.string.income), ctx.fmt.format(s.incomeMinor, ctx.base), app.penny.ui.common.ShareRow.Tone.Good),
                                app.penny.ui.common.ShareRow(context.getString(R.string.net), ctx.fmt.format(net, ctx.base, signed = true), if (net >= 0) app.penny.ui.common.ShareRow.Tone.Good else app.penny.ui.common.ShareRow.Tone.Warn),
                            ) + s.byCategory.take(4).map { app.penny.ui.common.ShareRow(label(it.key), ctx.fmt.format(it.amountMinor, ctx.base)) },
                            text,
                        )
                    }) { Icon(Icons.Outlined.IosShare, stringResource(R.string.share_digest)) }
                }
            }
            item { Segmented(listOf(Choice(Range.PERIOD, stringResource(R.string.range_period)), Choice(Range.MONTH, stringResource(R.string.range_month)), Choice(Range.QUARTER, stringResource(R.string.range_quarter)), Choice(Range.YEAR, stringResource(R.string.range_year))), range, { range = it; offset = 0 }) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { offset-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous)) }
                    Text(app.penny.ui.common.periodRange(androidx.compose.ui.platform.LocalContext.current, p.start, p.endInclusive), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { offset++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next)) }
                }
            }
            if (offset != 0) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton(onClick = { offset = 0 }) { Text(stringResource(R.string.this_period)) } }
            }
            // "Pay period" follows the pay cycle in the profile; say which one it is and where to change it.
            if (range == Range.PERIOD) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    // One tidy chip: the cycle in use, tap to change it (a sentence plus a link wrapped at large text).
                    androidx.compose.material3.AssistChip(
                        onClick = { go(app.penny.Routes.PROFILE_SETTINGS) },
                        label = { Text(app.penny.ui.common.cycleLabel(LocalContext.current, ctx.book.profile.cycle), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Edit, stringResource(R.string.change_cycle), Modifier.size(16.dp)) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    )
                }
            }
            item {
                PennyCard {
                    app.penny.ui.kit.StatsRow(listOf(
                        app.penny.ui.kit.Stat(stringResource(R.string.income), ctx.money(s.incomeMinor), Penny.status.positive),
                        app.penny.ui.kit.Stat(stringResource(R.string.spent), ctx.money(s.spentMinor)),
                        app.penny.ui.kit.Stat(stringResource(R.string.net), ctx.money(s.netMinor, signed = s.netMinor > 0), if (s.netMinor < 0) Penny.status.near else androidx.compose.ui.graphics.Color.Unspecified),
                    ), Modifier.padding(bottom = 0.dp))
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        fun delta(now: Long, then: Long): String = if (then == 0L) "—" else "%+d%%".format(((now - then) * 100 / then).toInt())
                        // Only comparisons that exist; a row of dashes says nothing.
                        val parts = listOfNotNull(
                            s.previousSpentMinor.takeIf { it != 0L }?.let { stringResource(R.string.vs_previous, delta(s.spentMinor, it)) },
                            s.lastYearSpentMinor.takeIf { it != 0L }?.let { stringResource(R.string.vs_last_year, delta(s.spentMinor, it)) },
                        )
                        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (s.count == 0) item { EmptyState(stringResource(R.string.no_insights), stringResource(R.string.no_insights_body)) }
            else {
                item { SectionHeader(stringResource(R.string.where_it_went), info = stringResource(R.string.info_where_it_went)) }
                item {
                    val data = topWithOther(s.byCategory.map { Datum(it.key, label(it.key), it.amountMinor, ctx.money(it.amountMinor)) }, 5, other) { ctx.money(it) }
                    PennyCard {
                        if (table) data.forEach { d -> ListRow(d.label, trailingText = d.valueText, onClick = { drill(TxFilter(from = p.start, to = p.endInclusive, categoryIds = listOf(d.key) + b.categories.filter { it.value.parentId == d.key }.map { it.state.id })) }, divider = true) }
                        else Donut(data, stringResource(R.string.spent), ctx.money(s.spentMinor)) { d ->
                            drill(TxFilter(from = p.start, to = p.endInclusive, categoryIds = listOf(d.key) + b.categories.filter { it.value.parentId == d.key }.map { it.state.id }, uncategorised = d.key == Insights.UNCATEGORISED))
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.trend), info = stringResource(R.string.info_trend)) }
                item {
                    val trend = ins.trend(b.period(0, p.start), b.profile.cycle, 6, b.periodContext)
                    PennyCard {
                        Bars(trend.map { (per, spent, _) -> Datum(per.start.toString(), app.penny.ui.common.monthDay(per.start), spent, ctx.money(spent)) }, secondary = trend.map { it.third }, onSelect = { i -> drill(TxFilter(from = trend[i].first.start, to = trend[i].first.endInclusive)) })
                        // Legend: a colour dot with plain text (identity by colour, words in text colours).
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            listOf(stringResource(R.string.spent) to MaterialTheme.colorScheme.primary, stringResource(R.string.income) to app.penny.ui.theme.Penny.gold).forEach { (label, c) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c))
                                    Spacer(Modifier.width(8.dp))
                                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.by_day), info = stringResource(R.string.info_by_day)) }
                item { PennyCard { Spacer(Modifier.height(12.dp)); Heatmap(ins.daily(p).toList(), { ctx.money(it) }, firstDay = b.profile.firstDayOfWeek) { d -> drill(TxFilter(from = d, to = d)) }; Spacer(Modifier.height(12.dp)) } }
                item { SectionHeader(stringResource(R.string.money_flow), info = stringResource(R.string.info_money_flow)) }
                item {
                    val flows = ins.flows(p, { id -> b.categoryMap[id]?.value?.name ?: context.getString(R.string.income) }, { id -> id?.let { label(it) } ?: context.getString(R.string.uncategorised) })
                    val sources = flows.filter { it.to == Insights.BUDGET }.map { Datum(it.from, if (it.from == Insights.FROM_SAVINGS) context.getString(R.string.from_savings) else it.from, it.amountMinor, ctx.money(it.amountMinor)) }
                    val sinks = topWithOther(flows.filter { it.from == Insights.BUDGET }.map { Datum(it.to, if (it.to == Insights.SAVED) context.getString(R.string.saved_label) else it.to, it.amountMinor, ctx.money(it.amountMinor)) },
                        // Income takes the first palette colours; the spending side gets the rest, never a repeat (Food was income-blue).
                        (app.penny.ui.theme.LocalPenny.current.entityPalette.size - sources.size).coerceAtLeast(1), other) { ctx.money(it) }
                    PennyCard { Spacer(Modifier.height(12.dp)); FlowBars(sources, sinks); Spacer(Modifier.height(12.dp)) }
                }
                if (s.byPayee.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.top_payees), info = stringResource(R.string.info_top_payees)) }
                    item { PennyCard { s.byPayee.take(5).forEach { pp -> ListRow(pp.key, trailingText = ctx.money(pp.amountMinor), onClick = { drill(TxFilter(from = p.start, to = p.endInclusive, text = pp.key)) }, divider = true) } } }
                }
                if (s.byMember.size > 1) {
                    // IN-07 household view by member.
                    item { SectionHeader(stringResource(R.string.by_member)) }
                    item { PennyCard { s.byMember.forEach { m -> ListRow(if (m.key == Insights.ME) stringResource(R.string.you) else b.personName(m.key).ifEmpty { m.key.take(6) }, trailingText = ctx.money(m.amountMinor), divider = true) } } }
                }
                if (b.profile.business != null) {
                    // IN-10 business: tax categories and VAT share.
                    item { SectionHeader(stringResource(R.string.tax_report)) }
                    item {
                        val taxCats = b.categories.filter { it.value.tax }.map { it.state.id }.toSet()
                        val taxSpend = b.txList.filter { it.date in p && (it.categoryId in taxCats || "tax" in it.tags.map(String::lowercase)) }.sumOf { it.amountMinor }
                        val vat = s.spentMinor * (b.profile.business?.vatRateBp ?: 0) / (10_000 + (b.profile.business?.vatRateBp ?: 0))
                        val miles = b.txList.filter { it.date in p }.sumOf { it.distance ?: 0.0 }
                        PennyCard { app.penny.ui.kit.StatsRow(listOf(
                            app.penny.ui.kit.Stat(stringResource(R.string.tax_deductible), ctx.money(taxSpend)),
                            app.penny.ui.kit.Stat(stringResource(R.string.vat_in_spend), ctx.money(vat)),
                            app.penny.ui.kit.Stat(stringResource(R.string.distance), "%.1f".format(miles)),
                        )) }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.net_worth), info = stringResource(R.string.info_net_worth)) }
            item {
                val full = NetWorth.history(b.accounts.map { it.state.id to it.value }, b.txList, today, 12, ctx.base, b.fx, b.valuations.map { it.value }, ctx.pack.goldPricePerGram)
                // The months before anything was recorded are not "zero net worth"; start the line at the first value.
                val first = full.indexOfFirst { it.second != 0L }
                val hist = if (first > 0) full.drop(first) else full
                PennyCard {
                    if (hist.size >= 2) { Spacer(Modifier.height(12.dp)); LineChart(hist.map { app.penny.ui.common.monthDay(it.first) to it.second }, { ctx.money(it) }); Spacer(Modifier.height(12.dp)) }
                    else Column(Modifier.padding(16.dp)) {
                        app.penny.ui.kit.MoneyLabel(ctx.money(full.lastOrNull()?.second ?: 0L), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.net_worth_builds), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.forecast), info = stringResource(R.string.info_forecast)) }
            item { ForecastCard(ctx) }
            item { Column { PlaygroundCards(ctx, ins, p, s, b.profile.cycle, { k -> label(k) }) { d -> drill(TxFilter(from = d, to = d)) } } }
            item { SectionHeader(stringResource(R.string.reports_and_coach)) }
            item {
                PennyCard {
                    ListRow(stringResource(R.string.ask_coach), subtitle = stringResource(R.string.ask_coach_sub), leading = { Icon(Icons.Outlined.AutoAwesome, null) }, onClick = { go(Routes.COACH) }, divider = true)
                    b.savedSearches.forEach { r -> ListRow(r.value.name, subtitle = stringResource(R.string.saved_report), onClick = { drill(r.value.filter) }, divider = true) }
                    if (b.savedSearches.isEmpty()) Text(stringResource(R.string.reports_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

/** IN-06: 90-day balance projection from scheduled items and run rate; scenario slider cuts a category. */
@Composable
private fun ForecastCard(ctx: Ctx) {
    val b = ctx.book
    val today = b.today()
    var cut by rememberSaveable { mutableStateOf(0f) }
    var cutCat by rememberSaveable { mutableStateOf<String?>(null) }
    val start = b.activeAccounts.filter { it.value.includeInBudget && it.value.currency == ctx.base }.sumOf { b.balance(it.state.id) }
    val sched = b.upcoming(today.plusDays(1), today.plusDays(90)).filter { it.open }.map { u -> u.date to (if (u.recurring.type == TxType.INCOME) u.amountMinor else -u.amountMinor) }
    val top = remember(b) {
        // Only everyday spending can be cut: not savings, transfers, money lent or loan payments.
        b.txList.filter { it.type == TxType.EXPENSE && it.date.isAfter(today.minusDays(90)) && it.recurringId == null && it.categoryId !in b.excludedCategoryIds }.groupBy { b.topLevel(it.categoryId) }
            .filterKeys { k -> k != null && k !in b.excludedCategoryIds && b.categoryMap[k]?.value?.key?.startsWith("loan") != true }
            .mapValues { (_, l) -> l.sumOf { it.amountMinor } }.entries.sortedByDescending { it.value }.take(4).mapNotNull { it.key }
    }
    val cutCats = cutCat?.let { c -> (listOf(c) + b.categories.filter { it.value.parentId == c }.map { it.state.id }).associateWith { (cut * 100).toInt() } }.orEmpty()
    val pts = Forecast.project(start, today, 90, b.txList, sched, b.excludedCategoryIds, cutCats)
    val safe = b.safeToSpend(b.period())
    PennyCard {
        Column(Modifier.padding(vertical = 12.dp)) {
            LineChart(listOf(shortDate(today) to start) + pts.filterIndexed { i, _ -> i % 7 == 6 || i == pts.lastIndex }.map { app.penny.ui.common.monthDay(it.date) to it.balanceMinor }, { ctx.money(it) })
            Text(stringResource(R.string.forecast_end_clear, ctx.money(pts.last().balanceMinor)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Text(stringResource(R.string.forecast_period_end, ctx.money(safe.safeMinor)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            if (top.isNotEmpty()) {
                Text(stringResource(R.string.what_if_cut), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(top) { c -> FilterChip(cutCat == c, { cutCat = if (cutCat == c) null else c }, label = { Text(b.categoryMap[c]?.value?.name ?: c) }) }
                }
                if (cutCat != null) {
                    Slider(cut, { cut = it }, valueRange = 0f..100f, steps = 9, modifier = Modifier.padding(horizontal = 16.dp))
                    Text(stringResource(R.string.cut_by, cut.toInt()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

/** IN-08 coach: on-device answers over the user's data, always with the transactions behind the answer. */
@Composable
fun CoachScreen(onBack: () -> Unit, onOpenTx: (String) -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        var q by rememberSaveable { mutableStateOf("") }
        var tone by rememberSaveable { mutableStateOf(CoachTone.valueOf(vm.prefs.string("coach.tone") ?: CoachTone.GENTLE.name)) }
        var answer by remember { mutableStateOf<CoachAnswer?>(null) }
        var asked by rememberSaveable { mutableStateOf("") }
        val decimals = runCatching { CurrencyUnit.of(ctx.base).minorUnits }.getOrDefault(2)
        fun ask(text: String) {
            asked = text
            val parser = NaturalQueryParser(b.today(), b.profile.cycle, decimals, b.categories.associate { it.value.name.lowercase() to it.state.id }, b.accounts.associate { it.value.name.lowercase() to it.state.id },
                categoryChildren = b.categories.filter { it.value.parentId != null }.groupBy({ it.value.parentId!! }, { it.state.id }))
            val prev = Periods.previous(b.period(), b.profile.cycle, b.periodContext)
            answer = Coach(parser, tone, b.profile.baseCurrency, b.fx).ask(text, b.rows, b.safeToSpend(b.period()).safeMinor, b.rows.filter { it.tx.date in prev })
        }
        PennyScreen(stringResource(R.string.coach), onBack) { pad ->
            LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item { Text(stringResource(R.string.coach_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp)) }
                item { Segmented(CoachTone.entries.map { Choice(it, stringResource(when (it) { CoachTone.GENTLE -> R.string.tone_gentle; CoachTone.DIRECT -> R.string.tone_direct; CoachTone.ROAST -> R.string.tone_roast })) }, tone, { tone = it; vm.prefs.setString("coach.tone", it.name); if (asked.isNotEmpty()) ask(asked) }) }
                item {
                    app.penny.ui.kit.SoftField(
                        q, { q = it }, placeholder = { Text(stringResource(R.string.coach_hint)) }, singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                        trailingIcon = { IconButton(onClick = { if (q.isNotBlank()) ask(q) }) { Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.ask)) } },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(R.string.coach_q1, R.string.coach_q2, R.string.coach_q3, R.string.coach_q4)) { r ->
                            val t = stringResource(r)
                            FilterChip(false, { q = t; ask(t) }, label = { Text(t) })
                        }
                    }
                }
                answer?.let { a ->
                    item { PennyCard(color = MaterialTheme.colorScheme.secondaryContainer) { Text(coachText(ctx, a), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp)) } }
                    if (a.understood.isNotEmpty()) item { Text(stringResource(R.string.understood, a.understood.joinToString(" · ")), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
                    if (a.evidence.isNotEmpty()) item { SectionHeader(stringResource(R.string.based_on)) }
                    items(a.evidence, key = { it.id }) { r: SRow -> TxRow(r.id, r.tx, ctx, onOpen = { onOpenTx(r.id) }, showDate = true) }
                }
            }
        }
    }
}

@Composable
private fun coachText(ctx: Ctx, a: CoachAnswer): String {
    val v = a.valueMinor ?: 0
    val core = when (a.intent) {
        CoachIntent.TOTAL, CoachIntent.UNKNOWN -> stringResource(R.string.coach_total, ctx.money(v), a.evidence.size)
        CoachIntent.AVERAGE -> stringResource(R.string.coach_average, ctx.money(v), (a.secondMinor ?: 0).toInt())
        CoachIntent.COUNT -> stringResource(R.string.coach_count, v.toInt(), ctx.money(a.secondMinor ?: 0))
        CoachIntent.BIGGEST -> stringResource(R.string.coach_biggest, ctx.money(v), a.label ?: "—")
        CoachIntent.COMPARE -> stringResource(R.string.coach_compare, ctx.money(v), ctx.money(a.secondMinor ?: 0))
        CoachIntent.WHERE_MONEY_WENT -> stringResource(R.string.coach_where, ctx.book.categoryName(a.label).ifEmpty { stringResource(R.string.uncategorised) }, ctx.money(v), ctx.money(a.secondMinor ?: 0))
        CoachIntent.CAN_I_AFFORD -> {
            val ask = a.secondMinor
            if (ask == null) stringResource(R.string.coach_safe, ctx.money(v))
            else if (ask <= v) stringResource(R.string.coach_afford_yes, ctx.money(ask), ctx.money(v - ask))
            else stringResource(R.string.coach_afford_no, ctx.money(ask), ctx.money(v))
        }
    }
    val suffix = when (a.tone) {
        CoachTone.GENTLE -> ""
        CoachTone.DIRECT -> " " + stringResource(R.string.coach_direct_suffix)
        CoachTone.ROAST -> " " + stringResource(if ((a.valueMinor ?: 0) > 0 && a.intent != CoachIntent.CAN_I_AFFORD) R.string.coach_roast_suffix else R.string.coach_roast_kind)
    }
    return core + suffix
}
