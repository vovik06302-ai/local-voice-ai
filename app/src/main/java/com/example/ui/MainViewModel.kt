package com.example.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.local.AppDatabase
import com.example.data.local.ChatMessage
import com.example.data.local.SettingsRepository
import com.example.data.remote.GitHubUpdateManager
import com.example.data.remote.ReleaseInfo
import com.example.data.remote.UpdateCheckResult
import com.example.data.remote.WebSearchService
import com.example.domain.AiCoordinator
import com.example.domain.ConnectionStatus
import com.example.domain.GenerationResult
import com.example.domain.LocalLlmEngine
import com.example.domain.LocalModelManager
import com.example.domain.ModelDescriptor
import com.example.domain.ModelState
import com.example.ui.components.UpdateUiState
import com.example.voice.OfflineSpeechStatus
import com.example.voice.OfflineVoiceStatus
import com.example.voice.TextToSpeechManager
import com.example.voice.VoiceRecognitionManager
import com.example.voice.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

sealed class ProcessingState {
    object Idle : ProcessingState()
    object Thinking : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val chatDao = database.chatDao()
    val settingsRepo = SettingsRepository(application)
    val voiceRecognizer = VoiceRecognitionManager(application)
    val ttsManager = TextToSpeechManager(application)
    val webSearchService = WebSearchService(application)
    val localModelManager = LocalModelManager(application)
    val localLlmEngine = LocalLlmEngine(application, localModelManager)
    val aiCoordinator = AiCoordinator(settingsRepo, webSearchService, localLlmEngine)
    val updateManager = GitHubUpdateManager()

    val modelStates: StateFlow<Map<String, ModelState>> = localModelManager.modelState

    val messages: StateFlow<List<ChatMessage>> = chatDao.getAllMessages()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val settings = settingsRepo.settings

    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()

    val connectionStatus: StateFlow<ConnectionStatus> = aiCoordinator.connectionStatus
    val voiceState: StateFlow<VoiceState> = voiceRecognizer.voiceState
    val rmsDb: StateFlow<Float> = voiceRecognizer.rmsDb
    val partialSpeech: StateFlow<String> = voiceRecognizer.partialText
    val isSpeaking: StateFlow<Boolean> = ttsManager.isSpeaking

    val offlineVoiceStatus: StateFlow<OfflineVoiceStatus> = ttsManager.offlineVoiceStatus
    val offlineSpeechStatus: StateFlow<OfflineSpeechStatus> = voiceRecognizer.offlineSpeechStatus

    val appVersionName: String = BuildConfig.VERSION_NAME
    val appVersionCode: Int = BuildConfig.VERSION_CODE

    private val _updateUiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateUiState: StateFlow<UpdateUiState> = _updateUiState.asStateFlow()

    private var downloadedApkFile: File? = null

    init {
        // Initial health check in background
        viewModelScope.launch {
            aiCoordinator.checkServerConnection()
        }
    }

    fun getDeviceSpecs(): com.example.data.device.DeviceSpecs {
        return com.example.data.device.DeviceHardwareDetector.detect(getApplication())
    }

    fun downloadOnDeviceModel(desc: ModelDescriptor) {
        viewModelScope.launch {
            localModelManager.downloadModel(desc)
        }
    }

    fun deleteOnDeviceModel(desc: ModelDescriptor) {
        localModelManager.deleteModel(desc)
    }

    fun openTtsSettings() {
        ttsManager.openTtsSettings()
    }

    fun openVoiceInputSettings() {
        voiceRecognizer.openVoiceInputSettings()
    }

    fun onMicrophoneToggle(hasPermission: Boolean, onRequestPermission: () -> Unit) {
        if (!hasPermission) {
            onRequestPermission()
            return
        }

        // If TTS is currently speaking, stopping speech is often what the user wants first
        if (isSpeaking.value) {
            ttsManager.stopSpeaking()
        }

        if (voiceState.value is VoiceState.Listening) {
            voiceRecognizer.stopListening()
        } else {
            voiceRecognizer.startListening { recognizedText ->
                if (recognizedText.isNotBlank()) {
                    sendMessage(recognizedText)
                }
            }
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch {
            // 1. Insert user message to database
            val userMsg = ChatMessage(
                sender = "user",
                text = trimmed,
                source = "user"
            )
            chatDao.insertMessage(userMsg)

            _processingState.value = ProcessingState.Thinking

            // 2. Prepare conversation history for context
            val currentHistory = messages.value.takeLast(6).map {
                Pair(it.sender, it.text)
            }

            // 3. Process via Coordinator (PC or local On-Device fallback or Web Search)
            val result: GenerationResult = aiCoordinator.processQuery(trimmed, currentHistory)

            // 4. Insert assistant message
            val assistantMsg = ChatMessage(
                sender = "assistant",
                text = result.replyText,
                source = result.note
            )
            chatDao.insertMessage(assistantMsg)
            _processingState.value = ProcessingState.Idle

            // 5. Speak response if enabled (for web searches, speaks introductory summary)
            if (settings.value.autoSpeakResponses) {
                // If text is very long direct search results, speak first sentence or clean summary
                val speechText = if (result.source == "web") {
                    result.replyText.lines().take(4).joinToString(". ").replace(Regex("""https?://\S+"""), "")
                } else {
                    result.replyText
                }
                ttsManager.speak(
                    text = speechText,
                    speed = settings.value.ttsSpeed,
                    pitch = settings.value.ttsPitch
                )
            }
        }
    }

    fun speakText(text: String) {
        ttsManager.speak(
            text = text,
            speed = settings.value.ttsSpeed,
            pitch = settings.value.ttsPitch
        )
    }

    fun stopSpeaking() {
        ttsManager.stopSpeaking()
    }

    fun clearHistory() {
        viewModelScope.launch {
            ttsManager.stopSpeaking()
            chatDao.clearHistory()
        }
    }

    fun testPcConnection(url: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = aiCoordinator.checkServerConnection()
            onResult(res.first, res.second)
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _updateUiState.value = UpdateUiState.Checking
            val repo = settings.value.githubRepo
            when (val result = updateManager.checkLatestRelease(repo, appVersionName)) {
                is UpdateCheckResult.NewVersionAvailable -> {
                    _updateUiState.value = UpdateUiState.Available(result.release)
                }
                is UpdateCheckResult.AlreadyLatest -> {
                    _updateUiState.value = UpdateUiState.UpToDate(result.currentVersion)
                }
                is UpdateCheckResult.Error -> {
                    _updateUiState.value = UpdateUiState.Error(result.message)
                }
            }
        }
    }

    fun startDownloadUpdate(release: ReleaseInfo) {
        viewModelScope.launch {
            _updateUiState.value = UpdateUiState.Downloading(release, 0f)
            val (file, error) = updateManager.downloadApk(
                context = getApplication(),
                downloadUrl = release.apkDownloadUrl,
                fileName = release.apkFileName
            ) { progress ->
                _updateUiState.value = UpdateUiState.Downloading(release, progress)
            }

            if (file != null && error == null) {
                // Verify integrity
                val (isValid, integrityMsg) = updateManager.verifyApkIntegrity(
                    context = getApplication(),
                    apkFile = file,
                    expectedSha256 = release.expectedSha256
                )
                if (isValid) {
                    downloadedApkFile = file
                    _updateUiState.value = UpdateUiState.ReadyToInstall(release, integrityMsg)
                } else {
                    _updateUiState.value = UpdateUiState.Error("Ошибка проверки файла: $integrityMsg")
                }
            } else {
                _updateUiState.value = UpdateUiState.Error(error ?: "Ошибка загрузки APK")
            }
        }
    }

    fun installDownloadedApk(context: Context) {
        val file = downloadedApkFile
        if (file != null && file.exists()) {
            val (launched, error) = updateManager.launchApkInstall(context, file)
            if (!launched) {
                _updateUiState.value = UpdateUiState.Error(error ?: "Не удалось запустить установку")
            }
        } else {
            _updateUiState.value = UpdateUiState.Error("Файл обновления не найден на устройстве")
        }
    }

    fun cancelGeneration() {
        localLlmEngine.cancelGeneration()
        ttsManager.stopSpeaking()
        _processingState.value = ProcessingState.Idle
    }

    fun dismissUpdateDialog() {
        _updateUiState.value = UpdateUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        localLlmEngine.freeLoadedModel()
        voiceRecognizer.stopListening()
        ttsManager.shutdown()
    }
}
