package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed class VoiceState {
    object Idle : VoiceState()
    object Listening : VoiceState()
    data class Recognized(val text: String) : VoiceState()
    data class Error(val message: String) : VoiceState()
}

data class OfflineSpeechStatus(
    val isAvailable: Boolean,
    val details: String
)

class VoiceRecognitionManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null

    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _rmsDb = MutableStateFlow(0f)
    val rmsDb: StateFlow<Float> = _rmsDb.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    private fun checkOfflineStatus(): OfflineSpeechStatus {
        val generalAvail = SpeechRecognizer.isRecognitionAvailable(context)
        if (!generalAvail) {
            return OfflineSpeechStatus(
                isAvailable = false,
                details = "Служба распознавания речи не найдена на устройстве."
            )
        }
        val onDeviceAvail = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } else {
            false
        }
        return if (onDeviceAvail) {
            OfflineSpeechStatus(
                isAvailable = true,
                details = "Аппаратное On-Device распознавание речи поддерживается системой Android."
            )
        } else {
            OfflineSpeechStatus(
                isAvailable = true,
                details = "EXTRA_PREFER_OFFLINE запрашивает локальное распознавание речи. Требуется загруженный русский языковой пакет в настройках голосового ввода Google."
            )
        }
    }

    private val _offlineSpeechStatus = MutableStateFlow(checkOfflineStatus())
    val offlineSpeechStatus: StateFlow<OfflineSpeechStatus> = _offlineSpeechStatus.asStateFlow()

    fun openVoiceInputSettings() {
        try {
            val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val altIntent = Intent(Settings.ACTION_LOCALE_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(altIntent)
            } catch (_: Exception) {}
        }
    }

    fun startListening(onResult: (String) -> Unit) {
        if (!isAvailable) {
            _voiceState.value = VoiceState.Error("Распознавание речи не поддерживается на этом устройстве")
            return
        }

        stopListening()

        try {
            val recognizer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            speechRecognizer = recognizer.apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _voiceState.value = VoiceState.Listening
                        _partialText.value = ""
                    }

                    override fun onBeginningOfSpeech() {
                        _voiceState.value = VoiceState.Listening
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        _rmsDb.value = rmsdB
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        _voiceState.value = VoiceState.Idle
                    }

                    override fun onError(error: Int) {
                        val errorMessage = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Ошибка аудиозаписи"
                            SpeechRecognizer.ERROR_CLIENT -> "Ошибка клиента распознавания"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на запись аудио"
                            SpeechRecognizer.ERROR_NETWORK -> "Ошибка сети. Для полной автономности загрузите русский офлайн-пакет в настройках голосового ввода Android."
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Таймаут соединения"
                            SpeechRecognizer.ERROR_NO_MATCH -> "Речь не распознана, повторите ещё раз"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Движок распознавания занят"
                            SpeechRecognizer.ERROR_SERVER -> "Ошибка сервера распознавания"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не услышал речь, попробуйте снова"
                            else -> "Ошибка распознавания ($error)"
                        }
                        _voiceState.value = VoiceState.Error(errorMessage)
                        _rmsDb.value = 0f
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        _voiceState.value = VoiceState.Idle
                        _partialText.value = ""
                        _rmsDb.value = 0f
                        if (text.isNotBlank()) {
                            onResult(text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        if (text.isNotBlank()) {
                            _partialText.value = text
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Note: EXTRA_PREFER_OFFLINE is a preference hint to Android SpeechRecognizer
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            _voiceState.value = VoiceState.Error("Сбой запуска микрофона: ${e.localizedMessage}")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        _voiceState.value = VoiceState.Idle
        _rmsDb.value = 0f
        _partialText.value = ""
    }
}
