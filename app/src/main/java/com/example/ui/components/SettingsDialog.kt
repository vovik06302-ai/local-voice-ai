package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.device.DeviceSpecs
import com.example.data.local.AppSettings
import com.example.domain.ModelCatalog
import com.example.domain.ModelDescriptor
import com.example.domain.ModelState
import com.example.voice.OfflineSpeechStatus
import com.example.voice.OfflineVoiceStatus
import java.util.Locale

@Composable
fun SettingsDialog(
    currentSettings: AppSettings,
    currentVersion: String,
    deviceSpecs: DeviceSpecs,
    modelStates: Map<String, ModelState>,
    offlineVoiceStatus: OfflineVoiceStatus,
    offlineSpeechStatus: OfflineSpeechStatus,
    onSave: (AppSettings) -> Unit,
    onDismiss: () -> Unit,
    onTestPcConnection: (String, (Boolean, String) -> Unit) -> Unit,
    onTestVoice: () -> Unit,
    onClearHistory: () -> Unit,
    onCheckUpdatesClick: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onDownloadModel: (ModelDescriptor) -> Unit,
    onDeleteModel: (ModelDescriptor) -> Unit
) {
    var pcUrl by remember { mutableStateOf(currentSettings.pcServerUrl) }
    var pcModel by remember { mutableStateOf(currentSettings.pcModel) }
    var autoFallback by remember { mutableStateOf(currentSettings.autoFallbackEnabled) }
    var forceLocal by remember { mutableStateOf(currentSettings.forceLocalOnly) }
    var ttsSpeed by remember { mutableFloatStateOf(currentSettings.ttsSpeed) }
    var ttsPitch by remember { mutableFloatStateOf(currentSettings.ttsPitch) }
    var autoSpeak by remember { mutableStateOf(currentSettings.autoSpeakResponses) }
    var githubRepo by remember { mutableStateOf(currentSettings.githubRepo) }
    var searchApiKey by remember { mutableStateOf(currentSettings.searchApiKey) }
    var selectedModelId by remember { mutableStateOf(currentSettings.selectedOnDeviceModelId) }

    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var showConfirmClearDialog by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(24.dp))
                .testTag("settings_dialog"),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Настройки приложения",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Версия программы: v$currentVersion",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Section 1: Device Hardware Info
                Text(
                    text = "Определённые характеристики устройства",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${deviceSpecs.manufacturer} ${deviceSpecs.model}",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Процессор: ${deviceSpecs.cpuCores} ядер (${deviceSpecs.cpuAbi})\n" +
                                    "• Физическая RAM: ${String.format(Locale.US, "%.1f", deviceSpecs.totalRamGb)} ГБ (свободно: ${String.format(Locale.US, "%.1f", deviceSpecs.availRamGb)} ГБ)\n" +
                                    "• Память накопителя: свободно ${String.format(Locale.US, "%.1f", deviceSpecs.freeStorageGb)} из ${String.format(Locale.US, "%.1f", deviceSpecs.totalStorageGb)} ГБ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 2: On-Device LLM Models
                Text(
                    text = "Локальная нейросеть (On-Device LLM)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Для работы голосового ИИ полностью автономно без ПК и интернета выберите и скачайте модель в память телефона.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                ModelCatalog.AVAILABLE_MODELS.forEach { modelDesc ->
                    val state = modelStates[modelDesc.id] ?: ModelState.NotDownloaded
                    val isSelected = selectedModelId == modelDesc.id

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedModelId = modelDesc.id }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = modelDesc.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = modelDesc.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "• Файл: ${modelDesc.fileName} (${modelDesc.downloadSizeBytes} байт)\n" +
                                        "• Лицензия: ${modelDesc.license}\n" +
                                        "• Ожидаемый SHA-256: ${modelDesc.expectedSha256.take(16)}...\n" +
                                        "• Память: Веса ~${String.format(Locale.US, "%.0f", modelDesc.weightsMb)} МБ + KV-кэш ~${String.format(Locale.US, "%.0f", modelDesc.kvCacheMb)} МБ + Буферы ~${String.format(Locale.US, "%.0f", modelDesc.workingBufferMb)} МБ = ~${modelDesc.totalEstimatedRamMb} МБ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 36.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Status and Action buttons
                            Box(modifier = Modifier.padding(start = 36.dp)) {
                                when (state) {
                                    is ModelState.NotDownloaded -> {
                                        OutlinedButton(
                                            onClick = { onDownloadModel(modelDesc) }
                                        ) {
                                            Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Скачать модель (${modelDesc.formattedSize()})", fontSize = 12.sp)
                                        }
                                    }
                                    is ModelState.Downloading -> {
                                        Column {
                                            Text(
                                                text = "Загрузка: ${(state.progress * 100).toInt()}% (${String.format(Locale.US, "%.1f", state.downloadedMb)} из ${String.format(Locale.US, "%.1f", state.totalMb)} МБ)",
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LinearProgressIndicator(
                                                progress = { state.progress },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                    is ModelState.VerifyingSha256 -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Проверка контрольной суммы SHA-256 и GGUF...", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    is ModelState.Ready -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF22C55E), modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Готова к работе (${String.format(Locale.US, "%.1f", state.sizeMb)} МБ)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                                }
                                                Text(
                                                    text = "SHA-256 подтверждён: ${state.sha256.take(16)}...",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF16A34A)
                                                )
                                            }
                                            TextButton(
                                                onClick = { onDeleteModel(modelDesc) },
                                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Text("Удалить", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                    is ModelState.Error -> {
                                        Column {
                                            Text(
                                                text = state.message,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            OutlinedButton(onClick = { onDownloadModel(modelDesc) }) {
                                                Text("Повторить скачивание", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 3: Internet Search Settings
                Text(
                    text = "Интернет-поиск (Команда «Найди в интернете...»)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Бесплатный открытый поиск активен", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "По умолчанию поиск работает без обязательных API-ключей через DuckDuckGo и SearXNG.\n\n" +
                                    "Если подключён ПК с LLM — модель кратко излагает факты со ссылками. Если ПК выключен — результаты выводятся напрямую без выдуманных данных.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchApiKey,
                    onValueChange = { searchApiKey = it },
                    label = { Text("Опциональный API-ключ Tavily (необязательно)") },
                    placeholder = { Text("tvly-xxxxxxxxxxxx") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 4: Offline Readiness Status
                Text(
                    text = "Фактическая готовность офлайн-функций",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Speech Recognizer Status
                Surface(
                    color = if (offlineSpeechStatus.isAvailable) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Распознавание речи (STT)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(offlineSpeechStatus.details, style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = onOpenVoiceSettings,
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Настройки голосового ввода Android", fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // TTS Voice Status
                Surface(
                    color = if (offlineVoiceStatus.isOfflineReady) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Синтез речи (TTS голос)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(offlineVoiceStatus.details, style = MaterialTheme.typography.bodySmall)
                        if (!offlineVoiceStatus.isOfflineReady) {
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = onOpenTtsSettings,
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Открыть настройки синтеза речи", fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 5: Architecture & PC Server
                Text(
                    text = "Архитектура и подключение к ПК",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Только автономно на телефоне", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Не обращаться к ПК, работать всегда 100% офлайн", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = forceLocal, onCheckedChange = { forceLocal = it })
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Умный автопереход (Auto-Fallback)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Если ПК недоступен или нет сети — отвечать с телефона", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoFallback, onCheckedChange = { autoFallback = it }, enabled = !forceLocal)
                }

                if (!forceLocal) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pcUrl,
                        onValueChange = { pcUrl = it },
                        label = { Text("Адрес сервера ПК (IP / Tailscale)") },
                        placeholder = { Text("http://192.168.1.100:11434") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pcModel,
                        onValueChange = { pcModel = it },
                        label = { Text("Модель на ПК (Ollama / llama.cpp)") },
                        placeholder = { Text("qwen2.5:7b или llama3.1:8b") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            isTesting = true
                            testStatus = null
                            onTestPcConnection(pcUrl) { _, msg ->
                                isTesting = false
                                testStatus = msg
                            }
                        },
                        enabled = !isTesting
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                        } else {
                            Icon(imageVector = Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("Проверить связь с ПК")
                    }

                    testStatus?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.contains("подключен", ignoreCase = true) || status.contains("доступен", ignoreCase = true)) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 6: Voice & TTS Tuning
                Text(
                    text = "Настройка голоса",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Озвучивать ответы голосом", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = autoSpeak, onCheckedChange = { autoSpeak = it })
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Скорость речи: ${String.format(Locale.US, "%.1fx", ttsSpeed)}", style = MaterialTheme.typography.bodySmall)
                Slider(value = ttsSpeed, onValueChange = { ttsSpeed = it }, valueRange = 0.5f..2.0f, steps = 5)

                Text("Тембр (высота): ${String.format(Locale.US, "%.1fx", ttsPitch)}", style = MaterialTheme.typography.bodySmall)
                Slider(value = ttsPitch, onValueChange = { ttsPitch = it }, valueRange = 0.5f..1.5f, steps = 4)

                OutlinedButton(onClick = onTestVoice, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(imageVector = Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Тест голоса")
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 7: GitHub Updates
                Text(
                    text = "Обновления через GitHub Releases",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = githubRepo,
                    onValueChange = { githubRepo = it },
                    label = { Text("GitHub репозиторий (username/repo)") },
                    placeholder = { Text("vovik06302/local-voice-ai") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        onSave(
                            AppSettings(
                                pcServerUrl = pcUrl,
                                pcModel = pcModel,
                                autoFallbackEnabled = autoFallback,
                                forceLocalOnly = forceLocal,
                                ttsSpeed = ttsSpeed,
                                ttsPitch = ttsPitch,
                                autoSpeakResponses = autoSpeak,
                                githubRepo = githubRepo,
                                searchApiKey = searchApiKey,
                                selectedOnDeviceModelId = selectedModelId
                            )
                        )
                        onCheckUpdatesClick()
                    }
                ) {
                    Icon(imageVector = Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Проверить обновления сейчас")
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 8: Clear History
                Text(
                    text = "Память диалога",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { showConfirmClearDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Очистить историю сообщений")
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons: Cancel / Save
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                AppSettings(
                                    pcServerUrl = pcUrl,
                                    pcModel = pcModel,
                                    autoFallbackEnabled = autoFallback,
                                    forceLocalOnly = forceLocal,
                                    ttsSpeed = ttsSpeed,
                                    ttsPitch = ttsPitch,
                                    autoSpeakResponses = autoSpeak,
                                    githubRepo = githubRepo,
                                    searchApiKey = searchApiKey,
                                    selectedOnDeviceModelId = selectedModelId
                                )
                            )
                            onDismiss()
                        }
                    ) {
                        Text("Сохранить")
                    }
                }
            }
        }
    }

    if (showConfirmClearDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmClearDialog = false },
            title = { Text("Очистить историю?") },
            text = { Text("Все сохранённые сообщения чата будут удалены без возможности восстановления.") },
            confirmButton = {
                Button(
                    onClick = {
                        onClearHistory()
                        showConfirmClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmClearDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}
