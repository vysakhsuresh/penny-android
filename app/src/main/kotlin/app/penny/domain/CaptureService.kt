package app.penny.domain

import app.penny.capture.CaptureConfig
import app.penny.capture.CaptureSink
import app.penny.core.capture.ParsedCapture
import app.penny.core.event.Stored
import app.penny.core.model.CaptureItem
import app.penny.core.model.CaptureSource
import app.penny.core.model.CaptureState
import app.penny.core.model.Transaction
import app.penny.core.model.TxStatus
import app.penny.core.model.TxType
import app.penny.core.rules.Dedupe
import app.penny.core.rules.RuleContext
import app.penny.core.rules.RuleEngine
import app.penny.domain.ops.saveTransaction
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CP-04 review queue and CP-11 dedupe across sources. Captured items become proposals in the queue unless the source
 * has auto-post on (opt-in after N consistent accepts) or a rule says auto-post / ignore.
 */
@Singleton
class CaptureService @Inject constructor(
    private val repo: PennyRepository,
    private val store: BookStore,
    private val prefs: AppPrefs,
) : CaptureSink {

    override fun config(): CaptureConfig {
        val r = store.ready() ?: return CaptureConfig(false, emptySet(), emptyList(), "USD")
        val extra = prefs.string("capture.extraPackages")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return CaptureConfig(prefs.flag("capture.notifications"), r.pack.capturePackages + extra, r.pack.notificationTemplates, r.book.profile.baseCurrency)
    }

    /** Matches "XX1234" style account hints against account names, institutions and notes. */
    private fun guessAccount(book: Book, hint: String?): String? {
        val digits = hint?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.length >= 3 } ?: return null
        return book.activeAccounts.firstOrNull { a -> listOf(a.value.name, a.value.institution, a.value.note).any { it.contains(digits) } }?.state?.id
    }

    fun propose(book: Book, source: String, parsed: ParsedCapture, externalId: String, raw: String): Transaction {
        val acct = guessAccount(book, parsed.account) ?: book.profile.defaultAccountId?.takeIf { book.accountMap[it]?.value?.archived == false } ?: book.activeAccounts.firstOrNull()?.state?.id.orEmpty()
        val base = Transaction(
            type = if (parsed.income) TxType.INCOME else TxType.EXPENSE, amountMinor = parsed.amountMinor, currency = parsed.currency,
            accountId = acct, payee = parsed.payee, date = parsed.date ?: book.today(), time = LocalTime.now(book.zone).withSecond(0).withNano(0),
            tz = book.profile.timezone, status = TxStatus.PENDING, source = source, confidence = parsed.confidence, externalIds = listOf(externalId),
            note = if (parsed.lines.isNotEmpty()) parsed.lines.joinToString(", ") { it.first } else "",
        )
        // Payee memory fills the category; rules then apply on top.
        val withMemory = base.copy(categoryId = book.payees.firstOrNull { p -> RuleEngine.normalise(p.value.name) == RuleEngine.normalise(parsed.payee.orEmpty()) }?.value?.defaultCategoryId
            ?: book.transactions.filter { RuleEngine.normalise(it.value.payee.orEmpty()) == RuleEngine.normalise(parsed.payee.orEmpty()) && parsed.payee != null }.maxByOrNull { it.value.date }?.value?.categoryId)
        return RuleEngine.apply(withMemory, book.rules.map { it.state.id to it.value }, RuleContext(app = raw.substringBefore('\n'), accountName = book::accountName)).tx
    }

    /** Captures arrive concurrently (apps re-post notifications); one at a time, with ids seen this session remembered. */
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val seen = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 500
    }

    override suspend fun captured(source: String, detail: String, raw: String, parsed: ParsedCapture, externalId: String) = lock.withLock {
        // The book snapshot lags our own writes by a moment, so a second copy arriving right after would pass the check below.
        if (seen.put(externalId, true) != null) return@withLock
        capturedLocked(source, detail, raw, parsed, externalId)
    }

    private suspend fun capturedLocked(source: String, detail: String, raw: String, parsed: ParsedCapture, externalId: String) {
        val book = store.ready()?.book ?: return
        val src = book.captureSources.firstOrNull { it.value.source == "$source:$detail" }
        if (src?.value?.alwaysIgnore == true) return
        if (parsed.payee != null && src?.value?.ignoredPayees?.any { RuleEngine.normalise(it) == RuleEngine.normalise(parsed.payee!!) } == true) return
        // Already captured or recorded (CP-11)?
        if (book.txList.any { externalId in it.externalIds } || book.captures.any { externalId in it.value.proposed.externalIds }) return
        val tx = propose(book, "$source:$detail", parsed, externalId, raw)
        val rr = RuleEngine.apply(tx, book.rules.map { it.state.id to it.value })
        if (rr.ignore) return
        val dupe = Dedupe.candidates(tx, book.transactions.map { it.state.id to it.value }).firstOrNull()
        if (dupe != null) {
            // Merge the new source into the existing transaction's history instead of adding a copy.
            book.txMap[dupe]?.let { repo.transactions.update(dupe, Dedupe.merge(it.value, tx.copy(note = "")), reason = "merge") }
            return
        }
        if ((src?.value?.autoPost == true || rr.autoPost == true) && tx.accountId.isNotEmpty()) {
            repo.saveTransaction(book, null, tx.copy(status = TxStatus.CLEARED))
            return
        }
        repo.captures.create(repo.newId(), book.profileId, CaptureItem("$source:$detail", detail, raw.take(500), tx, parsed.confidence, duplicateOf = dupe))
    }

    /** Accept as-is or after editing; edits reset the "consistent accepts" streak for auto-post (CP-04). */
    suspend fun accept(book: Book, item: Stored<CaptureItem>, edited: Transaction?): String {
        val g = repo.captures.newGroup()
        val tx = (edited ?: item.value.proposed).copy(status = TxStatus.CLEARED)
        // One undo group: undoing "Add" removes the transaction and puts the item back in the queue.
        val res = repo.saveTransaction(book, null, tx, group = g)
        repo.captures.update(item.state.id, item.value.copy(state = CaptureState.ACCEPTED), groupId = g)
        val src = book.captureSources.firstOrNull { it.value.source == item.value.source }
        val streak = if (edited == null || edited == item.value.proposed) (src?.value?.acceptsInRow ?: 0) + 1 else 0
        if (src == null) repo.captureSources.create(repo.newId(), book.profileId, CaptureSource(item.value.source, streak), g)
        else repo.captureSources.update(src.state.id, src.value.copy(acceptsInRow = streak), groupId = g)
        return res.group
    }

    suspend fun ignore(book: Book, item: Stored<CaptureItem>, alwaysSource: Boolean, alwaysPayee: Boolean): String {
        val g = repo.captures.newGroup()
        repo.captures.update(item.state.id, item.value.copy(state = CaptureState.IGNORED), groupId = g)
        if (alwaysSource || alwaysPayee) {
            val src = book.captureSources.firstOrNull { it.value.source == item.value.source }
            val payee = item.value.proposed.payee
            val next = (src?.value ?: CaptureSource(item.value.source)).let { s ->
                s.copy(alwaysIgnore = s.alwaysIgnore || alwaysSource, ignoredPayees = if (alwaysPayee && payee != null) (s.ignoredPayees + payee).distinct() else s.ignoredPayees)
            }
            if (src == null) repo.captureSources.create(repo.newId(), book.profileId, next, g) else repo.captureSources.update(src.state.id, next, groupId = g)
        }
        return g
    }

    suspend fun setAutoPost(book: Book, source: String, on: Boolean): String {
        val g = repo.captureSources.newGroup()
        val src = book.captureSources.firstOrNull { it.value.source == source }
        if (src == null) repo.captureSources.create(repo.newId(), book.profileId, CaptureSource(source, autoPost = on), g)
        else repo.captureSources.update(src.state.id, src.value.copy(autoPost = on), groupId = g)
        return g
    }

    /** UX-09 share intake: plain text from any app is parsed and lands in the review queue. */
    suspend fun fromSharedText(text: String): Boolean {
        val book = store.ready()?.book ?: return false
        val parsed = app.penny.core.capture.NotificationParser.generic(text, book.profile.baseCurrency)
            ?: app.penny.core.capture.ReceiptParser.parse(text, book.profile.baseCurrency, book.today()) ?: return false
        captured("share", "text", text, parsed, "share:" + app.penny.core.io.Importers.hash(text))
        return true
    }

    companion object { const val AUTO_POST_SUGGEST_AFTER = 10 }
}
