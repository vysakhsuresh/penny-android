package app.penny.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.core.money.AmountCalculator
import app.penny.core.money.AmountResult
import app.penny.core.money.CurrencyUnit
import app.penny.ui.common.MoneyFmt
import app.penny.ui.common.shortDate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

data class Choice<T>(val value: T, val label: String, val supporting: String? = null)

/**
 * Keeps the field's text locally so every keystroke lands at once, even when the caller's state arrives a frame
 * later (StateFlow in a ViewModel). Without this, fast typing, IME suggestions and voice typing drop characters.
 * A value coming from outside (a chip, a reset, a loaded record) replaces the buffer; echoes of what we sent do not.
 */
private class TextBuffer(initial: String) {
    var tfv by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    val sent = ArrayDeque<String>()

    fun sync(external: String) {
        if (external == tfv.text || external in sent) return
        tfv = TextFieldValue(external, TextRange(external.length))
        sent.clear()
    }

    fun edit(v: TextFieldValue, transform: (String) -> String = { it }): String {
        val text = transform(v.text)
        tfv = if (text == v.text) v else v.copy(text = text, selection = TextRange(v.selection.end.coerceAtMost(text.length)))
        sent.addLast(text)
        while (sent.size > 32) sent.removeFirst()
        return text
    }
}

@Composable
private fun rememberTextBuffer(value: String): TextBuffer {
    val b = remember { TextBuffer(value) }
    b.sync(value)
    return b
}

@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
) {
    val buf = rememberTextBuffer(value)
    OutlinedTextField(
        value = buf.tfv, onValueChange = { onChange(buf.edit(it)) }, label = { Text(label) },
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = singleLine, isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        placeholder = placeholder?.let { { Text(it) } },
        shape = MaterialTheme.shapes.medium,
    )
}

/**
 * TX-09 calculator field: accepts + − × ÷ % and parentheses with the locale separator; shows the evaluated result
 * before save; validates minor units for the currency (TX-08).
 */
@Composable
fun AmountInput(
    value: String,
    onChange: (String) -> Unit,
    currency: String,
    fmt: MoneyFmt,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    allowZero: Boolean = false,
) {
    val unit = runCatching { CurrencyUnit.of(currency) }.getOrNull()
    val preview = if (value.isBlank() || unit == null) null else runCatching { AmountCalculator.evaluate(value, unit, fmt.spec) }.getOrNull()
    // Both strings are resolved on every pass: a composable call made only in some branches changes the group
    // structure as the text goes empty -> non-empty, and that re-created the field and dropped the keyboard.
    val shown = (preview as? AmountResult.Ok)?.let { fmt.format(it.minor, currency) }.orEmpty()
    val roundedText = stringResource(R.string.amount_rounded, shown)
    val resultText = stringResource(R.string.amount_result, shown)
    val supporting = (preview as? AmountResult.Ok)?.let { p -> if (p.rounded) roundedText else if (p.isExpression) resultText else null }
    // State-based field (TextFieldState): the text lives in the field, so keystrokes never wait for the caller's
    // state. The value-based field lost focus and closed the keyboard right after the first digit on a Moto g84.
    val state = androidx.compose.foundation.text.input.rememberTextFieldState(value)
    val sent = remember { ArrayDeque<String>() }
    val latestOnChange by androidx.compose.runtime.rememberUpdatedState(onChange)
    // Outside -> field: a chip, a reset or a loaded record replaces the text; echoes of our own edits do not.
    androidx.compose.runtime.LaunchedEffect(value) {
        if (value != state.text.toString() && value !in sent) { state.edit { replace(0, length, value); selection = androidx.compose.ui.text.TextRange(value.length) }; sent.clear() }
    }
    // Field -> outside.
    androidx.compose.runtime.LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.text.toString() }.collect { s ->
            sent.addLast(s); while (sent.size > 32) sent.removeFirst()
            latestOnChange(s)
        }
    }
    fun insert(op: String) = state.edit { replace(selection.min, selection.max, op) }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            state = state,
            label = { Text(label) },
            prefix = { Text(app.penny.core.money.CurrencySymbols.symbol(currency, fmt.spec.localeTag) + " ") },
            modifier = Modifier.fillMaxWidth(),
            lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
            isError = error != null,
            inputTransformation = {
                val s = asCharSequence().toString()
                if ('*' in s || '/' in s) replace(0, length, s.replace('*', '×').replace('/', '÷'))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            textStyle = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = app.penny.ui.theme.TabularNumbers),
            shape = MaterialTheme.shapes.medium,
        )
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                error ?: supporting.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // Number keyboards have no + × ÷ keys, so the calculator operators are offered here.
            listOf("+" to R.string.op_plus, "−" to R.string.op_minus, "×" to R.string.op_times, "÷" to R.string.op_divide).forEach { (op, desc) ->
                val d = stringResource(desc)
                TextButton(onClick = { insert(op) }, modifier = Modifier.clearAndSetSemantics { contentDescription = d; role = Role.Button; onClick { insert(op); true } }) {
                    Text(op, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
    if (allowZero) Unit
}

/** Parses an amount field; null when empty or invalid. */
fun parseAmount(text: String, currency: String, fmt: MoneyFmt): Long? = runCatching {
    (AmountCalculator.evaluate(text, CurrencyUnit.of(currency), fmt.spec) as? AmountResult.Ok)?.minor
}.getOrNull()

/** A tappable field that opens a searchable bottom sheet of choices (accounts, categories, currencies...). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SelectField(
    label: String,
    selected: T?,
    choices: List<Choice<T>>,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
    allowNone: Boolean = false,
    noneLabel: String = stringResource(R.string.none),
    error: String? = null,
    searchable: Boolean = choices.size > 8,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = choices.firstOrNull { it.value == selected }?.label ?: if (allowNone) noneLabel else ""
    val openLabel = stringResource(R.string.choose_x, label)
    OutlinedTextField(
        value = current, onValueChange = {}, readOnly = true, label = { Text(label) }, isError = error != null,
        supportingText = error?.let { { Text(it) } },
        trailingIcon = { Icon(Icons.Outlined.ExpandMore, null) },
        // Disabled for typing; the whole field is one button that opens the sheet, announced with its value.
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(role = Role.Button) { open = true }.semantics(mergeDescendants = true) { contentDescription = "$openLabel: $current" },
        shape = MaterialTheme.shapes.medium,
        enabled = false,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface, disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledBorderColor = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant, disabledSupportingTextColor = MaterialTheme.colorScheme.error,
        ),
    )
    if (open) {
        OptionSheet(label, selected, choices, allowNone, noneLabel, searchable, onDismiss = { open = false }) { onSelect(it); open = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> OptionSheet(
    title: String,
    selected: T?,
    choices: List<Choice<T>>,
    allowNone: Boolean,
    noneLabel: String,
    searchable: Boolean,
    onDismiss: () -> Unit,
    onPick: (T?) -> Unit,
) {
    var q by rememberSaveable { mutableStateOf("") }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = choices.size > 12)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (searchable) {
            OutlinedTextField(
                q, { q = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text(stringResource(R.string.search)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = MaterialTheme.shapes.medium,
            )
        }
        val filtered = choices.filter { q.isBlank() || it.label.contains(q, ignoreCase = true) || it.supporting?.contains(q, ignoreCase = true) == true }
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            if (allowNone && q.isBlank()) item { ChoiceRow(noneLabel, null, selected == null) { onPick(null) } }
            items(filtered, key = { it.value.hashCode().toString() + it.label }) { c -> ChoiceRow(c.label, c.supporting, c.value == selected) { onPick(c.value) } }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, supporting: String?, selected: Boolean, onClick: () -> Unit) {
    ListRow(
        title = label, subtitle = supporting, onClick = onClick,
        trailing = if (selected) ({ Icon(Icons.Filled.Check, stringResource(R.string.selected), tint = MaterialTheme.colorScheme.primary) }) else null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateInput(label: String, date: LocalDate?, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier, quick: Boolean = true, today: LocalDate = LocalDate.now()) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        OutlinedTextField(
            value = date?.let { shortDate(it) }.orEmpty(), onValueChange = {}, readOnly = true, enabled = false, label = { Text(label) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(role = Role.Button) { open = true },
            shape = MaterialTheme.shapes.medium,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface, disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
        if (quick) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(date == today, { onChange(today) }, label = { Text(stringResource(R.string.today)) })
                FilterChip(date == today.minusDays(1), { onChange(today.minusDays(1)) }, label = { Text(stringResource(R.string.yesterday)) })
                FilterChip(false, { open = true }, label = { Text(stringResource(R.string.pick_date)) })
            }
        }
    }
    if (open) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (date ?: today).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(stringResource(R.string.done)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(st) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeInput(label: String, time: LocalTime?, onChange: (LocalTime?) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = time?.toString() ?: stringResource(R.string.field_no_time), onValueChange = {}, readOnly = true, enabled = false, label = { Text(label) },
            modifier = Modifier.weight(1f).clickable(role = Role.Button) { open = true }, shape = MaterialTheme.shapes.medium,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface, disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
        if (time != null) TextButton(onClick = { onChange(null) }) { Text(stringResource(R.string.clear_time)) }
    }
    if (open) {
        val st = rememberTimePickerState(time?.hour ?: LocalTime.now().hour, time?.minute ?: LocalTime.now().minute)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { onChange(LocalTime.of(st.hour, st.minute)); open = false }) { Text(stringResource(R.string.done)) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
            text = { TimePicker(st) },
        )
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, enabled: Boolean = true) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, null, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Segmented(options: List<Choice<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o.value == selected, onClick = { onSelect(o.value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                // No check icon: it squeezes labels on small screens and at large text; the fill shows the choice.
                icon = {},
            ) { Text(o.label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipFlow(options: List<Choice<T>>, selected: Set<T>, onToggle: (T) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(o.value in selected, { onToggle(o.value) }, label = { Text(o.label) }) }
    }
}

/** UX-02: typed confirmation, only for deleting a profile or account. */
@Composable
fun TypedConfirmDialog(title: String, body: String, expected: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                OutlinedTextField(typed, { typed = it }, label = { Text(stringResource(R.string.type_to_confirm, expected)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed.trim() == expected.trim()) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** All ISO 4217 currencies known to the platform, most relevant first. */
fun currencyChoices(first: List<String>): List<Choice<String>> {
    val all = java.util.Currency.getAvailableCurrencies().map { it.currencyCode }.sorted()
    val ordered = first.distinct() + all.filter { it !in first }
    return ordered.map { code ->
        Choice(code, code, runCatching { java.util.Currency.getInstance(code).displayName }.getOrNull())
    }
}
