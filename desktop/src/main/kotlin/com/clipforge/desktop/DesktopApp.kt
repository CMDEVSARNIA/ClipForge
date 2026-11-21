package com.clipforge.desktop

import com.clipforge.desktop.data.DesktopProject
import com.clipforge.desktop.data.Library
import com.clipforge.desktop.media.Ffmpeg
import com.clipforge.desktop.media.MediaTools
import com.clipforge.desktop.settings.DesktopSettings
import com.clipforge.net.WorkerClient
import com.clipforge.util.TimeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** App-wide state and services for the desktop app (one per process). */
class DesktopApp(dataDir: File, val ffmpeg: Ffmpeg?) {
    val library = Library(File(dataDir, "library.json"))
    val settings = DesktopSettings(File(dataDir, "settings.json"))
    val worker = WorkerClient(settings)
    val tools: MediaTools? = ffmpeg?.let { MediaTools(it) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val thumbDir = File(dataDir, "thumbs")

    data class ImportState(val busy: Boolean = false, val message: String? = null, val isError: Boolean = false)

    private val _import = MutableStateFlow(ImportState())
    val import: StateFlow<ImportState> = _import.asStateFlow()

    /** Set while a file is dragged over the window, for the drop overlay. */
    val dragging = MutableStateFlow(false)

    fun clearImportMessage() { _import.value = _import.value.copy(message = null) }

    /** Probes and adds each video; returns the last project added (to open it when only one was imported). */
    fun importFiles(files: List<File>, onDone: (DesktopProject?) -> Unit = {}) {
        val t = tools
        if (t == null) {
            _import.value = ImportState(message = "FFmpeg wasn't found, so videos can't be read. See Settings.", isError = true)
            return
        }
        val videos = files.filter { it.isFile && Library.isVideo(it) }
        if (videos.isEmpty()) {
            _import.value = ImportState(message = "That isn't a video file ClipForge can open (MP4, MKV, MOV, WebM, FLV…).", isError = true)
            return
        }
        scope.launch {
            val errors = ArrayList<String>()
            var last: DesktopProject? = null
            videos.forEachIndexed { i, f ->
                _import.value = ImportState(busy = true, message = "Reading ${f.name}" + if (videos.size > 1) " (${i + 1}/${videos.size})" else "")
                try {
                    val info = t.probe(f)
                    when {
                        info.durationMs <= 0 -> errors += "${f.name}: couldn't read its length."
                        info.durationMs > Library.MAX_DURATION_MS -> errors += "${f.name}: longer than 6 hours (${TimeFormat.format(info.durationMs)})."
                        else -> {
                            var p = library.add(f.path, info)
                            if (p.thumbPath == null) {
                                val thumb = File(thumbDir, "${p.id}.jpg")
                                val at = (info.durationMs / 10).coerceAtMost(30_000)
                                runCatching { t.thumbnail(f, at, 480, thumb) }.onSuccess {
                                    p = p.copy(thumbPath = it.path)
                                    library.update(p)
                                }
                            }
                            last = p
                        }
                    }
                } catch (e: Exception) {
                    errors += "${f.name}: ${e.message ?: "couldn't be read"}"
                }
            }
            _import.value = if (errors.isEmpty()) ImportState() else ImportState(message = errors.joinToString("\n"), isError = true)
            // Compose state is snapshot-safe, so the callback can update navigation from this thread.
            onDone(if (videos.size == 1) last else null)
        }
    }

    companion object {
        const val VERSION = "0.10.0"
    }
}
