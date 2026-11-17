package app.penny.ui.tx

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.core.model.Direction
import app.penny.core.model.TxStatus
import app.penny.core.model.TxType
import app.penny.domain.BookLoad
import app.penny.domain.ops.allTagNames
import app.penny.ui.common.typeLabel
import app.penny.ui.kit.AmountInput
import app.penny.ui.kit.Choice
import app.penny.ui.kit.DateInput
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.LoadingState
import app.penny.ui.kit.MoneyLabel
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.Pill
import app.penny.ui.kit.PrimaryButton
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.Segmented
import app.penny.ui.kit.SelectField
import app.penny.ui.kit.SwitchRow
import app.penny.ui.kit.TextInput
import app.penny.ui.kit.TimeInput
import app.penny.ui.kit.currencyChoices
import app.penny.ui.theme.Penny
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionFormScreen(
    vm: TransactionFormViewModel,
    onBack: () -> Unit,
    onHistory: (String) -> Unit,
    onMakeRecurring: (String) -> Unit,
) {
    val formState by vm.form.collectAsStateWithLifecycle()
    val load by vm.load.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val dupes by vm.dupes.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    val ready = st as? BookLoad.Ready
    var more by rememberSaveable { mutableStateOf(vm.isEdit) }
    var templateDialog by rememberSaveable { mutableStateOf(false) }
    var unlockReconciled by rememberSaveable { mutableStateOf(false) }
    // Back with unsaved edits asks first; nothing typed is thrown away silently.
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val dirty = formState != null && vm.hasUnsavedChanges()
    val leave = { if (dirty) confirmLeave = true else onBack() }
    androidx.activity.compose.BackHandler(enabled = dirty) { confirmLeave = true }
    if (confirmLeave) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text(stringResource(R.string.discard_changes_title)) },
        text = { Text(stringResource(R.string.discard_changes_body)) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmLeave = false; onBack() }) { Text(stringResource(R.string.discard)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.keep_editing)) } },
    )

    PennyScreen(
        title = stringResource(if (vm.isEdit) R.string.edit_transaction else R.string.new_transaction),
        onBack = leave, large = false,
        actions = {
            if (vm.isEdit) {
                IconButton(onClick = { vm.txId?.let(onHistory) }) { Icon(Icons.Outlined.History, stringResource(R.string.history)) }
                IconButton(onClick = { vm.delete(onBack) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
            }
        },
    ) { pad ->
        val f = formState
        when {
            load == LoadState.MISSING -> EmptyState(stringResource(R.string.tx_not_found), stringResource(R.string.tx_not_found_body), Modifier.padding(pad), action = stringResource(R.string.back), onAction = onBack)
            f == null || ready == null -> LoadingState(Modifier.padding(pad))
            else -> {
                val b = ready.book
                val fmt = ready.fmt
                // Locked only when the saved record is reconciled; choosing Reconciled in this form must still allow Save.
                val reconciledLocked = vm.isEdit && vm.savedAsReconciled() && !unlockReconciled
                Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                    if (reconciledLocked) {
                        // TX-12: reconciled items are locked; editing asks to un-reconcile first.
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(stringResource(R.string.reconciled_locked), style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { unlockReconciled = true; vm.update { it.copy(status = TxStatus.CLEARED) } }) { Text(stringResource(R.string.unreconcile)) }
                        }
                    }
                    Segmented(
                        listOf(TxType.EXPENSE, TxType.INCOME, TxType.TRANSFER).map { Choice(it, stringResource(typeLabel(it))) },
                        // A refund, adjustment or opening balance is chosen in the row below; this row then shows none.
                        f.type,
                        { t -> vm.update { it.copy(type = t, categoryId = null) } },
                    )
                    if (more || f.type in listOf(TxType.REFUND, TxType.ADJUSTMENT, TxType.OPENING_BALANCE)) {
                        Segmented(
                            listOf(TxType.REFUND, TxType.ADJUSTMENT, TxType.OPENING_BALANCE).map { Choice(it, stringResource(typeLabel(it))) },
                            f.type, { t -> vm.update { it.copy(type = t, direction = if (t == TxType.ADJUSTMENT) it.direction ?: Direction.OUT else null) } },
                        )
                    }
                    AmountInput(f.amount, { s -> vm.update { it.copy(amount = s) } }, f.currency, fmt, stringResource(R.string.field_amount), error = errors["amount"])
                    // Typical amounts from the pack (TX-14 usual-amount chips).
                    // Shown for the whole new-transaction form, not only while blank: removing the row under a focused
                    // field on the first keystroke shifts the layout and drops the next keys typed quickly.
                    if (!vm.isEdit && ready.pack.typicalAmountChips.isNotEmpty()) {
                        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ready.pack.typicalAmountChips.forEach { a ->
                                SuggestionChip({ vm.update { it.copy(amount = a.toString()) } }, label = { Text(app.penny.core.money.CurrencySymbols.symbol(f.currency, fmt.spec.localeTag) + app.penny.core.money.MoneyFormatter.group(a.toString(), fmt.spec)) })
                            }
                        }
                    }
                    if (f.type == TxType.ADJUSTMENT) {
                        Segmented(listOf(Choice(Direction.IN, stringResource(R.string.direction_in)), Choice(Direction.OUT, stringResource(R.string.direction_out))), f.direction ?: Direction.OUT, { d -> vm.update { it.copy(direction = d) } })
                    }
                    val accountChoices = b.activeAccounts.map { Choice(it.state.id, it.value.name, it.value.currency) }
                    SelectField(stringResource(if (f.type == TxType.TRANSFER) R.string.field_from_account else R.string.field_account), f.accountId, accountChoices, { it?.let(vm::selectAccount) }, error = errors["account"])
                    if (f.type == TxType.TRANSFER) {
                        SelectField(stringResource(R.string.field_to_account), f.toAccountId, accountChoices.filter { it.value != f.accountId }, { id -> vm.update { it.copy(toAccountId = id) } }, error = errors["toAccount"])
                        val toCur = b.accountMap[f.toAccountId]?.value?.currency
                        if (toCur != null && toCur != f.currency) {
                            AmountInput(f.toAmount, { s -> vm.update { it.copy(toAmount = s) } }, toCur, fmt, stringResource(R.string.field_to_amount), error = errors["toAmount"])
                            val sent = app.penny.ui.kit.parseAmount(f.amount, f.currency, fmt)
                            val got = app.penny.ui.kit.parseAmount(f.toAmount, toCur, fmt)
                            // Shown in the direction that reads naturally (1 USD = 95 INR, not 1 INR = 0.0105 USD).
                            val rate = if (sent != null && got != null) app.penny.core.budget.FxTable.impliedRate(sent, f.currency, got, toCur) else null
                            val rateParts = rate?.takeIf { it.signum() > 0 }?.let { r ->
                                if (r >= java.math.BigDecimal.ONE) Triple(f.currency, r, toCur)
                                else Triple(toCur, java.math.BigDecimal.ONE.divide(r, 10, java.math.RoundingMode.HALF_EVEN), f.currency)
                            }
                            val rateText = stringResource(
                                R.string.implied_rate, rateParts?.first.orEmpty(),
                                rateParts?.second?.setScale(4, java.math.RoundingMode.HALF_EVEN)?.stripTrailingZeros()?.toPlainString().orEmpty(), rateParts?.third.orEmpty(),
                            )
                            Text(if (rateParts != null) rateText else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
                        }
                    } else if (f.splits.isEmpty()) {
                        SelectField(stringResource(R.string.field_category), f.categoryId, vm.categoryChoices(b, if (f.type == TxType.INCOME) TxType.INCOME else TxType.EXPENSE), { id -> vm.selectCategory(id) }, allowNone = true, noneLabel = stringResource(R.string.uncategorised),
                            onAddNew = { name -> vm.addCategory(name, f.type) { id -> vm.selectCategory(id) } })
                    }
                    PayeeField(vm, f.payee)
                    DateInput(stringResource(R.string.field_date), runCatching { LocalDate.parse(f.date) }.getOrNull(), { d -> vm.update { it.copy(date = d.toString()) } }, today = b.today())
                    if (runCatching { LocalDate.parse(f.date) }.getOrNull()?.isAfter(b.today()) == true) {
                        Pill(stringResource(R.string.future_scheduled), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), container = Penny.status.nearContainer, content = MaterialTheme.colorScheme.onSurface)
                    }
                    TimeInput(stringResource(R.string.field_time), f.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }, { t -> vm.update { it.copy(time = t?.toString()) } })
                    TextInput(f.note, { s -> vm.update { it.copy(note = s) } }, stringResource(R.string.field_note), singleLine = false)
                    TagsEditor(f.tags, b.allTagNames(), onPending = { vm.pendingTag = it }) { tags -> vm.update { it.copy(tags = tags) } }

                    if (!more) {
                        TextButton(onClick = { more = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.more_options)) }
                    } else {
                        if (f.type != TxType.TRANSFER) SplitsEditor(vm, f, ready, errors["splits"])
                        SectionHeader(stringResource(R.string.details))
                        SelectField(
                            stringResource(R.string.field_currency), f.currency,
                            currencyChoices(listOf(b.profile.baseCurrency) + b.accounts.map { it.value.currency }), { c -> c?.let { cur -> vm.update { it.copy(currency = cur) } } },
                        )
                        Segmented(
                            listOf(TxStatus.PENDING, TxStatus.CLEARED, TxStatus.RECONCILED).map { Choice(it, stringResource(when (it) { TxStatus.PENDING -> R.string.status_pending; TxStatus.CLEARED -> R.string.status_cleared; else -> R.string.status_reconciled })) },
                            f.status, { s -> vm.update { it.copy(status = s) } },
                        )
                        SelectField(stringResource(R.string.field_via), f.viaAccountId, b.activeAccounts.filter { it.state.id != f.accountId }.map { Choice(it.state.id, it.value.name) }, { id -> vm.update { it.copy(viaAccountId = id) } }, allowNone = true)
                        TextInput(f.place, { s -> vm.update { it.copy(place = s) } }, stringResource(R.string.field_place), supporting = stringResource(R.string.field_place_hint))
                        if (b.profile.business?.mileage == true) TextInput(f.distance, { s -> vm.update { it.copy(distance = s) } }, stringResource(R.string.field_distance), keyboard = KeyboardType.Decimal)
                        SwitchRow(stringResource(R.string.field_private), f.isPrivate, { v -> vm.update { it.copy(isPrivate = v) } }, subtitle = stringResource(R.string.field_private_body))
                        SwitchRow(stringResource(R.string.field_exclude_budget), f.excludeBudget, { v -> vm.update { it.copy(excludeBudget = v) } })
                        SwitchRow(stringResource(R.string.field_exclude_reports), f.excludeReports, { v -> vm.update { it.copy(excludeReports = v) } })
                        AttachmentsEditor(vm, f, ready)
                        Row(Modifier.padding(horizontal = 8.dp)) {
                            TextButton(onClick = { templateDialog = true }) { Icon(Icons.Outlined.BookmarkAdd, null); Spacer(Modifier.padding(4.dp)); Text(stringResource(R.string.save_as_template)) }
                            if (vm.isEdit) TextButton(onClick = { vm.txId?.let(onMakeRecurring) }) { Icon(Icons.Outlined.Repeat, null); Spacer(Modifier.padding(4.dp)); Text(stringResource(R.string.make_recurring)) }
                        }
                    }
                    if (errors.isNotEmpty()) {
                        errors.values.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(stringResource(R.string.save), { vm.save(onBack) }, Modifier.padding(horizontal = 16.dp), enabled = !reconciledLocked)
                }
            }
        }
    }

    dupes?.let { d ->
        AlertDialog(
            onDismissRequest = { vm.resolveDupes(false, onBack) },
            title = { Text(stringResource(R.string.dupe_title)) },
            text = { Text(stringResource(R.string.dupe_body)) },
            confirmButton = { TextButton(onClick = { vm.resolveDupes(true, onBack) }) { Text(stringResource(R.string.merge)) } },
            dismissButton = { TextButton(onClick = { vm.resolveDupes(false, onBack) }) { Text(stringResource(R.string.keep_both)) } },
        )
        @Suppress("UNUSED_EXPRESSION") d
    }
    if (templateDialog) {
        var name by rememberSaveable { mutableStateOf(formState?.payee.orEmpty()) }
        val templateDefault = stringResource(R.string.template_default_name)
        AlertDialog(
            onDismissRequest = { templateDialog = false },
            title = { Text(stringResource(R.string.save_as_template)) },
            text = { app.penny.ui.kit.SoftField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.saveTemplate(name.ifBlank { templateDefault }); templateDialog = false }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { templateDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PayeeField(vm: TransactionFormViewModel, payee: String) {
    TextInput(payee, { s -> vm.update { it.copy(payee = s) } }, stringResource(R.string.field_payee))
    val sugg = vm.payeeSuggestions(payee)
    if (sugg.isNotEmpty()) {
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sugg.forEach { s -> SuggestionChip({ vm.payeePicked(s) }, label = { Text(s) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsEditor(tags: List<String>, known: List<String>, onPending: (String) -> Unit = {}, onChange: (List<String>) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(input) { onPending(input) }
    fun commit() { val t = input.trim(); if (t.isNotEmpty()) onChange((tags + t).distinct()); input = "" }
    Column {
        app.penny.ui.kit.SoftField(
            input, { s ->
                if (s.endsWith(",") || s.endsWith("\n")) {
                    val t = s.trimEnd(',', '\n').trim()
                    if (t.isNotEmpty() && t !in tags) onChange(tags + t)
                    input = ""
                } else input = s
            },
            label = { Text(stringResource(R.string.field_tags)) }, supportingText = { Text(stringResource(R.string.field_tags_hint)) }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = MaterialTheme.shapes.medium,
            trailingIcon = { if (input.isNotBlank()) TextButton(onClick = { commit() }) { Text(stringResource(R.string.add)) } },
        )
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { t ->
                InputChip(true, { onChange(tags - t) }, label = { Text(t) }, trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.remove_x, t)) })
            }
            known.filter { it !in tags && (input.isBlank() || it.contains(input, true)) }.take(6).forEach { t ->
                SuggestionChip({ onChange(tags + t); input = "" }, label = { Text(t) })
            }
        }
    }
}

/** TX-07: splits by amount or percent with live remaining; each line has category, note and tags. */
@Composable
private fun SplitsEditor(vm: TransactionFormViewModel, f: TxForm, ready: BookLoad.Ready, error: String?) {
    val b = ready.book
    SectionHeader(stringResource(R.string.field_splits))
    if (f.splits.isNotEmpty()) {
        Segmented(listOf(Choice(false, stringResource(R.string.split_by_amount)), Choice(true, stringResource(R.string.split_by_percent))), f.splitByPercent, { p -> vm.update { it.copy(splitByPercent = p) } })
        val remaining = vm.splitRemaining(f, ready.fmt)
        if (remaining != null) {
            MoneyLabel(
                stringResource(R.string.split_remaining, ready.fmt.format(remaining, f.currency)),
                Modifier.padding(horizontal = 20.dp),
                color = if (remaining == 0L) Penny.status.positive else Penny.status.near, style = MaterialTheme.typography.labelLarge,
            )
        }
    }
    f.splits.forEachIndexed { i, s ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.split_n, i + 1), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 20.dp).weight(1f))
                IconButton(onClick = { vm.update { it.copy(splits = it.splits.filterIndexed { j, _ -> j != i }) } }) { Icon(Icons.Outlined.Close, stringResource(R.string.remove_split)) }
            }
            if (f.splitByPercent) TextInput(s.amount, { v -> vm.update { it.copy(splits = it.splits.mapIndexed { j, x -> if (j == i) x.copy(amount = v) else x }) } }, stringResource(R.string.percent), keyboard = KeyboardType.Decimal)
            else AmountInput(s.amount, { v -> vm.update { it.copy(splits = it.splits.mapIndexed { j, x -> if (j == i) x.copy(amount = v) else x }) } }, f.currency, ready.fmt, stringResource(R.string.field_amount))
            SelectField(stringResource(R.string.field_category), s.categoryId, vm.categoryChoices(b, if (f.type == TxType.INCOME) TxType.INCOME else TxType.EXPENSE), { c -> vm.update { it.copy(splits = it.splits.mapIndexed { j, x -> if (j == i) x.copy(categoryId = c) else x }) } }, allowNone = true,
                onAddNew = { name -> vm.addCategory(name, f.type) { c -> vm.update { it.copy(splits = it.splits.mapIndexed { j, x -> if (j == i) x.copy(categoryId = c) else x }) } } })
            TextInput(s.note, { v -> vm.update { it.copy(splits = it.splits.mapIndexed { j, x -> if (j == i) x.copy(note = v) else x }) } }, stringResource(R.string.field_note))
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
    OutlinedButton(
        onClick = {
            vm.update { cur ->
                val remaining = vm.splitRemaining(cur, ready.fmt)
                val first = if (cur.splits.isEmpty()) listOf(SplitForm(cur.amount, cur.categoryId)) else emptyList()
                cur.copy(splits = cur.splits + first + SplitForm(if (remaining != null && remaining > 0 && cur.splits.isNotEmpty() && !cur.splitByPercent) ready.fmt.editable(remaining, cur.currency) else ""), categoryId = null)
            }
        },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) { Text(stringResource(if (f.splits.isEmpty()) R.string.split_this else R.string.add_split)) }
}

/** TX-11: attach from camera roll, files or PDF; shows each with remove. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentsEditor(vm: TransactionFormViewModel, f: TxForm, ready: BookLoad.Ready) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::attach) }
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.field_attachments))
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        f.attachmentIds.forEach { id ->
            val a = ready.book.attachments.firstOrNull { it.state.id == id }?.value
            InputChip(true, { vm.removeAttachment(id) }, label = { Text(a?.fileName ?: context.getString(R.string.obj_attachment)) }, trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.remove_x, a?.fileName.orEmpty())) })
        }
        AssistChip({ picker.launch(arrayOf("image/*", "application/pdf")) }, label = { Text(stringResource(R.string.add_attachment)) }, leadingIcon = { Icon(Icons.Outlined.AttachFile, null) })
    }
}
