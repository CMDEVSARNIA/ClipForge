package com.clipforge.desktop.data

import com.clipforge.media.VideoInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** One imported video. The file stays where it is; ClipForge only remembers its path. */
@Serializable
data class DesktopProject(
    val id: Long,
    val path: String,
    val name: String,
    val info: VideoInfo,
    val addedAt: Long,
    val thumbPath: String? = null,
) {
    val file: File get() = File(path)
    val missing: Boolean get() = !file.isFile
}

/** The project list, kept in library.json in the app data folder (written atomically). */
class Library(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val ser = ListSerializer(DesktopProject.serializer())
    private val _projects = MutableStateFlow(load())
    val projects: StateFlow<List<DesktopProject>> = _projects.asStateFlow()

    private fun load(): List<DesktopProject> =
        runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList()).sortedByDescending { it.addedAt }

    fun get(id: Long): DesktopProject? = _projects.value.firstOrNull { it.id == id }

    /** Same path imported again returns the existing project instead of a duplicate. */
    fun findByPath(path: String): DesktopProject? = _projects.value.firstOrNull { File(it.path).absoluteFile == File(path).absoluteFile }

    @Synchronized
    fun add(path: String, info: VideoInfo, now: Long = System.currentTimeMillis()): DesktopProject {
        findByPath(path)?.let { return it }
        val id = (_projects.value.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(now) + 1
        val p = DesktopProject(id, File(path).absolutePath, File(path).nameWithoutExtension, info, now)
        save(listOf(p) + _projects.value)
        return p
    }

    @Synchronized
    fun update(p: DesktopProject) = save(_projects.value.map { if (it.id == p.id) p else it })

    @Synchronized
    fun remove(id: Long) {
        get(id)?.thumbPath?.let { File(it).delete() }
        save(_projects.value.filterNot { it.id == id })
    }

    private fun save(list: List<DesktopProject>) {
        val sorted = list.sortedByDescending { it.addedAt }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ser, sorted))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        _projects.value = sorted
    }

    companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "m4v", "webm", "flv", "avi", "ts", "mts", "m2ts", "wmv")

        /** Desktop handles long streams; anything past 6 hours is almost certainly a mistake. */
        const val MAX_DURATION_MS = 6L * 60 * 60 * 1000

        fun isVideo(f: File) = f.extension.lowercase() in VIDEO_EXTENSIONS
    }
}
