package app.penny.ui.people

import app.penny.domain.ops.deleteCircleContribution
import app.penny.domain.ops.deleteRemittance
import app.penny.domain.ops.deleteGroupExpense
import app.penny.domain.ops.deleteGroup
import app.penny.domain.ops.deleteCircle
import app.penny.ui.common.safeStart
import android.content.Intent
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.core.io.Csv
import app.penny.core.model.Circle
import app.penny.core.model.CircleContribution
import app.penny.core.model.CircleMember
import app.penny.core.model.Group
import app.penny.core.model.GroupExpense
import app.penny.core.model.ItemLine
import app.penny.core.model.PayoutOrder
import app.penny.core.model.Person
import app.penny.core.model.RemitStatus
import app.penny.core.model.Remittance
import app.penny.core.model.Settlement
import app.penny.core.model.ShareLine
import app.penny.core.model.SplitMode
import app.penny.core.money.CurrencyUnit
import app.penny.core.planning.RemitCircle
import app.penny.core.planning.Splitting
import app.penny.core.schedule.Freq
import app.penny.core.schedule.Recurrence
import app.penny.domain.ops.groupBalances
import app.penny.domain.ops.recordCircle
import app.penny.domain.ops.saveCircle
import app.penny.domain.ops.saveGroup
import app.penny.domain.ops.saveGroupExpense
import app.penny.domain.ops.saveRemittance
import app.penny.domain.ops.settle
import app.penny.ui.common.Ctx
import app.penny.ui.common.WithBook
import app.penny.ui.common.shortDate
import app.penny.ui.kit.AmountInput
import app.penny.ui.kit.Avatar
import app.penny.ui.kit.Choice
import app.penny.ui.kit.DateInput
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.ListRow
import app.penny.ui.kit.MoneyLabel
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.Pill
import app.penny.ui.kit.PrimaryButton
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.Segmented
import app.penny.ui.kit.SelectField
import app.penny.ui.kit.StatBlock
import app.penny.ui.kit.SwitchRow
import app.penny.ui.kit.TextInput
import app.penny.ui.kit.parseAmount
import app.penny.ui.theme.Penny
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.math.BigDecimal

private fun memberName(ctx: Ctx, id: String, me: String) = if (id == Group.ME) me else ctx.book.personName(id).ifEmpty { "?" }

/** "Ken pays you" / "You pay Ken" / "Ken pays Aiko": "you" needs its own grammar, not a capitalised name. */
private fun payLine(context: android.content.Context, ctx: Ctx, from: String, to: String, me: String): String = when {
    from == Group.ME -> context.getString(R.string.you_pay_x, memberName(ctx, to, me))
    to == Group.ME -> context.getString(R.string.x_pays_you, memberName(ctx, from, me))
    else -> context.getString(R.string.x_pays_y, memberName(ctx, from, me), memberName(ctx, to, me))
}

/** PE-03 group: balances, simplified debts with settle-up links, expenses, export, archive. */
@Composable
fun GroupScreen(id: String, onBack: () -> Unit, onEdit: (String) -> Unit, onExpense: (String, String) -> Unit, svm: app.penny.ui.settings.SyncViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val g = b.groups.firstOrNull { it.state.id == id }
        if (g == null) { PennyScreen(stringResource(R.string.groups), onBack) { pad -> EmptyState(stringResource(R.string.not_found), stringResource(R.string.not_found_body), Modifier.padding(pad)) }; return@WithBook }
        val context = LocalContext.current
        val me = stringResource(R.string.you)
        val bal = b.groupBalances(id)
        val plan = if (g.value.simplify) Splitting.simplify(bal) else Splitting.pairwise(b.groupExpenses.filter { it.value.groupId == id }.map { it.value }, b.settlements.filter { it.value.groupId == id }.map { it.value }, g.value.baseCurrency)
        val expenses = b.groupExpenses.filter { it.value.groupId == id }
        var settleDialog by rememberSaveable { mutableStateOf<Int?>(null) }
        PennyScreen(
            g.value.name, onBack,
            actions = {
                IconButton(onClick = {
                    val rows = listOf(listOf("date", "description", "payer", "amount", "currency", "split", "disputed")) + expenses.map { e ->
                        listOf(e.value.date.toString(), e.value.description, memberName(ctx, e.value.payerId, me), BigDecimal.valueOf(e.value.amountMinor, CurrencyUnit.of(e.value.currency).minorUnits).toPlainString(), e.value.currency, e.value.mode.name.lowercase(), e.value.disputed.toString())
                    }
                    context.safeStart(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_TEXT, Csv.write(rows)).putExtra(Intent.EXTRA_SUBJECT, g.value.name), null))
                }) { Icon(Icons.Outlined.IosShare, stringResource(R.string.export)) }
                IconButton(onClick = { onEdit(id) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) }
            },
            floating = { app.penny.ui.kit.AddFab(stringResource(R.string.add_expense), { onExpense("new", id) }) },
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 100.dp)) {
                item { SectionHeader(stringResource(R.string.balances)) }
                item {
                    PennyCard {
                        (listOf(Group.ME) + g.value.memberIds.filter { it != Group.ME }).forEach { m ->
                            val (t, c) = balanceText(ctx, bal[m] ?: 0, g.value.baseCurrency)
                            ListRow(memberName(ctx, m, me), leading = { Avatar(memberName(ctx, m, me), m) }, trailingText = t, trailingColor = c, divider = true)
                        }
                    }
                }
                if (plan.isNotEmpty()) {
                    item { SectionHeader(stringResource(if (g.value.simplify) R.string.simplified_settle else R.string.who_owes_whom)) }
                    item {
                        PennyCard {
                            plan.forEachIndexed { i, d ->
                                ListRow(
                                    payLine(context, ctx, d.from, d.to, me), subtitle = ctx.money(d.amountMinor, g.value.baseCurrency),
                                    trailing = { TextButton(onClick = { settleDialog = i }) { Text(stringResource(R.string.settle)) } }, divider = i < plan.lastIndex,
                                )
                            }
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.expenses)) }
                if (expenses.isEmpty()) item { EmptyState(stringResource(R.string.no_group_expenses), stringResource(R.string.no_group_expenses_body)) }
                else item {
                    PennyCard {
                        expenses.forEachIndexed { i, e ->
                            ListRow(
                                e.value.description,
                                subtitle = listOfNotNull(shortDate(e.value.date), if (e.value.payerId == Group.ME) stringResource(R.string.paid_by_you) else stringResource(R.string.paid_by_x, memberName(ctx, e.value.payerId, me)), stringResource(R.string.disputed).takeIf { e.value.disputed }).joinToString(" · "),
                                trailingText = ctx.money(e.value.amountMinor, e.value.currency), onClick = { onExpense(e.state.id, id) }, divider = i < expenses.lastIndex,
                            )
                        }
                    }
                }
                item {
                    // US-10: a read-only web view for members without the app (end-to-end encrypted link).
                    val syncState by svm.sync.state.collectAsStateWithLifecycle()
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    if (syncState.phase == app.penny.domain.SyncPhase.READY) TextButton(onClick = {
                        scope.launch {
                            val rows = (listOf(Group.ME) + g.value.memberIds.filter { it != Group.ME }).map { m -> memberName(ctx, m, me) to (bal[m] ?: 0) }
                            val json = kotlinx.serialization.json.buildJsonObject {
                                put("title", kotlinx.serialization.json.JsonPrimitive(g.value.name))
                                put("generatedAt", kotlinx.serialization.json.JsonPrimitive(java.time.Instant.now().toString()))
                                put("currency", kotlinx.serialization.json.JsonPrimitive(g.value.baseCurrency))
                                put("note", kotlinx.serialization.json.JsonPrimitive(plan.joinToString("; ") { d -> payLine(context, ctx, d.from, d.to, me) + " " + ctx.money(d.amountMinor, g.value.baseCurrency) }))
                                put("rows", kotlinx.serialization.json.JsonArray(rows.map { (n, v) -> kotlinx.serialization.json.buildJsonObject { put("label", kotlinx.serialization.json.JsonPrimitive(n)); put("value", kotlinx.serialization.json.JsonPrimitive(ctx.money(v, g.value.baseCurrency, signed = v > 0))) } }))
                            }.toString()
                            runCatching { svm.sync.advisorLink(json, 30) }.onSuccess { link ->
                                context.safeStart(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, context.getString(R.string.group_link_text, g.value.name, link)), null))
                            }
                        }
                    }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.share_group_link)) }
                    app.penny.ui.kit.ActionStrip {
                        app.penny.ui.kit.StripAction(destructive = false, onClick = { vm.act(R.string.updated) { saveGroup(it, id, g.value.copy(simplify = !g.value.simplify)) } }) { Text(stringResource(if (g.value.simplify) R.string.show_pairwise else R.string.simplify_debts)) }
                        app.penny.ui.kit.StripAction(destructive = false, onClick = { vm.act(if (g.value.archived) R.string.unarchived else R.string.archived_done) { saveGroup(it, id, g.value.copy(archived = !g.value.archived)) } }) { Text(stringResource(if (g.value.archived) R.string.unarchive else R.string.archive)) }
                        app.penny.ui.kit.StripAction(destructive = true, onClick = { vm.act(R.string.deleted, after = onBack) { deleteGroup(it, id) } }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
        settleDialog?.let { i ->
            val d = plan.getOrNull(i) ?: return@let
            var account by rememberSaveable { mutableStateOf(b.liveDefaultAccountId) }
            val involvesMe = d.from == Group.ME || d.to == Group.ME
            AlertDialog(
                onDismissRequest = { settleDialog = null },
                title = { Text(stringResource(R.string.settle)) },
                text = {
                    Column {
                        Text(payLine(context, ctx, d.from, d.to, me) + " · " + ctx.money(d.amountMinor, g.value.baseCurrency))
                        if (involvesMe) SelectField(stringResource(R.string.field_account), account, b.activeAccounts.map { Choice(it.state.id, it.value.name) }, { account = it }, allowNone = true, noneLabel = stringResource(R.string.dont_post))
                        if (d.from == Group.ME) TextButton(onClick = { context.safeStart(settleIntent(context, ctx, b.peopleMap[d.to]?.value, d.amountMinor, g.value.baseCurrency, g.value.name)) }) { Text(stringResource(R.string.pay_in_app)) }
                        if (d.to == Group.ME) TextButton(onClick = {
                            // The real amount even while hidden on screen: the person is choosing to send it.
                            val amt = ctx.fmt.format(d.amountMinor, g.value.baseCurrency)
                            app.penny.ui.common.ShareCard.share(context, g.value.name, context.getString(R.string.share_card_settle, memberName(ctx, d.from, me)), null, amt, emptyList(),
                                context.getString(R.string.settle_request_text, amt, g.value.name))
                        }) { Text(stringResource(R.string.send_request)) }
                    }
                },
                confirmButton = {
                    // The one action the dialog is for, filled like the bill dialog's "Mark paid".
                    androidx.compose.material3.Button(onClick = {
                        vm.act(R.string.settled) { settle(it, Settlement(id, d.from, d.to, d.amountMinor, g.value.baseCurrency, it.today()), account.takeIf { involvesMe }) }
                        settleDialog = null
                    }) { Text(stringResource(R.string.mark_as_paid)) }
                },
                dismissButton = { TextButton(onClick = { settleDialog = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

@Composable
fun GroupEditScreen(id: String, onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val existing = b.groups.firstOrNull { it.state.id == id }?.value
        var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
        var currency by rememberSaveable { mutableStateOf(existing?.baseCurrency ?: ctx.base) }
        var members by rememberSaveable { mutableStateOf(existing?.memberIds?.filter { it != Group.ME } ?: emptyList()) }
        var newMember by rememberSaveable { mutableStateOf("") }
        var newNames by rememberSaveable { mutableStateOf(listOf<String>()) }
        var err by rememberSaveable { mutableStateOf<String?>(null) }
        val req = stringResource(R.string.err_group)
        PennyScreen(stringResource(if (existing == null) R.string.new_group else R.string.edit_group), onBack, large = false) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                TextInput(name, { name = it; err = null }, stringResource(R.string.field_name), error = err)
                // Expenses in the old base currency without a rate would be re-read 1:1 in a new one.
                val currencyLocked = existing != null && b.groupExpenses.any { it.value.groupId == id }
                SelectField(stringResource(R.string.group_currency), currency, app.penny.ui.kit.currencyChoices(listOf(ctx.base)), { it?.let { c -> if (!currencyLocked) currency = c } },
                    error = if (currencyLocked && currency != existing?.baseCurrency) stringResource(R.string.err_currency_locked) else null)
                if (currencyLocked) Text(stringResource(R.string.currency_locked_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                SectionHeader(stringResource(R.string.members))
                b.people.filter { !it.value.archived }.forEach { p ->
                    app.penny.ui.kit.CheckRow(p.state.id in members, { on -> members = if (on) members + p.state.id else members - p.state.id }, p.value.name)
                }
                // New names line up with existing people: ticked, and unticking removes them before they are created.
                newNames.forEach { n ->
                    app.penny.ui.kit.CheckRow(true, { on -> if (!on) newNames = newNames - n }, n)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextInput(newMember, { newMember = it }, stringResource(R.string.add_member_by_name), Modifier.weight(1f))
                    TextButton(onClick = {
                        val n = newMember.trim()
                        if (n.isNotEmpty()) {
                            // A name that is already a person ticks that person instead of creating a second one.
                            val known = b.people.firstOrNull { it.value.name.trim().equals(n, ignoreCase = true) }
                            if (known != null) { if (known.state.id !in members) members = members + known.state.id }
                            else if (newNames.none { it.equals(n, ignoreCase = true) }) newNames = newNames + n
                            newMember = ""
                        }
                    }) { Text(stringResource(R.string.add)) }
                }
                Text(stringResource(R.string.group_non_users), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.save), {
                    if (name.isBlank() || (members.isEmpty() && newNames.isEmpty())) { err = req; return@PrimaryButton }
                    vm.act(R.string.saved, after = onBack) { book ->
                        val g = groups.newGroup()
                        val created = newNames.map { n -> newId().also { people.create(it, book.profileId, Person(n), g) } }
                        val gr = (existing ?: Group(name, currency, emptyList())).copy(name = name.trim(), baseCurrency = currency, memberIds = listOf(Group.ME) + members + created)
                        if (existing == null) groups.create(newId(), book.profileId, gr, g) else groups.update(id, gr, groupId = g)
                        g
                    }
                }, Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

/** PE-03 expense: payer, shares (equal, exact, percent, shares, by item), currency with rate, dispute (PE-07). */
@Composable
fun GroupExpenseEditScreen(id: String, groupArg: String?, onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val existing = b.groupExpenses.firstOrNull { it.state.id == id }?.value
        val groupId = existing?.groupId ?: groupArg
        val g = groupId?.let { gid -> b.groups.firstOrNull { it.state.id == gid }?.value }
        if (groupId == null || g == null) { PennyScreen(stringResource(R.string.groups), onBack) { pad -> EmptyState(stringResource(R.string.not_found), stringResource(R.string.not_found_body), Modifier.padding(pad)) }; return@WithBook }
        val me = stringResource(R.string.you)
        val itemDefault = stringResource(R.string.item_default_name)
        val members = listOf(Group.ME) + g.memberIds.filter { it != Group.ME }
        var desc by rememberSaveable { mutableStateOf(existing?.description.orEmpty()) }
        var payer by rememberSaveable { mutableStateOf(existing?.payerId ?: Group.ME) }
        var currency by rememberSaveable { mutableStateOf(existing?.currency ?: g.baseCurrency) }
        var amount by rememberSaveable { mutableStateOf(existing?.let { ctx.fmt.editable(it.amountMinor, it.currency) }.orEmpty()) }
        var rate by rememberSaveable { mutableStateOf(existing?.rateToBase.orEmpty()) }
        var date by rememberSaveable { mutableStateOf((existing?.date ?: b.today()).toString()) }
        var mode by rememberSaveable { mutableStateOf(existing?.mode ?: SplitMode.EQUAL) }
        var values by rememberSaveable { mutableStateOf(members.associateWith { m -> existing?.shares?.firstOrNull { it.memberId == m }?.value?.toString() ?: if (existing == null) "1" else "0" }.map { "${it.key}=${it.value}" }) }
        var items by rememberSaveable { mutableStateOf<List<String>>(existing?.items?.map { "${it.label}|${ctx.fmt.editable(it.amountMinor, existing.currency)}|${it.memberIds.joinToString(",")}" } ?: emptyList()) }
        var category by rememberSaveable { mutableStateOf(existing?.categoryId) }
        var disputed by rememberSaveable { mutableStateOf(existing?.disputed ?: false) }
        var disputeNote by rememberSaveable { mutableStateOf(existing?.disputeNote.orEmpty()) }
        var account by rememberSaveable { mutableStateOf(b.liveDefaultAccountId) }
        var err by rememberSaveable { mutableStateOf<String?>(null) }
        val invalid = stringResource(R.string.err_split_invalid)
        val rateErr = stringResource(R.string.err_rate_required)
        val pctErr = stringResource(R.string.err_percent)
        val context = androidx.compose.ui.platform.LocalContext.current
        fun v(m: String) = values.firstOrNull { it.startsWith("$m=") }?.substringAfter('=').orEmpty()
        fun setV(m: String, s: String) { values = values.filter { !it.startsWith("$m=") } + "$m=$s" }
        PennyScreen(stringResource(if (existing == null) R.string.add_expense else R.string.edit_expense), onBack, large = false) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                TextInput(desc, { desc = it }, stringResource(R.string.description))
                SelectField(stringResource(R.string.field_currency), currency, app.penny.ui.kit.currencyChoices(listOf(g.baseCurrency, ctx.base)), { it?.let { c -> currency = c } })
                AmountInput(amount, { amount = it }, currency, ctx.fmt, stringResource(R.string.field_amount))
                if (currency != g.baseCurrency) TextInput(rate, { rate = it; err = null }, stringResource(R.string.rate_to_x, g.baseCurrency), keyboard = KeyboardType.Decimal)
                SelectField(stringResource(R.string.paid_by), payer, members.map { Choice(it, memberName(ctx, it, me)) }, { it?.let { p -> payer = p } })
                DateInput(stringResource(R.string.field_date), runCatching { java.time.LocalDate.parse(date) }.getOrNull(), { date = it.toString() }, today = b.today())
                SelectField(stringResource(R.string.field_category), category, b.categoriesOf(app.penny.core.model.CategoryKind.EXPENSE).map { Choice(it.state.id, b.categoryName(it.state.id)) }, { category = it }, allowNone = true,
                    onAddNew = { name -> vm.addCategory(b, name, app.penny.core.model.CategoryKind.EXPENSE) { category = it } })
                SectionHeader(stringResource(R.string.split))
                SelectField(stringResource(R.string.split_mode), mode, SplitMode.entries.map { Choice(it, stringResource(when (it) { SplitMode.EQUAL -> R.string.split_equal; SplitMode.EXACT -> R.string.split_exact; SplitMode.PERCENT -> R.string.split_percent; SplitMode.SHARES -> R.string.split_shares; SplitMode.ITEMS -> R.string.split_items })) }, { it?.let { m ->
                    // Each mode starts from a sensible split: even percentages, one share each, empty exact amounts.
                    if (m != mode) {
                        val n = members.size.coerceAtLeast(1)
                        val even = java.math.BigDecimal(100).divide(java.math.BigDecimal(n), 2, java.math.RoundingMode.DOWN)
                        val last = java.math.BigDecimal(100).subtract(even.multiply(java.math.BigDecimal(n - 1)))
                        values = members.mapIndexed { i, id -> "$id=" + when (m) {
                            SplitMode.PERCENT -> (if (i == members.lastIndex) last else even).stripTrailingZeros().toPlainString()
                            SplitMode.EXACT -> ""
                            else -> "1"
                        } }
                    }
                    mode = m
                } })
                // Live total for percent and exact splits, so a mismatch shows before Save.
                if (mode == SplitMode.PERCENT || mode == SplitMode.EXACT) {
                    val sum = members.sumOf { m -> if (mode == SplitMode.PERCENT) v(m).replace(',', '.').toBigDecimalOrNull()?.multiply(BigDecimal(100))?.toLong() ?: 0 else parseAmount(v(m), currency, ctx.fmt) ?: 0 }
                    val goal = if (mode == SplitMode.PERCENT) 10_000L else (parseAmount(amount, currency, ctx.fmt) ?: 0)
                    val shown = if (mode == SplitMode.PERCENT) stringResource(R.string.split_total_percent, BigDecimal(sum).movePointLeft(2).stripTrailingZeros().toPlainString())
                        else stringResource(R.string.split_total_amount, ctx.money(sum, currency), ctx.money(goal, currency))
                    Text(shown, style = MaterialTheme.typography.bodySmall, color = if (sum == goal) MaterialTheme.colorScheme.onSurfaceVariant else Penny.status.near, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                // Live preview of each person's part, with the same remainder rule as the saved split.
                val preview: Map<String, Long> = run {
                    val total = parseAmount(amount, currency, ctx.fmt) ?: 0L
                    when (mode) {
                        SplitMode.EQUAL, SplitMode.SHARES -> Splitting.distribute(total, members.mapNotNull { m -> v(m).toLongOrNull()?.takeIf { it > 0 }?.let { m to it } })
                        SplitMode.PERCENT -> Splitting.distribute(total, members.mapNotNull { m -> app.penny.ui.kit.parsePercentBp(v(m))?.toLong()?.takeIf { it > 0 }?.let { m to it } })
                        else -> emptyMap()
                    }
                }
                if (mode != SplitMode.ITEMS) members.forEach { m ->
                    if (mode == SplitMode.EQUAL) {
                        app.penny.ui.kit.CheckRow(v(m) != "0" && v(m).isNotEmpty(), { on -> setV(m, if (on) "1" else "0") }, memberName(ctx, m, me),
                            trailing = preview[m]?.let { amt -> { Text(ctx.money(amt, currency), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } })
                    } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, end = 20.dp)) {
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(memberName(ctx, m, me))
                            preview[m]?.let { Text(ctx.money(it, currency), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        TextInput(v(m), { setV(m, if (mode == SplitMode.SHARES) it.filter(Char::isDigit).take(4) else it) }, stringResource(when (mode) { SplitMode.EXACT -> R.string.field_amount; SplitMode.PERCENT -> R.string.percent; else -> R.string.shares }), Modifier.weight(1f), keyboard = if (mode == SplitMode.SHARES) KeyboardType.Number else KeyboardType.Decimal)
                    }
                } else {
                    items.forEachIndexed { i, line ->
                        val (label, amt, who) = line.split('|').let { Triple(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
                        PennyCard {
                            TextInput(label, { s -> items = items.toMutableList().also { l -> l[i] = "$s|$amt|$who" } }, stringResource(R.string.item))
                            AmountInput(amt, { s -> items = items.toMutableList().also { l -> l[i] = "$label|$s|$who" } }, currency, ctx.fmt, stringResource(R.string.field_amount))
                            members.forEach { m ->
                                val set = who.split(',').filter { it.isNotEmpty() }
                                app.penny.ui.kit.CheckRow(m in set, { on -> val ns = if (on) set + m else set - m; items = items.toMutableList().also { l -> l[i] = "$label|$amt|${ns.joinToString(",")}" } }, memberName(ctx, m, me))
                            }
                        }
                    }
                    TextButton(onClick = { items = items + "||${members.joinToString(",")}" }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.add_item)) }
                }
                if (existing == null && payer == Group.ME) SelectField(stringResource(R.string.post_from_account), account, b.activeAccounts.map { Choice(it.state.id, it.value.name) }, { account = it }, allowNone = true, noneLabel = stringResource(R.string.dont_post))
                // Flagging a problem is for an expense someone else sees later, not for one being written now.
                if (existing != null) SwitchRow(stringResource(R.string.dispute), disputed, { disputed = it }, subtitle = stringResource(R.string.dispute_hint))
                if (disputed) TextInput(disputeNote, { disputeNote = it }, stringResource(R.string.dispute_note))
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.save), {
                    val a = parseAmount(amount, currency, ctx.fmt)
                    if (a == null || desc.isBlank()) { err = invalid; return@PrimaryButton }
                    // A foreign expense needs its rate: without one it was counted 1:1 in the group's currency.
                    if (currency != g.baseCurrency && (rate.replace(',', '.').trim().toBigDecimalOrNull()?.signum() ?: 0) <= 0) { err = rateErr; return@PrimaryButton }
                    if (mode == SplitMode.PERCENT && members.any { m -> v(m).isNotBlank() && app.penny.ui.kit.parsePercentBp(v(m)) == null }) { err = pctErr; return@PrimaryButton }
                    if (mode == SplitMode.EXACT && members.any { m -> v(m).isNotBlank() && parseAmount(v(m), currency, ctx.fmt, allowZero = true) == null }) { err = invalid; return@PrimaryButton }
                    if (mode == SplitMode.ITEMS) items.firstOrNull { line -> val p = line.split('|'); (p.getOrElse(0) { "" }.isNotBlank() || p.getOrElse(1) { "" }.isNotBlank()) && parseAmount(p.getOrElse(1) { "" }, currency, ctx.fmt) == null }
                        ?.let { bad -> err = context.getString(R.string.err_split_item, bad.substringBefore('|').ifBlank { itemDefault }); return@PrimaryButton }
                    val shares = members.map { m ->
                        ShareLine(m, when (mode) {
                            SplitMode.EXACT -> parseAmount(v(m), currency, ctx.fmt) ?: 0
                            SplitMode.PERCENT -> v(m).replace(',', '.').toBigDecimalOrNull()?.multiply(BigDecimal(100))?.toLong() ?: 0
                            else -> v(m).toLongOrNull() ?: 0
                        })
                    }
                    val lines = items.mapNotNull { line ->
                        val p = line.split('|')
                        val amt = parseAmount(p.getOrElse(1) { "" }, currency, ctx.fmt) ?: return@mapNotNull null
                        ItemLine(p[0].ifBlank { itemDefault }, amt, p.getOrElse(2) { "" }.split(',').filter { it.isNotEmpty() })
                    }
                    val e = GroupExpense(groupId, desc.trim(), payer, a, currency, java.time.LocalDate.parse(date), mode, shares, lines, category, disputed = disputed, disputeNote = disputeNote, rateToBase = rate.replace(',', '.').ifBlank { null }, transactionId = existing?.transactionId)
                    if (!Splitting.validate(e)) { err = invalid; return@PrimaryButton }
                    vm.act(R.string.saved, after = onBack) { saveGroupExpense(it, if (existing == null) null else id, e, account.takeIf { existing == null && payer == Group.ME }) }
                }, Modifier.padding(horizontal = 16.dp))
                if (existing != null) app.penny.ui.kit.ActionStrip {
                    app.penny.ui.kit.StripAction(destructive = true, onClick = { vm.act(R.string.deleted, after = onBack) { deleteGroupExpense(it, id) } }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Remittances (RM)

@Composable
fun RemittancesScreen(onBack: () -> Unit, onEdit: (String) -> Unit) {
    WithBook { ctx, _ ->
        val b = ctx.book
        val byProvider = RemitCircle.byProvider(b.remittances.map { it.value })
        PennyScreen(
            stringResource(R.string.remittances), onBack,
            floating = { app.penny.ui.kit.AddFab(stringResource(R.string.record_remittance), { onEdit("new") }) },
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 100.dp)) {
                if (byProvider.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.provider_cost)) }
                    item {
                        PennyCard {
                            byProvider.entries.sortedBy { it.value }.forEach { (p, bp) -> ListRow(p.ifBlank { stringResource(R.string.provider_not_named) }, trailingText = "%.2f%%".format(bp / 100.0), divider = true) }
                        }
                        Text(stringResource(R.string.no_referrals), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }
                if (b.remittances.isEmpty()) item { EmptyState(stringResource(R.string.no_remittances), stringResource(R.string.no_remittances_body), icon = Icons.Outlined.Public) }
                items(b.remittances, key = { it.state.id }) { r ->
                    val c = RemitCircle.cost(r.value)
                    ListRow(
                        if (r.value.outgoing) stringResource(R.string.to_x, r.value.receiver) else stringResource(R.string.from_x, r.value.sender),
                        subtitle = listOfNotNull(shortDate(r.value.date), r.value.provider.takeIf { it.isNotBlank() }, c.totalBp?.let { stringResource(R.string.true_cost_x, "%.2f%%".format(it / 100.0)) }, stringResource(when (r.value.status) { app.penny.core.model.RemitStatus.SENT -> R.string.remit_status_sent; app.penny.core.model.RemitStatus.RECEIVED -> R.string.remit_status_received; app.penny.core.model.RemitStatus.CONFIRMED -> R.string.remit_status_confirmed })).joinToString(" · "),
                        trailingText = ctx.money(if (r.value.outgoing) r.value.sentMinor else r.value.receivedMinor, if (r.value.outgoing) r.value.sentCurrency else r.value.receivedCurrency),
                        onClick = { onEdit(r.state.id) }, divider = true,
                    )
                }
            }
        }
    }
}

/** RM-01/02/03: both sides, fee, rate, received amount, true cost vs mid-market; can post to the ledger. */
@Composable
fun RemittanceEditScreen(id: String, onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val existing = b.remittances.firstOrNull { it.state.id == id }?.value
        var outgoing by rememberSaveable { mutableStateOf(existing?.outgoing ?: true) }
        var sender by rememberSaveable { mutableStateOf(existing?.sender ?: b.profile.name) }
        var receiver by rememberSaveable { mutableStateOf(existing?.receiver.orEmpty()) }
        var sentCur by rememberSaveable { mutableStateOf(existing?.sentCurrency ?: ctx.base) }
        var recvCur by rememberSaveable { mutableStateOf(existing?.receivedCurrency ?: ctx.base) }
        var sent by rememberSaveable { mutableStateOf(existing?.let { ctx.fmt.editable(it.sentMinor, it.sentCurrency) }.orEmpty()) }
        var fee by rememberSaveable { mutableStateOf(existing?.let { ctx.fmt.editable(it.feeMinor, it.sentCurrency) }.orEmpty()) }
        var received by rememberSaveable { mutableStateOf(existing?.let { ctx.fmt.editable(it.receivedMinor, it.receivedCurrency) }.orEmpty()) }
        var mid by rememberSaveable { mutableStateOf(existing?.midRate.orEmpty()) }
        var provider by rememberSaveable { mutableStateOf(existing?.provider.orEmpty()) }
        var purpose by rememberSaveable { mutableStateOf(existing?.purpose.orEmpty()) }
        var date by rememberSaveable { mutableStateOf((existing?.date ?: b.today()).toString()) }
        var status by rememberSaveable { mutableStateOf(existing?.status ?: RemitStatus.SENT) }
        var account by rememberSaveable { mutableStateOf(b.liveDefaultAccountId) }
        var err by rememberSaveable { mutableStateOf<String?>(null) }
        val req = stringResource(R.string.err_remit)
        val feeErr = stringResource(R.string.err_fee_too_high)
        PennyScreen(stringResource(if (existing == null) R.string.record_remittance else R.string.edit_remittance), onBack, large = false) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                Segmented(listOf(Choice(true, stringResource(R.string.i_sent)), Choice(false, stringResource(R.string.i_received))), outgoing, { outgoing = it })
                TextInput(sender, { sender = it }, stringResource(R.string.sender))
                TextInput(receiver, { receiver = it }, stringResource(R.string.receiver))
                TextInput(provider, { provider = it }, stringResource(R.string.provider))
                SelectField(stringResource(R.string.sent_currency), sentCur, app.penny.ui.kit.currencyChoices(listOf(ctx.base)), { it?.let { c -> sentCur = c } })
                AmountInput(sent, { sent = it }, sentCur, ctx.fmt, stringResource(R.string.amount_sent))
                AmountInput(fee, { fee = it }, sentCur, ctx.fmt, stringResource(R.string.fee))
                SelectField(stringResource(R.string.received_currency), recvCur, app.penny.ui.kit.currencyChoices(listOf(ctx.base)), { it?.let { c -> recvCur = c } })
                AmountInput(received, { received = it }, recvCur, ctx.fmt, stringResource(R.string.amount_received))
                TextInput(mid, { mid = it }, stringResource(R.string.mid_market_rate), supporting = stringResource(R.string.mid_market_hint), keyboard = KeyboardType.Decimal)
                val s = parseAmount(sent, sentCur, ctx.fmt)
                val r = parseAmount(received, recvCur, ctx.fmt)
                val f = parseAmount(fee, sentCur, ctx.fmt) ?: 0
                val applied = if (s != null && r != null) app.penny.core.budget.FxTable.impliedRate(s, sentCur, r, recvCur) else null
                if (applied != null) {
                    val cost = RemitCircle.cost(Remittance(sender, receiver, sentMinor = s!!, sentCurrency = sentCur, feeMinor = f, rate = applied.toPlainString(), receivedMinor = r!!, receivedCurrency = recvCur, midRate = mid.ifBlank { null }, provider = provider, date = b.today()))
                    PennyCard(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.rate_x, applied.stripTrailingZeros().toPlainString()), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.fee_pct_x, "%.2f%%".format(cost.feeBp / 100.0)), style = MaterialTheme.typography.bodyMedium)
                            cost.totalBp?.let { Text(stringResource(R.string.true_cost_x, "%.2f%%".format(it / 100.0)), style = MaterialTheme.typography.titleSmall) }
                            cost.lostMinor?.let { if (it > 0) Text(stringResource(R.string.lost_x, ctx.money(it, recvCur)), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                TextInput(purpose, { purpose = it }, stringResource(R.string.purpose))
                DateInput(stringResource(R.string.field_date), runCatching { java.time.LocalDate.parse(date) }.getOrNull(), { date = it.toString() }, today = b.today())
                Segmented(RemitStatus.entries.map { Choice(it, stringResource(when (it) { RemitStatus.SENT -> R.string.status_sent; RemitStatus.RECEIVED -> R.string.status_received; RemitStatus.CONFIRMED -> R.string.status_confirmed })) }, status, { status = it })
                if (existing == null) SelectField(stringResource(R.string.post_to_account), account, b.activeAccounts.map { Choice(it.state.id, it.value.name) }, { account = it }, allowNone = true, noneLabel = stringResource(R.string.dont_post))
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.save), {
                    if (s == null || r == null || receiver.isBlank() || applied == null) { err = req; return@PrimaryButton }
                    // The fee is part of what was sent: one larger than the amount is a typo, and an unreadable one is not 0.
                    val feeOk = fee.isBlank() || parseAmount(fee, sentCur, ctx.fmt, allowZero = true)?.let { it < s } == true
                    if (!feeOk) { err = feeErr; return@PrimaryButton }
                    val rem = Remittance(sender.trim(), receiver.trim(), sentMinor = s, sentCurrency = sentCur, feeMinor = f, rate = applied.toPlainString(), receivedMinor = r, receivedCurrency = recvCur,
                        midRate = mid.replace(',', '.').ifBlank { null }, provider = provider.trim(), purpose = purpose.trim(), date = java.time.LocalDate.parse(date), status = status, outgoing = outgoing, transactionId = existing?.transactionId)
                    vm.act(R.string.saved, after = onBack) { saveRemittance(it, if (existing == null) null else id, rem, account.takeIf { existing == null }) }
                }, Modifier.padding(horizontal = 16.dp))
                if (existing != null) app.penny.ui.kit.ActionStrip {
                    app.penny.ui.kit.StripAction(destructive = true, onClick = { vm.act(R.string.deleted, after = onBack) { deleteRemittance(it, id) } }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Circles (CI)

/** CI-02/03: rounds with dates and recipient, who has paid, my turn, record contributions and payouts. */
@Composable
fun CircleScreen(id: String, onBack: () -> Unit, onEdit: (String) -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val c = b.circles.firstOrNull { it.state.id == id }
        if (c == null) { PennyScreen(stringResource(R.string.circles), onBack) { pad -> EmptyState(stringResource(R.string.not_found), stringResource(R.string.not_found_body), Modifier.padding(pad)) }; return@WithBook }
        val contribs = b.circleContributions.filter { it.value.circleId == id }.map { it.value }
        val rounds = RemitCircle.rounds(c.value, contribs)
        var account by rememberSaveable { mutableStateOf(b.activeAccountOrNull(c.value.accountId) ?: b.liveDefaultAccountId) }
        PennyScreen(c.value.name, onBack, actions = { IconButton(onClick = { onEdit(id) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) } }) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item {
                    app.penny.ui.kit.HeroCard {
                        Column(Modifier.padding(20.dp)) {
                            Text(c.value.localName.ifBlank { stringResource(R.string.circles) }, style = MaterialTheme.typography.labelLarge)
                            Row {
                                StatBlock(stringResource(R.string.contribution), ctx.money(c.value.contributionMinor, c.value.currency), Modifier.weight(1f))
                                StatBlock(stringResource(R.string.pot), ctx.money(rounds.firstOrNull()?.potMinor ?: 0, c.value.currency), Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(8.dp))
                            val turn = RemitCircle.myTurn(c.value, contribs)
                            Text(turn?.let { stringResource(R.string.your_turn_round, it, shortDate(rounds[it - 1].date)) } ?: stringResource(R.string.your_turn_unknown), style = MaterialTheme.typography.bodyMedium)
                            MoneyLabel(stringResource(R.string.net_so_far, ctx.money(RemitCircle.myNet(c.value, contribs), c.value.currency)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { SelectField(stringResource(R.string.post_to_account), account, b.activeAccounts.map { Choice(it.state.id, it.value.name) }, { account = it }, allowNone = true, noneLabel = stringResource(R.string.dont_post)) }
                items(rounds, key = { it.n }) { r ->
                    PennyCard {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.round_n, r.n) + " · " + shortDate(r.date), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                if (r.isMine) Pill(stringResource(R.string.your_turn))
                            }
                            Text(stringResource(R.string.recipient_x, r.recipient.ifBlank { stringResource(R.string.not_decided) }), style = MaterialTheme.typography.bodySmall)
                            c.value.members.sortedBy { it.turn }.forEach { m ->
                                app.penny.ui.kit.CheckRow(m.name in r.paidBy, { on ->
                                        if (on) vm.act(R.string.recorded) { recordCircle(it, id, CircleContribution(id, r.n, m.name, c.value.contributionMinor, it.today()), account) }
                                        else b.circleContributions.firstOrNull { it.value.circleId == id && it.value.round == r.n && it.value.memberName == m.name && !it.value.payout }?.let { e -> vm.act(R.string.deleted) { deleteCircleContribution(it, e.state.id) } }
                                    }, m.name + if (m.isMe) " (" + stringResource(R.string.you) + ")" else "")
                            }
                            if (contribs.none { it.round == r.n && it.payout }) {
                                Row {
                                    c.value.members.filter { c.value.payoutOrder != PayoutOrder.FIXED || it.name == r.recipient }.take(4).forEach { m ->
                                        TextButton(onClick = { vm.act(R.string.recorded) { recordCircle(it, id, CircleContribution(id, r.n, m.name, r.potMinor, it.today(), payout = true), account) } }) { Text(stringResource(R.string.payout_to, m.name)) }
                                    }
                                }
                            } else Text(stringResource(R.string.paid_out), style = MaterialTheme.typography.labelMedium, color = Penny.status.positive)
                        }
                    }
                }
                item {
                    app.penny.ui.kit.ActionStrip {
                        app.penny.ui.kit.StripAction(destructive = false, onClick = { vm.act(R.string.updated) { saveCircle(it, id, c.value.copy(closed = !c.value.closed)) } }) { Text(stringResource(if (c.value.closed) R.string.reopen else R.string.close_circle)) }
                        app.penny.ui.kit.StripAction(destructive = true, onClick = { vm.act(R.string.deleted, after = onBack) { deleteCircle(it, id) } }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
}

@Composable
fun CircleEditScreen(id: String, onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val existing = b.circles.firstOrNull { it.state.id == id }?.value
        val me = stringResource(R.string.you)
        var name by rememberSaveable { mutableStateOf(existing?.name ?: ctx.pack.circleNames.firstOrNull().orEmpty()) }
        var local by rememberSaveable { mutableStateOf(existing?.localName ?: ctx.pack.circleNames.firstOrNull().orEmpty()) }
        var currency by rememberSaveable { mutableStateOf(existing?.currency ?: ctx.base) }
        var contribution by rememberSaveable { mutableStateOf(existing?.let { ctx.fmt.editable(it.contributionMinor, it.currency) }.orEmpty()) }
        var fee by rememberSaveable { mutableStateOf(existing?.feeMinor?.takeIf { it > 0 }?.let { ctx.fmt.editable(it, currency) }.orEmpty()) }
        var freq by rememberSaveable { mutableStateOf(existing?.schedule?.freq ?: Freq.MONTHLY) }
        var start by rememberSaveable { mutableStateOf((existing?.schedule?.start ?: b.today()).toString()) }
        var order by rememberSaveable { mutableStateOf(existing?.payoutOrder ?: PayoutOrder.FIXED) }
        var members by rememberSaveable { mutableStateOf(existing?.members?.sortedBy { it.turn }?.map { (if (it.isMe) "*" else "") + it.name } ?: listOf("*$me")) }
        var newMember by rememberSaveable { mutableStateOf("") }
        var err by rememberSaveable { mutableStateOf<String?>(null) }
        val req = stringResource(R.string.err_circle)
        val feeErr = stringResource(R.string.err_fee_too_high)
        val dupErr = stringResource(R.string.err_member_dup)
        val lockedErr = stringResource(R.string.err_currency_locked)
        PennyScreen(stringResource(if (existing == null) R.string.new_circle else R.string.edit_circle), onBack, large = false) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                TextInput(name, { name = it; err = null }, stringResource(R.string.field_name), error = err)
                if (ctx.pack.circleNames.isNotEmpty()) SelectField(stringResource(R.string.local_name), local, ctx.pack.circleNames.map { Choice(it, it) }, { local = it ?: "" }, allowNone = true)
                SelectField(stringResource(R.string.field_currency), currency, app.penny.ui.kit.currencyChoices(listOf(ctx.base)), { it?.let { c -> currency = c } })
                AmountInput(contribution, { contribution = it }, currency, ctx.fmt, stringResource(R.string.contribution_each))
                AmountInput(fee, { fee = it }, currency, ctx.fmt, stringResource(R.string.fee_per_round))
                Segmented(listOf(Freq.WEEKLY, Freq.MONTHLY).map { Choice(it, stringResource(freqLabel2(it))) }, freq, { freq = it })
                DateInput(stringResource(R.string.first_round), runCatching { java.time.LocalDate.parse(start) }.getOrNull(), { start = it.toString() }, quick = false, today = b.today())
                Segmented(PayoutOrder.entries.map { Choice(it, stringResource(when (it) { PayoutOrder.FIXED -> R.string.order_fixed; PayoutOrder.LOTTERY -> R.string.order_lottery; PayoutOrder.BID -> R.string.order_bid })) }, order, { order = it })
                SectionHeader(stringResource(R.string.members_in_turn_order))
                members.forEachIndexed { i, m ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text("${i + 1}. " + m.removePrefix("*") + if (m.startsWith("*")) " (" + me + ")" else "", modifier = Modifier.weight(1f))
                        if (i > 0) TextButton(onClick = { members = members.toMutableList().also { l -> l[i] = l[i - 1].also { l[i - 1] = l[i] } } }) { Text("↑") }
                        IconButton(onClick = { members = members.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Outlined.Close, stringResource(R.string.remove_x, m.removePrefix("*"))) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextInput(newMember, { newMember = it }, stringResource(R.string.add_member_by_name), Modifier.weight(1f))
                    TextButton(onClick = {
                        val n = newMember.trim()
                        // Contributions are tracked by member name, so two members cannot share one.
                        if (n.isNotEmpty() && members.none { it.removePrefix("*").equals(n, ignoreCase = true) }) { members = members + n; newMember = ""; err = null }
                        else if (n.isNotEmpty()) err = dupErr
                    }) { Text(stringResource(R.string.add)) }
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.save), {
                    val amt = parseAmount(contribution, currency, ctx.fmt)
                    if (name.isBlank() || amt == null || members.size < 2) { err = req; return@PrimaryButton }
                    val feeMinor = if (fee.isBlank()) 0L else parseAmount(fee, currency, ctx.fmt, allowZero = true)
                    // The pot is everyone's contribution less the fee; a fee that eats the pot would pay out a negative amount.
                    if (feeMinor == null || feeMinor >= amt * members.size) { err = feeErr; return@PrimaryButton }
                    if (existing != null && currency != existing.currency && b.circleContributions.any { it.value.circleId == id }) { err = lockedErr; return@PrimaryButton }
                    val s = java.time.LocalDate.parse(start)
                    val circle = (existing ?: Circle(name, members = emptyList(), contributionMinor = amt, currency = currency, schedule = Recurrence.monthly(s))).copy(
                        name = name.trim(), localName = local, currency = currency, contributionMinor = amt, feeMinor = feeMinor,
                        schedule = if (freq == Freq.WEEKLY) Recurrence.weekly(s) else Recurrence.monthly(s), payoutOrder = order,
                        members = members.mapIndexed { i, m -> CircleMember(m.removePrefix("*"), turn = i + 1, isMe = m.startsWith("*")) },
                    )
                    vm.act(R.string.saved, after = onBack) { saveCircle(it, if (existing == null) null else id, circle) }
                }, Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

private fun freqLabel2(f: Freq) = if (f == Freq.WEEKLY) R.string.freq_weekly else R.string.freq_monthly
