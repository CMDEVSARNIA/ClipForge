package com.clipforge.export

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.clipforge.ClipForgeApp
import com.clipforge.MainActivity
import com.clipforge.R
import com.clipforge.analysis.AnalysisWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Batch export: clips are rendered one at a time (the encoder is the bottleneck) in rank order. */
@UnstableApi
class ExportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private data class P(val fraction: Float, val text: String)

    override suspend fun doWork(): Result {
        val projectId = inputData.getLong(KEY_PROJECT, -1)
        val ids = inputData.getLongArray(KEY_CLIPS)?.toList().orEmpty()
        if (projectId < 0 || ids.isEmpty()) return Result.failure()
        val progress = MutableStateFlow(P(0f, "Preparing"))
        runCatching { setForeground(foregroundInfo(progress.value)) }
        val outcome = coroutineScope {
            val reporter = launch {
                var last: P? = null
                while (isActive) {
                    val p = progress.value
                    if (p != last) {
                        last = p
                        setProgress(workDataOf(KEY_FRACTION to p.fraction, KEY_STEP to p.text))
                        runCatching { setForeground(foregroundInfo(p)) }
                    }
                    delay(500)
                }
            }
            try {
                ExportPipeline(applicationContext).run(projectId, ids) { f, t -> progress.value = P(f, t) }
            } finally {
                reporter.cancel()
            }
        }
        notifyDone(outcome.exported, outcome.failures)
        val out = workDataOf(KEY_EXPORTED to outcome.exported, KEY_ERROR to outcome.failures.joinToString("\n").ifBlank { null })
        return if (outcome.exported == 0) Result.failure(out) else Result.success(out)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(P(0f, "Preparing"))

    private fun foregroundInfo(p: P): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, AnalysisWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_movie)
            .setContentTitle("Exporting clips")
            .setContentText(p.text)
            .setProgress(100, (p.fraction * 100).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }

    private fun notifyDone(exported: Int, failures: List<String>) {
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = buildString {
            append("Saved $exported clip${if (exported == 1) "" else "s"} to Movies/ClipForge")
            if (failures.isNotEmpty()) append(" · ${failures.size} failed")
        }
        val n = NotificationCompat.Builder(applicationContext, AnalysisWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_movie)
            .setContentTitle(if (exported > 0) "Export finished" else "Export failed")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + if (failures.isNotEmpty()) "\n" + failures.joinToString("\n") else ""))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(DONE_NOTIFICATION_ID, n) }
    }

    companion object {
        const val KEY_PROJECT = "project_id"
        const val KEY_CLIPS = "clip_ids"
        const val KEY_FRACTION = "fraction"
        const val KEY_STEP = "step"
        const val KEY_ERROR = "error"
        const val KEY_EXPORTED = "exported"
        private const val NOTIFICATION_ID = 4102
        private const val DONE_NOTIFICATION_ID = 4103

        fun uniqueName(projectId: Long) = "export-$projectId"

        fun enqueue(context: Context, projectId: Long, clipIds: List<Long>) {
            val req = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(workDataOf(KEY_PROJECT to projectId, KEY_CLIPS to clipIds.toLongArray()))
                .addTag("export")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(projectId), ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun cancel(context: Context, projectId: Long) = WorkManager.getInstance(context).cancelUniqueWork(uniqueName(projectId))
    }
}
