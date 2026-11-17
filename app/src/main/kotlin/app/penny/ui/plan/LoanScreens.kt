package app.penny.ui.plan

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.core.model.AccountType
import app.penny.core.model.Loan
import app.penny.core.model.LoanKind
import app.penny.core.model.Recurring
import app.penny.core.model.RecurringKind
import app.penny.core.model.TxType
import app.penny.core.planning.Debt
import app.penny.core.schedule.Recurrence
import app.penny.domain.ops.balance
import app.penny.domain.ops.saveLoan
import app.penny.domain.ops.saveRecurring
import app.penny.ui.common.WithBook
import app.penny.ui.common.shortDate
import app.penny.ui.kit.AmountInput
import app.penny.ui.kit.Choice
import app.penny.ui.kit.DateInput
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.LineChart
import app.penny.ui.kit.ListRow
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.PrimaryButton
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.Segmented
import app.penny.ui.kit.SelectField
import app.penny.ui.kit.StatBlock
import app.penny.ui.kit.SwitchRow
import app.penny.ui.kit.TextInput
import app.penny.ui.kit.parseAmount

/** DB-01/DB-02: every debt in one planner — loans, EMIs and credit cards — with avalanche, snowball or custom order. */
@Composable
fun LoansScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    WithBook { ctx, _ ->
        val b = ctx.book
        val today = b.today()
        var strategy by rememberSaveable { mutableStateOf(Debt.Strategy.AVALANCHE) }
        var extra by rememberSaveable { mutableStateOf("") }
        val loans = b.loans.filter { !it.value.closed }
        val debtors = loans.map { l -> Debt.summary(l.value, today).let { s -> Debt.Debtor(l.state.id, l.value.name, s.remainingMinor, l.value.aprBp, s.emiMinor, l.value.order) } } +
            b.activeAccounts.filter { it.value.type == AccountType.CREDIT_CARD && b.balance(it.state.id) < 0 }.map { a ->
                val owed = -b.balance(a.state.id)
                Debt.Debtor(a.state.id, a.value.name, owed, a.value.card?.aprBp ?: 0, owed * (a.value.card?.minDueBp ?: 500) / 10_000)
            }
        val plan = if (debtors.isEmpty()) null else Debt.payoff(debtors, parseAmount(extra, ctx.base, ctx.fmt) ?: 0, strategy, today)
        PennyScreen(
            stringResource(R.string.debt_loans), onBack,
            floating = { app.penny.ui.kit.AddFab(stringResource(R.string.add_loan), onAdd) },
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 100.dp)) {
                if (debtors.isEmpty()) item { EmptyState(stringResource(R.string.no_debts), stringResource(R.string.no_debts_body), icon = Icons.Outlined.CreditCard) }
                if (plan != null) {
                    item {
                        app.penny.ui.kit.HeroCard {
                            Column(Modifier.padding(20.dp)) {
                                Text(stringResource(R.string.debt_free_by), style = MaterialTheme.typography.titleSmall)
                                Text(shortDate(plan.debtFreeDate), style = MaterialTheme.typography.headlineMedium)
                                Text(pluralStringResource(R.plurals.months_n, plan.months, plan.months) + " · " + stringResource(R.string.interest_x, ctx.money(plan.totalInterestMinor)), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    item {
                        Segmented(listOf(Choice(Debt.Strategy.AVALANCHE, stringResource(R.string.avalanche)), Choice(Debt.Strategy.SNOWBALL, stringResource(R.string.snowball)), Choice(Debt.Strategy.CUSTOM, stringResource(R.string.custom_order))), strategy, { strategy = it })
                        Text(stringResource(when (strategy) { Debt.Strategy.AVALANCHE -> R.string.avalanche_hint; Debt.Strategy.SNOWBALL -> R.string.snowball_hint; else -> R.string.custom_hint }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                        AmountInput(extra, { extra = it }, ctx.base, ctx.fmt, stringResource(R.string.extra_per_month))
                    }
                    item { SectionHeader(stringResource(R.string.projection)); LineChart(plan.balances.mapIndexed { i, v -> shortDate(today.plusMonths(i + 1L)) to v }, { ctx.money(it) }) }
                    item { SectionHeader(stringResource(R.string.payoff_order)) }
                    item {
                        val order = plan.payoffOrder.mapNotNull { id -> debtors.firstOrNull { it.id == id } }
                        PennyCard {
                            order.forEachIndexed { i, d ->
                                ListRow(d.name, subtitle = stringResource(R.string.apr_x, d.aprBp / 100.0), trailingText = ctx.money(d.balanceMinor), onClick = { if (loans.any { it.state.id == d.id }) onOpen(d.id) }, divider = i < order.lastIndex)
                            }
                        }
                    }
                }
                val closed = b.loans.filter { it.value.closed }
                if (closed.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.closed)) }
                    item { PennyCard { closed.forEachIndexed { i, l -> ListRow(l.value.name, subtitle = l.value.lender, onClick = { onOpen(l.state.id) }, divider = i < closed.lastIndex) } } }
                }
            }
        }
    }
}

/** RC-05: amortisation schedule, interest paid to date, remaining, prepayment what-if. */
@Composable
fun LoanDetailScreen(id: String, onBack: () -> Unit, onEdit: (String) -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val l = b.loans.firstOrNull { it.state.id == id }
        if (l == null) { PennyScreen(stringResource(R.string.debt_loans), onBack) { pad -> EmptyState(stringResource(R.string.not_found), stringResource(R.string.not_found_body), Modifier.padding(pad)) }; return@WithBook }
        val today = b.today()
        val s = Debt.summary(l.value, today)
        var pre by rememberSaveable { mutableStateOf("") }
        PennyScreen(l.value.name, onBack, actions = { IconButton(onClick = { onEdit(id) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) } }) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item {
                    PennyCard {
                        Column(Modifier.padding(16.dp)) {
                            Row {
                                StatBlock(stringResource(R.string.emi), ctx.money(s.emiMinor, l.value.currency), Modifier.weight(1f))
                                StatBlock(stringResource(R.string.remaining), ctx.money(s.remainingMinor, l.value.currency), Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(12.dp))
                            Row {
                                StatBlock(stringResource(R.string.interest_paid), ctx.money(s.interestPaidToDateMinor, l.value.currency), Modifier.weight(1f))
                                StatBlock(stringResource(R.string.total_interest), ctx.money(s.totalInterestMinor, l.value.currency), Modifier.weight(1f))
                            }
                            s.endDate?.let { Text(stringResource(R.string.ends_x, shortDate(it)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                        }
                    }
                }
                item {
                    SectionHeader(stringResource(R.string.prepayment_what_if))
                    AmountInput(pre, { pre = it }, l.value.currency, ctx.fmt, stringResource(R.string.prepay_amount))
                    parseAmount(pre, l.value.currency, ctx.fmt)?.let { a ->
                        val (months, interest) = Debt.prepaymentWhatIf(l.value, today, a)
                        Text(stringResource(R.string.prepay_result, months, ctx.money(interest, l.value.currency)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
                        TextButton(onClick = { vm.act(R.string.saved) { saveLoan(it, id, l.value.copy(prepayments = l.value.prepayments + app.penny.core.model.Prepayment(today, a))) }; pre = "" }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.record_prepayment)) }
                    }
                }
                item {
                    app.penny.ui.kit.ActionStrip {
                        if (b.recurring.none { it.value.loanId == id } && b.activeAccounts.isNotEmpty()) app.penny.ui.kit.StripAction(destructive = false, onClick = {
                            vm.act(R.string.recurring_added) {
                                saveRecurring(it, null, Recurring(l.value.name, RecurringKind.EMI, TxType.EXPENSE, s.emiMinor, currency = l.value.currency,
                                    accountId = it.activeAccountOrNull(l.value.paidFromAccountId) ?: it.defaultActiveAccountId ?: error("no account"),
                                    categoryId = "${it.profileId}:loan.emi", payee = l.value.lender.ifBlank { l.value.name }, schedule = Recurrence.monthly(l.value.start.plusMonths(1)), loanId = id))
                            }
                        }) { Text(stringResource(R.string.add_emi_bill)) }
                        app.penny.ui.kit.StripAction(destructive = false, onClick = { vm.act(R.string.updated) { saveLoan(it, id, l.value.copy(closed = !l.value.closed)) } }) { Text(stringResource(if (l.value.closed) R.string.reopen else R.string.close_loan)) }
                        app.penny.ui.kit.StripAction(destructive = true, onClick = { vm.act(R.string.deleted, after = onBack) { loans.delete(id) } }) { Text(stringResource(R.string.delete)) }
                    }
                }
                item { SectionHeader(stringResource(R.string.schedule)) }
                items(s.schedule.take(480), key = { it.n }) { i ->
                    ListRow(
                        "#${i.n} · ${shortDate(i.date)}", subtitle = stringResource(R.string.principal_interest, ctx.money(i.principalMinor, l.value.currency), ctx.money(i.interestMinor, l.value.currency)),
                        trailingText = ctx.money(i.balanceMinor, l.value.currency), divider = true,
                    )
                }
            }
        }
    }
}

@Composable
fun LoanEditScreen(id: String, onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val existing = b.loans.firstOrNull { it.state.id == id }?.value
        var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
        var kind by rememberSaveable { mutableStateOf(existing?.kind ?: LoanKind.EMI) }
        var currency by rememberSaveable { mutableStateOf(existing?.currency ?: ctx.base) }
        var principal by rememberSaveable { mutableStateOf(existing?.let { ctx.fmt.editable(it.principalMinor, it.currency) }.orEmpty()) }
        var apr by rememberSaveable { mutableStateOf(existing?.aprBp?.let { (it / 100.0).toString() }.orEmpty()) }
        var tenure by rememberSaveable { mutableStateOf(existing?.tenureMonths?.toString().orEmpty()) }
        var start by rememberSaveable { mutableStateOf((existing?.start ?: b.today()).toString()) }
        var lender by rememberSaveable { mutableStateOf(existing?.lender.orEmpty()) }
        var paidFrom by rememberSaveable { mutableStateOf(b.activeAccountOrNull(existing?.paidFromAccountId)) }
        var extra by rememberSaveable { mutableStateOf(existing?.extraMinor?.takeIf { it > 0 }?.let { ctx.fmt.editable(it, currency) }.orEmpty()) }
        var penalty by rememberSaveable { mutableStateOf(existing?.penaltyNote.orEmpty()) }
        val dirty = app.penny.ui.kit.rememberDirty(name, kind, currency, principal, apr, tenure, start, lender, paidFrom, extra, penalty)
        var err by rememberSaveable { mutableStateOf<String?>(null) }
        val req = stringResource(R.string.err_loan_fields)
        val rateErr = stringResource(R.string.err_rate_pct)
        val tenureErr = stringResource(R.string.err_tenure)
        val amountErr = stringResource(R.string.err_amount_invalid)
        var fieldErr by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
        PennyScreen(stringResource(if (existing == null) R.string.add_loan else R.string.edit_loan), onBack, large = false, dirty = dirty) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                TextInput(name, { name = it; err = null }, stringResource(R.string.field_name), error = err)
                SelectField(stringResource(R.string.field_type), kind, LoanKind.entries.map { Choice(it, stringResource(loanKindLabel(it))) }, { it?.let { k -> kind = k } })
                SelectField(stringResource(R.string.field_currency), currency, app.penny.ui.kit.currencyChoices(listOf(ctx.base)), { it?.let { c -> currency = c } })
                AmountInput(principal, { principal = it; fieldErr = fieldErr - "p" }, currency, ctx.fmt, stringResource(R.string.principal), error = fieldErr["p"])
                Row {
                    TextInput(apr, { apr = it; fieldErr = fieldErr - "apr" }, stringResource(R.string.apr_pct), Modifier.weight(1f), keyboard = KeyboardType.Decimal, error = fieldErr["apr"])
                    TextInput(tenure, { tenure = it.filter(Char::isDigit).take(4); fieldErr = fieldErr - "n" }, stringResource(R.string.tenure_months), Modifier.weight(1f), keyboard = KeyboardType.Number, error = fieldErr["n"])
                }
                DateInput(stringResource(R.string.start_date), runCatching { java.time.LocalDate.parse(start) }.getOrNull(), { start = it.toString() }, quick = false, today = b.today())
                TextInput(lender, { lender = it }, stringResource(R.string.lender))
                SelectField(stringResource(R.string.paid_from), paidFrom, b.activeAccounts.map { Choice(it.state.id, it.value.name) }, { paidFrom = it }, allowNone = true)
                AmountInput(extra, { extra = it; fieldErr = fieldErr - "extra" }, currency, ctx.fmt, stringResource(R.string.extra_per_month), error = fieldErr["extra"])
                TextInput(penalty, { penalty = it }, stringResource(R.string.penalty_note), singleLine = false)
                val p = parseAmount(principal, currency, ctx.fmt)
                val aprBp = app.penny.ui.kit.parsePercentBp(apr)
                val n = tenure.toIntOrNull()?.takeIf { it in 1..1200 }
                if (p != null && aprBp != null && n != null && n > 0) Text(stringResource(R.string.emi_preview, ctx.money(Debt.emi(p, aprBp, n), currency)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(20.dp))
                PrimaryButton(stringResource(R.string.save), {
                    val extraMinor = if (extra.isBlank()) 0L else parseAmount(extra, currency, ctx.fmt, allowZero = true)
                    val errs = buildMap {
                        if (p == null) put("p", amountErr)
                        if (aprBp == null) put("apr", rateErr)
                        if (n == null) put("n", tenureErr)
                        if (extraMinor == null) put("extra", amountErr)
                    }
                    if (name.isBlank()) err = req
                    fieldErr = errs
                    if (name.isBlank() || errs.isNotEmpty() || p == null || aprBp == null || n == null) return@PrimaryButton
                    val loan = (existing ?: Loan(name, principalMinor = p, currency = currency, aprBp = aprBp, tenureMonths = n, start = java.time.LocalDate.parse(start))).copy(
                        name = name.trim(), kind = kind, principalMinor = p, currency = currency, aprBp = aprBp, tenureMonths = n, start = java.time.LocalDate.parse(start),
                        lender = lender.trim(), paidFromAccountId = paidFrom, extraMinor = extraMinor ?: 0, penaltyNote = penalty.trim(),
                    )
                    vm.act(R.string.saved, after = onBack) { saveLoan(it, if (existing == null) null else id, loan) }
                }, Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

private fun loanKindLabel(k: LoanKind): Int = when (k) {
    LoanKind.EMI -> R.string.loan_emi
    LoanKind.PERSONAL -> R.string.loan_personal
    LoanKind.MORTGAGE -> R.string.loan_mortgage
    LoanKind.VEHICLE -> R.string.loan_vehicle
    LoanKind.EDUCATION -> R.string.loan_education
    LoanKind.PAY_LATER -> R.string.loan_pay_later
    LoanKind.INFORMAL -> R.string.loan_informal
    LoanKind.CARD -> R.string.loan_card
}
