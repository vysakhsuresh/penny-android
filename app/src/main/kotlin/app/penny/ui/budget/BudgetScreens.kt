package app.penny.ui.budget

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.Routes
import app.penny.core.budget.BudgetEngine
import app.penny.core.budget.EnvelopeState
import app.penny.core.budget.EnvelopeStatus
import app.penny.core.model.AlertMode
import app.penny.core.model.CategoryKind
import app.penny.core.model.Envelope
import app.penny.core.model.RolloverMode
import app.penny.core.model.Target
import app.penny.core.model.TargetKind
import app.penny.core.model.TxType
import app.penny.core.period.CycleRule
import app.penny.core.period.Period
import app.penny.domain.ops.BUDGET_TEMPLATES
import app.penny.domain.ops.allTagNames
import app.penny.domain.ops.applyBudgetTemplate
import app.penny.domain.ops.assign
import app.penny.domain.ops.autoAssign
import app.penny.domain.ops.budgetInput
import app.penny.domain.ops.copyLastPeriod
import app.penny.domain.ops.envelopeStatuses
import app.penny.domain.ops.moveMoney
import app.penny.domain.ops.period
import app.penny.domain.ops.saveEnvelope
import app.penny.domain.ops.zeroPeriod
import app.penny.ui.accounts.AmountDialog
import app.penny.ui.common.Ctx
import app.penny.ui.common.TxRow
import app.penny.ui.common.WithBook
import app.penny.ui.common.cycleLabel
import app.penny.ui.common.shortDate
import app.penny.ui.kit.AmountInput
import app.penny.ui.kit.Avatar
import app.penny.ui.kit.Choice
import app.penny.ui.kit.ChipFlow
import app.penny.ui.kit.DateInput
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.ListRow
import app.penny.ui.kit.Meter
import app.penny.ui.kit.MoneyLabel
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.Pill
import app.penny.ui.kit.PrimaryButton
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.Segmented
import app.penny.ui.kit.SelectField
import app.penny.ui.kit.StatBlock
import app.penny.ui.kit.TextInput
import app.penny.ui.kit.parseAmount
import app.penny.ui.theme.Penny

/** Budgets tab (BU-01…BU-10): period navigation, available to assign, envelopes, plan shortcuts and the year view. */
@Composable
fun BudgetsScreen(padding: PaddingValues, go: (String) -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        var offset by rememberSaveable { mutableIntStateOf(0) }
        var yearView by rememberSaveable { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var editAnyway by rememberSaveable { mutableStateOf(false) }
        var assignFor by rememberSaveable { mutableStateOf<String?>(null) }
        var templateDialog by rememberSaveable { mutableStateOf(false) }
        var showArchived by rememberSaveable { mutableStateOf(false) }
        val period = b.period(offset)
        val past = period.endInclusive.isBefore(b.today())
        val readOnly = past && !editAnyway
        val statuses = b.envelopeStatuses(period.start)
        val ata = BudgetEngine.availableToAssign(period, b.budgetInput())

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tab_budgets), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    app.penny.ui.kit.RevealAmountsButton()
                    IconButton(onClick = { yearView = !yearView }) { Icon(if (yearView) Icons.Outlined.PieChart else Icons.Outlined.CalendarMonth, stringResource(if (yearView) R.string.period_view else R.string.year_view)) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.auto_assign)) }, leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) }, onClick = { menu = false; vm.act(R.string.assigned) { autoAssign(it, period) } }, enabled = !readOnly)
                        DropdownMenuItem({ Text(stringResource(R.string.copy_last_period)) }, onClick = { menu = false; vm.act(R.string.assigned) { copyLastPeriod(it, period) } }, enabled = !readOnly)
                        DropdownMenuItem({ Text(stringResource(R.string.start_from_zero)) }, onClick = { menu = false; vm.act(R.string.assigned) { zeroPeriod(it, period) } }, enabled = !readOnly)
                        DropdownMenuItem({ Text(stringResource(R.string.budget_templates)) }, onClick = { menu = false; templateDialog = true })
                        DropdownMenuItem({ Text(stringResource(R.string.change_cycle)) }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, onClick = { menu = false; go(Routes.PROFILE_SETTINGS) })
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { offset-- ; editAnyway = false }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.prev_period)) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(app.penny.ui.common.periodRange(androidx.compose.ui.platform.LocalContext.current, period.start, period.endInclusive), style = MaterialTheme.typography.titleSmall)
                        Text(cycleLabel(androidx.compose.ui.platform.LocalContext.current, b.profile.cycle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { offset++ ; editAnyway = false }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_period)) }
                }
                if (offset != 0) TextButton(onClick = { offset = 0 }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.this_period)) }
                if (past) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(stringResource(if (editAnyway) R.string.editing_past else R.string.past_read_only))
                    if (!editAnyway) TextButton(onClick = { editAnyway = true }) { Text(stringResource(R.string.edit_anyway)) }
                }
            }
            if (yearView) {
                item { YearView(ctx, period) }
            } else {
                // BU-05 / profile "give every unit a job": zero-based budgets show "available to assign" first;
                // otherwise it follows the envelopes (still there when over-assigned, so the problem is visible).
                val ataCard: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
                item {
                    app.penny.ui.kit.HeroCard {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.available_to_assign), style = MaterialTheme.typography.titleSmall)
                                    app.penny.ui.kit.InfoButton(stringResource(R.string.available_to_assign), stringResource(R.string.info_available_to_assign), tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                                }
                                MoneyLabel(ctx.money(ata), style = MaterialTheme.typography.headlineMedium, color = if (ata < 0) Penny.status.over else androidx.compose.ui.graphics.Color.Unspecified)
                                Text(stringResource(if (ata < 0) R.string.over_assigned_hint else R.string.ata_hint), style = MaterialTheme.typography.bodySmall)
                            }
                            if (!readOnly && ata > 0 && statuses.any { it.neededMinor > 0 }) FilledTonalButton(onClick = { vm.act(R.string.assigned) { autoAssign(it, period) } }) { Text(stringResource(R.string.auto_assign)) }
                        }
                    }
                }
                }
                if (b.profile.zeroBased) ataCard()
                if (statuses.isEmpty()) item {
                    EmptyState(stringResource(R.string.no_envelopes), stringResource(R.string.no_envelopes_body), icon = Icons.Outlined.PieChart, action = stringResource(R.string.use_template), onAction = { templateDialog = true },
                        secondary = stringResource(R.string.add_envelope), onSecondary = { go("envelope/new/edit") })
                } else {
                    item { SectionHeader(stringResource(R.string.envelopes), action = stringResource(R.string.add), onAction = { go("envelope/new/edit") }) }
                    items(statuses, key = { it.id }) { s -> EnvelopeCard(ctx, s, readOnly, onOpen = { go("envelope/${s.id}") }, onAssign = { assignFor = s.id }) }
                }
                // Archived envelopes stay reachable, so archiving is never a one-way door.
                val archived = b.envelopes.filter { it.value.archived }
                if (archived.isNotEmpty()) {
                    item { TextButton(onClick = { showArchived = !showArchived }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(if (showArchived) R.string.hide_archived else R.string.show_archived_n, archived.size)) } }
                    if (showArchived) item { PennyCard { archived.forEach { e -> ListRow(e.value.name, subtitle = stringResource(R.string.archived), onClick = { go("envelope/${e.state.id}") }, divider = true) } } }
                }
                if (!b.profile.zeroBased) ataCard()
            }
            item { SectionHeader(stringResource(R.string.plan)) }
            item {
                PennyCard {
                    ListRow(stringResource(R.string.goals), leading = { Avatar("G", "goals", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, icon = Icons.Outlined.Flag) }, subtitle = stringResource(R.string.goals_sub), onClick = { go(Routes.GOALS) }, divider = true)
                    ListRow(stringResource(R.string.bills_subscriptions), leading = { Avatar("B", "bills", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, icon = Icons.Outlined.ReceiptLong) }, subtitle = stringResource(R.string.bills_sub), onClick = { go(Routes.BILLS) }, divider = true)
                    ListRow(stringResource(R.string.debt_loans), leading = { Avatar("D", "debt", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, icon = Icons.Outlined.CreditCard) }, subtitle = stringResource(R.string.debt_sub), onClick = { go(Routes.LOANS) })
                }
            }
        }

        assignFor?.let { id ->
            val s = statuses.firstOrNull { it.id == id }
            if (s != null) AmountDialog(ctx, stringResource(R.string.assign_to, s.envelope.name), stringResource(R.string.assign_body, ctx.money(ata)), s.envelope.currency, ctx.fmt.editable(s.assignedMinor, s.envelope.currency), onDismiss = { assignFor = null }) { v ->
                vm.act(R.string.assigned) { assign(it, id, period, v) }; assignFor = null
            }
        }
        if (templateDialog) TemplateDialog(ctx, onDismiss = { templateDialog = false }) { key, income ->
            vm.act(R.string.envelopes_created) { applyBudgetTemplate(it, key, income) }; templateDialog = false
        }
    }
}

@Composable
private fun EnvelopeCard(ctx: Ctx, s: EnvelopeStatus, readOnly: Boolean, onOpen: () -> Unit, onAssign: () -> Unit) {
    val st = Penny.status
    PennyCard(onClick = onOpen) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.envelope.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.spent_of, ctx.money(s.spentMinor, s.envelope.currency), ctx.money(s.assignedMinor + s.carriedMinor, s.envelope.currency)) +
                            if (s.carriedMinor != 0L) " · " + stringResource(R.string.carried_x, ctx.money(s.carriedMinor, s.envelope.currency)) else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MoneyLabel(
                    if (s.availableMinor >= 0) stringResource(R.string.x_left, ctx.money(s.availableMinor, s.envelope.currency))
                    else stringResource(R.string.x_over, ctx.money(-s.availableMinor, s.envelope.currency)),
                    style = MaterialTheme.typography.titleSmall,
                    color = when (s.state) { EnvelopeState.OVER -> st.over; EnvelopeState.NEAR, EnvelopeState.AT -> st.near; else -> MaterialTheme.colorScheme.onSurface },
                )
                if (!readOnly) IconButton(onClick = onAssign) { Icon(Icons.Outlined.Edit, stringResource(R.string.assign_to, s.envelope.name), tint = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.height(8.dp))
            Meter(s.usedBp, s.state)
            if (s.neededMinor > 0) {
                Text(stringResource(R.string.needs_x, ctx.money(s.neededMinor, s.envelope.currency)), style = MaterialTheme.typography.labelMedium, color = st.near, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** BU-08 year view: 12 columns of assigned vs spent per envelope, scrolling horizontally. */
@Composable
private fun YearView(ctx: Ctx, current: Period) {
    val b = ctx.book
    val input = b.budgetInput()
    val periods = (0 until 12).map { i -> b.period(0, current.start.withDayOfYear(1).plusMonths(i.toLong()).plusDays(14)) }.distinctBy { it.start }
    val scroll = rememberScrollState()
    // Open on the period being viewed, not on January.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val currentIndex = periods.indexOfFirst { it.start == current.start }.coerceAtLeast(0)
    androidx.compose.runtime.LaunchedEffect(currentIndex) { scroll.scrollTo(with(density) { (maxOf(0, currentIndex - 1) * 96).dp.roundToPx() }) }
    PennyCard {
        // Names stay pinned on the left while the periods scroll.
        Row(Modifier.padding(12.dp)) {
            Column(Modifier.width(120.dp)) {
                Text("", style = MaterialTheme.typography.labelSmall, modifier = Modifier.height(24.dp))
                b.envelopes.filter { !it.value.archived }.forEach { e -> Text(e.value.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(40.dp)) }
            }
            Row(Modifier.horizontalScroll(scroll)) {
            periods.forEach { p ->
                Column(Modifier.width(96.dp), horizontalAlignment = Alignment.End) {
                    Text(app.penny.ui.common.monthDay(p.start), style = MaterialTheme.typography.labelSmall, modifier = Modifier.height(24.dp), color = if (p.start == current.start) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    b.envelopes.filter { !it.value.archived }.forEach { e ->
                        val s = BudgetEngine.status(e.state.id, p.start, input, b.today())
                        // Before the envelope existed there is nothing to show (it read as money assigned in January).
                        val started = e.value.startedOn?.let { !p.endInclusive.isBefore(it) } ?: true
                        if (!started) { Text("—", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.height(40.dp)); return@forEach }
                        Column(Modifier.height(40.dp), horizontalAlignment = Alignment.End) {
                            MoneyLabel(ctx.money(s.spentMinor, e.value.currency), style = MaterialTheme.typography.labelMedium, color = if (s.state == EnvelopeState.OVER) Penny.status.over else MaterialTheme.colorScheme.onSurface)
                            MoneyLabel(ctx.money(s.assignedMinor, e.value.currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            }
        }
    }
    Text(stringResource(R.string.year_view_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
}

/** BU-09 regional templates: salaried, gig, student, household, OFW family, small business. */
@Composable
private fun TemplateDialog(ctx: Ctx, onDismiss: () -> Unit, onApply: (String, Long) -> Unit) {
    var key by rememberSaveable { mutableStateOf("salaried") }
    val lastIncome = ctx.book.txList.filter { it.type == TxType.INCOME && it.date.isAfter(ctx.book.today().minusDays(35)) }.sumOf { it.amountMinor }
    var income by rememberSaveable { mutableStateOf(if (lastIncome > 0) ctx.fmt.editable(lastIncome, ctx.base) else "") }
    val labels = mapOf("salaried" to R.string.tpl_salaried, "gig" to R.string.tpl_gig, "student" to R.string.tpl_student, "household" to R.string.tpl_household, "ofw_family" to R.string.tpl_remit_family, "small_business" to R.string.tpl_small_business)
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.budget_templates)) },
        text = {
            Column {
                SelectField(stringResource(R.string.template), key, BUDGET_TEMPLATES.keys.map { Choice(it, stringResource(labels.getValue(it))) }, { it?.let { k -> key = k } })
                AmountInput(income, { income = it }, ctx.base, ctx.fmt, stringResource(R.string.monthly_income))
            }
        },
        confirmButton = { TextButton(onClick = { parseAmount(income, ctx.base, ctx.fmt)?.let { onApply(key, it) } }, enabled = parseAmount(income, ctx.base, ctx.fmt) != null) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Envelope detail: status, spending this period, cover-from (AL-03), moves history (BU-10), archive, delete. */
@Composable
fun EnvelopeDetailScreen(id: String, onBack: () -> Unit, onEdit: (String) -> Unit, onOpenTx: (String) -> Unit, onHistory: (String) -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val env = b.envelopes.firstOrNull { it.state.id == id }
        if (env == null) { PennyScreen(stringResource(R.string.envelopes), onBack) { pad -> EmptyState(stringResource(R.string.not_found), stringResource(R.string.not_found_body), Modifier.padding(pad)) }; return@WithBook }
        var offset by rememberSaveable { mutableIntStateOf(0) }
        var dialog by rememberSaveable { mutableStateOf<String?>(null) }
        val period = b.period(offset)
        val statuses = b.envelopeStatuses(period.start)
        val s = statuses.firstOrNull { it.id == id } ?: BudgetEngine.status(id, period.start, b.budgetInput(), b.today())
        val covered = BudgetEngine.covered(env.value, b.categoryMap.mapValues { it.value.value })
        val txs = b.transactions.filter { t -> t.value.date in s.period && (t.value.categoryId in covered || t.value.splits.any { it.categoryId in covered } || t.value.tags.any { it in env.value.tags }) && t.value.type in listOf(TxType.EXPENSE, TxType.REFUND) }
            .sortedByDescending { it.value.date }
        val moves = b.moves.filter { (it.value.fromId == id || it.value.toId == id) }.sortedByDescending { it.state.createdAt }
        PennyScreen(
            env.value.name, onBack,
            actions = {
                IconButton(onClick = { onHistory(id) }) { Icon(Icons.Outlined.History, stringResource(R.string.history)) }
                IconButton(onClick = { onEdit(id) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) }
            },
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { offset-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.prev_period)) }
                        Text(app.penny.ui.common.periodRange(androidx.compose.ui.platform.LocalContext.current, s.period.start, s.period.endInclusive), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        IconButton(onClick = { offset++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_period)) }
                    }
                }
                item {
                    PennyCard {
                        Column(Modifier.padding(16.dp)) {
                            Row {
                                StatBlock(stringResource(R.string.assigned), ctx.money(s.assignedMinor, env.value.currency), Modifier.weight(1f))
                                StatBlock(stringResource(R.string.carried), ctx.money(s.carriedMinor, env.value.currency), Modifier.weight(1f))
                                StatBlock(stringResource(R.string.spent), ctx.money(s.spentMinor, env.value.currency), Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(12.dp))
                            Meter(s.usedBp, s.state, height = 10.dp)
                            Spacer(Modifier.height(8.dp))
                            MoneyLabel(
                                if (s.availableMinor >= 0) stringResource(R.string.x_left, ctx.money(s.availableMinor, env.value.currency)) else stringResource(R.string.x_over, ctx.money(-s.availableMinor, env.value.currency)),
                                style = MaterialTheme.typography.titleLarge, color = if (s.state == EnvelopeState.OVER) Penny.status.over else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { dialog = "assign" }) { Text(stringResource(R.string.assign)) }
                        OutlinedButton(onClick = { dialog = "move" }) { Text(stringResource(R.string.move_money)) }
                        if (s.state == EnvelopeState.OVER) {
                            val src = BudgetEngine.coverFrom(statuses, id)
                            if (src != null) FilledTonalButton(onClick = {
                                val amt = minOf(src.availableMinor, -s.availableMinor)
                                vm.act(messageText = vm.context.getString(R.string.covered_from, ctx.money(amt, env.value.currency), src.envelope.name)) { moveMoney(it, src.id, id, period, amt, "cover") }
                            }) { Text(stringResource(R.string.cover_from, src.envelope.name)) }
                        }
                    }
                    app.penny.ui.kit.ActionStrip {
                        app.penny.ui.kit.StripAction(destructive = false, onClick = { vm.act(if (env.value.archived) R.string.unarchived else R.string.archived_done) { saveEnvelope(it, id, env.value.copy(archived = !env.value.archived)) } }) { Text(stringResource(if (env.value.archived) R.string.unarchive else R.string.archive)) }
                        app.penny.ui.kit.StripAction(destructive = true, onClick = { vm.act(R.string.deleted, after = onBack) { envelopes.delete(id) } }) { Text(stringResource(R.string.delete)) }
                    }
                }
                if (moves.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.moves)) }
                    items(moves.take(20), key = { it.state.id }) { m ->
                        val incoming = m.value.toId == id
                        val otherId = if (incoming) m.value.fromId else m.value.toId
                        // No id means "available to assign"; an id that is gone is a deleted envelope, named as such.
                        val other = if (otherId == null) stringResource(R.string.available_to_assign) else b.envelopes.firstOrNull { it.state.id == otherId }?.value?.name ?: stringResource(R.string.deleted_item)
                        ListRow(
                            title = stringResource(if (incoming) R.string.from_x else R.string.to_x, other), subtitle = shortDate(m.value.periodStart),
                            trailingText = ctx.money(if (incoming) m.value.amountMinor else -m.value.amountMinor, env.value.currency), divider = true,
                            trailing = { TextButton(onClick = { vm.act(R.string.deleted) { this.moves.delete(m.state.id) } }) { Text(stringResource(R.string.undo)) } },
                        )
                    }
                }
                item { SectionHeader(stringResource(R.string.transactions)) }
                if (txs.isEmpty()) item { EmptyState(stringResource(R.string.no_tx_title), stringResource(R.string.no_tx_envelope_body)) }
                items(txs, key = { it.state.id }) { t -> TxRow(t.state.id, t.value, ctx, onOpen = { onOpenTx(t.state.id) }, showDate = true) }
            }
        }
        when (dialog) {
            "assign" -> AmountDialog(ctx, stringResource(R.string.assign_to, env.value.name), stringResource(R.string.assign_body, ctx.money(BudgetEngine.availableToAssign(period, b.budgetInput()))), env.value.currency, ctx.fmt.editable(s.assignedMinor, env.value.currency), { dialog = null }) { v ->
                vm.act(R.string.assigned) { assign(it, id, period, v) }; dialog = null
            }
            "move" -> MoveDialog(ctx, id, statuses, onDismiss = { dialog = null }) { from, amt ->
                vm.act(R.string.moved) { moveMoney(it, from, id, period, amt) }; dialog = null
            }
        }
    }
}

@Composable
private fun MoveDialog(ctx: Ctx, to: String, statuses: List<EnvelopeStatus>, onDismiss: () -> Unit, onMove: (String?, Long) -> Unit) {
    val env = statuses.firstOrNull { it.id == to }?.envelope ?: return
    var from by rememberSaveable { mutableStateOf<String?>(statuses.filter { it.id != to }.maxByOrNull { it.availableMinor }?.id) }
    var amount by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.move_money)) },
        text = {
            Column {
                SelectField(stringResource(R.string.from), from, statuses.filter { it.id != to }.map { Choice(it.id, it.envelope.name, ctx.money(it.availableMinor, it.envelope.currency)) }, { from = it }, allowNone = true, noneLabel = stringResource(R.string.available_to_assign))
                AmountInput(amount, { amount = it }, env.currency, ctx.fmt, stringResource(R.string.field_amount))
            }
        },
        confirmButton = { TextButton(onClick = { parseAmount(amount, env.currency, ctx.fmt)?.let { onMove(from, it) } }, enabled = parseAmount(amount, env.currency, ctx.fmt) != null) { Text(stringResource(R.string.move_money)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** BU-02/04/06/11/12 envelope form. */
@Composable
fun EnvelopeEditScreen(id: String, onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val existing = b.envelopes.firstOrNull { it.state.id == id }?.value
        var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
        var cats by rememberSaveable { mutableStateOf(existing?.categoryIds ?: emptyList()) }
        var tags by rememberSaveable { mutableStateOf(existing?.tags ?: emptyList()) }
        var currency by rememberSaveable { mutableStateOf(existing?.currency ?: ctx.base) }
        var limit by rememberSaveable { mutableStateOf(existing?.let { ctx.fmt.editable(it.limitMinor, it.currency) }.orEmpty()) }
        var rollover by rememberSaveable { mutableStateOf(existing?.rollover ?: RolloverMode.NONE) }
        var cap by rememberSaveable { mutableStateOf(existing?.rolloverCapMinor?.let { ctx.fmt.editable(it, currency) }.orEmpty()) }
        var period by rememberSaveable { mutableStateOf(existing?.periodOverride?.let { if (it is CycleRule.Yearly) "yearly" else if (it is CycleRule.Quarterly) "quarterly" else "monthly" } ?: "profile") }
        var targetKind by rememberSaveable { mutableStateOf(existing?.target?.kind) }
        var targetAmount by rememberSaveable { mutableStateOf(existing?.target?.amountMinor?.let { ctx.fmt.editable(it, currency) }.orEmpty()) }
        var targetBy by rememberSaveable { mutableStateOf(existing?.target?.by?.toString()) }
        var alert by rememberSaveable { mutableStateOf(existing?.alertMode ?: AlertMode.NEAR_AND_OVER) }
        var festival by rememberSaveable { mutableStateOf(existing?.festival) }
        var smoothing by rememberSaveable { mutableStateOf(existing?.smoothingMinor?.let { ctx.fmt.editable(it, currency) }.orEmpty()) }
        var err by rememberSaveable { mutableStateOf<String?>(null) }
        val req = stringResource(R.string.err_name_required)
        val takenErr = stringResource(R.string.name_taken)
        val amountErr = stringResource(R.string.err_amount_invalid)
        var fieldErr by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
        // Assignments and moves are stored without a currency of their own; changing it would re-read them all.
        val currencyLocked = existing != null && (b.allocations.any { it.value.envelopeId == id } || b.moves.any { it.value.fromId == id || it.value.toId == id })
        PennyScreen(stringResource(if (existing == null) R.string.add_envelope else R.string.edit_envelope), onBack, large = false) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                TextInput(name, { name = it; err = null }, stringResource(R.string.field_name), error = err)
                SectionHeader(stringResource(R.string.covers_categories))
                ChipFlow(b.categoriesOf(CategoryKind.EXPENSE).filter { it.value.parentId == null }.map { Choice(it.state.id, it.value.name) }, cats.toSet(), { c ->
                    cats = if (c in cats) cats - c else cats + c
                    if (name.isBlank()) name = b.categoryName(c)
                })
                app.penny.ui.tx.TagsEditor(tags, b.allTagNames()) { tags = it }
                app.penny.ui.kit.SelectField(stringResource(R.string.field_currency), currency, app.penny.ui.kit.currencyChoices(listOf(ctx.base)), { it?.let { c -> if (!currencyLocked) currency = c } })
                if (currencyLocked) Text(stringResource(R.string.currency_locked_entries), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                AmountInput(limit, { limit = it; fieldErr = fieldErr - "limit" }, currency, ctx.fmt, stringResource(R.string.field_limit), error = fieldErr["limit"], allowZero = true)
                SectionHeader(stringResource(R.string.rollover))
                SelectField(stringResource(R.string.rollover), rollover, listOf(
                    Choice(RolloverMode.NONE, stringResource(R.string.rollover_none)), Choice(RolloverMode.POSITIVE, stringResource(R.string.rollover_positive)),
                    Choice(RolloverMode.BOTH, stringResource(R.string.rollover_both)), Choice(RolloverMode.CAPPED, stringResource(R.string.rollover_capped)),
                ), { it?.let { r -> rollover = r } })
                if (rollover == RolloverMode.CAPPED) AmountInput(cap, { cap = it; fieldErr = fieldErr - "cap" }, currency, ctx.fmt, stringResource(R.string.rollover_cap), error = fieldErr["cap"], allowZero = true)
                SelectField(stringResource(R.string.envelope_period), period, listOf(
                    Choice("profile", stringResource(R.string.same_as_budget)), Choice("monthly", stringResource(R.string.cycle_monthly_short)),
                    Choice("quarterly", stringResource(R.string.cycle_quarterly)), Choice("yearly", stringResource(R.string.cycle_yearly)),
                ), { it?.let { p -> period = p } })
                SectionHeader(stringResource(R.string.target))
                SelectField(stringResource(R.string.target), targetKind, listOf(
                    Choice(TargetKind.FIXED, stringResource(R.string.target_fixed)), Choice(TargetKind.SAVE_BY_DATE, stringResource(R.string.target_by_date)),
                    Choice(TargetKind.REFILL_UP_TO, stringResource(R.string.target_refill)), Choice(TargetKind.SPEND_AT_MOST, stringResource(R.string.target_at_most)),
                ), { targetKind = it }, allowNone = true)
                if (targetKind != null) AmountInput(targetAmount, { targetAmount = it; fieldErr = fieldErr - "target" }, currency, ctx.fmt, stringResource(R.string.target_amount), error = fieldErr["target"])
                if (targetKind == TargetKind.SAVE_BY_DATE) DateInput(stringResource(R.string.by_date), targetBy?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }, { targetBy = it.toString() }, quick = false, today = b.today())
                SectionHeader(stringResource(R.string.alerts))
                Segmented(listOf(Choice(AlertMode.OFF, stringResource(R.string.alerts_off)), Choice(AlertMode.NEAR, stringResource(R.string.alerts_near)), Choice(AlertMode.NEAR_AND_OVER, stringResource(R.string.alerts_near_over))), alert, { alert = it })
                if (ctx.pack.festivalWindows.isNotEmpty()) {
                    SelectField(stringResource(R.string.festival_pot), festival, ctx.pack.festivalWindows.map { Choice(it.name, it.name) }, { festival = it }, allowNone = true)
                    festival?.let { f ->
                        val month = ctx.pack.festivalWindows.first { it.name == f }.month
                        val suggested = BudgetEngine.festivalTarget(month, b.today().year, b.txList, b.fx, currency)
                        if (suggested > 0) TextButton(onClick = { targetKind = TargetKind.SAVE_BY_DATE; targetAmount = ctx.fmt.editable(suggested, currency); targetBy = java.time.LocalDate.of(b.today().year + if (month < b.today().monthValue) 1 else 0, month, 1).toString() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(R.string.use_last_year, ctx.money(suggested, currency)))
                        }
                    }
                }
                AmountInput(smoothing, { smoothing = it; fieldErr = fieldErr - "smooth" }, currency, ctx.fmt, stringResource(R.string.income_smoothing), error = fieldErr["smooth"], allowZero = true)
                Text(stringResource(R.string.income_smoothing_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.save), {
                    if (name.isBlank()) { err = req; return@PrimaryButton }
                    if (b.envelopes.any { it.state.id != id && it.value.name.trim().equals(name.trim(), ignoreCase = true) }) { err = takenErr; return@PrimaryButton }
                    // Nothing typed is silently replaced: a bad limit was saved as 0 and an empty cap meant "no cap".
                    fun opt(s: String) = if (s.isBlank()) 0L else parseAmount(s, currency, ctx.fmt, allowZero = true)
                    val limitMinor = opt(limit)
                    val capMinor = if (rollover == RolloverMode.CAPPED) parseAmount(cap, currency, ctx.fmt, allowZero = true) else 0L
                    val targetMinor = if (targetKind != null) parseAmount(targetAmount, currency, ctx.fmt) else 0L
                    val smoothMinor = opt(smoothing)
                    val errs = buildMap {
                        if (limitMinor == null) put("limit", amountErr)
                        if (capMinor == null) put("cap", amountErr)
                        if (targetMinor == null) put("target", amountErr)
                        if (smoothMinor == null) put("smooth", amountErr)
                    }
                    fieldErr = errs
                    if (errs.isNotEmpty()) return@PrimaryButton
                    val e = (existing ?: Envelope(name, currency = currency)).copy(
                        name = name.trim(), categoryIds = cats, tags = tags, currency = currency, limitMinor = limitMinor!!,
                        rollover = rollover, rolloverCapMinor = capMinor.takeIf { rollover == RolloverMode.CAPPED },
                        periodOverride = when (period) {
                            "monthly" -> CycleRule.Monthly(1)
                            "quarterly" -> CycleRule.Quarterly()
                            "yearly" -> CycleRule.Yearly()
                            else -> null
                        },
                        target = targetKind?.let { k -> Target(k, targetMinor!!, targetBy?.let { d -> runCatching { java.time.LocalDate.parse(d) }.getOrNull() }) },
                        alertMode = alert, festival = festival, smoothingMinor = smoothMinor?.takeIf { it > 0 },
                    )
                    vm.act(R.string.saved, after = onBack) { saveEnvelope(it, if (existing == null) null else id, e) }
                }, Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}
