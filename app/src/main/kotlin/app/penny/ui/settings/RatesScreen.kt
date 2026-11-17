package app.penny.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.core.budget.FxTable
import app.penny.core.model.FxRate
import app.penny.ui.common.WithBook
import app.penny.ui.common.shortDate
import app.penny.ui.kit.DateInput
import app.penny.ui.kit.ListRow
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.SelectField
import app.penny.ui.kit.TextInput
import app.penny.ui.kit.currencyChoices
import kotlinx.coroutines.launch

/** AC-04: reference rates (cached offline) and manual overrides with undo. */
@Composable
fun RatesScreen(onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val scope = rememberCoroutineScope()
        val cached by vm.repo.rates.rates.collectAsStateWithLifecycle()
        var adding by rememberSaveable { mutableStateOf(false) }
        var status by rememberSaveable { mutableStateOf<String?>(null) }
        val used = (b.accounts.map { it.value.currency } + b.txList.map { it.currency }).distinct().filter { it != ctx.base }
        val table = FxTable(cached + b.fxRates.map { it.value })
        PennyScreen(stringResource(R.string.exchange_rates), onBack) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item {
                    Text(stringResource(R.string.rates_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    Text(vm.repo.rates.lastDate?.let { stringResource(R.string.rates_updated, shortDate(it)) } ?: stringResource(R.string.rates_never), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { scope.launch { status = vm.context.getString(if (vm.repo.rates.refresh(true)) R.string.rates_refreshed else R.string.rates_offline) } }) { Text(stringResource(R.string.refresh_now)) }
                        TextButton(onClick = { adding = true }) { Text(stringResource(R.string.add_manual_rate)) }
                    }
                }
                if (used.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.your_currencies)) }
                    items(used) { c ->
                        val r = table.rate(c, ctx.base, b.today())
                        ListRow("1 $c", trailingText = r?.let { "${it.setScale(4, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()} ${ctx.base}" } ?: stringResource(R.string.no_rate), divider = true)
                    }
                }
                item { SectionHeader(stringResource(R.string.manual_rates)) }
                if (b.fxRates.isEmpty()) item { Text(stringResource(R.string.no_manual_rates), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
                items(b.fxRates.sortedByDescending { it.value.date }, key = { it.state.id }) { r ->
                    ListRow(
                        "1 ${r.value.from} = ${r.value.rate} ${r.value.to}", subtitle = shortDate(r.value.date),
                        trailing = { app.penny.ui.kit.DangerTextButton(stringResource(R.string.delete)) { vm.act(R.string.deleted) { fxRates.delete(r.state.id) } } }, divider = true,
                    )
                }
            }
        }
        if (adding) {
            var from by rememberSaveable { mutableStateOf(used.firstOrNull() ?: "USD") }
            var rate by rememberSaveable { mutableStateOf("") }
            var date by rememberSaveable { mutableStateOf(b.today().toString()) }
            AlertDialog(
                onDismissRequest = { adding = false }, title = { Text(stringResource(R.string.add_manual_rate)) },
                text = {
                    Column {
                        SelectField(stringResource(R.string.from), from, currencyChoices(used), { it?.let { c -> from = c } })
                        TextInput(rate, { rate = it }, stringResource(R.string.rate_in_x, ctx.base), keyboard = KeyboardType.Decimal)
                        DateInput(stringResource(R.string.field_date), runCatching { java.time.LocalDate.parse(date) }.getOrNull(), { date = it.toString() }, quick = false, today = b.today())
                    }
                },
                confirmButton = {
                    val v = rate.replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                    TextButton(enabled = v != null && from != ctx.base, onClick = {
                        vm.act(R.string.saved) { fxRates.create(newId(), it.profileId, FxRate(from, ctx.base, java.time.LocalDate.parse(date), v!!.toPlainString())) }
                        adding = false
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}
