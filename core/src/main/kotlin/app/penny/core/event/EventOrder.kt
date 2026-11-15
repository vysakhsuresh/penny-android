package app.penny.core.event

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * SY-02: a total order every device agrees on. Each event carries a Lamport clock in `vclock["l"]`; ties break on device
 * then event id. Replaying an entity's events in this order gives field-level last-writer-wins on every device.
 * Local-only events written before sync existed have no clock and fall back to their sequence number.
 */
object EventOrder {
    const val LAMPORT = "l"

    fun lamport(e: Event): Long = e.vclock[LAMPORT] ?: e.seq

    val comparator: Comparator<Event> = compareBy<Event>({ lamport(it) }, { it.device }, { it.id })

    fun stamp(e: Event, clock: Long): Event = e.copy(vclock = e.vclock + (LAMPORT to clock))

    /**
     * A conflict is an UPDATE whose [Event.before] values do not match the state it was applied to: two devices
     * changed the same field concurrently. Both events stay in the log (nothing is lost); the later one in [comparator]
     * wins, and history shows the conflict.
     */
    fun conflicts(events: List<Event>): List<Conflict> {
        val out = mutableListOf<Conflict>()
        var state: EntityState? = null
        for (e in events.sortedWith(comparator)) {
            if (e.op == Op.UPDATE && state != null && e.reason != Reasons.UNDO) {
                e.before?.forEach { (k, v) ->
                    val actual: JsonElement = state!!.data[k] ?: JsonNull
                    if (actual != v) out += Conflict(e.entity, e.entityId, k, actual, e.patch[k], e.id, state!!.lastActor)
                }
            }
            state = Projector.apply(state, e)
        }
        return out
    }
}

data class Conflict(
    val entity: String,
    val entityId: String,
    val field: String,
    /** Value from the other device that was overwritten (kept in history). */
    val overwritten: JsonElement?,
    val winner: JsonElement?,
    val eventId: String,
    val otherActor: String,
)
