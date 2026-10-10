package com.example.domain

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class LlmGenerationResult(
    val text: String,
    val durationSeconds: Double,
    val tokensPerSecond: Double,
    val modelName: String,
    val isRealGgufActive: Boolean,
    val modelWeightsMb: Double,
    val memoryUsageMb: Double,
    val nativeHeapMb: Double,
    val errorMessage: String? = null
)

class LocalLlmEngine(
    private val context: Context,
    private val modelManager: LocalModelManager
) {
    companion object {
        private const val TAG = "LocalLlmEngine"
        private const val DEFAULT_CTX_TOKENS = 2048
        private const val DEFAULT_MAX_TOKENS = 512
        private const val DEFAULT_CPU_THREADS = 4 // Cortex-A76 performance cores on Helio G99
    }

    private var activeSessionHandle: Long = 0L
    private var activeLoadedModelId: String? = null
    private var modelWeightsBytes: Long = 0L
    private var isGenerating = false

    @Synchronized
    fun isModelLoaded(): Boolean = activeSessionHandle > 0L

    @Synchronized
    fun getActiveModelId(): String? = activeLoadedModelId

    @Synchronized
    fun getModelWeightsBytes(): Long = modelWeightsBytes

    suspend fun prepareModel(descriptor: ModelDescriptor): Pair<Boolean, String> = withContext(Dispatchers.Default) {
        val modelFile = modelManager.getModelFile(descriptor)
            ?: return@withContext Pair(
                false,
                "Файл модели ${descriptor.name} не найден на телефоне. Скачайте модель в Настройках."
            )

        // 1. RAM Safety Check: Weights + KV-Cache + Buffers
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val requiredRamBytes = descriptor.estimatedWeightsRamBytes +
                descriptor.estimatedKvCacheBytes +
                descriptor.estimatedWorkingBufferBytes
        val requiredRamMb = requiredRamBytes / (1024.0 * 1024.0)
        val availRamMb = memInfo.availMem / (1024.0 * 1024.0)

        if (memInfo.availMem < requiredRamBytes) {
            val errMsg = "Недостаточно свободной RAM! Доступно: ${String.format(Locale.US, "%.0f", availRamMb)} МБ, требуется: ${String.format(Locale.US, "%.0f", requiredRamMb)} МБ. Закройте фоновые приложения или используйте модель 0.5B."
            Log.e(TAG, errMsg)
            return@withContext Pair(false, errMsg)
        }

        // If already loaded the same model, reuse
        if (activeSessionHandle > 0L && activeLoadedModelId == descriptor.id) {
            return@withContext Pair(true, "Модель ${descriptor.name} уже загружена в память.")
        }

        // Free previous loaded session if any
        freeLoadedModel()

        // 2. Load via Native JNI Bridge
        if (!LlamaBridge.isNativeLoaded()) {
            val errorDetails = LlamaBridge.getLoadError() ?: "libllama-android.so не скомпилирована в текущем окружении"
            return@withContext Pair(
                false,
                "Нативный модуль llama.cpp недоступен: $errorDetails.\nДля сборки с нативным кодом запустите GitHub Action «Build Signed APK»."
            )
        }

        try {
            val handle = LlamaBridge.nativeInit(
                modelPath = modelFile.absolutePath,
                nThreads = DEFAULT_CPU_THREADS,
                nCtx = DEFAULT_CTX_TOKENS
            )

            if (handle <= 0L) {
                return@withContext Pair(
                    false,
                    "Ошибка загрузки модели GGUF через llama.cpp (код ошибки: $handle)."
                )
            }

            activeSessionHandle = handle
            activeLoadedModelId = descriptor.id
            modelWeightsBytes = LlamaBridge.nativeGetModelSizeBytes(handle)

            Log.i(TAG, "Model ${descriptor.name} successfully loaded into memory, handle=$handle, weights=$modelWeightsBytes bytes")
            Pair(true, "Модель ${descriptor.name} успешно загружена в память устройства.")
        } catch (e: Throwable) {
            Log.e(TAG, "Exception during nativeInit: ${e.message}", e)
            Pair(false, "Исключение при инициализации llama.cpp: ${e.localizedMessage}")
        }
    }

    suspend fun generate(
        query: String,
        selectedModelId: String,
        history: List<Pair<String, String>>,
        onTokenCallback: ((String) -> Boolean)? = null
    ): LlmGenerationResult = withContext(Dispatchers.Default) {
        val descriptor = ModelCatalog.getById(selectedModelId)
        if (!isModelLoaded() || activeLoadedModelId != selectedModelId) {
            val (success, message) = prepareModel(descriptor)
            if (!success) {
                return@withContext LlmGenerationResult(
                    text = message,
                    durationSeconds = 0.0,
                    tokensPerSecond = 0.0,
                    modelName = descriptor.name,
                    isRealGgufActive = false,
                    modelWeightsMb = 0.0,
                    memoryUsageMb = 0.0,
                    nativeHeapMb = 0.0,
                    errorMessage = message
                )
            }
        }

        generateResponse(
            prompt = query,
            history = history,
            onTokenCallback = onTokenCallback
        )
    }

    suspend fun generateResponse(
        prompt: String,
        systemPrompt: String = "Ты — персональный русскоязычный голосовой ассистент на телефоне Infinix Note 30. Отвечай кратко, чётко, грамотно и по делу на русском языке.",
        history: List<Pair<String, String>> = emptyList(),
        onTokenCallback: ((String) -> Boolean)? = null
    ): LlmGenerationResult = withContext(Dispatchers.Default) {
        val modelDesc = activeLoadedModelId?.let { ModelCatalog.getById(it) } ?: ModelCatalog.QWEN_1_5B
        val startTime = System.currentTimeMillis()
        isGenerating = true

        try {
            val formattedPrompt = if (activeSessionHandle > 0L && LlamaBridge.isNativeLoaded()) {
                val userHistory = history.map { it.first }.toTypedArray()
                val assistantHistory = history.map { it.second }.toTypedArray()
                LlamaBridge.nativeApplyChatTemplate(
                    handle = activeSessionHandle,
                    systemPrompt = systemPrompt,
                    userHistory = userHistory,
                    assistantHistory = assistantHistory,
                    userQuery = prompt
                )
            } else {
                buildFallbackChatMl(systemPrompt, history, prompt)
            }

            val generatedText = if (activeSessionHandle > 0L && LlamaBridge.isNativeLoaded()) {
                LlamaBridge.nativeGenerate(
                    handle = activeSessionHandle,
                    prompt = formattedPrompt,
                    maxTokens = DEFAULT_MAX_TOKENS,
                    callback = if (onTokenCallback != null) {
                        LlamaTokenCallback { token -> onTokenCallback(token) }
                    } else null
                )
            } else {
                "Нативный бинарный модуль llama.cpp не был скомпилирован в этом тестовом окружении. Для запуска реальной инференс-генерации соберите APK через GitHub Actions со скомпилированным NDK libllama-android.so."
            }

            val durationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
            val durationSec = durationMs / 1000.0

            // Measure actual memory consumption on device
            val nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize()
            val nativeHeapMb = nativeHeapAllocatedBytes / (1024.0 * 1024.0)

            val pssKb = Debug.getPss()
            val pssMb = pssKb / 1024.0

            val weightsMb = modelWeightsBytes / (1024.0 * 1024.0)

            val approxTokens = (generatedText.length / 3.3).toInt().coerceAtLeast(1)
            val tps = approxTokens / durationSec

            LlmGenerationResult(
                text = generatedText,
                durationSeconds = durationSec,
                tokensPerSecond = tps,
                modelName = modelDesc.name,
                isRealGgufActive = LlamaBridge.isNativeLoaded() && activeSessionHandle > 0L,
                modelWeightsMb = weightsMb,
                memoryUsageMb = pssMb,
                nativeHeapMb = nativeHeapMb
            )
        } catch (e: Exception) {
            Log.e(TAG, "Generation error: ${e.message}", e)
            LlmGenerationResult(
                text = "Ошибка генерации локальной модели: ${e.localizedMessage}",
                durationSeconds = 0.0,
                tokensPerSecond = 0.0,
                modelName = modelDesc.name,
                isRealGgufActive = false,
                modelWeightsMb = 0.0,
                memoryUsageMb = 0.0,
                nativeHeapMb = 0.0,
                errorMessage = e.localizedMessage
            )
        } finally {
            isGenerating = false
        }
    }

    fun cancelGeneration() {
        if (activeSessionHandle > 0L && LlamaBridge.isNativeLoaded()) {
            LlamaBridge.nativeCancel(activeSessionHandle)
            Log.i(TAG, "Cancellation signal sent to native llama session")
        }
        isGenerating = false
    }

    @Synchronized
    fun freeLoadedModel() {
        if (activeSessionHandle > 0L) {
            if (LlamaBridge.isNativeLoaded()) {
                LlamaBridge.nativeFree(activeSessionHandle)
            }
            activeSessionHandle = 0L
            activeLoadedModelId = null
            modelWeightsBytes = 0L
            Log.i(TAG, "Loaded model session freed from memory")
        }
    }

    private fun buildFallbackChatMl(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        prompt: String
    ): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n$systemPrompt<|im_end|>\n")
        history.takeLast(4).forEach { (role, content) ->
            sb.append("<|im_start|>$role\n$content<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n")
        return sb.toString()
    }
}
