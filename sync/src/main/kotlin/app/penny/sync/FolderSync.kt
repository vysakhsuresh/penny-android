package app.penny.sync

import app.penny.core.event.Event
import app.penny.core.event.EventStore
import app.penny.core.event.PennyJson
import kotlinx.serialization.builtins.ListSerializer
import java.security.SecureRandom

/**
 * A folder the user chose (kept in step between devices by any free folder-sync tool: Syncthing, a Drive or OneDrive
 * sync app, a USB copy). Paths use "/" and are relative to that folder.
 */
interface SyncFolder {
    /** File names directly inside [dir]; empty when it does not exist. */
    suspend fun list(dir: String): List<String>
    suspend fun read(path: String): ByteArray?
    /** Creates or replaces the file, creating parent folders as needed. */
    suspend fun write(path: String, bytes: ByteArray)
}

/** Small persistent key/value store for cursors (the app passes its database kv table). */
interface SyncKv {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
}

/**
 * Sync with no server and no cost (decision 53): every device writes its own changes as encrypted batches into a shared
 * folder and reads the batches other devices wrote. Nothing is readable without the recovery phrase.
 *
 * Layout:
 * - `penny.salt` (16 random bytes) and `penny.check` (a known value sealed with the master key, to test a phrase);
 * - `keys/<stream>.key`: the stream key wrapped by the master key;
 * - `streams/<stream>/<device>_<seq>.pnb`: deflated JSON events sealed with the stream key, `seq` = last local seq.
 *
 * Each device only ever writes files with its own device id, so two devices never write the same file and a folder
 * sync tool has nothing to merge. Reading is idempotent: ingest skips event ids it already has.
 */
class FolderSync(
    private val store: EventStore,
    private val kv: SyncKv,
    private val folder: SyncFolder,
    private val deviceId: String,
    private val phraseToKey: (phrase: String, salt: ByteArray) -> ByteArray = StreamCrypto::masterKey,
    /** Cursor namespace: each folder keeps its own, so the same book in two folders is sent to each in full. */
    private val kvPrefix: String = "fsync",
) {
    private val ser = ListSerializer(Event.serializer())

    /** Streams (books) the folder holds keys for; a shared book's folder holds exactly one. */
    suspend fun streamIds(): List<String> = folder.list(KEYS).filter { it.endsWith(".key") }.map { it.removeSuffix(".key") }

    /** True when the folder already holds a Penny sync set (another device set it up). */
    suspend fun isSetUp(): Boolean = folder.read(SALT) != null

    /** First device: writes a new salt and check for [phrase]; returns the master key. */
    suspend fun create(phrase: String): ByteArray {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val master = phraseToKey(phrase, salt)
        folder.write(SALT, salt)
        folder.write(CHECK, StreamCrypto.seal(master, CHECK_VALUE, "check"))
        return master
    }

    /** Another device: returns the master key when [phrase] opens this folder, else null. */
    suspend fun unlock(phrase: String): ByteArray? {
        val salt = folder.read(SALT) ?: return null
        val check = folder.read(CHECK) ?: return null
        val master = phraseToKey(phrase, salt)
        return master.takeIf { runCatching { StreamCrypto.open(master, check, "check").contentEquals(CHECK_VALUE) }.getOrDefault(false) }
    }

    /**
     * Pushes this device's events for [localStreams] and pulls every stream in the folder. One stream or one unreadable
     * file never stops the rest; the first problem is returned in [FolderSyncResult.error] and retried next time.
     */
    suspend fun sync(master: ByteArray, localStreams: List<String>, skip: Set<String> = emptySet()): FolderSyncResult {
        var pushed = 0; var pulled = 0; var error: Throwable? = null
        // [skip]: books deleted on this phone; the folder still has them for other devices, but they must not come back here.
        val keys = streamKeys(master, localStreams.filter { it !in skip }).filterKeys { it !in skip }
        for ((stream, key) in keys) {
            runCatching {
                if (stream in localStreams) pushed += push(stream, key)
                pulled += pull(stream, key)
            }.onFailure { if (error == null) error = it }
        }
        return FolderSyncResult(pushed, pulled, keys.keys.toList(), error)
    }

    /** Opens every stream key in the folder and adds a new one for each local stream that has none yet. */
    private suspend fun streamKeys(master: ByteArray, localStreams: List<String>): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        folder.list(KEYS).filter { it.endsWith(".key") }.forEach { name ->
            val stream = name.removeSuffix(".key")
            val wrapped = folder.read("$KEYS/$name") ?: return@forEach
            runCatching { StreamCrypto.unwrap(master, wrapped, stream) }.getOrNull()?.let { out[stream] = it }
        }
        localStreams.filter { it !in out }.forEach { s ->
            val key = StreamCrypto.newKey()
            folder.write("$KEYS/$s.key", StreamCrypto.wrap(master, key, s))
            out[s] = key
        }
        return out
    }

    private suspend fun push(stream: String, key: ByteArray): Int {
        var total = 0
        var after = kv.get("$kvPrefix.pushed.$stream")?.toLongOrNull() ?: 0
        while (true) {
            val batch = store.eventsAfter(stream, after, BATCH)
            if (batch.isEmpty()) break
            val last = batch.last().seq
            val plain = SyncEngine.deflate(PennyJson.encodeToString(ser, batch).toByteArray())
            folder.write("$STREAMS/$stream/${fileName(deviceId, last)}", StreamCrypto.seal(key, plain, "batch:$stream"))
            after = last
            kv.put("$kvPrefix.pushed.$stream", after.toString())
            total += batch.size
            if (batch.size < BATCH) break
        }
        return total
    }

    private suspend fun pull(stream: String, key: ByteArray): Int {
        var total = 0
        var failure: Throwable? = null
        // This device's own files are read too: ingest skips events already here, and after a book was deleted and joined
        // again on this phone they are the only copy of its history.
        val byDevice = folder.list("$STREAMS/$stream").mapNotNull(::parse).groupBy { it.device }
        for ((device, files) in byDevice) {
            var cursor = kv.get("$kvPrefix.cursor.$stream.$device")?.toLongOrNull() ?: 0
            for (f in files.filter { it.seq > cursor }.sortedBy { it.seq }) {
                // A file still being copied in by the sync tool fails to open: stop at it for this device so nothing after
                // it is skipped, and read it again next time.
                val events = runCatching {
                    val bytes = folder.read("$STREAMS/$stream/${f.name}") ?: error("missing ${f.name}")
                    PennyJson.decodeFromString(ser, String(SyncEngine.inflate(StreamCrypto.open(key, bytes, "batch:$stream"))))
                }.getOrElse { if (failure == null) failure = SyncBlobException(stream, it); break }
                // Only events for this stream are accepted (a file cannot smuggle events into another book).
                total += store.ingest(events.filter { it.profileId == stream }).size
                cursor = f.seq
                kv.put("$kvPrefix.cursor.$stream.$device", cursor.toString())
            }
        }
        failure?.let { throw it }
        return total
    }

    private data class BatchFile(val name: String, val device: String, val seq: Long)

    /** Names other than `<device>_<seq>.pnb` (temporary or conflict copies made by sync tools) are ignored. */
    private fun parse(name: String): BatchFile? {
        if (!name.endsWith(EXT)) return null
        val base = name.removeSuffix(EXT)
        val i = base.lastIndexOf('_').takeIf { it > 0 } ?: return null
        val seq = base.substring(i + 1).toLongOrNull() ?: return null
        return BatchFile(name, base.substring(0, i), seq)
    }

    companion object {
        const val SALT = "penny.salt"
        const val CHECK = "penny.check"
        const val KEYS = "keys"
        const val STREAMS = "streams"
        const val EXT = ".pnb"
        const val BATCH = 300
        private val CHECK_VALUE = "penny-folder-sync-v1".toByteArray()

        fun fileName(device: String, seq: Long) = "${device}_${seq.toString().padStart(12, '0')}$EXT"
    }
}

data class FolderSyncResult(val pushed: Int, val pulled: Int, val streams: List<String>, val error: Throwable?)
