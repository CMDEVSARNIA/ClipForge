package com.clipforge.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val workerUrl: String,
    /** Signed token from the Worker; empty until the app is connected. Not the Anthropic key. */
    val appToken: String,
    val analysisModel: String,
    val writingModel: String,
    val useTrends: Boolean,
    /** Max output frame rate (30 or 60); never above the source. */
    val exportFps: Int,
    /** whisper model: TINY or BASE. */
    val speechModel: String,
    /** "auto" or an ISO code like "en". */
    val captionLanguage: String,
    val watermarkEnabled: Boolean = false,
    /** e.g. "@yourhandle" */
    val watermarkText: String = "",
    val watermarkPosition: String = "MOVING",
    val watermarkOpacity: Float = 0.7f,
    val watermarkSize: String = "M",
    /** Defaults for new clips. */
    val defaultCaptionStyle: String = "CLASSIC",
    val defaultHookTeaser: Boolean = false,
    /** Autopilot: lowest tier exported (S, A or B), how many clips at most, and whether to caption. */
    val autopilotMinTier: String = "A",
    val autopilotMaxClips: Int = 5,
    val autopilotCaptions: Boolean = true,
    val autopilotAfterImport: Boolean = false,
    val onboardingDone: Boolean = false,
) {
    val isConnected: Boolean get() = workerUrl.isNotBlank() && appToken.isNotBlank()

    companion object {
        const val DEFAULT_ANALYSIS_MODEL = "claude-haiku-5-5"
        const val DEFAULT_WRITING_MODEL = "claude-sonnet-5-5"
    }
}

class SettingsRepository(private val context: Context) : com.clipforge.net.WorkerConnection {
    private object Keys {
        val workerUrl = stringPreferencesKey("worker_url")
        val appToken = stringPreferencesKey("app_token")
        val installId = stringPreferencesKey("install_id")
        val analysisModel = stringPreferencesKey("analysis_model")
        val writingModel = stringPreferencesKey("writing_model")
        val useTrends = booleanPreferencesKey("use_trends")
        val exportFps = androidx.datastore.preferences.core.intPreferencesKey("export_fps")
        val speechModel = stringPreferencesKey("speech_model")
        val captionLanguage = stringPreferencesKey("caption_language")
        val wmEnabled = booleanPreferencesKey("wm_enabled")
        val wmText = stringPreferencesKey("wm_text")
        val wmPosition = stringPreferencesKey("wm_position")
        val wmOpacity = androidx.datastore.preferences.core.floatPreferencesKey("wm_opacity")
        val wmSize = stringPreferencesKey("wm_size")
        val defCaptionStyle = stringPreferencesKey("def_caption_style")
        val defHookTeaser = booleanPreferencesKey("def_hook_teaser")
        val apMinTier = stringPreferencesKey("ap_min_tier")
        val apMax = androidx.datastore.preferences.core.intPreferencesKey("ap_max")
        val apCaptions = booleanPreferencesKey("ap_captions")
        val apAfterImport = booleanPreferencesKey("ap_after_import")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            workerUrl = p[Keys.workerUrl].orEmpty(),
            appToken = p[Keys.appToken].orEmpty(),
            analysisModel = p[Keys.analysisModel] ?: AppSettings.DEFAULT_ANALYSIS_MODEL,
            writingModel = p[Keys.writingModel] ?: AppSettings.DEFAULT_WRITING_MODEL,
            useTrends = p[Keys.useTrends] ?: true,
            exportFps = p[Keys.exportFps] ?: 60,
            speechModel = p[Keys.speechModel] ?: "TINY",
            captionLanguage = p[Keys.captionLanguage] ?: "auto",
            watermarkEnabled = p[Keys.wmEnabled] ?: false,
            watermarkText = p[Keys.wmText].orEmpty(),
            watermarkPosition = p[Keys.wmPosition] ?: "MOVING",
            watermarkOpacity = p[Keys.wmOpacity] ?: 0.7f,
            watermarkSize = p[Keys.wmSize] ?: "M",
            defaultCaptionStyle = p[Keys.defCaptionStyle] ?: "CLASSIC",
            defaultHookTeaser = p[Keys.defHookTeaser] ?: false,
            autopilotMinTier = p[Keys.apMinTier] ?: "A",
            autopilotMaxClips = p[Keys.apMax] ?: 5,
            autopilotCaptions = p[Keys.apCaptions] ?: true,
            autopilotAfterImport = p[Keys.apAfterImport] ?: false,
            onboardingDone = p[Keys.onboardingDone] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    /** Random per-install id sent once at registration; lets the Worker revoke one install. */
    override suspend fun endpoint(): com.clipforge.net.WorkerEndpoint? =
        current().takeIf { it.isConnected }?.let { com.clipforge.net.WorkerEndpoint(it.workerUrl, it.appToken) }

    override suspend fun installId(): String {
        context.dataStore.data.first()[Keys.installId]?.let { return it }
        val id = UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.installId] = id }
        return id
    }

    suspend fun saveConnection(url: String, token: String) = context.dataStore.edit {
        it[Keys.workerUrl] = url
        it[Keys.appToken] = token
    }

    suspend fun disconnect() = context.dataStore.edit { it.remove(Keys.appToken) }

    suspend fun saveModels(analysis: String, writing: String) = context.dataStore.edit {
        it[Keys.analysisModel] = analysis.trim().ifBlank { AppSettings.DEFAULT_ANALYSIS_MODEL }
        it[Keys.writingModel] = writing.trim().ifBlank { AppSettings.DEFAULT_WRITING_MODEL }
    }

    suspend fun setUseTrends(enabled: Boolean) = context.dataStore.edit { it[Keys.useTrends] = enabled }

    suspend fun setSpeechModel(m: String) = context.dataStore.edit { it[Keys.speechModel] = m }
    suspend fun setCaptionLanguage(l: String) = context.dataStore.edit { it[Keys.captionLanguage] = l }

    suspend fun setWatermark(
        enabled: Boolean? = null, text: String? = null, position: String? = null, opacity: Float? = null, size: String? = null,
    ) = context.dataStore.edit { p ->
        enabled?.let { p[Keys.wmEnabled] = it }
        text?.let { p[Keys.wmText] = it.take(40) }
        position?.let { p[Keys.wmPosition] = it }
        opacity?.let { p[Keys.wmOpacity] = it.coerceIn(0.2f, 1f) }
        size?.let { p[Keys.wmSize] = it }
    }

    suspend fun setDefaults(captionStyle: String? = null, hookTeaser: Boolean? = null) = context.dataStore.edit { p ->
        captionStyle?.let { p[Keys.defCaptionStyle] = it }
        hookTeaser?.let { p[Keys.defHookTeaser] = it }
    }

    suspend fun setAutopilot(minTier: String? = null, maxClips: Int? = null, captions: Boolean? = null, afterImport: Boolean? = null) =
        context.dataStore.edit { p ->
            minTier?.let { p[Keys.apMinTier] = it }
            maxClips?.let { p[Keys.apMax] = it.coerceIn(1, 20) }
            captions?.let { p[Keys.apCaptions] = it }
            afterImport?.let { p[Keys.apAfterImport] = it }
        }

    suspend fun setOnboardingDone() = context.dataStore.edit { it[Keys.onboardingDone] = true }

    suspend fun setExportFps(fps: Int) = context.dataStore.edit { it[Keys.exportFps] = if (fps > 30) 60 else 30 }
}
