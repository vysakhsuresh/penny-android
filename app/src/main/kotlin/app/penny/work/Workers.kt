package app.penny.work

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.penny.core.Brand
import app.penny.core.event.Entities
import app.penny.data.toState
import app.penny.domain.AlertService
import app.penny.domain.AppPrefs
import app.penny.domain.PennyRepository
import app.penny.domain.ops.exportJson
import app.penny.domain.ops.runScheduled
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Daily, battery-light (NF-05): auto-post due bills and goal contributions, evaluate alerts, purge Trash older than
 * 30 days (HS-02), and back up to the user's folder when one is set (SY-05).
 */
@HiltWorker
class DailyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repo: PennyRepository,
    private val alerts: AlertService,
    private val prefs: AppPrefs,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Every profile on the phone, not only the open one: bills and goals in other books must still post.
        val pids = runCatching { repo.observeProfiles().first().map { it.state.id } }.getOrDefault(emptyList())
            .ifEmpty { listOfNotNull(prefs.currentProfileId.value) }
        for (pid in pids) {
            val book = runCatching { repo.observeBook(pid).firstOrNull() }.getOrNull() ?: continue
            runCatching { repo.runScheduled(book) }
            runCatching { repo.observeBook(pid).first()?.let { alerts.run(it) } }
            runCatching { purgeTrash(repo, pid) }
        }
        if (prefs.string("backup.folder") != null) runCatching { BackupWorker.backup(applicationContext, repo, prefs) }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "daily", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS).build(),
            )
        }

        suspend fun purgeTrash(repo: PennyRepository, pid: String) {
            val cutoff = System.currentTimeMillis() - 30L * 86_400_000L
            Entities.TRASHABLE.forEach { e -> repo.dao.deletedBefore(e, pid, cutoff).forEach { row -> repo.repoFor(e).purge(row.toState().id) } }
        }
    }
}

object BackupWorker {
    private const val KEEP = 7

    fun schedule(context: Context) = DailyWorker.schedule(context)

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun repo(): PennyRepository
        fun prefs(): AppPrefs
    }

    suspend fun runNow(context: Context): Boolean {
        val d = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        return withContext(Dispatchers.IO) { runCatching { backup(context, d.repo(), d.prefs()) }.getOrDefault(false) }
    }

    /** Writes a dated export into the chosen folder and keeps the newest [KEEP] (SY-05). */
    suspend fun backup(context: Context, repo: PennyRepository, prefs: AppPrefs): Boolean {
        val folder = prefs.string("backup.folder")?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) } ?: return false
        val pid = prefs.currentProfileId.value ?: return false
        val name = "${Brand.EXPORT_APP_ID}-backup-${LocalDate.now()}.json"
        folder.findFile(name)?.delete()
        val file = folder.createFile("application/json", name) ?: return false
        val json = repo.exportJson(pid)
        val payload = app.penny.domain.BackupCrypto.maybeEncrypt(prefs, json)
        context.contentResolver.openOutputStream(file.uri)?.use { it.write(payload) } ?: return false
        folder.listFiles().filter { it.name?.startsWith("${Brand.EXPORT_APP_ID}-backup-") == true }.sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
        prefs.setLong("backup.last", System.currentTimeMillis())
        return true
    }
}
