package com.example.data.remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val changelog: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val expectedSha256: String?,
    val sha256Source: String, // "sha256_file" or "release_notes" or "none"
    val isNewer: Boolean
)

sealed class UpdateCheckResult {
    data class NewVersionAvailable(val release: ReleaseInfo) : UpdateCheckResult()
    data class AlreadyLatest(val currentVersion: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

class GitHubUpdateManager {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun checkLatestRelease(
        repoPath: String,
        currentVersion: String
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val cleanRepo = repoPath.trim().removePrefix("https://github.com/").trim('/')
        if (cleanRepo.isBlank() || !cleanRepo.contains("/")) {
            return@withContext UpdateCheckResult.Error("Укажите репозиторий в формате «пользователь/репозиторий» в настройках.")
        }

        val url = "https://api.github.com/repos/$cleanRepo/releases/latest"
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "VoiceAI-Android-Updater")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return@withContext UpdateCheckResult.Error("В репозитории $cleanRepo пока нет опубликованных релизов (GitHub Releases).")
                }
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckResult.Error("Ошибка GitHub API (код ${response.code}): ${response.message}")
                }

                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "").trim()
                val releaseTitle = json.optString("name", tagName)
                val changelog = json.optString("body", "Список изменений не указан")
                val cleanTag = tagName.removePrefix("v").removePrefix("V").trim()

                // Look for .apk asset and .sha256 asset
                val assets = json.optJSONArray("assets")
                var apkUrl = ""
                var apkName = "update.apk"
                var apkSize = 0L
                var sha256AssetUrl = ""

                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", "")
                            apkName = name
                            apkSize = asset.optLong("size", 0L)
                        } else if (name.endsWith(".sha256", ignoreCase = true)) {
                            sha256AssetUrl = asset.optString("browser_download_url", "")
                        }
                    }
                }

                if (apkUrl.isBlank()) {
                    return@withContext UpdateCheckResult.Error("В релизе $tagName не найден прикреплённый APK файл.")
                }

                // Retrieve expected SHA-256
                var expectedSha: String? = null
                var shaSource = "none"

                // 1. Try to fetch from companion .sha256 file asset
                if (sha256AssetUrl.isNotBlank()) {
                    try {
                        val shaReq = Request.Builder().url(sha256AssetUrl).get().build()
                        client.newCall(shaReq).execute().use { shaResp ->
                            if (shaResp.isSuccessful) {
                                val content = shaResp.body?.string()?.trim() ?: ""
                                val hashMatch = Regex("""([a-f0-9]{64})""", RegexOption.IGNORE_CASE).find(content)
                                if (hashMatch != null) {
                                    expectedSha = hashMatch.value.lowercase()
                                    shaSource = "sha256_file"
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                // 2. Fallback: Parse SHA-256 from release body description
                if (expectedSha == null) {
                    val shaRegex = Regex("""(?i)(?:sha-?256|hash)[:\s=]+([a-f0-9]{64})""")
                    val match = shaRegex.find(changelog)
                    if (match != null) {
                        expectedSha = match.groupValues[1].lowercase()
                        shaSource = "release_notes"
                    }
                }

                val isNewer = isVersionNewer(cleanTag, currentVersion)

                val releaseInfo = ReleaseInfo(
                    tagName = tagName,
                    versionName = cleanTag,
                    releaseTitle = releaseTitle,
                    changelog = changelog,
                    apkDownloadUrl = apkUrl,
                    apkFileName = apkName,
                    apkSizeBytes = apkSize,
                    expectedSha256 = expectedSha,
                    sha256Source = shaSource,
                    isNewer = isNewer
                )

                if (isNewer) {
                    UpdateCheckResult.NewVersionAvailable(releaseInfo)
                } else {
                    UpdateCheckResult.AlreadyLatest(currentVersion)
                }
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error("Не удалось подключиться к GitHub: ${e.localizedMessage ?: "Проверьте подключение к интернету"}")
        }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        fileName: String,
        onProgress: (Float) -> Unit
    ): Pair<File?, String?> = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val targetFile = File(updateDir, fileName)
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "VoiceAI-Android-Updater")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Pair(null, "Ошибка скачивания файла: HTTP ${response.code}")
                }

                val body = response.body ?: return@withContext Pair(null, "Пустой ответ сервера")
                val totalLength = body.contentLength()
                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(targetFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var downloadedBytes: Long = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalLength > 0) {
                        val progress = downloadedBytes.toFloat() / totalLength.toFloat()
                        withContext(Dispatchers.Main) {
                            onProgress(progress)
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()
            }

            Pair(targetFile, null)
        } catch (e: Exception) {
            Pair(null, "Ошибка скачивания: ${e.localizedMessage ?: "Обрыв соединения"}")
        }
    }

    fun verifyApkIntegrity(
        context: Context,
        apkFile: File,
        expectedSha256: String?
    ): Pair<Boolean, String> {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return Pair(false, "Файл APK не найден или пуст.")
        }

        // 1. Verify SHA-256 Checksum
        if (!expectedSha256.isNullOrBlank()) {
            val calculatedSha = calculateSha256(apkFile)
            if (!calculatedSha.equals(expectedSha256.trim(), ignoreCase = true)) {
                return Pair(
                    false,
                    "Контрольная сумма SHA-256 не совпадает!\nОжидалось: $expectedSha256\nПолучено: $calculatedSha"
                )
            }
        }

        // 2. Verify APK Package Info & Signature Compatibility
        try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

            val archivePackageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
                ?: return Pair(false, "Недействительный установочный пакет APK (файл повреждён).")

            // Check applicationId
            if (archivePackageInfo.packageName != context.packageName) {
                return Pair(
                    false,
                    "Имя пакета в обновлении (${archivePackageInfo.packageName}) не совпадает с приложением (${context.packageName})."
                )
            }

            // Check versionCode > current
            val currentPackageInfo = pm.getPackageInfo(context.packageName, flags)
            val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archivePackageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                archivePackageInfo.versionCode.toLong()
            }
            val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                currentPackageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                currentPackageInfo.versionCode.toLong()
            }

            if (archiveVersionCode <= currentVersionCode) {
                return Pair(
                    false,
                    "versionCode обновления ($archiveVersionCode) не выше текущего ($currentVersionCode). Обновление отменено."
                )
            }

            // 3. Verify Signature Compatibility
            val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archivePackageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                archivePackageInfo.signatures
            }

            val currentSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                currentPackageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                currentPackageInfo.signatures
            }

            if (archiveSignatures != null && currentSignatures != null &&
                archiveSignatures.isNotEmpty() && currentSignatures.isNotEmpty()
            ) {
                val archiveSigString = archiveSignatures[0].toCharsString()
                val currentSigString = currentSignatures[0].toCharsString()
                if (archiveSigString != currentSigString) {
                    return Pair(
                        false,
                        "Ключ подписи APK отличается от ключа установленного приложения! Установка будет заблокирована системой Android."
                    )
                }
            }
        } catch (e: Exception) {
            return Pair(false, "Сбой при проверке APK: ${e.localizedMessage}")
        }

        return Pair(true, "Целостность, applicationId, versionCode и подпись APK успешно проверены.")
    }

    fun launchApkInstall(context: Context, apkFile: File): Pair<Boolean, String?> {
        // Android 8.0+ Check permission to install unknown apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                try {
                    val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(manageIntent)
                    return Pair(
                        false,
                        "В открывшихся настройках Android включите разрешение «Разрешить установку из этого источника», затем нажмите «Установить» ещё раз."
                    )
                } catch (_: Exception) {}
            }
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
            Pair(true, null)
        } catch (e: Exception) {
            Pair(false, "Не удалось открыть системный установщик: ${e.localizedMessage}")
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isVersionNewer(newVer: String, currentVer: String): Boolean {
        val newParts = newVer.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        val curParts = currentVer.split(".", "-", "_").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(newParts.size, curParts.size)
        for (i in 0 until maxLen) {
            val n = newParts.getOrElse(i) { 0 }
            val c = curParts.getOrElse(i) { 0 }
            if (n > c) return true
            if (n < c) return false
        }
        return false
    }
}
