package app.penny.data

import androidx.room.withTransaction
import app.penny.core.event.Entities
import app.penny.core.event.EntityState
import app.penny.core.event.Event
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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject

/** Event store on SQLCipher-encrypted Room. Appending and projecting happen in one database transaction. */
class RoomEventStore(private val db: PennyDatabase) : EventStore {
    private val dao = db.dao()
    private val counter = MutableStateFlow(0L)
    override val changes: Flow<Long> = counter

    override suspend fun append(events: List<Event>): List<Event> {
        val stored = db.withTransaction {
            events.map { e ->
                val seq = dao.insertEvent(e.toRow())
                val s = e.copy(seq = seq)
                project(s)
                s
            }
        }
        counter.value++
        return stored
    }

    private suspend fun project(e: Event) {
        val next = Projector.apply(dao.entity(e.entity, e.entityId)?.toState(), e)
        if (next == null) dao.deleteEntity(e.entity, e.entityId) else dao.upsertEntity(next.toRow())
        if (e.entity == Entities.TRANSACTION) {
            if (next == null) dao.deleteTransaction(e.entityId) else dao.upsertTransaction(next.toTransactionRow())
        }
    }

    /** Drops every materialised row and replays the log; proves state is reproducible from events (§6 invariant 6). */
    suspend fun rebuild() = db.withTransaction {
        dao.clearEntities()
        dao.clearTransactions()
        dao.allEvents().forEach { project(it.toEvent()) }
    }.also { counter.value++ }

    override suspend fun eventsFor(entity: String, entityId: String) = dao.eventsFor(entity, entityId).map { it.toEvent() }
    override suspend fun eventsInGroup(groupId: String) = dao.eventsInGroup(groupId).map { it.toEvent() }
    override suspend fun current(entity: String, entityId: String) = dao.entity(entity, entityId)?.toState()
    override suspend fun all(entity: String, profileId: String, includeDeleted: Boolean) =
        dao.entities(entity, profileId, includeDeleted).map { it.toState() }
}

private val vclockSerializer = MapSerializer(String.serializer(), Long.serializer())

internal fun Event.toRow() = EventRow(
    eventId = id, entity = entity, entityId = entityId, profileId = profileId, op = op.name,
    patch = patch.toString(), before = before?.toString(), actor = actor, device = device, ts = ts, groupId = groupId,
    reason = reason, vclock = PennyJson.encodeToString(vclockSerializer, vclock), ciphertextRef = ciphertextRef,
)

internal fun EventRow.toEvent() = Event(
    id = eventId, seq = seq, entity = entity, entityId = entityId, profileId = profileId, op = Op.valueOf(op),
    patch = parseObject(patch), before = before?.let(::parseObject), actor = actor, device = device, ts = ts,
    groupId = groupId, reason = reason, vclock = PennyJson.decodeFromString(vclockSerializer, vclock), ciphertextRef = ciphertextRef,
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
