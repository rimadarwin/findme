package it.xcc.findme.receiver

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.xcc.findme.core.MonitoredDevice
import it.xcc.findme.core.ReceiverProfile

@Composable
fun ReceiverHomeScreen(
    profile: ReceiverProfile?,
    receiverId: String,
    devices: List<MonitoredDevice>,
    heartbeatIntervalSec: Int,
    onDeviceClick: (String) -> Unit,
    onAliasSave: (String, String) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Questo telefono",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = "Impostazioni tracking",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        profile?.name ?: "Ricevitore FindMe",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Codice di associazione",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        profile?.pairingCode ?: "Generazione…",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "ID $receiverId",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Text(
                "Dispositivi associati (${devices.size})",
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        if (devices.isEmpty()) {
            item {
                Text(
                    "Nessun dispositivo associato.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(devices, key = { it.device.id }) { device ->
                AssociatedDeviceCard(
                    item = device,
                    heartbeatIntervalSec = heartbeatIntervalSec,
                    onClick = { onDeviceClick(device.device.id) },
                    onAliasSave = { onAliasSave(device.device.id, it) },
                )
            }
        }
    }
}

@Composable
private fun AssociatedDeviceCard(
    item: MonitoredDevice,
    heartbeatIntervalSec: Int,
    onClick: () -> Unit,
    onAliasSave: (String) -> Unit,
) {
    val monitoringActive = item.isMonitoringActive(
        heartbeatIntervalSec = heartbeatIntervalSec,
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EditableDeviceName(
                    item = item,
                    onAliasSave = onAliasSave,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(12.dp)
                        .background(
                            if (monitoringActive) MonitoringGreen else Color(0xFF40505B),
                            CircleShape,
                        ),
                )
            }
            Text(
                buildString {
                    append(
                        if (item.isOnline(heartbeatIntervalSec = heartbeatIntervalSec)) {
                            "Online"
                        } else {
                            "Offline"
                        },
                    )
                    append("  •  Batteria ")
                    append(item.status?.batteryPercent?.let { "$it%" } ?: "n/d")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
