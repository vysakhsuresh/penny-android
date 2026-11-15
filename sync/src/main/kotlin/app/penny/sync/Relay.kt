package app.penny.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable data class StartAuth(val email: String)
@Serializable data class VerifyAuth(val email: String, val code: String, val deviceName: String, val platform: String, val publicKey: String)
@Serializable data class Session(val accountId: String, val deviceId: String, val token: String, val salt: String, val region: String = "")
@Serializable data class PushBody(val blobs: List<String>)
@Serializable data class PushResult(val cursor: Long)
@Serializable data class PulledBlob(val cursor: Long, val blob: String, val device: String = "")
@Serializable data class StreamKey(val stream: String, val accountId: String, val wrapped: String, val kind: String)
@Serializable data class SpaceCreate(val spaceId: String)
@Serializable data class InviteCreate(val role: String, val expiresInDays: Int = 7, val summary: String = "")
@Serializable data class InviteInfo(val token: String, val spaceId: String, val role: String, val expiresAt: Long, val summary: String = "", val inviterPublicKey: String = "")
@Serializable data class InviteAccept(val publicKey: String)
@Serializable data class MemberInfo(val accountId: String, val role: String, val publicKey: String, val joinedAt: Long, val left: Boolean = false)
@Serializable data class RoleChange(val role: String)
@Serializable data class DeviceInfo(val deviceId: String, val name: String, val platform: String, val lastSeen: Long, val current: Boolean = false)
@Serializable data class AdvisorPut(val blob: String, val expiresAt: Long)
@Serializable data class ErrorBody(val error: String = "")

// ---- CP-10 bank connectors (tokens stay on the server, SE-08)
@Serializable data class ConnectorInfo(val id: String, val name: String, val kind: String, val regions: List<String> = emptyList(), val enabled: Boolean = true)
@Serializable data class ConnectBody(val provider: String? = null, val redirectUri: String? = null, val token: String? = null)
@Serializable data class RemoteAccount(val id: String, val name: String, val currency: String, val type: String = "", val balanceMinor: Long = 0)
@Serializable data class RemoteTx(val id: String, val accountId: String, val date: Long, val amountMinor: Long, val currency: String, val description: String = "", val pending: Boolean = false)
@Serializable data class Connection(
    val id: String = "", val provider: String = "", val institution: String = "", val status: String = "active",
    val consentExpiry: Long? = null, val lastSyncedAt: Long? = null, val createdAt: Long = 0, val accounts: List<RemoteAccount>? = null,
    val redirectUrl: String? = null, val state: String? = null,
)
@Serializable data class CompleteBody(val state: String, val params: Map<String, String>)
@Serializable data class SyncBody(val accounts: List<String>? = null, val since: Long? = null, val full: Boolean = false)
@Serializable data class SyncOut(val transactions: List<RemoteTx> = emptyList(), val accounts: List<RemoteAccount> = emptyList(), val status: String = "active", val message: String? = null)
@Serializable data class EntitlementBody(val store: String, val purchaseToken: String, val productId: String)
@Serializable data class Entitlement(val plus: Boolean = false, val plusUntil: Long? = null, val productId: String? = null, val store: String? = null)

class RelayException(val code: Int, message: String) : IOException(message)

/**
 * HTTPS client for the relay (SY-02). The relay sees account ids, stream ids, opaque blobs and sizes only; every user
 * value is end-to-end encrypted before it leaves the device (SE-02). Region is chosen at sign-up (SE-11).
 */
class RelayClient(
    private val baseUrl: String,
    private val token: () -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val media = "application/json".toMediaType()

    private suspend fun <T> call(method: String, path: String, body: String?, out: KSerializer<T>?): T? = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(baseUrl.trimEnd('/') + path)
        token()?.let { b.header("Authorization", "Bearer $it") }
        b.method(method, body?.toRequestBody(media) ?: if (method == "POST" || method == "PUT") "{}".toRequestBody(media) else null)
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RelayException(r.code, runCatching { json.decodeFromString(ErrorBody.serializer(), text).error }.getOrDefault("").ifBlank { "HTTP ${r.code}" })
            if (out == null || text.isBlank()) null else json.decodeFromString(out, text)
        }
    }

    private fun <T> enc(s: KSerializer<T>, v: T) = json.encodeToString(s, v)

    suspend fun startAuth(email: String) { call<Unit>("POST", "/v1/auth/start", enc(StartAuth.serializer(), StartAuth(email)), null) }
    suspend fun verify(v: VerifyAuth): Session = call("POST", "/v1/auth/verify", enc(VerifyAuth.serializer(), v), Session.serializer())!!

    suspend fun push(stream: String, blobs: List<String>): Long = call("POST", "/v1/streams/$stream/events", enc(PushBody.serializer(), PushBody(blobs)), PushResult.serializer())!!.cursor
    suspend fun pull(stream: String, after: Long, limit: Int = 200): List<PulledBlob> =
        call("GET", "/v1/streams/$stream/events?after=$after&limit=$limit", null, ListSerializer(PulledBlob.serializer())).orEmpty()

    suspend fun putKey(k: StreamKey) { call<Unit>("PUT", "/v1/keys/${k.stream}/${k.accountId}", enc(StreamKey.serializer(), k), null) }
    suspend fun keys(): List<StreamKey> = call("GET", "/v1/keys", null, ListSerializer(StreamKey.serializer())).orEmpty()

    suspend fun createSpace(id: String) { call<Unit>("POST", "/v1/spaces", enc(SpaceCreate.serializer(), SpaceCreate(id)), null) }
    suspend fun createInvite(space: String, i: InviteCreate): InviteInfo = call("POST", "/v1/spaces/$space/invites", enc(InviteCreate.serializer(), i), InviteInfo.serializer())!!
    suspend fun revokeInvite(token: String) { call<Unit>("DELETE", "/v1/invites/$token", null, null) }
    suspend fun invite(token: String): InviteInfo = call("GET", "/v1/invites/$token", null, InviteInfo.serializer())!!
    suspend fun acceptInvite(token: String, publicKey: String): InviteInfo = call("POST", "/v1/invites/$token/accept", enc(InviteAccept.serializer(), InviteAccept(publicKey)), InviteInfo.serializer())!!
    suspend fun members(space: String): List<MemberInfo> = call("GET", "/v1/spaces/$space/members", null, ListSerializer(MemberInfo.serializer())).orEmpty()
    suspend fun setRole(space: String, account: String, role: String) { call<Unit>("PUT", "/v1/spaces/$space/members/$account", enc(RoleChange.serializer(), RoleChange(role)), null) }
    suspend fun removeMember(space: String, account: String) { call<Unit>("DELETE", "/v1/spaces/$space/members/$account", null, null) }

    suspend fun devices(): List<DeviceInfo> = call("GET", "/v1/devices", null, ListSerializer(DeviceInfo.serializer())).orEmpty()
    suspend fun signOutDevice(id: String) { call<Unit>("DELETE", "/v1/devices/$id", null, null) }
    /** SE-09 / SY-09: relay deletes everything for the account within 24 h. */
    suspend fun deleteAccount() { call<Unit>("DELETE", "/v1/account", null, null) }

    /** US-14: an encrypted report blob; the key travels only in the link's #fragment, never to the server. */
    suspend fun putAdvisor(id: String, blob: String, expiresAt: Long) { call<Unit>("PUT", "/v1/advisor/$id", enc(AdvisorPut.serializer(), AdvisorPut(blob, expiresAt)), null) }
    suspend fun deleteAdvisor(id: String) { call<Unit>("DELETE", "/v1/advisor/$id", null, null) }

    suspend fun connectors(): List<ConnectorInfo> = call("GET", "/v1/connectors", null, ListSerializer(ConnectorInfo.serializer())).orEmpty()
    /** Token providers return a Connection with accounts; redirect providers return redirectUrl and state. */
    suspend fun connect(b: ConnectBody): Connection = call("POST", "/v1/connections", enc(ConnectBody.serializer(), b), Connection.serializer())!!
    suspend fun complete(state: String, params: Map<String, String>): Connection = call("POST", "/v1/connections/complete", enc(CompleteBody.serializer(), CompleteBody(state, params)), Connection.serializer())!!
    suspend fun connections(): List<Connection> = call("GET", "/v1/connections", null, ListSerializer(Connection.serializer())).orEmpty()
    suspend fun syncConnection(id: String, b: SyncBody): SyncOut = call("POST", "/v1/connections/$id/sync", enc(SyncBody.serializer(), b), SyncOut.serializer())!!
    suspend fun reconsent(id: String, b: ConnectBody): Connection = call("POST", "/v1/connections/$id/reconsent", enc(ConnectBody.serializer(), b), Connection.serializer())!!
    suspend fun disconnect(id: String) { call<Unit>("DELETE", "/v1/connections/$id", null, null) }
    suspend fun putEntitlement(b: EntitlementBody): Entitlement = call("PUT", "/v1/entitlements/plus", enc(EntitlementBody.serializer(), b), Entitlement.serializer())!!
    suspend fun entitlement(): Entitlement = call("GET", "/v1/entitlements/plus", null, Entitlement.serializer()) ?: Entitlement()
}
