package it.xcc.findme.receiver

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.xcc.findme.core.CommandType
import it.xcc.findme.core.MonitoredDevice
import it.xcc.findme.core.VoiceMessagePolicy
import it.xcc.findme.core.VoiceMessageVolume
import it.xcc.findme.receiver.recording.LocalRecordingState
import it.xcc.findme.receiver.recording.RecordingPolicy

enum class DeviceTab(
    val label: String,
    val icon: ImageVector,
) {
    POSITION("Posizione", Icons.Outlined.LocationOn),
    VIDEO("Video", Icons.Outlined.Videocam),
    AUDIO("Audio", Icons.Outlined.Mic),
    SCREEN("Schermo", Icons.AutoMirrored.Outlined.ScreenShare),
}

@Composable
fun DeviceDetailScreen(
    item: MonitoredDevice,
    heartbeatIntervalSec: Int,
    selectedTab: DeviceTab,
    audioLevel: Float,
    onTabSelected: (DeviceTab) -> Unit,
    onBack: () -> Unit,
    onCommand: (CommandType) -> Unit,
    fastTrackingActive: Boolean,
    fastHistoryActive: Boolean,
    onFastTrackingChange: (Boolean) -> Unit,
    onFastHistoryChange: (Boolean) -> Unit,
    onGeofenceChange: (Boolean) -> Unit,
    onFullscreen: () -> Unit,
    onScreenFullscreen: () -> Unit,
    onOpenHistory: () -> Unit,
    onTakePhoto: () -> Unit,
    videoTrackAvailable: Boolean,
    audioTrackAvailable: Boolean,
    screenTrackAvailable: Boolean,
    videoRecordingState: LocalRecordingState,
    audioRecordingState: LocalRecordingState,
    screenRecordingState: LocalRecordingState,
    onVideoRecordingToggle: () -> Unit,
    onAudioRecordingToggle: () -> Unit,
    voiceMessageState: VoiceMessageDraftState,
    voiceMessageVolume: VoiceMessageVolume,
    voiceMessageFeedback: String,
    onVoiceMessageRecordToggle: () -> Unit,
    onVoiceMessageVolumeChange: (VoiceMessageVolume) -> Unit,
    onVoiceMessageSend: () -> Unit,
    onVoiceMessageDiscard: () -> Unit,
    onScreenRecordingToggle: () -> Unit,
    snapshotPreview: Bitmap?,
    onSnapshotAnimationFinished: () -> Unit,
    videoContent: @Composable () -> Unit,
    screenContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceHeader(item, heartbeatIntervalSec, onBack)
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                DeviceTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { onTabSelected(tab) },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.size(26.dp),
                            )
                        },
                    )
                }
            }
            when (selectedTab) {
                DeviceTab.POSITION -> PositionTab(
                    item = item,
                    heartbeatIntervalSec = heartbeatIntervalSec,
                    fastTrackingActive = fastTrackingActive,
                    fastHistoryActive = fastHistoryActive,
                    onFastTrackingChange = onFastTrackingChange,
                    onFastHistoryChange = onFastHistoryChange,
                    onGeofenceChange = onGeofenceChange,
                    onFullscreen = onFullscreen,
                    onOpenHistory = onOpenHistory,
                )
                DeviceTab.VIDEO -> VideoTab(
                    item = item,
                    heartbeatIntervalSec = heartbeatIntervalSec,
                    onCommand = onCommand,
                    onTakePhoto = onTakePhoto,
                    trackAvailable = videoTrackAvailable,
                    recordingState = videoRecordingState,
                    onRecordingToggle = onVideoRecordingToggle,
                    videoContent = videoContent,
                )
                DeviceTab.AUDIO -> AudioTab(
                    item = item,
                    heartbeatIntervalSec = heartbeatIntervalSec,
                    audioLevel = audioLevel,
                    onCommand = onCommand,
                    trackAvailable = audioTrackAvailable,
                    recordingState = audioRecordingState,
                    onRecordingToggle = onAudioRecordingToggle,
                    voiceMessageState = voiceMessageState,
                    voiceMessageVolume = voiceMessageVolume,
                    voiceMessageFeedback = voiceMessageFeedback,
                    onVoiceMessageRecordToggle = onVoiceMessageRecordToggle,
                    onVoiceMessageVolumeChange = onVoiceMessageVolumeChange,
                    onVoiceMessageSend = onVoiceMessageSend,
                    onVoiceMessageDiscard = onVoiceMessageDiscard,
                )
                DeviceTab.SCREEN -> ScreenTab(
                    item = item,
                    heartbeatIntervalSec = heartbeatIntervalSec,
                    onCommand = onCommand,
                    trackAvailable = screenTrackAvailable,
                    recordingState = screenRecordingState,
                    onRecordingToggle = onScreenRecordingToggle,
                    onFullscreen = onScreenFullscreen,
                    screenContent = screenContent,
                )
            }
        }
        snapshotPreview?.let {
            SnapshotCaptureAnimation(
                bitmap = it,
                onFinished = onSnapshotAnimationFinished,
            )
        }
    }
}

@Composable
private fun DeviceHeader(
    item: MonitoredDevice,
    heartbeatIntervalSec: Int,
    onBack: () -> Unit,
) {
    val monitoringActive = item.isMonitoringActive(
        heartbeatIntervalSec = heartbeatIntervalSec,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Text(
                    "‹",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    item.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${if (item.isOnline(heartbeatIntervalSec = heartbeatIntervalSec)) "Online" else "Offline"}  •  " +
                        "Batteria ${item.status?.batteryPercent?.let { "$it%" } ?: "n/d"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .size(12.dp)
                    .background(
                        if (monitoringActive) MonitoringGreen else Color(0xFF40505B),
                        CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun PositionTab(
    item: MonitoredDevice,
    heartbeatIntervalSec: Int,
    fastTrackingActive: Boolean,
    fastHistoryActive: Boolean,
    onFastTrackingChange: (Boolean) -> Unit,
    onFastHistoryChange: (Boolean) -> Unit,
    onGeofenceChange: (Boolean) -> Unit,
    onFullscreen: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val location = item.location
        if (location == null) {
            Text(
                "Posizione non ancora disponibile.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            PositionCoordinates(item)
            Box {
                DeviceMap(
                    deviceName = item.displayName,
                    location = location,
                    geofence = item.relationship,
                )
                IconButton(
                    onClick = onFullscreen,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            CircleShape,
                        ),
                ) {
                    Icon(
                        Icons.Outlined.Fullscreen,
                        contentDescription = "Mappa a schermo intero",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        PositionControls(
            item = item,
            heartbeatIntervalSec = heartbeatIntervalSec,
            fastTrackingActive = fastTrackingActive,
            fastHistoryActive = fastHistoryActive,
            onFastTrackingChange = onFastTrackingChange,
            onFastHistoryChange = onFastHistoryChange,
            onGeofenceChange = onGeofenceChange,
            onOpenHistory = onOpenHistory,
        )
    }
}

@Composable
internal fun PositionCoordinates(
    item: MonitoredDevice,
) {
    val location = item.location ?: return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "%.6f, %.6f  •  ±%.0f m".format(
                location.latitude,
                location.longitude,
                location.accuracy ?: 0f,
            ),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun PositionControls(
    item: MonitoredDevice,
    heartbeatIntervalSec: Int,
    fastTrackingActive: Boolean,
    fastHistoryActive: Boolean,
    onFastTrackingChange: (Boolean) -> Unit,
    onFastHistoryChange: (Boolean) -> Unit,
    onGeofenceChange: (Boolean) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val geofence = item.relationship
    val geofenceActive = geofence?.geofenceEnabled == true
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TrackingControl(
            title = "Aggiornamento rapido",
            description = "Richiede posizioni più frequenti finché questa vista resta attiva.",
            checked = fastTrackingActive,
            enabled = item.isOnline(heartbeatIntervalSec = heartbeatIntervalSec),
            icon = {
                Icon(
                    if (fastTrackingActive) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    contentDescription = null,
                )
            },
            onCheckedChange = onFastTrackingChange,
        )
        TrackingControl(
            title = "Storico rapido",
            description = "Salva lo storico usando la frequenza rapida moltiplicata.",
            checked = fastHistoryActive,
            enabled = fastTrackingActive,
            icon = {
                Icon(Icons.Outlined.Timeline, contentDescription = null)
            },
            onCheckedChange = onFastHistoryChange,
        )
        TrackingControl(
            title = "Avviso uscita area",
            description = if (geofenceActive) {
                val state = if (geofence?.geofenceIsOutside == true) "fuori area" else "dentro l’area"
                "Raggio ${geofence?.geofenceRadiusM ?: 0} m • $state"
            } else {
                "Usa la posizione attuale come centro dell’area."
            },
            checked = geofenceActive,
            enabled = geofenceActive ||
                (item.location != null &&
                    item.isOnline(heartbeatIntervalSec = heartbeatIntervalSec)),
            icon = {
                Icon(
                    if (geofenceActive) {
                        Icons.Outlined.NotificationsActive
                    } else {
                        Icons.Outlined.NotificationsOff
                    },
                    contentDescription = null,
                )
            },
            onCheckedChange = onGeofenceChange,
        )
        if (geofenceActive) {
            Text(
                "Centro %.6f, %.6f".format(
                    geofence?.geofenceCenterLatitude ?: 0.0,
                    geofence?.geofenceCenterLongitude ?: 0.0,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onOpenHistory) {
            Icon(Icons.Outlined.History, contentDescription = null)
            Text("  Consulta storico posizioni")
        }
    }
}

@Composable
internal fun TrackingControl(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon()
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun VideoTab(
    item: MonitoredDevice,
    heartbeatIntervalSec: Int,
    onCommand: (CommandType) -> Unit,
    onTakePhoto: () -> Unit,
    trackAvailable: Boolean,
    recordingState: LocalRecordingState,
    onRecordingToggle: () -> Unit,
    videoContent: @Composable () -> Unit,
) {
    val streaming = item.status?.cameraStreaming == true
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MediaSwitch(
            title = "Streaming video",
            checked = streaming,
            enabled = item.isOnline(heartbeatIntervalSec = heartbeatIntervalSec) &&
                item.status?.cameraAvailable == true,
            onCheckedChange = {
                onCommand(if (it) CommandType.START_VIDEO else CommandType.STOP_VIDEO)
            },
            action = {
                IconButton(
                    enabled = streaming && !recordingState.isActive,
                    onClick = { onCommand(CommandType.SWITCH_CAMERA) },
                ) {
                    Icon(
                        Icons.Outlined.Cameraswitch,
                        contentDescription = "Cambia fotocamera",
                        modifier = Modifier.size(30.dp),
                        tint = if (streaming) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(
                    enabled = streaming,
                    onClick = onTakePhoto,
                ) {
                    Icon(
                        Icons.Outlined.CameraAlt,
                        contentDescription = "Scatta foto",
                        modifier = Modifier.size(28.dp),
                        tint = if (streaming) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            },
        )
        RecordingControl(
            title = "Registra video",
            state = recordingState,
            enabled = streaming && trackAvailable,
            onToggle = onRecordingToggle,
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                videoContent()
                if (!streaming) {
                    Text(
                        "Video non attivo",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (streaming) {
            Text(
                "Camera ${if (item.status?.cameraFacing == "back") "posteriore" else "frontale"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ScreenTab(
    item: MonitoredDevice,
    heartbeatIntervalSec: Int,
    onCommand: (CommandType) -> Unit,
    trackAvailable: Boolean,
    recordingState: LocalRecordingState,
    onRecordingToggle: () -> Unit,
    onFullscreen: () -> Unit,
    screenContent: @Composable () -> Unit,
) {
    val ready = item.status?.screenShareReady == true
    val streaming = item.status?.screenStreaming == true
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MediaSwitch(
            title = "Mirroring schermo",
            checked = streaming,
            enabled = item.isOnline(heartbeatIntervalSec = heartbeatIntervalSec) && ready,
            onCheckedChange = {
                onCommand(if (it) CommandType.START_SCREEN else CommandType.STOP_SCREEN)
            },
        )
        RecordingControl(
            title = "Registra schermo",
            state = recordingState,
            enabled = streaming && trackAvailable,
            onToggle = onRecordingToggle,
        )
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .aspectRatio(SCREEN_PREVIEW_ASPECT_RATIO),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    screenContent()
                    if (!streaming) {
                        Text(
                            if (ready) "Mirroring non attivo" else "Mirroring non autorizzato",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        enabled = streaming && trackAvailable,
                        onClick = onFullscreen,
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(
                            Icons.Outlined.Fullscreen,
                            contentDescription = "Schermo intero verticale",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

private const val SCREEN_PREVIEW_ASPECT_RATIO = 576f / 1280f

@Composable
internal fun ScreenFullscreenScreen(
    streaming: Boolean,
    onExit: () -> Unit,
    screenContent: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        screenContent()
        if (!streaming) {
            Text("Mirroring non attivo", color = Color.White)
        }
        IconButton(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        ) {
            Icon(
                Icons.Outlined.FullscreenExit,
                contentDescription = "Torna al dettaglio",
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun AudioTab(
    item: MonitoredDevice,
    heartbeatIntervalSec: Int,
    audioLevel: Float,
    onCommand: (CommandType) -> Unit,
    trackAvailable: Boolean,
    recordingState: LocalRecordingState,
    onRecordingToggle: () -> Unit,
    voiceMessageState: VoiceMessageDraftState,
    voiceMessageVolume: VoiceMessageVolume,
    voiceMessageFeedback: String,
    onVoiceMessageRecordToggle: () -> Unit,
    onVoiceMessageVolumeChange: (VoiceMessageVolume) -> Unit,
    onVoiceMessageSend: () -> Unit,
    onVoiceMessageDiscard: () -> Unit,
) {
    val streaming = item.status?.microphoneStreaming == true
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MediaSwitch(
            title = "Streaming audio",
            checked = streaming,
            enabled = item.isOnline(heartbeatIntervalSec = heartbeatIntervalSec) &&
                item.status?.microphoneAvailable == true,
            onCheckedChange = {
                onCommand(if (it) CommandType.START_AUDIO else CommandType.STOP_AUDIO)
            },
        )
        RecordingControl(
            title = "Registra audio",
            state = recordingState,
            enabled = streaming && trackAvailable,
            onToggle = onRecordingToggle,
        )
        AudioVisualizer(level = audioLevel, active = streaming)
        Text(
            if (streaming) "Livello audio in tempo reale" else "Audio non attivo",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        VoiceMessageControl(
            state = voiceMessageState,
            volume = voiceMessageVolume,
            feedback = voiceMessageFeedback,
            onRecordToggle = onVoiceMessageRecordToggle,
            onVolumeChange = onVoiceMessageVolumeChange,
            onSend = onVoiceMessageSend,
            onDiscard = onVoiceMessageDiscard,
        )
    }
}

@Composable
private fun VoiceMessageControl(
    state: VoiceMessageDraftState,
    volume: VoiceMessageVolume,
    feedback: String,
    onRecordToggle: () -> Unit,
    onVolumeChange: (VoiceMessageVolume) -> Unit,
    onSend: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Messaggio vocale", style = MaterialTheme.typography.titleMedium)
            Text(
                "Il messaggio sarà consegnato anche se il trasmettitore è offline.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceMessageVolume.entries.forEach { candidate ->
                    FilterChip(
                        selected = volume == candidate,
                        onClick = { onVolumeChange(candidate) },
                        enabled = state !is VoiceMessageDraftState.Sending &&
                            state !is VoiceMessageDraftState.Recording,
                        label = {
                            Text(
                                when (candidate) {
                                    VoiceMessageVolume.LOW -> "Basso"
                                    VoiceMessageVolume.MEDIUM -> "Medio"
                                    VoiceMessageVolume.HIGH -> "Alto"
                                },
                            )
                        },
                    )
                }
            }
            when (state) {
                VoiceMessageDraftState.Idle -> Button(onClick = onRecordToggle) {
                    Text("Registra")
                }
                is VoiceMessageDraftState.Recording -> {
                    Text(
                        "${RecordingPolicy.formatElapsed(state.elapsedMs)} / " +
                            RecordingPolicy.formatElapsed(VoiceMessagePolicy.MAX_DURATION_MS.toLong()),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(onClick = onRecordToggle) {
                        Text("Ferma")
                    }
                }
                is VoiceMessageDraftState.Ready -> {
                    Text(
                        "Pronto: ${RecordingPolicy.formatElapsed(state.durationMs)}",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDiscard) { Text("Annulla") }
                        Button(onClick = onSend) { Text("Invia") }
                    }
                }
                VoiceMessageDraftState.Sending -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Invio…")
                }
            }
            if (feedback.isNotBlank()) {
                Text(
                    feedback,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RecordingControl(
    title: String,
    state: LocalRecordingState,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val recording = state is LocalRecordingState.Starting ||
        state is LocalRecordingState.Recording
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    when (state) {
                        LocalRecordingState.Idle -> "Massimo 30 minuti"
                        LocalRecordingState.Starting -> "Avvio registrazione…"
                        is LocalRecordingState.Recording ->
                            "${RecordingPolicy.formatElapsed(state.elapsedMs)} / 30:00"
                        LocalRecordingState.Finalizing -> "Salvataggio…"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                enabled = (enabled || recording) &&
                    state !is LocalRecordingState.Finalizing,
                onClick = onToggle,
            ) {
                Icon(
                    if (recording) Icons.Outlined.StopCircle
                    else Icons.Outlined.FiberManualRecord,
                    contentDescription = if (recording) {
                        "Ferma registrazione"
                    } else {
                        "Avvia registrazione"
                    },
                    modifier = Modifier.size(32.dp),
                    tint = if (recording || enabled) {
                        Color(0xFFFF5252)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun MediaSwitch(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            action?.invoke()
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}
