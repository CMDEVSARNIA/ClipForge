package com.clipforge.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/** One timed step. [mediaS] is how much video/audio it covered, for a realtime ratio. */
@Serializable
data class PerfStage(val name: String, val ms: Long, val mediaS: Double? = null)

/** The most recent run of one job ("Analysis", "Captions", "Export"). */
@Serializable
data class PerfRun(val kind: String, val at: Long, val stages: List<PerfStage>, val note: String? = null) {
    val totalMs: Long get() = stages.sumOf { it.ms }
}

object PerfFormat {
    fun seconds(ms: Long): String = String.format(Locale.US, "%.1f s", ms / 1000.0)

    /** "Export clip 1 · 18.0 s for 30 s of video (0.6× realtime)". */
    fun line(s: PerfStage): String {
        val base = "${s.name} · ${seconds(s.ms)}"
        val media = s.mediaS?.takeIf { it > 0 } ?: return base
        val ratio = s.ms / 1000.0 / media
        return base + String.format(Locale.US, " for %.0f s of video (%.2f× realtime)", media, ratio)
    }
}

/** Collects stages for one run; [mark] times the work since the previous mark. */
class PerfTimer(private val clock: () -> Long = System::currentTimeMillis) {
    private val stages = ArrayList<PerfStage>()
    private var last = clock()

    fun mark(name: String, mediaS: Double? = null) {
        val now = clock()
        synchronized(stages) { stages += PerfStage(name, now - last, mediaS) }
        last = now
    }

    /** Reset the stopwatch without recording (e.g. to skip time spent waiting on something else). */
    fun restart() { last = clock() }

    fun run(kind: String, note: String? = null) = PerfRun(kind, clock(), synchronized(stages) { stages.toList() }, note)
}

/** Keeps the latest run per kind in filesDir/perf.json, shown under Settings → Advanced. */
class PerfLog(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(PerfRun.serializer())

    @Synchronized
    fun all(): List<PerfRun> = runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    fun save(run: PerfRun) {
        if (run.stages.isEmpty()) return
        val next = (all().filter { it.kind != run.kind } + run).sortedBy { ORDER.indexOf(it.kind).let { i -> if (i < 0) 99 else i } }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ser, next))
            tmp.renameTo(file)
        }
    }

    companion object {
        private val ORDER = listOf("Analysis", "Captions", "Export")
        /** The log kept in an app's private files directory. */
        fun inDir(dir: File) = PerfLog(File(dir, "perf.json"))
    }
}
