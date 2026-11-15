package app.penny.ui.capture

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.penny.R
import app.penny.capture.Ocr
import app.penny.core.capture.ReceiptParser
import app.penny.core.capture.VoiceGrammar
import app.penny.core.model.Attachment
import app.penny.core.model.CaptureState
import app.penny.core.model.CategoryKind
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.domain.CaptureService
import app.penny.domain.ops.saveTransaction
import app.penny.ui.common.Ctx
import app.penny.ui.common.TxRow
import app.penny.ui.common.WithBook
import app.penny.ui.kit.AmountInput
import app.penny.ui.kit.Choice
import app.penny.ui.kit.DateInput
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.Pill
import app.penny.ui.kit.PrimaryButton
import app.penny.ui.kit.SelectField
import app.penny.ui.kit.TextInput
import app.penny.ui.kit.parseAmount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CaptureViewModel @Inject constructor(val service: CaptureService) : ViewModel()

/** CP-04 review queue: source, confidence, suggestion; accept, edit then accept, ignore, always ignore; auto-post opt-in. */
@Composable
fun ReviewScreen(onBack: () -> Unit, cvm: CaptureViewModel = hiltViewModel()) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val pending = b.captures.filter { it.value.state == CaptureState.PENDING }
        var editing by rememberSaveable { mutableStateOf<String?>(null) }
        PennyScreen(stringResource(R.string.review_queue), onBack) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                if (pending.isEmpty()) item { EmptyState(stringResource(R.string.review_empty), stringResource(R.string.review_empty_body), icon = Icons.Outlined.Inbox) }
                // CP-04: suggest auto-post for sources with N consistent accepts.
                b.captureSources.filter { !it.value.autoPost && it.value.acceptsInRow >= CaptureService.AUTO_POST_SUGGEST_AFTER }.forEach { s ->
                    item(key = "s" + s.state.id) {
                        PennyCard(color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.auto_post_suggest, s.value.acceptsInRow, s.value.source.substringAfter(':')), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { vm.act(R.string.updated) { cvm.service.setAutoPost(it, s.value.source, true) } }) { Text(stringResource(R.string.turn_on_auto_post)) }
                            }
                        }
                    }
                }
                items(pending, key = { it.state.id }) { item ->
                    var menu by remember { mutableStateOf(false) }
                    PennyCard {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Pill(item.value.source.substringBefore(':').replaceFirstChar(Char::uppercase) + " · " + item.value.sourceDetail.substringAfterLast('.'))
                                Spacer(Modifier.weight(1f))
                                Text(stringResource(R.string.confidence_x, item.value.confidence), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TxRow(item.state.id, item.value.proposed, ctx, onOpen = { editing = item.state.id })
                            Text(item.value.rawText.take(160), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                            if (editing == item.state.id) EditProposal(ctx, item.value.proposed, onCancel = { editing = null }) { tx ->
                                vm.act(R.string.added_tx) { cvm.service.accept(it, item, tx) }; editing = null
                            } else Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilledTonalButton(onClick = { vm.act(R.string.added_tx) { cvm.service.accept(it, item, null) } }, enabled = item.value.proposed.accountId.isNotEmpty()) { Text(stringResource(R.string.accept)) }
                                TextButton(onClick = { editing = item.state.id }) { Text(stringResource(R.string.edit)) }
                                TextButton(onClick = { menu = true }) { Text(stringResource(R.string.ignore)) }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem({ Text(stringResource(R.string.ignore_once)) }, onClick = { menu = false; vm.act(R.string.ignored) { cvm.service.ignore(it, item, false, false) } })
                                    if (item.value.proposed.payee != null) DropdownMenuItem({ Text(stringResource(R.string.always_ignore_payee, item.value.proposed.payee!!)) }, onClick = { menu = false; vm.act(R.string.ignored) { cvm.service.ignore(it, item, false, true) } })
                                    DropdownMenuItem({ Text(stringResource(R.string.always_ignore_source)) }, onClick = { menu = false; vm.act(R.string.ignored) { cvm.service.ignore(it, item, true, false) } })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** CP-04 "edit before accept" and the confirm step for voice and receipts. */
@Composable
fun EditProposal(ctx: Ctx, t: Transaction, saveLabel: Int = R.string.accept, onCancel: (() -> Unit)? = null, onSave: (Transaction) -> Unit) {
    val b = ctx.book
    var amount by rememberSaveable { mutableStateOf(ctx.fmt.editable(t.amountMinor, t.currency)) }
    var payee by rememberSaveable { mutableStateOf(t.payee.orEmpty()) }
    var cat by rememberSaveable { mutableStateOf(t.categoryId) }
    var acct by rememberSaveable { mutableStateOf(t.accountId.ifEmpty { null }) }
    var date by rememberSaveable { mutableStateOf(t.date.toString()) }
    var income by rememberSaveable { mutableStateOf(t.type == TxType.INCOME) }
    Column {
        app.penny.ui.kit.Segmented(listOf(Choice(false, stringResource(R.string.type_expense)), Choice(true, stringResource(R.string.type_income))), income, { income = it })
        AmountInput(amount, { amount = it }, t.currency, ctx.fmt, stringResource(R.string.field_amount))
        TextInput(payee, { payee = it }, stringResource(R.string.field_payee))
        SelectField(stringResource(R.string.field_category), cat, b.categoriesOf(if (income) CategoryKind.INCOME else CategoryKind.EXPENSE).map { Choice(it.state.id, b.categoryName(it.state.id)) }, { cat = it }, allowNone = true)
        SelectField(stringResource(R.string.field_account), acct, b.activeAccounts.map { Choice(it.state.id, it.value.name) }, { acct = it })
        DateInput(stringResource(R.string.field_date), runCatching { java.time.LocalDate.parse(date) }.getOrNull(), { date = it.toString() }, today = b.today())
        Row(Modifier.padding(horizontal = 8.dp)) {
            FilledTonalButton(onClick = {
                val a = parseAmount(amount, t.currency, ctx.fmt) ?: return@FilledTonalButton
                val ac = acct ?: return@FilledTonalButton
                onSave(t.copy(amountMinor = a, payee = payee.ifBlank { null }, categoryId = cat, accountId = ac, date = java.time.LocalDate.parse(date), type = if (income) TxType.INCOME else TxType.EXPENSE))
            }) { Text(stringResource(saveLabel)) }
            if (onCancel != null) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}

/** CP-08 voice: platform speech recognition (offline preferred), then the grammar; the user confirms before saving. */
@Composable
fun VoiceScreen(onBack: () -> Unit, onDone: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        var heard by rememberSaveable { mutableStateOf("") }
        var unavailable by rememberSaveable { mutableStateOf(false) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK) heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        }
        val prompt = stringResource(R.string.voice_prompt)
        fun listen() {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            runCatching { launcher.launch(i) }.onFailure { unavailable = true }
        }
        LaunchedEffect(Unit) { if (heard.isEmpty()) listen() }
        PennyScreen(stringResource(R.string.voice_add), onBack, large = false) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                Text(stringResource(R.string.voice_examples), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                if (unavailable) Text(stringResource(R.string.voice_unavailable), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp))
                TextInput(heard, { heard = it }, stringResource(R.string.voice_heard), singleLine = false)
                OutlinedButton(onClick = ::listen, modifier = Modifier.padding(horizontal = 16.dp)) { Icon(Icons.Outlined.Mic, null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.listen_again)) }
                if (heard.isNotBlank()) {
                    val g = VoiceGrammar(ctx.base, b.today(), b.categories.associate { it.value.name.lowercase() to it.state.id }, b.accounts.associate { it.value.name.lowercase() to it.state.id })
                    val r = g.parse(heard)
                    val tx = Transaction(
                        type = if (r.income) TxType.INCOME else TxType.EXPENSE, amountMinor = r.amountMinor ?: 0, currency = r.currency,
                        accountId = r.accountId ?: b.profile.defaultAccountId ?: b.activeAccounts.firstOrNull()?.state?.id.orEmpty(), categoryId = r.categoryId,
                        payee = r.payee, date = r.date, time = LocalTime.now(b.zone).withSecond(0).withNano(0), tz = b.profile.timezone, note = r.note, source = "voice",
                        tags = if (r.people.isNotEmpty()) listOf("split") else emptyList(),
                    )
                    if (r.people.isNotEmpty()) Text(stringResource(R.string.voice_people, r.people.joinToString()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
                    androidx.compose.runtime.key(heard) {
                        EditProposal(ctx, tx, R.string.save) { t -> vm.act(R.string.added_tx, after = onDone) { saveTransaction(it, null, t).group } }
                    }
                }
            }
        }
    }
}

/** CP-07 / TX-11: photograph or pick a receipt, read it on device, confirm, save with the image attached. */
@Composable
fun ScanScreen(onBack: () -> Unit, onDone: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var text by rememberSaveable { mutableStateOf<String?>(null) }
        var image by rememberSaveable { mutableStateOf<String?>(null) }
        var busy by rememberSaveable { mutableStateOf(false) }
        var failed by rememberSaveable { mutableStateOf(false) }
        fun read(uri: Uri) {
            busy = true; failed = false; image = uri.toString()
            scope.launch { text = Ocr.recognize(context, uri); busy = false; if (text == null) failed = true }
        }
        val cameraFile = remember { File(File(context.cacheDir, "scans").apply { mkdirs() }, "scan.jpg") }
        val cameraUri = remember { FileProvider.getUriForFile(context, context.packageName + ".files", cameraFile) }
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) read(cameraUri) }
        val gallery = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::read) }
        PennyScreen(stringResource(R.string.scan_receipt), onBack, large = false) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                Text(stringResource(R.string.scan_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { camera.launch(cameraUri) }) { Icon(Icons.Outlined.CameraAlt, null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.take_photo)) }
                    OutlinedButton(onClick = { gallery.launch(arrayOf("image/*")) }) { Icon(Icons.Outlined.Image, null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.choose_image)) }
                }
                if (busy) Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.size(12.dp)); Text(stringResource(R.string.reading_receipt)) }
                if (failed) Text(stringResource(R.string.ocr_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
                text?.let { t ->
                    val parsed = ReceiptParser.parse(t, ctx.base, b.today())
                    if (parsed == null) Text(stringResource(R.string.ocr_no_total), modifier = Modifier.padding(20.dp))
                    else {
                        parsed.tax?.let { Text(stringResource(R.string.tax_found, ctx.money(it, parsed.currency)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
                        if (parsed.lines.isNotEmpty()) Text(stringResource(R.string.items_found, parsed.lines.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
                        val proposal = Transaction(
                            type = TxType.EXPENSE, amountMinor = parsed.amountMinor, currency = parsed.currency,
                            accountId = b.profile.defaultAccountId ?: b.activeAccounts.firstOrNull()?.state?.id.orEmpty(), payee = parsed.payee, date = parsed.date ?: b.today(),
                            tz = b.profile.timezone, source = "ocr", confidence = parsed.confidence, note = parsed.lines.joinToString(", ") { it.first }.take(300),
                        )
                        androidx.compose.runtime.key(t) {
                            EditProposal(ctx, proposal, R.string.save) { tx ->
                                vm.act(R.string.added_tx, after = onDone) { book ->
                                    // Keep the receipt image as an attachment with its OCR text (SR-01 searches it).
                                    val att = withContext(Dispatchers.IO) { copyReceipt(context, Uri.parse(image), t) }
                                    val g = transactions.newGroup()
                                    val aid = att?.let { newId().also { id -> attachments.create(id, book.profileId, it, g) } }
                                    val res = saveTransaction(book, null, tx.copy(attachmentIds = listOfNotNull(aid)))
                                    aid?.let { id -> attachments.get(id)?.let { a -> attachments.update(id, a.value.copy(transactionId = res.id), groupId = g) } }
                                    res.group
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copyReceipt(context: android.content.Context, uri: Uri, ocr: String): Attachment? = runCatching {
    val dir = File(context.filesDir, "attachments").apply { mkdirs() }
    val name = UUID.randomUUID().toString() + ".jpg"
    val out = File(dir, name)
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    val bmp = context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
    bmp.recycle()
    Attachment("pending", "receipt.jpg", "image/jpeg", out.length(), "attachments/$name", ocrText = ocr.take(4000))
}.getOrNull()
