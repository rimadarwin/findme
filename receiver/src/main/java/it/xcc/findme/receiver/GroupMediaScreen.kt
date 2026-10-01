/**
 * @author Infinity
 * @description Selezione e controllo simultaneo dei flussi video e audio dei trasmettitori.
 * @modified 01.10.2026 - Infinity | Compattate le anteprime video con comandi laterali.
 * @modified 01.10.2026 - Infinity | Prima implementazione.
 */
package it.xcc.findme.receiver

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.xcc.findme.core.GroupMediaPolicy
import it.xcc.findme.core.GroupStreamState
import it.xcc.findme.core.MediaCommandFeedback
import it.xcc.findme.core.MediaCommandPhase
import it.xcc.findme.core.MediaCommandPolicy
import it.xcc.findme.core.MonitoredDevice
import it.xcc.findme.receiver.recording.LocalRecordingState
import it.xcc.findme.receiver.recording.RecordingPolicy

enum class GroupMediaTab {
    VIDEO,
    AUDIO,
}

@Composable
fun GroupMediaSelectionScreen(
    devices: List<MonitoredDevice>,
    selectedDeviceIds: Set<String>,
    onSelectionChange: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GroupHeader("Visualizzazione multipla", onBack)
        Text(
            "Seleziona almeno due dispositivi da visualizzare o ascoltare insieme.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(devices, key = { it.device.id }) { item ->
                val selected = item.device.id in selectedDeviceIds
                Card(
                    onClick = {
                        onSelectionChange(item.device.id, !selected)
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = {
                                onSelectionChange(item.device.id, it)
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (item.status?.isMonitoring == true) "Disponibile" else "Non disponibile",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        Button(
            onClick = onConfirm,
            enabled = GroupMediaPolicy.isValidSelection(
                associatedDeviceIds = devices.mapTo(mutableSetOf()) { it.device.id },
                selectedDeviceIds = selectedDeviceIds,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Apri visualizzazione (${selectedDeviceIds.size})")
        }
    }
}

@Composable
fun GroupMediaScreen(
    devices: List<MonitoredDevice>,
    selectedTab: GroupMediaTab,
    heartbeatIntervalSec: Int,
    audioLevels: Map<String, Float>,
    videoFeedback: Map<String, MediaCommandFeedback>,
    audioFeedback: Map<String, MediaCommandFeedback>,
    cameraSwitchPending: Set<String>,
    videoRecordingStates: Map<String, LocalRecordingState>,
    audioRecordingStates: Map<String, LocalRecordingState>,
    videoTrackDeviceIds: Set<String>,
    audioTrackDeviceIds: Set<String>,
    onTabSelected: (GroupMediaTab) -> Unit,
    onBack: () -> Unit,
    onVideoStreamingChange: (Boolean) -> Unit,
    onAudioStreamingChange: (Boolean) -> Unit,
    onVideoRecordingToggle: (MonitoredDevice) -> Unit,
    onAudioRecordingToggle: (MonitoredDevice) -> Unit,
    onCameraSwitch: (MonitoredDevice) -> Unit,
    onTakePhoto: (MonitoredDevice) -> Unit,
    onGroupFullscreen: () -> Unit,
    onSingleFullscreen: (String) -> Unit,
    snapshotPreview: Bitmap?,
    onSnapshotAnimationFinished: () -> Unit,
    videoContent: @Composable (MonitoredDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GroupHeader("Dispositivi multipli (${devices.size})", onBack)
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                Tab(
                    selected = selectedTab == GroupMediaTab.VIDEO,
                    onClick = { onTabSelected(GroupMediaTab.VIDEO) },
                    icon = { Icon(Icons.Outlined.Videocam, contentDescription = "Video") },
                )
                Tab(
                    selected = selectedTab == GroupMediaTab.AUDIO,
                    onClick = { onTabSelected(GroupMediaTab.AUDIO) },
                    icon = { Icon(Icons.Outlined.Mic, contentDescription = "Audio") },
                )
            }
            when (selectedTab) {
                GroupMediaTab.VIDEO -> GroupVideoTab(
                    devices = devices,
                    heartbeatIntervalSec = heartbeatIntervalSec,
                    feedback = videoFeedback,
                    cameraSwitchPending = cameraSwitchPending,
                    recordingStates = videoRecordingStates,
                    trackDeviceIds = videoTrackDeviceIds,
                    onStreamingChange = onVideoStreamingChange,
                    onRecordingToggle = onVideoRecordingToggle,
                    onCameraSwitch = onCameraSwitch,
                    onTakePhoto = onTakePhoto,
                    onGroupFullscreen = onGroupFullscreen,
                    onSingleFullscreen = onSingleFullscreen,
                    videoContent = videoContent,
                    modifier = Modifier.weight(1f),
                )
                GroupMediaTab.AUDIO -> GroupAudioTab(
                    devices = devices,
                    heartbeatIntervalSec = heartbeatIntervalSec,
                    audioLevels = audioLevels,
                    feedback = audioFeedback,
                    recordingStates = audioRecordingStates,
                    trackDeviceIds = audioTrackDeviceIds,
                    onStreamingChange = onAudioStreamingChange,
                    onRecordingToggle = onAudioRecordingToggle,
                    modifier = Modifier.weight(1f),
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
private fun GroupVideoTab(
    devices: List<MonitoredDevice>,
    heartbeatIntervalSec: Int,
    feedback: Map<String, MediaCommandFeedback>,
    cameraSwitchPending: Set<String>,
    recordingStates: Map<String, LocalRecordingState>,
    trackDeviceIds: Set<String>,
    onStreamingChange: (Boolean) -> Unit,
    onRecordingToggle: (MonitoredDevice) -> Unit,
    onCameraSwitch: (MonitoredDevice) -> Unit,
    onTakePhoto: (MonitoredDevice) -> Unit,
    onGroupFullscreen: () -> Unit,
    onSingleFullscreen: (String) -> Unit,
    videoContent: @Composable (MonitoredDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayed = devices.associate { item ->
        val actual = item.status?.cameraStreaming == true
        item.device.id to MediaCommandPolicy.displayedValue(actual, feedback[item.device.id])
    }
    val streamState = GroupMediaPolicy.streamState(displayed.values)
    val groupFullscreenEnabled = displayed.values.any { it }
    val activeRecordings = recordingStates.filterValues { it.isActive }.keys
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GroupStreamingControl(
            title = "Streaming video",
            state = streamState,
            onChange = onStreamingChange,
            action = {
                IconButton(
                    onClick = onGroupFullscreen,
                    enabled = groupFullscreenEnabled,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(52.dp),
                ) {
                    Icon(
                        Icons.Outlined.Fullscreen,
                        contentDescription = "Griglia a schermo intero",
                        tint = if (groupFullscreenEnabled) {
                            NeonBlue
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(32.dp),
                    )
                }
            },
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(devices, key = { it.device.id }) { item ->
            val id = item.device.id
            val streaming = displayed[id] == true
            val recordingState = recordingStates[id] ?: LocalRecordingState.Idle
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.displayName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (feedback[id]?.phase == MediaCommandPhase.SENDING ||
                            feedback[id]?.phase == MediaCommandPhase.AWAITING_CONFIRMATION
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        }
                    }
                    CompactVideoPanel(
                        streaming = streaming,
                        trackAvailable = id in trackDeviceIds,
                        cameraInterrupted = item.status?.cameraInterrupted == true,
                        cameraSwitchPending = id in cameraSwitchPending,
                        recordingState = recordingState,
                        recordingEnabled = streaming &&
                            id in trackDeviceIds &&
                            GroupMediaPolicy.canStartRecording(activeRecordings, id),
                        onCameraSwitch = { onCameraSwitch(item) },
                        onTakePhoto = { onTakePhoto(item) },
                        onRecordingToggle = { onRecordingToggle(item) },
                        onFullscreen = { onSingleFullscreen(id) },
                        videoContent = { videoContent(item) },
                    )
                    if (!item.isOnline(heartbeatIntervalSec = heartbeatIntervalSec)) {
                        Text(
                            "Dispositivo offline",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    feedback[id]?.errorMessage?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            }
            item {
                Text(
                    "È possibile registrare al massimo ${GroupMediaPolicy.MAX_SIMULTANEOUS_RECORDINGS} " +
                        "video contemporaneamente.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun GroupAudioTab(
    devices: List<MonitoredDevice>,
    heartbeatIntervalSec: Int,
    audioLevels: Map<String, Float>,
    feedback: Map<String, MediaCommandFeedback>,
    recordingStates: Map<String, LocalRecordingState>,
    trackDeviceIds: Set<String>,
    onStreamingChange: (Boolean) -> Unit,
    onRecordingToggle: (MonitoredDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayed = devices.associate { item ->
        val actual = item.status?.microphoneStreaming == true
        item.device.id to MediaCommandPolicy.displayedValue(actual, feedback[item.device.id])
    }
    val streamState = GroupMediaPolicy.streamState(displayed.values)
    val activeRecordings = recordingStates.filterValues { it.isActive }.keys
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GroupStreamingControl(
            title = "Streaming audio",
            state = streamState,
            onChange = onStreamingChange,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(devices, key = { it.device.id }) { item ->
            val id = item.device.id
            val streaming = displayed[id] == true
            val recordingState = recordingStates[id] ?: LocalRecordingState.Idle
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (streaming) {
                                    "Livello ${(audioLevels[id] ?: 0f).times(100).toInt()}%"
                                } else {
                                    "Audio non attivo"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (feedback[id]?.phase == MediaCommandPhase.SENDING ||
                            feedback[id]?.phase == MediaCommandPhase.AWAITING_CONFIRMATION
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        }
                        RecordingButton(
                            state = recordingState,
                            enabled = streaming &&
                                id in trackDeviceIds &&
                                GroupMediaPolicy.canStartRecording(activeRecordings, id),
                            onClick = { onRecordingToggle(item) },
                        )
                    }
                    if (!item.isOnline(heartbeatIntervalSec = heartbeatIntervalSec)) {
                        Text(
                            "Dispositivo offline",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    feedback[id]?.errorMessage?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            }
            item {
                Text(
                    "È possibile registrare al massimo ${GroupMediaPolicy.MAX_SIMULTANEOUS_RECORDINGS} " +
                        "audio contemporaneamente.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun GroupVideoFullscreenScreen(
    devices: List<MonitoredDevice>,
    singleDeviceId: String?,
    cameraSwitchPending: Set<String>,
    videoRecordingStates: Map<String, LocalRecordingState>,
    videoTrackDeviceIds: Set<String>,
    onSingleFullscreen: (String) -> Unit,
    onVideoRecordingToggle: (MonitoredDevice) -> Unit,
    onCameraSwitch: (MonitoredDevice) -> Unit,
    onTakePhoto: (MonitoredDevice) -> Unit,
    onBack: () -> Unit,
    snapshotPreview: Bitmap?,
    onSnapshotAnimationFinished: () -> Unit,
    videoContent: @Composable (MonitoredDevice) -> Unit,
    singleBackContentDescription: String = "Torna alla griglia",
) {
    val single = devices.firstOrNull { it.device.id == singleDeviceId }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (single != null) {
            val id = single.device.id
            val recordingState = videoRecordingStates[id] ?: LocalRecordingState.Idle
            var rotationDegrees by rememberSaveable(id) { mutableIntStateOf(0) }
            Column(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black),
                ) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val rotated = rotationDegrees % 180 != 0
                        val videoModifier = if (rotated) {
                            Modifier.requiredSize(
                                width = maxHeight,
                                height = maxWidth,
                            )
                        } else {
                            Modifier.fillMaxSize()
                        }
                        Box(
                            modifier = videoModifier
                                .graphicsLayer(
                                    rotationZ = rotationDegrees.toFloat(),
                                ),
                        ) {
                            videoContent(single)
                        }
                    }
                    if (single.status?.cameraInterrupted == true) {
                        CameraInterruptionWarning(Modifier.align(Alignment.Center))
                    }
                    VideoRecordingTimer(
                        state = recordingState,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    )
                    Text(
                        single.displayName,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            rotationDegrees = (rotationDegrees + 90) % 360
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.RotateRight,
                            contentDescription = "Ruota video di 90 gradi",
                            tint = Color.White,
                            modifier = Modifier.graphicsLayer(
                                rotationZ = rotationDegrees.toFloat(),
                            ),
                        )
                    }
                    IconButton(
                        enabled = !recordingState.isActive && id !in cameraSwitchPending,
                        onClick = { onCameraSwitch(single) },
                    ) {
                        if (id in cameraSwitchPending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Cameraswitch,
                                contentDescription = "Cambia camera",
                                tint = Color.White,
                                modifier = Modifier.graphicsLayer(
                                    rotationZ = rotationDegrees.toFloat(),
                                ),
                            )
                        }
                    }
                    IconButton(
                        enabled = id in videoTrackDeviceIds,
                        onClick = { onTakePhoto(single) },
                    ) {
                        Icon(
                            Icons.Outlined.CameraAlt,
                            contentDescription = "Scatta foto",
                            tint = Color.White,
                            modifier = Modifier.graphicsLayer(
                                rotationZ = rotationDegrees.toFloat(),
                            ),
                        )
                    }
                    FullscreenRecordingButton(
                        state = recordingState,
                        enabled = id in videoTrackDeviceIds,
                        onClick = { onVideoRecordingToggle(single) },
                        iconRotationDegrees = rotationDegrees,
                    )
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Outlined.FullscreenExit,
                            contentDescription = singleBackContentDescription,
                            tint = Color.White,
                            modifier = Modifier.graphicsLayer(
                                rotationZ = rotationDegrees.toFloat(),
                            ),
                        )
                    }
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val visibleRows = GroupMediaPolicy.visibleGridRows(devices.size)
                val itemHeight = (maxHeight - 24.dp) / visibleRows
                LazyVerticalGrid(
                    columns = GridCells.Fixed(GroupMediaPolicy.GRID_COLUMNS),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        items = devices,
                        key = { index, item -> "${item.device.id}-$index" },
                    ) { _, item ->
                        Card(
                            onClick = { onSingleFullscreen(item.device.id) },
                            modifier = Modifier.height(itemHeight),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black),
                            ) {
                                videoContent(item)
                                if (item.status?.cameraInterrupted == true) {
                                    CameraInterruptionWarning(Modifier.align(Alignment.Center))
                                }
                                VideoRecordingTimer(
                                    state = videoRecordingStates[item.device.id]
                                        ?: LocalRecordingState.Idle,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp),
                                )
                                Text(
                                    item.displayName,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .background(Color.Black.copy(alpha = 0.65f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (single == null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.55f)),
            ) {
                Icon(
                    Icons.Outlined.FullscreenExit,
                    contentDescription = "Torna al dettaglio",
                    tint = Color.White,
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
private fun CameraInterruptionWarning(modifier: Modifier = Modifier) {
    Text(
        "La fotocamera locale ha interrotto lo streaming.\n" +
            "Spegnilo e riaccendilo per riprendere.",
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(12.dp),
        color = Color.White,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun GroupHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Text(
                "‹",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Text(
            title,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun FullscreenRecordingButton(
    state: LocalRecordingState,
    enabled: Boolean,
    onClick: () -> Unit,
    iconRotationDegrees: Int = 0,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled || state.isActive,
    ) {
        when (state) {
            LocalRecordingState.Starting,
            LocalRecordingState.Finalizing,
            -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
            )
            is LocalRecordingState.Recording -> Icon(
                Icons.Outlined.StopCircle,
                contentDescription = "Ferma registrazione",
                tint = Color.Red,
                modifier = Modifier.graphicsLayer(rotationZ = iconRotationDegrees.toFloat()),
            )
            LocalRecordingState.Idle -> Icon(
                Icons.Outlined.FiberManualRecord,
                contentDescription = "Registra",
                tint = Color.Red,
                modifier = Modifier.graphicsLayer(rotationZ = iconRotationDegrees.toFloat()),
            )
        }
    }
}

@Composable
private fun GroupStreamingControl(
    title: String,
    state: GroupStreamState,
    onChange: (Boolean) -> Unit,
    action: @Composable (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    when (state) {
                        GroupStreamState.OFF -> "Disattivo"
                        GroupStreamState.PARTIAL -> "Attivo su alcuni dispositivi"
                        GroupStreamState.ON -> "Attivo su tutti i dispositivi"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            action?.invoke()
            Switch(
                checked = state != GroupStreamState.OFF,
                onCheckedChange = onChange,
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun RecordingButton(
    state: LocalRecordingState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled || state.isActive,
    ) {
        Text(
            when (state) {
                LocalRecordingState.Idle -> "Registra"
                LocalRecordingState.Starting -> "Avvio…"
                is LocalRecordingState.Recording ->
                    RecordingPolicy.formatElapsed(state.elapsedMs)
                LocalRecordingState.Finalizing -> "Salvataggio…"
            },
        )
    }
}
