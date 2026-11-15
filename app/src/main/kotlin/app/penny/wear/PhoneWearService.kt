package app.penny.wear

import app.penny.core.capture.VoiceGrammar
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.domain.BookLoad
import app.penny.domain.BookStore
import app.penny.domain.PennyRepository
import app.penny.domain.ops.fromTemplate
import app.penny.domain.ops.period
import app.penny.domain.ops.safeToSpend
import app.penny.domain.ops.saveTransaction
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalTime
import javax.inject.Inject

/** Messages between phone and watch (WG-02). Paths start with /penny. Payloads are small JSON objects. */
object WearPaths {
    const val SUMMARY_REQUEST = "/penny/summary/request"
    const val SUMMARY = "/penny/summary"
    const val ADD_TEMPLATE = "/penny/add/template"
    const val ADD_VOICE = "/penny/add/voice"
    const val ADDED = "/penny/added"
}

@Serializable
data class WearSummary(val safe: String, val perDay: String, val daysLeft: Int, val templates: List<WearTemplate>, val hidden: Boolean)

@Serializable
data class WearTemplate(val id: String, val name: String, val amount: String?)

/** Phone side: answers summary requests and adds transactions sent from the watch. Data stays phone-to-watch. */
@AndroidEntryPoint
class PhoneWearService : WearableListenerService() {
    @Inject lateinit var store: BookStore
    @Inject lateinit var repo: PennyRepository
    @Inject lateinit var prefs: app.penny.domain.AppPrefs

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    override fun onMessageReceived(event: MessageEvent) {
        scope.launch {
            val ready = withTimeoutOrNull(5_000) { store.state.filterIsInstance<BookLoad.Ready>().first() } ?: return@launch
            val b = ready.book
            when (event.path) {
                WearPaths.ADD_TEMPLATE -> {
                    val id = String(event.data)
                    // Reply "ok" only when something was saved; an empty reply makes the watch say it could not add.
                    val saved = b.templates.firstOrNull { it.state.id == id }?.let { t ->
                        val tx = b.fromTemplate(t.value).copy(source = "watch")
                        if (tx.amountMinor > 0 && tx.accountId.isNotEmpty()) runCatching { repo.saveTransaction(b, null, tx) }.isSuccess else false
                    } ?: false
                    reply(event.sourceNodeId, WearPaths.ADDED, if (saved) "ok" else "")
                }
                WearPaths.ADD_VOICE -> {
                    val r = VoiceGrammar(b.profile.baseCurrency, b.today(), b.categories.associate { it.value.name.lowercase() to it.state.id }, b.accounts.associate { it.value.name.lowercase() to it.state.id })
                        .parse(String(event.data))
                    val amount = r.amountMinor
                    val acct = r.accountId ?: b.profile.defaultAccountId ?: b.activeAccounts.firstOrNull()?.state?.id
                    if (amount != null && amount > 0 && acct != null) {
                        repo.saveTransaction(b, null, Transaction(
                            type = if (r.income) TxType.INCOME else TxType.EXPENSE, amountMinor = amount, currency = r.currency, accountId = acct,
                            categoryId = r.categoryId, payee = r.payee, date = r.date, time = LocalTime.now(b.zone).withSecond(0).withNano(0),
                            tz = b.profile.timezone, note = r.note, source = "watch",
                        ))
                        reply(event.sourceNodeId, WearPaths.ADDED, ready.fmt.format(amount, r.currency))
                    } else reply(event.sourceNodeId, WearPaths.ADDED, "")
                }
            }
            // Every exchange refreshes the watch summary.
            val s = b.safeToSpend(b.period())
            // Same rule as the widgets: amounts stay off the watch when hidden or when the app lock is on.
            val hidden = prefs.appearance.value.hideAmounts || prefs.security.value.lockEnabled
            val summary = WearSummary(
                if (hidden) "" else ready.fmt.format(s.safeMinor, b.profile.baseCurrency), if (hidden) "" else ready.fmt.format(s.perDayMinor, b.profile.baseCurrency), s.daysLeft + 1,
                b.templates.filter { it.value.favourite }.take(8).map { WearTemplate(it.state.id, it.value.name, if (hidden) null else it.value.amountMinor?.let { a -> ready.fmt.format(a, it.value.currency) }) },
                hidden = hidden,
            )
            reply(event.sourceNodeId, WearPaths.SUMMARY, json.encodeToString(WearSummary.serializer(), summary))
        }
    }

    private fun reply(node: String, path: String, body: String) {
        runCatching { Wearable.getMessageClient(this).sendMessage(node, path, body.toByteArray()) }
    }
}
