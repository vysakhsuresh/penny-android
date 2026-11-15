package app.penny.ui.tx

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.core.model.Direction
import app.penny.core.model.TxStatus
import app.penny.core.model.TxType
import app.penny.core.money.AmountResult
import app.penny.core.money.CurrencyUnit
import app.penny.ui.common.shortDate
import app.penny.ui.common.typeLabel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionFormScreen(vm: TransactionFormViewModel, onBack: () -> Unit, onHistory: (String) -> Unit) {
    val form by vm.form.collectAsStateWithLifecycle()
    val ctx by vm.ctx.collectAsStateWithLifecycle()
    val load by vm.load.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (vm.isEdit) R.string.edit_transaction else R.string.new_transaction)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (vm.isEdit && load == LoadState.READY) {
                        TextButton(onClick = { onHistory(vm.txId!!) }) { Text(stringResource(R.string.history)) }
                        IconButton(onClick = { vm.delete(onBack) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                    }
                },
            )
        },
    ) { padding ->
        val f = form
        val c = ctx
        if (load == LoadState.MISSING) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { Text(stringResource(R.string.tx_not_found)) }
            return@Scaffold
        }
        if (f == null || c == null) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.field_type), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TxType.EXPENSE, TxType.INCOME, TxType.TRANSFER, TxType.REFUND, TxType.ADJUSTMENT).forEach { t ->
                    FilterChip(
                        selected = f.type == t,
                        onClick = { vm.update { it.copy(type = t, categoryId = if (it.type == TxType.INCOME || t == TxType.INCOME) null else it.categoryId) } },
                        label = { Text(stringResource(typeLabel(t))) },
                    )
                }
            }

            val preview = vm.preview(f, c)
            OutlinedTextField(
                value = f.amount,
                onValueChange = { v -> vm.update { it.copy(amount = v) } },
                label = { Text(stringResource(R.string.field_amount)) },
                prefix = { Text(f.currency + " ") },
                placeholder = { Text(stringResource(R.string.field_amount_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                singleLine = true,
                isError = preview is AmountResult.Invalid,
                supportingText = {
                    val text = when (preview) {
                        is AmountResult.Ok -> if (preview.isExpression) {
                            stringResource(
                                if (preview.rounded) R.string.amount_rounded else R.string.amount_result,
                                c.fmt.format(preview.minor, f.currency),
                            )
                        } else {
                            null
                        }
                        is AmountResult.Invalid -> vm.amountErrorText(preview.error, f.currency)
                        null -> null
                    }
                    if (text != null) Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Picker(
                label = stringResource(R.string.field_account),
                selected = c.accounts.firstOrNull { it.state.id == f.accountId }?.value?.name.orEmpty(),
                options = c.accounts.map { Option(it.state.id, "${it.value.name} · ${it.value.currency}") },
                onSelect = vm::selectAccount,
            )
            OutlinedTextField(
                value = f.currency,
                onValueChange = { v -> vm.update { it.copy(currency = v.uppercase().filter(Char::isLetter).take(3)) } },
                label = { Text(stringResource(R.string.field_currency)) },
                isError = runCatching { CurrencyUnit.of(f.currency) }.isFailure,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (f.type == TxType.TRANSFER) {
                Picker(
                    label = stringResource(R.string.field_to_account),
                    selected = c.accounts.firstOrNull { it.state.id == f.toAccountId }?.value?.name.orEmpty(),
                    options = c.accounts.filter { it.state.id != f.accountId }.map { Option(it.state.id, "${it.value.name} · ${it.value.currency}") },
                    onSelect = { id -> vm.update { it.copy(toAccountId = id) } },
                )
                val toCur = c.accounts.firstOrNull { it.state.id == f.toAccountId }?.value?.currency
                if (toCur != null && toCur != f.currency) {
                    OutlinedTextField(
                        value = f.toAmount,
                        onValueChange = { v -> vm.update { it.copy(toAmount = v) } },
                        label = { Text(stringResource(R.string.field_to_amount)) },
                        prefix = { Text("$toCur ") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                val options = remember(c, f.type) { vm.categoryOptions(c, f.type) }
                Picker(
                    label = stringResource(R.string.field_category),
                    selected = options.firstOrNull { it.id == f.categoryId }?.label ?: stringResource(R.string.uncategorised),
                    options = listOf(Option("", stringResource(R.string.uncategorised))) + options,
                    onSelect = { id -> vm.update { it.copy(categoryId = id.ifEmpty { null }) } },
                )
            }

            if (f.type == TxType.ADJUSTMENT) {
                Text(stringResource(R.string.field_direction), style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(Direction.IN to R.string.direction_in, Direction.OUT to R.string.direction_out).forEachIndexed { i, (d, label) ->
                        SegmentedButton(
                            selected = f.direction == d,
                            onClick = { vm.update { it.copy(direction = d) } },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(stringResource(label)) }
                    }
                }
            }

            OutlinedTextField(
                value = f.payee,
                onValueChange = { v -> vm.update { it.copy(payee = v) } },
                label = { Text(stringResource(R.string.field_payee)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            DateTimeRow(f.date, f.time, today = LocalDate.now(), onDate = { d -> vm.update { it.copy(date = d.toString()) } }) { t ->
                vm.update { it.copy(time = t?.toString()) }
            }
            if (LocalDate.parse(f.date).isAfter(LocalDate.now())) {
                Text(stringResource(R.string.future_scheduled), style = MaterialTheme.typography.bodySmall)
            }

            OutlinedTextField(
                value = f.note,
                onValueChange = { v -> vm.update { it.copy(note = v) } },
                label = { Text(stringResource(R.string.field_note)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = f.tags,
                onValueChange = { v -> vm.update { it.copy(tags = v) } },
                label = { Text(stringResource(R.string.field_tags)) },
                supportingText = { Text(stringResource(R.string.field_tags_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.field_status), style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val statuses = listOf(TxStatus.PENDING to R.string.status_pending, TxStatus.CLEARED to R.string.status_cleared,
                    TxStatus.RECONCILED to R.string.status_reconciled)
                statuses.forEachIndexed { i, (s, label) ->
                    SegmentedButton(
                        selected = f.status == s,
                        onClick = { vm.update { it.copy(status = s) } },
                        shape = SegmentedButtonDefaults.itemShape(i, statuses.size),
                    ) { Text(stringResource(label)) }
                }
            }

            SwitchRow(stringResource(R.string.field_private), stringResource(R.string.field_private_body), f.isPrivate) { v ->
                vm.update { it.copy(isPrivate = v) }
            }
            SwitchRow(stringResource(R.string.field_exclude_budget), null, f.excludeBudget) { v -> vm.update { it.copy(excludeBudget = v) } }
            SwitchRow(stringResource(R.string.field_exclude_reports), null, f.excludeReports) { v -> vm.update { it.copy(excludeReports = v) } }

            errors.forEach { e ->
                Text(e, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }
            Button(onClick = { vm.save(onBack) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@Composable
private fun Picker(label: String, selected: String, options: List<Option>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable(onClickLabel = label) { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o.label) }, onClick = { open = false; onSelect(o.id) })
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DateTimeRow(date: String, time: String?, today: LocalDate, onDate: (LocalDate) -> Unit, onTime: (LocalTime?) -> Unit) {
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    val d = LocalDate.parse(date)
    val context = LocalContext.current
    Text(stringResource(R.string.field_date), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = d == today, onClick = { onDate(today) }, label = { Text(stringResource(R.string.today)) })
        FilterChip(selected = d == today.minusDays(1), onClick = { onDate(today.minusDays(1)) }, label = { Text(stringResource(R.string.yesterday)) })
        FilterChip(selected = d != today && d != today.minusDays(1), onClick = { showDate = true }, label = { Text(shortDate(d)) })
        FilterChip(
            selected = time != null,
            onClick = { showTime = true },
            label = { Text(time ?: context.getString(R.string.field_no_time)) },
        )
    }
    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    showDate = false
                }) { Text(stringResource(R.string.done)) }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
    if (showTime) {
        val t = time?.let(LocalTime::parse) ?: LocalTime.now()
        val state = rememberTimePickerState(t.hour, t.minute, android.text.format.DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text(stringResource(R.string.pick_time)) },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = { onTime(LocalTime.of(state.hour, state.minute)); showTime = false }) { Text(stringResource(R.string.done)) }
            },
            dismissButton = {
                TextButton(onClick = { onTime(null); showTime = false }) { Text(stringResource(R.string.clear_time)) }
            },
        )
    }
}
