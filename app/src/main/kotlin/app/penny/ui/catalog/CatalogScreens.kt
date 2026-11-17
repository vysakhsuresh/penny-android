package app.penny.ui.catalog

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
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.core.event.PennyJson
import app.penny.core.model.Rule
import app.penny.core.model.RuleActions
import app.penny.core.model.RuleCondition
import app.penny.core.model.RuleField
import app.penny.core.model.RuleOp
import app.penny.core.model.Tag
import app.penny.core.model.TxFilter
import app.penny.core.model.TxType
import app.penny.core.rules.RuleEngine
import app.penny.core.rules.RuleLearner
import app.penny.domain.ops.allTagNames
import app.penny.domain.ops.applyRuleRetro
import app.penny.domain.ops.deletePayee
import app.penny.domain.ops.mergePayee
import app.penny.domain.ops.deleteTag
import app.penny.domain.ops.mergeTag
import app.penny.domain.ops.renamePayee
import app.penny.domain.ops.renameTag
import app.penny.domain.ops.reorderRules
import app.penny.domain.ops.saveRule
import app.penny.domain.ops.saveTag
import app.penny.ui.common.TxRow
import app.penny.ui.common.WithBook
import app.penny.ui.kit.Avatar
import app.penny.ui.kit.Choice
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.ListRow
import app.penny.ui.kit.LineChart
import app.penny.ui.kit.MoneyLabel
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.PrimaryButton
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.SelectField
import app.penny.ui.kit.StatBlock
import app.penny.ui.kit.SwitchRow
import app.penny.ui.kit.TextInput
import app.penny.ui.kit.parseAmount

// ------------------------------------------------------------------ Tags (CAT-07)

@Composable
fun TagsScreen(onBack: () -> Unit, onActivity: (String) -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        var edit by rememberSaveable { mutableStateOf<String?>(null) }
        var menu by remember { mutableStateOf<String?>(null) }
        var mergeFrom by rememberSaveable { mutableStateOf<String?>(null) }
        var showArchived by rememberSaveable { mutableStateOf(false) }
        // Tags only seen on transactions become entities when the screen opens, so every tag can be managed. Only once:
        // a rename writes the tag and its transactions in two steps, and re-running between them re-created the old name.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val missing = b.allTagNames().filter { n -> b.tags.none { it.value.name == n } }
            if (missing.isNotEmpty()) vm.launch { tags.createAll(b.profileId, missing.map { newId() to Tag(it) }) }
        }
        val counts = b.txList.flatMap { it.tags }.groupingBy { it }.eachCount()
        PennyScreen(
            stringResource(R.string.field_tags), onBack,
            actions = { TextButton(onClick = { showArchived = !showArchived }) { Text(stringResource(if (showArchived) R.string.hide_archived else R.string.show_archived)) } },
            floating = { app.penny.ui.kit.AddFab(stringResource(R.string.add_tag), { edit = "new" }) },
        ) { pad ->
            val list = b.tags.filter { showArchived || !it.value.archived }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 100.dp)) {
                if (list.isEmpty()) item { EmptyState(stringResource(R.string.no_tags), stringResource(R.string.no_tags_body), icon = Icons.Outlined.Sell) }
                items(list, key = { it.state.id }) { s ->
                    val n = counts[s.value.name] ?: 0
                    ListRow(
                        title = s.value.name,
                        subtitle = listOfNotNull(pluralStringResource(R.plurals.tx_count, n, n), stringResource(R.string.smart_tag).takeIf { s.value.smart != null }, stringResource(R.string.archived).takeIf { s.value.archived }).joinToString(" · "),
                        leading = { Avatar(s.value.name, s.state.id, icon = Icons.Outlined.Sell, size = 36.dp) },
                        onClick = { onActivity(PennyJson.encodeToString(TxFilter.serializer(), TxFilter(tags = listOf(s.value.name)))) },
                        trailing = {
                            IconButton(onClick = { menu = s.state.id }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.actions_for, s.value.name)) }
                            DropdownMenu(menu == s.state.id, { menu = null }) {
                                DropdownMenuItem({ Text(stringResource(R.string.rename)) }, onClick = { menu = null; edit = s.state.id })
                                DropdownMenuItem({ Text(stringResource(R.string.merge_into)) }, onClick = { menu = null; mergeFrom = s.state.id })
                                DropdownMenuItem({ Text(stringResource(if (s.value.archived) R.string.unarchive else R.string.archive)) }, onClick = { menu = null; vm.act(R.string.updated) { saveTag(it, s.state.id, s.value.copy(archived = !s.value.archived)) } })
                                DropdownMenuItem({ Text(stringResource(R.string.delete)) }, onClick = { menu = null; vm.act(R.string.deleted) { deleteTag(it, s.state.id) } })
                            }
                        },
                        divider = true,
                    )
                }
            }
        }
        edit?.let { id ->
            val cur = b.tags.firstOrNull { it.state.id == id }?.value
            NameDialog(stringResource(if (cur == null) R.string.add_tag else R.string.rename), cur?.name.orEmpty(), onDismiss = { edit = null },
                taken = { n -> b.tags.any { it.state.id != id && it.value.name.trim().equals(n, ignoreCase = true) } }) { name ->
                if (cur == null) vm.act(R.string.saved) { saveTag(it, null, Tag(name)) } else vm.act(R.string.renamed) { renameTag(it, id, name) }
                edit = null
            }
        }
        mergeFrom?.let { from ->
            PickDialog(stringResource(R.string.merge_into), b.tags.filter { it.state.id != from }.map { Choice(it.state.id, it.value.name) }, onDismiss = { mergeFrom = null }) { into ->
                vm.act(R.string.merged) { mergeTag(it, from, into) }; mergeFrom = null
            }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, taken: (String) -> Boolean = { false }, onOk: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    // Two things with the same name cannot be told apart in lists and pickers; say so before saving.
    val clash = name.isNotBlank() && !name.trim().equals(initial.trim(), ignoreCase = true) && taken(name.trim())
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            app.penny.ui.kit.SoftField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true, isError = clash,
                supportingText = { if (clash) Text(stringResource(R.string.name_taken)) })
        },
        confirmButton = { TextButton(onClick = { onOk(name.trim()) }, enabled = name.isNotBlank() && !clash) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun PickDialog(title: String, choices: List<Choice<String>>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var sel by rememberSaveable { mutableStateOf(choices.firstOrNull()?.value) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { SelectField(title, sel, choices, { sel = it }) },
        confirmButton = { TextButton(onClick = { sel?.let(onPick) }, enabled = sel != null) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------------ Payees (PY)

@Composable
fun PayeesScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    WithBook { ctx, _ ->
        val b = ctx.book
        var q by rememberSaveable { mutableStateOf("") }
        val stats = b.txList.filter { it.payeeId != null && it.type == TxType.EXPENSE }.groupBy { it.payeeId }.mapValues { (_, l) -> l.size to l.sumOf { it.amountMinor } }
        PennyScreen(stringResource(R.string.payees), onBack) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item { TextInput(q, { q = it }, stringResource(R.string.search)) }
                val list = b.payees.filter { q.isBlank() || it.value.name.contains(q, true) || it.value.aliases.any { a -> a.contains(q, true) } }
                if (list.isEmpty()) item { EmptyState(stringResource(R.string.no_payees), stringResource(R.string.no_payees_body), icon = Icons.Outlined.Storefront) }
                items(list, key = { it.state.id }) { p ->
                    val (n, total) = stats[p.state.id] ?: (0 to 0L)
                    ListRow(
                        title = p.value.name, subtitle = pluralStringResource(R.plurals.tx_count, n, n),
                        leading = { Avatar(p.value.name, p.state.id) }, trailingText = if (total > 0) ctx.money(total) else null,
                        onClick = { onOpen(p.state.id) }, divider = true,
                    )
                }
            }
        }
    }
}

/** PY-04 payee page: total, count, average, trend, all transactions; rename, merge, default category, delete. */
@Composable
fun PayeeDetailScreen(id: String, onBack: () -> Unit, onOpenTx: (String) -> Unit, onHistory: (String) -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val p = b.payees.firstOrNull { it.state.id == id }
        if (p == null) { PennyScreen(stringResource(R.string.payees), onBack) { pad -> EmptyState(stringResource(R.string.not_found), stringResource(R.string.not_found_body), Modifier.padding(pad)) }; return@WithBook }
        var dialog by rememberSaveable { mutableStateOf<String?>(null) }
        val names = (p.value.aliases + p.value.name).map(RuleEngine::normalise).toSet()
        val txs = b.transactions.filter { it.value.payeeId == id || RuleEngine.normalise(it.value.payee.orEmpty()) in names }.sortedByDescending { it.value.date }
        val spend = txs.filter { it.value.type == TxType.EXPENSE }
        val total = spend.sumOf { it.value.amountMinor }
        PennyScreen(p.value.name, onBack, actions = { IconButton(onClick = { onHistory(id) }) { Icon(Icons.Outlined.History, stringResource(R.string.history)) } }) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item {
                    PennyCard {
                        Row(Modifier.padding(16.dp)) {
                            StatBlock(stringResource(R.string.total_spent), ctx.money(total), Modifier.weight(1f))
                            StatBlock(stringResource(R.string.times), spend.size.toString(), Modifier.weight(1f))
                            StatBlock(stringResource(R.string.average), ctx.money(if (spend.isEmpty()) 0 else total / spend.size), Modifier.weight(1f))
                        }
                    }
                }
                item {
                    val today = b.today()
                    val months = (5 downTo 0).map { m -> today.minusMonths(m.toLong()).withDayOfMonth(1) }
                    val pts = months.map { start -> app.penny.ui.common.monthDay(start) to spend.filter { !it.value.date.isBefore(start) && it.value.date.isBefore(start.plusMonths(1)) }.sumOf { it.value.amountMinor } }
                    if (spend.isNotEmpty()) { SectionHeader(stringResource(R.string.trend)); LineChart(pts, { ctx.money(it) }) }
                }
                item {
                    SelectField(stringResource(R.string.default_category), p.value.defaultCategoryId, b.categories.filter { !it.value.archived }.map { Choice(it.state.id, b.categoryName(it.state.id)) }, { c ->
                        vm.act(R.string.updated) { payees.update(id, p.value.copy(defaultCategoryId = c)) }
                    }, allowNone = true)
                    if (p.value.aliases.isNotEmpty()) Text(stringResource(R.string.aliases, p.value.aliases.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                    app.penny.ui.kit.ActionStrip {
                        app.penny.ui.kit.StripAction(destructive = false, onClick = { dialog = "rename" }) { Text(stringResource(R.string.rename)) }
                        app.penny.ui.kit.StripAction(destructive = false, onClick = { dialog = "merge" }) { Text(stringResource(R.string.merge_into)) }
                        app.penny.ui.kit.StripAction(destructive = true, onClick = { dialog = "delete" }) { Text(stringResource(R.string.delete)) }
                    }
                }
                item { SectionHeader(stringResource(R.string.transactions)) }
                items(txs.take(300), key = { it.state.id }) { s -> TxRow(s.state.id, s.value, ctx, onOpen = { onOpenTx(s.state.id) }, showDate = true) }
            }
        }
        when (dialog) {
            "rename" -> NameDialog(stringResource(R.string.rename), p.value.name, { dialog = null },
                taken = { n -> b.payees.any { it.state.id != id && it.value.name.trim().equals(n, ignoreCase = true) } }) { n -> vm.act(R.string.renamed) { renamePayee(it, id, n) }; dialog = null }
            "merge" -> PickDialog(stringResource(R.string.merge_into), b.payees.filter { it.state.id != id }.map { Choice(it.state.id, it.value.name) }, { dialog = null }) { into ->
                vm.act(R.string.merged, after = onBack) { mergePayee(it, id, into) }; dialog = null
            }
            "delete" -> AlertDialog(
                onDismissRequest = { dialog = null }, title = { Text(stringResource(R.string.delete)) },
                text = { Text(stringResource(R.string.delete_payee_body)) },
                confirmButton = { app.penny.ui.kit.DangerTextButton(stringResource(R.string.delete)) { vm.act(R.string.deleted, after = onBack) { deletePayee(it, id, null) }; dialog = null } },
                dismissButton = { TextButton(onClick = { dialog = "merge" }) { Text(stringResource(R.string.merge_into)) } },
            )
        }
    }
}

// ------------------------------------------------------------------ Rules (RU)

@Composable
fun RulesScreen(onBack: () -> Unit, onEdit: (String) -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val visible = b.rules.filter { RuleLearner.isVisible(it.value) }
        val conflicts = RuleEngine.conflicts(visible.map { it.state.id to it.value }, b.txList.takeLast(500))
        PennyScreen(
            stringResource(R.string.rules), onBack,
            floating = { app.penny.ui.kit.AddFab(stringResource(R.string.add_rule), { onEdit("new") }) },
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 100.dp)) {
                item { Text(stringResource(R.string.rules_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                if (conflicts.isNotEmpty()) item {
                    // RU-05 conflicts surfaced.
                    PennyCard(color = app.penny.ui.theme.Penny.status.nearContainer) {
                        Column(Modifier.padding(16.dp)) {
                            Text(pluralStringResource(R.plurals.rule_conflicts, conflicts.size, conflicts.size), style = MaterialTheme.typography.titleSmall)
                            conflicts.take(5).forEach { (a, c) ->
                                Text("${b.rules.first { it.state.id == a }.value.name} ↔ ${b.rules.first { it.state.id == c }.value.name}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (visible.isEmpty()) item { EmptyState(stringResource(R.string.no_rules), stringResource(R.string.no_rules_body), icon = Icons.Outlined.Rule) }
                items(visible, key = { it.state.id }) { r ->
                    ListRow(
                        title = r.value.name,
                        subtitle = listOfNotNull(
                            r.value.conditions.joinToString(", ") { "${it.field.name.lowercase()} ${it.op.name.lowercase().replace('_', ' ')} ${it.value}" },
                            stringResource(R.string.learned_draft).takeIf { RuleLearner.isDraft(r.value) },
                            stringResource(R.string.learned).takeIf { r.value.learned && r.value.enabled },
                        ).joinToString(" · "),
                        onClick = { onEdit(r.state.id) },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { val ids = visible.map { it.state.id }.toMutableList(); val i = ids.indexOf(r.state.id); if (i > 0) { ids[i] = ids[i - 1].also { ids[i - 1] = ids[i] }; vm.act(R.string.reordered) { reorderRules(it, ids) } } }) {
                                    Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.move_up))
                                }
                                Switch(r.value.enabled, { on -> vm.act(if (on) R.string.rule_on else R.string.rule_off) { saveRule(it, r.state.id, r.value.copy(enabled = on)) } })
                            }
                        },
                        divider = true,
                    )
                }
            }
        }
    }
}

/** RU-01/02 + CAT-08: conditions, actions, order, continue; live preview count and retroactive apply. */
@Composable
fun RuleEditScreen(id: String, onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val existing = b.rules.firstOrNull { it.state.id == id }?.value
        var json by rememberSaveable { mutableStateOf(PennyJson.encodeToString(Rule.serializer(), existing ?: Rule("", listOf(RuleCondition(RuleField.PAYEE, RuleOp.CONTAINS, "")), actions = RuleActions()))) }
        val dirty = app.penny.ui.kit.rememberDirty(json)
        val rule = runCatching { PennyJson.decodeFromString(Rule.serializer(), json) }.getOrNull() ?: return@WithBook
        fun set(r: Rule) { json = PennyJson.encodeToString(Rule.serializer(), r) }
        var retro by rememberSaveable { mutableStateOf(false) }
        val preview = RuleEngine.preview(rule, b.transactions.map { it.state.id to it.value })
        PennyScreen(stringResource(if (existing == null) R.string.add_rule else R.string.edit_rule), onBack, large = false, dirty = dirty) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                TextInput(rule.name, { set(rule.copy(name = it)) }, stringResource(R.string.field_name))
                SectionHeader(stringResource(R.string.when_label))
                app.penny.ui.kit.Segmented(listOf(Choice(true, stringResource(R.string.match_all)), Choice(false, stringResource(R.string.match_any))), rule.matchAll, { set(rule.copy(matchAll = it)) })
                rule.conditions.forEachIndexed { i, c ->
                    PennyCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                SelectField(stringResource(R.string.field_label), c.field, RuleField.entries.map { Choice(it, it.name.lowercase().replaceFirstChar(Char::uppercase)) }, { f -> f?.let { set(rule.copy(conditions = rule.conditions.mapIndexed { j, x -> if (j == i) x.copy(field = it) else x })) } })
                                SelectField(stringResource(R.string.operator), c.op, RuleOp.entries.map { Choice(it, it.name.lowercase().replace('_', ' ')) }, { o -> o?.let { set(rule.copy(conditions = rule.conditions.mapIndexed { j, x -> if (j == i) x.copy(op = it) else x })) } })
                                val isAmount = c.field == RuleField.AMOUNT
                                // Amounts keep what is being typed; the stored value is the parsed minor units. Re-showing
                                // the parsed value after each key turned "50" into "5.000" mid-typing.
                                var raw1 by rememberSaveable(i, c.field) { mutableStateOf(if (isAmount) c.value.toLongOrNull()?.let { ctx.fmt.editable(it, ctx.base) } ?: c.value else c.value) }
                                var raw2 by rememberSaveable(i, c.field) { mutableStateOf(c.value2.toLongOrNull()?.let { ctx.fmt.editable(it, ctx.base) } ?: c.value2) }
                                TextInput(
                                    if (isAmount) raw1 else c.value,
                                    { v -> raw1 = v; set(rule.copy(conditions = rule.conditions.mapIndexed { j, x -> if (j == i) x.copy(value = if (isAmount) (parseAmount(v, ctx.base, ctx.fmt)?.toString() ?: "?$v") else v) else x })) },
                                    stringResource(R.string.value),
                                    error = if (isAmount && raw1.isNotBlank() && parseAmount(raw1, ctx.base, ctx.fmt) == null) stringResource(R.string.err_amount_invalid) else null,
                                )
                                if (c.op == RuleOp.BETWEEN) TextInput(raw2, { v -> raw2 = v; set(rule.copy(conditions = rule.conditions.mapIndexed { j, x -> if (j == i) x.copy(value2 = if (isAmount) (parseAmount(v, ctx.base, ctx.fmt)?.toString() ?: "?$v") else v) else x })) }, stringResource(R.string.and_value),
                                    error = if (isAmount && raw2.isNotBlank() && parseAmount(raw2, ctx.base, ctx.fmt) == null) stringResource(R.string.err_amount_invalid) else null)
                            }
                            if (rule.conditions.size > 1) IconButton(onClick = { set(rule.copy(conditions = rule.conditions.filterIndexed { j, _ -> j != i })) }) { Icon(Icons.Outlined.Close, stringResource(R.string.remove)) }
                        }
                    }
                }
                TextButton(onClick = { set(rule.copy(conditions = rule.conditions + RuleCondition(RuleField.PAYEE, RuleOp.CONTAINS, ""))) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.add_condition)) }
                SectionHeader(stringResource(R.string.then_label))
                val a = rule.actions
                fun setA(x: RuleActions) = set(rule.copy(actions = x))
                SelectField(stringResource(R.string.field_category), a.categoryId, b.categories.filter { !it.value.archived }.map { Choice(it.state.id, b.categoryName(it.state.id)) }, { setA(a.copy(categoryId = it)) }, allowNone = true, noneLabel = stringResource(R.string.no_change))
                TextInput(a.renamePayee.orEmpty(), { setA(a.copy(renamePayee = it.ifBlank { null })) }, stringResource(R.string.rename_payee_to))
                SelectField(stringResource(R.string.field_account), a.accountId, b.activeAccounts.map { Choice(it.state.id, it.value.name) }, { setA(a.copy(accountId = it)) }, allowNone = true, noneLabel = stringResource(R.string.no_change))
                SelectField(stringResource(R.string.mark_transfer_to), a.transferToAccountId, b.activeAccounts.map { Choice(it.state.id, it.value.name) }, { setA(a.copy(transferToAccountId = it)) }, allowNone = true, noneLabel = stringResource(R.string.no_change))
                app.penny.ui.tx.TagsEditor(a.addTags, b.allTagNames()) { setA(a.copy(addTags = it)) }
                SwitchRow(stringResource(R.string.field_exclude_budget), a.excludeFromBudget == true, { setA(a.copy(excludeFromBudget = if (it) true else null)) })
                SwitchRow(stringResource(R.string.field_exclude_reports), a.excludeFromReports == true, { setA(a.copy(excludeFromReports = if (it) true else null)) })
                SwitchRow(stringResource(R.string.field_private), a.isPrivate == true, { setA(a.copy(isPrivate = if (it) true else null)) })
                SwitchRow(stringResource(R.string.auto_post), a.autoPost == true, { setA(a.copy(autoPost = if (it) true else null)) }, subtitle = stringResource(R.string.auto_post_hint))
                SwitchRow(stringResource(R.string.ignore_matches), a.ignore, { setA(a.copy(ignore = it)) })
                SwitchRow(stringResource(R.string.continue_after), rule.continueAfter, { set(rule.copy(continueAfter = it)) }, subtitle = stringResource(R.string.continue_after_hint))
                SwitchRow(stringResource(R.string.enabled), rule.enabled, { set(rule.copy(enabled = it)) })
                // CAT-08: before saving, show how many past transactions it would change.
                PennyCard(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.padding(16.dp)) {
                        Text(pluralStringResource(R.plurals.rule_preview, preview.size, preview.size), style = MaterialTheme.typography.titleSmall)
                        preview.take(3).forEach { (pid, t) -> TxRow(pid, t, ctx, onOpen = {}) }
                        if (preview.isNotEmpty()) SwitchRow(stringResource(R.string.apply_retro), retro, { retro = it })
                    }
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.save), {
                    val r = rule.copy(name = rule.name.ifBlank { rule.conditions.firstOrNull()?.value.orEmpty() }, learned = existing?.learned == true && rule == existing, conditions = rule.conditions.filter { it.value.isNotBlank() })
                    if (r.conditions.isEmpty()) return@PrimaryButton
                    // An amount condition that is not a number would save a rule that can never match.
                    if (r.conditions.any { it.field == RuleField.AMOUNT && (it.value.startsWith("?") || it.value2.startsWith("?")) }) return@PrimaryButton
                    vm.act(R.string.saved, after = onBack) { book ->
                        val g = saveRule(book, if (existing == null) null else id, r.copy(evidence = maxOf(r.evidence, if (r.learned) RuleLearner.PROMOTE_AT else 0)))
                        if (retro) applyRuleRetro(book, r)
                        g
                    }
                }, Modifier.padding(horizontal = 16.dp))
                if (existing != null) TextButton(onClick = { vm.act(R.string.deleted, after = onBack) { rules.delete(id) } }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.delete_rule)) }
            }
        }
    }
}

// ------------------------------------------------------------------ Templates (TX-16)

@Composable
fun TemplatesScreen(onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        var rename by rememberSaveable { mutableStateOf<String?>(null) }
        PennyScreen(stringResource(R.string.templates), onBack) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item { Text(stringResource(R.string.templates_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                if (b.templates.isEmpty()) item { EmptyState(stringResource(R.string.no_templates), stringResource(R.string.no_templates_body), icon = Icons.Outlined.Star) }
                items(b.templates, key = { it.state.id }) { t ->
                    ListRow(
                        title = t.value.name, subtitle = listOfNotNull(b.categoryName(t.value.categoryId).ifEmpty { null }, b.accountName(t.value.accountId).ifEmpty { null }).joinToString(" · "),
                        trailingText = t.value.amountMinor?.let { ctx.money(it, t.value.currency) },
                        trailing = {
                            Row {
                                FilterChip(t.value.favourite, { vm.act(R.string.updated) { templates.update(t.state.id, t.value.copy(favourite = !t.value.favourite)) } }, label = { Text(stringResource(R.string.favourite)) })
                                IconButton(onClick = { vm.act(R.string.deleted) { templates.delete(t.state.id) } }) { Icon(Icons.Outlined.Close, stringResource(R.string.delete)) }
                            }
                        },
                        onClick = { rename = t.state.id }, divider = true,
                    )
                }
            }
        }
        rename?.let { id ->
            // The template can vanish while the dialog is open (sync, undo): close instead of crashing.
            val t = b.templates.firstOrNull { it.state.id == id }?.value
            if (t == null) rename = null
            else NameDialog(stringResource(R.string.rename), t.name, { rename = null },
                taken = { n -> b.templates.any { it.state.id != id && it.value.name.trim().equals(n, ignoreCase = true) } }) { n -> vm.act(R.string.renamed) { templates.update(id, t.copy(name = n)) }; rename = null }
        }
    }
}
