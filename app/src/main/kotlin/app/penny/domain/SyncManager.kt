package app.penny.domain

import android.content.Context
import android.os.Build
import app.penny.core.Brand
import app.penny.core.event.Entities
import app.penny.core.event.PennyJson
import app.penny.core.model.Member
import app.penny.core.model.Profile
import app.penny.core.model.Role
import app.penny.core.model.Space
import app.penny.data.db.PennyDao
import app.penny.sync.DeviceKeys
import app.penny.sync.InviteCreate
import app.penny.sync.InviteInfo
import app.penny.sync.KeyRing
import app.penny.sync.RelayClient
import app.penny.sync.Session
import app.penny.sync.StreamCrypto
import app.penny.sync.StreamKey
import app.penny.sync.SyncEngine
import app.penny.sync.SyncResult
import app.penny.sync.Vault
import app.penny.sync.VerifyAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncPhase { LOCAL_ONLY, NEEDS_PHRASE, READY, SYNCING, ERROR }

data class SyncState(val phase: SyncPhase, val email: String? = null, val lastSync: Long? = null, val error: String? = null, val region: String = "")

/**
 * US-01 accounts (local-only by default, upgradeable without loss), SE-02/SE-03 keys, SY-02…SY-04 sync, US-05…US-13
 * spaces and invites. A space is a shared book: its own stream, its own key, members with roles.
 */
@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: PennyRepository,
    private val dao: PennyDao,
    private val prefs: AppPrefs,
) {
    private val vault by lazy { Vault(context) }
    private val device by lazy { DeviceKeys(context) }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(initial())
    val state: StateFlow<SyncState> = _state

    private fun initial(): SyncState = when {
        prefs.string("sync.accountId") == null -> SyncState(SyncPhase.LOCAL_ONLY)
        runCatching { Vault(context).get("master") }.getOrNull() == null -> SyncState(SyncPhase.NEEDS_PHRASE, prefs.string("sync.email"))
        else -> SyncState(SyncPhase.READY, prefs.string("sync.email"), prefs.long("sync.last").takeIf { it > 0 }, region = prefs.string("sync.region").orEmpty())
    }

    val relayUrl: String get() = prefs.string("sync.relay") ?: Brand.RELAY_URL
    private val relay get() = RelayClient(relayUrl, { vault.string("token") })
    fun relayClient(): RelayClient = relay

    /** MO-03: tells the relay about a store purchase so Plus-only services (bank sync) work on every device. */
    suspend fun reportPurchase(token: String, productId: String) = runCatching {
        relay.putEntitlement(app.penny.sync.EntitlementBody("play", token, productId))
    }
    val signedIn: Boolean get() = prefs.string("sync.accountId") != null

    suspend fun startSignIn(email: String, relayOverride: String?) {
        relayOverride?.takeIf { it.isNotBlank() }?.let { prefs.setString("sync.relay", it.trim()) }
        relay.startAuth(email.trim())
        prefs.setString("sync.email", email.trim())
    }

    /** Verifies the code. Returns true when this is a brand-new account (the UI then shows a new recovery phrase). */
    suspend fun verify(code: String): Session {
        val email = prefs.string("sync.email") ?: error("no email")
        val s = relay.verify(VerifyAuth(email, code.trim(), Build.MODEL ?: "Android", "android", device.publicKey()))
        vault.putString("token", s.token)
        prefs.setString("sync.accountId", s.accountId)
        prefs.setString("sync.deviceId", s.deviceId)
        prefs.setString("sync.salt", s.salt)
        prefs.setString("sync.region", s.region)
        // The local account id becomes the server account id so events from now on carry it (US-01 upgrade path).
        prefs.setAccountId(s.accountId)
        _state.value = SyncState(SyncPhase.NEEDS_PHRASE, email, region = s.region)
        return s
    }

    /** True when the relay holds no keys for this account yet, i.e. this is the first device. */
    suspend fun isNewAccount(): Boolean = runCatching { relay.keys().isEmpty() }.getOrDefault(true)

    /** SE-03: unlocks (or sets up) the master key from the 24-word phrase; also used for encrypted backups. */
    suspend fun usePhrase(phrase: String): Boolean {
        val salt = prefs.string("sync.salt")?.let(StreamCrypto::unb64) ?: return false
        val master = StreamCrypto.masterKey(BackupCrypto.normalise(phrase), salt)
        // If the relay already holds a wrapped key for one of our streams, the phrase must open it.
        val existing = runCatching { relay.keys() }.getOrDefault(emptyList()).firstOrNull { it.kind == "master" }
        if (existing != null && runCatching { StreamCrypto.unwrap(master, StreamCrypto.unb64(existing.wrapped), existing.stream) }.isFailure) return false
        vault.put("master", master)
        BackupCrypto.enable(prefs, BackupCrypto.normalise(phrase).split(' '))
        _state.value = SyncState(SyncPhase.READY, prefs.string("sync.email"), region = prefs.string("sync.region").orEmpty())
        return true
    }

    private val ring = KeyRing { stream -> vault.get("sk.$stream") }

    /** Makes sure every local profile has a stream key on the relay (wrapped by the master key) and pulls keys we lack. */
    private suspend fun ensureKeys(streams: List<String>) {
        val master = vault.get("master") ?: return
        val me = prefs.string("sync.accountId") ?: return
        val remote = relay.keys()
        remote.forEach { k ->
            if (vault.get("sk.${k.stream}") != null) return@forEach
            val key = runCatching {
                if (k.kind == "master") StreamCrypto.unwrap(master, StreamCrypto.unb64(k.wrapped), k.stream)
                else device.decrypt(StreamCrypto.unb64(k.wrapped), "space:${k.stream}")
            }.getOrNull()
            if (key != null) {
                vault.put("sk.${k.stream}", key)
                // Keep a master-wrapped copy so other devices of this account can open it with the phrase.
                if (k.kind != "master") relay.putKey(StreamKey(k.stream, me, StreamCrypto.b64(StreamCrypto.wrap(master, key, k.stream)), "master"))
            }
        }
        streams.forEach { s ->
            if (vault.get("sk.$s") == null && remote.none { it.stream == s }) {
                val key = StreamCrypto.newKey()
                vault.put("sk.$s", key)
                relay.putKey(StreamKey(s, me, StreamCrypto.b64(StreamCrypto.wrap(master, key, s)), "master"))
            }
        }
    }

    /** Streams this device syncs: every local profile plus every space we hold a key for. */
    private suspend fun streams(): List<String> {
        val profiles = dao.observeAllOf(Entities.PROFILE).first().map { it.id }
        val spaces = vault.names("sk.").map { it.removePrefix("sk.") }
        return (profiles + spaces).distinct().filter { it != prefs.security.value.duressProfileId }
    }

    suspend fun syncNow(): SyncResult = mutex.withLock {
        if (!signedIn || vault.get("master") == null) return SyncResult(0, 0)
        _state.value = _state.value.copy(phase = SyncPhase.SYNCING, error = null)
        return runCatching {
            val all = streams()
            ensureKeys(all)
            val engine = SyncEngine(repo.store, dao, relay, ring)
            var pushed = 0; var pulled = 0
            (all + vault.names("sk.").map { it.removePrefix("sk.") }).distinct().forEach { s ->
                val r = engine.sync(s); pushed += r.pushed; pulled += r.pulled
            }
            shareSpaceKeys()
            prefs.setLong("sync.last", System.currentTimeMillis())
            _state.value = SyncState(SyncPhase.READY, prefs.string("sync.email"), System.currentTimeMillis(), region = prefs.string("sync.region").orEmpty())
            SyncResult(pushed, pulled)
        }.getOrElse { e ->
            _state.value = _state.value.copy(phase = SyncPhase.ERROR, error = e.message)
            SyncResult(0, 0)
        }
    }

    // ------------------------------------------------------------------ Spaces (US-05…US-13)

    /** Creates a shared book: a stream with its own key; the creator is Owner. Returns the space id. */
    suspend fun createSpace(name: String, base: Profile): String {
        val id = repo.newId()
        val me = prefs.string("sync.accountId") ?: prefs.localAccountId
        if (signedIn) relay.createSpace(id)
        vault.put("sk.$id", StreamCrypto.newKey())
        val g = repo.profiles.newGroup()
        repo.profiles.create(id, id, base.copy(name = name, decoy = false, defaultAccountId = null), g)
        repo.seedCategories(id)
        repo.spaces.create(id, id, Space(name, me, base.baseCurrency, base.cycle, base.timezone), g)
        repo.members.create(repo.newId(), id, Member(id, me, prefs.string("sync.email") ?: base.name, Role.OWNER, publicKey = device.publicKey()), g)
        if (signedIn) runCatching { syncNow() }
        return id
    }

    fun inviteLink(token: String) = "${Brand.WEB_URL}/i/$token"

    suspend fun invite(spaceId: String, role: Role, summary: String): InviteInfo = relay.createInvite(spaceId, InviteCreate(role.name, 7, summary))
    suspend fun revokeInvite(token: String) = relay.revokeInvite(token)
    suspend fun peekInvite(token: String): InviteInfo = relay.invite(token)

    /** US-12: accepting registers our public key; an admin device wraps the space key for us on its next sync. */
    suspend fun acceptInvite(token: String): InviteInfo {
        val info = relay.acceptInvite(token, device.publicKey())
        runCatching { syncNow() }
        return info
    }

    /** Wraps keys of spaces we can open for members who do not have one yet (run by any member's device). */
    private suspend fun shareSpaceKeys() {
        val me = prefs.string("sync.accountId") ?: return
        val keys = relay.keys()
        vault.names("sk.").map { it.removePrefix("sk.") }.forEach { space ->
            val members = runCatching { relay.members(space) }.getOrNull() ?: return@forEach
            val key = vault.get("sk.$space") ?: return@forEach
            members.filter { !it.left && it.accountId != me && it.publicKey.isNotEmpty() }.forEach { m ->
                // We can only see our own keys; the relay rejects duplicates idempotently, so re-wrapping is harmless.
                if (keys.none { it.stream == space && it.accountId == m.accountId }) {
                    runCatching { relay.putKey(StreamKey(space, m.accountId, StreamCrypto.b64(DeviceKeys.encryptFor(m.publicKey, key, "space:$space")), "member")) }
                }
            }
        }
    }

    suspend fun members(spaceId: String) = relay.members(spaceId)
    suspend fun setRole(spaceId: String, account: String, role: Role) = relay.setRole(spaceId, account, role.name)
    suspend fun removeMember(spaceId: String, account: String) = relay.removeMember(spaceId, account)
    suspend fun leave(spaceId: String) {
        prefs.string("sync.accountId")?.let { relay.removeMember(spaceId, it) }
        vault.put("sk.$spaceId", null)
        repo.store.eraseProfile(spaceId)
    }

    suspend fun devices() = relay.devices()
    suspend fun signOutDevice(id: String) = relay.signOutDevice(id)

    /** Signs this device out; local data stays (US-01: local-only mode keeps working). */
    fun signOut() {
        vault.clear()
        listOf("sync.accountId", "sync.deviceId", "sync.salt", "sync.email", "sync.region").forEach { prefs.setString(it, null) }
        _state.value = SyncState(SyncPhase.LOCAL_ONLY)
    }

    /** SE-09: deletes the account on the relay (and every space it solely owns), then signs out. */
    suspend fun deleteRemoteAccount() {
        relay.deleteAccount()
        signOut()
    }

    /** US-14: an advisor link. The report is encrypted with a random key that only appears in the link fragment. */
    suspend fun advisorLink(reportJson: String, days: Int): String {
        val id = repo.newId().replace("-", "")
        val key = StreamCrypto.newKey()
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv)) }
        val blob = iv + c.doFinal(reportJson.toByteArray())
        val expires = System.currentTimeMillis() + days * 86_400_000L
        relay.putAdvisor(id, StreamCrypto.b64(blob), expires)
        return "$relayUrl/a/$id#${StreamCrypto.b64(key)}"
    }

    suspend fun revokeAdvisor(id: String) = relay.deleteAdvisor(id)
}
