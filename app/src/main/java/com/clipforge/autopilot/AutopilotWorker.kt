package com.clipforge.autopilot

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
import com.clipforge.captions.CaptionPipeline
import com.clipforge.data.db.ClipEntity
import com.clipforge.export.ExportPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object AutopilotRules {
    private val order = listOf("S", "A", "B", "C", "D")

    /** Clips at [minTier] or better, best first, at most [max]. */
    fun pick(clips: List<ClipEntity>, minTier: String, max: Int): List<ClipEntity> {
        val limit = order.indexOf(minTier).let { if (it < 0) 1 else it }
        return clips.filter { order.indexOf(it.tier) in 0..limit }.sortedBy { it.rank }.take(max.coerceAtLeast(1))
    }
}

/**
 * One tap from a raw recording to finished clips: find moments (unless the project already has
 * them), caption + title the best ones, and export them, then post a "clips ready" notification.
 */
@UnstableApi
class AutopilotWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private data class P(val fraction: Float, val text: String)

    override suspend fun doWork(): Result {
        val projectId = inputData.getLong(KEY_PROJECT, -1)
        if (projectId < 0) return Result.failure()
        val game = inputData.getString(KEY_GAME)
        val reanalyze = inputData.getBoolean(KEY_REANALYZE, false)
        val c = (applicationContext as ClipForgeApp).container
        val db = c.database
        val settings = c.settings.current()
        val progress = MutableStateFlow(P(0f, "Starting"))
        runCatching { setForeground(foregroundInfo(progress.value)) }

        return coroutineScope {
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
                // 1. Moments
                val existing = db.clipDao().getAll(db.clipDao().idsForProject(projectId))
                if (reanalyze || existing.isEmpty()) {
                    c.analyzer.run(projectId, game) { p -> progress.value = P(0.55f * p.fraction, "Finding moments · ${p.step}") }
                }
                val all = db.clipDao().getAll(db.clipDao().idsForProject(projectId))
                val picked = AutopilotRules.pick(all, settings.autopilotMinTier, settings.autopilotMaxClips)
                if (picked.isEmpty()) {
                    notifyDone("No ${settings.autopilotMinTier}-tier moments", "Nothing reached ${settings.autopilotMinTier} tier or better. Open the project to pick clips yourself.")
                    return@coroutineScope Result.success(workDataOf(KEY_EXPORTED to 0))
                }
                val ids = picked.map { it.id }
                val failures = ArrayList<String>()
                // 2. Captions & titles
                if (settings.autopilotCaptions) {
                    failures += CaptionPipeline(applicationContext).run(projectId, ids) { f, t -> progress.value = P(0.55f + 0.15f * f, "Captions · $t") }
                }
                // 3. Export
                val out = ExportPipeline(applicationContext).run(projectId, ids) { f, t -> progress.value = P(0.7f + 0.3f * f, "Exporting · $t") }
                failures += out.failures
                val n = out.exported
                notifyDone(
                    if (n > 0) "$n clip${if (n == 1) "" else "s"} ready" else "Autopilot couldn't export",
                    if (n > 0) "Saved to Movies/ClipForge" + (if (failures.isNotEmpty()) " · ${failures.size} issue(s)" else "") else failures.firstOrNull() ?: "Unknown error",
                )
                val data = workDataOf(KEY_EXPORTED to n, KEY_ERROR to failures.joinToString("\n").ifBlank { null })
                if (n > 0) Result.success(data) else Result.failure(data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notifyDone("Autopilot stopped", e.message ?: "Analysis failed")
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Autopilot failed")))
            } finally {
                reporter.cancel()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(P(0f, "Starting"))

    private fun foregroundInfo(p: P): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, AnalysisWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_movie)
            .setContentTitle("Autopilot")
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

    private fun notifyDone(title: String, text: String) {
        val open = PendingIntent.getActivity(
            applicationContext, 1, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(applicationContext, AnalysisWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_movie)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(DONE_ID, n) }
    }

    companion object {
        const val KEY_PROJECT = "project_id"
        const val KEY_GAME = "game"
        const val KEY_REANALYZE = "reanalyze"
        const val KEY_FRACTION = "fraction"
        const val KEY_STEP = "step"
        const val KEY_ERROR = "error"
        const val KEY_EXPORTED = "exported"
        private const val NOTIFICATION_ID = 4105
        private const val DONE_ID = 4106

        fun uniqueName(projectId: Long) = "autopilot-$projectId"

        fun enqueue(context: Context, projectId: Long, game: String?, reanalyze: Boolean) {
            val req = OneTimeWorkRequestBuilder<AutopilotWorker>()
                .setInputData(workDataOf(KEY_PROJECT to projectId, KEY_GAME to game, KEY_REANALYZE to reanalyze))
                .addTag("autopilot")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(projectId), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, projectId: Long) = WorkManager.getInstance(context).cancelUniqueWork(uniqueName(projectId))
    }
}
