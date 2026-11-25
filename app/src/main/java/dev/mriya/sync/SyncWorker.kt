package dev.mriya.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.mriya.MriyaApplication
import dev.mriya.R
import dev.mriya.data.PassportRepository
import kotlinx.coroutines.CancellationException

/**
 * Runs [PassportRepository.sync] outside the UI process lifetime. The API key never travels through
 * WorkManager: the worker reads URL and key from the repository's (encrypted) store itself.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val wantsForeground get() = inputData.getBoolean(KEY_FOREGROUND, false)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private fun foregroundInfo(page: Int, total: Int): ForegroundInfo {
        val n = buildNotification(page, total)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    private fun buildNotification(page: Int, total: Int): android.app.Notification {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sync", NotificationManager.IMPORTANCE_LOW))
        }
        val text = if (total > 0) "Syncing page $page of $total" else "Syncing…"
        val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(id)
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mriya)
            .setContentTitle("Mriya")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(total, page, total <= 0)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .build()
    }

    private fun fail(message: String) = Result.failure(workDataOf(KEY_ERROR to message))

    override suspend fun doWork(): Result {
        val repo = (applicationContext as? MriyaApplication)?.repository ?: PassportRepository(applicationContext)
        try {
            if (repo.serverUrl.isBlank() || repo.apiKey.isBlank()) return fail("Set your Dawarich server URL and API key in Settings.")
            if (!repo.isUrlAllowed(repo.serverUrl)) return fail("Server URL must start with https://")
        } catch (e: Exception) {
            return fail("Could not read the saved connection settings.")
        }

        var foregroundOn = false
        if (wantsForeground) {
            // Can be refused (e.g. started from the background on Android 12+); the sync then runs as plain work.
            foregroundOn = try { setForeground(foregroundInfo(0, 0)); true } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
        }

        var page = 0
        var total = 0
        var commits = 0
        fun publish() { setProgressAsync(workDataOf(KEY_PAGE to page, KEY_TOTAL to total, KEY_COMMITS to commits)) }

        return try {
            repo.sync(
                onProgress = { p, t ->
                    page = p; total = t
                    publish()
                    if (foregroundOn) runCatching {
                        applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(p, t))
                    }
                },
                onData = { commits++; publish() }, // a checkpoint reached the disk: the UI may reload
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (val o = SyncErrors.decide(e, runAttemptCount)) {
                is SyncOutcome.Retry -> Result.retry()
                is SyncOutcome.Fail -> fail(o.message)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "sync"
        const val NOTIF_ID = 4201
        const val KEY_FOREGROUND = "foreground"
        const val KEY_PAGE = "page"
        const val KEY_TOTAL = "total"
        const val KEY_COMMITS = "commits"
        const val KEY_ERROR = "error"
    }
}
