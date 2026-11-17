package app.penny.ui.accounts

import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Archive
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
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.core.insights.NetWorth
import app.penny.core.model.AccountType
import app.penny.core.model.CardFields
import app.penny.core.model.FinancialAccount
import app.penny.core.planning.Debt
import app.penny.domain.ops.balance
import app.penny.domain.ops.countCash
import app.penny.domain.ops.deleteAccount
import app.penny.domain.ops.isLiability
import app.penny.domain.ops.reconcile
import app.penny.domain.ops.reorderAccounts
import app.penny.domain.ops.saveAccount
import app.penny.domain.ops.setBalance
import app.penny.ui.common.Ctx
import app.penny.ui.common.TxRow
import app.penny.ui.common.WithBook
import app.penny.ui.common.accountIcon
import app.penny.ui.common.accountTypeLabel
import app.penny.ui.common.shortDate
import app.penny.ui.kit.AmountInput
import app.penny.ui.kit.Avatar
import app.penny.ui.kit.Choice
import app.penny.ui.kit.DateInput
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.LineChart
import app.penny.ui.kit.ListRow
import app.penny.ui.kit.MoneyLabel
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.PrimaryButton
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.SelectField
import app.penny.ui.kit.StatBlock
import app.penny.ui.kit.SwitchRow
import app.penny.ui.kit.TextInput
import app.penny.ui.kit.TypedConfirmDialog
import app.penny.ui.kit.currencyChoices
import app.penny.ui.kit.parseAmount
import app.penny.ui.kit.parseDayOfMonth
import app.penny.ui.kit.parseNonNegative
import app.penny.ui.kit.parsePercentBp
import app.penny.ui.theme.Penny
import java.time.temporal.ChronoUnit

@Composable
fun AccountsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        var showArchived by rememberSaveable { mutableStateOf(false) }
        var reorder by rememberSaveable { mutableStateOf(false) }
        val (net, missing) = NetWorth.total(b.accounts.map { it.state.id to it.value }, b.txList, b.today(), ctx.base, b.fx, b.valuations.map { it.value }, ctx.pack.goldPricePerGram)
        PennyScreen(
            stringResource(R.string.accounts), onBack,
            actions = { TextButton(onClick = { reorder = !reorder }) { Text(stringResource(if (reorder) R.string.done else R.string.reorder)) } },
            floating = { app.penny.ui.kit.AddFab(stringResource(R.string.add_account), onAdd) },
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 100.dp)) {
                item {
                    app.penny.ui.kit.HeroCard {
                        Column(Modifier.padding(20.dp)) {
                            Text(stringResource(R.string.net_worth), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            MoneyLabel(ctx.money(net), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            if (missing > 0) Text(pluralStringResource(R.plurals.no_rate_accounts, missing, missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                // Only when there is something archived to show.
                if (b.accounts.any { it.value.archived }) item { FilterChip(showArchived, { showArchived = !showArchived }, label = { Text(stringResource(R.string.show_archived)) }, modifier = Modifier.padding(horizontal = 16.dp)) }
                val list = b.accounts.filter { showArchived || !it.value.archived }
                if (list.isEmpty()) item { EmptyState(stringResource(R.string.no_accounts), stringResource(R.string.no_accounts_body), icon = Icons.Outlined.AccountBalance) }
                list.groupBy { it.value.group ?: "" }.toSortedMap().forEach { (group, inGroup) ->
                    inGroup.groupBy { if (group.isEmpty()) it.value.type else null }.forEach { (type, accts) ->
                        item(key = "g:$group:$type") { SectionHeader(group.ifEmpty { type?.let { stringResource(accountTypeLabel(it)) }.orEmpty() }) }
                        item(key = "c:$group:$type") {
                            PennyCard {
                                accts.forEachIndexed { idx, a ->
                                    val bal = if (a.value.type in listOf(AccountType.ASSET, AccountType.INVESTMENT)) NetWorth.value(a.state.id, a.value, b.txList, b.today(), b.valuations.map { it.value }, ctx.pack.goldPricePerGram) else b.balance(a.state.id)
                                    ListRow(
                                        title = a.value.name,
                                        subtitle = listOfNotNull(
                                            a.value.institution.takeIf { it.isNotBlank() },
                                            stringResource(R.string.archived).takeIf { a.value.archived },
                                            stringResource(R.string.excluded_net_worth).takeIf { !a.value.includeInNetWorth },
                                            stringResource(R.string.manual).takeIf { a.value.syncLinkId == null && a.value.type == AccountType.BANK },
                                        ).joinToString(" · "),
                                        leading = { Avatar(a.value.name, a.state.id, icon = accountIcon(a.value.type)) },
                                        trailingText = if (reorder) null else ctx.money(bal, a.value.currency),
                                        trailing = if (reorder) ({
                                            Row {
                                                // Accounts are shown in sections, so a move swaps with the neighbour in the same section.
                                                IconButton(onClick = { move(vm, ctx, a.state.id, accts[idx - 1].state.id) }, enabled = idx > 0) { Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.move_up)) }
                                                IconButton(onClick = { move(vm, ctx, a.state.id, accts[idx + 1].state.id) }, enabled = idx < accts.lastIndex) { Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.move_down)) }
                                            }
                                        }) else null,
                                        onClick = { onOpen(a.state.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun move(vm: app.penny.ui.common.BookViewModel, ctx: Ctx, id: String, with: String) {
    val ids = ctx.book.accounts.map { it.state.id }.toMutableList()
    val i = ids.indexOf(id)
    val j = ids.indexOf(with)
    if (i < 0 || j < 0) return
    ids[i] = ids[j].also { ids[j] = ids[i] }
    vm.act(R.string.reordered) { reorderAccounts(it, ids) }
}

@Composable
fun AccountDetailScreen(id: String, onBack: () -> Unit, onEdit: (String) -> Unit, onOpenTx: (String) -> Unit, onHistory: (String) -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val acct = b.accountMap[id]
        if (acct == null) {
            PennyScreen(stringResource(R.string.accounts), onBack) { pad -> EmptyState(stringResource(R.string.not_found), stringResource(R.string.not_found_body), Modifier.padding(pad), action = stringResource(R.string.back), onAction = onBack) }
            return@WithBook
        }
        val a = acct.value
        var dialog by rememberSaveable { mutableStateOf<String?>(null) }
        // Assets show their latest valuation plus later movements; other accounts show the ledger balance.
        val bal = if (a.type in listOf(AccountType.ASSET, AccountType.INVESTMENT)) NetWorth.value(id, a, b.txList, b.today(), b.valuations.map { it.value }, ctx.pack.goldPricePerGram) else b.balance(id)
        val txs = b.transactions.filter { it.value.accountId == id || it.value.toAccountId == id }.sortedByDescending { it.value.date }
        PennyScreen(
            a.name, onBack,
            actions = {
                IconButton(onClick = { onHistory(id) }) { Icon(Icons.Outlined.History, stringResource(R.string.history)) }
                IconButton(onClick = { onEdit(id) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) }
            },
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item {
                    app.penny.ui.kit.HeroCard {
                        Column(Modifier.padding(20.dp)) {
                            Text(stringResource(accountTypeLabel(a.type)) + " · " + a.currency, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            MoneyLabel(ctx.money(bal, a.currency), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            a.card?.let { c -> CardSummary(ctx, id, a, c, bal) }
                        }
                    }
                }
                // AC-03 balance history from the event log (end of each of the last 6 months).
                item {
                    val today = b.today()
                    val pts = (5 downTo 0).map { m ->
                        val d = if (m == 0) today else today.minusMonths(m.toLong()).withDayOfMonth(1).plusMonths(1).minusDays(1)
                        shortDate(d) to NetWorth.value(id, a, b.txList, d, b.valuations.map { it.value }, ctx.pack.goldPricePerGram)
                    }
                    if (txs.isNotEmpty()) {
                        SectionHeader(stringResource(R.string.balance_history))
                        PennyCard { androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp)); LineChart(pts, { ctx.money(it, a.currency) }); androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp)) }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OutlinedButton(onClick = { dialog = "set" }, modifier = Modifier.weight(1f).padding(end = 4.dp)) { Text(stringResource(R.string.set_balance)) }
                        if (a.type == AccountType.CASH) OutlinedButton(onClick = { dialog = "count" }, modifier = Modifier.weight(1f).padding(start = 4.dp)) { Text(stringResource(R.string.count_cash)) }
                        else OutlinedButton(onClick = { dialog = "reconcile" }, modifier = Modifier.weight(1f).padding(start = 4.dp)) { Text(stringResource(R.string.reconcile)) }
                    }
                    // IN-05: assets and investments keep a history of manual valuations.
                    if (a.type in listOf(AccountType.ASSET, AccountType.INVESTMENT, AccountType.RECEIVABLE)) {
                        OutlinedButton(onClick = { dialog = "value" }, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.record_value)) }
                    }
                }
                item { SectionHeader(stringResource(R.string.transactions)) }
                if (txs.isEmpty()) item { EmptyState(stringResource(R.string.no_tx_title), stringResource(R.string.no_tx_account_body)) }
                items(txs.take(300), key = { it.state.id }) { s -> TxRow(s.state.id, s.value, ctx, onOpen = { onOpenTx(s.state.id) }, showDate = true, forAccount = id) }
                // Rare, account-level actions live together at the end instead of as loose links mid-screen.
                item {
                    SectionHeader(stringResource(R.string.manage))
                    PennyCard {
                        ListRow(stringResource(if (a.archived) R.string.unarchive else R.string.archive),
                            leading = { Icon(Icons.Outlined.Archive, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = { vm.act(if (a.archived) R.string.unarchived else R.string.archived_done) { saveAccount(it, id, a.copy(archived = !a.archived)) } }, divider = true)
                        ListRow(stringResource(R.string.delete_account_row),
                            leading = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { dialog = "delete" })
                    }
                }
            }
        }
        when (dialog) {
            "set", "count" -> AmountDialog(
                ctx, stringResource(if (dialog == "set") R.string.set_balance else R.string.count_cash),
                stringResource(if (dialog == "count") R.string.count_cash_body else if (a.isLiability()) R.string.set_balance_owed_body else R.string.set_balance_body),
                a.currency, ctx.fmt.editable(if (a.isLiability()) -bal else bal, a.currency),
                onDismiss = { dialog = null },
                label = stringResource(if (a.isLiability() && dialog == "set") R.string.amount_owed else R.string.field_amount),
            ) { v ->
                val count = dialog == "count"
                // Cards and loans: the user types what they owe; the ledger balance is the negative of that.
                val target = if (!count && a.isLiability()) -v else v
                vm.act(R.string.balance_updated) { if (count) countCash(it, id, target) else setBalance(it, id, target) }
                dialog = null
            }
            "reconcile" -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.reconcile)) },
                text = { Text(stringResource(R.string.reconcile_body, ctx.money(bal, a.currency))) },
                confirmButton = { TextButton(onClick = { vm.act(R.string.reconciled_done) { reconcile(it, id, it.today()) }; dialog = null }) { Text(stringResource(R.string.reconcile)) } },
                dismissButton = { TextButton(onClick = { dialog = "set" }) { Text(stringResource(R.string.set_balance)) } },
            )
            "value" -> AmountDialog(ctx, stringResource(R.string.record_value), stringResource(R.string.record_value_body), a.currency, ctx.fmt.editable(bal, a.currency), onDismiss = { dialog = null }) { v ->
                vm.act(R.string.value_recorded) { valuations.create(newId(), it.profileId, app.penny.core.model.Valuation(id, it.today(), v)) }
                dialog = null
            }
            "delete" -> DeleteAccountDialog(ctx, id, a, txs.size, onDismiss = { dialog = null }) { moveTo ->
                vm.act(R.string.account_deleted, after = onBack) { deleteAccount(it, id, moveTo) }
                dialog = null
            }
        }
    }
}

@Composable
private fun CardSummary(ctx: Ctx, id: String, a: FinancialAccount, c: CardFields, bal: Long) {
    val today = ctx.book.today()
    val cyc = Debt.cardCycle(c.statementDay, c.dueDay, today)
    val owed = -bal
    val statementBal = -app.penny.core.budget.Ledger.balance(id, a, ctx.book.txList, cyc.statementDate)
    val unbilled = owed - statementBal
    val days = ChronoUnit.DAYS.between(today, cyc.dueDate)
    val util = if (c.limitMinor > 0) (owed * 100 / c.limitMinor).toInt() else 0
    Spacer(Modifier.height(12.dp))
    Row {
        StatBlock(stringResource(R.string.statement_balance), ctx.money(maxOf(0, statementBal), a.currency), Modifier.weight(1f), MaterialTheme.colorScheme.onPrimaryContainer)
        StatBlock(stringResource(R.string.unbilled), ctx.money(maxOf(0, unbilled), a.currency), Modifier.weight(1f), MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Spacer(Modifier.height(8.dp))
    Row {
        StatBlock(stringResource(R.string.minimum_due), ctx.money(maxOf(0, statementBal) * c.minDueBp / 10_000, a.currency), Modifier.weight(1f), MaterialTheme.colorScheme.onPrimaryContainer)
        StatBlock(stringResource(R.string.utilisation), "$util%", Modifier.weight(1f), if (util >= 80) Penny.status.over else MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Text(pluralStringResource(R.plurals.due_in_days, days.toInt().coerceAtLeast(0), days.toInt().coerceAtLeast(0)) + " · " + shortDate(cyc.dueDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun AmountDialog(ctx: Ctx, title: String, body: String, currency: String, initial: String, onDismiss: () -> Unit, label: String? = null, onOk: (Long) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val v = parseAmount(text, currency, ctx.fmt, allowZero = true)
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column { Text(body); Spacer(Modifier.height(8.dp)); AmountInput(text, { text = it }, currency, ctx.fmt, label ?: stringResource(R.string.field_amount), allowZero = true) } },
        confirmButton = { TextButton(onClick = { v?.let(onOk) }, enabled = v != null) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** AC-06 + UX-02: choose to move the transactions or delete them; typed confirmation of the account name. */
@Composable
private fun DeleteAccountDialog(ctx: Ctx, id: String, a: FinancialAccount, count: Int, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var moveTo by rememberSaveable { mutableStateOf<String?>(ctx.book.activeAccounts.firstOrNull { it.state.id != id && it.value.currency == a.currency }?.state?.id) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    // Bills, rules, goals and the like that use this account; they follow the same choice as its transactions.
    val uses = ctx.book.accountUseCount(id)
    if (!confirm && (count > 0 || uses > 0)) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.delete_account_title, a.name)) },
            text = {
                Column {
                    if (count > 0) Text(pluralStringResource(R.plurals.delete_account_body, count, count))
                    if (uses > 0) Text(pluralStringResource(R.plurals.delete_account_uses, uses, uses), modifier = Modifier.padding(top = 8.dp))
                    SelectField(stringResource(R.string.move_to), moveTo, ctx.book.activeAccounts.filter { it.state.id != id }.map { Choice(it.state.id, it.value.name, it.value.currency) }, { moveTo = it }, allowNone = true, noneLabel = stringResource(if (count > 0) R.string.delete_transactions_too else R.string.dont_move_pause))
                }
            },
            confirmButton = { TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.next)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    } else {
        TypedConfirmDialog(
            stringResource(R.string.delete_account_title, a.name), stringResource(R.string.delete_account_confirm), a.name,
            // Nothing moves unless the person chose where: with nothing using the account there was no choice to make.
            stringResource(R.string.delete), onConfirm = { onConfirm(if (count > 0 || uses > 0) moveTo else null) }, onDismiss = onDismiss,
        )
    }
}

/** AC-01…AC-05: every field, including card details and net-worth/budget switches. */
@Composable
fun AccountEditScreen(id: String, onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val existing = b.accountMap[id]?.value
        val isNew = existing == null
        var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
        var type by rememberSaveable { mutableStateOf(existing?.type ?: AccountType.BANK) }
        var currency by rememberSaveable { mutableStateOf(existing?.currency ?: ctx.base) }
        // Cards and loans take the amount owed as a positive number; the ledger stores it as negative.
        var opening by rememberSaveable { mutableStateOf(existing?.let { ctx.fmt.editable(if (it.isLiability()) -it.openingBalanceMinor else it.openingBalanceMinor, it.currency) } ?: "0") }
        var openingDate by rememberSaveable { mutableStateOf((existing?.openingDate ?: b.today()).toString()) }
        var institution by rememberSaveable { mutableStateOf(existing?.institution.orEmpty()) }
        var group by rememberSaveable { mutableStateOf(existing?.group.orEmpty()) }
        var note by rememberSaveable { mutableStateOf(existing?.note.orEmpty()) }
        var netWorth by rememberSaveable { mutableStateOf(existing?.includeInNetWorth ?: true) }
        var budget by rememberSaveable { mutableStateOf(existing?.includeInBudget ?: true) }
        var limit by rememberSaveable { mutableStateOf(existing?.card?.let { ctx.fmt.editable(it.limitMinor, currency) }.orEmpty()) }
        var stmt by rememberSaveable { mutableStateOf(existing?.card?.statementDay?.toString() ?: "1") }
        var due by rememberSaveable { mutableStateOf(existing?.card?.dueDay?.toString() ?: "20") }
        var minDue by rememberSaveable { mutableStateOf(existing?.card?.minDueBp?.let { (it / 100.0).toString() } ?: "5") }
        var apr by rememberSaveable { mutableStateOf(existing?.card?.aprBp?.let { (it / 100.0).toString() } ?: "") }
        var aliasOf by rememberSaveable { mutableStateOf(existing?.aliasOf) }
        var grams by rememberSaveable { mutableStateOf(existing?.weightGrams?.toString().orEmpty()) }
        var error by rememberSaveable { mutableStateOf<String?>(null) }
        val nameErr = stringResource(R.string.err_name_required)
        val takenErr = stringResource(R.string.name_taken)
        // Per-field problems, shown under the field that has them (nothing is silently replaced by a default).
        var fieldErr by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
        val amountErr = stringResource(R.string.err_amount_invalid)
        val dayErr = stringResource(R.string.err_day_of_month)
        val pctErr = stringResource(R.string.err_percent)
        val numErr = stringResource(R.string.err_number)
        val lockedErr = stringResource(R.string.err_currency_locked)
        PennyScreen(stringResource(if (isNew) R.string.add_account else R.string.edit_account), onBack, large = false) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                TextInput(name, { name = it; error = null }, stringResource(R.string.field_name), error = error)
                SelectField(stringResource(R.string.field_type), type, AccountType.entries.map { Choice(it, stringResource(accountTypeLabel(it))) }, { it?.let { t -> type = t; if (t in listOf(AccountType.LOAN, AccountType.LIABILITY, AccountType.CREDIT_CARD)) budget = t == AccountType.CREDIT_CARD } })
                SelectField(stringResource(R.string.field_currency), currency, currencyChoices(listOf(ctx.base)), { it?.let { c -> currency = c; fieldErr = fieldErr - "currency" } }, error = fieldErr["currency"])
                AmountInput(opening, { opening = it; fieldErr = fieldErr - "opening" }, currency, ctx.fmt, stringResource(if (isLiabilityHint(type)) R.string.opening_owed else R.string.opening_balance), allowZero = true, error = fieldErr["opening"])
                DateInput(stringResource(R.string.opening_date), runCatching { java.time.LocalDate.parse(openingDate) }.getOrNull(), { openingDate = it.toString() }, quick = false, today = b.today())
                TextInput(institution, { institution = it }, stringResource(R.string.institution))
                TextInput(group, { group = it }, stringResource(R.string.account_group), supporting = stringResource(R.string.account_group_hint))
                if (type == AccountType.CREDIT_CARD) {
                    SectionHeader(stringResource(R.string.card_details))
                    AmountInput(limit, { limit = it; fieldErr = fieldErr - "limit" }, currency, ctx.fmt, stringResource(R.string.credit_limit), error = fieldErr["limit"])
                    Row {
                        TextInput(stmt, { stmt = it.filter(Char::isDigit).take(2); fieldErr = fieldErr - "stmt" }, stringResource(R.string.statement_day), Modifier.weight(1f), keyboard = KeyboardType.Number, error = fieldErr["stmt"])
                        TextInput(due, { due = it.filter(Char::isDigit).take(2); fieldErr = fieldErr - "due" }, stringResource(R.string.due_day), Modifier.weight(1f), keyboard = KeyboardType.Number, error = fieldErr["due"])
                    }
                    Row {
                        TextInput(minDue, { minDue = it; fieldErr = fieldErr - "minDue" }, stringResource(R.string.minimum_due_pct), Modifier.weight(1f), keyboard = KeyboardType.Decimal, error = fieldErr["minDue"])
                        TextInput(apr, { apr = it; fieldErr = fieldErr - "apr" }, stringResource(R.string.apr_pct), Modifier.weight(1f), keyboard = KeyboardType.Decimal, error = fieldErr["apr"])
                    }
                }
                if (type == AccountType.E_WALLET) {
                    SelectField(stringResource(R.string.alias_of), aliasOf, b.activeAccounts.filter { it.state.id != id && it.value.type == AccountType.BANK }.map { Choice(it.state.id, it.value.name) }, { aliasOf = it }, allowNone = true)
                    Text(stringResource(R.string.alias_of_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                }
                if (type == AccountType.ASSET) TextInput(grams, { grams = it; fieldErr = fieldErr - "grams" }, stringResource(R.string.weight_grams), supporting = stringResource(R.string.weight_grams_hint), keyboard = KeyboardType.Decimal, error = fieldErr["grams"])
                TextInput(note, { note = it }, stringResource(R.string.field_note), singleLine = false)
                SwitchRow(stringResource(R.string.include_net_worth), netWorth, { netWorth = it })
                SwitchRow(stringResource(R.string.include_budget), budget, { budget = it }, subtitle = stringResource(R.string.include_budget_hint))
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.save), {
                    if (name.isBlank()) { error = nameErr; return@PrimaryButton }
                    // Two accounts with one name cannot be told apart in pickers and on Home.
                    if (b.accounts.any { it.state.id != id && it.value.name.trim().equals(name.trim(), ignoreCase = true) }) { error = takenErr; return@PrimaryButton }
                    val card = type == AccountType.CREDIT_CARD
                    val openMinor = if (opening.isBlank()) 0L else parseAmount(opening, currency, ctx.fmt, allowZero = true, allowNegative = true)
                    val limitMinor = if (!card || limit.isBlank()) 0L else parseAmount(limit, currency, ctx.fmt, allowZero = true)
                    val errs = buildMap {
                        if (openMinor == null) put("opening", amountErr)
                        if (card && limitMinor == null) put("limit", amountErr)
                        if (card && parseDayOfMonth(stmt) == null) put("stmt", dayErr)
                        if (card && parseDayOfMonth(due) == null) put("due", dayErr)
                        if (card && parsePercentBp(minDue) == null) put("minDue", pctErr)
                        if (card && apr.isNotBlank() && parsePercentBp(apr) == null) put("apr", pctErr)
                        if (type == AccountType.ASSET && grams.isNotBlank() && parseNonNegative(grams) == null) put("grams", numErr)
                        // Amounts carry the account's currency; changing it would re-read every balance in the new one.
                        if (existing != null && currency != existing.currency && b.transactions.any { it.value.accountId == id || it.value.toAccountId == id }) put("currency", lockedErr)
                    }
                    if (errs.isNotEmpty()) { fieldErr = errs; return@PrimaryButton }
                    val acct = (existing ?: FinancialAccount(name, type, currency)).copy(
                        name = name.trim(), type = type, currency = currency, openingBalanceMinor = openMinor!!.let { if (isLiabilityHint(type)) -it else it },
                        openingDate = runCatching { java.time.LocalDate.parse(openingDate) }.getOrNull(), institution = institution.trim(),
                        group = group.trim().ifEmpty { null }, note = note.trim(), includeInNetWorth = netWorth, includeInBudget = budget,
                        card = if (card) CardFields(limitMinor!!, parseDayOfMonth(stmt)!!, parseDayOfMonth(due)!!, parsePercentBp(minDue)!!, parsePercentBp(apr) ?: 0) else null,
                        aliasOf = aliasOf.takeIf { type == AccountType.E_WALLET }, weightGrams = parseNonNegative(grams).takeIf { type == AccountType.ASSET },
                    )
                    vm.act(if (isNew) R.string.account_added else R.string.saved, after = onBack) { saveAccount(it, if (isNew) null else id, acct) }
                }, Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

private fun isLiabilityHint(t: AccountType) = FinancialAccount("", t, "XXX").isLiability()
