package app.penny.ui.home

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.penny.R
import app.penny.core.budget.Ledger
import app.penny.core.budget.SafeToSpend
import app.penny.core.budget.SafeToSpendCalculator
import app.penny.core.event.Stored
import app.penny.core.model.Category
import app.penny.core.model.Profile
import app.penny.core.model.TxType
import app.penny.core.period.CycleRule
import app.penny.core.period.Period
import app.penny.core.period.PeriodContext
import app.penny.core.period.Periods
import app.penny.domain.PennyRepository
import app.penny.domain.TxItem
import app.penny.ui.common.MoneyFmt
import app.penny.ui.common.UndoController
import app.penny.ui.common.UndoMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class AccountUi(val id: String, val name: String, val balance: String)
data class TxRowUi(val id: String, val title: String, val subtitle: String, val amount: String, val inflow: Boolean, val date: LocalDate)

data class HomeData(
    val profile: Profile,
    val fmt: MoneyFmt,
    val period: Period,
    val today: LocalDate,
    val offset: Int,
    val safe: SafeToSpend,
    val safeText: String,
    val perDayText: String,
    val incomeText: String,
    val spentText: String,
    val accounts: List<AccountUi>,
    val rows: List<TxRowUi>,
)

sealed interface HomeUi {
    data object Loading : HomeUi
    data object NeedsOnboarding : HomeUi
    data object Error : HomeUi
    data class Ready(val data: HomeData) : HomeUi
}

/** Category ids excluded from spend, including children of excluded parents (CAT-04). */
fun excludedIds(cats: List<Stored<Category>>): Set<String> {
    val byId = cats.associateBy { it.state.id }
    fun excluded(id: String?): Boolean {
        var c = id?.let(byId::get)
        while (c != null) {
            if (c.value.excludedFromSpend) return true
            c = c.value.parentId?.let(byId::get)
        }
        return false
    }
    return cats.map { it.state.id }.filter(::excluded).toSet()
}

fun categoryPath(cats: Map<String, Stored<Category>>, id: String?): String? {
    val c = id?.let(cats::get) ?: return null
    val parent = c.value.parentId?.let(cats::get)
    return if (parent != null) "${parent.value.name} › ${c.value.name}" else c.value.name
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: PennyRepository,
    private val undo: UndoController,
    private val handle: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    /** Period offset survives process death (UX-07). */
    private val offset = handle.getStateFlow(KEY_OFFSET, 0)

    val ui: StateFlow<HomeUi> = repo.observeProfile().flatMapLatest { stored ->
        if (stored == null) {
            flowOf<HomeUi>(if (repo.currentProfileId.value == null) HomeUi.NeedsOnboarding else HomeUi.Loading)
        } else {
            val pid = stored.state.id
            combine(repo.observeAccounts(pid), repo.observeCategories(pid), repo.observeTransactions(pid), offset) { accts, cats, txs, off ->
                HomeUi.Ready(build(stored.value, accts.map { it.state.id to it.value }, cats, txs, off)) as HomeUi
            }
        }
    }.catch { emit(HomeUi.Error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi.Loading)

    private fun build(
        profile: Profile,
        accts: List<Pair<String, app.penny.core.model.FinancialAccount>>,
        cats: List<Stored<Category>>,
        txs: List<TxItem>,
        off: Int,
    ): HomeData {
        val pack = repo.packs.get(profile.regionPack)
        val fmt = MoneyFmt.of(profile, pack)
        val today = LocalDate.now(ZoneId.of(profile.timezone))
        val ctx = PeriodContext(incomeDates(profile, txs))
        val period = Periods.shift(Periods.periodFor(today, profile.cycle, ctx), off, profile.cycle, ctx)
        val all = txs.map { it.tx }
        val safe = SafeToSpendCalculator.compute(all, period, today, profile.baseCurrency, excludedIds(cats))
        val catMap = cats.associateBy { it.state.id }
        val acctNames = accts.toMap()
        val rows = txs.filter { it.tx.date in period }
            .sortedWith(compareByDescending<TxItem> { it.tx.date }.thenByDescending { it.tx.time }.thenByDescending { it.updatedAt })
            .map { item ->
                val t = item.tx
                val cat = categoryPath(catMap, t.categoryId)
                val acct = acctNames[t.accountId]?.name.orEmpty()
                val title = t.payee?.takeIf { it.isNotBlank() }
                    ?: if (t.type == TxType.TRANSFER) context.getString(R.string.transfer_to, acctNames[t.toAccountId]?.name.orEmpty())
                    else cat ?: context.getString(R.string.uncategorised)
                val inflow = t.type == TxType.INCOME || t.type == TxType.REFUND ||
                    (t.type == TxType.ADJUSTMENT && t.direction == app.penny.core.model.Direction.IN)
                TxRowUi(
                    id = item.id,
                    title = title,
                    subtitle = listOfNotNull(cat.takeIf { t.payee?.isNotBlank() == true }, acct).joinToString(" · "),
                    amount = fmt.format(if (inflow || t.type == TxType.TRANSFER) t.amountMinor else -t.amountMinor, t.currency, signed = inflow),
                    inflow = inflow,
                    date = t.date,
                )
            }
        return HomeData(
            profile = profile, fmt = fmt, period = period, today = today, offset = off, safe = safe,
            safeText = fmt.format(safe.safeMinor, profile.baseCurrency),
            perDayText = fmt.format(safe.perDayMinor, profile.baseCurrency),
            incomeText = fmt.format(safe.incomeMinor, profile.baseCurrency),
            spentText = fmt.format(safe.spentMinor, profile.baseCurrency),
            accounts = accts.map { (id, a) -> AccountUi(id, a.name, fmt.format(Ledger.balance(id, a, all), a.currency)) },
            rows = rows,
        )
    }

    private fun incomeDates(profile: Profile, txs: List<TxItem>): List<LocalDate> {
        val rule = profile.cycle as? CycleRule.OnIncome ?: return emptyList()
        return txs.map { it.tx }.filter {
            it.type == TxType.INCOME && it.currency == profile.baseCurrency && it.amountMinor >= rule.thresholdMinor
        }.map { it.date }
    }

    fun shiftPeriod(delta: Int) { handle[KEY_OFFSET] = if (delta == 0) 0 else offset.value + delta }

    fun delete(id: String) = viewModelScope.launch {
        val g = repo.transactions.delete(id)
        undo.offer(UndoMessage(context.getString(R.string.deleted_tx), undo = { repo.transactions.undo(g) }))
    }

    private companion object { const val KEY_OFFSET = "period_offset" }
}
