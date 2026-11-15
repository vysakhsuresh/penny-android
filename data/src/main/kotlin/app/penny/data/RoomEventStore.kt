package app.penny.data

import androidx.room.withTransaction
import app.penny.core.event.Entities
import app.penny.core.event.EntityState
import app.penny.core.event.Event
import app.penny.core.event.EventOrder
import app.penny.core.event.EventStore
import app.penny.core.event.Op
import app.penny.core.event.PennyJson
import app.penny.core.event.Projector
import app.penny.core.model.Transaction
import app.penny.data.db.EntityRow
import app.penny.data.db.EventRow
import app.penny.data.db.PennyDatabase
import app.penny.data.db.TransactionRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject

/**
 * Event store on SQLCipher-encrypted Room. Appending and projecting happen in one database transaction.
 * Local appends take the next Lamport clock; remote events are ingested and their entities re-projected in
 * [EventOrder], so all devices converge (SY-02).
 */
class RoomEventStore(private val db: PennyDatabase) : EventStore {
    private val dao = db.dao()
    private val counter = MutableStateFlow(0L)
    private val clockLock = Mutex()
    private var lamport: Long? = null
    override val changes: Flow<Long> = counter

    /** Caller holds [clockLock]. */
    private suspend fun nextClockLocked(): Long {
        val next = (lamport ?: dao.maxLamport()) + 1
        lamport = next
        return next
    }

    /**
     * Stamping, inserting and projecting happen under one lock and one transaction, so commit order always equals clock
     * order on this device and every local event is projected on top of a state with lower clocks.
     */
    override suspend fun append(events: List<Event>): List<Event> {
        val stored = clockLock.withLock {
            db.withTransaction {
                events.map { raw ->
                    val e = EventOrder.stamp(raw, nextClockLocked())
                    val seq = dao.insertEvent(e.toRow())
                    val s = e.copy(seq = seq)
                    project(s)
                    s
                }
            }
        }
        counter.value++
        return stored
    }

    /**
     * Ingests events from another device (or the user's own restored export when [remote] is false, so they are pushed
     * when sync is turned on). Known ids are skipped; affected entities are re-projected in clock order.
     */
    override suspend fun ingest(events: List<Event>, remote: Boolean): List<Event> {
        if (events.isEmpty()) return emptyList()
        val unique = events.distinctBy { it.id }
        val stored = clockLock.withLock {
            val known = unique.map { it.id }.chunked(500).flatMap { dao.knownIds(it) }.toSet()
            val fresh = unique.filter { it.id !in known }
            if (fresh.isEmpty()) return emptyList()
            val out = db.withTransaction {
                val rows = fresh.sortedWith(EventOrder.comparator).map { e ->
                    val seq = dao.insertEvent(e.toRow(remote = remote))
                    e.copy(seq = seq)
                }
                rows.map { it.entity to it.entityId }.toSet().forEach { (entity, id) -> reproject(entity, id) }
                rows
            }
            // Raised before the lock is released: the next local edit always gets a higher clock than what it overwrites.
            lamport = maxOf(lamport ?: dao.maxLamport(), fresh.maxOf { EventOrder.lamport(it) })
            out
        }
        counter.value++
        return stored
    }

    private suspend fun reproject(entity: String, id: String) {
        var state: EntityState? = null
        dao.eventsFor(entity, id).map { it.toEvent() }.sortedWith(EventOrder.comparator).forEach { state = Projector.apply(state, it) }
        write(entity, id, state)
    }

    private suspend fun project(e: Event) {
        write(e.entity, e.entityId, Projector.apply(dao.entity(e.entity, e.entityId)?.toState(), e))
    }

    private suspend fun write(entity: String, id: String, next: EntityState?) {
        if (next == null) dao.deleteEntity(entity, id) else dao.upsertEntity(next.toRow())
        if (entity == Entities.TRANSACTION) {
            if (next == null) dao.deleteTransaction(id) else dao.upsertTransaction(next.toTransactionRow())
        }
    }

    /** Drops every materialised row and replays the log; proves state is reproducible from events (§6 invariant 6). */
    suspend fun rebuild() = db.withTransaction {
        dao.clearEntities()
        dao.clearTransactions()
        val byKey = dao.allEvents().map { it.toEvent() }.groupBy { it.entity to it.entityId }
        byKey.forEach { (k, list) ->
            var state: EntityState? = null
            list.sortedWith(EventOrder.comparator).forEach { state = Projector.apply(state, it) }
            write(k.first, k.second, state)
        }
    }.also { counter.value++ }

    override suspend fun eventsAfter(profileId: String, seq: Long, limit: Int) = dao.localEventsAfter(profileId, seq, limit).map { it.toEvent() }
    override suspend fun eventsFor(entity: String, entityId: String) = dao.eventsFor(entity, entityId).map { it.toEvent() }
    override suspend fun eventsInGroup(groupId: String) = dao.eventsInGroup(groupId).map { it.toEvent() }
    override suspend fun current(entity: String, entityId: String) = dao.entity(entity, entityId)?.toState()
    override suspend fun all(entity: String, profileId: String, includeDeleted: Boolean) =
        dao.entities(entity, profileId, includeDeleted).map { it.toState() }

    suspend fun allEventsFor(profileId: String): List<Event> = dao.eventsForProfile(profileId).map { it.toEvent() }

    /**
     * SE-09 / ST-09 erasure: the one deliberate exception to append-only. A deleted profile's events and projections
     * are removed from this device so the data is really gone (decision 2026-11-15 in DECISIONS.md).
     */
    suspend fun eraseProfile(profileId: String) {
        db.withTransaction {
            dao.eraseProfileEvents(profileId)
            dao.eraseProfileEntities(profileId)
            dao.eraseProfileTransactions(profileId)
        }
        counter.value++
    }

    suspend fun eventPage(profileId: String, limit: Int, offset: Int): List<Event> = dao.profileEventsPage(profileId, limit, offset).map { it.toEvent() }
}

private val vclockSerializer = MapSerializer(String.serializer(), Long.serializer())

internal fun Event.toRow(remote: Boolean = false) = EventRow(
    eventId = id, entity = entity, entityId = entityId, profileId = profileId, op = op.name,
    patch = patch.toString(), before = before?.toString(), actor = actor, device = device, ts = ts, groupId = groupId,
    reason = reason, vclock = PennyJson.encodeToString(vclockSerializer, vclock), ciphertextRef = ciphertextRef,
    lamport = EventOrder.lamport(this), remote = remote,
)

fun EventRow.toEvent() = Event(
    id = eventId, seq = seq, entity = entity, entityId = entityId, profileId = profileId, op = Op.valueOf(op),
    patch = parseObject(patch), before = before?.let(::parseObject), actor = actor, device = device, ts = ts,
    groupId = groupId, reason = reason,
    vclock = PennyJson.decodeFromString(vclockSerializer, vclock).let { if (EventOrder.LAMPORT in it || lamport == 0L) it else it + (EventOrder.LAMPORT to lamport) },
    ciphertextRef = ciphertextRef,
)

internal fun parseObject(s: String): JsonObject = PennyJson.parseToJsonElement(s) as JsonObject

fun EntityRow.toState() = EntityState(entity, id, profileId, parseObject(json), version, createdAt, updatedAt, deletedAt, lastActor)

internal fun EntityState.toRow() = EntityRow(entity, id, profileId, data.toString(), version, createdAt, updatedAt, deletedAt, lastActor)

internal fun EntityState.toTransactionRow(): TransactionRow {
    val t = PennyJson.decodeFromJsonElement(Transaction.serializer(), data)
    return TransactionRow(
        id = id, profileId = profileId, type = t.type.name, amountMinor = t.amountMinor, currency = t.currency,
        accountId = t.accountId, toAccountId = t.toAccountId, categoryId = t.categoryId, payee = t.payee, note = t.note,
        dateEpochDay = t.date.toEpochDay(), timeMinutes = t.time?.let { it.hour * 60 + it.minute }, json = data.toString(),
        version = version, updatedAt = updatedAt, deletedAt = deletedAt,
    )
}
