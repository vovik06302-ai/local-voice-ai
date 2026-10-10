package com.example.domain

import android.content.Context
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed class ModelState {
    object NotDownloaded : ModelState()
    object CheckingStorage : ModelState()
    data class Downloading(
        val progress: Float,
        val downloadedMb: Double,
        val totalMb: Double
    ) : ModelState()
    data class VerifyingSha256(val progress: Float = 0f) : ModelState()
    data class Ready(val file: File, val sizeMb: Double, val sha256: String) : ModelState()
    data class Error(val message: String) : ModelState()
}

class LocalModelManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    private val _modelState = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val modelState: StateFlow<Map<String, ModelState>> = _modelState.asStateFlow()

    init {
        refreshAllStates()
    }

    fun refreshAllStates() {
        val currentMap = mutableMapOf<String, ModelState>()
        ModelCatalog.AVAILABLE_MODELS.forEach { desc ->
            val file = File(modelsDir, desc.fileName)
            if (file.exists() && file.length() >= 10_000_000L) {
                if (verifyGgufHeader(file)) {
                    val sizeMb = file.length() / (1024.0 * 1024.0)
                    currentMap[desc.id] = ModelState.Ready(file, sizeMb, desc.expectedSha256)
                } else {
                    currentMap[desc.id] = ModelState.NotDownloaded
                }
            } else {
                currentMap[desc.id] = ModelState.NotDownloaded
            }
        }
        _modelState.value = currentMap
    }

    fun isModelReady(descriptor: ModelDescriptor): Boolean {
        val file = File(modelsDir, descriptor.fileName)
        return file.exists() && file.length() >= 10_000_000L && verifyGgufHeader(file)
    }

    fun getModelFile(descriptor: ModelDescriptor): File? {
        val file = File(modelsDir, descriptor.fileName)
        return if (file.exists() && verifyGgufHeader(file)) file else null
    }

    suspend fun downloadModel(descriptor: ModelDescriptor) = withContext(Dispatchers.IO) {
        val statFs = try {
            StatFs(context.filesDir.absolutePath)
        } catch (_: Exception) {
            StatFs(Environment.getDataDirectory().absolutePath)
        }
        val freeBytes = statFs.availableBlocksLong * statFs.blockSizeLong

        // 1. Check free storage
        if (freeBytes < descriptor.requiredStorageBytes) {
            val freeMb = freeBytes / (1024.0 * 1024.0)
            val reqMb = descriptor.requiredStorageBytes / (1024.0 * 1024.0)
            updateState(
                descriptor.id,
                ModelState.Error(
                    "Недостаточно места на накопителе. Доступно: ${String.format(Locale.US, "%.0f", freeMb)} МБ, требуется: ${String.format(Locale.US, "%.0f", reqMb)} МБ."
                )
            )
            return@withContext
        }

        updateState(
            descriptor.id,
            ModelState.Downloading(0f, 0.0, descriptor.downloadSizeMb)
        )

        val targetFile = File(modelsDir, descriptor.fileName)
        val partFile = File(modelsDir, "${descriptor.fileName}.part")
        if (partFile.exists()) partFile.delete()

        val digest = MessageDigest.getInstance("SHA-256")

        try {
            val request = Request.Builder()
                .url(descriptor.downloadUrl)
                .header("User-Agent", "VoiceAI-Local-Engine/1.2")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    updateState(
                        descriptor.id,
                        ModelState.Error("Ошибка сервера загрузки: HTTP ${response.code} (${response.message})")
                    )
                    return@withContext
                }

                val body = response.body ?: run {
                    updateState(descriptor.id, ModelState.Error("Пустой ответ от сервера модели"))
                    return@withContext
                }

                val totalLength = if (body.contentLength() > 0) body.contentLength() else descriptor.downloadSizeBytes
                val totalMb = totalLength / (1024.0 * 1024.0)
                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(partFile)

                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var downloadedBytes: Long = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    digest.update(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val progress = (downloadedBytes.toDouble() / totalLength.toDouble()).toFloat().coerceIn(0f, 1f)
                    val downloadedMb = downloadedBytes / (1024.0 * 1024.0)

                    updateState(
                        descriptor.id,
                        ModelState.Downloading(progress, downloadedMb, totalMb)
                    )
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()
            }

            // 2. Strict SHA-256 and GGUF verification
            updateState(descriptor.id, ModelState.VerifyingSha256())

            if (!partFile.exists() || partFile.length() < 10_000_000L) {
                partFile.delete()
                updateState(descriptor.id, ModelState.Error("Файл модели загружен не полностью."))
                return@withContext
            }

            // Verify GGUF magic bytes
            if (!verifyGgufHeader(partFile)) {
                partFile.delete()
                updateState(descriptor.id, ModelState.Error("Ошибка заголовка GGUF: файл повреждён."))
                return@withContext
            }

            // Calculate actual SHA-256 hex string
            val hashBytes = digest.digest()
            val computedSha256 = hashBytes.joinToString("") { "%02x".format(it) }.lowercase(Locale.US)
            val expectedSha256 = descriptor.expectedSha256.lowercase(Locale.US)

            if (computedSha256 != expectedSha256) {
                partFile.delete()
                updateState(
                    descriptor.id,
                    ModelState.Error(
                        "Несовпадение SHA-256! Ожидалось: ${expectedSha256.take(8)}..., получено: ${computedSha256.take(8)}... Повреждённый файл удалён."
                    )
                )
                return@withContext
            }

            if (targetFile.exists()) targetFile.delete()
            partFile.renameTo(targetFile)

            val finalSizeMb = targetFile.length() / (1024.0 * 1024.0)
            updateState(descriptor.id, ModelState.Ready(targetFile, finalSizeMb, computedSha256))

        } catch (e: Exception) {
            if (partFile.exists()) partFile.delete()
            updateState(descriptor.id, ModelState.Error("Сбой скачивания: ${e.localizedMessage ?: "Обрыв связи"}"))
        }
    }

    fun deleteModel(descriptor: ModelDescriptor) {
        val file = File(modelsDir, descriptor.fileName)
        if (file.exists()) file.delete()
        val part = File(modelsDir, "${descriptor.fileName}.part")
        if (part.exists()) part.delete()
        updateState(descriptor.id, ModelState.NotDownloaded)
    }

    private fun verifyGgufHeader(file: File): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                val read = fis.read(header)
                read == 4 &&
                        header[0] == 0x47.toByte() && // 'G'
                        header[1] == 0x47.toByte() && // 'G'
                        header[2] == 0x55.toByte() && // 'U'
                        header[3] == 0x46.toByte()    // 'F'
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun updateState(id: String, state: ModelState) {
        val updated = _modelState.value.toMutableMap()
        updated[id] = state
        _modelState.value = updated
    }
}
