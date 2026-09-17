package it.xcc.findme.receiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.xcc.findme.core.ReceiverTrackingSettings
import it.xcc.findme.core.TrackingSettingsUpdate

@Composable
fun TrackingSettingsScreen(
    settings: ReceiverTrackingSettings,
    onBack: () -> Unit,
    onChange: (TrackingSettingsUpdate) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                "Impostazioni tracking",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        SettingOptions(
            title = "Posizione in background",
            description = "Frequenza quando non stai seguendo il dispositivo.",
            values = listOf(30, 60, 90, 120),
            selected = settings.offlineLocationIntervalSec,
            label = { "$it s" },
            onSelected = {
                onChange(settings.toUpdate(offlineLocationIntervalSec = it))
            },
        )
        SettingOptions(
            title = "Aggiornamento rapido",
            description = "Frequenza usata mentre l’occhio è attivo.",
            values = listOf(5, 10, 15, 20),
            selected = settings.onlineLocationIntervalSec,
            label = { "$it s" },
            onSelected = {
                onChange(settings.toUpdate(onlineLocationIntervalSec = it))
            },
        )
        SettingOptions(
            title = "Frequenza storico",
            description = "Moltiplica la frequenza di posizione scelta.",
            values = listOf(1, 2, 3),
            selected = settings.historyMultiplier,
            label = { "${it}x" },
            onSelected = {
                onChange(settings.toUpdate(historyMultiplier = it))
            },
        )
        BooleanSetting(
            title = "Solo movimento",
            description = "Salva un punto solo dopo uno spostamento significativo.",
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
            title = "Heartbeat",
            description = "Frequenza dello stato online e della batteria.",
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
    }
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
