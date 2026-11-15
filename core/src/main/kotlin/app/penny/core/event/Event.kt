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
    const val TAG = "tag"
    const val PAYEE = "payee"
    const val RULE = "rule"
    const val ENVELOPE = "envelope"
    const val ALLOCATION = "allocation"
    const val BUDGET_MOVE = "budget_move"
    const val GOAL = "goal"
    const val GOAL_ENTRY = "goal_entry"
    const val RECURRING = "recurring"
    const val OCCURRENCE = "occurrence"
    const val TEMPLATE = "template"
    const val SAVED_SEARCH = "saved_search"
    const val ATTACHMENT = "attachment"
    const val VALUATION = "valuation"
    const val FX_RATE = "fx_rate"
    const val PERSON = "person"
    const val LENDING = "lending"
    const val GROUP = "group"
    const val GROUP_EXPENSE = "group_expense"
    const val SETTLEMENT = "settlement"
    const val LOAN = "loan"
    const val REMITTANCE = "remittance"
    const val CIRCLE = "circle"
    const val CIRCLE_CONTRIBUTION = "circle_contribution"
    const val SPACE = "space"
    const val MEMBER = "member"
    const val INVITATION = "invitation"
    const val DEVICE = "device"
    const val SYNC_LINK = "sync_link"
    const val ALERT = "alert"
    const val WATCHLIST = "watchlist"
    const val ADVISOR_LINK = "advisor_link"
    const val CAPTURE = "capture"
    const val CAPTURE_SOURCE = "capture_source"

    /** Objects shown in the global Trash (HS-02). */
    val TRASHABLE = listOf(
        TRANSACTION, ACCOUNT, CATEGORY, TAG, PAYEE, RULE, ENVELOPE, GOAL, RECURRING, TEMPLATE, SAVED_SEARCH, PERSON,
        LENDING, GROUP, GROUP_EXPENSE, LOAN, REMITTANCE, CIRCLE, ATTACHMENT, MEMBER, WATCHLIST,
    )
}

/** Event reasons that history renders specially. */
object Reasons {
    const val UNDO = "undo"
    const val REVERT_PREFIX = "revert:v"
    const val SEED = "seed"
}
