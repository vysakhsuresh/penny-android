package app.penny.ui.tx

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.penny.R
import app.penny.core.event.PennyJson
import app.penny.core.event.Stored
import app.penny.core.model.Category
import app.penny.core.model.CategoryKind
import app.penny.core.model.DefaultCategories
import app.penny.core.model.Direction
import app.penny.core.model.FinancialAccount
import app.penny.core.model.Profile
import app.penny.core.model.Transaction
import app.penny.core.model.TransactionValidator
import app.penny.core.model.TxError
import app.penny.core.model.TxStatus
import app.penny.core.model.TxType
import app.penny.core.money.AmountCalculator
import app.penny.core.money.AmountError
import app.penny.core.money.AmountResult
import app.penny.core.money.CurrencyUnit
import app.penny.domain.PennyRepository
import app.penny.ui.common.MoneyFmt
import app.penny.ui.common.UndoController
import app.penny.ui.common.UndoMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

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
    val tags: String = "",
    val status: TxStatus = TxStatus.CLEARED,
    val direction: Direction? = null,
    val isPrivate: Boolean = false,
    val excludeBudget: Boolean = false,
    val excludeReports: Boolean = false,
)

data class Option(val id: String, val label: String)

data class FormContext(
    val profile: Profile,
    val fmt: MoneyFmt,
    val accounts: List<Stored<FinancialAccount>>,
    val categories: List<Stored<Category>>,
)

enum class LoadState { LOADING, READY, MISSING }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionFormViewModel @Inject constructor(
    private val repo: PennyRepository,
    private val undo: UndoController,
    private val handle: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val txId: String? = handle.get<String>(ARG_ID)?.takeIf { it != NEW }
    val isEdit get() = txId != null

    private val _form = MutableStateFlow(handle.get<String>(KEY_FORM)?.let { PennyJson.decodeFromString(TxForm.serializer(), it) })
    val form: StateFlow<TxForm?> = _form
    private val _load = MutableStateFlow(if (_form.value != null) LoadState.READY else LoadState.LOADING)
    val load: StateFlow<LoadState> = _load
    private val _errors = MutableStateFlow<List<String>>(emptyList())
    val errors: StateFlow<List<String>> = _errors
    private var original: Transaction? = null

    val ctx: StateFlow<FormContext?> = repo.observeProfile().filterNotNull().flatMapLatest { p ->
        val pid = p.state.id
        kotlinx.coroutines.flow.combine(repo.observeAccounts(pid), repo.observeCategories(pid)) { a, c ->
            FormContext(p.value, MoneyFmt.of(p.value, repo.packs.get(p.value.regionPack)), a.filter { !it.value.archived }, c)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            val c = ctx.filterNotNull().first()
            val existing = txId?.let { repo.transactions.get(it) }
            original = existing?.value
            if (_form.value == null) {
                when {
                    txId != null && existing == null -> { _load.value = LoadState.MISSING; return@launch }
                    existing != null -> set(fromTx(existing.value, c.fmt))
                    else -> {
                        val acct = c.profile.defaultAccountId?.let { id -> c.accounts.firstOrNull { it.state.id == id } } ?: c.accounts.firstOrNull()
                        set(
                            TxForm(
                                currency = acct?.value?.currency ?: c.profile.baseCurrency,
                                accountId = acct?.state?.id.orEmpty(),
                                date = LocalDate.now(ZoneId.of(c.profile.timezone)).toString(),
                                time = LocalTime.now(ZoneId.of(c.profile.timezone)).withSecond(0).withNano(0).toString(),
                            ),
                        )
                    }
                }
            }
            _load.value = LoadState.READY
        }
    }

    private fun fromTx(t: Transaction, fmt: MoneyFmt) = TxForm(
        type = t.type, amount = fmt.editable(t.amountMinor, t.currency), currency = t.currency, accountId = t.accountId,
        toAccountId = t.toAccountId, toAmount = t.toAmountMinor?.let { fmt.editable(it, t.toCurrency ?: t.currency) }.orEmpty(),
        categoryId = t.categoryId, payee = t.payee.orEmpty(), date = t.date.toString(), time = t.time?.toString(), note = t.note,
        tags = t.tags.joinToString(", "), status = t.status, direction = t.direction, isPrivate = t.isPrivate,
        excludeBudget = t.excludeFromBudget, excludeReports = t.excludeFromReports,
    )

    fun update(f: (TxForm) -> TxForm) { _form.value?.let { set(f(it)) } }

    private fun set(f: TxForm) {
        _form.value = f
        handle[KEY_FORM] = PennyJson.encodeToString(TxForm.serializer(), f)
    }

    /** Selecting an account switches the currency to the account's currency (AC-04). */
    fun selectAccount(id: String) {
        val cur = ctx.value?.accounts?.firstOrNull { it.state.id == id }?.value?.currency
        update { it.copy(accountId = id, currency = cur ?: it.currency) }
    }

    fun categoryOptions(c: FormContext, type: TxType): List<Option> {
        val kind = if (type == TxType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        val byId = c.categories.associateBy { it.state.id }
        return c.categories.filter { it.value.kind == kind }
            .sortedBy { it.value.sort }
            .map { s ->
                val parent = s.value.parentId?.let(byId::get)?.value?.name
                Option(s.state.id, if (parent != null) "$parent › ${s.value.name}" else s.value.name)
            }
    }

    fun preview(f: TxForm, c: FormContext): AmountResult? = runCatching {
        if (f.amount.isBlank()) null else AmountCalculator.evaluate(f.amount, CurrencyUnit.of(f.currency), c.fmt.spec)
    }.getOrNull()

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

    fun save(onDone: () -> Unit) {
        val f = _form.value ?: return
        val c = ctx.value ?: return
        val errs = mutableListOf<String>()
        val amount = when (val r = AmountCalculator.evaluate(f.amount, CurrencyUnit.of(f.currency), c.fmt.spec)) {
            is AmountResult.Ok -> r.minor
            is AmountResult.Invalid -> { errs += amountErrorText(r.error, f.currency); 0L }
        }
        val toAccount = c.accounts.firstOrNull { it.state.id == f.toAccountId }?.value
        val toCurrency = toAccount?.currency?.takeIf { f.type == TxType.TRANSFER }
        val toAmount = if (f.type == TxType.TRANSFER && toCurrency != null && toCurrency != f.currency && f.toAmount.isNotBlank()) {
            (AmountCalculator.evaluate(f.toAmount, CurrencyUnit.of(toCurrency), c.fmt.spec) as? AmountResult.Ok)?.minor
        } else {
            null
        }
        val base = original
            ?: Transaction(type = f.type, amountMinor = 0, currency = f.currency, accountId = "", date = LocalDate.now(), tz = c.profile.timezone)
        val tx = base.copy(
            type = f.type,
            amountMinor = amount,
            currency = f.currency,
            accountId = f.accountId,
            toAccountId = f.toAccountId.takeIf { f.type == TxType.TRANSFER },
            toAmountMinor = toAmount,
            toCurrency = toCurrency.takeIf { it != f.currency },
            categoryId = f.categoryId.takeIf { f.type != TxType.TRANSFER }
                // Categories are seeded as "<profileId>:<key>"; adjustments default to "Adjustments" (TX-01).
                ?: (if (f.type == TxType.ADJUSTMENT) "${repo.currentProfileId.value}:${DefaultCategories.ADJUSTMENTS}" else null),
            payee = f.payee.trim().ifEmpty { null },
            date = LocalDate.parse(f.date),
            time = f.time?.let(LocalTime::parse),
            note = f.note.trim(),
            tags = f.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            status = f.status,
            direction = f.direction.takeIf { f.type == TxType.ADJUSTMENT },
            isPrivate = f.isPrivate,
            excludeFromBudget = f.excludeBudget,
            excludeFromReports = f.excludeReports,
        )
        if (errs.isEmpty()) {
            errs += TransactionValidator.validate(tx) { id -> c.accounts.firstOrNull { it.state.id == id }?.value?.currency }
                .filter { it != TxError.AMOUNT_NOT_POSITIVE }
                .map(::txErrorText)
        }
        _errors.value = errs
        if (errs.isNotEmpty()) return
        viewModelScope.launch {
            val pid = repo.currentProfileId.value ?: return@launch
            if (txId == null) repo.transactions.create(repo.newId(), pid, tx) else repo.transactions.update(txId, tx)
            handle.remove<String>(KEY_FORM)
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

    companion object {
        const val ARG_ID = "id"
        const val NEW = "new"
        private const val KEY_FORM = "form"
    }
}
