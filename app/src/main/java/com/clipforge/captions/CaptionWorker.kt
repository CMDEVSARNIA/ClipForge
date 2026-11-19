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
        val c = (applicationContext as ClipForgeApp).container
        val db = c.database
        val project = db.projectDao().get(projectId) ?: return Result.failure()
        val clips = db.clipDao().getAll(ids)
        val settings = c.settings.current()
        val analysis = db.analysisDao().latest(projectId)
        val model = SpeechModel.of(settings.speechModel)
        val store = SpeechModelStore(applicationContext)
        val extractor = PcmExtractor(applicationContext)

        val progress = MutableStateFlow(P(0f, "Preparing"))
        runCatching { setForeground(foregroundInfo(progress.value)) }
        val failures = ArrayList<String>()
        var engine: WhisperEngine? = null

        coroutineScope {
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
                if (project.hasAudio && !store.isReady(model)) {
                    store.ensure(model) { f -> progress.value = P(0f, "Downloading speech model ${(f * 100).toInt()}%") }
                }
                clips.forEachIndexed { i, clip ->
                    val label = "Clip ${i + 1}/${clips.size}"
                    fun step(frac: Float, what: String) { progress.value = P((i + frac) / clips.size, "$label · $what") }
                    try {
                        var words = emptyList<CaptionWord>()
                        var status = "NO_AUDIO"
                        var lang: String? = null
                        if (project.hasAudio) {
                            step(0.05f, "reading audio")
                            val pcm = extractor.extract16kMono(project.sourceUri.toUri(), clip.startMs, clip.endMs)
                            if (pcm != null && pcm.isNotEmpty()) {
                                step(0.2f, "transcribing")
                                val eng = engine ?: withContext(Dispatchers.IO) { WhisperEngine.load(store.file(model)) }.also { engine = it }
                                val result = withContext(Dispatchers.Default) { eng.transcribe(pcm, settings.captionLanguage) }
                                    ?: throw IllegalStateException("Transcription failed")
                                lang = result.lang
                                val speech = CaptionMath.filterSpeech(result.segments)
                                if (speech.isSpeech) {
                                    words = CaptionMath.toCaptionWords(speech.words, clip.startMs)
                                    status = "READY"
                                } else status = "NO_SPEECH"
                            }
                        }
                        var titles: List<String> = emptyList()
                        var tags: List<String> = emptyList()
                        if (settings.isConnected) {
                            step(0.7f, if (words.isEmpty()) "writing titles" else "polishing captions")
                            runCatching {
                                CaptionWriter.run(
                                    c.worker, settings.writingModel, analysis?.game, clip.title, clip.why,
                                    ((clip.endMs - clip.startMs) / 1000).toInt(), words,
                                )
                            }.onSuccess { r -> words = r.words; titles = r.titles; tags = r.hashtags }
                                .onFailure { e -> if (e is CancellationException) throw e; failures += "#${clip.rank}: titles/polish skipped (${e.message})" }
                        }
                        val fresh = db.clipDao().get(clip.id) ?: return@forEachIndexed
                        db.clipDao().update(
                            fresh.copy(
                                captionsJson = words.takeIf { it.isNotEmpty() }?.let { CaptionMath.encode(it) },
                                captionStatus = status,
                                captionLang = lang,
                                titleOptions = titles.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(ListSerializer(String.serializer()), it) } ?: fresh.titleOptions,
                                hashtags = tags.takeIf { it.isNotEmpty() }?.joinToString(" ") ?: fresh.hashtags,
                            )
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures += "#${clip.rank} ${clip.title}: ${e.message}"
                        db.clipDao().get(clip.id)?.let { db.clipDao().update(it.copy(captionStatus = "FAILED")) }
                    }
                }
            } finally {
                reporter.cancel()
                engine?.close()
            }
        }
        val out = workDataOf(KEY_ERROR to failures.joinToString("\n").ifBlank { null })
        return Result.success(out)
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
