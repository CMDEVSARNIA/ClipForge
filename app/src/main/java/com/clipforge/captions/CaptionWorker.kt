package com.clipforge.captions

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.clipforge.ClipForgeApp
import com.clipforge.R
import com.clipforge.analysis.AnalysisWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Captions + titles for one or more clips:
 * whisper (on-device) → speech check → Claude polish, 3 titles, 5 hashtags → saved on the clip.
 * Without speech it still asks Claude for titles/hashtags; without a Worker connection it keeps raw captions.
 */
class CaptionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private data class P(val fraction: Float, val text: String)

    override suspend fun doWork(): Result {
        val projectId = inputData.getLong(KEY_PROJECT, -1)
        val ids = inputData.getLongArray(KEY_CLIPS)?.toList().orEmpty()
        if (projectId < 0 || ids.isEmpty()) return Result.failure()
        val progress = MutableStateFlow(P(0f, "Preparing"))
        runCatching { setForeground(foregroundInfo(progress.value)) }
        val failures = coroutineScope {
            val reporter = launch {
                var last: P? = null
                while (isActive) {
                    val p = progress.value
                    if (p != last) {
                        last = p
                        setProgress(workDataOf(KEY_FRACTION to p.fraction, KEY_STEP to p.text))
                        runCatching { setForeground(foregroundInfo(p)) }
                    }
                    delay(400)
                }
            }
            try {
                CaptionPipeline(applicationContext).run(projectId, ids) { f, t -> progress.value = P(f, t) }
            } finally {
                reporter.cancel()
            }
        }
        return Result.success(workDataOf(KEY_ERROR to failures.joinToString("\n").ifBlank { null }))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(P(0f, "Preparing"))

    private fun foregroundInfo(p: P): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, AnalysisWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_movie)
            .setContentTitle("Captions & titles")
            .setContentText(p.text)
            .setProgress(100, (p.fraction * 100).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, n)
    }

    companion object {
        const val KEY_PROJECT = "project_id"
        const val KEY_CLIPS = "clip_ids"
        const val KEY_FRACTION = "fraction"
        const val KEY_STEP = "step"
        const val KEY_ERROR = "error"
        const val TAG = "captions"
        private const val NOTIFICATION_ID = 4104

        fun uniqueName(projectId: Long) = "captions-$projectId"

        fun enqueue(context: Context, projectId: Long, clipIds: List<Long>) {
            val req = OneTimeWorkRequestBuilder<CaptionWorker>()
                .setInputData(workDataOf(KEY_PROJECT to projectId, KEY_CLIPS to clipIds.toLongArray()))
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(projectId), ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun cancel(context: Context, projectId: Long) = WorkManager.getInstance(context).cancelUniqueWork(uniqueName(projectId))
    }
}
