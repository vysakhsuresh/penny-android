package app.penny.domain

import android.content.Context
import app.penny.core.capture.NotificationParser
import app.penny.core.capture.ReceiptParser
import app.penny.core.rules.RuleEngine
import app.penny.sync.MailCrypto
import app.penny.sync.MerchantItem
import app.penny.sync.StreamCrypto
import app.penny.sync.Vault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.interfaces.ECPublicKey
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class MailPlain(val from: String = "", val subject: String = "", val text: String = "", val receivedAt: Long = 0)

/**
 * CP-06 email forwarding client: a P-256 key pair kept in the Keystore-encrypted vault, the public half registered with
 * the relay. Forwarded receipts arrive already encrypted; this device decrypts, parses on the phone, sends the item to
 * the review queue and deletes the relay copy. PY-03 merchant map: opt-in suggestions and contributions.
 */
@Singleton
class MailService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: SyncManager,
    private val capture: CaptureService,
    private val store: BookStore,
    private val repo: PennyRepository,
    private val prefs: AppPrefs,
) {
    private val vault by lazy { Vault(context) }
    private val json = Json { ignoreUnknownKeys = true }

    private fun keys() = runCatching {
        val priv = vault.get("mail.priv") ?: return@runCatching null
        val pub = vault.get("mail.pub") ?: return@runCatching null
        MailCrypto.restore(priv, pub)
    }.getOrNull()

    /** Creates the forwarding address and registers this device's key. Returns the address. */
    suspend fun enable(rotate: Boolean = false): String {
        val kp = keys() ?: MailCrypto.newKeyPair().also { vault.put("mail.priv", MailCrypto.encodePrivate(it)); vault.put("mail.pub", MailCrypto.encodePublic(it)) }
        sync.relayClient().putMailKey(StreamCrypto.b64(MailCrypto.uncompressed(kp.public as ECPublicKey)))
        val a = sync.relayClient().mailAddress(rotate).address
        prefs.setString("mail.address", a)
        return a
    }

    suspend fun disable() {
        runCatching { sync.relayClient().deleteMailAddress() }
        vault.put("mail.priv", null); vault.put("mail.pub", null)
        prefs.setString("mail.address", null)
    }

    val address: String? get() = prefs.string("mail.address")

    /** Pulls, decrypts, parses and deletes pending mail. Returns how many receipts reached the review queue. */
    suspend fun fetch(): Int {
        if (address == null) return 0
        val kp = keys() ?: return 0
        val book = store.ready()?.book ?: return 0
        var n = 0
        sync.relayClient().pendingMail().forEach { m ->
            val plain = runCatching { json.decodeFromString(MailPlain.serializer(), String(MailCrypto.decrypt(StreamCrypto.unb64(m.blob), kp))) }.getOrNull()
            if (plain != null) {
                val body = plain.subject + "\n" + plain.text
                val parsed = ReceiptParser.parse(body, book.profile.baseCurrency, book.today()) ?: NotificationParser.generic(body, book.profile.baseCurrency)
                if (parsed != null) {
                    val payee = parsed.payee ?: plain.from.substringAfter('@').substringBefore('.').replaceFirstChar(Char::uppercase)
                    capture.captured("email", plain.from.substringAfter('@'), body.take(500), parsed.copy(payee = payee), "mail:${m.id}")
                    n++
                }
            }
            // The relay copy is removed whether or not it parsed: raw mail is never kept (CP-06).
            runCatching { sync.relayClient().deleteMail(m.id) }
        }
        return n
    }

    // ---------------------------------------------------------------- PY-03 community merchant map (opt-in)

    val merchantsOn: Boolean get() = prefs.flag("merchants.community")

    /** Fills default categories for payees seen in captures/imports that the user has not categorised yet. */
    suspend fun suggestPayees(): Int {
        if (!merchantsOn || !sync.signedIn) return 0
        val book = store.ready()?.book ?: return 0
        val pending = book.payees.filter { it.value.defaultCategoryId == null }.take(200)
        if (pending.isEmpty()) return 0
        val out = sync.relayClient().suggestMerchants(book.profile.regionPack, pending.map { it.value.name })
        var n = 0
        out.filter { it.confidence >= 0.6 }.forEach { s ->
            val p = pending.firstOrNull { RuleEngine.normalise(it.value.name) == RuleEngine.normalise(s.descriptor) } ?: return@forEach
            val cat = "${book.profileId}:${s.categoryKey}".takeIf { book.categoryMap.containsKey(it) } ?: return@forEach
            repo.payees.update(p.state.id, p.value.copy(defaultCategoryId = cat)); n++
        }
        return n
    }

    /** Shares the user's own payee → category choices (names and default category keys only, never amounts). */
    suspend fun contribute(): Int {
        if (!merchantsOn || !sync.signedIn) return 0
        val book = store.ready()?.book ?: return 0
        val items = book.payees.mapNotNull { p ->
            val key = book.categoryMap[p.value.defaultCategoryId]?.value?.key ?: return@mapNotNull null
            val raw = p.value.aliases.firstOrNull() ?: return@mapNotNull null
            // Strip long digit runs (references) before anything leaves the phone.
            MerchantItem(raw.replace(Regex("[0-9][0-9 .\\-]{3,}"), " ").trim().take(64), p.value.name.take(48), key)
        }.filter { it.descriptor.length >= 3 }.take(100)
        if (items.isEmpty()) return 0
        runCatching { sync.relayClient().contributeMerchants(book.profile.regionPack, items) }
        return items.size
    }
}
