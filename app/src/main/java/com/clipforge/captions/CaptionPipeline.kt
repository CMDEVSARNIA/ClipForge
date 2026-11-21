package com.clipforge.captions

import android.content.Context
import androidx.core.net.toUri
import com.clipforge.ClipForgeApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import com.clipforge.util.PerfLog
import com.clipforge.util.PerfTimer

/**
 * Captions + titles for clips (shared by the captions job and autopilot):
 * whisper on the device → speech check → Claude polish, 3 titles, 5 hashtags → saved on each clip.
 */
class CaptionPipeline(private val context: Context) {

    suspend fun run(projectId: Long, clipIds: List<Long>, progress: (Float, String) -> Unit): List<String> {
        val c = (context.applicationContext as ClipForgeApp).container
        val db = c.database
        val project = db.projectDao().get(projectId) ?: return listOf("Project not found")
        val clips = db.clipDao().getAll(clipIds)
        val settings = c.settings.current()
        val analysis = db.analysisDao().latest(projectId)
        val model = SpeechModel.of(settings.speechModel)
        val store = SpeechModelStore(context)
        val extractor = PcmExtractor(context)
        val failures = ArrayList<String>()
        var engine: WhisperEngine? = null
        val perf = PerfTimer()
        try {
            if (project.hasAudio && !store.isReady(model)) {
                store.ensure(model) { f -> progress(0f, "Downloading speech model ${(f * 100).toInt()}%") }
            }
            perf.restart()
            clips.forEachIndexed { i, clip ->
                val label = "Clip ${i + 1}/${clips.size}"
                fun step(frac: Float, what: String) = progress((i + frac) / clips.size, "$label · $what")
                try {
                    var words = emptyList<CaptionWord>()
                    var status = "NO_AUDIO"
                    var lang: String? = null
                    if (project.hasAudio) {
                        step(0.05f, "reading audio")
                        val pcm = extractor.extract16kMono(project.sourceUri.toUri(), clip.startMs, clip.endMs)
                        if (pcm != null && pcm.isNotEmpty()) {
                            step(0.2f, "transcribing")
                            val eng = engine ?: withContext(Dispatchers.IO) { WhisperEngine.load(store.file(model)) }.also { engine = it; perf.mark("Load ${model.label}") }
                            perf.restart()
                            val result = withContext(Dispatchers.Default) { eng.transcribe(pcm, settings.captionLanguage) }
                                ?: throw IllegalStateException("Transcription failed")
                            perf.mark("Transcribe clip ${i + 1}", pcm.size / 16000.0)
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
            engine?.close()
            if (engine != null) PerfLog.inDir(context.filesDir).save(perf.run("Captions", "${model.label}, ${WhisperNative.variant} engine"))
        }
        return failures
    }
}
