package com.clipforge.desktop.media

import com.clipforge.desktop.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Raised when FFmpeg is missing or a command fails. */
class FfmpegException(message: String) : Exception(message)

/**
 * Locates the bundled ffmpeg/ffprobe and runs them. Search order: the `clipforge.ffmpeg.dir` property,
 * the installer's app resources (`compose.application.resources.dir`), then the PATH.
 */
class Ffmpeg(val ffmpeg: File, val ffprobe: File) {

    /** Runs a short command and returns stdout bytes; throws on a non-zero exit. */
    suspend fun run(tool: File, args: List<String>, timeoutS: Long = 120): ByteArray = withContext(Dispatchers.IO) {
        val p = ProcessBuilder(listOf(tool.path) + args).redirectError(ProcessBuilder.Redirect.PIPE).start()
        try {
            val err = StringBuffer()
            val errReader = Thread { err.append(String(p.errorStream.readBytes()).takeLast(600)) }.apply { isDaemon = true; start() }
            val out = runInterruptible { p.inputStream.readBytes() }
            if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) throw FfmpegException("${tool.name} timed out")
            errReader.join(1000)
            if (p.exitValue() != 0) {
                throw FfmpegException("${tool.name} failed (${p.exitValue()}): ${err.toString().trim().lines().lastOrNull().orEmpty()}")
            }
            out
        } finally {
            p.destroyForcibly()
        }
    }

    /** Starts a long-running ffmpeg (preview playback) whose stdout the caller streams. */
    fun start(args: List<String>): Process =
        ProcessBuilder(listOf(ffmpeg.path, "-hide_banner", "-loglevel", "error", "-nostdin") + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

    suspend fun version(): String = runCatching {
        String(run(ffmpeg, listOf("-hide_banner", "-version"), 10)).lineSequence().firstOrNull().orEmpty()
    }.getOrDefault("")

    companion object {
        private fun exe(name: String) = if (AppDirs.os() == AppDirs.Os.WINDOWS) "$name.exe" else name

        fun candidates(): List<File> = buildList {
            System.getProperty("clipforge.ffmpeg.dir")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }?.let {
                add(File(it)); add(File(it, "ffmpeg"))
            }
            System.getenv("PATH")?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.forEach { add(File(it)) }
        }

        /** The first directory holding both tools, or null when FFmpeg isn't available. */
        fun locate(dirs: List<File> = candidates()): Ffmpeg? {
            for (d in dirs) {
                val f = File(d, exe("ffmpeg"))
                val p = File(d, exe("ffprobe"))
                if (f.isFile && p.isFile) return Ffmpeg(f, p)
            }
            return null
        }

        /** "12.5" style seconds for -ss/-t, always with a dot. */
        fun seconds(ms: Long): String = String.format(java.util.Locale.US, "%.3f", ms / 1000.0)
    }
}
