package app.penny.ui.onboarding

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.penny.R
import app.penny.core.Brand
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repo: PennyRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    val packs: List<RegionalPack> = repo.packs.all()

    /**
     * Where the person lives, best guess: the SIM's country, then the network's, then the phone language's region.
     * A phone set to English (US) in Dubai suggested the United States; the SIM knows better. No permission needed.
     */
    val devicePack: RegionalPack = run {
        val tm = context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val candidates = listOfNotNull(
            runCatching { tm?.simCountryIso }.getOrNull(),
            runCatching { tm?.networkCountryIso }.getOrNull(),
            Locale.getDefault().country,
        ).filter { it.length == 2 }
        candidates.asSequence().map { repo.packs.forCountry(it) }.firstOrNull { it.id != app.penny.packs.PackParser.GENERIC_ID }
            ?: repo.packs.forCountry(Locale.getDefault().country)
    }

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

    /** Busy and the outcome live here, not in the composition: rotation during seeding must neither strand nor repeat it. */
    val busy = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** null while working; "main" or "import" when the profile exists and the screen should leave. */
    val done = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun finish(pack: RegionalPack, cycle: CycleRule, cashName: String, cashText: String, sample: Boolean = false, target: String = "main") {
        if (!busy.compareAndSet(false, true)) return
        val minor = (AmountCalculator.evaluate(cashText, CurrencyUnit.of(pack.currency), MoneyFmt.of(pack).spec) as? AmountResult.Ok)?.minor ?: 0
        viewModelScope.launch {
            try {
                val pid = repo.completeOnboarding(pack, cycle, cashName, minor)
                // "Explore with sample data": a realistic month to try every screen; delete the profile later to start fresh.
                if (sample) app.penny.domain.SampleData.seed(repo, pid)
                done.value = target
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("Penny", "onboarding failed", e)
                busy.value = false
            }
        }
    }
}

/** OB-01: welcome, then region, pay cycle and cash — every step skippable with defaults from the device locale. */
@Composable
fun OnboardingScreen(vm: OnboardingViewModel, onImport: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(0) }
    var packId by rememberSaveable { mutableStateOf(vm.devicePack.id) }
    var cycleIndex by rememberSaveable { mutableStateOf(0) }
    // OB-02: a monthly cycle can start on payday (the 25th, the last day), not only on the 1st.
    var monthDay by rememberSaveable { mutableStateOf<Int?>(null) }
    var cash by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    val finished by vm.done.collectAsState()
    LaunchedEffect(finished) { when (finished) { "main" -> onDone(); "import" -> { onDone(); onImport() } } }
    val pack = vm.packs.first { it.id == packId }
    val cycles = vm.cycleOptions(pack)
    val cashName = stringResource(R.string.ob_cash_name)

    fun finish(sample: Boolean = false, target: String = "main") {
        val chosen = cycles.getOrElse(cycleIndex) { pack.payCycleDefault }.let { c -> if (c is CycleRule.Monthly && monthDay != null) CycleRule.Monthly(monthDay!!) else c }
        vm.finish(pack, chosen, cashName, cash, sample, target)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
        if (step > 0) {
            LinearProgressIndicator(progress = { step / 3f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.ob_step, step, 3), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
        }
        AnimatedContent(step, modifier = Modifier.weight(1f), label = "ob") { s ->
            when (s) {
                0 -> Welcome()
                1 -> Column {
                    Header(R.string.ob_region_title, R.string.ob_region_body)
                    OutlinedTextField(
                        query, { query = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text(stringResource(R.string.search)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = MaterialTheme.shapes.extraLarge,
                    )
                    // The device's region first, then everything else alphabetically.
                    val ordered = listOf(vm.devicePack) + vm.packs.filter { it.id != vm.devicePack.id }
                    LazyColumn(Modifier.selectableGroup()) {
                        items(ordered.filter { query.isBlank() || it.name.contains(query, true) || it.currency.contains(query, true) }, key = { it.id }) { p ->
                            OptionRow(p.id == packId, p.name, "${p.currency} · ${MoneyFmt.of(p).format(123456789, p.currency)}") { packId = p.id; cycleIndex = 0 }
                        }
                    }
                }
                2 -> Column {
                    Header(R.string.ob_cycle_title, R.string.ob_cycle_body)
                    LazyColumn(Modifier.selectableGroup()) {
                        items(cycles.size) { i ->
                            val label = cycleLabel(context, cycles[i])
                            OptionRow(i == cycleIndex, if (i == 0) stringResource(R.string.cycle_default_suffix, label) else label, null) { cycleIndex = i }
                        }
                    }
                    (cycles.getOrNull(cycleIndex) as? CycleRule.Monthly)?.let { m ->
                        Spacer(Modifier.height(8.dp))
                        app.penny.ui.kit.SelectField(
                            stringResource(R.string.cycle_start_day), monthDay ?: m.startDay,
                            (1..28).map { app.penny.ui.kit.Choice(it, it.toString()) } + app.penny.ui.kit.Choice(app.penny.core.period.LAST_DAY_OF_MONTH, stringResource(R.string.last_day)),
                            { it?.let { d -> monthDay = d } },
                        )
                    }
                }
                else -> Column {
                    Header(R.string.ob_account_title, R.string.ob_account_body)
                    OutlinedTextField(
                        value = cash, onValueChange = { cash = it }, label = { Text(stringResource(R.string.field_amount)) },
                        prefix = { Text(app.penny.core.money.CurrencySymbols.symbol(pack.currency, pack.primaryLocale) + " ") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                        modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.headlineSmall, shape = MaterialTheme.shapes.medium,
                    )
                    Spacer(Modifier.height(24.dp))
                    // OB-03 / OB-04: bring data from another app, or join a household, right after setup.
                    OutlinedButton(onClick = { finish(target = "import") }, enabled = !busy) { Text(stringResource(R.string.ob_import)) }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.ob_join_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (step == 0) {
            Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.ob_get_started), style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = { finish(sample = true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ob_sample)) }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (step > 1) step-- else step = 0 }, enabled = !busy) { Text(stringResource(R.string.back)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (step < 3) step++ else finish() }, enabled = !busy) { Text(stringResource(R.string.skip)) }
                    Button(onClick = { if (step < 3) step++ else finish() }, enabled = !busy, modifier = Modifier.heightIn(min = 52.dp)) {
                        Text(if (step < 3) stringResource(R.string.next) else stringResource(R.string.ob_finish, stringResource(R.string.brand_name)))
                    }
                }
            }
        }
    }
}

@Composable
private fun Welcome() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        // The app icon itself (plum with the gold coin), so the first screen matches what is on the home screen.
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).semantics { contentDescription = app.penny.core.Brand.NAME }) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.35f; scaleY = 1.35f })
        }
        Spacer(Modifier.height(28.dp))
        Text(Brand.NAME, style = MaterialTheme.typography.displayMedium)
        Text(Brand.TAGLINE, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Point(Icons.Outlined.Savings, R.string.ob_point_safe)
        Point(Icons.Outlined.Public, R.string.ob_point_world)
        Point(Icons.Outlined.Lock, R.string.ob_point_private)
        Point(Icons.Outlined.CheckCircle, R.string.ob_point_free)
    }
}

@Composable
private fun Point(icon: androidx.compose.ui.graphics.vector.ImageVector, text: Int) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Header(title: Int, body: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(6.dp))
    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun OptionRow(selected: Boolean, label: String, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(MaterialTheme.shapes.medium)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
