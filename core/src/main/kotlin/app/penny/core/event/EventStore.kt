package app.penny.core.event

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistence port for the event log. Implementations append events and update materialised state atomically:
 * either both happen or neither does. Local appends get the next Lamport clock (SY-02).
 */
interface EventStore {
    /** Appends [events] in order, assigns sequence numbers and Lamport clocks, updates projections. */
    suspend fun append(events: List<Event>): List<Event>
    suspend fun eventsFor(entity: String, entityId: String): List<Event>
    suspend fun eventsInGroup(groupId: String): List<Event>
    suspend fun current(entity: String, entityId: String): EntityState?
    suspend fun all(entity: String, profileId: String, includeDeleted: Boolean = false): List<EntityState>

    /**
     * SY-02: merges events from another device. Already-known ids are skipped; affected entities are re-projected in
     * [EventOrder] so every device converges. Returns the events that were new.
     */
    suspend fun ingest(events: List<Event>, remote: Boolean = true): List<Event>

    /** Local events after [seq] for one stream (profile or space id), oldest first, for pushing. */
    suspend fun eventsAfter(profileId: String, seq: Long, limit: Int = 500): List<Event>

    /** Emits a counter that increases on every append; observers re-query on change. */
    val changes: Flow<Long>
}

/** Thread-safe in-memory store for tests and previews. */
class InMemoryEventStore : EventStore {
    private val mutex = Mutex()
    private val log = mutableListOf<Event>()
    private val states = linkedMapOf<Pair<String, String>, EntityState>()
    private val counter = MutableStateFlow(0L)
    private var lamport = 0L
    override val changes: Flow<Long> = counter.asStateFlow()

    override suspend fun append(events: List<Event>): List<Event> = mutex.withLock {
        val stored = events.map { e ->
            lamport++
            val s = EventOrder.stamp(e.copy(seq = (log.lastOrNull()?.seq ?: 0) + 1), lamport)
            log += s
            val key = s.entity to s.entityId
            val next = Projector.apply(states[key], s)
            if (next == null) states.remove(key) else states[key] = next
            s
        }
        counter.value++
        stored
    }

    override suspend fun ingest(events: List<Event>, remote: Boolean): List<Event> = mutex.withLock {
        val known = log.map { it.id }.toSet()
        val fresh = events.filter { it.id !in known }.distinctBy { it.id }
        if (fresh.isEmpty()) return@withLock emptyList()
        val stored = fresh.map { e ->
            lamport = maxOf(lamport, EventOrder.lamport(e))
            e.copy(seq = (log.lastOrNull()?.seq ?: 0) + 1).also { log += it }
        }
        stored.map { it.entity to it.entityId }.toSet().forEach { key ->
            var st: EntityState? = null
            log.filter { it.entity == key.first && it.entityId == key.second }.sortedWith(EventOrder.comparator).forEach { st = Projector.apply(st, it) }
            if (st == null) states.remove(key) else states[key] = st!!
        }
        counter.value++
        stored
    }

    override suspend fun eventsAfter(profileId: String, seq: Long, limit: Int) =
        mutex.withLock { log.filter { it.profileId == profileId && it.seq > seq }.take(limit) }

    override suspend fun eventsFor(entity: String, entityId: String) =
        mutex.withLock { log.filter { it.entity == entity && it.entityId == entityId } }

    override suspend fun eventsInGroup(groupId: String) = mutex.withLock { log.filter { it.groupId == groupId } }

    override suspend fun current(entity: String, entityId: String) = mutex.withLock { states[entity to entityId] }

    override suspend fun all(entity: String, profileId: String, includeDeleted: Boolean) = mutex.withLock {
        states.values.filter { it.entity == entity && it.profileId == profileId && (includeDeleted || it.deletedAt == null) }
    }

    suspend fun allEvents(): List<Event> = mutex.withLock { log.toList() }
}
