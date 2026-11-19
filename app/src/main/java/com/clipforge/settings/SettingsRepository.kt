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
) {
    val isConnected: Boolean get() = workerUrl.isNotBlank() && appToken.isNotBlank()

    companion object {
        const val DEFAULT_ANALYSIS_MODEL = "claude-haiku-5-5"
        const val DEFAULT_WRITING_MODEL = "claude-sonnet-5-5"
    }
}

class SettingsRepository(private val context: Context) {
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
        )
    }

    suspend fun current(): AppSettings = settings.first()

    /** Random per-install id sent once at registration; lets the Worker revoke one install. */
    suspend fun installId(): String {
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

    suspend fun setExportFps(fps: Int) = context.dataStore.edit { it[Keys.exportFps] = if (fps > 30) 60 else 30 }
}
