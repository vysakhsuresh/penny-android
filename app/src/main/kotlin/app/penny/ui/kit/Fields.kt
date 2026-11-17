package app.penny.ui.kit

import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.focus.onFocusChanged
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
    androidx.compose.material3.TextField(
        value = buf.tfv, onValueChange = { onChange(buf.edit(it)) }, label = { Text(label) },
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = singleLine, isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        placeholder = placeholder?.let { { Text(it) } },
        shape = PennyFieldShape, colors = pennyFieldColors(error != null),
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
        androidx.compose.material3.TextField(
            state = state,
            label = { Text(label) },
            prefix = { Text(app.penny.core.money.CurrencySymbols.symbol(currency, fmt.spec.localeTag) + " ") },
            // A prefilled zero ("0", "0.00") is a placeholder: clear it on focus so typing 5000 gives 5000, not 0.005000.
            modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                val s = state.text.toString()
                if (f.isFocused && s.isNotEmpty() && s.all { it == '0' || it == '.' || it == ',' || it == ' ' || it == ' ' || it == ' ' })
                    state.edit { replace(0, length, "") }
            },
            lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
            isError = error != null,
            inputTransformation = {
                val s = asCharSequence().toString()
                if ('*' in s || '/' in s) replace(0, length, s.replace('*', '×').replace('/', '÷'))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            textStyle = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = app.penny.ui.theme.TabularNumbers),
            shape = PennyFieldShape, colors = pennyFieldColors(error != null),
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

/**
 * Parses an amount field; null when empty or invalid. [allowZero] accepts 0 (resetting an assignment);
 * [allowNegative] accepts a leading minus (opening balances of overdrafts and cards in credit).
 */
fun parseAmount(text: String, currency: String, fmt: MoneyFmt, allowZero: Boolean = false, allowNegative: Boolean = false): Long? = runCatching {
    val t = text.trim()
    val neg = allowNegative && (t.startsWith("-") || t.startsWith("−"))
    val body = if (neg) t.drop(1) else t
    val unit = CurrencyUnit.of(currency)
    when (val r = AmountCalculator.evaluate(body, unit, fmt.spec)) {
        is AmountResult.Ok -> if (neg) -r.minor else r.minor
        is AmountResult.Invalid -> if (allowZero && r.error == app.penny.core.money.AmountError.NOT_POSITIVE &&
            body.isNotEmpty() && body.all { it == '0' || it == fmt.spec.decimalSeparator || it == '.' }) 0L else null
    }
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
    onAddNew: ((String) -> Unit)? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = choices.firstOrNull { it.value == selected }?.label ?: if (allowNone) noneLabel else ""
    val openLabel = stringResource(R.string.choose_x, label)
    androidx.compose.material3.TextField(
        value = current, onValueChange = {}, readOnly = true, label = { Text(label) }, isError = error != null,
        supportingText = error?.let { { Text(it) } },
        trailingIcon = { Icon(Icons.Outlined.ExpandMore, null) },
        // Disabled for typing; the whole field is one button that opens the sheet, announced with its value.
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(role = Role.Button) { open = true }.semantics(mergeDescendants = true) { contentDescription = "$openLabel: $current" },
        shape = PennyFieldShape,
        enabled = false,
        colors = pennyFieldColors(error = error != null),
    )
    if (open) {
        OptionSheet(label, selected, choices, allowNone, noneLabel, searchable || onAddNew != null, onDismiss = { open = false },
            onAddNew = onAddNew?.let { add -> { name: String -> add(name); open = false } }) { onSelect(it); open = false }
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
    onAddNew: ((String) -> Unit)? = null,
    onPick: (T?) -> Unit,
) {
    var q by rememberSaveable { mutableStateOf("") }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = choices.size > 12)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (searchable) {
            androidx.compose.material3.TextField(
                q, { q = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text(stringResource(R.string.search)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = PennyFieldShape,
                colors = pennyFieldColors(),
            )
        }
        val filtered = choices.filter { q.isBlank() || it.label.contains(q, ignoreCase = true) || it.supporting?.contains(q, ignoreCase = true) == true }
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            // Can't find it? Type a name and add it right here, without leaving the form.
            if (onAddNew != null) {
                val name = q.trim()
                val exists = choices.any { it.label.split(" › ").last().equals(name, ignoreCase = true) }
                if (name.isEmpty()) item {
                    Text(stringResource(R.string.type_to_add_new), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                } else if (!exists) item {
                    ListRow(
                        stringResource(R.string.add_x, name), onClick = { onAddNew(name) },
                        leading = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
            if (allowNone && q.isBlank()) item { ChoiceRow(noneLabel, null, selected == null) { onPick(null) } }
            items(filtered, key = { it.value.hashCode().toString() + it.label }) { c ->
                // Paths like "Food and groceries › Groceries" read as a tree: the leaf name, indented under its parent.
                // While searching, rows are not grouped, so the parent shows as a small line for context.
                val parts = c.label.split(" › ")
                val leaf = parts.last()
                val parent = parts.dropLast(1).joinToString(" › ").takeIf { it.isNotEmpty() }
                ChoiceRow(
                    leaf, if (q.isBlank()) c.supporting else listOfNotNull(parent, c.supporting).joinToString(" · ").ifEmpty { null },
                    c.value == selected, indent = if (q.isBlank()) (parts.size - 1) * 24 else 0,
                    description = c.label,
                ) { onPick(c.value) }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, supporting: String?, selected: Boolean, indent: Int = 0, description: String? = null, onClick: () -> Unit) {
    ListRow(
        title = label, subtitle = supporting, onClick = onClick,
        modifier = Modifier.padding(start = indent.dp).then(if (description != null && description != label) Modifier.semantics { contentDescription = description } else Modifier),
        trailing = if (selected) ({ Icon(Icons.Filled.Check, stringResource(R.string.selected), tint = MaterialTheme.colorScheme.primary) }) else null,
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DateInput(label: String, date: LocalDate?, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier, quick: Boolean = true, today: LocalDate = LocalDate.now()) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        androidx.compose.material3.TextField(
            value = date?.let { shortDate(it) }.orEmpty(), onValueChange = {}, readOnly = true, enabled = false, label = { Text(label) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(role = Role.Button) { open = true },
            shape = PennyFieldShape,
            colors = pennyFieldColors(error = false),
        )
        if (quick) {
            // Wraps in narrow places (dialogs, 200% text) instead of squeezing the last chip into a vertical strip.
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        androidx.compose.material3.TextField(
            value = time?.toString() ?: stringResource(R.string.field_no_time), onValueChange = {}, readOnly = true, enabled = false, label = { Text(label) },
            modifier = Modifier.weight(1f).clickable(role = Role.Button) { open = true }, shape = PennyFieldShape,
            colors = pennyFieldColors(error = false),
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

/**
 * Segmented choice as a sliding pill: a soft track with one raised thumb that glides to the selection. No borders or
 * dividers between options (the bordered Material row read like a table). Same API, so every screen picks it up.
 */
@Composable
fun <T> Segmented(options: List<Choice<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    if (options.isEmpty()) return
    val index = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val reduce = app.penny.ui.theme.LocalPenny.current.reduceMotion
    val dark = app.penny.ui.theme.LocalPenny.current.isDark
    val track = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerHighest
    // Neutral pill; the selected label carries the accent (in dark), so selection is clear without a purple slab.
    val thumb = if (dark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLowest
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(track)
            .padding(4.dp)
            .selectableGroup(),
    ) {
        val segment = maxWidth / options.size
        val offset by androidx.compose.animation.core.animateDpAsState(
            segment * index,
            animationSpec = if (reduce) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 500f),
            label = "segment",
        )
        // The thumb: a raised pill under the chosen option.
        Box(
            Modifier.matchParentSize().wrapContentWidth(Alignment.Start).width(segment).offset(x = offset)
                .shadow(if (dark) 0.dp else 2.dp, androidx.compose.foundation.shape.RoundedCornerShape(50), clip = false)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(thumb),
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, o ->
                val on = i == index
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .selectable(selected = on, role = androidx.compose.ui.semantics.Role.Tab, onClick = { onSelect(o.value) })
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Labels shrink a little to fit their slot (large text) instead of being cut to "Pay per…".
                    val style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium,
                        color = if (on) (if (dark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.text.BasicText(
                        o.label, style = style, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize),
                    )
                }
            }
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
                SoftField(typed, { typed = it }, label = { Text(stringResource(R.string.type_to_confirm, expected)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed.trim() == expected.trim()) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** All ISO 4217 currencies known to the platform, most relevant first. */
/**
 * Currencies in circulation: those some locale uses. getAvailableCurrencies() also lists retired ones (Andorran
 * peseta, Deutsche Mark...), which only clutter the picker. Codes already in use in the book stay listed via `first`.
 */
private val circulatingCurrencies: List<String> by lazy {
    java.util.Locale.getAvailableLocales()
        .mapNotNull { l -> if (l.country.isEmpty()) null else runCatching { java.util.Currency.getInstance(l)?.currencyCode }.getOrNull() }
        .filter { it != "XXX" }
        .distinct().sorted()
}

fun currencyChoices(first: List<String>): List<Choice<String>> {
    val all = circulatingCurrencies
    val ordered = first.distinct() + all.filter { it !in first }
    return ordered.map { code ->
        Choice(code, code, runCatching { java.util.Currency.getInstance(code).displayName }.getOrNull())
    }
}

/** A percentage typed by a person, as basis points; null unless it is a plain number within [min]..[max] percent. */
fun parsePercentBp(s: String, min: Int = 0, max: Int = 100): Int? = runCatching {
    val v = java.math.BigDecimal(s.trim().replace(',', '.'))
    if (v < java.math.BigDecimal(min) || v > java.math.BigDecimal(max)) null else v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_EVEN).intValueExact()
}.getOrNull()

/** A day of the month, 1 to 31; null otherwise. */
fun parseDayOfMonth(s: String): Int? = s.trim().toIntOrNull()?.takeIf { it in 1..31 }

/** A finite number of zero or more (weights, distances); null otherwise, so NaN and Infinity never reach storage. */
fun parseNonNegative(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

/**
 * Field look for the whole app: a soft tinted surface with rounded corners and no outline, so a screen reads like a
 * card being filled in, not a form. A thin accent line appears only on the field being typed in, and red on error.
 */
@Composable
fun pennyFieldColors(error: Boolean = false): androidx.compose.material3.TextFieldColors {
    val cs = MaterialTheme.colorScheme
    val fill = cs.surfaceContainerHigh
    return androidx.compose.material3.TextFieldDefaults.colors(
        focusedContainerColor = fill, unfocusedContainerColor = fill, disabledContainerColor = fill, errorContainerColor = fill,
        focusedIndicatorColor = cs.primary, unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = if (error) cs.error else Color.Transparent, errorIndicatorColor = cs.error,
        disabledTextColor = cs.onSurface, disabledLabelColor = cs.onSurfaceVariant, unfocusedLabelColor = cs.onSurfaceVariant,
        disabledTrailingIconColor = cs.onSurfaceVariant, disabledLeadingIconColor = cs.onSurfaceVariant,
        disabledSupportingTextColor = cs.error, disabledPlaceholderColor = cs.onSurfaceVariant,
    )
}

val PennyFieldShape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)

/** Drop-in for OutlinedTextField with the soft look ([pennyFieldColors]). */
@Composable
fun SoftField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    shape: androidx.compose.ui.graphics.Shape = PennyFieldShape,
    prefix: (@Composable () -> Unit)? = null,
    textStyle: androidx.compose.ui.text.TextStyle = androidx.compose.material3.LocalTextStyle.current,
) = androidx.compose.material3.TextField(
    value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, readOnly = readOnly, label = label,
    placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon, supportingText = supportingText, isError = isError,
    visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
    singleLine = singleLine, minLines = minLines, maxLines = maxLines, shape = shape, colors = pennyFieldColors(isError),
    prefix = prefix, textStyle = textStyle,
)
