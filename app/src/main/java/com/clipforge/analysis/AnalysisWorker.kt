package com.clipforge.analysis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.clipforge.ClipForgeApp
import com.clipforge.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Runs moment finding as a foreground job so it survives leaving the screen. */
class AnalysisWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getLong(KEY_PROJECT, -1)
        if (projectId < 0) return Result.failure()
        val game = inputData.getString(KEY_GAME)
        val app = applicationContext as ClipForgeApp
        val progress = MutableStateFlow(MomentAnalyzer.Progress(0f, "Starting"))
        runCatching { setForeground(foregroundInfo(progress.value)) }

        return coroutineScope {
            val reporter = launch {
                var last: MomentAnalyzer.Progress? = null
                while (isActive) {
                    val p = progress.value
                    if (p != last) {
                        last = p
                        setProgress(workDataOf(KEY_FRACTION to p.fraction, KEY_STEP to p.step))
                        runCatching { setForeground(foregroundInfo(p)) }
                    }
                    delay(500)
                }
            }
            try {
                app.container.analyzer.run(projectId, game) { progress.value = it }
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Analysis failed")))
            } finally {
                reporter.cancel()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(MomentAnalyzer.Progress(0f, "Starting"))

    private fun foregroundInfo(p: MomentAnalyzer.Progress): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_movie)
            .setContentTitle("Finding moments")
            .setContentText(p.step)
            .setProgress(100, (p.fraction * 100).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val KEY_PROJECT = "project_id"
        const val KEY_GAME = "game"
        const val KEY_FRACTION = "fraction"
        const val KEY_STEP = "step"
        const val KEY_ERROR = "error"
        const val CHANNEL_ID = "analysis"
        private const val NOTIFICATION_ID = 4101

        fun uniqueName(projectId: Long) = "analyze-$projectId"

        fun enqueue(context: Context, projectId: Long, game: String?) {
            val req = OneTimeWorkRequestBuilder<AnalysisWorker>()
                .setInputData(workDataOf(KEY_PROJECT to projectId, KEY_GAME to game))
                .addTag("analysis")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(projectId), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, projectId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(projectId))
        }

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Moment finding", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress while ClipForge analyzes a video"
                }
            )
        }
    }
}
