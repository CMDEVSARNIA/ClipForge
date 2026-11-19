package com.clipforge.export

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.clipforge.ClipForgeApp
import kotlinx.coroutines.CancellationException

/** Exports clips one by one (shared by the export job and autopilot). */
@UnstableApi
class ExportPipeline(private val context: Context) {
    data class Outcome(val exported: Int, val failures: List<String>)

    suspend fun run(projectId: Long, clipIds: List<Long>, progress: (Float, String) -> Unit): Outcome {
        val c = (context.applicationContext as ClipForgeApp).container
        val project = c.database.projectDao().get(projectId) ?: return Outcome(0, listOf("Project not found"))
        val clips = c.database.clipDao().getAll(clipIds)
        val signals = c.signals.read(projectId)
        val cutsMs = signals?.cuts.orEmpty().map { (it * 1000).toLong() }
        val specialMs = signals?.specialFrames.orEmpty().map { (it.tS * 1000).toLong() }
        val settings = c.settings.current()
        val watermark = WatermarkStore(context).build(settings)
        val camRect = project.camPresetId?.let { c.database.camPresetDao().get(it) }?.let { NRect.fromCsv(it.rect) }
        val exporter = ClipExporter(context)
        val failures = ArrayList<String>()
        var exported = 0
        clips.forEachIndexed { i, clip ->
            val label = "Clip ${i + 1}/${clips.size}"
            progress(i.toFloat() / clips.size, label)
            try {
                val segs = exporter.resolveSegments(clip, cutsMs, specialMs)
                val layout = CamLayout.of(clip.camLayout)
                val cam = camRect?.takeIf { layout != CamLayout.NONE }?.let { CamSpec(it, layout, PipCorner.of(clip.pipCorner)) }
                val teaser = if (!clip.hookTeaser) null else HookTeaser.range(
                    clip.startMs, clip.endMs, clip.teaserStartMs, signals?.loudnessDb.orEmpty(), signals?.loudnessWindowS ?: 0.5,
                )
                val r = exporter.export(project, clip, segs, i + 1, settings.exportFps, cam, watermark, teaser) { p ->
                    progress((i + p) / clips.size, "$label · ${(p * 100).toInt()}%")
                }
                c.database.clipDao().markExported(clip.id, r.uri.toString(), System.currentTimeMillis())
                exported++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "#${clip.rank} ${clip.title}: ${e.message ?: e::class.simpleName}"
            }
        }
        return Outcome(exported, failures)
    }
}
