package app.penny.core.event

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

val PennyJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    classDiscriminator = "type"
}

/** A field change shown in history: JSON values so the UI can render any field generically. */
data class FieldChange(val field: String, val before: JsonElement?, val after: JsonElement?)

data class Version(
    val version: Int,
    val op: Op,
    val actor: String,
    val ts: Long,
    val reason: String?,
    val changes: List<FieldChange>,
    val eventId: String,
)

data class Stored<T>(val value: T, val state: EntityState)

/**
 * Create, update every field, soft delete, restore, purge, undo, history and revert for one entity type,
 * all through the append-only log (CLAUDE.md rule 5, NN-3, TX-03, TX-05).
 */
open class EventSourcedRepository<T>(
    val entity: String,
    private val serializer: KSerializer<T>,
    protected val store: EventStore,
    protected val actor: () -> Actor,
    protected val clock: Clock = SystemClock,
    protected val ids: IdGenerator = UuidGenerator,
    /** Applied to every value before it is written (e.g. AC-07 routes a pass-through wallet's spend to its bank). */
    private val normalize: suspend (T) -> T = { it },
) {
    fun encode(value: T): JsonObject = PennyJson.encodeToJsonElement(serializer, value).jsonObject
    fun decode(data: JsonObject): T = PennyJson.decodeFromJsonElement(serializer, data)

    suspend fun get(id: String): Stored<T>? = store.current(entity, id)?.let { Stored(decode(it.data), it) }

    suspend fun list(profileId: String, includeDeleted: Boolean = false): List<Stored<T>> =
        store.all(entity, profileId, includeDeleted).map { Stored(decode(it.data), it) }

    suspend fun trash(profileId: String): List<Stored<T>> =
        store.all(entity, profileId, includeDeleted = true).filter { it.deletedAt != null }.map { Stored(decode(it.data), it) }

    /** Returns the group id; pass it to [undo] to reverse the action. */
    suspend fun create(id: String, profileId: String, value: T, groupId: String = ids.newId(), reason: String? = null): String {
        store.append(listOf(event(id, profileId, Op.CREATE, encode(normalize(value)), null, groupId, reason)))
        return groupId
    }

    suspend fun createAll(profileId: String, values: List<Pair<String, T>>, reason: String? = null, groupId: String = ids.newId()): String {
        val g = groupId
        val normalized = values.map { (id, v) -> id to normalize(v) }
        store.append(normalized.map { (id, v) -> event(id, profileId, Op.CREATE, encode(v), null, g, reason) })
        return g
    }

    /** Writes only the fields that changed. Returns null when nothing changed (no event is written). */
    suspend fun update(id: String, value: T, reason: String? = null, groupId: String = ids.newId()): String? {
        val cur = store.current(entity, id) ?: error("No $entity $id")
        val (patch, before) = Projector.diff(cur.data, encode(normalize(value)))
        if (patch.isEmpty()) return null
        store.append(listOf(event(id, cur.profileId, Op.UPDATE, patch, before, groupId, reason)))
        return groupId
    }

    /** Bulk update in one append (TX-04): one undo group, one database transaction. */
    suspend fun updateMany(values: List<Pair<String, T>>, groupId: String = ids.newId(), reason: String? = null): String? {
        val events = values.mapNotNull { (id, v) ->
            val cur = store.current(entity, id) ?: return@mapNotNull null
            val (patch, before) = Projector.diff(cur.data, encode(normalize(v)))
            if (patch.isEmpty()) null else event(id, cur.profileId, Op.UPDATE, patch, before, groupId, reason)
        }
        if (events.isEmpty()) return null
        store.append(events)
        return groupId
    }

    suspend fun deleteMany(idList: List<String>, groupId: String = ids.newId()): String {
        val events = idList.mapNotNull { id -> store.current(entity, id)?.let { event(id, it.profileId, Op.DELETE, JsonObject(emptyMap()), null, groupId, null) } }
        if (events.isNotEmpty()) store.append(events)
        return groupId
    }

    /** Creates or updates [id] (used for deterministic ids such as allocations and occurrences). */
    suspend fun upsert(id: String, profileId: String, value: T, groupId: String = ids.newId(), reason: String? = null): String? {
        val cur = store.current(entity, id)
        return when {
            cur == null -> create(id, profileId, value, groupId, reason)
            cur.deletedAt != null -> { lifecycle(id, Op.RESTORE, groupId, reason); update(id, value, reason, groupId) ?: groupId }
            else -> update(id, value, reason, groupId)
        }
    }

    suspend fun delete(id: String, groupId: String = ids.newId()): String = lifecycle(id, Op.DELETE, groupId)
    suspend fun restore(id: String, groupId: String = ids.newId()): String = lifecycle(id, Op.RESTORE, groupId)
    suspend fun purge(id: String, groupId: String = ids.newId()): String = lifecycle(id, Op.PURGE, groupId)

    fun newGroup(): String = ids.newId()

    private suspend fun lifecycle(id: String, op: Op, groupId: String = ids.newId(), reason: String? = null): String {
        val cur = store.current(entity, id) ?: error("No $entity $id")
        store.append(listOf(event(id, cur.profileId, op, JsonObject(emptyMap()), null, groupId, reason)))
        return groupId
    }

    /** Appends compensating events for every event in [groupId], newest first. Never edits the log. */
    suspend fun undo(groupId: String): String? {
        val events = store.eventsInGroup(groupId).filter { it.reason != Reasons.UNDO }
        if (events.isEmpty()) return null
        val g = ids.newId()
        val compensating = events.sortedWith(EventOrder.comparator.reversed()).mapNotNull { e ->
            when (e.op) {
                Op.CREATE -> event(e.entityId, e.profileId, Op.PURGE, JsonObject(emptyMap()), null, g, Reasons.UNDO)
                Op.UPDATE -> event(e.entityId, e.profileId, Op.UPDATE, e.before ?: JsonObject(emptyMap()), e.patch, g, Reasons.UNDO)
                Op.DELETE -> event(e.entityId, e.profileId, Op.RESTORE, JsonObject(emptyMap()), null, g, Reasons.UNDO)
                Op.RESTORE -> event(e.entityId, e.profileId, Op.DELETE, JsonObject(emptyMap()), null, g, Reasons.UNDO)
                Op.PURGE -> null
            }?.copy(entity = e.entity)
        }
        store.append(compensating)
        return g
    }

    suspend fun history(id: String): List<Version> {
        var state: EntityState? = null
        return store.eventsFor(entity, id).sortedWith(EventOrder.comparator).map { e ->
            val prev = state
            state = Projector.apply(state, e)
            val changes = when (e.op) {
                Op.CREATE -> e.patch.map { (k, v) -> FieldChange(k, null, v) }
                Op.UPDATE -> e.patch.map { (k, v) -> FieldChange(k, e.before?.get(k) ?: prev?.data?.get(k), v) }
                else -> emptyList()
            }
            Version(state?.version ?: (prev?.version ?: 0) + 1, e.op, e.actor, e.ts, e.reason, changes, e.id)
        }
    }

    /** Data as it was right after [version]. */
    suspend fun stateAt(id: String, version: Int): EntityState? {
        var state: EntityState? = null
        for (e in store.eventsFor(entity, id).sortedWith(EventOrder.comparator)) {
            state = Projector.apply(state, e)
            if (state != null && state.version >= version) return state
        }
        return state
    }

    /** Reverts data fields to [version] with one compensating UPDATE event. Deletion status is left as it is. */
    suspend fun revertTo(id: String, version: Int): String? {
        val target = stateAt(id, version) ?: return null
        val cur = store.current(entity, id) ?: return null
        val (patch, before) = Projector.diff(cur.data, target.data)
        if (patch.isEmpty()) return null
        val g = ids.newId()
        store.append(listOf(event(id, cur.profileId, Op.UPDATE, patch, before, g, Reasons.REVERT_PREFIX + version)))
        return g
    }

    protected fun event(
        entityId: String,
        profileId: String,
        op: Op,
        patch: JsonObject,
        before: JsonObject?,
        groupId: String,
        reason: String?,
    ): Event {
        val a = actor()
        return Event(
            id = ids.newId(),
            entity = entity,
            entityId = entityId,
            profileId = profileId,
            op = op,
            patch = patch,
            before = before,
            actor = a.id,
            device = a.device,
            ts = clock.nowMillis(),
            groupId = groupId,
            reason = reason,
        )
    }
}
