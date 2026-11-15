package app.penny.core.event

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistence port for the event log. Implementations append events and update materialised state atomically:
 * either both happen or neither does.
 */
interface EventStore {
    /** Appends [events] in order, assigns sequence numbers and updates projections. Returns the stored events. */
    suspend fun append(events: List<Event>): List<Event>
    suspend fun eventsFor(entity: String, entityId: String): List<Event>
    suspend fun eventsInGroup(groupId: String): List<Event>
    suspend fun current(entity: String, entityId: String): EntityState?
    suspend fun all(entity: String, profileId: String, includeDeleted: Boolean = false): List<EntityState>
    /** Emits a counter that increases on every append; observers re-query on change. */
    val changes: Flow<Long>
}

/** Thread-safe in-memory store for tests and previews. */
class InMemoryEventStore : EventStore {
    private val mutex = Mutex()
    private val log = mutableListOf<Event>()
    private val states = linkedMapOf<Pair<String, String>, EntityState>()
    private val counter = MutableStateFlow(0L)
    override val changes: Flow<Long> = counter.asStateFlow()

    override suspend fun append(events: List<Event>): List<Event> = mutex.withLock {
        val stored = events.map { e ->
            val s = e.copy(seq = (log.lastOrNull()?.seq ?: 0) + 1)
            log += s
            val key = s.entity to s.entityId
            val next = Projector.apply(states[key], s)
            if (next == null) states.remove(key) else states[key] = next
            s
        }
        counter.value++
        stored
    }

    override suspend fun eventsFor(entity: String, entityId: String) =
        mutex.withLock { log.filter { it.entity == entity && it.entityId == entityId } }

    override suspend fun eventsInGroup(groupId: String) = mutex.withLock { log.filter { it.groupId == groupId } }

    override suspend fun current(entity: String, entityId: String) = mutex.withLock { states[entity to entityId] }

    override suspend fun all(entity: String, profileId: String, includeDeleted: Boolean) = mutex.withLock {
        states.values.filter { it.entity == entity && it.profileId == profileId && (includeDeleted || it.deletedAt == null) }
    }

    suspend fun allEvents(): List<Event> = mutex.withLock { log.toList() }
}
