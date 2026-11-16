package app.penny.ui.tx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.penny.R
import app.penny.core.event.PennyJson
import app.penny.core.model.Attachment
import app.penny.core.model.CategoryKind
import app.penny.core.model.DefaultCategories
import app.penny.core.model.Direction
import app.penny.core.model.GeoPoint
import app.penny.core.model.Split
import app.penny.core.model.Template
import app.penny.core.model.Transaction
import app.penny.core.model.TransactionValidator
import app.penny.core.model.TxError
import app.penny.core.model.TxStatus
import app.penny.core.model.TxType
import app.penny.core.money.AmountCalculator
import app.penny.core.money.AmountError
import app.penny.core.money.AmountResult
import app.penny.core.money.CurrencyUnit
import app.penny.domain.Book
import app.penny.domain.BookLoad
import app.penny.domain.BookStore
import app.penny.domain.PennyRepository
import app.penny.domain.ops.mergeTransactions
import app.penny.domain.ops.payeeMemory
import app.penny.domain.ops.saveTransaction
import app.penny.ui.common.MoneyFmt
import app.penny.ui.common.UndoController
import app.penny.ui.common.UndoMessage
import app.penny.ui.kit.Choice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject

@Serializable
data class SplitForm(val amount: String = "", val categoryId: String? = null, val note: String = "", val tags: String = "")

/** Form state; serialised into SavedStateHandle so input survives rotation and process death (UX-04). */
@Serializable
data class TxForm(
    val type: TxType = TxType.EXPENSE,
    val amount: String = "",
    val currency: String = "",
    val accountId: String = "",
    val toAccountId: String? = null,
    val toAmount: String = "",
    val categoryId: String? = null,
    val payee: String = "",
    val date: String = "",
    val time: String? = null,
    val note: String = "",
    val tags: List<String> = emptyList(),
    val status: TxStatus = TxStatus.CLEARED,
    val direction: Direction? = null,
    val isPrivate: Boolean = false,
    val excludeBudget: Boolean = false,
    val excludeReports: Boolean = false,
    val splits: List<SplitForm> = emptyList(),
    val splitByPercent: Boolean = false,
    val attachmentIds: List<String> = emptyList(),
    val place: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val viaAccountId: String? = null,
    val distance: String = "",
)

enum class LoadState { LOADING, READY, MISSING }

data class DupePrompt(val savedId: String, val group: String, val duplicates: List<String>)

@HiltViewModel
class TransactionFormViewModel @Inject constructor(
    private val repo: PennyRepository,
    private val store: BookStore,
    private val undo: UndoController,
    private val handle: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val txId: String? = handle.get<String>(ARG_ID)?.takeIf { it != NEW }
    val isEdit get() = txId != null
    val state = store.state

    private val _form = MutableStateFlow(handle.get<String>(KEY_FORM)?.let { runCatching { PennyJson.decodeFromString(TxForm.serializer(), it) }.getOrNull() })
    val form: StateFlow<TxForm?> = _form
    private val _load = MutableStateFlow(if (_form.value != null) LoadState.READY else LoadState.LOADING)
    val load: StateFlow<LoadState> = _load
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors
    private val _dupes = MutableStateFlow<DupePrompt?>(null)
    val dupes: StateFlow<DupePrompt?> = _dupes
    private var original: Transaction? = null
    /** The form exactly as loaded from the record; equal to the current form while the user has not edited it. */
    private var loaded: TxForm? = null

    /** Text typed in the tags box but not yet turned into a chip; Save keeps it rather than dropping it. */
    var pendingTag: String = ""

    /** True when leaving would lose typing: the form differs from what it opened with (or was restored after a kill). */
    fun hasUnsavedChanges(): Boolean = _form.value.let { f -> f != null && f != loaded }

    init {
        viewModelScope.launch {
            val ready = store.state.filterIsInstance<BookLoad.Ready>().first()
            val b = ready.book
            val existing = txId?.let { b.txMap[it] }
            original = existing?.value
            if (_form.value == null) {
                when {
                    txId != null && existing == null -> { _load.value = LoadState.MISSING; return@launch }
                    existing != null -> fromTx(existing.value, ready.fmt).let { loaded = it; set(it) }
                    else -> newForm(b, ready.fmt).let { loaded = it; set(it) }
                }
            }
            _load.value = LoadState.READY
            // The record can change while this form is open (Revert in History, sync, a rule). Untouched forms follow
            // it; a form with unsaved edits keeps them, so saving never silently undoes the other change.
            if (txId != null) store.state.filterIsInstance<BookLoad.Ready>().collect { r ->
                val cur = r.book.txMap[txId]?.value ?: return@collect
                if (cur == original) return@collect
                val untouched = loaded != null && _form.value == loaded
                original = cur
                if (untouched) fromTx(cur, r.fmt).let { loaded = it; set(it) }
            }
        }
    }


    private fun newForm(b: Book, fmt: MoneyFmt): TxForm {
        val acct = b.profile.defaultAccountId?.let { id -> b.activeAccounts.firstOrNull { it.state.id == id } } ?: b.activeAccounts.firstOrNull()
        val type = handle.get<String>(ARG_TYPE)?.let { runCatching { TxType.valueOf(it) }.getOrNull() } ?: b.profile.defaultTxType
        val amountArg = handle.get<String>(ARG_AMOUNT)?.toLongOrNull()
        val currency = b.profile.travelCurrency ?: acct?.value?.currency ?: b.profile.baseCurrency
        val catArg = handle.get<String>(ARG_CATEGORY)
        val payeeArg = handle.get<String>(ARG_PAYEE)
        val noteArg = handle.get<String>(ARG_NOTE)
        return TxForm(
            type = type,
            amount = amountArg?.let { fmt.editable(it, currency) }.orEmpty(),
            currency = currency,
            accountId = acct?.state?.id.orEmpty(),
            categoryId = catArg?.let { c -> b.categoryMap[c]?.state?.id ?: b.categories.firstOrNull { it.value.name.equals(c, true) || it.value.key == c }?.state?.id },
            payee = payeeArg.orEmpty(),
            note = noteArg.orEmpty(),
            date = b.today().toString(),
            time = LocalTime.now(b.zone).withSecond(0).withNano(0).toString(),
        )
    }

    private fun fromTx(t: Transaction, fmt: MoneyFmt) = TxForm(
        type = t.type, amount = fmt.editable(t.amountMinor, t.currency), currency = t.currency, accountId = t.accountId,
        toAccountId = t.toAccountId, toAmount = t.toAmountMinor?.let { fmt.editable(it, t.toCurrency ?: t.currency) }.orEmpty(),
        categoryId = t.categoryId, payee = t.payee.orEmpty(), date = t.date.toString(), time = t.time?.toString(), note = t.note,
        tags = t.tags, status = t.status, direction = t.direction, isPrivate = t.isPrivate,
        excludeBudget = t.excludeFromBudget, excludeReports = t.excludeFromReports,
        splits = t.splits.map { SplitForm(fmt.editable(it.amountMinor, t.currency), it.categoryId, it.note, it.tags.joinToString(", ")) },
        attachmentIds = t.attachmentIds, place = t.location?.label.orEmpty(), lat = t.location?.lat, lon = t.location?.lon,
        viaAccountId = t.viaAccountId, distance = t.distance?.toString().orEmpty(),
    )

    fun update(f: (TxForm) -> TxForm) { _form.value?.let { set(f(it)) } }

    /** TX-12: the stored record (not the form being edited) is reconciled. */
    fun savedAsReconciled() = original?.status == TxStatus.RECONCILED

    private fun set(f: TxForm) {
        _form.value = f
        handle[KEY_FORM] = PennyJson.encodeToString(TxForm.serializer(), f)
    }

    private fun ready() = store.state.value as? BookLoad.Ready

    /** Selecting an account switches the currency to the account's currency (AC-04). */
    fun selectAccount(id: String) {
        val cur = ready()?.book?.accountMap?.get(id)?.value?.currency
        update { it.copy(accountId = id, currency = cur ?: it.currency) }
    }

    /** TX-15 payee memory: fills category, account and amount when they are still empty. */
    fun payeePicked(name: String) {
        val r = ready() ?: return
        val mem = r.book.payeeMemory(name)
        update { f ->
            var n = f.copy(payee = name)
            if (mem != null) {
                if (n.categoryId == null && n.type != TxType.TRANSFER) n = n.copy(categoryId = mem.categoryId)
                if (n.amount.isBlank() && mem.typicalMinor != null) n = n.copy(amount = r.fmt.editable(mem.typicalMinor, n.currency))
                if (!isEdit && mem.accountId != null && r.book.accountMap[mem.accountId]?.value?.archived == false) {
                    n = n.copy(accountId = mem.accountId, currency = r.book.accountMap[mem.accountId]?.value?.currency ?: n.currency)
                }
            }
            n
        }
    }

    fun payeeSuggestions(prefix: String): List<String> {
        val b = ready()?.book ?: return emptyList()
        if (prefix.length < 1) return emptyList()
        return (b.payees.map { it.value.name } + b.txList.mapNotNull { it.payee }).distinct()
            .filter { it.contains(prefix, ignoreCase = true) && !it.equals(prefix, ignoreCase = true) }.take(5)
    }

    fun categoryChoices(b: Book, type: TxType): List<Choice<String>> {
        val kind = if (type == TxType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        return b.categoriesOf(kind).map { Choice(it.state.id, b.categoryName(it.state.id)) }
    }

    fun amountErrorText(e: AmountError, currency: String): String {
        val unit = runCatching { CurrencyUnit.of(currency) }.getOrNull()
        return when (e) {
            AmountError.EMPTY -> context.getString(R.string.err_amount_empty)
            AmountError.INVALID -> context.getString(R.string.err_amount_invalid)
            AmountError.NOT_POSITIVE -> context.getString(R.string.err_amount_positive)
            AmountError.TOO_MANY_DECIMALS ->
                if (unit?.minorUnits == 0) context.getString(R.string.err_amount_decimals_none, currency)
                else context.getString(R.string.err_amount_decimals, currency, unit?.minorUnits ?: 2)
            AmountError.TOO_LARGE -> context.getString(R.string.err_amount_large)
            AmountError.DIVIDE_BY_ZERO -> context.getString(R.string.err_divide_zero)
        }
    }

    private fun txErrorText(e: TxError) = context.getString(
        when (e) {
            TxError.AMOUNT_NOT_POSITIVE -> R.string.err_amount_positive
            TxError.AMOUNT_TOO_LARGE -> R.string.err_amount_large
            TxError.ACCOUNT_REQUIRED -> R.string.err_account_required
            TxError.TRANSFER_TARGET_REQUIRED -> R.string.err_transfer_target
            TxError.TRANSFER_SAME_ACCOUNT -> R.string.err_transfer_same
            TxError.TRANSFER_TO_AMOUNT_REQUIRED -> R.string.err_transfer_to_amount
            TxError.ADJUSTMENT_DIRECTION_REQUIRED -> R.string.err_direction
            TxError.SPLITS_DO_NOT_SUM, TxError.SPLIT_NOT_POSITIVE -> R.string.err_splits
        },
    )

    private fun eval(text: String, currency: String, fmt: MoneyFmt): Long? =
        (runCatching { AmountCalculator.evaluate(text, CurrencyUnit.of(currency), fmt.spec) }.getOrNull() as? AmountResult.Ok)?.minor

    /** TX-07 live: minor units left to split (total − splits); percent mode converts basis points. */
    fun splitRemaining(f: TxForm, fmt: MoneyFmt): Long? {
        val total = eval(f.amount, f.currency, fmt) ?: return null
        return total - splitAmounts(f, fmt, total).sum()
    }

    private fun splitAmounts(f: TxForm, fmt: MoneyFmt, total: Long): List<Long> = if (f.splitByPercent) {
        val bps = f.splits.map { (it.amount.replace(',', '.').toBigDecimalOrNull()?.multiply(java.math.BigDecimal(100))?.toInt() ?: 0) }
        app.penny.core.rules.RuleEngine.splitByBp(total, bps.map { it to null }).map { it.amountMinor }.let { lines ->
            // Only a full 100% assigns the rounding remainder; otherwise use plain shares.
            if (bps.sum() == 10_000) lines else bps.map { total * it / 10_000 }
        }
    } else f.splits.map { eval(it.amount, f.currency, fmt) ?: 0 }

    fun build(f: TxForm, b: Book, fmt: MoneyFmt): Pair<Transaction?, Map<String, String>> {
        val errs = linkedMapOf<String, String>()
        val amount = when (val r = runCatching { AmountCalculator.evaluate(f.amount, CurrencyUnit.of(f.currency), fmt.spec) }.getOrElse { AmountResult.Invalid(AmountError.INVALID) }) {
            is AmountResult.Ok -> r.minor
            is AmountResult.Invalid -> { errs["amount"] = amountErrorText(r.error, f.currency); 0L }
        }
        val toAccount = b.accountMap[f.toAccountId]?.value
        val toCurrency = toAccount?.currency?.takeIf { f.type == TxType.TRANSFER }
        val toAmount = if (f.type == TxType.TRANSFER && toCurrency != null && toCurrency != f.currency && f.toAmount.isNotBlank()) eval(f.toAmount, toCurrency, fmt) else null
        val splits = if (f.splits.isEmpty() || f.type == TxType.TRANSFER) emptyList() else {
            val amts = splitAmounts(f, fmt, amount)
            f.splits.mapIndexed { i, s -> Split(amts[i], s.categoryId, s.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }, s.note.trim()) }
        }
        val date = runCatching { LocalDate.parse(f.date) }.getOrElse { b.today() }
        val base = original ?: Transaction(type = f.type, amountMinor = 0, currency = f.currency, accountId = "", date = date, tz = b.profile.timezone)
        val tx = base.copy(
            type = f.type, amountMinor = amount, currency = f.currency, accountId = f.accountId,
            toAccountId = f.toAccountId.takeIf { f.type == TxType.TRANSFER },
            toAmountMinor = toAmount, toCurrency = toCurrency.takeIf { it != f.currency },
            categoryId = (f.categoryId.takeIf { f.type != TxType.TRANSFER && splits.isEmpty() })
                ?: (if (f.type == TxType.ADJUSTMENT) "${b.profileId}:${DefaultCategories.ADJUSTMENTS}" else null),
            payee = f.payee.trim().ifEmpty { null }, date = date, time = f.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
            note = f.note.trim(), tags = (f.tags + pendingTag).map { it.trim() }.filter { it.isNotEmpty() }.distinct(), status = f.status,
            direction = f.direction.takeIf { f.type == TxType.ADJUSTMENT }, isPrivate = f.isPrivate,
            excludeFromBudget = f.excludeBudget, excludeFromReports = f.excludeReports, splits = splits,
            attachmentIds = f.attachmentIds,
            location = if (f.place.isBlank() && f.lat == null) null else GeoPoint(f.lat ?: 0.0, f.lon ?: 0.0, f.place.trim().ifEmpty { null }),
            viaAccountId = f.viaAccountId, distance = f.distance.replace(',', '.').toDoubleOrNull(),
            // Keep the account-currency amount only while amount, currency, account and date are unchanged; otherwise
            // the repository recomputes it at the day's rate.
            accountAmountMinor = original?.takeIf { o -> o.amountMinor == amount && o.currency == f.currency && o.accountId == f.accountId && o.date == date }?.accountAmountMinor,
        )
        if (errs.isEmpty()) {
            TransactionValidator.validate(tx) { id -> b.accountMap[id]?.value?.currency }.forEach { e ->
                val key = when (e) {
                    TxError.ACCOUNT_REQUIRED -> "account"
                    TxError.TRANSFER_TARGET_REQUIRED, TxError.TRANSFER_SAME_ACCOUNT -> "toAccount"
                    TxError.TRANSFER_TO_AMOUNT_REQUIRED -> "toAmount"
                    TxError.ADJUSTMENT_DIRECTION_REQUIRED -> "direction"
                    TxError.SPLITS_DO_NOT_SUM, TxError.SPLIT_NOT_POSITIVE -> "splits"
                    else -> "amount"
                }
                errs.putIfAbsent(key, txErrorText(e))
            }
        }
        return (if (errs.isEmpty()) tx else null) to errs
    }

    private var saving = false

    fun save(onDone: () -> Unit) {
        val f = _form.value ?: return
        val r = ready() ?: return
        val (tx, errs) = build(f, r.book, r.fmt)
        _errors.value = errs
        if (tx == null) return
        // Second tap while the first save is writing: ignore (would create a duplicate and pop twice).
        if (saving) return
        saving = true
        viewModelScope.launch {
            val res = try {
                withContext(Dispatchers.Default) {
                    runCatching {
                        repo.saveTransaction(r.book, txId, tx).also { res ->
                            // Attachments picked before the first save are linked to the new transaction in the same undo group.
                            tx.attachmentIds.forEach { aid ->
                                repo.attachments.get(aid)?.let { a -> if (a.value.transactionId != res.id) repo.attachments.update(aid, a.value.copy(transactionId = res.id), groupId = res.group) }
                            }
                        }
                    }
                }.getOrElse { e ->
                    android.util.Log.w("Penny", "save failed", e)
                    undo.offer(UndoMessage(context.getString(R.string.action_failed), undo = null))
                    return@launch
                }
            } finally {
                saving = false
            }
            handle.remove<String>(KEY_FORM)
            if (res.duplicates.isNotEmpty()) {
                _dupes.value = DupePrompt(res.id, res.group, res.duplicates)
            } else {
                undo.offer(UndoMessage(context.getString(if (isEdit) R.string.saved_tx else R.string.added_tx), undo = { repo.undo(res.group) }))
                onDone()
            }
        }
    }

    /** TX-10 "Keep both" or "Merge". */
    fun resolveDupes(merge: Boolean, onDone: () -> Unit) {
        val p = _dupes.value ?: return
        viewModelScope.launch {
            if (merge) {
                val book = store.state.filterIsInstance<BookLoad.Ready>().first { it.book.txMap.containsKey(p.savedId) }.book
                val keep = p.duplicates.first()
                val g = repo.mergeTransactions(book, keep, listOf(p.savedId))
                undo.offer(UndoMessage(context.getString(R.string.merged), undo = { repo.undo(g) }))
            } else {
                undo.offer(UndoMessage(context.getString(R.string.added_tx), undo = { repo.undo(p.group) }))
            }
            _dupes.value = null
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = txId ?: return
        viewModelScope.launch {
            val g = repo.transactions.delete(id)
            undo.offer(UndoMessage(context.getString(R.string.deleted_tx), undo = { repo.transactions.undo(g) }))
            onDone()
        }
    }

    /** TX-16 save as template. */
    fun saveTemplate(name: String) {
        val f = _form.value ?: return
        val r = ready() ?: return
        val amount = eval(f.amount, f.currency, r.fmt)
        viewModelScope.launch {
            val g = repo.templates.newGroup()
            repo.templates.create(repo.newId(), r.book.profileId, Template(name, f.type, amount, f.currency, f.accountId, f.toAccountId, f.categoryId, f.payee.ifBlank { null }, f.note, f.tags), g)
            undo.offer(UndoMessage(context.getString(R.string.template_saved), undo = { repo.undo(g) }))
        }
    }

    /**
     * TX-11: copies the picked file into app-private storage. Images are downsampled to 1600 px and re-encoded as JPEG
     * on device; PDFs and other files are copied as they are. Returns after creating the attachment entity.
     */
    fun attach(uri: Uri) {
        val r = ready() ?: return
        viewModelScope.launch {
            val att = withContext(Dispatchers.IO) { copyIn(uri) } ?: return@launch
            val id = repo.newId()
            repo.attachments.create(id, r.book.profileId, att.copy(transactionId = txId ?: "pending"))
            update { it.copy(attachmentIds = it.attachmentIds + id) }
        }
    }

    fun removeAttachment(id: String) = update { it.copy(attachmentIds = it.attachmentIds - id) }

    private fun copyIn(uri: Uri): Attachment? = runCatching {
        val cr = context.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val outName = UUID.randomUUID().toString() + if (mime.startsWith("image/")) ".jpg" else "." + name.substringAfterLast('.', "bin")
        val out = File(dir, outName)
        if (mime.startsWith("image/")) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            bmp.recycle()
        } else {
            cr.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        Attachment("", name, if (mime.startsWith("image/")) "image/jpeg" else mime, out.length(), "attachments/$outName")
    }.getOrNull()

    companion object {
        const val ARG_ID = "id"
        const val ARG_TYPE = "type"
        const val ARG_AMOUNT = "amount"
        const val ARG_CATEGORY = "category"
        const val ARG_PAYEE = "payee"
        const val ARG_NOTE = "note"
        const val NEW = "new"
        private const val KEY_FORM = "form"
    }
}
