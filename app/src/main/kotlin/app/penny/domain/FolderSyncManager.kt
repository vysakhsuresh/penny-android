package app.penny.domain

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.penny.core.event.Entities
import app.penny.data.db.KvRow
import app.penny.data.db.PennyDao
import app.penny.sync.FolderSync
import app.penny.sync.SyncFolder
import app.penny.sync.SyncKv
import app.penny.sync.Vault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class FolderSyncState(
    val on: Boolean = false,
    val folderName: String? = null,
    val syncing: Boolean = false,
    val lastSync: Long? = null,
    val error: String? = null,
    /** Shared books that sync through their own folder. */
    val spaces: List<String> = emptyList(),
)

/**
 * Free sync (decision 53): no account, no server. Each device keeps the same folder (Syncthing, a Drive/OneDrive sync
 * app, a USB copy) and Penny reads and writes encrypted change files in it. The recovery phrase opens it on a new device.
 */
@Singleton
class FolderSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: PennyRepository,
    private val dao: PennyDao,
    private val prefs: AppPrefs,
) {
    private val vault by lazy { Vault(context) }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(FolderSyncState())
    val state: StateFlow<FolderSyncState> = _state

    init { refreshState() }

    /** Personal folder sync is set up on this phone. */
    val on: Boolean get() = prefs.string(FOLDER) != null && runCatching { vault.get(MASTER) }.getOrNull() != null

    /** Anything syncs through a folder (personal or a shared book): used to schedule sync after changes. */
    val active: Boolean get() = on || sharedSpaces().isNotEmpty()

    private fun refreshState() {
        val uri = prefs.string(FOLDER)?.let(Uri::parse)
        _state.value = _state.value.copy(
            on = on,
            folderName = uri?.let { runCatching { DocumentFile.fromTreeUri(context, it)?.name }.getOrNull() },
            lastSync = prefs.long(LAST).takeIf { it > 0 },
            spaces = sharedSpaces(),
        )
    }

    private fun engine(uri: Uri, kvPrefix: String = "fsync") = FolderSync(repo.store, Kv(dao), DocFolder(context, uri), prefs.deviceId, kvPrefix = kvPrefix)

    /** True when the chosen folder already holds Penny sync files from another device (then the phrase is asked). */
    suspend fun folderHasPenny(uri: Uri): Boolean = withContext(Dispatchers.IO) { engine(uri).isSetUp() }

    /** First device: sets the folder up with [words]; they also encrypt backups when backups have no phrase yet. */
    suspend fun startNew(uri: Uri, words: List<String>) = withContext(Dispatchers.IO) {
        val phrase = BackupCrypto.normalise(words.joinToString(" "))
        val master = engine(uri).create(phrase)
        vault.put(MASTER, master)
        prefs.setString(FOLDER, uri.toString())
        if (!BackupCrypto.enabled(prefs)) BackupCrypto.enable(prefs, phrase.split(' '))
        refreshState()
        syncNow()
    }

    /** Another device: returns false when the phrase does not open this folder. */
    suspend fun join(uri: Uri, phrase: String): Boolean = withContext(Dispatchers.IO) {
        val master = engine(uri).unlock(BackupCrypto.normalise(phrase)) ?: return@withContext false
        vault.put(MASTER, master)
        prefs.setString(FOLDER, uri.toString())
        if (!BackupCrypto.enabled(prefs)) BackupCrypto.enable(prefs, BackupCrypto.normalise(phrase).split(' '))
        refreshState()
        syncNow()
        true
    }

    // ------------------------------------------------------------------ Shared books through a folder (free)

    /** Shared books on this phone that sync through their own folder. */
    fun sharedSpaces(): List<String> = prefs.string(SPACES)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    fun spaceFolderName(spaceId: String): String? = prefs.string("$SPACE_FOLDER$spaceId")?.let { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }.getOrNull() }
    /** The share code, kept so the owner can give it to someone else later (it is a shared secret by design). */
    fun shareCode(spaceId: String): List<String>? = vault.string("$SPACE_WORDS$spaceId")?.split(' ')

    private fun addSpace(spaceId: String, uri: Uri, master: ByteArray, words: String) {
        prefs.setString(FORGOTTEN, (forgotten() - spaceId).joinToString(",").ifEmpty { null })
        vault.put("$SPACE_MASTER$spaceId", master)
        vault.putString("$SPACE_WORDS$spaceId", words)
        prefs.setString("$SPACE_FOLDER$spaceId", uri.toString())
        prefs.setString(SPACES, (sharedSpaces() + spaceId).distinct().joinToString(","))
        _state.value = _state.value.copy(spaces = sharedSpaces())
    }

    /** Owner: puts [spaceId] (and only it) into [uri], sealed with a new share code made of [words]. */
    suspend fun shareSpace(spaceId: String, uri: Uri, words: List<String>) = withContext(Dispatchers.IO) {
        val code = BackupCrypto.normalise(words.joinToString(" "))
        val master = engine(uri, "fspace.$spaceId").create(code)
        addSpace(spaceId, uri, master, code)
        syncNow()
    }

    /**
     * Someone joining: opens the shared folder with the share code, pulls the book and adds [myName] as a member.
     * Returns the shared book's id, or null when the code does not open the folder.
     */
    suspend fun joinSpace(uri: Uri, code: String, myName: String): String? = withContext(Dispatchers.IO) {
        val words = BackupCrypto.normalise(code)
        val e = engine(uri)
        val master = e.unlock(words) ?: return@withContext null
        val spaceId = e.streamIds().firstOrNull() ?: return@withContext null
        addSpace(spaceId, uri, master, words)
        syncNow()
        // The code was right but the book's files have not been copied in yet: say so rather than open an empty book.
        if (repo.store.current(Entities.PROFILE, spaceId) == null) throw IllegalStateException(NOT_ARRIVED)
        // Joining adds you to the member list everyone sees; the book itself arrived with the sync above.
        if (repo.store.current(Entities.SPACE, spaceId) != null && myName.isNotBlank()) {
            runCatching {
                repo.members.create(repo.newId(), spaceId, app.penny.core.model.Member(spaceId, prefs.localAccountId, myName.trim(), app.penny.core.model.Role.EDITOR))
                syncNow()
            }
        }
        spaceId
    }

    private fun forgotten(): Set<String> = prefs.string(FORGOTTEN)?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    /** A book deleted on this phone: stop its own shared folder and never pull it from the personal folder again. */
    suspend fun forget(pid: String) = mutex.withLock {
        if (pid in sharedSpaces()) stopSpaceLocked(pid)
        prefs.setString(FORGOTTEN, (forgotten() + pid).joinToString(","))
    }

    /** Stops sharing [spaceId] on this phone. The book stays here; the folder keeps what was written. */
    suspend fun stopSpace(spaceId: String) = mutex.withLock { stopSpaceLocked(spaceId) }

    private suspend fun stopSpaceLocked(spaceId: String) {
        vault.put("$SPACE_MASTER$spaceId", null)
        vault.put("$SPACE_WORDS$spaceId", null)
        dao.deleteKvPrefix("fspace.$spaceId.")
        prefs.setString("$SPACE_FOLDER$spaceId", null)
        prefs.setString(SPACES, sharedSpaces().filter { it != spaceId }.joinToString(",").ifEmpty { null })
        _state.value = _state.value.copy(spaces = sharedSpaces())
    }

    /** Stops syncing on this device. The folder and the data on this phone stay as they are. */
    suspend fun stop() = mutex.withLock {
        vault.put(MASTER, null)
        prefs.setString(FOLDER, null)
        // Cursors go too, so turning it on again later reads everything once more (ingest skips what it already has).
        dao.deleteKvPrefix("fsync.")
        _state.value = FolderSyncState(spaces = sharedSpaces())
    }

    suspend fun syncNow() = mutex.withLock {
        if (!active) return@withLock
        _state.value = _state.value.copy(syncing = true, error = null)
        var failed = false
        var partial = false
        withContext(Dispatchers.IO) {
            val shared = sharedSpaces()
            // Personal folder: every book on this phone except the duress book and books shared through their own folder.
            val uri = prefs.string(FOLDER)?.let(Uri::parse)
            val master = vault.get(MASTER)
            if (uri != null && master != null) runCatching {
                val profiles = dao.observeAllOf(Entities.PROFILE).first().map { it.id }
                engine(uri).sync(master, profiles.filter { it != prefs.security.value.duressProfileId && it !in shared }, forgotten())
            }.onSuccess { if (it.error != null) partial = true }.onFailure { android.util.Log.w("Penny", "folder sync failed", it); failed = true }
            // Each shared book: only that book, in its own folder, with its own share code.
            shared.forEach { space ->
                val su = prefs.string("$SPACE_FOLDER$space")?.let(Uri::parse) ?: return@forEach
                val sm = vault.get("$SPACE_MASTER$space") ?: return@forEach
                runCatching { engine(su, "fspace.$space").sync(sm, listOf(space)) }
                    .onSuccess { if (it.error != null) partial = true }.onFailure { android.util.Log.w("Penny", "shared folder sync failed", it); failed = true }
            }
        }
        val now = System.currentTimeMillis()
        if (!failed && !partial) prefs.setLong(LAST, now)
        _state.value = _state.value.copy(
            syncing = false,
            lastSync = if (!failed && !partial) now else _state.value.lastSync,
            error = when {
                failed -> context.getString(app.penny.R.string.folder_sync_failed)
                partial -> context.getString(app.penny.R.string.folder_sync_partial)
                else -> null
            },
        )
    }

    private class Kv(private val dao: PennyDao) : SyncKv {
        override suspend fun get(key: String) = dao.kv(key)
        override suspend fun put(key: String, value: String) = dao.putKv(KvRow(key, value))
    }

    /** The user's folder through the system document picker (any provider that supports folders). */
    private class DocFolder(private val context: Context, uri: Uri) : SyncFolder {
        private val root = DocumentFile.fromTreeUri(context, uri) ?: error("folder unavailable")
        private val dirs = mutableMapOf<String, DocumentFile?>()

        private fun dir(path: String, create: Boolean): DocumentFile? {
            if (path.isEmpty()) return root
            dirs[path]?.let { return it }
            val parent = dir(path.substringBeforeLast('/', ""), create) ?: return null
            val name = path.substringAfterLast('/')
            val found = parent.findFile(name)?.takeIf { it.isDirectory } ?: if (create) parent.createDirectory(name) else null
            if (found != null) dirs[path] = found
            return found
        }

        override suspend fun list(dir: String): List<String> = dir(dir, false)?.listFiles()?.filter { it.isFile }?.mapNotNull { it.name }.orEmpty()

        override suspend fun read(path: String): ByteArray? {
            val f = dir(path.substringBeforeLast('/', ""), false)?.findFile(path.substringAfterLast('/')) ?: return null
            return context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
        }

        override suspend fun write(path: String, bytes: ByteArray) {
            val d = dir(path.substringBeforeLast('/', ""), true) ?: error("cannot create folder")
            val name = path.substringAfterLast('/')
            val f = d.findFile(name) ?: d.createFile("application/octet-stream", name) ?: error("cannot create file")
            context.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(bytes) } ?: error("cannot write file")
        }
    }

    companion object {
        const val FOLDER = "fsync.folder"
        const val LAST = "fsync.last"
        const val MASTER = "fsync.master"
        const val SPACES = "fspace.list"
        const val NOT_ARRIVED = "not_arrived"
        const val FORGOTTEN = "fsync.forgotten"
        const val SPACE_FOLDER = "fspace.folder."
        const val SPACE_MASTER = "fspace.master."
        const val SPACE_WORDS = "fspace.words."
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface FolderSyncDeps { fun folderSync(): FolderSyncManager }
