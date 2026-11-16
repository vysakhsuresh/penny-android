package app.penny.ui.settings

import app.penny.ui.common.safeStart
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.Routes
import app.penny.capture.PennyNotificationListener
import app.penny.core.Brand
import app.penny.core.io.Csv
import app.penny.core.io.CsvMapping
import app.penny.core.io.ImportResult
import app.penny.core.io.Importers
import app.penny.domain.ops.ImportPlan
import app.penny.domain.ops.commitImport
import app.penny.domain.ops.exportCsv
import app.penny.domain.ops.exportJson
import app.penny.domain.ops.importJson
import app.penny.domain.ops.planImport
import app.penny.ui.common.TxRow
import app.penny.ui.common.WithBook
import app.penny.ui.kit.Choice
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.ListRow
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.Pill
import app.penny.ui.kit.PrimaryButton
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.SelectField
import app.penny.ui.kit.SwitchRow
import app.penny.ui.kit.TextInput
import app.penny.ui.kit.TypedConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** ST-09 / SY-05…SY-09: export (JSON, CSV per table), backup to the user's folder, restore, delete everything. */
@Composable
fun DataScreen(onBack: () -> Unit, go: (String) -> Unit, onWiped: () -> Unit) {
    WithBook { ctx, vm ->
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var wipe by rememberSaveable { mutableStateOf(false) }
        var status by rememberSaveable { mutableStateOf<String?>(null) }
        val stamp = LocalDate.now().toString()
        val jsonOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { val j = vm.repo.exportJson(ctx.book.profileId); context.contentResolver.openOutputStream(uri)?.use { it.write(j.toByteArray()) } }.isSuccess }
                status = context.getString(if (ok) R.string.export_done else R.string.export_failed)
            }
        }
        val csvOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val files = ctx.book.exportCsv()
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            ZipOutputStream(os).use { z -> files.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() } }
                        }
                    }.isSuccess
                }
                status = context.getString(if (ok) R.string.export_done else R.string.export_failed)
            }
        }
        val jsonIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { vm.repo.importJson(context.contentResolver.openInputStream(uri)!!.bufferedReader().readText()) } }
                status = r.fold({ (pid, n) -> vm.prefs.setCurrentProfile(pid); context.resources.getQuantityString(R.plurals.restored_events, n, n) }, { context.getString(R.string.import_failed, it.message ?: "") })
            }
        }
        // SY-05: backup folder chosen by the user (Drive, OneDrive, Dropbox, local) through the system picker.
        val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            vm.prefs.setString("backup.folder", uri.toString())
            app.penny.work.BackupWorker.schedule(context)
            scope.launch { status = context.getString(if (app.penny.work.BackupWorker.runNow(context)) R.string.backup_done else R.string.backup_failed) }
        }
        PennyScreen(stringResource(R.string.data_backup), onBack) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {
                status?.let { Pill(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                SectionHeader(stringResource(R.string.export))
                Text(stringResource(R.string.export_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                ListRow(stringResource(R.string.export_json), subtitle = stringResource(R.string.export_json_sub), onClick = { jsonOut.launch("${Brand.EXPORT_APP_ID}-$stamp.json") }, divider = true)
                ListRow(stringResource(R.string.export_csv), subtitle = stringResource(R.string.export_csv_sub), onClick = { csvOut.launch("${Brand.EXPORT_APP_ID}-$stamp-csv.zip") }, divider = true)
                SectionHeader(stringResource(R.string.import_label))
                ListRow(stringResource(R.string.import_file), subtitle = stringResource(R.string.import_file_sub), onClick = { go(Routes.IMPORT) }, divider = true)
                ListRow(stringResource(R.string.restore_export), subtitle = stringResource(R.string.restore_export_sub), onClick = { jsonIn.launch(arrayOf("application/json", "*/*")) }, divider = true)
                SectionHeader(stringResource(R.string.backup))
                val folderSet = vm.prefs.string("backup.folder") != null
                ListRow(stringResource(if (folderSet) R.string.backup_folder_change else R.string.backup_folder_choose), subtitle = stringResource(R.string.backup_folder_sub), onClick = { folder.launch(null) }, divider = true)
                if (folderSet) ListRow(stringResource(R.string.backup_now), subtitle = vm.prefs.long("backup.last").takeIf { it > 0 }?.let { stringResource(R.string.last_backup, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))) }, onClick = { scope.launch { status = context.getString(if (app.penny.work.BackupWorker.runNow(context)) R.string.backup_done else R.string.backup_failed) } }, divider = true)
                SectionHeader(stringResource(R.string.privacy))
                var optIn by rememberSaveable { mutableStateOf(vm.prefs.analyticsOptIn) }
                SwitchRow(stringResource(R.string.usage_counts), optIn, { optIn = it; vm.prefs.analyticsOptIn = it; if (!it) vm.telemetry.clear() }, subtitle = stringResource(R.string.usage_counts_hint))
                if (optIn) {
                    val counts = vm.telemetry.snapshot()
                    Text(if (counts.isEmpty()) stringResource(R.string.usage_counts_none) else counts.entries.joinToString { "${it.key.lowercase()}: ${it.value}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                }
                SectionHeader(stringResource(R.string.danger_zone))
                ListRow(stringResource(R.string.delete_everything), subtitle = stringResource(R.string.delete_everything_sub), onClick = { wipe = true })
            }
        }
        if (wipe) TypedConfirmDialog(
            stringResource(R.string.delete_everything), stringResource(R.string.delete_everything_body), ctx.book.profile.name, stringResource(R.string.delete),
            onConfirm = {
                wipe = false
                scope.launch {
                    withContext(Dispatchers.IO) { app.penny.PennyApp.wipeEverything(context) }
                    onWiped()
                }
            },
            onDismiss = { wipe = false },
        )
    }
}

/** CP-09 import: CSV with column mapper and saved presets, OFX/QFX, QIF, CAMT.053, MT940; preview with duplicates. */
@Composable
fun ImportScreen(onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var text by remember { mutableStateOf<String?>(null) }
        // File body, mapping and preview live together; keeping only the name across rotation showed a file with no preview.
        var fileName by remember { mutableStateOf("") }
        var mapping by remember { mutableStateOf<CsvMapping?>(null) }
        var preset by remember { mutableStateOf<String?>(null) }
        var account by rememberSaveable { mutableStateOf(b.profile.defaultAccountId ?: b.activeAccounts.firstOrNull()?.state?.id) }
        var skipDupes by rememberSaveable { mutableStateOf(true) }
        var result by remember { mutableStateOf<ImportResult?>(null) }
        // Reads a file (picked here, or shared to Penny from another app) and builds the preview.
        fun load(read: suspend () -> Pair<String, String>) {
            scope.launch {
              runCatching {
                val (name, body) = withContext(Dispatchers.IO) { read() }
                val low = name.lowercase()
                var newPreset: String? = null
                var newMapping: CsvMapping? = null
                val parsed = withContext(Dispatchers.Default) { when {
                    low.endsWith(".ofx") || low.endsWith(".qfx") || body.contains("<OFX>") -> Importers.ofx(body, ctx.base)
                    low.endsWith(".qif") || body.startsWith("!Type") -> Importers.qif(body, ctx.base)
                    body.contains("camt.053") || body.contains("<BkToCstmrStmt>") -> Importers.camt053(body)
                    body.contains(":61:") && body.contains(":60F:") -> Importers.mt940(body, ctx.base)
                    else -> {
                        val rows = Csv.parse(body)
                        val header = rows.firstOrNull().orEmpty()
                        val det = Importers.detectPreset(header)
                        newPreset = det?.first?.app
                        val m = det?.second ?: Importers.guessMapping(header, rows.getOrNull(1).orEmpty())
                        newMapping = m
                        Importers.csv(body, m, ctx.base)
                    }
                } }
                fileName = name; text = body; preset = newPreset; mapping = newMapping; result = parsed
              }.onFailure { e ->
                  android.util.Log.w("Penny", "import read failed", e)
                  vm.toast(context.getString(R.string.import_read_failed))
              }
            }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            load {
                val n = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
                n to (context.contentResolver.openInputStream(uri)?.use { s -> readText(s.readBytes()) } ?: "")
            }
        }
        // A bank file shared to Penny (ShareActivity) waits in the cache; open it once, then remove it.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val pending = app.penny.SharedImport.take(context) ?: return@LaunchedEffect
            load { pending }
        }
        val plan: ImportPlan? = result?.let { r -> account?.let { b.planImport(r.rows, it) } }
        PennyScreen(stringResource(R.string.import_file), onBack) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item {
                    Text(stringResource(R.string.import_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(if (text == null) R.string.choose_file else R.string.choose_another)) }
                    if (fileName.isNotEmpty()) Text(fileName + (preset?.let { " · " + stringResource(R.string.detected_x, it) } ?: ""), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                }
                mapping?.let { m ->
                    item {
                        val header = Csv.parse(text.orEmpty()).firstOrNull().orEmpty()
                        val cols = header.mapIndexed { i, h -> Choice(i, h.ifBlank { "#${i + 1}" }) }
                        SectionHeader(stringResource(R.string.column_mapper))
                        fun remap(n: CsvMapping) { mapping = n; runCatching { Importers.csv(text.orEmpty(), n, ctx.base) }.onSuccess { result = it } }
                        SelectField(stringResource(R.string.field_date), m.dateCol, cols, { it?.let { c -> remap(m.copy(dateCol = c)) } })
                        SelectField(stringResource(R.string.field_amount), m.amountCol, cols, { remap(m.copy(amountCol = it)) }, allowNone = true, noneLabel = stringResource(R.string.debit_credit_columns))
                        if (m.amountCol == null) {
                            SelectField(stringResource(R.string.debit), m.debitCol, cols, { remap(m.copy(debitCol = it)) }, allowNone = true)
                            SelectField(stringResource(R.string.credit), m.creditCol, cols, { remap(m.copy(creditCol = it)) }, allowNone = true)
                        }
                        SelectField(stringResource(R.string.field_payee), m.payeeCol, cols, { remap(m.copy(payeeCol = it)) }, allowNone = true)
                        SelectField(stringResource(R.string.field_category), m.categoryCol, cols, { remap(m.copy(categoryCol = it)) }, allowNone = true)
                        SelectField(stringResource(R.string.field_note), m.noteCol, cols, { remap(m.copy(noteCol = it)) }, allowNone = true)
                        SelectField(stringResource(R.string.date_format), m.datePattern, listOf("yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "dd.MM.yyyy", "dd-MM-yyyy", "M/d/yyyy", "d/M/yyyy").map { Choice(it, it) }, { it?.let { p -> remap(m.copy(datePattern = p)) } })
                        SelectField(stringResource(R.string.number_style), m.numberLocale, listOf(Choice("en-US", "1,234.56"), Choice("de-DE", "1.234,56"), Choice("fr-FR", "1 234,56"), Choice("en-IN", "12,34,567.89")), { it?.let { l -> remap(m.copy(numberLocale = l)) } })
                        SwitchRow(stringResource(R.string.flip_sign), m.invertSign, { remap(m.copy(invertSign = it)) }, subtitle = stringResource(R.string.flip_sign_hint))
                    }
                }
                result?.let { r ->
                    item {
                        SectionHeader(stringResource(R.string.preview))
                        SelectField(stringResource(R.string.into_account), account, b.activeAccounts.map { Choice(it.state.id, it.value.name, it.value.currency) }, { account = it })
                        Text(pluralStringResource(R.plurals.rows_read, r.rows.size, r.rows.size) + if (r.errors.isNotEmpty()) " · " + pluralStringResource(R.plurals.rows_skipped, r.errors.size, r.errors.size) else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
                        plan?.let { p ->
                            if (p.duplicates.isNotEmpty()) SwitchRow(pluralStringResource(R.plurals.skip_duplicates, p.duplicates.size, p.duplicates.size), skipDupes, { skipDupes = it })
                            if (p.newCategories.isNotEmpty()) Text(stringResource(R.string.new_categories_x, p.newCategories.take(8).joinToString()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
                            Spacer(Modifier.height(8.dp))
                            PrimaryButton(stringResource(R.string.import_n, p.transactions.size - if (skipDupes) p.duplicates.size else 0), {
                                vm.act(R.string.import_done, after = onBack) { commitImport(it, p, skipDupes).first }
                            }, Modifier.padding(horizontal = 16.dp), enabled = p.transactions.isNotEmpty())
                        }
                    }
                    plan?.let { p ->
                        items(p.transactions.take(50).withIndex().toList(), key = { it.index }) { (i, t) ->
                            Column {
                                TxRow("import$i", t, ctx, onOpen = {}, showDate = true)
                                if (i in p.duplicates) Pill(stringResource(R.string.duplicate), Modifier.padding(start = 70.dp, bottom = 4.dp))
                            }
                        }
                    }
                }
                if (text == null) item { EmptyState(stringResource(R.string.import_empty), stringResource(R.string.import_formats)) }
            }
        }
    }
}

/** Reads UTF-8 (with or without BOM), UTF-16 with BOM, or falls back to Windows-1252 for old bank exports. */
private val BOM = Char(0xFEFF).toString()

fun readText(bytes: ByteArray): String = when {
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, Charsets.UTF_16LE).drop(1)
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, Charsets.UTF_16BE).drop(1)
    else -> {
        val utf = String(bytes, Charsets.UTF_8)
        if (utf.contains('�')) String(bytes, charset("windows-1252")) else utf.removePrefix(BOM)
    }
}

/** ST-03: each capture source explained with its own switch; nothing runs until the user turns it on. */
@Composable
fun CaptureSettingsScreen(onBack: () -> Unit, go: (String) -> Unit) {
    WithBook { ctx, vm ->
        val context = LocalContext.current
        var listenerOn by remember { mutableStateOf(PennyNotificationListener.isEnabled(context)) }
        var capture by rememberSaveable { mutableStateOf(vm.prefs.flag("capture.notifications")) }
        var explain by rememberSaveable { mutableStateOf(false) }
        var extra by rememberSaveable { mutableStateOf(vm.prefs.string("capture.extraPackages").orEmpty()) }
        androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { listenerOn = PennyNotificationListener.isEnabled(context); onPauseOrDispose { } }
        PennyScreen(stringResource(R.string.capture), onBack) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {
                SectionHeader(stringResource(R.string.notification_capture))
                Text(stringResource(R.string.notification_capture_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
                SwitchRow(stringResource(R.string.read_payment_notifications), capture && listenerOn, { on ->
                    if (on && !listenerOn) explain = true
                    capture = on; vm.prefs.setFlag("capture.notifications", on)
                }, subtitle = if (!listenerOn && capture) stringResource(R.string.needs_system_access) else null)
                val apps = (ctx.pack.paymentApps + ctx.pack.bankApps).map { it.name }
                if (apps.isNotEmpty()) Text(stringResource(R.string.apps_in_region, apps.joinToString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                TextInput(extra, { extra = it; vm.prefs.setString("capture.extraPackages", it) }, stringResource(R.string.extra_apps), supporting = stringResource(R.string.extra_apps_hint))
                SectionHeader(stringResource(R.string.sources))
                if (ctx.book.captureSources.isEmpty()) Text(stringResource(R.string.no_sources_yet), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
                ctx.book.captureSources.forEach { s ->
                    SwitchRow(s.value.source.substringAfter(':'), s.value.autoPost, { on -> vm.act(R.string.updated) { captureSources.update(s.state.id, s.value.copy(autoPost = on)) } },
                        subtitle = stringResource(R.string.auto_post_source, s.value.acceptsInRow) + if (s.value.alwaysIgnore) " · " + stringResource(R.string.ignored) else "")
                    if (s.value.alwaysIgnore || s.value.ignoredPayees.isNotEmpty()) TextButton(onClick = { vm.act(R.string.updated) { captureSources.update(s.state.id, s.value.copy(alwaysIgnore = false, ignoredPayees = emptyList())) } }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.clear_ignores)) }
                }
                SectionHeader(stringResource(R.string.other_ways))
                ListRow(stringResource(R.string.share_sheet), subtitle = stringResource(R.string.share_sheet_sub), divider = true)
                ListRow(stringResource(R.string.scan_receipt), subtitle = stringResource(R.string.scan_receipt_sub), onClick = { go(Routes.SCAN) }, divider = true)
                ListRow(stringResource(R.string.voice_add), subtitle = stringResource(R.string.voice_sub), onClick = { go(Routes.VOICE) }, divider = true)
                EmailForwarding()
                CommunityMerchants()
                ListRow(stringResource(R.string.review_queue), onClick = { go(Routes.REVIEW) })
                Text(stringResource(R.string.never_sms), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
        }
        if (explain) AlertDialog(
            onDismissRequest = { explain = false }, title = { Text(stringResource(R.string.read_payment_notifications)) },
            text = { Text(stringResource(R.string.listener_explain)) },
            confirmButton = { TextButton(onClick = { explain = false; context.safeStart(PennyNotificationListener.settingsIntent()) }) { Text(stringResource(R.string.open_settings)) } },
            dismissButton = { TextButton(onClick = { explain = false }) { Text(stringResource(R.string.not_now)) } },
        )
    }
}

@dagger.hilt.android.lifecycle.HiltViewModel
class MailViewModel @javax.inject.Inject constructor(val mail: app.penny.domain.MailService, val sync: app.penny.domain.SyncManager) : androidx.lifecycle.ViewModel()

/** CP-06: per-account forwarding address; mail is encrypted to this phone by the relay and parsed here. */
@Composable
private fun EmailForwarding(mvm: MailViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val scope = rememberCoroutineScope()
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var address by rememberSaveable { mutableStateOf(mvm.mail.address) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    ListRow(stringResource(R.string.email_forwarding), subtitle = address ?: stringResource(R.string.email_forwarding_sub), divider = address == null)
    if (!mvm.sync.signedIn) { Text(stringResource(R.string.bank_needs_sync), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)); return }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
    Row(Modifier.padding(horizontal = 8.dp)) {
        if (address == null) TextButton(onClick = { scope.launch { runCatching { mvm.mail.enable() }.onSuccess { address = it }.onFailure { error = it.message } } }) { Text(stringResource(R.string.get_address)) }
        else {
            TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(address!!)) }) { Text(stringResource(R.string.copy)) }
            TextButton(onClick = { scope.launch { runCatching { mvm.mail.enable(rotate = true) }.onSuccess { address = it }.onFailure { error = it.message } } }) { Text(stringResource(R.string.new_address)) }
            TextButton(onClick = { scope.launch { mvm.mail.disable(); address = null } }) { Text(stringResource(R.string.turn_off)) }
        }
    }
    if (address != null) Text(stringResource(R.string.email_forwarding_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
}

/** PY-03: opt-in, anonymous merchant names and categories shared between users of the same region. */
@Composable
private fun CommunityMerchants(mvm: MailViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val ctx = LocalContext.current
    val prefs = remember { app.penny.domain.AppPrefs(ctx.applicationContext) }
    val scope = rememberCoroutineScope()
    var on by rememberSaveable { mutableStateOf(prefs.flag("merchants.community")) }
    SwitchRow(stringResource(R.string.community_merchants), on, { v ->
        on = v; prefs.setFlag("merchants.community", v)
        if (v) scope.launch { mvm.mail.contribute(); mvm.mail.suggestPayees() }
    }, subtitle = stringResource(R.string.community_merchants_hint))
}
