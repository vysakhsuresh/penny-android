package app.penny.domain.ops

import app.penny.core.budget.Ledger
import app.penny.core.model.Category
import app.penny.core.model.DefaultCategories
import app.penny.core.model.Direction
import app.penny.core.model.FinancialAccount
import app.penny.core.model.Payee
import app.penny.core.model.Rule
import app.penny.core.model.Tag
import app.penny.core.model.Transaction
import app.penny.core.model.TxStatus
import app.penny.core.model.TxType
import app.penny.core.rules.Dedupe
import app.penny.core.rules.RuleContext
import app.penny.core.rules.RuleEngine
import app.penny.core.rules.RuleLearner
import app.penny.domain.Book
import app.penny.domain.PennyRepository
import java.time.LocalDate
import java.time.LocalTime

/** Transactions, accounts, categories, tags and payees: every operation returns its undo group. */

fun catId(pid: String, key: String) = "$pid:$key"

/** PY-01/PY-02: finds the payee by name or alias, or creates it (auto) in [group]. */
suspend fun PennyRepository.ensurePayee(book: Book, name: String?, group: String): String? {
    val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val norm = RuleEngine.normalise(n)
    book.payees.firstOrNull { p -> RuleEngine.normalise(p.value.name) == norm || p.value.aliases.any { RuleEngine.normalise(it) == norm } }?.let { return it.state.id }
    val id = newId()
    payees.create(id, book.profileId, Payee(n), group)
    return id
}

/** TX-15 payee memory: last category, account and typical (median) amount for a payee. */
data class PayeeMemory(val categoryId: String?, val accountId: String?, val typicalMinor: Long?)

fun Book.payeeMemory(name: String): PayeeMemory? {
    val norm = RuleEngine.normalise(name)
    if (norm.isEmpty()) return null
    val payee = payees.firstOrNull { RuleEngine.normalise(it.value.name) == norm || it.value.aliases.any { a -> RuleEngine.normalise(a) == norm } }
    val past = transactions.filter { RuleEngine.normalise(it.value.payee.orEmpty()) == norm }.sortedByDescending { it.value.date }
    if (past.isEmpty() && payee == null) return null
    val amounts = past.take(9).map { it.value.amountMinor }.sorted()
    return PayeeMemory(
        payee?.value?.defaultCategoryId ?: past.firstOrNull()?.value?.categoryId,
        activeAccountOrNull(payee?.value?.defaultAccountId) ?: activeAccountOrNull(past.firstOrNull()?.value?.accountId),
        amounts.getOrNull(amounts.size / 2),
    )
}

data class SaveResult(val id: String, val group: String, val duplicates: List<String>)

/**
 * Creates or updates a transaction (TX-02): links the payee entity, records a learning signal when the category of an
 * existing payee is corrected (RU-03), and reports duplicates (TX-10) so the UI can offer "Keep both / Merge".
 */
suspend fun PennyRepository.saveTransaction(book: Book, id: String?, tx: Transaction, applyRules: Boolean = false, group: String? = null): SaveResult {
    val g = group ?: transactions.newGroup()
    var t = tx
    if (applyRules) t = RuleEngine.apply(t, book.rules.map { it.state.id to it.value }, RuleContext(accountName = book::accountName)).tx
    val payeeId = ensurePayee(book, t.payee, g)
    t = t.copy(payeeId = payeeId)
    val newId = id ?: newId()
    val previous = id?.let { book.txMap[it]?.value }
    if (id == null) transactions.create(newId, book.profileId, t, g) else transactions.update(id, t, groupId = g)
    // RU-03: a category correction on a known payee is a learning signal.
    if (previous != null && t.categoryId != null && previous.categoryId != t.categoryId && !t.payee.isNullOrBlank()) {
        RuleLearner.observe(t.payee, t.categoryId!!, book.rules.map { it.state.id to it.value })?.let { (rid, rule) ->
            if (rid == null) rules.create(newId(), book.profileId, rule, g) else rules.update(rid, rule, groupId = g)
        }
    }
    val dupes = if (id == null) Dedupe.candidates(t, book.transactions.map { it.state.id to it.value }, newId) else emptyList()
    return SaveResult(newId, g, dupes)
}

/** TX-10 / TX-04 merge duplicates: keeps the first, folds the rest into it, soft-deletes them; one undo. */
suspend fun PennyRepository.mergeTransactions(book: Book, keepId: String, dropIds: List<String>): String {
    val g = transactions.newGroup()
    var keep = book.txMap[keepId]?.value ?: return g
    dropIds.forEach { d -> book.txMap[d]?.value?.let { keep = Dedupe.merge(keep, it) } }
    transactions.update(keepId, keep, reason = "merge", groupId = g)
    transactions.deleteMany(dropIds, g)
    return g
}

/** TX-04 bulk edit: [transform] is applied to each selected transaction; one undo group. */
suspend fun PennyRepository.bulkEdit(book: Book, ids: Collection<String>, transform: (Transaction) -> Transaction): String? =
    transactions.updateMany(ids.mapNotNull { id -> book.txMap[id]?.let { id to transform(it.value) } })

suspend fun PennyRepository.bulkDelete(ids: Collection<String>): String = transactions.deleteMany(ids.toList())

/** TX-16: transaction from a template, dated today. */
fun Book.fromTemplate(t: app.penny.core.model.Template): Transaction = Transaction(
    type = t.type, amountMinor = t.amountMinor ?: 0, currency = t.currency, accountId = activeAccountOrNull(t.accountId) ?: defaultActiveAccountId.orEmpty(),
    toAccountId = t.toAccountId, categoryId = t.categoryId, payee = t.payee, date = today(), time = LocalTime.now(zone).withSecond(0).withNano(0),
    tz = profile.timezone, note = t.note, tags = t.tags, source = "template",
)

// ------------------------------------------------------------------ Accounts (AC)

/** Balance as of today: future-dated (scheduled) transactions move it on their date, not before. */
fun Book.balance(id: String): Long = accountMap[id]?.let { Ledger.balance(id, it.value, txList, today()) } ?: 0

/** AC-03 "set balance today": an adjustment for the difference. Returns null when already equal. */
suspend fun PennyRepository.setBalance(book: Book, accountId: String, target: Long, categoryKey: String = DefaultCategories.ADJUSTMENTS): String? {
    val a = book.accountMap[accountId]?.value ?: return null
    val diff = target - book.balance(accountId)
    if (diff == 0L) return null
    val tx = Transaction(
        type = TxType.ADJUSTMENT, amountMinor = kotlin.math.abs(diff), currency = a.currency, accountId = accountId,
        categoryId = catId(book.profileId, categoryKey), date = book.today(), time = LocalTime.now(book.zone).withSecond(0).withNano(0),
        tz = book.profile.timezone, direction = if (diff > 0) Direction.IN else Direction.OUT, status = TxStatus.RECONCILED,
        note = if (categoryKey == DefaultCategories.UNRECORDED_CASH) "Cash count" else "Balance set", source = "reconcile",
    )
    val g = transactions.newGroup()
    transactions.create(newId(), book.profileId, tx, g)
    return g
}

/** AC-08 "count my cash": the difference posts to Unrecorded cash (reportable). */
suspend fun PennyRepository.countCash(book: Book, accountId: String, counted: Long) = setBalance(book, accountId, counted, DefaultCategories.UNRECORDED_CASH)

/** TX-12 reconcile: marks cleared transactions up to [upTo] as reconciled. */
suspend fun PennyRepository.reconcile(book: Book, accountId: String, upTo: LocalDate): String? =
    bulkEdit(book, book.transactions.filter { (it.value.accountId == accountId || it.value.toAccountId == accountId) && !it.value.date.isAfter(upTo) && it.value.status == TxStatus.CLEARED }.map { it.state.id }) {
        it.copy(status = TxStatus.RECONCILED)
    }

/** AC-06: delete with reassignment to [moveTo], or soft-delete its transactions with it. One undo group. */
suspend fun PennyRepository.deleteAccount(book: Book, id: String, moveTo: String?): String {
    val g = accounts.newGroup()
    val related = book.transactions.filter { it.value.accountId == id || it.value.toAccountId == id }
    if (moveTo != null) {
        transactions.updateMany(related.map { s ->
            val t = s.value
            s.state.id to t.copy(accountId = if (t.accountId == id) moveTo else t.accountId, toAccountId = if (t.toAccountId == id) moveTo else t.toAccountId)
        }.filter { (_, t) -> !(t.type == TxType.TRANSFER && t.accountId == t.toAccountId) }, g)
        // A transfer between the two merged accounts makes no sense any more.
        transactions.deleteMany(related.filter { s -> s.value.type == TxType.TRANSFER && setOf(s.value.accountId, s.value.toAccountId) == setOf(id, moveTo) }.map { it.state.id }, g)
    } else {
        transactions.deleteMany(related.map { it.state.id }, g)
    }
    repairAccountRefs(book, id, moveTo, g)
    accounts.delete(id, g)
    if (book.profile.defaultAccountId == id) {
        profiles.update(book.profileId, book.profile.copy(defaultAccountId = moveTo ?: book.activeAccounts.firstOrNull { it.state.id != id }?.state?.id), groupId = g)
    }
    return g
}

suspend fun PennyRepository.reorderAccounts(book: Book, orderedIds: List<String>): String? =
    accounts.updateMany(orderedIds.mapIndexedNotNull { i, id -> book.accountMap[id]?.let { id to it.value.copy(sort = i) } })

suspend fun PennyRepository.saveAccount(book: Book, id: String?, a: FinancialAccount): String {
    val g = accounts.newGroup()
    if (id == null) accounts.create(newId(), book.profileId, a.copy(sort = book.accounts.size), g) else accounts.update(id, a, groupId = g)
    // Archiving the default account hands "default" to another active account in the same undo step.
    if (id != null && a.archived && book.profile.defaultAccountId == id) {
        profiles.update(book.profileId, book.profile.copy(defaultAccountId = book.activeAccounts.firstOrNull { it.state.id != id }?.state?.id), groupId = g)
    }
    return g
}

// ------------------------------------------------------------------ Categories (CAT)

/** CAT-06: delete with reassignment of transactions, splits, rules, envelopes and children to [to]. */
suspend fun PennyRepository.deleteCategory(book: Book, id: String, to: String?): String {
    val g = categories.newGroup()
    reassignCategory(book, id, to, g)
    categories.delete(id, g)
    return g
}

/** CAT-03 merge A into B: like delete-with-reassign, and B keeps A's name as nothing (A goes to Trash, restorable). */
suspend fun PennyRepository.mergeCategory(book: Book, from: String, into: String): String = deleteCategory(book, from, into)

private suspend fun PennyRepository.reassignCategory(book: Book, id: String, to: String?, g: String) {
    transactions.updateMany(book.transactions.filter { s -> s.value.categoryId == id || s.value.splits.any { it.categoryId == id } }.map { s ->
        s.state.id to s.value.copy(
            categoryId = if (s.value.categoryId == id) to else s.value.categoryId,
            splits = s.value.splits.map { if (it.categoryId == id) it.copy(categoryId = to) else it },
        )
    }, g)
    rules.updateMany(book.rules.filter { it.value.actions.categoryId == id }.map { it.state.id to it.value.copy(actions = it.value.actions.copy(categoryId = to)) }, g)
    envelopes.updateMany(book.envelopes.filter { id in it.value.categoryIds }.map { e ->
        e.state.id to e.value.copy(categoryIds = (e.value.categoryIds - id + listOfNotNull(to)).distinct())
    }, g)
    categories.updateMany(book.categories.filter { it.value.parentId == id }.map { it.state.id to it.value.copy(parentId = to?.takeIf { t -> t != it.state.id }) }, g)
    payees.updateMany(book.payees.filter { it.value.defaultCategoryId == id }.map { it.state.id to it.value.copy(defaultCategoryId = to) }, g)
    repairCategoryRefs(book, id, to, g)
}

suspend fun PennyRepository.saveCategory(book: Book, id: String?, c: Category): String {
    val g = categories.newGroup()
    // Guard against cycles when moving within the tree (CAT-03).
    var p = c.parentId
    var depth = 1
    while (p != null && depth < 10) { if (p == id) error("cycle"); p = book.categoryMap[p]?.value?.parentId; depth++ }
    if (id == null) categories.create(newId(), book.profileId, c.copy(sort = book.categories.size), g) else categories.update(id, c, groupId = g)
    return g
}

/**
 * Adds a top-level category of [kind] named [name] with the given [id] (so the form that asked can select it at once).
 * A category with that name already there is reused instead of making a twin. Returns the undo group, or null if reused.
 */
suspend fun PennyRepository.addCategoryNamed(book: Book, id: String, name: String, kind: app.penny.core.model.CategoryKind): String? {
    if (book.categories.any { it.value.parentId == null && it.value.kind == kind && it.value.name.trim().equals(name.trim(), ignoreCase = true) }) return null
    val g = categories.newGroup()
    categories.create(id, book.profileId, Category(kind = kind, name = name.trim(), sort = book.categories.size), g)
    return g
}

suspend fun PennyRepository.reorderCategories(book: Book, orderedIds: List<String>): String? =
    categories.updateMany(orderedIds.mapIndexedNotNull { i, id -> book.categoryMap[id]?.let { id to it.value.copy(sort = i) } })

/** CAT-01: depth of a category (1 = top level); the tree allows three levels. */
fun Book.depth(id: String?): Int {
    var d = 0
    var c = id?.let(categoryMap::get)
    while (c != null && d < 10) { d++; c = c.value.parentId?.let(categoryMap::get) }
    return d
}

// ------------------------------------------------------------------ Tags (CAT-07) and payees (PY)

suspend fun PennyRepository.renameTag(book: Book, id: String, newName: String): String {
    val g = tags.newGroup()
    val old = book.tags.firstOrNull { it.state.id == id }?.value ?: return g
    repairTagRefs(book, old.name, newName, g)
    tags.update(id, old.copy(name = newName), groupId = g)
    return g
}

suspend fun PennyRepository.mergeTag(book: Book, fromId: String, intoId: String): String {
    val g = tags.newGroup()
    val from = book.tags.firstOrNull { it.state.id == fromId }?.value ?: return g
    val into = book.tags.firstOrNull { it.state.id == intoId }?.value ?: return g
    repairTagRefs(book, from.name, into.name, g)
    tags.delete(fromId, g)
    return g
}

/** Deleting a tag also removes it from everything that carries it (one undo group), so it is not re-created from use. */
suspend fun PennyRepository.deleteTag(book: Book, id: String): String {
    val g = tags.newGroup()
    val t = book.tags.firstOrNull { it.state.id == id }?.value ?: return g
    repairTagRefs(book, t.name, null, g)
    tags.delete(id, g)
    return g
}

/** Tags used on transactions but without an entity yet become entities so they can be managed. */
fun Book.allTagNames(): List<String> = (tags.map { it.value.name } + txList.flatMap { it.tags }).distinct().sortedBy { it.lowercase() }

suspend fun PennyRepository.saveTag(book: Book, id: String?, t: Tag): String {
    val g = tags.newGroup()
    if (id == null) tags.create(newId(), book.profileId, t.copy(sort = book.tags.size), g) else tags.update(id, t, groupId = g)
    return g
}

/** PY-02: rename propagates to every transaction; the old name stays as an alias for matching. */
suspend fun PennyRepository.renamePayee(book: Book, id: String, newName: String): String {
    val g = payees.newGroup()
    val p = book.payees.firstOrNull { it.state.id == id }?.value ?: return g
    payees.update(id, p.copy(name = newName, aliases = (p.aliases + p.name).distinct().filter { it != newName }), groupId = g)
    transactions.updateMany(book.transactions.filter { it.value.payeeId == id || RuleEngine.normalise(it.value.payee.orEmpty()) == RuleEngine.normalise(p.name) }
        .map { it.state.id to it.value.copy(payee = newName, payeeId = id) }, g)
    return g
}

suspend fun PennyRepository.mergePayee(book: Book, fromId: String, intoId: String): String {
    val g = payees.newGroup()
    val from = book.payees.firstOrNull { it.state.id == fromId }?.value ?: return g
    val into = book.payees.firstOrNull { it.state.id == intoId }?.value ?: return g
    payees.update(intoId, into.copy(aliases = (into.aliases + from.name + from.aliases).distinct().filter { it != into.name }), groupId = g)
    val names = (from.aliases + from.name).map(RuleEngine::normalise).toSet()
    transactions.updateMany(book.transactions.filter { it.value.payeeId == fromId || RuleEngine.normalise(it.value.payee.orEmpty()) in names }
        .map { it.state.id to it.value.copy(payee = into.name, payeeId = intoId) }, g)
    payees.delete(fromId, g)
    return g
}

/** Payee delete with reassignment (§10.2): transactions move to [to] or lose the link (text kept). */
suspend fun PennyRepository.deletePayee(book: Book, id: String, to: String?): String {
    if (to != null) return mergePayee(book, id, to)
    val g = payees.newGroup()
    transactions.updateMany(book.transactions.filter { it.value.payeeId == id }.map { it.state.id to it.value.copy(payeeId = null) }, g)
    payees.delete(id, g)
    return g
}

// ------------------------------------------------------------------ Rules (RU)

suspend fun PennyRepository.saveRule(book: Book, id: String?, r: Rule): String {
    val g = rules.newGroup()
    if (id == null) rules.create(newId(), book.profileId, r.copy(order = (book.rules.maxOfOrNull { it.value.order } ?: 0) + 1), g) else rules.update(id, r, groupId = g)
    return g
}

/** RU-02 apply retroactively: one undo for every changed transaction. */
suspend fun PennyRepository.applyRuleRetro(book: Book, r: Rule): String? =
    transactions.updateMany(RuleEngine.preview(r, book.transactions.map { it.state.id to it.value }).map { (id, t) -> id to RuleEngine.act(t, r.actions) })

suspend fun PennyRepository.reorderRules(book: Book, orderedIds: List<String>): String? =
    rules.updateMany(orderedIds.mapIndexedNotNull { i, id -> book.rules.firstOrNull { it.state.id == id }?.let { id to it.value.copy(order = i) } })

// ------------------------------------------------------------------ Accounts helpers

fun Book.accountsOfCurrency(c: String): List<String> = activeAccounts.filter { it.value.currency == c }.map { it.state.id }

fun FinancialAccount.isLiability() = type in listOf(app.penny.core.model.AccountType.CREDIT_CARD, app.penny.core.model.AccountType.LOAN, app.penny.core.model.AccountType.LIABILITY)

@Suppress("unused")
private fun unusedCategory(c: Category) = c
