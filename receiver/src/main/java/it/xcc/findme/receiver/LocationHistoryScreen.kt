package it.xcc.findme.receiver

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.xcc.findme.core.LocationHistoryPage
import it.xcc.findme.core.LocationHistoryPoint
import it.xcc.findme.core.MonitoredDevice
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun LocationHistoryScreen(
    device: MonitoredDevice,
    onBack: () -> Unit,
    loadRoute: suspend (Instant, Instant) -> List<LocationHistoryPoint>,
    loadPage: suspend (Instant, Instant, Long) -> LocationHistoryPage,
    modifier: Modifier = Modifier,
) {
    var from by remember(device.device.id) { mutableStateOf(Instant.now().minus(Duration.ofHours(24))) }
    var to by remember(device.device.id) { mutableStateOf(Instant.now()) }
    var activePreset by remember { mutableStateOf("24h") }
    var queryVersion by remember { mutableIntStateOf(0) }
    var route by remember { mutableStateOf<List<LocationHistoryPoint>>(emptyList()) }
    var listPoints by remember { mutableStateOf<List<LocationHistoryPoint>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }
    var filtersExpanded by remember(device.device.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun executeQuery() {
        val validation = validateRange(from, to)
        if (validation != null) {
            error = validation
        } else {
            error = ""
            queryVersion++
        }
    }

    fun selectPreset(label: String, duration: Duration) {
        to = Instant.now()
        from = to.minus(duration)
        activePreset = label
        executeQuery()
    }

    LaunchedEffect(device.device.id, queryVersion) {
        val validation = validateRange(from, to)
        if (validation != null) {
            error = validation
            return@LaunchedEffect
        }
        loading = true
        runCatching {
            val newRoute = loadRoute(from, to)
            val firstPage = loadPage(from, to, 0)
            newRoute to firstPage
        }.onSuccess { (newRoute, firstPage) ->
            route = newRoute
            listPoints = firstPage.points
            hasMore = firstPage.hasMore
            selectedIndex = newRoute.lastIndex.coerceAtLeast(0)
            error = ""
        }.onFailure {
            error = it.message ?: "Caricamento storico non riuscito."
        }
        loading = false
    }

    val safeIndex = selectedIndex.coerceIn(0..route.lastIndex.coerceAtLeast(0))
    val selectedPoint = route.getOrNull(safeIndex)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Indietro")
                }
                Column {
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
            }
        }
        item {
            HistoryFilters(
                expanded = filtersExpanded,
                activePreset = activePreset,
                from = from,
                to = to,
                onExpandedChange = { filtersExpanded = it },
                onPresetSelected = ::selectPreset,
                onFromSelected = {
                    from = it
                    activePreset = ""
                },
                onToSelected = {
                    to = it
                    activePreset = ""
                },
                onSearch = ::executeQuery,
            )
        }
        if (error.isNotBlank()) {
            item {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
        if (loading) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (route.isEmpty()) {
            item {
                Text(
                    "Nessun punto nel periodo selezionato.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                HistoryMap(route, safeIndex)
            }
            item {
                Column {
                    Slider(
                        value = safeIndex.toFloat(),
                        onValueChange = { selectedIndex = it.roundToInt() },
                        valueRange = 0f..route.lastIndex.toFloat().coerceAtLeast(0f),
                        steps = (route.size - 2).coerceAtLeast(0),
                        enabled = route.size > 1,
                    )
                    Text(
                        "Percorso visualizzato: ${safeIndex + 1} di ${route.size} punti",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                selectedPoint?.let {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        HistoryPointText(
                            point = it,
                            prefix = "Punto selezionato",
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
        item {
            Text("Punti registrati", style = MaterialTheme.typography.titleMedium)
        }
        items(listPoints, key = { it.id }) { point ->
            val isSelected = point.id == selectedPoint?.id
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
            ) {
                HistoryPointText(
                    point = point,
                    prefix = if (isSelected) "Punto selezionato" else null,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        if (hasMore) {
            item {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loadingMore,
                    onClick = {
                        scope.launch {
                            loadingMore = true
                            runCatching {
                                loadPage(from, to, listPoints.size.toLong())
                            }.onSuccess { page ->
                                listPoints = listPoints + page.points
                                hasMore = page.hasMore
                            }.onFailure {
                                error = it.message ?: "Caricamento pagina non riuscito."
                            }
                            loadingMore = false
                        }
                    },
                ) {
                    if (loadingMore) {
                        CircularProgressIndicator()
                    } else {
                        Text("Carica altri 50 punti")
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryFilters(
    expanded: Boolean,
    activePreset: String,
    from: Instant,
    to: Instant,
    onExpandedChange: (Boolean) -> Unit,
    onPresetSelected: (String, Duration) -> Unit,
    onFromSelected: (Instant) -> Unit,
    onToSelected: (Instant) -> Unit,
    onSearch: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Filtri periodo",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                activePreset.ifBlank {
                    "${SHORT_DATE_FORMAT.format(from.atZone(ZoneId.systemDefault()))} – " +
                        SHORT_DATE_FORMAT.format(to.atZone(ZoneId.systemDefault()))
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
            IconButton(onClick = { onExpandedChange(!expanded) }) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Comprimi filtri" else "Espandi filtri",
                )
            }
        }
        if (expanded) {
            HorizontalDivider()
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "6h" to Duration.ofHours(6),
                        "24h" to Duration.ofHours(24),
                        "7g" to Duration.ofDays(7),
                    ).forEach { (label, duration) ->
                        FilterChip(
                            selected = activePreset == label,
                            onClick = { onPresetSelected(label, duration) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DateTimeButton(
                            label = "Da",
                            instant = from,
                            onSelected = onFromSelected,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FILTER_ROW_HEIGHT),
                        )
                        DateTimeButton(
                            label = "A",
                            instant = to,
                            onSelected = onToSelected,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FILTER_ROW_HEIGHT),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.height(FILTER_ROW_HEIGHT))
                        Button(
                            onClick = onSearch,
                            modifier = Modifier.height(FILTER_ROW_HEIGHT),
                        ) {
                            Text("Cerca")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DateTimeButton(
    label: String,
    instant: Instant,
    onSelected: (Instant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val dateTime = instant.atZone(zone)
    OutlinedButton(
        modifier = modifier,
        onClick = {
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            onSelected(
                                ZonedDateTime.of(
                                    year,
                                    month + 1,
                                    day,
                                    hour,
                                    minute,
                                    0,
                                    0,
                                    zone,
                                ).toInstant(),
                            )
                        },
                        dateTime.hour,
                        dateTime.minute,
                        true,
                    ).show()
                },
                dateTime.year,
                dateTime.monthValue - 1,
                dateTime.dayOfMonth,
            ).show()
        },
    ) {
        Text(
            "$label: ${DATE_TIME_FORMAT.format(dateTime)}",
            maxLines = 1,
        )
    }
}

@Composable
private fun HistoryPointText(
    point: LocationHistoryPoint,
    modifier: Modifier = Modifier,
    prefix: String? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (prefix != null) {
            Text(prefix, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            DATE_TIME_FORMAT.format(Instant.parse(point.recordedAt).atZone(ZoneId.systemDefault())),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "%.6f, %.6f  •  ±%.0f m".format(
                point.latitude,
                point.longitude,
                point.accuracy ?: 0f,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun validateRange(from: Instant, to: Instant): String? = when {
    !from.isBefore(to) -> "L’inizio deve precedere la fine."
    Duration.between(from, to) > Duration.ofDays(7) ->
        "Ogni ricerca può coprire al massimo 7 giorni."
    from.isBefore(Instant.now().minus(Duration.ofDays(30))) ->
        "Lo storico è disponibile per gli ultimi 30 giorni."
    else -> null
}

private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm:ss")
private val SHORT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private val FILTER_ROW_HEIGHT = 48.dp
