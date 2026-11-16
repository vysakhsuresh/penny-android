package app.penny.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.core.budget.EnvelopeState
import app.penny.core.model.AlertState
import app.penny.core.model.CaptureState
import app.penny.domain.ops.balance
import app.penny.domain.ops.envelopeStatuses
import app.penny.domain.ops.period
import app.penny.domain.ops.runScheduled
import app.penny.domain.ops.safeToSpend
import app.penny.domain.ops.upcoming
import app.penny.ui.common.BookViewModel
import app.penny.ui.common.Ctx
import app.penny.ui.common.SwipeableTx
import app.penny.ui.common.TxRow
import app.penny.ui.common.WithBook
import app.penny.ui.common.accountIcon
import app.penny.ui.common.relativeDate
import app.penny.ui.common.shortDate
import app.penny.ui.kit.Avatar
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.Meter
import app.penny.ui.kit.MoneyLabel
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.Pill
import app.penny.ui.kit.SectionHeader
import app.penny.ui.theme.Penny
import java.time.temporal.ChronoUnit

data class HomeNav(
    val openTx: (String) -> Unit,
    val add: () -> Unit,
    val activity: () -> Unit,
    val search: () -> Unit,
    val accounts: () -> Unit,
    val account: (String) -> Unit,
    val envelope: (String) -> Unit,
    val bills: () -> Unit,
    val alerts: () -> Unit,
    val review: () -> Unit,
    val profiles: () -> Unit,
    val categorySheet: (String) -> Unit,
)

@Composable
fun HomeScreen(nav: HomeNav, padding: PaddingValues) {
    WithBook { ctx, vm ->
        // Auto-post due bills and goal contributions once per open (RC-01, GO-01).
        LaunchedEffect(ctx.book.profileId) { vm.launch { runScheduled(ctx.book) } }
        HomeContent(ctx, vm, nav, padding)
    }
}

@Composable
private fun HomeContent(ctx: Ctx, vm: BookViewModel, nav: HomeNav, padding: PaddingValues) {
    val b = ctx.book
    var offset by rememberSaveable { mutableIntStateOf(0) }
    // Read the shared setting: a local copy went stale when amounts were shown again from another screen.
    val hide = app.penny.ui.theme.LocalPenny.current.hideAmounts
    val period = b.period(offset)
    val safe = b.safeToSpend(period)
    val today = b.today()
    val context = LocalContext.current
    val pendingReview = b.captures.count { it.value.state == CaptureState.PENDING }
    val newAlerts = b.alerts.filter { it.value.state == AlertState.NEW }
    val rows = b.transactions.filter { it.value.date in period }
        .sortedWith(compareByDescending<app.penny.core.event.Stored<app.penny.core.model.Transaction>> { it.value.date }.thenByDescending { it.value.time }.thenByDescending { it.state.createdAt })

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.profiles) { Avatar(b.profile.name, b.profileId, size = 36.dp, modifier = Modifier.semantics { contentDescription = context.getString(R.string.settings) }) }
                // The book name opens the book list too (switch, add, rename), not only the small avatar.
                Column(Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable(onClick = nav.profiles).padding(start = 4.dp, top = 2.dp, bottom = 2.dp)) {
                    Text(b.profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(app.penny.ui.common.periodRange(androidx.compose.ui.platform.LocalContext.current, period.start, period.endInclusive), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.prefs.setAppearance(vm.prefs.appearance.value.copy(hideAmounts = !hide)) }) {
                    Icon(if (hide) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(if (hide) R.string.show_amounts else R.string.hide_amounts))
                }
                IconButton(onClick = nav.search) { Icon(Icons.Outlined.Search, stringResource(R.string.search)) }
                IconButton(onClick = nav.alerts) {
                    BadgedBox(badge = { if (newAlerts.isNotEmpty()) Badge { Text(newAlerts.size.toString()) } }) {
                        Icon(Icons.Outlined.NotificationsNone, stringResource(R.string.alerts))
                    }
                }
            }
        }
        item { SafeHero(ctx, safe, offset, onShift = { d -> offset = if (d == 0) 0 else offset + d }) }

        if (pendingReview > 0) item {
            PennyCard(onClick = nav.review, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Inbox, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pluralStringResource(R.plurals.review_waiting, pendingReview, pendingReview), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(stringResource(R.string.review_waiting_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }

        newAlerts.firstOrNull()?.let { a ->
            item {
                PennyCard(onClick = nav.alerts, color = Penny.status.nearContainer) {
                    Column(Modifier.padding(16.dp)) {
                        Text(a.value.title, style = MaterialTheme.typography.titleSmall)
                        Text(a.value.body, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Upcoming bills in the next 14 days (RC-06 coverage at a glance).
        val bills = b.upcoming(today, today.plusDays(14)).filter { it.open }
        if (bills.isNotEmpty() && offset == 0) {
            item { SectionHeader(stringResource(R.string.upcoming), action = stringResource(R.string.see_all), onAction = nav.bills) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(bills.take(10), key = { it.key }) { u ->
                        val days = ChronoUnit.DAYS.between(today, u.date).toInt()
                        Card(onClick = nav.bills, colors = CardDefaults.cardColors(containerColor = app.penny.ui.theme.Penny.card), shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(14.dp).width(150.dp)) {
                                Icon(Icons.Outlined.ReceiptLong, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(6.dp))
                                Text(u.recurring.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                MoneyLabel(ctx.money(u.amountMinor, u.recurring.currency), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (days == 0) stringResource(R.string.due_today) else pluralStringResource(R.plurals.due_in_days, days, days),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Envelopes that need attention first (near or over), then the busiest.
        val envs = b.envelopeStatuses(period.start).sortedByDescending { it.usedBp }.take(4)
        if (envs.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.budgets)) }
            item {
                PennyCard {
                    envs.forEach { s ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.envelope.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                MoneyLabel(
                                    if (s.availableMinor >= 0) stringResource(R.string.x_left, ctx.money(s.availableMinor, s.envelope.currency)) else stringResource(R.string.x_over, ctx.money(-s.availableMinor, s.envelope.currency)),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (s.state == EnvelopeState.OVER) Penny.status.over else if (s.state == EnvelopeState.NEAR) Penny.status.near else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Meter(s.usedBp, s.state)
                        }
                    }
                }
            }
        }

        if (b.activeAccounts.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.accounts), action = stringResource(R.string.see_all), onAction = nav.accounts) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(b.activeAccounts, key = { it.state.id }) { a ->
                        Card(onClick = { nav.account(a.state.id) }, colors = CardDefaults.cardColors(containerColor = app.penny.ui.theme.Penny.card), shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(14.dp).width(148.dp)) {
                                Avatar(a.value.name, a.state.id, icon = accountIcon(a.value.type), size = 32.dp)
                                Spacer(Modifier.height(8.dp))
                                Text(a.value.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val v = if (a.value.type in listOf(app.penny.core.model.AccountType.ASSET, app.penny.core.model.AccountType.INVESTMENT)) app.penny.core.insights.NetWorth.value(a.state.id, a.value, b.txList, b.today(), b.valuations.map { it.value }, ctx.pack.goldPricePerGram) else b.balance(a.state.id)
                                MoneyLabel(ctx.money(v, a.value.currency), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.transactions), action = stringResource(R.string.see_all), onAction = nav.activity) }
        if (rows.isEmpty()) {
            item {
                EmptyState(
                    stringResource(R.string.empty_period_title), stringResource(R.string.empty_period_body),
                    action = stringResource(R.string.add_transaction), onAction = nav.add,
                )
            }
        } else {
            var lastDate: java.time.LocalDate? = null
            rows.take(60).forEach { s ->
                if (s.value.date != lastDate) {
                    lastDate = s.value.date
                    val d = s.value.date
                    item(key = "h$d") {
                        Text(
                            relativeDate(context, d, today), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
                        )
                    }
                }
                item(key = s.state.id) {
                    SwipeableTx(s.state.id, onDelete = {
                        vm.act(messageText = app.penny.ui.common.deletedMessage(context, ctx, s.value)) { transactions.delete(s.state.id) }
                    }, onCategory = { nav.categorySheet(s.state.id) }) {
                        TxRow(s.state.id, s.value, ctx, onOpen = { nav.openTx(s.state.id) })
                    }
                }
            }
            if (rows.size > 60) item { TextButton(onClick = nav.activity, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.see_all_n, rows.size)) } }
        }
    }
}

@Composable
private fun SafeHero(ctx: Ctx, safe: app.penny.core.budget.SafeToSpend, offset: Int, onShift: (Int) -> Unit) {
    val st = Penny.status
    val ahead = safe.aheadOfPace
    val elapsedBp = if (safe.period.days == 0) 0 else safe.daysElapsed * 10_000 / safe.period.days
    val budget = safe.incomeMinor + safe.expectedIncomeMinor - safe.committedMinor - safe.savingsMinor
    val usedBp = if (budget <= 0) (if (safe.spentMinor > 0) 10_001 else 0) else (safe.spentMinor * 10_000 / budget).toInt()
    val noIncome = safe.incomeMinor + safe.expectedIncomeMinor == 0L
    val over = safe.safeMinor < 0
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (offset == 0) R.string.safe_to_spend else if (offset < 0) R.string.safe_was else R.string.safe_planned),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onShift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.prev_period), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                IconButton(onClick = { onShift(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_period), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            // Several taps back or ahead: one tap returns to now.
            if (offset != 0) androidx.compose.material3.AssistChip(
                onClick = { onShift(-offset) }, label = { Text(stringResource(R.string.this_period)) },
                colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onPrimaryContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f)),
            )
            MoneyLabel(ctx.money(safe.safeMinor), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(2.dp))
            if (offset == 0) {
                Text(
                    stringResource(R.string.per_day, ctx.money(safe.perDayMinor)) + " · " + pluralStringResource(R.plurals.days_left, safe.daysLeft + 1, safe.daysLeft + 1),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                TextButton(onClick = { onShift(0) }) { Text(stringResource(R.string.this_period)) }
            }
            Spacer(Modifier.height(14.dp))
            Meter(usedBp, when { over -> EnvelopeState.OVER; ahead -> EnvelopeState.NEAR; else -> EnvelopeState.OK }, height = 10.dp, paceBp = if (offset == 0) elapsedBp else null)
            Spacer(Modifier.height(12.dp))
            val stats = listOf(
                stringResource(R.string.income) to ctx.money(safe.incomeMinor + safe.expectedIncomeMinor),
                stringResource(R.string.spent) to ctx.money(safe.spentMinor),
                stringResource(R.string.bills_and_goals) to ctx.money(safe.committedMinor + safe.savingsMinor),
            )
            // Three columns fit normal text; from 130% they stack as label and amount rows so nothing is cut or shrunk.
            if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                stats.forEach { (l, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(l, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                        MoneyLabel(v, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                stats.forEach { (l, v) -> HeroStat(l, v, Modifier.weight(1f)) }
            }
            if (safe.unconvertedCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(pluralStringResource(R.plurals.unconverted, safe.unconvertedCount, safe.unconvertedCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (offset == 0) {
                Spacer(Modifier.height(10.dp))
                // Honest states: no income yet, spent more than came in, ahead of pace, or on pace. Never red (AL-08).
                when {
                    noIncome -> Pill(stringResource(R.string.no_income_yet), container = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f), content = MaterialTheme.colorScheme.onPrimaryContainer)
                    over -> Pill(stringResource(R.string.spent_more_than_income), container = st.overContainer, content = MaterialTheme.colorScheme.onSurface)
                    ahead -> Pill(stringResource(R.string.ahead_of_pace), container = st.nearContainer, content = MaterialTheme.colorScheme.onSurface)
                    else -> Pill(stringResource(R.string.on_pace), container = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f), content = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        MoneyLabel(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
