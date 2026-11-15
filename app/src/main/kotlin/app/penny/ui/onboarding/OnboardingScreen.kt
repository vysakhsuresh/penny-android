package app.penny.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.penny.R
import app.penny.core.money.AmountCalculator
import app.penny.core.money.AmountResult
import app.penny.core.money.CurrencyUnit
import app.penny.core.period.CycleRule
import app.penny.domain.PennyRepository
import app.penny.packs.RegionalPack
import app.penny.ui.common.MoneyFmt
import app.penny.ui.common.cycleLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val repo: PennyRepository) : ViewModel() {
    val packs: List<RegionalPack> = repo.packs.all()
    val devicePack: RegionalPack = repo.packs.forCountry(Locale.getDefault().country)

    fun cycleOptions(pack: RegionalPack): List<CycleRule> {
        val today = LocalDate.now()
        val all = listOf(
            pack.payCycleDefault,
            CycleRule.Monthly(1),
            CycleRule.SemiMonthly(15, 31),
            CycleRule.Weekly(DayOfWeek.MONDAY),
            CycleRule.Biweekly(today),
            CycleRule.FourWeekly(today),
            CycleRule.OnIncome(),
        )
        return all.distinctBy { it::class to (it as? CycleRule.Monthly)?.startDay }
    }

    fun finish(pack: RegionalPack, cycle: CycleRule, cashName: String, cashText: String, done: () -> Unit) {
        val minor = (AmountCalculator.evaluate(cashText, CurrencyUnit.of(pack.currency), MoneyFmt.of(pack).spec) as? AmountResult.Ok)?.minor ?: 0
        viewModelScope.launch {
            repo.completeOnboarding(pack, cycle, cashName, minor)
            done()
        }
    }
}

@Composable
fun OnboardingScreen(vm: OnboardingViewModel, onImport: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(0) }
    var packId by rememberSaveable { mutableStateOf(vm.devicePack.id) }
    var cycleIndex by rememberSaveable { mutableStateOf(0) }
    var cash by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    val pack = vm.packs.first { it.id == packId }
    val cycles = vm.cycleOptions(pack)
    val cashName = stringResource(R.string.ob_cash_name)

    fun finish() {
        if (busy) return
        busy = true
        vm.finish(pack, cycles.getOrElse(cycleIndex) { pack.payCycleDefault }, cashName, cash, onDone)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(stringResource(R.string.ob_step, step + 1, 3), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        val (title, body) = when (step) {
            0 -> R.string.ob_region_title to R.string.ob_region_body
            1 -> R.string.ob_cycle_title to R.string.ob_cycle_body
            else -> R.string.ob_account_title to R.string.ob_account_body
        }
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        Column(Modifier.weight(1f)) {
            when (step) {
                0 -> LazyColumn(Modifier.selectableGroup()) {
                    items(vm.packs, key = { it.id }) { p ->
                        OptionRow(
                            selected = p.id == packId,
                            label = p.name,
                            detail = "${p.currency} · ${MoneyFmt.of(p).format(123456789, p.currency)}",
                        ) { packId = p.id; cycleIndex = 0 }
                    }
                }
                1 -> LazyColumn(Modifier.selectableGroup()) {
                    items(cycles.size) { i ->
                        val label = cycleLabel(context, cycles[i])
                        OptionRow(
                            selected = i == cycleIndex,
                            label = if (i == 0) stringResource(R.string.cycle_default_suffix, label) else label,
                            detail = null,
                        ) { cycleIndex = i }
                    }
                }
                else -> Column {
                    OutlinedTextField(
                        value = cash,
                        onValueChange = { cash = it },
                        label = { Text(stringResource(R.string.field_amount)) },
                        prefix = { Text(pack.currency + " ") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(24.dp))
                    // OB-03 / OB-04: bring data from another app, or join a household, right after setup.
                    TextButton(onClick = {
                        if (!busy) { busy = true; vm.finish(pack, cycles.getOrElse(cycleIndex) { pack.payCycleDefault }, cashName, cash) { onDone(); onImport() } }
                    }) { Text(stringResource(R.string.ob_import)) }
                    Text(stringResource(R.string.ob_join_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (step < 2) step++ else finish() }, enabled = !busy) { Text(stringResource(R.string.skip)) }
            Button(onClick = { if (step < 2) step++ else finish() }, enabled = !busy) {
                Text(if (step < 2) stringResource(R.string.next) else stringResource(R.string.ob_finish, stringResource(R.string.brand_name)))
            }
        }
    }
}

@Composable
private fun OptionRow(selected: Boolean, label: String, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
