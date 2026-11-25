package dev.mriya.sync

import android.content.Context
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

object SyncScheduler {
    const val WORK_NOW = "sync-now"
    const val WORK_PERIODIC = "sync-periodic"
    private const val PREF_PERIODIC = "periodic_sync"
    private const val PERIOD_HOURS = 6L

    private fun wm(ctx: Context) = WorkManager.getInstance(ctx.applicationContext)
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun isPeriodicEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_PERIODIC, false)

    /** Starts a sync now (expedited, with a foreground notification). A sync already queued or running is kept. */
    fun enqueueManual(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_FOREGROUND to true))
            .build()
        wm(ctx).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, req)
    }

    /** Cancels the manual sync only; the periodic schedule is untouched. */
    fun cancel(ctx: Context) { wm(ctx).cancelUniqueWork(WORK_NOW) }

    /** Opt-in periodic sync (every 6 h, on network, battery not low). Persists the preference. */
    fun setPeriodic(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit { putBoolean(PREF_PERIODIC, enabled) }
        applyPeriodic(ctx)
    }

    /** Re-applies the saved preference (after [stopAll], which cancels the periodic work too). */
    fun applyPeriodic(ctx: Context) {
        if (!isPeriodicEnabled(ctx)) { wm(ctx).cancelUniqueWork(WORK_PERIODIC); return }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build(),
            )
            // First run after a full period, so toggling the switch (or re-applying it) never syncs instantly.
            .setInitialDelay(PERIOD_HOURS, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .setInputData(workDataOf(SyncWorker.KEY_FOREGROUND to false))
            .build()
        wm(ctx).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /**
     * Cancels manual and periodic work and waits (briefly) until no worker is still running, so nothing can
     * write a checkpoint after the caller wipes data. Call [applyPeriodic] afterwards to restore the schedule.
     */
    suspend fun stopAll(ctx: Context) {
        val w = wm(ctx)
        w.cancelUniqueWork(WORK_NOW).await()
        w.cancelUniqueWork(WORK_PERIODIC).await()
        withTimeoutOrNull(15_000) {
            combine(w.getWorkInfosForUniqueWorkFlow(WORK_NOW), w.getWorkInfosForUniqueWorkFlow(WORK_PERIODIC)) { a, b ->
                (a + b).none { it.state == WorkInfo.State.RUNNING }
            }.first { it }
        }
    }
}
