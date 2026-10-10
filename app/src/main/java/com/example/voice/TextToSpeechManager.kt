package com.example.voice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class OfflineVoiceStatus(
    val isInstalled: Boolean,
    val isOfflineReady: Boolean,
    val details: String
)

class TextToSpeechManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _offlineVoiceStatus = MutableStateFlow(
        OfflineVoiceStatus(
            isInstalled = false,
            isOfflineReady = false,
            details = "Проверка наличия русского голоса..."
        )
    )
    val offlineVoiceStatus: StateFlow<OfflineVoiceStatus> = _offlineVoiceStatus.asStateFlow()

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val russianLocale = Locale("ru", "RU")
            val langResult = tts?.setLanguage(russianLocale)
            val langSupported = langResult != TextToSpeech.LANG_MISSING_DATA && langResult != TextToSpeech.LANG_NOT_SUPPORTED

            _isAvailable.value = langSupported
            inspectRussianOfflineVoices(langSupported)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _isSpeaking.value = false
                }
            })

            isInitialized = true
        } else {
            _isAvailable.value = false
            _offlineVoiceStatus.value = OfflineVoiceStatus(
                isInstalled = false,
                isOfflineReady = false,
                details = "Движок TTS Android не инициализирован (код ошибки: $status)."
            )
            isInitialized = false
        }
    }

    private fun inspectRussianOfflineVoices(langSupported: Boolean) {
        val currentTts = tts ?: return
        try {
            val voices = currentTts.voices
            if (voices == null || voices.isEmpty()) {
                _offlineVoiceStatus.value = OfflineVoiceStatus(
                    isInstalled = langSupported,
                    isOfflineReady = false,
                    details = if (langSupported) "Язык поддерживается, но список голосов недоступен." else "Русский голос не найден."
                )
                return
            }

            val ruVoices = voices.filter { it.locale.language == "ru" }
            if (ruVoices.isEmpty()) {
                _offlineVoiceStatus.value = OfflineVoiceStatus(
                    isInstalled = false,
                    isOfflineReady = false,
                    details = "Русский голосовой пакет не установлен в TTS Android."
                )
                return
            }

            // Check if there is at least one voice that does NOT require network connection and has no missing data
            val offlineRuVoice = ruVoices.firstOrNull { voice ->
                val notNetwork = !voice.isNetworkConnectionRequired
                val noMissingData = !voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                notNetwork && noMissingData
            }

            if (offlineRuVoice != null) {
                // Set the offline voice explicitly
                currentTts.voice = offlineRuVoice
                _offlineVoiceStatus.value = OfflineVoiceStatus(
                    isInstalled = true,
                    isOfflineReady = true,
                    details = "Локальный русский голос (${offlineRuVoice.name}) установлен, не требует сети (isNetworkConnectionRequired=false) и готов к работе в авиарежиме."
                )
            } else {
                _offlineVoiceStatus.value = OfflineVoiceStatus(
                    isInstalled = true,
                    isOfflineReady = false,
                    details = "Установленные голоса требуют сеть (isNetworkConnectionRequired=true) или не загружены полностью. Откройте настройки и скачайте офлайн-пакет."
                )
            }
        } catch (e: Exception) {
            _offlineVoiceStatus.value = OfflineVoiceStatus(
                isInstalled = langSupported,
                isOfflineReady = false,
                details = "Ошибка проверки голоса: ${e.localizedMessage}"
            )
        }
    }

    fun refreshVoiceStatus() {
        if (isInitialized && tts != null) {
            val russianLocale = Locale("ru", "RU")
            val langResult = tts?.setLanguage(russianLocale)
            val langSupported = langResult != TextToSpeech.LANG_MISSING_DATA && langResult != TextToSpeech.LANG_NOT_SUPPORTED
            inspectRussianOfflineVoices(langSupported)
        }
    }

    fun openTtsSettings() {
        try {
            val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun speak(text: String, speed: Float = 1.0f, pitch: Float = 1.0f) {
        if (!isInitialized || tts == null) return

        stopSpeaking()

        tts?.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))

        val utteranceId = "tts_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stopSpeaking() {
        if (tts != null) {
            tts?.stop()
            _isSpeaking.value = false
        }
    }

    fun shutdown() {
        stopSpeaking()
        tts?.shutdown()
        tts = null
    }
}
