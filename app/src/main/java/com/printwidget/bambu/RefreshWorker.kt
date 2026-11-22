package com.printwidget.bambu

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Fetches fresh status from the Bambu cloud and redraws the widgets.
 *
 * Runs every 15 minutes (Android's minimum for periodic background work). While a print is
 * running it also schedules itself again after 5 minutes, so the time stays accurate.
 * Between refreshes the widget counts down on its own.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = Store(applicationContext)
        if (!store.isSignedIn) {
            PrintWidgetProvider.updateAll(applicationContext)
            return@withContext Result.success()
        }
        try {
            val cloud = BambuCloud(store.region)
            val printers = cloud.fetchPrinters(store.token)
            store.printers = printers
            store.statuses = StatusFetcher(store.region, store.userId, store.token).fetch(printers)
            store.lastError = ""
        } catch (e: AuthExpiredException) {
            store.lastError = "Sign-in expired. Tap to sign in again."
        } catch (e: BambuApiException) {
            store.lastError = if (e.httpCode == 401 || e.httpCode == 403) "Sign-in expired. Tap to sign in again." else "Couldn't reach Bambu cloud"
        } catch (e: Exception) {
            store.lastError = "Couldn't reach Bambu cloud"
        }
        PrintWidgetProvider.updateAll(applicationContext)

        if (store.statuses.any { it.isActive }) {
            val isFollowUp = inputData.getBoolean(KEY_FOLLOW_UP, false)
            scheduleFollowUp(applicationContext, if (isFollowUp) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP)
        }
        Result.success()
    }

    companion object {
        private const val PERIODIC = "print_widget_periodic"
        private const val NOW = "print_widget_now"
        private const val FOLLOW_UP = "print_widget_follow_up"
        private const val KEY_FOLLOW_UP = "follow_up"
        private const val FOLLOW_UP_MINUTES = 5L

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun refreshNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        private fun scheduleFollowUp(context: Context, policy: ExistingWorkPolicy) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setInitialDelay(FOLLOW_UP_MINUTES, TimeUnit.MINUTES)
                .setConstraints(network)
                .setInputData(workDataOf(KEY_FOLLOW_UP to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(FOLLOW_UP, policy, req)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC); cancelUniqueWork(NOW); cancelUniqueWork(FOLLOW_UP)
            }
        }
    }
}
