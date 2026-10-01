/**
 * @author Infinity
 * @description Vista fullscreen della posizione corrente e relativi controlli.
 * @modified 29.09.2026 - MDS | Collegato l'accesso alla verifica distanza.
 */
package it.xcc.findme.receiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.xcc.findme.core.MonitoredDevice

@Composable
fun PositionFullscreenScreen(
    item: MonitoredDevice,
    heartbeatIntervalSec: Int,
    fastTrackingActive: Boolean,
    fastHistoryActive: Boolean,
    onFastTrackingChange: (Boolean) -> Unit,
    onFastHistoryChange: (Boolean) -> Unit,
    onGeofenceChange: (Boolean) -> Unit,
    onOpenHistory: () -> Unit,
    onVerifyDistance: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            val location = item.location
            if (location == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Posizione non disponibile.")
                }
            } else {
                DeviceMap(
                    deviceName = item.displayName,
                    location = location,
                    geofence = item.relationship,
                    fillAvailable = true,
                )
            }
        }
        Column(
            modifier = Modifier
                .width(310.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.displayName,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        item.device.name,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onExit) {
                    Icon(
                        Icons.Outlined.FullscreenExit,
                        contentDescription = "Torna alla vista normale",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            PositionCoordinates(item)
            PositionControls(
                item = item,
                heartbeatIntervalSec = heartbeatIntervalSec,
                fastTrackingActive = fastTrackingActive,
                fastHistoryActive = fastHistoryActive,
                onFastTrackingChange = onFastTrackingChange,
                onFastHistoryChange = onFastHistoryChange,
                onGeofenceChange = onGeofenceChange,
                onOpenHistory = onOpenHistory,
                onVerifyDistance = onVerifyDistance,
            )
        }
    }
}
