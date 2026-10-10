package com.example.data.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.util.Locale

data class DeviceSpecs(
    val manufacturer: String,
    val model: String,
    val deviceName: String,
    val hardwareBoard: String,
    val cpuAbi: String,
    val cpuCores: Int,
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val isLowRam: Boolean,
    val totalStorageBytes: Long,
    val freeStorageBytes: Long
) {
    val totalRamGb: Double get() = totalRamBytes / (1024.0 * 1024.0 * 1024.0)
    val availRamGb: Double get() = availRamBytes / (1024.0 * 1024.0 * 1024.0)
    val freeStorageGb: Double get() = freeStorageBytes / (1024.0 * 1024.0 * 1024.0)
    val totalStorageGb: Double get() = totalStorageBytes / (1024.0 * 1024.0 * 1024.0)

    fun formattedSummary(): String {
        return "$manufacturer $model\n" +
                "Процессор: $cpuCores ядер (${cpuAbi}, $hardwareBoard)\n" +
                "Физическая RAM: ${String.format(Locale.US, "%.1f", totalRamGb)} ГБ (свободно: ${String.format(Locale.US, "%.1f", availRamGb)} ГБ)\n" +
                "Память накопителя: свободно ${String.format(Locale.US, "%.1f", freeStorageGb)} из ${String.format(Locale.US, "%.1f", totalStorageGb)} ГБ"
    }
}

object DeviceHardwareDetector {

    fun detect(context: Context): DeviceSpecs {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val statFs = try {
            StatFs(context.filesDir.absolutePath)
        } catch (_: Exception) {
            StatFs(Environment.getDataDirectory().absolutePath)
        }

        val totalStorage = statFs.blockCountLong * statFs.blockSizeLong
        val freeStorage = statFs.availableBlocksLong * statFs.blockSizeLong

        val cpuAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val cpuCores = Runtime.getRuntime().availableProcessors()

        val manufacturer = Build.MANUFACTURER.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        val model = Build.MODEL
        val device = Build.DEVICE
        val board = Build.BOARD

        return DeviceSpecs(
            manufacturer = manufacturer,
            model = model,
            deviceName = device,
            hardwareBoard = board,
            cpuAbi = cpuAbi,
            cpuCores = cpuCores,
            totalRamBytes = memInfo.totalMem,
            availRamBytes = memInfo.availMem,
            isLowRam = memInfo.lowMemory,
            totalStorageBytes = totalStorage,
            freeStorageBytes = freeStorage
        )
    }
}
