package app.penny.sync

import app.penny.core.event.Event
import app.penny.core.event.PennyJson
import app.penny.data.RoomEventStore
import app.penny.data.db.KvRow
import app.penny.data.db.PennyDao
import kotlinx.serialization.builtins.ListSerializer
import java.util.zip.Deflater
import java.util.zip.Inflater

data class SyncResult(val pushed: Int, val pulled: Int, val conflicts: Int = 0)

/** A synced batch this app cannot read yet; sync stops before it so an update can read it later. */
class SyncBlobException(val stream: String, cause: Throwable?) : Exception("Unreadable sync batch in $stream", cause)

/** Supplies the stream key for a stream id (profile or space), or null when this device has no access. */
fun interface KeyRing { fun key(stream: String): ByteArray? }

/**
 * SY-02/SY-03/SY-04: push local events in encrypted, compressed batches; pull other devices' batches after our cursor
 * and ingest them. Ingest skips ids it already has, so pushing and pulling are idempotent and order-independent.
 */
class SyncEngine(
    private val store: RoomEventStore,
    private val dao: PennyDao,
    private val relay: RelayClient,
    private val keys: KeyRing,
) {
    private val ser = ListSerializer(Event.serializer())

    suspend fun sync(stream: String): SyncResult {
        val key = keys.key(stream) ?: return SyncResult(0, 0)
        val pushed = push(stream, key)
        val pulled = pull(stream, key)
        return SyncResult(pushed, pulled)
    }

    private suspend fun push(stream: String, key: ByteArray): Int {
        var total = 0
        var after = dao.kv("sync.pushed.$stream")?.toLongOrNull() ?: 0
        while (true) {
            val batch = store.eventsAfter(stream, after, BATCH)
            if (batch.isEmpty()) break
            val plain = deflate(PennyJson.encodeToString(ser, batch).toByteArray())
            relay.push(stream, listOf(StreamCrypto.b64(StreamCrypto.seal(key, plain, stream))))
            after = batch.last().seq
            dao.putKv(KvRow("sync.pushed.$stream", after.toString()))
            total += batch.size
            if (batch.size < BATCH) break
        }
        return total
    }

    private suspend fun pull(stream: String, key: ByteArray): Int {
        var total = 0
        var cursor = dao.kv("sync.cursor.$stream")?.toLongOrNull() ?: 0
        while (true) {
            val blobs = relay.pull(stream, cursor)
            if (blobs.isEmpty()) break
            // Decode in order and stop at the first blob we cannot read (newer app version, key not yet right): the cursor
            // only moves past blobs that were ingested, so nothing is skipped for good; the next sync retries it.
            val events = mutableListOf<Event>()
            var lastGood: Long? = null
            var failure: Throwable? = null
            for (b in blobs) {
                val decoded = runCatching { PennyJson.decodeFromString(ser, String(inflate(StreamCrypto.open(key, StreamCrypto.unb64(b.blob), stream)))) }
                if (decoded.isFailure) { failure = decoded.exceptionOrNull(); break }
                events += decoded.getOrThrow()
                lastGood = b.cursor
            }
            // Only events for this stream are accepted (a blob cannot smuggle events into another book).
            total += store.ingest(events.filter { it.profileId == stream }).size
            if (lastGood != null) {
                cursor = lastGood
                dao.putKv(KvRow("sync.cursor.$stream", cursor.toString()))
            }
            if (failure != null) throw SyncBlobException(stream, failure)
            if (blobs.size < 200) break
        }
        dao.putKv(KvRow("sync.last.$stream", System.currentTimeMillis().toString()))
        return total
    }

    companion object {
        const val BATCH = 300

        fun deflate(b: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_SPEED).apply { setInput(b); finish() }
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            return out.toByteArray()
        }

        fun inflate(b: ByteArray): ByteArray {
            val i = Inflater().apply { setInput(b) }
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!i.finished()) { val n = i.inflate(buf); if (n == 0 && (i.needsInput() || i.needsDictionary())) break; out.write(buf, 0, n) }
            i.end()
            return out.toByteArray()
        }
    }
}
