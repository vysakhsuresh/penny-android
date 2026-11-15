package app.penny.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.penny.domain.SyncManager
import app.penny.glance.Widgets
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** SY-04: sync on open, a few seconds after changes (debounced), and every 6 hours when on a network. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: SyncManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!sync.signedIn) return Result.success()
        val before = sync.state.value.error
        sync.syncNow()
        Widgets.refresh(applicationContext)
        return if (sync.state.value.error != null && sync.state.value.error != before) Result.retry() else Result.success()
    }

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sync", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(net).build(),
            )
        }

        /** Debounced: each call replaces the pending one, so a burst of edits syncs once. */
        fun soon(context: Context, delaySeconds: Long = 8) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync-soon", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build(),
            )
        }
    }
}
