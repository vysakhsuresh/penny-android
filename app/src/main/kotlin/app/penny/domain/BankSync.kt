package app.penny.domain

import app.penny.core.Brand
import app.penny.core.model.AccountType
import app.penny.core.model.FinancialAccount
import app.penny.core.model.SyncLink
import app.penny.core.model.SyncStatus
import app.penny.core.model.Transaction
import app.penny.core.model.TxStatus
import app.penny.core.model.TxType
import app.penny.core.rules.Dedupe
import app.penny.core.rules.RuleContext
import app.penny.core.rules.RuleEngine
import app.penny.sync.ConnectBody
import app.penny.sync.Connection
import app.penny.sync.ConnectorInfo
import app.penny.sync.RemoteAccount
import app.penny.sync.RemoteTx
import app.penny.sync.SyncBody
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CP-10 / AC-09 client side. Credentials live only on the relay's connector service (SE-08); the phone gets normalised
 * transactions over TLS and keeps them in its own encrypted book. Bank transactions are matched to manual or captured
 * ones (CP-11) so nothing is counted twice, and pending items become cleared when the bank posts them.
 */
@Singleton
class BankSync @Inject constructor(private val sync: SyncManager, private val repo: PennyRepository) {
    val callbackUri = "${Brand.DEEP_LINK_SCHEME}://bank/callback"

    suspend fun providers(region: List<String>): List<ConnectorInfo> =
        sync.relayClient().connectors().filter { it.enabled && (it.id == "sandbox" || it.id == "simplefin" || it.id in region) }

    /** Token providers (SimpleFIN) complete now; redirect providers return the consent URL to open. */
    suspend fun connect(book: Book, provider: String, token: String?): Connection {
        val c = sync.relayClient().connect(ConnectBody(provider, callbackUri, token))
        if (c.redirectUrl == null) link(book, c)
        return c
    }

    suspend fun complete(book: Book, state: String, params: Map<String, String>): Connection {
        val c = sync.relayClient().complete(state, params)
        link(book, c)
        return c
    }

    /** Creates a local account per remote account (or reuses one already linked) and a SyncLink entity. */
    private suspend fun link(book: Book, c: Connection) {
        val g = repo.syncLinks.newGroup()
        val existing = book.syncLinks.firstOrNull { it.value.connectionRef == c.id }
        val remote = c.accounts.orEmpty()
        val localIds = remote.map { ra ->
            existing?.value?.remoteAccountIds?.indexOf(ra.id)?.takeIf { it >= 0 }?.let { existing.value.accountIds[it] }
                ?: repo.newId().also { id ->
                    repo.accounts.create(id, book.profileId, FinancialAccount(
                        name = ra.name, type = typeOf(ra), currency = ra.currency, openingBalanceMinor = 0, openingDate = book.today(),
                        institution = c.institution, syncLinkId = c.id,
                    ), g)
                }
        }
        val link = SyncLink(c.provider, c.institution, localIds, remote.map { it.id }, c.consentExpiry?.let(::date), c.lastSyncedAt, statusOf(c.status), c.id)
        if (existing == null) repo.syncLinks.create(repo.newId(), book.profileId, link, g) else repo.syncLinks.update(existing.state.id, link, groupId = g)
    }

    /**
     * After the first fetch, each linked account's opening balance is set so the ledger equals the bank's balance:
     * opening = bank balance − sum of the fetched history. Later syncs only add transactions.
     */
    private suspend fun alignOpening(local: String, ra: RemoteAccount, g: String) {
        val a = repo.accounts.get(local) ?: return
        val txs = repo.transactions.list(a.state.profileId).map { it.value }
        val moved = app.penny.core.budget.Ledger.balance(local, a.value.copy(openingBalanceMinor = 0), txs)
        repo.accounts.update(local, a.value.copy(openingBalanceMinor = ra.balanceMinor - moved), groupId = g)
    }

    private fun typeOf(ra: RemoteAccount) = when (ra.type.lowercase()) {
        "credit", "credit_card", "card" -> AccountType.CREDIT_CARD
        "loan", "mortgage" -> AccountType.LOAN
        "investment", "brokerage" -> AccountType.INVESTMENT
        else -> AccountType.BANK
    }

    private fun statusOf(s: String) = when (s) {
        "needs_consent" -> SyncStatus.NEEDS_CONSENT
        "broken" -> SyncStatus.BROKEN
        "disconnected" -> SyncStatus.DISCONNECTED
        else -> SyncStatus.ACTIVE
    }

    private fun date(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    /** Pulls new transactions for every active link; returns how many were added or updated. */
    suspend fun syncAll(book: Book): Int {
        var n = 0
        book.syncLinks.filter { it.value.status != SyncStatus.DISCONNECTED }.forEach { s -> n += syncOne(book, s.state.id) }
        return n
    }

    suspend fun syncOne(book: Book, linkId: String): Int {
        val link = book.syncLinks.firstOrNull { it.state.id == linkId }?.value ?: return 0
        val out = sync.relayClient().syncConnection(link.connectionRef, SyncBody(accounts = link.remoteAccountIds))
        val g = repo.transactions.newGroup()
        var n = 0
        val byExternal = book.transactions.flatMap { t -> t.value.externalIds.map { it to t } }.toMap()
        val rules = book.rules.map { it.state.id to it.value }
        out.transactions.forEach { rt ->
            val local = link.accountIds.getOrNull(link.remoteAccountIds.indexOf(rt.accountId)) ?: return@forEach
            val ext = "bank:${link.connectionRef}:${rt.id}"
            val tx = toTx(book, rt, local, ext, link.provider)
            val seen = byExternal[ext]
            when {
                // Pending → posted (or amount corrected): update the same transaction.
                seen != null -> {
                    // Read the current row, not the snapshot from before the network call: a category the user changed
                    // while the sync ran must not be written back to its old value.
                    val cur = repo.transactions.get(seen.state.id)?.takeIf { it.state.deletedAt == null }?.value ?: return@forEach
                    val upd = cur.copy(status = if (rt.pending) TxStatus.PENDING else if (cur.status == TxStatus.RECONCILED) TxStatus.RECONCILED else TxStatus.CLEARED, amountMinor = tx.amountMinor)
                    if (upd != cur && repo.transactions.update(seen.state.id, upd, groupId = g) != null) n++
                }
                else -> {
                    val dupe = Dedupe.candidates(tx, book.transactions.map { it.state.id to it.value }).firstOrNull()
                    if (dupe != null) {
                        repo.transactions.get(dupe)?.takeIf { it.state.deletedAt == null }?.let { repo.transactions.update(dupe, Dedupe.merge(it.value, tx).copy(status = tx.status), reason = "merge", groupId = g) }
                    } else {
                        repo.transactions.create(repo.newId(), book.profileId, RuleEngine.apply(tx, rules, RuleContext(accountName = book::accountName)).tx, g)
                    }
                    n++
                }
            }
        }
        val st = book.syncLinks.first { it.state.id == linkId }
        if (st.value.lastFetchMillis == null) {
            out.accounts.forEach { ra -> link.accountIds.getOrNull(link.remoteAccountIds.indexOf(ra.id))?.let { alignOpening(it, ra, g) } }
        }
        repo.syncLinks.update(linkId, st.value.copy(status = statusOf(out.status), lastFetchMillis = System.currentTimeMillis()), groupId = g)
        return n
    }

    private fun toTx(book: Book, rt: RemoteTx, account: String, ext: String, provider: String) = Transaction(
        type = if (rt.amountMinor < 0) TxType.EXPENSE else TxType.INCOME, amountMinor = kotlin.math.abs(rt.amountMinor), currency = rt.currency,
        accountId = account, payee = rt.description.take(80).ifBlank { null }, date = date(rt.date), tz = book.profile.timezone,
        status = if (rt.pending) TxStatus.PENDING else TxStatus.CLEARED, source = "bank:$provider", externalIds = listOf(ext),
    )

    suspend fun reconsent(book: Book, linkId: String, token: String?): Connection? {
        val link = book.syncLinks.firstOrNull { it.state.id == linkId }?.value ?: return null
        val c = sync.relayClient().reconsent(link.connectionRef, ConnectBody(redirectUri = callbackUri, token = token))
        if (c.redirectUrl == null) link(book, c)
        return c
    }

    /** CP-10 delete-on-disconnect: the provider link and stored credentials are removed; accounts turn manual. */
    suspend fun disconnect(book: Book, linkId: String) {
        val s = book.syncLinks.firstOrNull { it.state.id == linkId } ?: return
        runCatching { sync.relayClient().disconnect(s.value.connectionRef) }
        val g = repo.syncLinks.newGroup()
        repo.syncLinks.update(linkId, s.value.copy(status = SyncStatus.DISCONNECTED), groupId = g)
        s.value.accountIds.forEach { id -> book.accountMap[id]?.let { repo.accounts.update(id, it.value.copy(syncLinkId = null), groupId = g) } }
    }
}
