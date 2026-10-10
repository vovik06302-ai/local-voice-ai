package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.domain.ConnectionStatus
import com.example.ui.components.ChatBubble
import com.example.ui.components.QuickPromptsRow
import com.example.ui.components.SettingsDialog
import com.example.ui.components.UpdateDialog
import com.example.ui.components.UpdateUiState
import com.example.ui.components.VoiceControlBottomBar
import com.example.voice.VoiceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val rmsDb by viewModel.rmsDb.collectAsState()
    val partialSpeech by viewModel.partialSpeech.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val processingState by viewModel.processingState.collectAsState()
    val updateUiState by viewModel.updateUiState.collectAsState()

    var showSettingsDialog by remember { mutableStateOf(false) }

    // Audio recording permission state
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            viewModel.onMicrophoneToggle(true) {}
        }
    }

    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Голосовой ИИ",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.padding(top = 1.dp)
                            ) {
                                Text(
                                    text = "v${viewModel.appVersionName}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        // Status badge pill
                        StatusBadge(
                            status = connectionStatus,
                            onClick = { showSettingsDialog = true }
                        )
                    }
                },
                actions = {
                    // Update Button in Top Bar
                    IconButton(
                        onClick = { viewModel.checkForUpdates() },
                        modifier = Modifier.testTag("check_updates_top_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = "Проверить обновление",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Settings Button
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Настройки"
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            VoiceControlBottomBar(
                voiceState = voiceState,
                rmsDb = rmsDb,
                partialText = partialSpeech,
                isSpeaking = isSpeaking,
                isThinking = processingState is ProcessingState.Thinking,
                onMicClick = {
                    viewModel.onMicrophoneToggle(hasAudioPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStopSpeechClick = { viewModel.cancelGeneration() },
                onSendTextMessage = { text -> viewModel.sendMessage(text) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (messages.isEmpty()) {
                // Empty state with welcoming intro and suggestions
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Персональный голосовой ИИ",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Работает локально на телефоне без интернета.\nПри подключении к ПК использует модель Qwen2.5.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    QuickPromptsRow(
                        onPromptClick = { prompt -> viewModel.sendMessage(prompt) }
                    )
                }
            } else {
                // Messages list
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("chat_messages_list")
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatBubble(
                            message = message,
                            isSpeaking = isSpeaking,
                            onSpeakClick = { viewModel.speakText(message.text) },
                            onStopSpeakClick = { viewModel.stopSpeaking() }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    val offlineVoiceStatus by viewModel.offlineVoiceStatus.collectAsState()
    val offlineSpeechStatus by viewModel.offlineSpeechStatus.collectAsState()
    val modelStates by viewModel.modelStates.collectAsState()

    // Settings Dialog
    if (showSettingsDialog) {
        SettingsDialog(
            currentSettings = settings,
            currentVersion = "${viewModel.appVersionName} (${viewModel.appVersionCode})",
            deviceSpecs = viewModel.getDeviceSpecs(),
            modelStates = modelStates,
            offlineVoiceStatus = offlineVoiceStatus,
            offlineSpeechStatus = offlineSpeechStatus,
            onSave = { updated ->
                viewModel.settingsRepo.updateSettings(
                    pcServerUrl = updated.pcServerUrl,
                    pcModel = updated.pcModel,
                    autoFallbackEnabled = updated.autoFallbackEnabled,
                    forceLocalOnly = updated.forceLocalOnly,
                    ttsSpeed = updated.ttsSpeed,
                    ttsPitch = updated.ttsPitch,
                    autoSpeakResponses = updated.autoSpeakResponses,
                    githubRepo = updated.githubRepo,
                    searchApiKey = updated.searchApiKey,
                    selectedOnDeviceModelId = updated.selectedOnDeviceModelId
                )
            },
            onDismiss = { showSettingsDialog = false },
            onTestPcConnection = { url, cb ->
                viewModel.testPcConnection(url, cb)
            },
            onTestVoice = {
                viewModel.speakText("Проверка голоса. Локальный синтез русской речи работает нормально.")
            },
            onClearHistory = {
                viewModel.clearHistory()
            },
            onCheckUpdatesClick = {
                showSettingsDialog = false
                viewModel.checkForUpdates()
            },
            onOpenTtsSettings = {
                viewModel.openTtsSettings()
            },
            onOpenVoiceSettings = {
                viewModel.openVoiceInputSettings()
            },
            onDownloadModel = { desc ->
                viewModel.downloadOnDeviceModel(desc)
            },
            onDeleteModel = { desc ->
                viewModel.deleteOnDeviceModel(desc)
            }
        )
    }

    // GitHub Update Dialog
    if (updateUiState !is UpdateUiState.Idle) {
        UpdateDialog(
            currentVersion = viewModel.appVersionName,
            currentVersionCode = viewModel.appVersionCode,
            state = updateUiState,
            onStartDownload = { release ->
                viewModel.startDownloadUpdate(release)
            },
            onInstall = {
                viewModel.installDownloadedApk(context)
            },
            onDismiss = {
                viewModel.dismissUpdateDialog()
            },
            onCheckAgain = {
                viewModel.checkForUpdates()
            }
        )
    }
}

@Composable
fun StatusBadge(
    status: ConnectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (dotColor, text, icon) = when (status) {
        is ConnectionStatus.PcOnline -> Triple(
            Color(0xFF22C55E),
            "ПК: ${status.model}",
            Icons.Default.Computer
        )
        is ConnectionStatus.Checking -> Triple(
            Color(0xFFEAB308),
            "Проверка...",
            Icons.Default.Sync
        )
        is ConnectionStatus.OnDeviceOnly -> Triple(
            Color(0xFFF59E0B),
            "Телефон (Офлайн)",
            Icons.Default.PhoneAndroid
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("connection_status_badge")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
