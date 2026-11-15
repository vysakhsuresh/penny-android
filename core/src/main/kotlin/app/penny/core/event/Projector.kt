package app.penny.core.event

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Folds events into entity state. The only code that turns the log into materialised rows. */
object Projector {

    fun apply(state: EntityState?, e: Event): EntityState? = when (e.op) {
        Op.CREATE -> EntityState(
            entity = e.entity,
            id = e.entityId,
            profileId = e.profileId,
            data = withoutNulls(e.patch),
            version = (state?.version ?: 0) + 1,
            createdAt = state?.createdAt ?: e.ts,
            updatedAt = e.ts,
            deletedAt = null,
            lastActor = e.actor,
        )
        Op.UPDATE -> state?.copy(data = merge(state.data, e.patch), version = state.version + 1, updatedAt = e.ts, lastActor = e.actor)
        Op.DELETE -> state?.copy(deletedAt = e.ts, version = state.version + 1, updatedAt = e.ts, lastActor = e.actor)
        Op.RESTORE -> state?.copy(deletedAt = null, version = state.version + 1, updatedAt = e.ts, lastActor = e.actor)
        Op.PURGE -> null
    }

    /** Rebuilds every entity from the log; used for integrity checks and for rebuilding materialised tables. */
    fun rebuild(events: Iterable<Event>): Map<Pair<String, String>, EntityState> {
        val out = linkedMapOf<Pair<String, String>, EntityState>()
        events.sortedBy { it.seq }.forEach { e ->
            val key = e.entity to e.entityId
            val next = apply(out[key], e)
            if (next == null) out.remove(key) else out[key] = next
        }
        return out
    }

    fun merge(data: JsonObject, patch: JsonObject): JsonObject {
        val m = data.toMutableMap()
        patch.forEach { (k, v) -> if (v is JsonNull) m.remove(k) else m[k] = v }
        return JsonObject(m)
    }

    private fun withoutNulls(o: JsonObject) = JsonObject(o.filterValues { it !is JsonNull })

    /** Patch that turns [from] into [to]: changed or added keys with new values, removed keys as JSON null. */
    fun diff(from: JsonObject, to: JsonObject): Pair<JsonObject, JsonObject> {
        val patch = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        val before = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        (from.keys + to.keys).forEach { k ->
            val a = from[k] ?: JsonNull
            val b = to[k] ?: JsonNull
            if (a != b) {
                patch[k] = b
                before[k] = a
            }
        }
        return JsonObject(patch) to JsonObject(before)
    }
}
