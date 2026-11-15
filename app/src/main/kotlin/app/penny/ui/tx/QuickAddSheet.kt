package app.penny.ui.tx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.core.model.CategoryKind
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.core.money.AmountCalculator
import app.penny.core.money.AmountResult
import app.penny.core.money.CurrencyUnit
import app.penny.domain.Book
import app.penny.domain.ops.fromTemplate
import app.penny.domain.ops.saveTransaction
import app.penny.ui.common.WithBook
import app.penny.ui.common.categoryIcon
import app.penny.ui.common.typeLabel
import app.penny.ui.kit.Choice
import app.penny.ui.kit.MoneyLabel
import app.penny.ui.kit.OptionSheet
import app.penny.ui.theme.LocalPenny
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.exp

/** TX-14: six categories weighted by recency and time of day; falls back to the most common defaults. */
fun Book.smartCategories(now: LocalTime, n: Int = 6): List<String> {
    val today = today()
    val hourBucket = now.hour / 4
    val score = mutableMapOf<String, Double>()
    txList.filter { it.type == TxType.EXPENSE && it.categoryId != null && it.date.isAfter(today.minusDays(120)) }.forEach { t ->
        val age = ChronoUnit.DAYS.between(t.date, today).toDouble()
        val tod = if (t.time != null && t.time!!.hour / 4 == hourBucket) 1.5 else 1.0
        score[t.categoryId!!] = (score[t.categoryId!!] ?: 0.0) + exp(-age / 30.0) * tod
    }
    val picked = score.entries.sortedByDescending { it.value }.map { it.key }.filter { categoryMap[it]?.value?.archived == false }
    val defaults = listOf("food.groc", "eat.rest", "trans.ride", "shop", "util", "eat.cafe", "health.pharm", "fun").map { "$profileId:$it" }.filter { categoryMap.containsKey(it) }
    return (picked + defaults).distinct().take(n)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(onDismiss: () -> Unit, onMore: (TxType) -> Unit, onVoice: () -> Unit, onScan: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        WithBook { ctx, vm ->
            val b = ctx.book
            val haptic = LocalHapticFeedback.current
            val haptics = LocalPenny.current.haptics
            var type by rememberSaveable { mutableStateOf(if (b.profile.defaultTxType == TxType.INCOME) TxType.INCOME else TxType.EXPENSE) }
            var amount by rememberSaveable { mutableStateOf("") }
            var category by rememberSaveable { mutableStateOf<String?>(null) }
            var account by rememberSaveable { mutableStateOf(b.profile.defaultAccountId?.takeIf { b.accountMap[it]?.value?.archived == false } ?: b.activeAccounts.firstOrNull()?.state?.id) }
            var yesterday by rememberSaveable { mutableStateOf(false) }
            var pickAccount by remember { mutableStateOf(false) }
            var pickCategory by remember { mutableStateOf(false) }
            val currency = b.profile.travelCurrency ?: b.accountMap[account]?.value?.currency ?: ctx.base
            val unit = runCatching { CurrencyUnit.of(currency) }.getOrNull()
            val result = if (amount.isBlank() || unit == null) null else AmountCalculator.evaluate(amount, unit, ctx.fmt.spec)
            val ok = result as? AmountResult.Ok
            val smart = remember(b, type) {
                if (type == TxType.INCOME) b.categoriesOf(CategoryKind.INCOME).take(6).map { it.state.id } else b.smartCategories(LocalTime.now(b.zone))
            }
            fun tick() { if (haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            fun key(k: String) {
                tick()
                amount = when (k) {
                    "⌫" -> amount.dropLast(1)
                    "dec" -> if (amount.takeLastWhile { it.isDigit() || it == ctx.fmt.spec.decimalSeparator }.contains(ctx.fmt.spec.decimalSeparator)) amount else amount + ctx.fmt.spec.decimalSeparator
                    else -> amount + k
                }
            }
            fun save() {
                val minor = ok?.minor ?: return
                val acct = account ?: return
                val tx = Transaction(
                    type = type, amountMinor = minor, currency = currency, accountId = acct, categoryId = category,
                    date = if (yesterday) b.today().minusDays(1) else b.today(), time = LocalTime.now(b.zone).withSecond(0).withNano(0),
                    tz = b.profile.timezone, source = "quick",
                )
                vm.act(R.string.added_tx) { saveTransaction(it, null, tx).group }
                onDismiss()
            }

            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(TxType.EXPENSE, TxType.INCOME).forEach { t ->
                        FilterChip(type == t, { type = t; category = null }, label = { Text(stringResource(typeLabel(t))) }, modifier = Modifier.padding(end = 8.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { onDismiss(); onVoice() }) { Icon(Icons.Outlined.Mic, stringResource(R.string.voice_add)) }
                    IconButton(onClick = { onDismiss(); onScan() }) { Icon(Icons.Outlined.CameraAlt, stringResource(R.string.scan_receipt)) }
                }
                // Amount display: the evaluated result is shown under the expression (TX-09).
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
                    MoneyLabel(
                        if (amount.isEmpty()) ctx.money(0, currency) else "${app.penny.core.money.CurrencySymbols.symbol(currency, ctx.fmt.spec.localeTag)} $amount",
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.semantics { contentDescription = amount.ifEmpty { "0" } },
                    )
                    if (ok != null && ok.isExpression) MoneyLabel("= " + ctx.money(ok.minor, currency), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (result is AmountResult.Invalid && amount.isNotEmpty() && !amount.last().let { it in "+−-×÷(" }) {
                        Text(stringResource(R.string.err_amount_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (amount.isEmpty() && ctx.pack.typicalAmountChips.isNotEmpty()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ctx.pack.typicalAmountChips) { a -> AssistChip({ tick(); amount = a.toString() }, label = { Text(ctx.money(a * (unit?.let { u -> Math.pow(10.0, u.minorUnits.toDouble()).toLong() } ?: 1), currency)) }) }
                    }
                }
                // Six smart categories (TX-14) plus "All".
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    smart.forEach { id ->
                        val c = b.categoryMap[id]?.value ?: return@forEach
                        FilterChip(category == id, { tick(); category = if (category == id) null else id }, label = { Text(c.name) },
                            leadingIcon = categoryIcon(c.key)?.let { ic -> { Icon(ic, null, Modifier.height(18.dp)) } })
                    }
                    AssistChip({ pickCategory = true }, label = { Text(if (category != null && category !in smart) b.categoryName(category) else stringResource(R.string.all_categories)) })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ pickAccount = true }, label = { Text(b.accountName(account).ifEmpty { stringResource(R.string.field_account) }) })
                    FilterChip(yesterday, { yesterday = !yesterday }, label = { Text(stringResource(if (yesterday) R.string.yesterday else R.string.today)) })
                }
                // Templates and favourites (TX-16).
                val favs = b.templates.filter { it.value.favourite }.take(8)
                if (favs.isNotEmpty() && amount.isEmpty()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(favs, key = { it.state.id }) { t ->
                            AssistChip({
                                val tx = b.fromTemplate(t.value)
                                if ((t.value.amountMinor ?: 0) > 0 && tx.accountId.isNotEmpty()) {
                                    vm.act(R.string.added_tx) { saveTransaction(it, null, tx).group }; onDismiss()
                                } else {
                                    amount = t.value.amountMinor?.let { ctx.fmt.editable(it, t.value.currency) }.orEmpty(); category = t.value.categoryId; t.value.accountId?.let { account = it }
                                }
                            }, label = { Text(t.value.name) }, leadingIcon = { Icon(Icons.Outlined.Star, null, Modifier.height(18.dp)) })
                        }
                    }
                }
                Keypad(ctx.fmt.spec.decimalSeparator, ::key)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onDismiss(); onMore(type) }) { Text(stringResource(R.string.more)) }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = ::save, enabled = ok != null && account != null, modifier = Modifier.heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.save), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (pickAccount) OptionSheet(stringResource(R.string.field_account), account, b.activeAccounts.map { Choice(it.state.id, it.value.name, it.value.currency) }, false, "", false, { pickAccount = false }) { account = it ?: account; pickAccount = false }
            if (pickCategory) OptionSheet(stringResource(R.string.field_category), category, b.categoriesOf(if (type == TxType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE).map { Choice(it.state.id, b.categoryName(it.state.id)) }, true, stringResource(R.string.uncategorised), true, { pickCategory = false }) { category = it; pickCategory = false }
        }
    }
}

/** Calculator keypad: digits, locale decimal separator, + − × ÷, backspace. 56 dp keys. */
@Composable
private fun Keypad(decimal: Char, onKey: (String) -> Unit) {
    val rows = listOf(listOf("7", "8", "9", "÷"), listOf("4", "5", "6", "×"), listOf("1", "2", "3", "−"), listOf("dec", "0", "⌫", "+"))
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.forEach { k ->
                    val op = k in listOf("÷", "×", "−", "+")
                    Surface(
                        onClick = { onKey(k) }, shape = MaterialTheme.shapes.medium,
                        color = if (op) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            when (k) {
                                "⌫" -> Icon(Icons.AutoMirrored.Outlined.Backspace, stringResource(R.string.backspace))
                                "dec" -> Text(decimal.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                else -> Text(k, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}
