package app.penny.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.Routes
import app.penny.core.model.BusinessSettings
import app.penny.core.model.Profile
import app.penny.core.model.ProfileKind
import app.penny.core.model.TxType
import app.penny.core.model.WatchTarget
import app.penny.core.model.Watchlist
import app.penny.core.money.Grouping
import app.penny.core.period.CycleRule
import app.penny.core.period.LAST_DAY_OF_MONTH
import app.penny.domain.ThemeMode
import app.penny.ui.common.WithBook
import app.penny.ui.common.cycleLabel
import app.penny.ui.kit.AmountInput
import app.penny.ui.kit.Avatar
import app.penny.ui.kit.Choice
import app.penny.ui.kit.DateInput
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.ListRow
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.PrimaryButton
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.Segmented
import app.penny.ui.kit.SelectField
import app.penny.ui.kit.SwitchRow
import app.penny.ui.kit.TextInput
import app.penny.ui.kit.TypedConfirmDialog
import app.penny.ui.kit.currencyChoices
import app.penny.ui.kit.parseAmount
import app.penny.ui.lock.PinHasher
import app.penny.ui.lock.canUseBiometrics
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Composable
private fun Entry(title: String, icon: ImageVector, subtitle: String? = null, onClick: () -> Unit) =
    ListRow(title, subtitle = subtitle, leading = { Avatar(title, title, icon = icon, size = 36.dp) }, onClick = onClick, divider = true)

/** Settings hub (ST-01…ST-12): everything is at most two taps from Home (UX-03). */
@Composable
fun SettingsScreen(onBack: () -> Unit, go: (String) -> Unit) {
    WithBook { ctx, _ ->
        PennyScreen(stringResource(R.string.settings), onBack) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item {
                    PennyCard(onClick = { go(Routes.PROFILES) }) {
                        ListRow(ctx.book.profile.name, subtitle = stringResource(R.string.switch_profile), leading = { Avatar(ctx.book.profile.name, ctx.book.profileId, size = 44.dp) })
                    }
                }
                item { SectionHeader(stringResource(R.string.set_book)) }
                item {
                    PennyCard {
                        Entry(stringResource(R.string.profile_settings), Icons.Outlined.Person, stringResource(R.string.profile_settings_sub)) { go(Routes.PROFILE_SETTINGS) }
                        Entry(stringResource(R.string.accounts), Icons.Outlined.AccountBalance) { go(Routes.ACCOUNTS) }
                        Entry(stringResource(R.string.categories), Icons.Outlined.Category) { go(Routes.CATEGORIES) }
                        Entry(stringResource(R.string.field_tags), Icons.Outlined.Sell) { go(Routes.TAGS) }
                        Entry(stringResource(R.string.payees), Icons.Outlined.Storefront) { go(Routes.PAYEES) }
                        Entry(stringResource(R.string.rules), Icons.Outlined.Rule) { go(Routes.RULES) }
                        Entry(stringResource(R.string.templates), Icons.Outlined.Star) { go(Routes.TEMPLATES) }
                    }
                }
                item { SectionHeader(stringResource(R.string.app_section)) }
                item {
                    PennyCard {
                        Entry(stringResource(R.string.appearance), Icons.Outlined.Palette, stringResource(R.string.appearance_sub)) { go(Routes.APPEARANCE) }
                        Entry(stringResource(R.string.alerts), Icons.Outlined.NotificationsNone, stringResource(R.string.alerts_sub)) { go(Routes.ALERT_SETTINGS) }
                        Entry(stringResource(R.string.capture), Icons.Outlined.TouchApp, stringResource(R.string.capture_sub)) { go(Routes.CAPTURE_SETTINGS) }
                        Entry(stringResource(R.string.security), Icons.Outlined.Lock, stringResource(R.string.security_sub)) { go(Routes.SECURITY) }
                        Entry(stringResource(R.string.language), Icons.Outlined.Language, stringResource(R.string.language_sub)) { go(Routes.PROFILE_SETTINGS) }
                    }
                }
                item { SectionHeader(stringResource(R.string.sharing_and_sync)) }
                item {
                    PennyCard {
                        Entry(stringResource(R.string.spaces), Icons.Outlined.Groups, stringResource(R.string.spaces_sub)) { go("settings/spaces") }
                        Entry(stringResource(R.string.sync_and_banks), Icons.Outlined.CloudSync, stringResource(R.string.sync_sub)) { go(Routes.SYNC) }
                        Entry(stringResource(R.string.plus), Icons.Outlined.WorkspacePremium, stringResource(R.string.plus_sub)) { go(Routes.PLUS) }
                    }
                }
                item { SectionHeader(stringResource(R.string.your_data)) }
                item {
                    PennyCard {
                        Entry(stringResource(R.string.data_backup), Icons.Outlined.Storage, stringResource(R.string.data_sub)) { go(Routes.DATA) }
                        Entry(stringResource(R.string.global_history), Icons.Outlined.History) { go(Routes.GLOBAL_HISTORY) }
                        Entry(stringResource(R.string.trash_title), Icons.Outlined.DeleteOutline) { go(Routes.TRASH) }
                    }
                }
                item { SectionHeader(stringResource(R.string.support_section)) }
                item {
                    PennyCard {
                        Entry(stringResource(R.string.get_help), Icons.AutoMirrored.Outlined.Help) { go(Routes.HELP) }
                        Entry(stringResource(R.string.guides), Icons.AutoMirrored.Outlined.LibraryBooks) { go(Routes.guides()) }
                        Entry(stringResource(R.string.buy_coffee), Icons.Outlined.Coffee, stringResource(R.string.buy_coffee_sub)) { go(Routes.COFFEE) }
                        Entry(stringResource(R.string.about_us), Icons.Outlined.Info) { go(Routes.ABOUT) }
                    }
                }
            }
        }
    }
}

/** ST-01 + OB-02 + US-04: every profile setting, reversible; region switch keeps data. Delete profile (typed). */
@Composable
fun ProfileSettingsScreen(onBack: () -> Unit, onDeleted: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val p = b.profile
        val context = LocalContext.current
        var deleting by rememberSaveable { mutableStateOf(false) }
        var rename by rememberSaveable { mutableStateOf(false) }
        fun save(np: Profile, msg: Int = R.string.updated) { vm.act(msg) { profiles.update(it.profileId, np) } }
        PennyScreen(stringResource(R.string.profile_settings), onBack) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {
                ListRow(p.name, subtitle = stringResource(R.string.rename), leading = { Avatar(p.name, b.profileId) }, onClick = { rename = true })
                SectionHeader(stringResource(R.string.settings_region))
                SelectField(stringResource(R.string.settings_region), p.regionPack, vm.repo.packs.all().map { Choice(it.id, it.name, it.currency) }, { id ->
                    id?.let { vm.act(messageText = context.getString(R.string.region_changed, vm.repo.packs.get(it).name)) { book -> switchRegion(book.profileId, it) } }
                })
                Text(stringResource(R.string.settings_region_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                SelectField(stringResource(R.string.base_currency), p.baseCurrency, currencyChoices(listOf(ctx.pack.currency)), { it?.let { c -> save(p.copy(baseCurrency = c)) } })
                SelectField(stringResource(R.string.timezone), p.timezone, ZoneId.getAvailableZoneIds().sorted().map { Choice(it, it.replace('_', ' ')) }, { it?.let { z -> save(p.copy(timezone = z)) } })
                SectionHeader(stringResource(R.string.settings_cycle))
                CycleEditor(p.cycle, b.today()) { c -> save(p.copy(cycle = c), R.string.cycle_changed) }
                Text(stringResource(R.string.cycle_change_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                SelectField(stringResource(R.string.first_day_of_week), p.firstDayOfWeek, DayOfWeek.entries.map { Choice(it, it.getDisplayName(TextStyle.FULL, Locale.getDefault())) }, { it?.let { d -> save(p.copy(firstDayOfWeek = d)) } })
                SelectField(stringResource(R.string.fiscal_year_start), p.fiscalYearStart, listOf("01-01", "04-01", "04-06", "07-01", "10-01").map { Choice(it, it) }, { it?.let { f -> save(p.copy(fiscalYearStart = f)) } })
                SectionHeader(stringResource(R.string.numbers_and_dates))
                SelectField(stringResource(R.string.number_format), p.groupingOverride, listOf(Choice(Grouping.STANDARD, "1,234,567"), Choice(Grouping.INDIAN, "12,34,567"), Choice(Grouping.NONE, "1234567")), { save(p.copy(groupingOverride = it)) }, allowNone = true, noneLabel = stringResource(R.string.follow_region))
                SwitchRow(stringResource(R.string.show_currency_code), p.showCurrencyCode, { save(p.copy(showCurrencyCode = it)) })
                SelectField(stringResource(R.string.default_account), p.defaultAccountId, b.activeAccounts.map { Choice(it.state.id, it.value.name) }, { save(p.copy(defaultAccountId = it)) })
                SelectField(stringResource(R.string.default_type), p.defaultTxType, listOf(TxType.EXPENSE, TxType.INCOME).map { Choice(it, stringResource(app.penny.ui.common.typeLabel(it))) }, { it?.let { t -> save(p.copy(defaultTxType = t)) } })
                SectionHeader(stringResource(R.string.budgeting))
                SwitchRow(stringResource(R.string.zero_based), p.zeroBased, { save(p.copy(zeroBased = it)) }, subtitle = stringResource(R.string.zero_based_hint))
                SelectField(stringResource(R.string.travel_mode), p.travelCurrency, currencyChoices(listOf(p.baseCurrency)), { save(p.copy(travelCurrency = it)) }, allowNone = true, noneLabel = stringResource(R.string.off))
                Text(stringResource(R.string.travel_mode_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                SectionHeader(stringResource(R.string.business_book))
                SwitchRow(stringResource(R.string.business_book), p.business != null, { on -> save(p.copy(business = if (on) BusinessSettings() else null, kind = if (on) ProfileKind.BUSINESS else ProfileKind.PERSONAL)) }, subtitle = stringResource(R.string.business_book_hint))
                p.business?.let { bs ->
                    SwitchRow(stringResource(R.string.mileage), bs.mileage, { save(p.copy(business = bs.copy(mileage = it))) })
                    TextInput((bs.vatRateBp / 100.0).toString(), { v -> v.replace(',', '.').toBigDecimalOrNull()?.let { save(p.copy(business = bs.copy(vatRateBp = it.multiply(java.math.BigDecimal(100)).toInt()))) } }, stringResource(R.string.vat_rate_pct), keyboard = KeyboardType.Decimal)
                }
                SectionHeader(stringResource(R.string.language))
                Text(stringResource(R.string.language_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
                TextButton(onClick = {
                    // ST-10: per-app language through the system (Android 13+) or app settings, independent of region.
                    val i = if (Build.VERSION.SDK_INT >= 33) android.content.Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, android.net.Uri.parse("package:" + context.packageName))
                    else android.content.Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS)
                    runCatching { context.startActivity(i) }
                }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.change_language)) }
                SectionHeader(stringResource(R.string.danger_zone))
                TextButton(onClick = { deleting = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.delete_profile), color = MaterialTheme.colorScheme.error) }
            }
        }
        if (rename) app.penny.ui.catalog.NameDialog(stringResource(R.string.rename), p.name, { rename = false }) { n -> save(p.copy(name = n), R.string.renamed); rename = false }
        if (deleting) TypedConfirmDialog(
            stringResource(R.string.delete_profile), stringResource(R.string.delete_profile_body), p.name, stringResource(R.string.delete),
            onConfirm = {
                deleting = false
                vm.launch {
                    val pid = currentProfileId.value ?: return@launch
                    store.eraseProfile(pid)
                    // Open another profile on this device if there is one; the Profiles screen sends to onboarding otherwise.
                    val remaining = dao.observeAllOf(app.penny.core.event.Entities.PROFILE).first().map { it.id }.filter { it != pid && it != prefs.security.value.duressProfileId }
                    prefs.setCurrentProfile(remaining.firstOrNull())
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDeleted() }
                }
            },
            onDismiss = { deleting = false },
        )
    }
}

/** BU-01 cycle editor: every cycle type with its parameters. */
@Composable
fun CycleEditor(current: CycleRule, today: java.time.LocalDate, onChange: (CycleRule) -> Unit) {
    val context = LocalContext.current
    val kinds = listOf("monthly", "semimonthly", "weekly", "biweekly", "fourweekly", "quarterly", "yearly", "onincome")
    val kind = when (current) {
        is CycleRule.Monthly -> "monthly"; is CycleRule.SemiMonthly -> "semimonthly"; is CycleRule.Weekly -> "weekly"; is CycleRule.Biweekly -> "biweekly"
        is CycleRule.FourWeekly -> "fourweekly"; is CycleRule.Quarterly -> "quarterly"; is CycleRule.Yearly -> "yearly"; is CycleRule.OnIncome -> "onincome"
    }
    SelectField(stringResource(R.string.settings_cycle), kind, kinds.map { k ->
        Choice(k, cycleLabel(context, when (k) {
            "monthly" -> CycleRule.Monthly(1); "semimonthly" -> CycleRule.SemiMonthly(); "weekly" -> CycleRule.Weekly(); "biweekly" -> CycleRule.Biweekly(today)
            "fourweekly" -> CycleRule.FourWeekly(today); "quarterly" -> CycleRule.Quarterly(); "yearly" -> CycleRule.Yearly(); else -> CycleRule.OnIncome()
        }))
    }, { k ->
        when (k) {
            "monthly" -> onChange(CycleRule.Monthly(1)); "semimonthly" -> onChange(CycleRule.SemiMonthly()); "weekly" -> onChange(CycleRule.Weekly())
            "biweekly" -> onChange(CycleRule.Biweekly(today)); "fourweekly" -> onChange(CycleRule.FourWeekly(today)); "quarterly" -> onChange(CycleRule.Quarterly())
            "yearly" -> onChange(CycleRule.Yearly()); "onincome" -> onChange(CycleRule.OnIncome())
        }
    })
    when (current) {
        is CycleRule.Monthly -> SelectField(stringResource(R.string.cycle_start_day), current.startDay, ((1..28).map { Choice(it, it.toString()) } + Choice(LAST_DAY_OF_MONTH, context.getString(R.string.last_day))), { it?.let { d -> onChange(CycleRule.Monthly(d)) } })
        is CycleRule.SemiMonthly -> SelectField(stringResource(R.string.first_payday), current.firstDay, (1..27).map { Choice(it, it.toString()) }, { it?.let { d -> onChange(current.copy(firstDay = d)) } })
        is CycleRule.Weekly -> SelectField(stringResource(R.string.starts_on_day), current.startsOn, DayOfWeek.entries.map { Choice(it, it.getDisplayName(TextStyle.FULL, Locale.getDefault())) }, { it?.let { d -> onChange(CycleRule.Weekly(d)) } })
        is CycleRule.Biweekly -> DateInput(stringResource(R.string.anchor_payday), current.anchor, { onChange(CycleRule.Biweekly(it)) }, quick = false, today = today)
        is CycleRule.FourWeekly -> DateInput(stringResource(R.string.anchor_payday), current.anchor, { onChange(CycleRule.FourWeekly(it)) }, quick = false, today = today)
        is CycleRule.OnIncome -> SelectField(stringResource(R.string.max_days), current.maxDays, listOf(7, 14, 31, 45, 62).map { Choice(it, it.toString()) }, { it?.let { d -> onChange(current.copy(maxDays = d)) } })
        else -> Unit
    }
}

/** US-02: profiles on this device — switch in one tap, add, reorder; the decoy profile is never listed. */
@Composable
fun ProfilesScreen(onBack: () -> Unit, onSwitched: () -> Unit, onNone: () -> Unit, vm: app.penny.ui.common.BookViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val profilesState = vm.repo.observeProfiles().collectAsStateWithLifecycle(null)
    val profiles = profilesState.value
    val currentId by vm.repo.currentProfileId.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(profiles) { if (profiles != null && profiles.isEmpty()) onNone() }
    if (profiles == null) { app.penny.ui.kit.LoadingState(); return }
    run {
        val anyPack = vm.repo.packs.get(vm.ctx()?.book?.profile?.regionPack ?: profiles.firstOrNull()?.value?.regionPack ?: "XX")
        val baseCycle = vm.ctx()?.book?.profile?.cycle ?: anyPack.payCycleDefault
        var adding by rememberSaveable { mutableStateOf(false) }
        PennyScreen(stringResource(R.string.profiles), onBack) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item { Text(stringResource(R.string.profiles_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                items(profiles, key = { it.state.id }) { p ->
                    ListRow(
                        p.value.name, subtitle = listOfNotNull(p.value.baseCurrency, stringResource(R.string.business_book).takeIf { p.value.business != null }, stringResource(R.string.current).takeIf { p.state.id == currentId }).joinToString(" · "),
                        leading = { Avatar(p.value.name, p.state.id) }, selected = p.state.id == currentId,
                        onClick = { vm.prefs.setCurrentProfile(p.state.id); onSwitched() }, divider = true,
                    )
                }
                item { TextButton(onClick = { adding = true }, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.add_profile)) } }
            }
        }
        if (adding) {
            var name by rememberSaveable { mutableStateOf("") }
            var kind by rememberSaveable { mutableStateOf(ProfileKind.PERSONAL) }
            AlertDialog(
                onDismissRequest = { adding = false }, title = { Text(stringResource(R.string.add_profile)) },
                text = {
                    Column {
                        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true)
                        Segmented(listOf(Choice(ProfileKind.PERSONAL, stringResource(R.string.personal)), Choice(ProfileKind.BUSINESS, stringResource(R.string.business)), Choice(ProfileKind.SUPERVISED, stringResource(R.string.supervised))), kind, { kind = it })
                    }
                },
                confirmButton = {
                    TextButton(enabled = name.isNotBlank(), onClick = {
                        adding = false
                        vm.launch {
                            val pid = createProfile(anyPack, name.trim(), baseCycle, kind, vm.context.getString(R.string.ob_cash_name), 0)
                            prefs.setCurrentProfile(pid)
                        }
                        onSwitched()
                    }) { Text(stringResource(R.string.create)) }
                },
                dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

/** ST-02: theme, accent, text size up to 200%, dense mode, haptics, reduce motion, hide amounts. */
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    WithBook { _, vm ->
        val a by vm.prefs.appearance.collectAsStateWithLifecycle()
        fun set(n: app.penny.domain.Appearance) = vm.prefs.setAppearance(n)
        PennyScreen(stringResource(R.string.appearance), onBack) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {
                SectionHeader(stringResource(R.string.theme))
                SelectField(stringResource(R.string.theme), a.theme, listOf(
                    Choice(ThemeMode.SYSTEM, stringResource(R.string.theme_system)), Choice(ThemeMode.LIGHT, stringResource(R.string.theme_light)),
                    Choice(ThemeMode.DARK, stringResource(R.string.theme_dark)), Choice(ThemeMode.BLACK, stringResource(R.string.theme_black)),
                    Choice(ThemeMode.HIGH_CONTRAST, stringResource(R.string.theme_contrast)),
                ), { it?.let { t -> set(a.copy(theme = t)) } })
                SelectField(stringResource(R.string.accent), a.accent, listOf(Choice(0L, stringResource(R.string.accent_default)), Choice(0xFF2A5DB0, stringResource(R.string.accent_blue)), Choice(0xFF7B4FB8, stringResource(R.string.accent_violet)), Choice(0xFF1F7A86, stringResource(R.string.accent_teal)), Choice(0xFF8A5A2B, stringResource(R.string.accent_bronze))), { it?.let { c -> set(a.copy(accent = c)) } })
                SectionHeader(stringResource(R.string.text_size))
                Slider(a.textScale, { set(a.copy(textScale = (Math.round(it * 20) / 20f))) }, valueRange = 0.85f..2f, modifier = Modifier.padding(horizontal = 20.dp))
                Text(stringResource(R.string.text_size_x, (a.textScale * 100).toInt()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
                SwitchRow(stringResource(R.string.dense_mode), a.dense, { set(a.copy(dense = it)) })
                SwitchRow(stringResource(R.string.haptics), a.haptics, { set(a.copy(haptics = it)) })
                SwitchRow(stringResource(R.string.reduce_motion), a.reduceMotion, { set(a.copy(reduceMotion = it)) })
                SwitchRow(stringResource(R.string.hide_amounts), a.hideAmounts, { set(a.copy(hideAmounts = it)) }, subtitle = stringResource(R.string.hide_amounts_hint))
            }
        }
    }
}

/** ST-04 + NT-01: explain before asking for notification permission; quiet hours; per-type switches; watchlists. */
@Composable
fun AlertSettingsScreen(onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val context = LocalContext.current
        var os by rememberSaveable { mutableStateOf(vm.prefs.flag("alerts.os")) }
        var explain by rememberSaveable { mutableStateOf(false) }
        val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> os = ok; vm.prefs.setFlag("alerts.os", ok) }
        var qs by rememberSaveable { mutableStateOf(vm.prefs.string("alerts.quietStart") ?: "21:00") }
        var qe by rememberSaveable { mutableStateOf(vm.prefs.string("alerts.quietEnd") ?: "09:00") }
        var watch by rememberSaveable { mutableStateOf(false) }
        PennyScreen(stringResource(R.string.alerts), onBack) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {
                SwitchRow(stringResource(R.string.os_notifications), os, { on ->
                    if (!on) { os = false; vm.prefs.setFlag("alerts.os", false) }
                    else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) explain = true
                    else { os = true; vm.prefs.setFlag("alerts.os", true) }
                }, subtitle = stringResource(R.string.os_notifications_hint))
                SectionHeader(stringResource(R.string.quiet_hours))
                Row {
                    TextInput(qs, { qs = it; if (Regex("\\d{2}:\\d{2}").matches(it)) vm.prefs.setString("alerts.quietStart", it) }, stringResource(R.string.from), Modifier.weight(1f))
                    TextInput(qe, { qe = it; if (Regex("\\d{2}:\\d{2}").matches(it)) vm.prefs.setString("alerts.quietEnd", it) }, stringResource(R.string.to), Modifier.weight(1f))
                }
                SectionHeader(stringResource(R.string.which_alerts))
                listOf("pace" to R.string.alert_pace, "bills" to R.string.alert_bills, "safe" to R.string.alert_safe_low, "large" to R.string.alert_large, "unusual" to R.string.alert_unusual, "forecast" to R.string.alert_forecast).forEach { (k, label) ->
                    var on by rememberSaveable(k) { mutableStateOf(!vm.prefs.flag("alerts.$k.off")) }
                    SwitchRow(stringResource(label), on, { on = it; vm.prefs.setFlag("alerts.$k.off", !it) })
                }
                Text(stringResource(R.string.envelope_alerts_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                SectionHeader(stringResource(R.string.watchlists), action = stringResource(R.string.add), onAction = { watch = true })
                if (ctx.book.watchlists.isEmpty()) Text(stringResource(R.string.watchlists_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
                ctx.book.watchlists.forEach { w ->
                    ListRow(w.value.value, subtitle = w.value.target.name.lowercase(), trailingText = ctx.money(w.value.limitMinor, w.value.currency),
                        trailing = { TextButton(onClick = { vm.act(R.string.deleted) { watchlists.delete(w.state.id) } }) { Text(stringResource(R.string.delete)) } }, divider = true)
                }
            }
        }
        if (explain) AlertDialog(
            onDismissRequest = { explain = false }, title = { Text(stringResource(R.string.os_notifications)) },
            text = { Text(stringResource(R.string.notif_explain)) },
            confirmButton = { TextButton(onClick = { explain = false; if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(stringResource(R.string.continue_label)) } },
            dismissButton = { TextButton(onClick = { explain = false }) { Text(stringResource(R.string.not_now)) } },
        )
        if (watch) {
            var target by rememberSaveable { mutableStateOf(WatchTarget.PAYEE) }
            var value by rememberSaveable { mutableStateOf("") }
            var limit by rememberSaveable { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { watch = false }, title = { Text(stringResource(R.string.add_watchlist)) },
                text = {
                    Column {
                        Segmented(WatchTarget.entries.map { Choice(it, it.name.lowercase().replaceFirstChar(Char::uppercase)) }, target, { target = it })
                        TextInput(value, { value = it }, stringResource(R.string.value))
                        AmountInput(limit, { limit = it }, ctx.base, ctx.fmt, stringResource(R.string.limit_per_period))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val l = parseAmount(limit, ctx.base, ctx.fmt) ?: return@TextButton
                        vm.act(R.string.saved) { watchlists.create(newId(), it.profileId, Watchlist(target, value.trim(), l, ctx.base)) }; watch = false
                    }, enabled = value.isNotBlank()) { Text(stringResource(R.string.save)) }
                },
                dismissButton = { TextButton(onClick = { watch = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

/** ST-08 / SE-04 / SE-05: app lock with PIN and biometrics, auto-lock timer, duress PIN with a decoy book, screenshots. */
@Composable
fun SecurityScreen(onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val s by vm.prefs.security.collectAsStateWithLifecycle()
        val context = LocalContext.current
        var pinDialog by rememberSaveable { mutableStateOf<String?>(null) }
        PennyScreen(stringResource(R.string.security), onBack) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {
                SwitchRow(stringResource(R.string.app_lock), s.lockEnabled, { on -> if (on && s.pinHash.isEmpty()) pinDialog = "pin" else vm.prefs.setSecurity(s.copy(lockEnabled = on)) }, subtitle = stringResource(R.string.app_lock_hint))
                if (s.lockEnabled) {
                    SwitchRow(stringResource(R.string.use_biometric), s.biometric, { vm.prefs.setSecurity(s.copy(biometric = it)) }, enabled = canUseBiometrics(context))
                    ListRow(stringResource(R.string.change_pin), onClick = { pinDialog = "pin" }, divider = true)
                    SelectField(stringResource(R.string.auto_lock_after), s.autoLockSeconds, listOf(0, 30, 60, 300, 900).map { Choice(it, if (it == 0) context.getString(R.string.immediately) else context.resources.getQuantityString(R.plurals.seconds_n, it, it)) }, { it?.let { v -> vm.prefs.setSecurity(s.copy(autoLockSeconds = v)) } })
                    SectionHeader(stringResource(R.string.duress_pin))
                    Text(stringResource(R.string.duress_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                    ListRow(stringResource(if (s.duressPinHash.isEmpty()) R.string.set_duress_pin else R.string.change_duress_pin), onClick = { pinDialog = "duress" }, divider = true)
                    if (s.duressPinHash.isNotEmpty()) ListRow(stringResource(R.string.remove_duress_pin), onClick = { vm.prefs.setSecurity(s.copy(duressPinHash = "", duressProfileId = null)) }, divider = true)
                }
                SwitchRow(stringResource(R.string.block_screenshots), s.blockScreenshots, { vm.prefs.setSecurity(s.copy(blockScreenshots = it)) }, subtitle = stringResource(R.string.block_screenshots_hint))
                SectionHeader(stringResource(R.string.encryption))
                Text(stringResource(R.string.encryption_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        pinDialog?.let { which ->
            var pin by rememberSaveable { mutableStateOf("") }
            var pin2 by rememberSaveable { mutableStateOf("") }
            val ok = pin.length >= 4 && pin == pin2 && pin.all(Char::isDigit)
            AlertDialog(
                onDismissRequest = { pinDialog = null },
                title = { Text(stringResource(if (which == "pin") R.string.set_pin else R.string.set_duress_pin)) },
                text = {
                    Column {
                        OutlinedTextField(pin, { pin = it.take(12) }, label = { Text(stringResource(R.string.pin)) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                        OutlinedTextField(pin2, { pin2 = it.take(12) }, label = { Text(stringResource(R.string.repeat_pin)) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                        if (which == "duress" && s.pinHash.isNotEmpty() && pin.isNotEmpty() && PinHasher.verify(pin, s.pinHash)) Text(stringResource(R.string.duress_same), color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton(enabled = ok, onClick = {
                        val h = PinHasher.hash(pin)
                        if (which == "pin") vm.prefs.setSecurity(s.copy(pinHash = h, lockEnabled = true))
                        else vm.launch {
                            // SE-05: the decoy book has plausible sample data and is never listed.
                            val decoy = s.duressProfileId ?: createProfile(packs.get(ctx.book.profile.regionPack), ctx.book.profile.name, ctx.book.profile.cycle, ProfileKind.PERSONAL, context.getString(R.string.ob_cash_name), 0, decoy = true).also { pid ->
                                app.penny.domain.SampleData.seed(this, pid)
                            }
                            prefs.setSecurity(prefs.security.value.copy(duressPinHash = h, duressProfileId = decoy))
                        }
                        pinDialog = null
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = { TextButton(onClick = { pinDialog = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}
