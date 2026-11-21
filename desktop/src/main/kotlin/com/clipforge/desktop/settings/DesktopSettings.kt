package com.clipforge.desktop.settings

import com.clipforge.net.WorkerConnection
import com.clipforge.net.WorkerEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class DesktopPrefs(
    val workerUrl: String = "",
    val appToken: String = "",
    val installId: String = "",
    val analysisModel: String = "claude-haiku-5-5",
    val writingModel: String = "claude-sonnet-5-5",
    val exportFps: Int = 60,
) {
    val isConnected: Boolean get() = workerUrl.isNotBlank() && appToken.isNotBlank()
}

/** settings.json in the app data folder. The app token is per install, like on Android; no Anthropic key ever. */
class DesktopSettings(private val file: File) : WorkerConnection {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val _prefs = MutableStateFlow(load())
    val prefs: StateFlow<DesktopPrefs> = _prefs.asStateFlow()

    private fun load(): DesktopPrefs = runCatching { json.decodeFromString(DesktopPrefs.serializer(), file.readText()) }.getOrDefault(DesktopPrefs())

    @Synchronized
    fun update(change: (DesktopPrefs) -> DesktopPrefs) {
        val next = change(_prefs.value)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(DesktopPrefs.serializer(), next))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        _prefs.value = next
    }

    override suspend fun endpoint(): WorkerEndpoint? = _prefs.value.takeIf { it.isConnected }?.let { WorkerEndpoint(it.workerUrl, it.appToken) }

    override suspend fun installId(): String {
        _prefs.value.installId.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        update { it.copy(installId = id) }
        return id
    }

    fun connect(url: String, token: String) = update { it.copy(workerUrl = url, appToken = token) }
    fun disconnect() = update { it.copy(appToken = "") }

    companion object {
        /** "clipforge-worker.x.workers.dev/" → "https://clipforge-worker.x.workers.dev". */
        fun normalizeUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (u.isEmpty()) return u
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            return u
        }
    }
}
