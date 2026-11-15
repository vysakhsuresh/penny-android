package app.penny.core.event

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class Op { CREATE, UPDATE, DELETE, RESTORE, PURGE }

/**
 * One append-only row in the event log (SPEC §6 `Event`, SY-01). Every mutation in Penny is one of these;
 * the materialised tables are projections of the log and can be rebuilt from it at any time.
 *
 * [patch] holds the new values of the fields that changed (JSON null clears a field). [before] holds the previous
 * values of the same fields, so history can show diffs and undo can compensate without re-reading the past.
 */
@Serializable
data class Event(
    val id: String,
    val seq: Long = 0,
    val entity: String,
    val entityId: String,
    val profileId: String,
    val op: Op,
    val patch: JsonObject = JsonObject(emptyMap()),
    val before: JsonObject? = null,
    val actor: String,
    val device: String,
    val ts: Long,
    val groupId: String? = null,
    val reason: String? = null,
    val vclock: Map<String, Long> = emptyMap(),
    val ciphertextRef: String? = null,
)

/** Materialised state of one entity: its data fields plus lifecycle metadata derived from the log. */
data class EntityState(
    val entity: String,
    val id: String,
    val profileId: String,
    val data: JsonObject,
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val lastActor: String,
)

object Entities {
    const val PROFILE = "profile"
    const val ACCOUNT = "account"
    const val CATEGORY = "category"
    const val TRANSACTION = "transaction"
}

/** Event reasons that history renders specially. */
object Reasons {
    const val UNDO = "undo"
    const val REVERT_PREFIX = "revert:v"
    const val SEED = "seed"
}
