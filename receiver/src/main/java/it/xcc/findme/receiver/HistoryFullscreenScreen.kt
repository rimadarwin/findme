package it.xcc.findme.receiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.xcc.findme.core.LocationHistoryPoint
import it.xcc.findme.core.MonitoredDevice
import kotlin.math.roundToInt

@Composable
fun HistoryFullscreenScreen(
    device: MonitoredDevice,
    route: List<LocationHistoryPoint>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeIndex = selectedIndex.coerceIn(0..route.lastIndex.coerceAtLeast(0))
    val selectedPoint = route.getOrNull(safeIndex)
    Row(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            HistoryMap(
                points = route,
                selectedIndex = safeIndex,
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Storico posizioni",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        device.displayName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onExit) {
                    Icon(
                        Icons.Outlined.FullscreenExit,
                        contentDescription = "Torna allo storico",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Slider(
                value = safeIndex.toFloat(),
                onValueChange = { onSelectedIndexChange(it.roundToInt()) },
                valueRange = 0f..route.lastIndex.toFloat().coerceAtLeast(0f),
                steps = (route.size - 2).coerceAtLeast(0),
                enabled = route.size > 1,
            )
            Text(
                "Punto ${safeIndex + 1} di ${route.size}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            selectedPoint?.let { point ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    HistoryPointText(
                        point = point,
                        prefix = "Punto selezionato",
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}
