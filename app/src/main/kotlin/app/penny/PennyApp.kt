package app.penny

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.penny.capture.CaptureRegistry
import app.penny.domain.CaptureService
import app.penny.work.DailyWorker
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.security.KeyStore
import javax.inject.Inject

@HiltAndroidApp
class PennyApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var capture: CaptureService

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        CaptureRegistry.sink = capture
        DailyWorker.schedule(this)
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
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("penny_db_wrap_v1") }
            File(context.filesDir, "attachments").deleteRecursively()
            context.cacheDir.deleteRecursively()
            File(context.filesDir.parentFile, "shared_prefs").listFiles()?.forEach { it.delete() }
            val restart = context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (restart != null) context.startActivity(restart)
            Runtime.getRuntime().exit(0)
        }
    }
}
