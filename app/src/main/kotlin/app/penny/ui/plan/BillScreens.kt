package app.penny.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.core.model.AmountKind
import app.penny.core.model.CategoryKind
import app.penny.core.model.OccurrenceStatus
import app.penny.core.model.Recurring
import app.penny.core.model.RecurringKind
import app.penny.core.model.TxType
import app.penny.core.planning.Bills
import app.penny.core.planning.Upcoming
import app.penny.core.schedule.Freq
import app.penny.core.schedule.Recurrence
import app.penny.domain.ops.editFuture
import app.penny.domain.ops.editOccurrence
import app.penny.domain.ops.payOccurrence
import app.penny.domain.ops.reopenOccurrence
import app.penny.domain.ops.safeToSpend
import app.penny.domain.ops.period
import app.penny.domain.ops.saveRecurring
import app.penny.domain.ops.skipOccurrence
import app.penny.domain.ops.upcoming
import app.penny.ui.common.Ctx
import app.penny.ui.common.WithBook
import app.penny.ui.common.shortDate
import app.penny.ui.common.typeLabel
import app.penny.ui.kit.AmountInput
import app.penny.ui.kit.Avatar
import app.penny.ui.kit.Choice
import app.penny.ui.kit.ChipFlow
import app.penny.ui.kit.DateInput
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.Heatmap
import app.penny.ui.kit.ListRow
import app.penny.ui.kit.MoneyLabel
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.Pill
import app.penny.ui.kit.PrimaryButton
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.Segmented
import app.penny.ui.kit.SelectField
import app.penny.ui.kit.SwitchRow
import app.penny.ui.kit.TextInput
import app.penny.ui.kit.parseAmount
import app.penny.ui.theme.Penny
import java.time.DayOfWeek
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** RC-02/04/06: upcoming list with pay/skip/edit, month calendar with coverage, subscriptions and detected items. */
@Composable
fun BillsScreen(onBack: () -> Unit, onEdit: (String) -> Unit, onOpenTx: (String) -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        var tab by rememberSaveable { mutableStateOf("upcoming") }
        var monthOffset by rememberSaveable { mutableIntStateOf(0) }
        var act by rememberSaveable { mutableStateOf<String?>(null) }
        val today = b.today()
        PennyScreen(
            stringResource(R.string.bills_subscriptions), onBack,
            floating = { ExtendedFloatingActionButton(onClick = { onEdit("new") }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.add_recurring)) }) },
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 120.dp)) {
                item { Segmented(listOf(Choice("upcoming", stringResource(R.string.upcoming)), Choice("calendar", stringResource(R.string.calendar)), Choice("subs", stringResource(R.string.subscriptions)), Choice("all", stringResource(R.string.all))), tab, { tab = it }) }
                when (tab) {
                    "upcoming" -> {
                        val up = b.upcoming(today.minusDays(30), today.plusDays(45))
                        val overdue = up.filter { it.open && it.date.isBefore(today) }
                        val next = up.filter { !it.date.isBefore(today) }
                        // RC-03 detection suggestions.
                        val detected = Bills.detect(b.txList, b.recurring.map { it.value })
                        if (detected.isNotEmpty()) item {
                            PennyCard(color = MaterialTheme.colorScheme.tertiaryContainer) {
                                Column(Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.padding(4.dp)); Text(stringResource(R.string.detected_recurring), style = MaterialTheme.typography.titleSmall) }
                                    detected.take(5).forEach { d ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("${d.payee} · ${ctx.money(d.amountMinor, d.currency)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                            TextButton(onClick = {
                                                vm.act(R.string.recurring_added) { saveRecurring(it, null, Recurring(d.payee, RecurringKind.SUBSCRIPTION, amountMinor = d.amountMinor, currency = d.currency, accountId = d.accountId, categoryId = d.categoryId, payee = d.payee, schedule = d.schedule)) }
                                            }) { Text(stringResource(R.string.add)) }
                                        }
                                    }
                                }
                            }
                        }
                        if (overdue.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.overdue)) }
                            items(overdue, key = { "o" + it.key }) { u -> OccurrenceRow(ctx, u) { act = u.key } }
                        }
                        item { SectionHeader(stringResource(R.string.next_45_days)) }
                        if (next.isEmpty()) item { EmptyState(stringResource(R.string.no_bills), stringResource(R.string.no_bills_body), icon = Icons.Outlined.ReceiptLong, action = stringResource(R.string.add_recurring), onAction = { onEdit("new") }) }
                        items(next, key = { it.key }) { u -> OccurrenceRow(ctx, u) { act = u.key } }
                    }
                    "calendar" -> {
                        val ym = YearMonth.from(today).plusMonths(monthOffset.toLong())
                        val up = b.upcoming(ym.atDay(1), ym.atEndOfMonth())
                        item {
                            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { monthOffset-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous)) }
                                Text(ym.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + ym.year, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { monthOffset++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next)) }
                            }
                        }
                        item {
                            val days = (1..ym.lengthOfMonth()).map { d -> ym.atDay(d).let { date -> date to up.filter { it.date == date && it.recurring.type == TxType.EXPENSE }.sumOf { it.amountMinor } } }
                            Heatmap(days, { ctx.money(it) }, firstDay = b.profile.firstDayOfWeek)
                        }
                        // RC-06: each bill shows whether safe-to-spend covers it.
                        item {
                            val safe = b.safeToSpend(b.period())
                            Text(stringResource(if (safe.safeMinor >= 0) R.string.bills_covered else R.string.bills_short, ctx.money(kotlin.math.abs(safe.safeMinor))), style = MaterialTheme.typography.bodyMedium, color = if (safe.safeMinor >= 0) MaterialTheme.colorScheme.onSurfaceVariant else Penny.status.near, modifier = Modifier.padding(16.dp))
                        }
                        items(up, key = { it.key }) { u -> OccurrenceRow(ctx, u) { act = u.key } }
                    }
                    "subs" -> {
                        val subs = b.recurring.filter { it.value.kind == RecurringKind.SUBSCRIPTION && !it.value.paused }
                        val yearly = subs.sumOf { Bills.yearlyCost(it.value) }
                        item {
                            PennyCard(color = MaterialTheme.colorScheme.primaryContainer) {
                                Column(Modifier.padding(20.dp)) {
                                    Text(stringResource(R.string.subscriptions_yearly), style = MaterialTheme.typography.titleSmall)
                                    MoneyLabel(ctx.money(yearly), style = MaterialTheme.typography.headlineMedium)
                                    Text(pluralStringResource(R.plurals.active_subs, subs.size, subs.size), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (subs.isEmpty()) item { EmptyState(stringResource(R.string.no_subs), stringResource(R.string.no_subs_body)) }
                        items(subs, key = { it.state.id }) { s ->
                            val actuals = b.txList.filter { it.recurringId == s.state.id }.sortedBy { it.date }.map { it.amountMinor }
                            val up = Bills.priceIncrease(actuals)
                            SubscriptionRow(ctx, s.state.id, s.value, up, onEdit)
                        }
                    }
                    else -> {
                        if (b.recurring.isEmpty()) item { EmptyState(stringResource(R.string.no_bills), stringResource(R.string.no_bills_body), action = stringResource(R.string.add_recurring), onAction = { onEdit("new") }) }
                        items(b.recurring, key = { it.state.id }) { r ->
                            ListRow(
                                title = r.value.name,
                                subtitle = listOfNotNull(scheduleText(r.value.schedule), stringResource(R.string.paused).takeIf { r.value.paused }, stringResource(R.string.auto_post).takeIf { r.value.autoPost }).joinToString(" · "),
                                trailingText = ctx.money(r.value.amountMinor, r.value.currency), onClick = { onEdit(r.state.id) }, divider = true,
                            )
                        }
                    }
                }
            }
        }
        act?.let { key ->
            val u = b.upcoming(today.minusDays(400), today.plusDays(400)).firstOrNull { it.key == key }
            if (u == null) act = null else OccurrenceDialog(ctx, u, onDismiss = { act = null }, onOpenTx = onOpenTx, onEditSeries = { onEdit(u.recurringId) }, vm = vm)
        }
    }
}

@Composable
private fun OccurrenceRow(ctx: Ctx, u: Upcoming, onClick: () -> Unit) {
    val today = ctx.book.today()
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, u.date).toInt()
    ListRow(
        title = u.recurring.name,
        subtitle = shortDate(u.date) + " · " + when {
            u.status == OccurrenceStatus.PAID -> stringResource(R.string.paid)
            u.status == OccurrenceStatus.SKIPPED -> stringResource(R.string.skipped)
            days < 0 -> stringResource(R.string.overdue)
            days == 0 -> stringResource(R.string.due_today)
            else -> pluralStringResource(R.plurals.due_in_days, days, days)
        } + if (u.recurring.amountKind != AmountKind.FIXED) " · " + stringResource(R.string.estimated) else "",
        leading = { Avatar(u.recurring.name, u.recurringId, icon = Icons.Outlined.ReceiptLong) },
        trailingText = ctx.money(u.amountMinor, u.recurring.currency), trailingColor = if (u.recurring.type == TxType.INCOME) Penny.status.positive else androidx.compose.ui.graphics.Color.Unspecified,
        onClick = onClick, divider = true,
    )
}

@Composable
private fun SubscriptionRow(ctx: Ctx, id: String, r: Recurring, priceUp: Long?, onEdit: (String) -> Unit) {
    val uri = LocalUriHandler.current
    PennyCard(onClick = { onEdit(id) }) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.per_year_x, ctx.money(Bills.yearlyCost(r), r.currency)) + (r.lastUsed?.let { " · " + stringResource(R.string.last_used_x, shortDate(it)) } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MoneyLabel(ctx.money(r.amountMinor, r.currency), style = MaterialTheme.typography.titleMedium)
            }
            if (priceUp != null) Pill(stringResource(R.string.price_up_x, ctx.money(priceUp, r.currency)), Modifier.padding(top = 6.dp), container = Penny.status.nearContainer, content = MaterialTheme.colorScheme.onSurface)
            // RC-04: cancellation guide link per service (no success fee ever).
            Row {
                if (r.cancelUrl.isNotBlank()) TextButton(onClick = { runCatching { uri.openUri(r.cancelUrl) } }) { Text(stringResource(R.string.how_to_cancel)) }
                val ctxA = LocalContext.current
                TextButton(onClick = {
                    val text = ctxA.getString(R.string.cancel_message_template, r.name)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
                    ctxA.startActivity(android.content.Intent.createChooser(send, null))
                }) { Text(stringResource(R.string.draft_cancellation)) }
            }
        }
    }
}

@Composable
private fun OccurrenceDialog(ctx: Ctx, u: Upcoming, onDismiss: () -> Unit, onOpenTx: (String) -> Unit, onEditSeries: () -> Unit, vm: app.penny.ui.common.BookViewModel) {
    var amount by rememberSaveable { mutableStateOf(ctx.fmt.editable(u.amountMinor, u.recurring.currency)) }
    var date by rememberSaveable { mutableStateOf(u.date.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(u.recurring.name) },
        text = {
            Column {
                if (u.open) {
                    AmountInput(amount, { amount = it }, u.recurring.currency, ctx.fmt, stringResource(R.string.field_amount))
                    DateInput(stringResource(R.string.field_date), runCatching { java.time.LocalDate.parse(date) }.getOrNull(), { date = it.toString() }, today = ctx.book.today())
                } else Text(stringResource(if (u.status == OccurrenceStatus.PAID) R.string.paid else R.string.skipped))
                Row {
                    if (u.open) TextButton(onClick = {
                        val a = parseAmount(amount, u.recurring.currency, ctx.fmt) ?: u.amountMinor
                        vm.act(R.string.updated) { editOccurrence(it, u, a, java.time.LocalDate.parse(date)) }; onDismiss()
                    }) { Text(stringResource(R.string.edit_this_one)) }
                    TextButton(onClick = { onDismiss(); onEditSeries() }) { Text(stringResource(R.string.edit_series)) }
                }
            }
        },
        confirmButton = {
            if (u.open) TextButton(onClick = {
                val a = parseAmount(amount, u.recurring.currency, ctx.fmt) ?: u.amountMinor
                vm.act(R.string.marked_paid) { payOccurrence(it, u, a, java.time.LocalDate.parse(date)) }; onDismiss()
            }) { Text(stringResource(if (u.recurring.type == TxType.INCOME) R.string.mark_received else R.string.mark_paid)) }
            else {
                u.transactionId?.let { tid -> TextButton(onClick = { onDismiss(); onOpenTx(tid) }) { Text(stringResource(R.string.open_transaction)) } }
                TextButton(onClick = { vm.act(R.string.reopened) { reopenOccurrence(it, u) }; onDismiss() }) { Text(stringResource(if (u.status == OccurrenceStatus.SKIPPED) R.string.unskip else R.string.mark_unpaid)) }
            }
        },
        dismissButton = { if (u.open) TextButton(onClick = { vm.act(R.string.skipped) { skipOccurrence(it, u) }; onDismiss() }) { Text(stringResource(R.string.skip)) } },
    )
}

@Composable
fun scheduleText(r: Recurrence): String {
    val lastDay = stringResource(R.string.last_day)
    val unit = when (r.freq) {
        Freq.DAILY -> pluralStringResource(R.plurals.every_n_days, r.interval, r.interval)
        Freq.WEEKLY -> pluralStringResource(R.plurals.every_n_weeks, r.interval, r.interval) + if (r.byWeekday.isNotEmpty()) " · " + r.byWeekday.joinToString { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) } else ""
        Freq.MONTHLY -> pluralStringResource(R.plurals.every_n_months, r.interval, r.interval) + when {
            r.setPos != null && r.byWeekday.isNotEmpty() -> " · " + stringResource(R.string.nth_weekday, if (r.setPos == -1) stringResource(R.string.last) else r.setPos.toString(), r.byWeekday.first().getDisplayName(TextStyle.FULL, Locale.getDefault()))
            r.byMonthDay.isNotEmpty() -> " · " + r.byMonthDay.joinToString { if (it == -1) lastDay else it.toString() }
            else -> ""
        }
        Freq.YEARLY -> pluralStringResource(R.plurals.every_n_years, r.interval, r.interval)
    }
    return unit
}

/** RC-01 every field; RC-02 "this and future" when editing an existing series from a date. */
@Composable
fun RecurringEditScreen(id: String, fromTx: String?, onBack: () -> Unit, onHistory: (String) -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val existing = b.recurring.firstOrNull { it.state.id == id }?.value
        val seed = fromTx?.let { b.txMap[it]?.value }
        val today = b.today()
        val defAccount = seed?.accountId ?: b.profile.defaultAccountId ?: b.activeAccounts.firstOrNull()?.state?.id.orEmpty()
        var name by rememberSaveable { mutableStateOf(existing?.name ?: seed?.payee.orEmpty()) }
        var kind by rememberSaveable { mutableStateOf(existing?.kind ?: RecurringKind.BILL) }
        var type by rememberSaveable { mutableStateOf(existing?.type ?: seed?.type ?: TxType.EXPENSE) }
        var currency by rememberSaveable { mutableStateOf(existing?.currency ?: seed?.currency ?: ctx.base) }
        var amount by rememberSaveable { mutableStateOf((existing?.amountMinor ?: seed?.amountMinor)?.let { ctx.fmt.editable(it, currency) }.orEmpty()) }
        var amountKind by rememberSaveable { mutableStateOf(existing?.amountKind ?: AmountKind.FIXED) }
        var amountMax by rememberSaveable { mutableStateOf(existing?.amountMaxMinor?.let { ctx.fmt.editable(it, currency) }.orEmpty()) }
        var account by rememberSaveable { mutableStateOf(existing?.accountId ?: defAccount) }
        var toAccount by rememberSaveable { mutableStateOf(existing?.toAccountId) }
        var category by rememberSaveable { mutableStateOf(existing?.categoryId ?: seed?.categoryId) }
        var freq by rememberSaveable { mutableStateOf(existing?.schedule?.freq ?: Freq.MONTHLY) }
        var interval by rememberSaveable { mutableStateOf((existing?.schedule?.interval ?: 1).toString()) }
        var start by rememberSaveable { mutableStateOf((existing?.schedule?.start ?: seed?.date?.plusMonths(1) ?: today).toString()) }
        var until by rememberSaveable { mutableStateOf(existing?.schedule?.until?.toString()) }
        var count by rememberSaveable { mutableStateOf(existing?.schedule?.count?.toString().orEmpty()) }
        var lastDay by rememberSaveable { mutableStateOf(existing?.schedule?.byMonthDay?.contains(-1) == true) }
        var nthWeekday by rememberSaveable { mutableStateOf(existing?.schedule?.setPos != null) }
        var autoPost by rememberSaveable { mutableStateOf(existing?.autoPost ?: false) }
        var lead by rememberSaveable { mutableStateOf((existing?.leadDays ?: 2).toString()) }
        var paused by rememberSaveable { mutableStateOf(existing?.paused ?: false) }
        var cancelUrl by rememberSaveable { mutableStateOf(existing?.cancelUrl.orEmpty()) }
        var future by rememberSaveable { mutableStateOf(false) }
        var futureFrom by rememberSaveable { mutableStateOf(today.toString()) }
        var err by rememberSaveable { mutableStateOf<String?>(null) }
        val req = stringResource(R.string.err_name_amount)
        PennyScreen(
            stringResource(if (existing == null) R.string.add_recurring else R.string.edit_recurring), onBack, large = false,
            actions = { if (existing != null) IconButton(onClick = { onHistory(id) }) { Icon(Icons.Outlined.History, stringResource(R.string.history)) } },
        ) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                TextInput(name, { name = it; err = null }, stringResource(R.string.field_name), error = err)
                ChipFlow(RecurringKind.entries.map { Choice(it, stringResource(kindLabel(it))) }, setOf(kind), { k -> kind = k; type = if (k == RecurringKind.INCOME) TxType.INCOME else if (k == RecurringKind.TRANSFER || k == RecurringKind.SAVINGS) TxType.TRANSFER else TxType.EXPENSE })
                Segmented(listOf(TxType.EXPENSE, TxType.INCOME, TxType.TRANSFER).map { Choice(it, stringResource(typeLabel(it))) }, type, { type = it })
                Segmented(listOf(Choice(AmountKind.FIXED, stringResource(R.string.amount_fixed)), Choice(AmountKind.VARIABLE, stringResource(R.string.amount_variable)), Choice(AmountKind.RANGE, stringResource(R.string.amount_range))), amountKind, { amountKind = it })
                AmountInput(amount, { amount = it }, currency, ctx.fmt, stringResource(if (amountKind == AmountKind.VARIABLE) R.string.estimate else if (amountKind == AmountKind.RANGE) R.string.min else R.string.field_amount))
                if (amountKind == AmountKind.RANGE) AmountInput(amountMax, { amountMax = it }, currency, ctx.fmt, stringResource(R.string.max))
                SelectField(stringResource(R.string.field_account), account, b.activeAccounts.map { Choice(it.state.id, it.value.name, it.value.currency) }, { it?.let { a -> account = a; currency = b.accountMap[a]?.value?.currency ?: currency } })
                if (type == TxType.TRANSFER) SelectField(stringResource(R.string.field_to_account), toAccount, b.activeAccounts.filter { it.state.id != account }.map { Choice(it.state.id, it.value.name) }, { toAccount = it })
                else SelectField(stringResource(R.string.field_category), category, b.categoriesOf(if (type == TxType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE).map { Choice(it.state.id, b.categoryName(it.state.id)) }, { category = it }, allowNone = true)
                SectionHeader(stringResource(R.string.schedule))
                Segmented(Freq.entries.map { Choice(it, stringResource(freqLabel(it))) }, freq, { freq = it })
                TextInput(interval, { interval = it.filter(Char::isDigit).take(3) }, stringResource(R.string.every_n), keyboard = KeyboardType.Number)
                DateInput(stringResource(R.string.starts_on), runCatching { java.time.LocalDate.parse(start) }.getOrNull(), { start = it.toString() }, quick = false, today = today)
                if (freq == Freq.MONTHLY) {
                    SwitchRow(stringResource(R.string.on_last_day), lastDay, { lastDay = it; if (it) nthWeekday = false })
                    SwitchRow(stringResource(R.string.on_nth_weekday), nthWeekday, { nthWeekday = it; if (it) lastDay = false })
                }
                DateInput(stringResource(R.string.ends_on), until?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }, { until = it.toString() }, quick = false, today = today)
                if (until != null) TextButton(onClick = { until = null }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.no_end)) }
                TextInput(count, { count = it.filter(Char::isDigit).take(4) }, stringResource(R.string.or_after_n_times), keyboard = KeyboardType.Number)
                SwitchRow(stringResource(R.string.auto_post), autoPost, { autoPost = it }, subtitle = stringResource(R.string.auto_post_recurring_hint))
                TextInput(lead, { lead = it.filter(Char::isDigit).take(2) }, stringResource(R.string.remind_days_before), keyboard = KeyboardType.Number)
                SwitchRow(stringResource(R.string.pause), paused, { paused = it })
                if (kind == RecurringKind.SUBSCRIPTION) TextInput(cancelUrl, { cancelUrl = it }, stringResource(R.string.cancel_link), keyboard = KeyboardType.Uri)
                if (existing != null) {
                    SwitchRow(stringResource(R.string.apply_this_and_future), future, { future = it }, subtitle = stringResource(R.string.apply_this_and_future_hint))
                    if (future) DateInput(stringResource(R.string.from), runCatching { java.time.LocalDate.parse(futureFrom) }.getOrNull(), { futureFrom = it.toString() }, quick = false, today = today)
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.save), {
                    val a = parseAmount(amount, currency, ctx.fmt)
                    if (name.isBlank() || a == null || account.isEmpty()) { err = req; return@PrimaryButton }
                    val s = runCatching { java.time.LocalDate.parse(start) }.getOrDefault(today)
                    val sched = Recurrence(
                        freq, interval.toIntOrNull()?.coerceAtLeast(1) ?: 1, s,
                        until = until?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }, count = count.toIntOrNull()?.takeIf { it > 0 },
                        byWeekday = if (freq == Freq.WEEKLY || nthWeekday) listOf(s.dayOfWeek) else emptyList(),
                        byMonthDay = if (freq == Freq.MONTHLY && !nthWeekday) listOf(if (lastDay) -1 else s.dayOfMonth) else emptyList(),
                        setPos = if (freq == Freq.MONTHLY && nthWeekday) ((s.dayOfMonth - 1) / 7 + 1).let { if (s.plusWeeks(1).month != s.month) -1 else it } else null,
                    )
                    val r = (existing ?: Recurring(name, amountMinor = a, currency = currency, accountId = account, schedule = sched)).copy(
                        name = name.trim(), kind = kind, type = type, amountMinor = a, amountKind = amountKind, amountMaxMinor = parseAmount(amountMax, currency, ctx.fmt),
                        currency = currency, accountId = account, toAccountId = toAccount.takeIf { type == TxType.TRANSFER }, categoryId = category.takeIf { type != TxType.TRANSFER },
                        payee = name.trim(), schedule = sched, autoPost = autoPost, leadDays = lead.toIntOrNull() ?: 2, paused = paused, cancelUrl = cancelUrl.trim(),
                    )
                    if (existing != null && future) vm.act(R.string.saved, after = onBack) { editFuture(it, id, java.time.LocalDate.parse(futureFrom), r) }
                    else vm.act(if (existing == null) R.string.recurring_added else R.string.saved, after = onBack) { book ->
                        val g = saveRecurring(book, if (existing == null) null else id, r)
                        // "Make recurring" from a transaction links that transaction as the first paid occurrence.
                        if (seed != null && fromTx != null) transactions.update(fromTx, seed.copy(recurringId = null), groupId = g)
                        g
                    }
                }, Modifier.padding(horizontal = 16.dp))
                if (existing != null) TextButton(onClick = { vm.act(R.string.deleted, after = onBack) { recurring.delete(id) } }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.delete)) }
            }
        }
        @Suppress("UNUSED_VARIABLE") val unused = DayOfWeek.MONDAY
    }
}

fun kindLabel(k: RecurringKind) = when (k) {
    RecurringKind.BILL -> R.string.kind_bill
    RecurringKind.SUBSCRIPTION -> R.string.kind_subscription
    RecurringKind.INCOME -> R.string.kind_income
    RecurringKind.EMI -> R.string.kind_emi
    RecurringKind.TRANSFER -> R.string.kind_transfer
    RecurringKind.SAVINGS -> R.string.kind_savings
}

fun freqLabel(f: Freq) = when (f) {
    Freq.DAILY -> R.string.freq_daily
    Freq.WEEKLY -> R.string.freq_weekly
    Freq.MONTHLY -> R.string.freq_monthly
    Freq.YEARLY -> R.string.freq_yearly
}

@Suppress("unused")
private val arrangement = Arrangement.spacedBy(8.dp)
