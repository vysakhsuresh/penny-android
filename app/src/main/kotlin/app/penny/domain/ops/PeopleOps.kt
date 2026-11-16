package app.penny.domain.ops

import app.penny.core.model.Circle
import app.penny.core.model.CircleContribution
import app.penny.core.model.Group
import app.penny.core.model.GroupExpense
import app.penny.core.model.LendDirection
import app.penny.core.model.LendingEntry
import app.penny.core.model.Loan
import app.penny.core.model.Person
import app.penny.core.model.Remittance
import app.penny.core.model.Settlement
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.core.planning.Splitting
import app.penny.domain.Book
import app.penny.domain.PennyRepository
import java.time.LocalTime

suspend fun PennyRepository.savePerson(book: Book, id: String?, p: Person): String {
    val g = people.newGroup()
    if (id == null) people.create(newId(), book.profileId, p, g) else people.update(id, p, groupId = g)
    return g
}

fun Book.personIdByName(name: String): String? = people.firstOrNull { it.value.name.equals(name.trim(), ignoreCase = true) }?.state?.id

suspend fun PennyRepository.ensurePerson(book: Book, name: String, group: String): String =
    book.personIdByName(name) ?: newId().also { people.create(it, book.profileId, Person(name.trim()), group) }

/**
 * PE-01/PE-04: a lending entry. With an account it also posts the matching transaction in the user's ledger with
 * "Loans › Given" or "Repaid to me" (excluded from spend).
 */
suspend fun PennyRepository.saveLending(book: Book, id: String?, e: LendingEntry, postToAccount: Boolean): String {
    val g = lending.newGroup()
    var entry = e
    val acct = e.accountId
    if (postToAccount && acct != null && e.transactionId == null) {
        val txId = newId()
        val give = e.direction == LendDirection.GAVE
        transactions.create(txId, book.profileId, Transaction(
            type = if (give) TxType.EXPENSE else TxType.INCOME, amountMinor = e.amountMinor, currency = e.currency, accountId = acct,
            categoryId = if (give) catId(book.profileId, "loan.given") else null,
            payee = book.personName(e.personId), date = e.date, time = LocalTime.now(book.zone).withSecond(0).withNano(0), tz = book.profile.timezone,
            // Money lent coming back is not earnings: kept out of income in reports.
            note = e.note, excludeFromBudget = true, excludeFromReports = !give, source = "lending",
        ), g)
        entry = entry.copy(transactionId = txId)
    }
    if (id == null) {
        val newId = newId()
        lending.create(newId, book.profileId, entry, g)
        entry.transactionId?.let { t -> book.txMap[t] ?: transactions.get(t)?.let { s -> transactions.update(t, s.value.copy(lendingId = newId), groupId = g) } }
    } else {
        lending.update(id, entry, groupId = g)
        updateLinkedTx(book, entry.transactionId, g) { it.copy(amountMinor = entry.amountMinor, currency = entry.currency, date = entry.date, note = entry.note, payee = book.personName(entry.personId)) }
    }
    return g
}

/** Balance per person per currency: positive = to receive (neutral wording, PE-02). */
fun Book.lendingBalances(): Map<String, Map<String, Long>> =
    lending.filter { !it.value.closed }.groupBy { it.value.personId }.mapValues { (_, l) -> Splitting.lendingBalance(l.map { it.value }, today()) }

suspend fun PennyRepository.saveGroup(book: Book, id: String?, gr: Group): String {
    val g = groups.newGroup()
    if (id == null) groups.create(newId(), book.profileId, gr, g) else groups.update(id, gr, groupId = g)
    return g
}

/** PE-03: the user's own share of a group expense posts to their ledger when they paid (minus others' shares is owed). */
suspend fun PennyRepository.saveGroupExpense(book: Book, id: String?, e: GroupExpense, accountId: String?): String {
    val g = groupExpenses.newGroup()
    var exp = e
    if (id == null && accountId != null && e.payerId == Group.ME && e.transactionId == null) {
        val mine = Splitting.shares(e)[Group.ME] ?: 0
        val txId = newId()
        transactions.create(txId, book.profileId, Transaction(
            type = TxType.EXPENSE, amountMinor = e.amountMinor, currency = e.currency, accountId = accountId, categoryId = e.categoryId,
            payee = e.description, date = e.date, tz = book.profile.timezone, note = e.note, source = "group",
            // Only the user's share counts as spend; the rest is money lent to the group (excluded from spend).
            splits = if (mine < e.amountMinor) listOfNotNull(
                app.penny.core.model.Split(mine, e.categoryId).takeIf { mine > 0 },
                app.penny.core.model.Split(e.amountMinor - mine, catId(book.profileId, "loan.given"), note = "Others' share"),
            ) else emptyList(),
        ), g)
        exp = exp.copy(transactionId = txId)
    }
    if (id == null) groupExpenses.create(newId(), book.profileId, exp, g) else {
        groupExpenses.update(id, exp, groupId = g)
        val mine = Splitting.shares(exp)[Group.ME] ?: 0
        updateLinkedTx(book, exp.transactionId, g) {
            it.copy(
                amountMinor = exp.amountMinor, currency = exp.currency, categoryId = exp.categoryId, payee = exp.description, date = exp.date, note = exp.note,
                splits = if (mine < exp.amountMinor) listOfNotNull(
                    app.penny.core.model.Split(mine, exp.categoryId).takeIf { mine > 0 },
                    app.penny.core.model.Split(exp.amountMinor - mine, catId(book.profileId, "loan.given"), note = "Others' share"),
                ) else emptyList(),
            )
        }
    }
    return g
}

fun Book.groupBalances(groupId: String): Map<String, Long> {
    val gr = groups.firstOrNull { it.state.id == groupId }?.value ?: return emptyMap()
    return Splitting.balances(groupExpenses.filter { it.value.groupId == groupId }.map { it.value }, settlements.filter { it.value.groupId == groupId }.map { it.value }, gr.baseCurrency)
}

/** PE-04: settling posts a settlement and, when the user pays or receives, a ledger transaction excluded from spend. */
suspend fun PennyRepository.settle(book: Book, s: Settlement, accountId: String?): String {
    val g = settlements.newGroup()
    var st = s
    if (accountId != null && (s.fromId == Group.ME || s.toId == Group.ME)) {
        val paying = s.fromId == Group.ME
        val txId = newId()
        transactions.create(txId, book.profileId, Transaction(
            type = if (paying) TxType.EXPENSE else TxType.INCOME, amountMinor = s.amountMinor, currency = s.currency, accountId = accountId,
            categoryId = catId(book.profileId, if (paying) "loan.given" else "loan.back").takeIf { paying },
            payee = book.personName(if (paying) s.toId else s.fromId), date = s.date, tz = book.profile.timezone, note = s.note,
            excludeFromBudget = true, excludeFromReports = !paying, source = "settle",
        ), g)
        st = st.copy(transactionId = txId)
    }
    settlements.create(newId(), book.profileId, st, g)
    return g
}

suspend fun PennyRepository.saveLoan(book: Book, id: String?, l: Loan): String {
    val g = loans.newGroup()
    if (id == null) loans.create(newId(), book.profileId, l.copy(order = book.loans.size), g) else loans.update(id, l, groupId = g)
    return g
}

/** RM-01: an outgoing remittance posts an expense (Family › Remittance sent); incoming posts income. */
suspend fun PennyRepository.saveRemittance(book: Book, id: String?, r: Remittance, accountId: String?): String {
    val g = remittances.newGroup()
    var rem = r
    if (id == null && accountId != null && r.transactionId == null) {
        val txId = newId()
        val out = r.outgoing
        transactions.create(txId, book.profileId, Transaction(
            type = if (out) TxType.EXPENSE else TxType.INCOME,
            amountMinor = if (out) r.sentMinor + r.feeMinor else r.receivedMinor,
            currency = if (out) r.sentCurrency else r.receivedCurrency, accountId = accountId,
            categoryId = catId(book.profileId, if (out) "fam.remit" else "inc.remit"),
            payee = if (out) r.receiver else r.sender, date = r.date, tz = book.profile.timezone, note = r.purpose, source = "remittance",
            accountAmountMinor = remittanceInAccount(r, book.accountMap[accountId]?.value?.currency),
        ), g)
        rem = rem.copy(transactionId = txId)
    }
    if (id == null) remittances.create(newId(), book.profileId, rem, g) else {
        remittances.update(id, rem, groupId = g)
        updateLinkedTx(book, rem.transactionId, g) {
            it.copy(
                amountMinor = if (rem.outgoing) rem.sentMinor + rem.feeMinor else rem.receivedMinor,
                currency = if (rem.outgoing) rem.sentCurrency else rem.receivedCurrency,
                payee = if (rem.outgoing) rem.receiver else rem.sender, date = rem.date, note = rem.purpose,
                accountAmountMinor = remittanceInAccount(rem, book.accountMap[it.accountId]?.value?.currency),
            )
        }
    }
    return g
}

/**
 * A remittance recorded in an account of the other side's currency (sending AED from a rupee account) uses the
 * transfer's own rate, so the balance moves by what actually left the account. Null when no conversion is needed.
 */
internal fun remittanceInAccount(r: Remittance, accountCurrency: String?): Long? {
    if (accountCurrency == null || r.sentMinor <= 0 || r.receivedMinor <= 0) return null
    val txCur = if (r.outgoing) r.sentCurrency else r.receivedCurrency
    if (accountCurrency == txCur) return null
    return when {
        r.outgoing && accountCurrency == r.receivedCurrency ->
            java.math.BigDecimal(r.sentMinor + r.feeMinor).multiply(java.math.BigDecimal(r.receivedMinor)).divide(java.math.BigDecimal(r.sentMinor), 0, java.math.RoundingMode.HALF_EVEN).toLong()
        !r.outgoing && accountCurrency == r.sentCurrency -> r.sentMinor
        else -> null
    }
}

suspend fun PennyRepository.saveCircle(book: Book, id: String?, c: Circle): String {
    val g = circles.newGroup()
    if (id == null) circles.create(newId(), book.profileId, c, g) else circles.update(id, c, groupId = g)
    return g
}

/** CI-02: my contribution or payout posts a transaction (excluded from spend: it is saving, not spending). */
suspend fun PennyRepository.recordCircle(book: Book, circleId: String, c: CircleContribution, accountId: String?): String {
    val g = circleContributions.newGroup()
    val circle = book.circles.firstOrNull { it.state.id == circleId }?.value ?: return g
    val me = circle.members.firstOrNull { it.isMe }?.name
    var entry = c
    if (accountId != null && c.memberName == me) {
        val txId = newId()
        transactions.create(txId, book.profileId, Transaction(
            type = if (c.payout) TxType.INCOME else TxType.EXPENSE, amountMinor = c.amountMinor, currency = circle.currency, accountId = accountId,
            categoryId = catId(book.profileId, if (c.payout) "inc.other" else "save").takeIf { !c.payout }, payee = circle.name, date = c.date,
            tz = book.profile.timezone, excludeFromBudget = true, excludeFromReports = c.payout, circleId = circleId, source = "circle",
        ), g)
        entry = entry.copy(transactionId = txId)
    }
    circleContributions.create(newId(), book.profileId, entry, g)
    return g
}

// ---------------------------------------------------------------- Linked ledger transactions

/** Soft-deletes the ledger transaction a people record posted, in the record's undo group. */
private suspend fun PennyRepository.deleteLinkedTx(book: Book, txId: String?, group: String) {
    if (txId != null && book.txMap.containsKey(txId)) transactions.delete(txId, group)
}

/** Keeps the posted transaction in step with an edited record (amount, date, note, payee), in the same undo group. */
private suspend fun PennyRepository.updateLinkedTx(book: Book, txId: String?, group: String, change: (Transaction) -> Transaction) {
    val cur = txId?.let { book.txMap[it]?.value } ?: return
    val next = change(cur)
    if (next != cur) transactions.update(txId, next, groupId = group)
}

/** Deleting the record removes what it posted: a deleted remittance must not stay in balances and spend. */
suspend fun PennyRepository.deleteLending(book: Book, id: String): String {
    val g = lending.newGroup()
    val e = book.lending.firstOrNull { it.state.id == id }?.value
    lending.delete(id, g)
    deleteLinkedTx(book, e?.transactionId, g)
    return g
}

suspend fun PennyRepository.deleteGroupExpense(book: Book, id: String): String {
    val g = groupExpenses.newGroup()
    val e = book.groupExpenses.firstOrNull { it.state.id == id }?.value
    groupExpenses.delete(id, g)
    deleteLinkedTx(book, e?.transactionId, g)
    return g
}

suspend fun PennyRepository.deleteRemittance(book: Book, id: String): String {
    val g = remittances.newGroup()
    val r = book.remittances.firstOrNull { it.state.id == id }?.value
    remittances.delete(id, g)
    deleteLinkedTx(book, r?.transactionId, g)
    return g
}

suspend fun PennyRepository.deleteCircleContribution(book: Book, id: String): String {
    val g = circleContributions.newGroup()
    val c = book.circleContributions.firstOrNull { it.state.id == id }?.value
    circleContributions.delete(id, g)
    deleteLinkedTx(book, c?.transactionId, g)
    return g
}
