/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Schermata normale e fullscreen per verificare la distanza TX-RX.
 * @modified 29.09.2026 - MDS | Uniformato il ritorno alla schermata dello storico.
 * @modified 29.09.2026 - MDS | Resi visibili i controlli di ritorno normale e fullscreen.
 * @modified 29.09.2026 - MDS | Prima implementazione con coordinate e distanza Haversine.
 */
package it.xcc.findme.receiver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.xcc.findme.core.DeviceLocation
import it.xcc.findme.core.MonitoredDevice
import it.xcc.findme.core.TrackingConfigResolver

/** Mostra la verifica distanza nella variante normale o fullscreen. */
@Composable
fun DistanceVerificationScreen(
    device: MonitoredDevice,
    receiverLocation: DeviceLocation?,
    gpsAvailable: Boolean,
    fullscreen: Boolean,
    onBack: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (fullscreen) {
        DistanceFullscreenContent(
            device = device,
            receiverLocation = receiverLocation,
            gpsAvailable = gpsAvailable,
            onExit = { onFullscreenChange(false) },
            modifier = modifier,
        )
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Text(
                        "‹",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                Text(
                    "Verifica distanza",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            DistanceCoordinates(device.location, receiverLocation)
            DistanceMapCard(
                device = device,
                receiverLocation = receiverLocation,
                gpsAvailable = gpsAvailable,
                onFullscreen = { onFullscreenChange(true) },
            )
            DistanceValue(device.location, receiverLocation)
        }
    }
}

/** Compone mappa e dati in orientamento orizzontale immersivo. */
@Composable
private fun DistanceFullscreenContent(
    device: MonitoredDevice,
    receiverLocation: DeviceLocation?,
    gpsAvailable: Boolean,
    onExit: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            DistanceMapOrStatus(
                device.location,
                receiverLocation,
                gpsAvailable,
                fillAvailable = true,
            )
        }
        Column(
            modifier = Modifier
                .width(310.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Verifica distanza",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = onExit) {
                    Icon(
                        Icons.Outlined.FullscreenExit,
                        contentDescription = "Torna alla vista normale",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            DistanceCoordinates(device.location, receiverLocation)
            DistanceValue(device.location, receiverLocation)
        }
    }
}

/** Incapsula la mappa normale e il comando di ingresso fullscreen. */
@Composable
private fun DistanceMapCard(
    device: MonitoredDevice,
    receiverLocation: DeviceLocation?,
    gpsAvailable: Boolean,
    onFullscreen: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box {
            DistanceMapOrStatus(device.location, receiverLocation, gpsAvailable)
            if (device.location != null && receiverLocation != null) {
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
                        contentDescription = "Distanza a schermo intero",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** Mostra la mappa solo quando entrambe le posizioni sono disponibili. */
@Composable
private fun DistanceMapOrStatus(
    transmitterLocation: DeviceLocation?,
    receiverLocation: DeviceLocation?,
    gpsAvailable: Boolean,
    fillAvailable: Boolean = false,
) {
    if (transmitterLocation != null && receiverLocation != null) {
        DistanceMap(
            transmitterLocation = transmitterLocation,
            receiverLocation = receiverLocation,
            fillAvailable = fillAvailable,
        )
    } else {
        Box(
            modifier = if (fillAvailable) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxWidth().height(320.dp)
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    !gpsAvailable -> "GPS del ricevitore non disponibile."
                    transmitterLocation == null -> "Posizione del trasmettitore non disponibile."
                    else -> "In attesa della posizione del ricevitore…"
                },
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Riepiloga coordinate e accuratezza dei due telefoni. */
@Composable
private fun DistanceCoordinates(
    transmitterLocation: DeviceLocation?,
    receiverLocation: DeviceLocation?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CoordinateRow("Trasmettitore", transmitterLocation, Color(0xFF00AEEF))
        CoordinateRow("Ricevitore", receiverLocation, Color(0xFFFF5252))
    }
}

/** Disegna una riga coordinata con il colore del marker corrispondente. */
@Composable
private fun CoordinateRow(label: String, location: DeviceLocation?, color: Color) {
    Text(
        if (location == null) {
            "$label: posizione non disponibile"
        } else {
            "$label: %.6f, %.6f • ±%.0f m".format(
                location.latitude,
                location.longitude,
                location.accuracy ?: 0f,
            )
        },
        color = color,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Calcola e presenta la distanza Haversine in metri. */
@Composable
private fun DistanceValue(
    transmitterLocation: DeviceLocation?,
    receiverLocation: DeviceLocation?,
) {
    val distance = if (transmitterLocation != null && receiverLocation != null) {
        TrackingConfigResolver.distanceMeters(transmitterLocation, receiverLocation)
    } else {
        null
    }
    Text(
        distance?.let { "Distanza in linea d’aria: %.1f m".format(it) }
            ?: "Distanza in linea d’aria: non disponibile",
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleMedium,
    )
}
