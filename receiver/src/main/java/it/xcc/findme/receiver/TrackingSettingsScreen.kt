package it.xcc.findme.receiver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.xcc.findme.core.MonitoredDevice
import it.xcc.findme.core.ReceiverTrackingSettings
import it.xcc.findme.core.TrackingSettingsUpdate

@Composable
fun TrackingSettingsScreen(
    settings: ReceiverTrackingSettings,
    devices: List<MonitoredDevice>,
    historyDeletionInProgress: Boolean,
    historyDeletionMessage: String,
    onBack: () -> Unit,
    onChange: (TrackingSettingsUpdate) -> Unit,
    onDeleteHistory: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeviceSelection by remember { mutableStateOf(false) }
    var showDeletionConfirmation by remember { mutableStateOf(false) }
    var selectedDeviceIds by remember { mutableStateOf(emptySet<String>()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Indietro")
            }
            Text(
                "Configurazioni generali",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        SettingOptions(
            title = "Frequenza offline",
            description = "Tempo di interrogazione quando non stai osservando il dispositivo.",
            values = listOf(30, 60, 90, 120),
            selected = settings.offlineLocationIntervalSec,
            label = { "$it s" },
            onSelected = {
                onChange(settings.toUpdate(offlineLocationIntervalSec = it))
            },
        )
        SettingOptions(
            title = "Frequenza online",
            description = "Tempo di interrogazione quando attivi l’osservazione rapida.",
            values = listOf(5, 10, 15, 20),
            selected = settings.onlineLocationIntervalSec,
            label = { "$it s" },
            onSelected = {
                onChange(settings.toUpdate(onlineLocationIntervalSec = it))
            },
        )
        SettingOptions(
            title = "Frequenza storico",
            description = "Tempo di salvataggio moltiplicando x volte la frequenza offline/online.",
            values = listOf(1, 2, 3),
            selected = settings.historyMultiplier,
            label = { "${it}x" },
            onSelected = {
                onChange(settings.toUpdate(historyMultiplier = it))
            },
        )
        BooleanSetting(
            title = "Solo movimento",
            description = "Salva una posizione solo dopo uno spostamento significativo.",
            checked = settings.onlyMovement,
            onCheckedChange = {
                onChange(settings.toUpdate(onlyMovement = it))
            },
        )
        SettingOptions(
            title = "Raggio avviso area",
            description = "Distanza dal punto di attivazione oltre la quale inviare l’avviso.",
            values = listOf(50, 100, 250, 500, 1000),
            selected = settings.geofenceRadiusM,
            label = { "$it m" },
            onSelected = {
                onChange(settings.toUpdate(geofenceRadiusM = it))
            },
        )
        SettingOptions(
            title = "Frequenza heartbeat",
            description = "Tempo di interrogazione per stato online e batteria.",
            values = listOf(30, 60, 90, 120),
            selected = settings.heartbeatIntervalSec,
            label = { "$it s" },
            onSelected = {
                onChange(settings.toUpdate(heartbeatIntervalSec = it))
            },
        )
        Text(
            "Le modifiche sono salvate subito e valgono per tutti i trasmettitori associati.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                selectedDeviceIds = emptySet()
                showDeviceSelection = true
            },
            enabled = devices.isNotEmpty() && !historyDeletionInProgress,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            if (historyDeletionInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onError,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Cancella storico posizioni")
            }
        }
        if (devices.isEmpty()) {
            Text(
                "Nessun trasmettitore associato.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (historyDeletionMessage.isNotBlank()) {
            Text(
                historyDeletionMessage,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (showDeviceSelection) {
        HistoryDeviceSelectionDialog(
            devices = devices,
            selectedDeviceIds = selectedDeviceIds,
            onSelectionChange = { selectedDeviceIds = it },
            onDismiss = { showDeviceSelection = false },
            onContinue = {
                showDeviceSelection = false
                showDeletionConfirmation = true
            },
        )
    }

    if (showDeletionConfirmation) {
        HistoryDeletionConfirmationDialog(
            devices = devices.filter { it.device.id in selectedDeviceIds },
            onDismiss = { showDeletionConfirmation = false },
            onConfirm = {
                showDeletionConfirmation = false
                onDeleteHistory(selectedDeviceIds)
            },
        )
    }
}

@Composable
private fun HistoryDeviceSelectionDialog(
    devices: List<MonitoredDevice>,
    selectedDeviceIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seleziona i trasmettitori") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                devices.forEach { device ->
                    val selected = device.device.id in selectedDeviceIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectionChange(
                                    if (selected) {
                                        selectedDeviceIds - device.device.id
                                    } else {
                                        selectedDeviceIds + device.device.id
                                    },
                                )
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = {
                                onSelectionChange(
                                    if (selected) {
                                        selectedDeviceIds - device.device.id
                                    } else {
                                        selectedDeviceIds + device.device.id
                                    },
                                )
                            },
                        )
                        Column {
                            Text(device.displayName)
                            if (device.displayName != device.device.name) {
                                Text(
                                    device.device.name,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onContinue,
                enabled = selectedDeviceIds.isNotEmpty(),
            ) {
                Text("Continua")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        },
    )
}

@Composable
private fun HistoryDeletionConfirmationDialog(
    devices: List<MonitoredDevice>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conferma cancellazione") },
        text = {
            Text(
                "Vuoi cancellare definitivamente lo storico delle posizioni di " +
                    "${devices.joinToString { it.displayName }}? " +
                    "L’operazione non può essere annullata.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Cancella")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        },
    )
}

@Composable
private fun <T> SettingOptions(
    title: String,
    description: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                values.forEach { value ->
                    FilterChip(
                        selected = value == selected,
                        onClick = { onSelected(value) },
                        label = { Text(label(value)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BooleanSetting(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private fun ReceiverTrackingSettings.toUpdate(
    offlineLocationIntervalSec: Int = this.offlineLocationIntervalSec,
    onlineLocationIntervalSec: Int = this.onlineLocationIntervalSec,
    historyMultiplier: Int = this.historyMultiplier,
    onlyMovement: Boolean = this.onlyMovement,
    heartbeatIntervalSec: Int = this.heartbeatIntervalSec,
    geofenceRadiusM: Int = this.geofenceRadiusM,
) = TrackingSettingsUpdate(
    offlineLocationIntervalSec = offlineLocationIntervalSec,
    onlineLocationIntervalSec = onlineLocationIntervalSec,
    historyMultiplier = historyMultiplier,
    onlyMovement = onlyMovement,
    heartbeatIntervalSec = heartbeatIntervalSec,
    geofenceRadiusM = geofenceRadiusM,
)
