package it.xcc.findme.receiver

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
            selectedIndex = 0
            error = ""
        }.onFailure {
            error = it.message ?: "Caricamento storico non riuscito."
        }
        loading = false
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                Triple("6h", Duration.ofHours(6), "Ultime 6 ore"),
                Triple("24h", Duration.ofHours(24), "Ultime 24 ore"),
                Triple("7g", Duration.ofDays(7), "Ultimi 7 giorni"),
            ).forEach { (label, duration, accessibilityLabel) ->
                FilterChip(
                    selected = activePreset == label,
                    onClick = { selectPreset(label, duration) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
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
                DateTimeButton(
                    label = "Da",
                    instant = from,
                    onSelected = {
                        from = it
                        activePreset = ""
                    },
                    modifier = Modifier.weight(1f),
                )
                DateTimeButton(
                    label = "A",
                    instant = to,
                    onSelected = {
                        to = it
                        activePreset = ""
                    },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = ::executeQuery) {
                    Text("Cerca")
                }
            }
        }
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (route.isEmpty()) {
            Text(
                "Nessun punto nel periodo selezionato.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val safeIndex = selectedIndex.coerceIn(route.indices)
            HistoryMap(route, safeIndex)
            Slider(
                value = safeIndex.toFloat(),
                onValueChange = { selectedIndex = it.roundToInt() },
                valueRange = 0f..route.lastIndex.toFloat().coerceAtLeast(0f),
                steps = (route.size - 2).coerceAtLeast(0),
                enabled = route.size > 1,
            )
            HistoryPointText(route[safeIndex], prefix = "Punto selezionato")
        }
        Text("Punti registrati", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(listPoints, key = { it.id }) { point ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    HistoryPointText(
                        point = point,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            if (hasMore) {
                item {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                runCatching {
                                    loadPage(from, to, listPoints.size.toLong())
                                }.onSuccess { page ->
                                    listPoints = listPoints + page.points
                                    hasMore = page.hasMore
                                }.onFailure {
                                    error = it.message ?: "Caricamento pagina non riuscito."
                                }
                            }
                        },
                    ) {
                        Text("Carica altri 50 punti")
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
        Text("$label\n${DATE_TIME_FORMAT.format(dateTime)}")
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

private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm")
