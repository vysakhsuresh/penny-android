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

    val on: Boolean get() = prefs.string(FOLDER) != null && runCatching { vault.get(MASTER) }.getOrNull() != null

    private fun refreshState() {
        val uri = prefs.string(FOLDER)?.let(Uri::parse)
        _state.value = _state.value.copy(
            on = on,
            folderName = uri?.let { runCatching { DocumentFile.fromTreeUri(context, it)?.name }.getOrNull() },
            lastSync = prefs.long(LAST).takeIf { it > 0 },
        )
    }

    private fun engine(uri: Uri) = FolderSync(repo.store, Kv(dao), DocFolder(context, uri), prefs.deviceId)

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

    /** Stops syncing on this device. The folder and the data on this phone stay as they are. */
    suspend fun stop() = mutex.withLock {
        vault.put(MASTER, null)
        prefs.setString(FOLDER, null)
        // Cursors go too, so turning it on again later reads everything once more (ingest skips what it already has).
        dao.deleteKvPrefix("fsync.")
        _state.value = FolderSyncState()
    }

    suspend fun syncNow() = mutex.withLock {
        val uri = prefs.string(FOLDER)?.let(Uri::parse) ?: return@withLock
        val master = vault.get(MASTER) ?: return@withLock
        _state.value = _state.value.copy(syncing = true, error = null)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val profiles = dao.observeAllOf(Entities.PROFILE).first().map { it.id }
                // The duress book never leaves the phone, and spaces are shared through the relay, not this folder.
                engine(uri).sync(master, profiles.filter { it != prefs.security.value.duressProfileId })
            }
        }
        val now = System.currentTimeMillis()
        result.onSuccess { r ->
            if (r.error == null) prefs.setLong(LAST, now)
            _state.value = _state.value.copy(syncing = false, lastSync = if (r.error == null) now else _state.value.lastSync, error = r.error?.let { context.getString(app.penny.R.string.folder_sync_partial) })
        }.onFailure { e ->
            android.util.Log.w("Penny", "folder sync failed", e)
            _state.value = _state.value.copy(syncing = false, error = context.getString(app.penny.R.string.folder_sync_failed))
        }
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

    private companion object {
        const val FOLDER = "fsync.folder"
        const val LAST = "fsync.last"
        const val MASTER = "fsync.master"
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface FolderSyncDeps { fun folderSync(): FolderSyncManager }
