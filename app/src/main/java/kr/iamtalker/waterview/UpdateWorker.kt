package kr.iamtalker.waterview

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Looks for a newer release once a day, even while the app is closed, when the phone has a network.
 * It is the same check as the one made when the app opens, see [UpdateNotice.checkDaily], so the
 * notice comes at most once a day whichever runs first.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        UpdateNotice.checkDaily(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "update-check"

        /** Schedules the daily check, once. Calling it again keeps the schedule already made. */
        fun schedule(context: Context) {
            // No network constraint: that would need ACCESS_NETWORK_STATE, which the app leaves out on
            // purpose. Without a network the check just finds nothing and tries again the next day.
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
