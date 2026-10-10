package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val pcServerUrl: String = "http://192.168.1.100:11434",
    val pcModel: String = "qwen2.5:7b",
    val autoFallbackEnabled: Boolean = true,
    val forceLocalOnly: Boolean = false,
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val autoSpeakResponses: Boolean = true,
    val githubRepo: String = "vovik06302/local-voice-ai",
    val searchApiKey: String = "",
    val selectedOnDeviceModelId: String = "qwen2.5-1.5b-q4"
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("voice_ai_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        return AppSettings(
            pcServerUrl = prefs.getString("pc_server_url", "http://192.168.1.100:11434")
                ?: "http://192.168.1.100:11434",
            pcModel = prefs.getString("pc_model", "qwen2.5:7b") ?: "qwen2.5:7b",
            autoFallbackEnabled = prefs.getBoolean("auto_fallback_enabled", true),
            forceLocalOnly = prefs.getBoolean("force_local_only", false),
            ttsSpeed = prefs.getFloat("tts_speed", 1.0f),
            ttsPitch = prefs.getFloat("tts_pitch", 1.0f),
            autoSpeakResponses = prefs.getBoolean("auto_speak_responses", true),
            githubRepo = prefs.getString("github_repo", "vovik06302/local-voice-ai")
                ?: "vovik06302/local-voice-ai",
            searchApiKey = prefs.getString("search_api_key", "") ?: "",
            selectedOnDeviceModelId = prefs.getString("selected_on_device_model_id", "qwen2.5-1.5b-q4")
                ?: "qwen2.5-1.5b-q4"
        )
    }

    fun updateSettings(
        pcServerUrl: String = _settings.value.pcServerUrl,
        pcModel: String = _settings.value.pcModel,
        autoFallbackEnabled: Boolean = _settings.value.autoFallbackEnabled,
        forceLocalOnly: Boolean = _settings.value.forceLocalOnly,
        ttsSpeed: Float = _settings.value.ttsSpeed,
        ttsPitch: Float = _settings.value.ttsPitch,
        autoSpeakResponses: Boolean = _settings.value.autoSpeakResponses,
        githubRepo: String = _settings.value.githubRepo,
        searchApiKey: String = _settings.value.searchApiKey,
        selectedOnDeviceModelId: String = _settings.value.selectedOnDeviceModelId
    ) {
        val updated = AppSettings(
            pcServerUrl = pcServerUrl.trim().trimEnd('/'),
            pcModel = pcModel.trim(),
            autoFallbackEnabled = autoFallbackEnabled,
            forceLocalOnly = forceLocalOnly,
            ttsSpeed = ttsSpeed,
            ttsPitch = ttsPitch,
            autoSpeakResponses = autoSpeakResponses,
            githubRepo = githubRepo.trim().removePrefix("https://github.com/").trim('/'),
            searchApiKey = searchApiKey.trim(),
            selectedOnDeviceModelId = selectedOnDeviceModelId
        )
        prefs.edit()
            .putString("pc_server_url", updated.pcServerUrl)
            .putString("pc_model", updated.pcModel)
            .putBoolean("auto_fallback_enabled", updated.autoFallbackEnabled)
            .putBoolean("force_local_only", updated.forceLocalOnly)
            .putFloat("tts_speed", updated.ttsSpeed)
            .putFloat("tts_pitch", updated.ttsPitch)
            .putBoolean("auto_speak_responses", updated.autoSpeakResponses)
            .putString("github_repo", updated.githubRepo)
            .putString("search_api_key", updated.searchApiKey)
            .putString("selected_on_device_model_id", updated.selectedOnDeviceModelId)
            .apply()

        _settings.value = updated
    }
}
