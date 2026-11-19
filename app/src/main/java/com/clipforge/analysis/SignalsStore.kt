package com.clipforge.analysis

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Raw per-video signals kept for the trim editor (cut markers) and Phase 3 (auto crop, black-frame trim). */
@Serializable
data class AnalysisSignals(
    val sampleIntervalS: Double,
    val loudnessWindowS: Double,
    val loudnessDb: List<Float>,
    val cuts: List<Double>,
    val specialFrames: List<SpecialFrame>,
    val blackFrames: List<Double>,
)

class SignalsStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(projectId: Long) = File(context.filesDir, "analysis/$projectId/signals.json")

    fun write(projectId: Long, s: AnalysisSignals) {
        val f = file(projectId)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "signals.json.tmp")
        tmp.writeText(json.encodeToString(AnalysisSignals.serializer(), s))
        tmp.renameTo(f)
    }

    fun read(projectId: Long): AnalysisSignals? = runCatching {
        json.decodeFromString(AnalysisSignals.serializer(), file(projectId).readText())
    }.getOrNull()

    fun delete(projectId: Long) {
        File(context.filesDir, "analysis/$projectId").deleteRecursively()
    }
}
