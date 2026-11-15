package app.penny.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.penny.core.event.Entities
import app.penny.core.event.Op
import app.penny.core.event.PennyJson
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.core.money.CurrencyUnit
import app.penny.data.db.KvRow
import app.penny.data.toEvent
import app.penny.domain.AppPrefs
import app.penny.domain.BookStore
import app.penny.domain.PennyRepository
import app.penny.domain.ops.saveTransaction
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.time.LocalTime
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/**
 * WG-03 / M6 automation on the device. Penny is end-to-end encrypted, so third-party automation talks to the phone, not a
 * cloud API:
 *  - Tasker, Automate, MacroDroid…: broadcast `app.penny.action.ADD_TRANSACTION` with extras `amount` (decimal text),
 *    optional `type` (expense|income), `currency`, `category` (name or key), `payee`, `note`, `account` (name).
 *    Off until the user allows it, and only adds — it can never read data.
 *  - Webhooks: when the user sets a URL, each new transaction is POSTed as JSON to that URL only, signed with
 *    HMAC-SHA256 in `X-Penny-Signature`. Nothing is sent anywhere else.
 */
@AndroidEntryPoint
class AutomationReceiver : BroadcastReceiver() {
    @Inject lateinit var repo: PennyRepository
    @Inject lateinit var store: BookStore
    @Inject lateinit var prefs: AppPrefs

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADD || !prefs.flag(FLAG_INTENTS)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            // Any app can send this once the user allows it: bad input must be ignored, never crash Penny.
            try { runCatching {
                val b = store.ready()?.book ?: return@launch
                val acct = intent.getStringExtra("account")?.let { n -> b.activeAccounts.firstOrNull { it.value.name.equals(n, true) }?.state?.id }
                    ?: b.profile.defaultAccountId ?: b.activeAccounts.firstOrNull()?.state?.id ?: return@launch
                val currency = intent.getStringExtra("currency")?.uppercase()?.takeIf { c -> runCatching { CurrencyUnit.of(c) }.isSuccess }
                    ?: b.accountMap[acct]?.value?.currency ?: b.profile.baseCurrency
                val amount = intent.getStringExtra("amount")?.replace(',', '.')?.toBigDecimalOrNull()?.abs()
                    ?.setScale(CurrencyUnit.of(currency).minorUnits, RoundingMode.HALF_EVEN)?.unscaledValue()?.toLong() ?: return@launch
                if (amount <= 0) return@launch
                val cat = intent.getStringExtra("category")?.let { c -> b.categories.firstOrNull { it.value.name.equals(c, true) || it.value.key == c }?.state?.id }
                repo.saveTransaction(b, null, Transaction(
                    type = if (intent.getStringExtra("type") == "income") TxType.INCOME else TxType.EXPENSE, amountMinor = amount, currency = currency,
                    accountId = acct, categoryId = cat, payee = intent.getStringExtra("payee"), note = intent.getStringExtra("note").orEmpty(),
                    date = b.today(), time = LocalTime.now(b.zone).withSecond(0).withNano(0), tz = b.profile.timezone, source = "automation",
                ), applyRules = true)
            }.onFailure { android.util.Log.w("Penny", "automation intent ignored", it) } } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_ADD = "app.penny.action.ADD_TRANSACTION"
        const val FLAG_INTENTS = "automation.intents"
    }
}

object Webhooks {
    const val KEY_URL = "webhook.url"
    const val KEY_SECRET = "webhook.secret"

    fun newSecret(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun sign(secret: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return "sha256=" + mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Sends transactions created since the last delivery; only https URLs are allowed. Returns how many were sent. */
    suspend fun deliver(repo: PennyRepository, prefs: AppPrefs, pid: String): Int {
        val url = prefs.string(KEY_URL)?.takeIf { it.startsWith("https://") } ?: return 0
        val secret = prefs.string(KEY_SECRET) ?: return 0
        // First delivery starts from now: history is never sent.
        // One cursor per profile: a shared cursor skipped the other profile's events after switching.
        val cursorKey = "webhook.seq.$pid"
        val after = repo.dao.kv(cursorKey)?.toLongOrNull() ?: repo.dao.maxSeq().also { repo.dao.putKv(KvRow(cursorKey, it.toString())) }
        val events = repo.dao.localEventsAfter(pid, after, 200).map { it.toEvent() }
        val created = events.filter { it.entity == Entities.TRANSACTION && it.op == Op.CREATE }
        if (events.isEmpty()) return 0
        if (created.isNotEmpty()) {
            val body = buildJsonObject {
                put("type", "transactions.created")
                put("transactions", buildJsonArray {
                    created.forEach { e ->
                        val t = runCatching { PennyJson.decodeFromJsonElement(Transaction.serializer(), e.patch) }.getOrNull() ?: return@forEach
                        add(buildJsonObject {
                            put("id", e.entityId); put("type", t.type.name.lowercase()); put("date", t.date.toString())
                            put("amount", BigDecimal.valueOf(t.amountMinor, CurrencyUnit.of(t.currency).minorUnits).toPlainString())
                            put("currency", t.currency); put("payee", JsonPrimitive(t.payee)); put("note", t.note); put("source", t.source)
                        })
                    }
                })
            }.toString()
            val ok = runCatching {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/json"); setRequestProperty("X-Penny-Signature", sign(secret, body))
                }
                c.outputStream.use { it.write(body.toByteArray()) }
                c.responseCode in 200..299
            }.getOrDefault(false)
            if (!ok) return 0
        }
        repo.dao.putKv(KvRow(cursorKey, events.last().seq.toString()))
        return created.size
    }
}
