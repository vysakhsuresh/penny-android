package app.penny.core.model

import app.penny.core.period.LocalDateIsoSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** US-06 roles, highest first. */
@Serializable
enum class Role { OWNER, ADMIN, EDITOR, CONTRIBUTOR, VIEWER, NONE }

/** US-05 a shared book. The space's own entities carry the space id as their profileId. */
@Serializable
data class Space(
    val name: String,
    val ownerAccountId: String,
    val baseCurrency: String,
    val cycle: app.penny.core.period.CycleRule,
    val timezone: String,
    val memberLimit: Int = 10,
    val icon: String = "",
)

/** Per-object override of a member's role: on an account or an envelope (US-06). */
@Serializable
data class RoleOverride(val objectEntity: String, val objectId: String, val role: Role)

@Serializable
data class Member(
    val spaceId: String,
    val accountId: String,
    val displayName: String,
    val role: Role,
    val overrides: List<RoleOverride> = emptyList(),
    /** US-08: true after leaving or removal; past transactions keep the "ex-member" label. */
    val left: Boolean = false,
    /** X25519 public key (base64) used to wrap the space key for this member (SE-02). */
    val publicKey: String = "",
)

@Serializable
enum class InviteChannel { LINK, EMAIL, PHONE, QR }

/** US-12: expires in 7 days, revocable, shows what will be shared. Token is random and single-use. */
@Serializable
data class Invitation(
    val spaceId: String,
    val role: Role,
    val channel: InviteChannel,
    val address: String = "",
    val token: String,
    val expiresAtMillis: Long,
    val revoked: Boolean = false,
    val acceptedBy: String? = null,
    val sharedSummary: String = "",
)

/** SE-06: a signed-in device. */
@Serializable
data class Device(
    val name: String,
    val platform: String,
    val lastActiveMillis: Long,
    val signedOut: Boolean = false,
    val publicKey: String = "",
)

@Serializable
enum class SyncStatus { ACTIVE, NEEDS_CONSENT, BROKEN, DISCONNECTED }

/** CP-10 / AC-09: bank connection. Tokens live only in the connector service (SE-08). */
@Serializable
data class SyncLink(
    val provider: String,
    val institution: String,
    val accountIds: List<String>,
    val remoteAccountIds: List<String> = emptyList(),
    @Serializable(with = LocalDateIsoSerializer::class) val consentExpiry: LocalDate? = null,
    val lastFetchMillis: Long? = null,
    val status: SyncStatus = SyncStatus.ACTIVE,
    val connectionRef: String = "",
)

@Serializable
enum class AlertKind {
    ENVELOPE_HALF, ENVELOPE_NEAR, ENVELOPE_AT, ENVELOPE_OVER, PACE, SAFE_LOW, BILL_DUE, LARGE_TX, NEW_RECURRING,
    PRICE_UP, UNUSUAL_MERCHANT, CONSENT_EXPIRING, SYNC_BROKEN, FORECAST_SHORT, WATCHLIST, GOAL_BEHIND, CIRCLE_DUE,
}

@Serializable
enum class AlertState { NEW, SEEN, DISMISSED, ACTED }

/** AL-*: a fired alert with what it refers to; the key de-duplicates firings per object and period. */
@Serializable
data class AlertRecord(
    val kind: AlertKind,
    val key: String,
    val objectEntity: String? = null,
    val objectId: String? = null,
    val title: String,
    val body: String,
    val firedAtMillis: Long,
    val deliveredVia: List<String> = emptyList(),
    val state: AlertState = AlertState.NEW,
    val amountMinor: Long? = null,
)

@Serializable
enum class WatchTarget { PAYEE, TAG, CATEGORY }

/** AL-05. */
@Serializable
data class Watchlist(
    val target: WatchTarget,
    val value: String,
    val limitMinor: Long,
    val currency: String,
    val muted: Boolean = false,
)

/** US-14: read-only report link; the token is shown once. */
@Serializable
data class AdvisorLink(
    val label: String,
    val reportIds: List<String>,
    val expiresAtMillis: Long,
    val token: String,
    val includeAttachments: Boolean = false,
    val revoked: Boolean = false,
)

/** CP-04 review queue item for captured transactions. */
@Serializable
data class CaptureItem(
    val source: String,
    val sourceDetail: String = "",
    val rawText: String,
    val proposed: Transaction,
    val confidence: Int,
    val state: CaptureState = CaptureState.PENDING,
    val duplicateOf: String? = null,
)

@Serializable
enum class CaptureState { PENDING, ACCEPTED, IGNORED }

/** CP-04 per-source learning: accepts in a row and whether auto-post is enabled. */
@Serializable
data class CaptureSource(
    val source: String,
    val acceptsInRow: Int = 0,
    val autoPost: Boolean = false,
    val alwaysIgnore: Boolean = false,
    val ignoredPayees: List<String> = emptyList(),
)
