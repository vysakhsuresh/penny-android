package app.penny

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.penny.capture.CaptureRegistry
import app.penny.core.Brand
import app.penny.domain.AlertService
import app.penny.domain.CaptureService
import app.penny.domain.PennyRepository
import app.penny.domain.SyncManager
import app.penny.glance.Widgets
import app.penny.work.DailyWorker
import app.penny.work.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File
import java.security.KeyStore
import javax.inject.Inject

@HiltAndroidApp
class PennyApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var capture: CaptureService
    @Inject lateinit var repo: PennyRepository
    @Inject lateinit var sync: SyncManager
    @Inject lateinit var alerts: AlertService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        CaptureRegistry.sink = capture
        DailyWorker.schedule(this)
        SyncWorker.schedulePeriodic(this)
        app.penny.domain.RatesWorker.schedule(this)
        alerts.ensureChannel()
        shortcuts()
        // SY-04 sync shortly after local changes (debounced); widgets follow the same signal.
        scope.launch {
            repo.store.changes.drop(1).debounce(1_500).collect {
                if (sync.signedIn) SyncWorker.soon(this@PennyApp)
                Widgets.refresh(this@PennyApp)
            }
        }
        if (sync.signedIn) SyncWorker.soon(this, 1)
    }

    /** WG-03 launcher shortcuts, built in code so they follow the build's application id. */
    private fun shortcuts() {
        fun s(id: String, label: Int, uri: String) = ShortcutInfoCompat.Builder(this, id)
            .setShortLabel(getString(label)).setLongLabel(getString(label))
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(uri), this, MainActivity::class.java)).build()
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(this, listOf(
                s("add", R.string.shortcut_add, "${Brand.DEEP_LINK_SCHEME}://add"),
                s("search", R.string.search, "${Brand.DEEP_LINK_SCHEME}://activity"),
                s("review", R.string.review_queue, "${Brand.DEEP_LINK_SCHEME}://review"),
            ))
        }
    }

    companion object {
        /**
         * SE-09 "delete everything" on this device: database, key blob and Keystore key, attachments, preferences,
         * caches. Then the app restarts into onboarding. Relay deletion runs from the sync layer when signed in.
         */
        fun wipeEverything(context: Context) {
            runCatching { androidx.work.WorkManager.getInstance(context).cancelAllWork() }
            context.deleteDatabase(app.penny.data.db.PennyDatabase.NAME)
            File(context.noBackupFilesDir, "db.key").delete()
            runCatching {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                listOf("penny_db_wrap_v1", "penny_vault_v1", "penny_backup_wrap_v1", "penny_device_master_v1").forEach { a -> runCatching { ks.deleteEntry(a) } }
            }
            File(context.filesDir, "attachments").deleteRecursively()
            context.cacheDir.deleteRecursively()
            File(context.filesDir.parentFile, "shared_prefs").listFiles()?.forEach { it.delete() }
            val restart = context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (restart != null) context.startActivity(restart)
            Runtime.getRuntime().exit(0)
        }
    }
}
