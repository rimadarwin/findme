package it.xcc.findme.receiver

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import it.xcc.findme.core.AppConfig
import it.xcc.findme.core.CommandType
import it.xcc.findme.core.DeviceIdentity
import it.xcc.findme.core.DeviceRole
import it.xcc.findme.core.FindMeRepository
import it.xcc.findme.core.MonitoredDevice
import it.xcc.findme.core.ReceiverProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ReceiverActivity : ComponentActivity() {
    private var repository: FindMeRepository? = null
    private lateinit var identity: DeviceIdentity
    private var ready by mutableStateOf(false)
    private var receiverProfile by mutableStateOf<ReceiverProfile?>(null)
    private var devices by mutableStateOf<List<MonitoredDevice>>(emptyList())
    private var selectedDeviceId by mutableStateOf<String?>(null)
    private var selectedTab by mutableStateOf(DeviceTab.POSITION)
    private var message by mutableStateOf("")
    private var audioLevel by mutableFloatStateOf(0f)
    private var videoTrack by mutableStateOf<VideoTrack?>(null)
    private var room: Room? = null
    private var renderer: TextureViewRenderer? = null
    private var deviceJob: Job? = null
    private var profileJob: Job? = null
    private var roomEventsJob: Job? = null
    private var audioMeterJob: Job? = null
    private var connectionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        identity = DeviceIdentity(this)
        if (AppConfig.isConfigured) {
            repository = runCatching { FindMeRepository() }.getOrNull()
        }
        setContent {
            FindMeReceiverTheme {
                ReceiverApp()
            }
        }
        initializeDevice()
    }

    override fun onStart() {
        super.onStart()
        if (room == null) selectedDeviceId?.let(::connectTo)
    }

    override fun onDestroy() {
        disconnectMedia()
        deviceJob?.cancel()
        profileJob?.cancel()
        super.onDestroy()
    }

    @Composable
    private fun ReceiverApp() {
        val selected = selectedDeviceId?.let { id ->
            devices.firstOrNull { it.device.id == id }
        }
        BackHandler(enabled = selected != null) { closeDetail() }

        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "FindMe",
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                )
                when {
                    !AppConfig.isConfigured ->
                        Text("Configurazione mancante: completa local.properties seguendo README.md.")
                    !ready -> Text("Inizializzazione sicura del dispositivo…")
                    selected == null -> ReceiverHomeScreen(
                        profile = receiverProfile,
                        receiverId = identity.id,
                        devices = devices,
                        onDeviceClick = ::openDetail,
                        onAliasSave = ::updateAlias,
                        modifier = Modifier.weight(1f),
                    )
                    else -> DeviceDetailScreen(
                        item = selected,
                        selectedTab = selectedTab,
                        audioLevel = audioLevel,
                        onTabSelected = { selectedTab = it },
                        onBack = ::closeDetail,
                        onAliasSave = { updateAlias(selected.device.id, it) },
                        onCommand = { command(selected, it) },
                        videoContent = {
                            VideoSurface(
                                streaming = selected.status?.cameraStreaming == true,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (message.isNotBlank()) {
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    @Composable
    private fun VideoSurface(streaming: Boolean) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                TextureViewRenderer(context).also { view ->
                    renderer = view
                    room?.initVideoRenderer(view)
                }
            },
        )
        LaunchedEffect(streaming, videoTrack) {
            renderer?.let { view ->
                videoTrack?.removeRenderer(view)
                if (streaming) {
                    videoTrack?.addRenderer(view)
                } else {
                    view.clearImage()
                }
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                renderer?.let { view ->
                    videoTrack?.removeRenderer(view)
                    view.clearImage()
                    view.release()
                }
                renderer = null
            }
        }
    }

    private fun initializeDevice() {
        lifecycleScope.launch {
            runCatching {
                repository!!.ensureAuthenticated()
                repository!!.registerDevice(identity.id, identity.name, DeviceRole.RECEIVER)
                repository!!.registerReceiver(identity.id, identity.name)
            }.onSuccess {
                ready = true
                observeReceiverProfile()
                observeDevices()
            }.onFailure {
                message = it.message ?: "Inizializzazione non riuscita."
            }
        }
    }

    private fun observeReceiverProfile() {
        profileJob?.cancel()
        profileJob = lifecycleScope.launch {
            repository!!.receiverProfile(identity.id).collect { receiverProfile = it }
        }
    }

    private fun observeDevices() {
        deviceJob?.cancel()
        deviceJob = lifecycleScope.launch {
            runCatching {
                repository!!.monitoredDevices().collect { rows ->
                    devices = rows
                    val selectedId = selectedDeviceId
                    if (selectedId != null && rows.none { it.device.id == selectedId }) {
                        closeDetail()
                    }
                }
            }.onFailure {
                message = it.message ?: "Errore aggiornamento dispositivi."
            }
        }
    }

    private fun openDetail(deviceId: String) {
        selectedDeviceId = deviceId
        selectedTab = DeviceTab.POSITION
        message = ""
        connectTo(deviceId)
    }

    private fun closeDetail() {
        selectedDeviceId?.let(::stopAllStreams)
        selectedDeviceId = null
        selectedTab = DeviceTab.POSITION
        message = ""
        disconnectMedia()
    }

    private fun stopAllStreams(deviceId: String) {
        lifecycleScope.launch {
            runCatching {
                repository!!.sendCommand(deviceId, CommandType.STOP_VIDEO)
                repository!!.sendCommand(deviceId, CommandType.STOP_AUDIO)
            }.onFailure {
                message = it.message ?: "Chiusura stream non riuscita."
            }
        }
    }

    private fun command(device: MonitoredDevice, type: CommandType) {
        lifecycleScope.launch {
            runCatching { repository!!.sendCommand(device.device.id, type) }
                .onFailure { message = it.message ?: "Invio comando non riuscito." }
        }
    }

    private fun updateAlias(transmitterId: String, alias: String) {
        lifecycleScope.launch {
            runCatching {
                repository!!.updateTransmitterAlias(
                    receiverId = identity.id,
                    transmitterId = transmitterId,
                    alias = alias,
                )
            }.onSuccess {
                message = ""
            }.onFailure {
                message = it.message ?: "Salvataggio nome non riuscito."
            }
        }
    }

    private fun connectTo(deviceId: String) {
        connectionJob?.cancel()
        connectionJob = lifecycleScope.launch {
            runCatching {
                disconnectMedia(cancelConnection = false)
                val credentials = repository!!.liveKitToken(deviceId, "subscribe")
                room = LiveKit.create(applicationContext).also { newRoom ->
                    renderer?.let(newRoom::initVideoRenderer)
                    roomEventsJob = lifecycleScope.launch {
                        newRoom.events.collect { event ->
                            Log.d(TAG, "LiveKit room event: ${event::class.simpleName}")
                            when {
                                event is RoomEvent.TrackSubscribed && event.track is VideoTrack -> {
                                    bindVideoTrack(event.track as VideoTrack)
                                }
                                event is RoomEvent.TrackMuted &&
                                    event.publication.kind == Track.Kind.VIDEO -> {
                                    renderer?.clearImage()
                                }
                                event is RoomEvent.TrackUnmuted &&
                                    event.publication.kind == Track.Kind.VIDEO -> {
                                    (event.publication.track as? VideoTrack)?.let(::bindVideoTrack)
                                }
                                event is RoomEvent.TrackMuted &&
                                    event.publication.kind == Track.Kind.AUDIO -> {
                                    audioLevel = 0f
                                }
                                event is RoomEvent.ActiveSpeakersChanged -> {
                                    audioLevel = event.speakers.maxOfOrNull { it.audioLevel } ?: 0f
                                }
                                event is RoomEvent.Disconnected -> {
                                    audioLevel = 0f
                                    renderer?.clearImage()
                                }
                            }
                        }
                    }
                    audioMeterJob = lifecycleScope.launch {
                        while (isActive) {
                            audioLevel = newRoom.remoteParticipants.values
                                .maxOfOrNull { it.audioLevel }
                                ?: 0f
                            delay(AUDIO_METER_INTERVAL_MS)
                        }
                    }
                    newRoom.connect(credentials.url, credentials.token)
                }
            }.onSuccess {
                Log.i(TAG, "LiveKit room connected for device $deviceId")
                message = ""
            }.onFailure {
                Log.e(TAG, "LiveKit connection failed for device $deviceId", it)
                message = "Connessione multimediale non riuscita."
            }
        }
    }

    private fun bindVideoTrack(track: VideoTrack) {
        renderer?.let { view ->
            videoTrack?.removeRenderer(view)
            track.addRenderer(view)
        }
        videoTrack = track
    }

    private fun disconnectMedia(cancelConnection: Boolean = true) {
        if (cancelConnection) connectionJob?.cancel()
        renderer?.let { view -> videoTrack?.removeRenderer(view) }
        roomEventsJob?.cancel()
        roomEventsJob = null
        audioMeterJob?.cancel()
        audioMeterJob = null
        room?.disconnect()
        room = null
        videoTrack = null
        audioLevel = 0f
        renderer?.clearImage()
    }

    private companion object {
        const val TAG = "FindMeReceiver"
        const val AUDIO_METER_INTERVAL_MS = 100L
    }
}
