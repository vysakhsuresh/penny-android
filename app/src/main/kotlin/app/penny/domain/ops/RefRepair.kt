package app.penny.domain.ops

import app.penny.core.model.RuleField
import app.penny.core.model.WatchTarget
import app.penny.domain.Book
import app.penny.domain.PennyRepository

/*
 * Referential integrity for deletes and merges. Everything that points at an account, category or tag is rewritten in
 * the same undo group as the delete, so nothing keeps posting to, filtering by or budgeting against a record that is
 * gone. With a replacement the reference moves to it; without one it is cleared, or the owner is paused when a cleared
 * reference would make it act on the wrong thing (a bill or rule with no account).
 */

private fun List<String>.swap(id: String, to: String?): List<String> = if (id !in this) this else (map { if (it == id) to else it }.filterNotNull()).distinct()

/** Rewrites every reference to account [id]; [to] is the account it was merged into, or null when it was deleted. */
internal suspend fun PennyRepository.repairAccountRefs(book: Book, id: String, to: String?, g: String) {
    fun r(v: String?) = if (v == id) to else v
    recurring.updateMany(book.recurring.map { s ->
        val v = s.value
        val hit = v.accountId == id || v.toAccountId == id
        s.state.id to if (!hit) v else if (to != null) v.copy(accountId = r(v.accountId)!!, toAccountId = r(v.toAccountId)?.takeIf { it != r(v.accountId) })
        else v.copy(paused = true, autoPost = false, toAccountId = r(v.toAccountId))
    }, g)
    rules.updateMany(book.rules.map { s ->
        val v = s.value
        val condHit = v.conditions.any { it.field == RuleField.ACCOUNT && id in it.value.split(',') }
        val actHit = v.actions.accountId == id || v.actions.transferToAccountId == id
        s.state.id to if (!condHit && !actHit) v else v.copy(
            conditions = v.conditions.map { c -> if (c.field == RuleField.ACCOUNT) c.copy(value = c.value.split(',').swap(id, to).joinToString(",")) else c },
            actions = v.actions.copy(accountId = r(v.actions.accountId), transferToAccountId = r(v.actions.transferToAccountId)),
            // A rule that matched only this account would otherwise match every account.
            enabled = v.enabled && (to != null || !condHit),
        )
    }, g)
    templates.updateMany(book.templates.map { s -> s.state.id to s.value.copy(accountId = r(s.value.accountId), toAccountId = r(s.value.toAccountId)) }, g)
    goals.updateMany(book.goals.map { s -> s.state.id to s.value.copy(linkedAccountIds = s.value.linkedAccountIds.swap(id, to)) }, g)
    loans.updateMany(book.loans.map { s -> s.state.id to s.value.copy(accountId = r(s.value.accountId), paidFromAccountId = r(s.value.paidFromAccountId)) }, g)
    lending.updateMany(book.lending.map { s -> s.state.id to s.value.copy(accountId = r(s.value.accountId)) }, g)
    circles.updateMany(book.circles.map { s -> s.state.id to s.value.copy(accountId = r(s.value.accountId)) }, g)
    payees.updateMany(book.payees.map { s -> s.state.id to s.value.copy(defaultAccountId = r(s.value.defaultAccountId)) }, g)
    categories.updateMany(book.categories.map { s -> s.state.id to s.value.copy(defaultAccountId = r(s.value.defaultAccountId)) }, g)
    savedSearches.updateMany(book.savedSearches.map { s -> s.state.id to s.value.copy(filter = s.value.filter.copy(accountIds = s.value.filter.accountIds.swap(id, to))) }, g)
    // Pending captures move to the replacement, or to another everyday account, so Accept never posts into a deleted one.
    val fallback = to ?: book.activeAccounts.firstOrNull { it.state.id != id }?.state?.id
    captures.updateMany(book.captures.filter { it.value.proposed.accountId == id }.mapNotNull { s ->
        fallback?.let { f -> s.state.id to s.value.copy(proposed = s.value.proposed.copy(accountId = f)) }
    }, g)
    // Bank links: drop the local account (and its remote pair) so a sync never recreates rows on it.
    syncLinks.updateMany(book.syncLinks.filter { id in it.value.accountIds }.map { s ->
        val v = s.value
        val i = v.accountIds.indexOf(id)
        s.state.id to if (to != null && to !in v.accountIds) v.copy(accountIds = v.accountIds.map { if (it == id) to else it })
        else v.copy(accountIds = v.accountIds.filterIndexed { k, _ -> k != i }, remoteAccountIds = v.remoteAccountIds.filterIndexed { k, _ -> k != i })
    }, g)
}

/** Rewrites every reference to category [id] outside transactions, envelopes, children and payees (handled already). */
internal suspend fun PennyRepository.repairCategoryRefs(book: Book, id: String, to: String?, g: String) {
    fun r(v: String?) = if (v == id) to else v
    recurring.updateMany(book.recurring.map { s -> s.state.id to s.value.copy(categoryId = r(s.value.categoryId)) }, g)
    templates.updateMany(book.templates.map { s -> s.state.id to s.value.copy(categoryId = r(s.value.categoryId)) }, g)
    rules.updateMany(book.rules.map { s ->
        val v = s.value
        val condHit = v.conditions.any { it.field == RuleField.CATEGORY && id in it.value.split(',') }
        s.state.id to v.copy(
            conditions = v.conditions.map { c -> if (c.field == RuleField.CATEGORY) c.copy(value = c.value.split(',').swap(id, to).joinToString(",")) else c },
            actions = v.actions.copy(splitTemplate = v.actions.splitTemplate.map { if (it.categoryId == id) it.copy(categoryId = to) else it }),
            enabled = v.enabled && (to != null || !condHit),
        )
    }, g)
    groupExpenses.updateMany(book.groupExpenses.map { s -> s.state.id to s.value.copy(categoryId = r(s.value.categoryId)) }, g)
    savedSearches.updateMany(book.savedSearches.map { s -> s.state.id to s.value.copy(filter = s.value.filter.copy(categoryIds = s.value.filter.categoryIds.swap(id, to))) }, g)
    captures.updateMany(book.captures.map { s -> s.state.id to s.value.copy(proposed = s.value.proposed.copy(categoryId = r(s.value.proposed.categoryId))) }, g)
    val watched = book.watchlists.filter { it.value.target == WatchTarget.CATEGORY && it.value.value == id }
    if (to != null) watchlists.updateMany(watched.map { it.state.id to it.value.copy(value = to) }, g)
    else watchlists.deleteMany(watched.map { it.state.id }, g)
}

/** Renames tag [from] to [to] everywhere a tag name is stored (null removes it). */
internal suspend fun PennyRepository.repairTagRefs(book: Book, from: String, to: String?, g: String) {
    fun l(v: List<String>) = v.swap(from, to)
    transactions.updateMany(book.transactions.filter { from in it.value.tags }.map { s -> s.state.id to s.value.copy(tags = l(s.value.tags)) }, g)
    envelopes.updateMany(book.envelopes.map { s -> s.state.id to s.value.copy(tags = l(s.value.tags)) }, g)
    recurring.updateMany(book.recurring.map { s -> s.state.id to s.value.copy(tags = l(s.value.tags)) }, g)
    templates.updateMany(book.templates.map { s -> s.state.id to s.value.copy(tags = l(s.value.tags)) }, g)
    rules.updateMany(book.rules.map { s -> s.state.id to s.value.copy(actions = s.value.actions.copy(addTags = l(s.value.actions.addTags))) }, g)
    categories.updateMany(book.categories.map { s -> s.state.id to s.value.copy(defaultTags = l(s.value.defaultTags)) }, g)
    savedSearches.updateMany(book.savedSearches.map { s -> s.state.id to s.value.copy(filter = s.value.filter.copy(tags = l(s.value.filter.tags))) }, g)
    val watched = book.watchlists.filter { it.value.target == WatchTarget.TAG && it.value.value == from }
    if (to != null) watchlists.updateMany(watched.map { it.state.id to it.value.copy(value = to) }, g)
    else watchlists.deleteMany(watched.map { it.state.id }, g)
}
