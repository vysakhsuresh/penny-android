package app.penny.ui.plan

import app.penny.domain.ops.isLiability
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.core.budget.EnvelopeState
import app.penny.core.model.Goal
import app.penny.core.model.GoalKind
import app.penny.core.model.GoalStatus
import app.penny.core.planning.Goals
import app.penny.domain.ops.catId
import app.penny.domain.ops.deleteGoal
import app.penny.domain.ops.goalEntry
import app.penny.domain.ops.goalSaved
import app.penny.domain.ops.saveGoal
import app.penny.domain.ops.setGoalStatus
import app.penny.ui.accounts.AmountDialog
import app.penny.ui.common.Ctx
import app.penny.ui.common.WithBook
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
import app.penny.ui.kit.PrimaryButton
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.SelectField
import app.penny.ui.kit.StatBlock
import app.penny.ui.kit.TextInput
import app.penny.ui.kit.parseAmount
import app.penny.ui.theme.Penny

@Composable
fun GoalsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    WithBook { ctx, _ ->
        val b = ctx.book
        var showDone by rememberSaveable { mutableStateOf(false) }
        PennyScreen(
            stringResource(R.string.goals), onBack,
            floating = { app.penny.ui.kit.AddFab(stringResource(R.string.add_goal), onAdd) },
        ) { pad ->
            val list = b.goals.filter { showDone || it.value.status in listOf(GoalStatus.ACTIVE, GoalStatus.PAUSED) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 120.dp)) {
                item { FilterChip(showDone, { showDone = !showDone }, label = { Text(stringResource(R.string.show_completed_archived)) }, modifier = Modifier.padding(horizontal = 16.dp)) }
                if (list.isEmpty()) item { EmptyState(stringResource(R.string.no_goals), stringResource(R.string.no_goals_body), icon = Icons.Outlined.Flag, action = stringResource(R.string.add_goal), onAction = onAdd) }
                items(list, key = { it.state.id }) { g -> GoalCard(ctx, g.state.id, g.value) { onOpen(g.state.id) } }
            }
        }
    }
}

@Composable
private fun GoalCard(ctx: Ctx, id: String, g: Goal, onClick: () -> Unit) {
    val b = ctx.book
    val entries = b.goalEntries.filter { it.value.goalId == id }.map { it.value }
    val p = Goals.progress(g, b.goalSaved(id), entries, b.today(), b.profile.cycle)
    PennyCard(onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(
                            g.due?.let { stringResource(R.string.by_x, shortDate(it)) },
                            stringResource(R.string.paused).takeIf { g.status == GoalStatus.PAUSED },
                            stringResource(R.string.completed).takeIf { p.completed },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MoneyLabel(stringResource(R.string.x_of_y, ctx.money(p.savedMinor, g.currency), ctx.money(g.targetMinor, g.currency)), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(10.dp))
            Meter(p.progressBp, if (p.onTrack) EnvelopeState.OK else EnvelopeState.NEAR)
            Spacer(Modifier.height(6.dp))
            val per = p.perPeriodMinor
            val projected = p.projectedDate
            Text(
                when {
                    p.completed && p.remainingMinor == 0L -> stringResource(R.string.goal_reached)
                    p.completed -> stringResource(R.string.goal_closed_early, ctx.money(p.savedMinor, g.currency))
                    g.status == GoalStatus.PAUSED -> stringResource(R.string.goal_paused_hint)
                    per != null -> stringResource(if (p.onTrack) R.string.on_track_need else R.string.behind_need, ctx.money(per, g.currency))
                    projected != null -> stringResource(R.string.projected_x, shortDate(projected))
                    else -> stringResource(R.string.start_saving)
                },
                style = MaterialTheme.typography.bodySmall, color = if (p.onTrack) MaterialTheme.colorScheme.onSurfaceVariant else Penny.status.near,
            )
        }
    }
}

/** GO-02 actions: contribute, withdraw, pause, complete, archive, delete; GO-05 history. */
@Composable
fun GoalDetailScreen(id: String, onBack: () -> Unit, onEdit: (String) -> Unit, onHistory: (String) -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val g = b.goals.firstOrNull { it.state.id == id }
        if (g == null) { PennyScreen(stringResource(R.string.goals), onBack) { pad -> EmptyState(stringResource(R.string.not_found), stringResource(R.string.not_found_body), Modifier.padding(pad)) }; return@WithBook }
        var dialog by rememberSaveable { mutableStateOf<String?>(null) }
        val entries = b.goalEntries.filter { it.value.goalId == id }.sortedByDescending { it.value.date }
        PennyScreen(
            g.value.name, onBack,
            actions = {
                IconButton(onClick = { onHistory(id) }) { Icon(Icons.Outlined.History, stringResource(R.string.history)) }
                IconButton(onClick = { onEdit(id) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) }
            },
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item { GoalCard(ctx, id, g.value) {} }
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { dialog = "add" }) { Text(stringResource(R.string.contribute)) }
                        OutlinedButton(onClick = { dialog = "take" }) { Text(stringResource(R.string.withdraw)) }
                    }
                    app.penny.ui.kit.ActionStrip {
                        val s = g.value.status
                        app.penny.ui.kit.StripAction(destructive = false, onClick = { vm.act(R.string.updated) { setGoalStatus(it, id, if (s == GoalStatus.PAUSED) GoalStatus.ACTIVE else GoalStatus.PAUSED) } }) { Text(stringResource(if (s == GoalStatus.PAUSED) R.string.resume else R.string.pause)) }
                        app.penny.ui.kit.StripAction(destructive = false, onClick = { vm.act(R.string.updated) { setGoalStatus(it, id, if (s == GoalStatus.COMPLETED) GoalStatus.ACTIVE else GoalStatus.COMPLETED) } }) { Text(stringResource(if (s == GoalStatus.COMPLETED) R.string.reopen else R.string.mark_complete)) }
                        if (s == GoalStatus.ARCHIVED) app.penny.ui.kit.StripAction(destructive = false, onClick = { vm.act(R.string.unarchived) { setGoalStatus(it, id, GoalStatus.ACTIVE) } }) { Text(stringResource(R.string.unarchive)) }
                        else app.penny.ui.kit.StripAction(destructive = false, onClick = { vm.act(R.string.archived_done) { setGoalStatus(it, id, GoalStatus.ARCHIVED) } }) { Text(stringResource(R.string.archive)) }
                        app.penny.ui.kit.StripAction(destructive = true, onClick = { vm.act(R.string.deleted, after = onBack) { deleteGoal(it, id) } }) { Text(stringResource(R.string.delete)) }
                    }
                }
                item { SectionHeader(stringResource(R.string.history)) }
                if (entries.isEmpty()) item { EmptyState(stringResource(R.string.no_contributions), stringResource(R.string.no_contributions_body)) }
                items(entries, key = { it.state.id }) { e ->
                    ListRow(
                        title = stringResource(if (e.value.amountMinor >= 0) R.string.contribution else R.string.withdrawal),
                        subtitle = shortDate(e.value.date) + (e.value.note.takeIf { it.isNotBlank() && !it.startsWith("auto:") }?.let { " · $it" } ?: if (e.value.note.startsWith("auto:")) " · " + stringResource(R.string.automatic) else ""),
                        trailingText = ctx.money(e.value.amountMinor, g.value.currency, signed = e.value.amountMinor > 0),
                        trailing = { TextButton(onClick = { vm.act(R.string.deleted) { goalEntries.delete(e.state.id) } }) { Text(stringResource(R.string.delete)) } },
                        divider = true,
                    )
                }
            }
        }
        if (dialog != null) {
            val add = dialog == "add"
            AmountDialog(ctx, stringResource(if (add) R.string.contribute else R.string.withdraw), stringResource(if (add) R.string.contribute_body else R.string.withdraw_body), g.value.currency, "", { dialog = null }) { v ->
                vm.act(if (add) R.string.saved else R.string.updated) { goalEntry(it, id, if (add) v else -v) }; dialog = null
            }
        }
    }
}

/** GO-01 + GO-06 emergency fund template. */
@Composable
fun GoalEditScreen(id: String, onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val existing = b.goals.firstOrNull { it.state.id == id }?.value
        var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
        var kind by rememberSaveable { mutableStateOf(existing?.kind ?: GoalKind.STANDARD) }
        var currency by rememberSaveable { mutableStateOf(existing?.currency ?: ctx.base) }
        var target by rememberSaveable { mutableStateOf(existing?.let { ctx.fmt.editable(it.targetMinor, it.currency) }.orEmpty()) }
        var due by rememberSaveable { mutableStateOf(existing?.due?.toString()) }
        var auto by rememberSaveable { mutableStateOf(existing?.autoMinor?.let { ctx.fmt.editable(it, currency) }.orEmpty()) }
        var linked by rememberSaveable { mutableStateOf(existing?.linkedAccountIds ?: emptyList()) }
        var notes by rememberSaveable { mutableStateOf(existing?.notes.orEmpty()) }
        var months by rememberSaveable { mutableStateOf(existing?.months ?: 6) }
        var err by rememberSaveable { mutableStateOf<String?>(null) }
        val req = stringResource(R.string.err_name_required)
        val takenErr = stringResource(R.string.name_taken)
        val targetReq = stringResource(R.string.err_target_required)
        val amountErr = stringResource(R.string.err_amount_invalid)
        var targetErr by rememberSaveable { mutableStateOf<String?>(null) }
        var autoErr by rememberSaveable { mutableStateOf<String?>(null) }
        val essentials = listOf("home", "util", "food", "health", "trans", "fam").map { catId(b.profileId, it) }.flatMap { p -> listOf(p) + b.categories.filter { it.value.parentId == p }.map { it.state.id } }.toSet()
        PennyScreen(stringResource(if (existing == null) R.string.add_goal else R.string.edit_goal), onBack, large = false) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                ChipFlow(listOf(Choice(GoalKind.STANDARD, stringResource(R.string.goal_standard)), Choice(GoalKind.EMERGENCY, stringResource(R.string.goal_emergency))), setOf(kind), { kind = it })
                TextInput(name, { name = it; err = null }, stringResource(R.string.field_name), error = err)
                // Contributions are stored without a currency of their own; changing it would re-read them all.
                val currencyLocked = existing != null && b.goalEntries.any { it.value.goalId == id }
                SelectField(stringResource(R.string.field_currency), currency, app.penny.ui.kit.currencyChoices(listOf(ctx.base)), { it?.let { c -> if (!currencyLocked) currency = c } })
                if (currencyLocked) Text(stringResource(R.string.currency_locked_entries), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                if (kind == GoalKind.EMERGENCY) {
                    SelectField(stringResource(R.string.months_of_essentials), months, listOf(3, 6, 9, 12).map { Choice(it, it.toString()) }, { it?.let { m -> months = m } })
                    // From past essential spending; with no full month yet, from the budget for essentials.
                    val suggested = Goals.emergencyTarget(b.txList, essentials, months, b.today()).takeIf { it > 0 }
                        ?: b.envelopes.filter { e -> !e.value.archived && e.value.categoryIds.any { it in essentials } }.sumOf { it.value.limitMinor } * months
                    val defaultName = stringResource(R.string.goal_emergency)
                    if (suggested > 0) TextButton(onClick = { target = ctx.fmt.editable(suggested, currency); if (name.isBlank()) name = defaultName; targetErr = null }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.use_suggested, ctx.money(suggested, currency)))
                    }
                }
                AmountInput(target, { target = it; targetErr = null }, currency, ctx.fmt, stringResource(R.string.field_target), error = targetErr)
                DateInput(stringResource(R.string.target_date), due?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }, { due = it.toString() }, quick = false, today = b.today())
                if (due != null) TextButton(onClick = { due = null }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.no_date)) }
                AmountInput(auto, { auto = it; autoErr = null }, currency, ctx.fmt, stringResource(R.string.auto_contribution), error = autoErr)
                Text(stringResource(R.string.auto_contribution_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                SectionHeader(stringResource(R.string.linked_accounts))
                // Money saved sits in real asset accounts: not cards or loans, and not wallets that only pass money to a bank.
                // Archived accounts still linked stay visible so they can be unlinked.
                ChipFlow(b.accounts.filter { (!it.value.archived && !it.value.isLiability() && it.value.aliasOf == null) || it.state.id in linked }.map { Choice(it.state.id, it.value.name) }, linked.toSet(), { a -> linked = if (a in linked) linked - a else linked + a })
                TextInput(notes, { notes = it }, stringResource(R.string.field_note), singleLine = false)
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.save), {
                    val t = parseAmount(target, currency, ctx.fmt)
                    val autoMinor = if (auto.isBlank()) 0L else parseAmount(auto, currency, ctx.fmt, allowZero = true)
                    if (name.isBlank()) err = req
                    else if (b.goals.any { it.state.id != id && it.value.name.trim().equals(name.trim(), ignoreCase = true) }) err = takenErr
                    if (t == null) targetErr = targetReq
                    if (autoMinor == null) autoErr = amountErr
                    if (err != null || t == null || autoMinor == null) return@PrimaryButton
                    val g = (existing ?: Goal(name, t, currency)).copy(
                        name = name.trim(), targetMinor = t, currency = currency, due = due?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
                        autoMinor = autoMinor.takeIf { it > 0 }, linkedAccountIds = linked.filter { b.accountMap.containsKey(it) }, notes = notes.trim(), kind = kind,
                        months = months.takeIf { kind == GoalKind.EMERGENCY },
                    )
                    vm.act(R.string.saved, after = onBack) { saveGoal(it, if (existing == null) null else id, g) }
                }, Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}
