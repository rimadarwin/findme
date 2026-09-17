package it.xcc.findme.receiver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.xcc.findme.core.CommandType
import it.xcc.findme.core.MonitoredDevice

enum class DeviceTab(val label: String) {
    POSITION("Posizione"),
    VIDEO("Video"),
    AUDIO("Audio"),
}

@Composable
fun DeviceDetailScreen(
    item: MonitoredDevice,
    selectedTab: DeviceTab,
    audioLevel: Float,
    onTabSelected: (DeviceTab) -> Unit,
    onBack: () -> Unit,
    onAliasSave: (String) -> Unit,
    onCommand: (CommandType) -> Unit,
    videoContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DeviceHeader(item, onBack, onAliasSave)
        TabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            DeviceTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    text = { Text(tab.label) },
                )
            }
        }
        when (selectedTab) {
            DeviceTab.POSITION -> PositionTab(item)
            DeviceTab.VIDEO -> VideoTab(item, onCommand, videoContent)
            DeviceTab.AUDIO -> AudioTab(item, audioLevel, onCommand)
        }
    }
}

@Composable
private fun DeviceHeader(
    item: MonitoredDevice,
    onBack: () -> Unit,
    onAliasSave: (String) -> Unit,
) {
    val monitoringActive = item.isMonitoringActive()
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
                EditableDeviceName(item = item, onAliasSave = onAliasSave)
                Text(
                    "${if (item.isOnline()) "Online" else "Offline"}  •  " +
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
private fun PositionTab(item: MonitoredDevice) {
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
            Text(
                "%.6f, %.6f  •  ±%.0f m".format(
                    location.latitude,
                    location.longitude,
                    location.accuracy ?: 0f,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            DeviceMap(item.displayName, location)
        }
    }
}

@Composable
private fun VideoTab(
    item: MonitoredDevice,
    onCommand: (CommandType) -> Unit,
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
            enabled = item.isOnline() && item.status?.cameraAvailable == true,
            onCheckedChange = {
                onCommand(if (it) CommandType.START_VIDEO else CommandType.STOP_VIDEO)
            },
            action = {
                IconButton(
                    enabled = streaming,
                    onClick = { onCommand(CommandType.SWITCH_CAMERA) },
                ) {
                    Text(
                        "↻",
                        color = if (streaming) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            },
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
private fun AudioTab(
    item: MonitoredDevice,
    audioLevel: Float,
    onCommand: (CommandType) -> Unit,
) {
    val streaming = item.status?.microphoneStreaming == true
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MediaSwitch(
            title = "Streaming audio",
            checked = streaming,
            enabled = item.isOnline() && item.status?.microphoneAvailable == true,
            onCheckedChange = {
                onCommand(if (it) CommandType.START_AUDIO else CommandType.STOP_AUDIO)
            },
        )
        AudioVisualizer(level = audioLevel, active = streaming)
        Text(
            if (streaming) "Livello audio in tempo reale" else "Audio non attivo",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
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
