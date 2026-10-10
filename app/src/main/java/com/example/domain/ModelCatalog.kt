package com.example.domain

import java.util.Locale

data class ModelDescriptor(
    val id: String,
    val name: String,
    val parameterCount: String,
    val quantization: String,
    val sourceRepo: String,
    val downloadUrl: String,
    val fileName: String,
    val downloadSizeBytes: Long,
    val requiredStorageBytes: Long,
    val expectedSha256: String,
    val license: String,
    val estimatedWeightsRamBytes: Long,
    val estimatedKvCacheBytes: Long,
    val estimatedWorkingBufferBytes: Long,
    val totalEstimatedRamMb: Int,
    val description: String
) {
    val downloadSizeMb: Double get() = downloadSizeBytes / (1024.0 * 1024.0)
    val requiredStorageMb: Double get() = requiredStorageBytes / (1024.0 * 1024.0)
    val weightsMb: Double get() = estimatedWeightsRamBytes / (1024.0 * 1024.0)
    val kvCacheMb: Double get() = estimatedKvCacheBytes / (1024.0 * 1024.0)
    val workingBufferMb: Double get() = estimatedWorkingBufferBytes / (1024.0 * 1024.0)

    fun formattedSize(): String = String.format(Locale.US, "%.1f МБ", downloadSizeMb)
    fun formattedStorage(): String = String.format(Locale.US, "%.1f ГБ", requiredStorageBytes / (1024.0 * 1024.0 * 1024.0))
}

object ModelCatalog {

    // Закреплённая ревизия (commit 91cad51170dc346986eccefdc2dd33a9da36ead9)
    val QWEN_1_5B = ModelDescriptor(
        id = "qwen2.5-1.5b-q4",
        name = "Qwen2.5 1.5B Instruct",
        parameterCount = "1.54 млрд параметров",
        quantization = "Q4_K_M (4-бит k-квантование)",
        sourceRepo = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        downloadSizeBytes = 1_117_320_736L, // Точно 1 117 320 736 байт (1.04 ГБ)
        requiredStorageBytes = 1_500_000_000L, // минимум 1.5 ГБ свободного места
        expectedSha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
        license = "Apache 2.0 (Qwen)",
        estimatedWeightsRamBytes = 1_117_320_736L,
        estimatedKvCacheBytes = 58_720_256L, // 28 слоев * 2 группы * 64 dim * 2048 ctx * 2 байта (fp16)
        estimatedWorkingBufferBytes = 188_743_680L, // ~180 МБ рабочие буферы активаций
        totalEstimatedRamMb = 1750, // ~1.75 ГБ с учетом накладных расходов ОС
        description = "Рекомендуется для 8 ГБ RAM: качественный русский язык, развернутые ответы и логика."
    )

    // Закреплённая ревизия (commit 9217f5db79a29953eb74d5343926648285ec7e67)
    val QWEN_0_5B = ModelDescriptor(
        id = "qwen2.5-0.5b-q4",
        name = "Qwen2.5 0.5B Instruct",
        parameterCount = "0.49 млрд параметров",
        quantization = "Q4_K_M (4-бит k-квантование)",
        sourceRepo = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        downloadSizeBytes = 491_400_032L, // Точно 491 400 032 байт (468.6 МБ)
        requiredStorageBytes = 650_000_000L, // минимум 650 МБ свободного места
        expectedSha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        license = "Apache 2.0 (Qwen)",
        estimatedWeightsRamBytes = 491_400_032L,
        estimatedKvCacheBytes = 24_000_000L, // ~23 МБ
        estimatedWorkingBufferBytes = 100_000_000L, // ~95 МБ
        totalEstimatedRamMb = 750, // ~750 МБ
        description = "Ультралегкая модель: минимальное потребление RAM и батареи, базовые ответы."
    )

    val AVAILABLE_MODELS = listOf(QWEN_1_5B, QWEN_0_5B)

    fun getById(id: String): ModelDescriptor {
        return AVAILABLE_MODELS.firstOrNull { it.id == id } ?: QWEN_1_5B
    }
}
