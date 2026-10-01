/**
 * @author Infinity
 * @description Activity principale del ricevitore e coordinamento delle funzioni remote.
 * @modified 01.10.2026 - Infinity | Reso deterministico il lifecycle dei renderer video.
 * @modified 01.10.2026 - Infinity | Aggiunta visualizzazione LiveKit condivisa multi-dispositivo.
 * @modified 01.10.2026 - Infinity | Uniformati layout secondari e aggiunta gestione challenge.
 * @modified 29.09.2026 - MDS | Aggiunto invio e feedback dei messaggi testuali.
 * @modified 29.09.2026 - MDS | Aggiunta verifica distanza con GPS locale e fullscreen.
 * @modified 29.09.2026 - MDS | Allineati switch e lifecycle al tracking persistente server-driven.
 * @modified 23.09.2026 - MDS | Forzato il portrait in uscita e aggiunto feedback cambio camera.
 * @modified 23.09.2026 - MDS | Mantenuto il tracking rapido durante il blocco schermo.
 * @modified 23.09.2026 - MDS | Aggiunto feedback verificato per i comandi multimediali.
 */
package it.xcc.findme.receiver

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.RendererCommon
import it.xcc.findme.core.AppConfig
import it.xcc.findme.core.CameraSwitchFeedback
import it.xcc.findme.core.CommandType
import it.xcc.findme.core.ConnectionRecoveryPolicy
import it.xcc.findme.core.DeviceIdentity
import it.xcc.findme.core.DeviceLocation
import it.xcc.findme.core.DeviceRole
import it.xcc.findme.core.FindMeRepository
import it.xcc.findme.core.LocationHistoryDeletionPolicy
import it.xcc.findme.core.GroupMediaPolicy
import it.xcc.findme.core.MediaCommandKey
import it.xcc.findme.core.MediaCommandFeedback
import it.xcc.findme.core.MediaCommandPhase
import it.xcc.findme.core.MediaCommandPolicy
import it.xcc.findme.core.MediaConnectionPolicy
import it.xcc.findme.core.MediaStreamKind
import it.xcc.findme.core.MonitoredDevice
import it.xcc.findme.core.ReceiverAccessConfiguration
import it.xcc.findme.core.ReceiverProfile
import it.xcc.findme.core.ReceiverPowerPolicy
import it.xcc.findme.core.ReceiverTrackingSettings
import it.xcc.findme.core.TrackingConfigResolver
import it.xcc.findme.core.TrackingSettingsUpdate
import it.xcc.findme.core.TextMessagePolicy
import it.xcc.findme.core.TextMessageStatus
import it.xcc.findme.core.VoiceMessagePolicy
import it.xcc.findme.core.VoiceMessageStatus
import it.xcc.findme.core.VoiceMessageVolume
import it.xcc.findme.receiver.recording.AudioM4aRecorder
import it.xcc.findme.receiver.recording.LocalRecordingState
import it.xcc.findme.receiver.recording.RecordingPolicy
import it.xcc.findme.receiver.recording.RecordingResult
import it.xcc.findme.receiver.recording.RecordingKind
import it.xcc.findme.receiver.recording.VideoMp4Recorder
import java.time.Instant
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class ReceiverActivity : ComponentActivity() {
    private var repository: FindMeRepository? = null
    private lateinit var identity: DeviceIdentity
    private var ready by mutableStateOf(false)
    private var receiverProfile by mutableStateOf<ReceiverProfile?>(null)
    private var receiverAccessConfiguration by mutableStateOf<ReceiverAccessConfiguration?>(null)
    private var accessUpdateInProgress by mutableStateOf(false)
    private var accessUpdateMessage by mutableStateOf("")
    private var devices by mutableStateOf<List<MonitoredDevice>>(emptyList())
    private lateinit var powerManager: PowerManager
    private lateinit var mediaWakeLock: PowerManager.WakeLock
    private var mediaPowerProtectionActive = false
    private var selectedDeviceId by mutableStateOf<String?>(null)
    private var selectedTab by mutableStateOf(DeviceTab.POSITION)
    private var showGroupSelection by mutableStateOf(false)
    private var groupMediaActive by mutableStateOf(false)
    private var groupSelectedDeviceIds by mutableStateOf<Set<String>>(emptySet())
    private var groupSelectedTab by mutableStateOf(GroupMediaTab.VIDEO)
    private var groupFullscreenActive by mutableStateOf(false)
    private var groupSingleFullscreenDeviceId by mutableStateOf<String?>(null)
    private var showSettings by mutableStateOf(false)
    private var historyDeviceId by mutableStateOf<String?>(null)
    private var distanceDeviceId by mutableStateOf<String?>(null)
    private var distanceFullscreenActive by mutableStateOf(false)
    private var receiverLocation by mutableStateOf<DeviceLocation?>(null)
    private var receiverGpsAvailable by mutableStateOf(false)
    private var fullscreenDeviceId by mutableStateOf<String?>(null)
    private var videoFullscreenDeviceId by mutableStateOf<String?>(null)
    private var screenFullscreenDeviceId by mutableStateOf<String?>(null)
    private var historyFullscreenActive by mutableStateOf(false)
    private var trackingSettings by mutableStateOf(ReceiverTrackingSettings(receiverId = ""))
    private var fastTrackingDeviceId by mutableStateOf<String?>(null)
    private var fastHistory by mutableStateOf(false)
    private var fastHistoryOverrideDeviceId by mutableStateOf<String?>(null)
    private var fastTrackingDisabledOverrideDeviceId by mutableStateOf<String?>(null)
    private var message by mutableStateOf("")
    private var historyDeletionInProgress by mutableStateOf(false)
    private var historyDeletionMessage by mutableStateOf("")
    private var audioLevel by mutableFloatStateOf(0f)
    private var cameraTrack by mutableStateOf<VideoTrack?>(null)
    private var screenTrack by mutableStateOf<VideoTrack?>(null)
    private var audioTrack by mutableStateOf<AudioTrack?>(null)
    private var room: Room? = null
    private var roomDeviceId: String? = null
    private val cameraTracks = mutableStateMapOf<String, VideoTrack>()
    private val screenTracks = mutableStateMapOf<String, VideoTrack>()
    private val audioTracks = mutableStateMapOf<String, AudioTrack>()
    private val audioLevels = mutableStateMapOf<String, Float>()
    private val cameraRenderers = mutableMapOf<String, TextureViewRenderer>()
    private var screenRenderer: TextureViewRenderer? = null
    private val initializedRenderers =
        Collections.newSetFromMap(IdentityHashMap<TextureViewRenderer, Boolean>())
    private var snapshotPreview by mutableStateOf<Bitmap?>(null)
    private var videoRecordingState by mutableStateOf<LocalRecordingState>(
        LocalRecordingState.Idle,
    )
    private var audioRecordingState by mutableStateOf<LocalRecordingState>(
        LocalRecordingState.Idle,
    )
    private var screenRecordingState by mutableStateOf<LocalRecordingState>(
        LocalRecordingState.Idle,
    )
    private val groupVideoRecordingStates = mutableStateMapOf<String, LocalRecordingState>()
    private val groupAudioRecordingStates = mutableStateMapOf<String, LocalRecordingState>()
    private val groupVideoRecorders = mutableMapOf<String, VideoMp4Recorder>()
    private val groupAudioRecorders = mutableMapOf<String, AudioM4aRecorder>()
    private val groupVideoRecordingTimerJobs = mutableMapOf<String, Job>()
    private val groupAudioRecordingTimerJobs = mutableMapOf<String, Job>()
    private var voiceMessageState by mutableStateOf<VoiceMessageDraftState>(
        VoiceMessageDraftState.Idle,
    )
    private var voiceMessageVolume by mutableStateOf(VoiceMessageVolume.MEDIUM)
    private var voiceMessageFeedback by mutableStateOf("")
    private var textMessageDraft by mutableStateOf("")
    private var textMessageFeedback by mutableStateOf("")
    private var textMessageSending by mutableStateOf(false)
    private lateinit var voiceMessageRecorder: VoiceMessageRecorder
    private var voiceMessageDraftFile: File? = null
    private var pendingVoiceRecordDeviceId: String? = null
    private var videoRecorder: VideoMp4Recorder? = null
    private var audioRecorder: AudioM4aRecorder? = null
    private var screenRecorder: VideoMp4Recorder? = null
    private var videoRecordingTimerJob: Job? = null
    private var audioRecordingTimerJob: Job? = null
    private var screenRecordingTimerJob: Job? = null
    private var voiceMessageTimerJob: Job? = null
    private var voiceMessageDeliveryJob: Job? = null
    private var textMessageDeliveryJob: Job? = null
    private var mediaCommandFeedback by mutableStateOf<Map<MediaCommandKey, MediaCommandFeedback>>(
        emptyMap(),
    )
    private val mediaCommandTimeoutJobs = mutableMapOf<MediaCommandKey, Job>()
    private var mediaCommandRequestSequence = 0L
    private var cameraSwitchFeedback by mutableStateOf<Map<String, CameraSwitchFeedback>>(
        emptyMap(),
    )
    private val cameraSwitchTimeoutJobs = mutableMapOf<String, Job>()
    private var dataPlaneJob: Job? = null
    private var trackingLeaseJob: Job? = null
    private var roomEventsJob: Job? = null
    private var audioMeterJob: Job? = null
    private var connectionJob: Job? = null
    private var isForeground = false
    private var pendingNotificationDeviceId: String? = null
    private var pushNotificationsInitialized = false
    private var onlineClockTick by mutableStateOf(System.currentTimeMillis())
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var receiverLocationController: ReceiverLocationController
    private val recoverySignals = Channel<Throwable>(Channel.CONFLATED)
    @Volatile
    private var networkWasLost = false

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val deviceId = pendingVoiceRecordDeviceId
        pendingVoiceRecordDeviceId = null
        if (granted && deviceId != null && selectedDeviceId == deviceId) {
            startVoiceMessageRecording()
        } else {
            voiceMessageFeedback = "Permesso microfono necessario per registrare."
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startDistanceLocationIfAllowed()
        } else {
            receiverGpsAvailable = false
            message = "Concedi la posizione al ricevitore per verificare la distanza."
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            networkWasLost = true
            Log.w(TAG, "Receiver network unavailable; automatic recovery armed")
        }

        override fun onAvailable(network: Network) {
            if (networkWasLost) {
                networkWasLost = false
                recoverySignals.trySend(DataPlaneRestart("network restored"))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        identity = DeviceIdentity(this)
        voiceMessageRecorder = VoiceMessageRecorder(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        receiverLocationController = ReceiverLocationController(
            context = this,
            deviceId = { identity.id },
            onLocation = { receiverLocation = it },
            onAvailabilityChanged = { receiverGpsAvailable = it },
        )
        powerManager = getSystemService(PowerManager::class.java)
        mediaWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            MEDIA_WAKE_LOCK_TAG,
        ).apply {
            setReferenceCounted(false)
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        pendingNotificationDeviceId =
            intent.getStringExtra(FindMeMessagingService.EXTRA_DEVICE_ID)
        setContent {
            FindMeReceiverTheme {
                ReceiverApp()
            }
        }
        if (AppConfig.isConfigured) initializeDevice()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val deviceId = intent.getStringExtra(FindMeMessagingService.EXTRA_DEVICE_ID) ?: return
        if (devices.any { it.device.id == deviceId }) {
            openDetail(deviceId)
        } else {
            pendingNotificationDeviceId = deviceId
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus &&
            (fullscreenDeviceId != null ||
                videoFullscreenDeviceId != null ||
                screenFullscreenDeviceId != null ||
                historyFullscreenActive)
        ) {
            hideSystemBars()
        }
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
        startDistanceLocationIfAllowed()
        updateMediaPowerProtection()
        reconcileMediaConnection()
    }

    override fun onStop() {
        isForeground = false
        if (::receiverLocationController.isInitialized) receiverLocationController.stop()
        if (ReceiverPowerPolicy.shouldKeepSessionWhenStopped(
                screenInteractive = powerManager.isInteractive,
                mediaActive = shouldMaintainMediaSession(),
            )
        ) {
            Log.i(TAG, "Keeping active remote session while receiver screen is locked")
            updateMediaPowerProtection()
        } else {
            selectedDeviceId?.let(::stopAllStreams)
            if (groupMediaActive) {
                groupSelectedDeviceIds.forEach(::stopAllStreams)
                stopAllGroupRecordings()
            }
            stopFastTracking()
            disconnectMedia()
            releaseMediaPowerProtection()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (fullscreenDeviceId != null) closeFullscreenMap()
        if (videoFullscreenDeviceId != null) closeVideoFullscreen()
        if (screenFullscreenDeviceId != null) closeScreenFullscreen()
        if (groupFullscreenActive) closeGroupFullscreen()
        if (historyFullscreenActive) setHistoryFullscreen(false)
        if (distanceFullscreenActive) setDistanceFullscreen(false)
        if (::receiverLocationController.isInitialized) receiverLocationController.stop()
        voiceMessageDeliveryJob?.cancel()
        textMessageDeliveryJob?.cancel()
        mediaCommandTimeoutJobs.values.forEach { it.cancel() }
        mediaCommandTimeoutJobs.clear()
        cameraSwitchTimeoutJobs.values.forEach { it.cancel() }
        cameraSwitchTimeoutJobs.clear()
        discardVoiceMessage()
        disconnectMedia()
        releaseMediaPowerProtection()
        dataPlaneJob?.cancel()
        trackingLeaseJob?.cancel()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        super.onDestroy()
    }

    @Composable
    private fun ReceiverApp() {
        LaunchedEffect(Unit) {
            while (isActive) {
                onlineClockTick = System.currentTimeMillis()
                delay(ONLINE_CLOCK_INTERVAL_MS)
            }
        }
        @Suppress("UNUSED_VARIABLE")
        val refreshOnlineState = onlineClockTick
        val selected = selectedDeviceId?.let { id ->
            devices.firstOrNull { it.device.id == id }
        }
        val groupDevices = devices.filter { it.device.id in groupSelectedDeviceIds }
        val historyDevice = historyDeviceId?.let { id ->
            devices.firstOrNull { it.device.id == id }
        }
        val distanceDevice = distanceDeviceId?.let { id ->
            devices.firstOrNull { it.device.id == id }
        }
        val fullscreenDevice = fullscreenDeviceId?.let { id ->
            devices.firstOrNull { it.device.id == id }
        }
        val videoFullscreenDevice = videoFullscreenDeviceId?.let { id ->
            devices.firstOrNull { it.device.id == id }
        }
        val screenFullscreenDevice = screenFullscreenDeviceId?.let { id ->
            devices.firstOrNull { it.device.id == id }
        }
        if (groupFullscreenActive) {
            val activeGroupDevices = groupDevices.filter {
                it.status?.cameraStreaming == true
            }
            BackHandler {
                if (groupSingleFullscreenDeviceId != null) {
                    closeGroupSingleFullscreen()
                } else {
                    closeGroupFullscreen()
                }
            }
            GroupVideoFullscreenScreen(
                devices = activeGroupDevices,
                singleDeviceId = groupSingleFullscreenDeviceId,
                cameraSwitchPending = cameraSwitchFeedback
                    .filterValues { it.phase != MediaCommandPhase.FAILED }
                    .keys,
                videoRecordingStates = groupVideoRecordingStates,
                videoTrackDeviceIds = cameraTracks.keys,
                onSingleFullscreen = ::openGroupSingleFullscreen,
                onVideoRecordingToggle = ::toggleGroupVideoRecording,
                onCameraSwitch = ::sendCameraSwitchCommand,
                onTakePhoto = { takeVideoSnapshot(it) },
                onBack = {
                    if (groupSingleFullscreenDeviceId != null) {
                        closeGroupSingleFullscreen()
                    } else {
                        closeGroupFullscreen()
                    }
                },
                snapshotPreview = snapshotPreview,
                onSnapshotAnimationFinished = ::clearSnapshotAnimation,
                videoContent = { item ->
                    CameraSurface(
                        deviceId = item.device.id,
                        streaming = item.status?.cameraStreaming == true,
                        aspectFit = true,
                        renderTargetKey = if (groupSingleFullscreenDeviceId == null) {
                            "group-grid-${item.device.id}"
                        } else {
                            "group-single-${item.device.id}"
                        },
                    )
                },
            )
            return
        }
        if (videoFullscreenDevice != null) {
            val id = videoFullscreenDevice.device.id
            BackHandler(onBack = ::closeVideoFullscreen)
            GroupVideoFullscreenScreen(
                devices = listOf(videoFullscreenDevice),
                singleDeviceId = id,
                cameraSwitchPending = cameraSwitchFeedback[id]
                    ?.takeIf { it.phase != MediaCommandPhase.FAILED }
                    ?.let { setOf(id) }
                    ?: emptySet(),
                videoRecordingStates = mapOf(id to videoRecordingState),
                videoTrackDeviceIds = cameraTracks.keys,
                onSingleFullscreen = {},
                onVideoRecordingToggle = {
                    if (videoRecordingState.isActive) {
                        stopVideoRecording()
                    } else {
                        startVideoRecording(videoFullscreenDevice)
                    }
                },
                onCameraSwitch = ::sendCameraSwitchCommand,
                onTakePhoto = { takeVideoSnapshot(it) },
                onBack = ::closeVideoFullscreen,
                snapshotPreview = snapshotPreview,
                onSnapshotAnimationFinished = ::clearSnapshotAnimation,
                videoContent = {
                    CameraSurface(
                        deviceId = id,
                        streaming = videoFullscreenDevice.status?.cameraStreaming == true,
                        renderTargetKey = "detail-fullscreen-$id",
                    )
                },
                singleBackContentDescription = "Torna al dettaglio",
            )
            return
        }
        if (videoFullscreenDeviceId != null) {
            LaunchedEffect(videoFullscreenDeviceId) { closeVideoFullscreen() }
        }
        if (screenFullscreenDevice != null) {
            BackHandler(onBack = ::closeScreenFullscreen)
            ScreenFullscreenScreen(
                streaming = screenFullscreenDevice.status?.screenStreaming == true,
                onExit = ::closeScreenFullscreen,
                screenContent = {
                    ScreenSurface(
                        deviceId = screenFullscreenDevice.device.id,
                        streaming = screenFullscreenDevice.status?.screenStreaming == true,
                    )
                },
            )
            return
        }
        if (screenFullscreenDeviceId != null) {
            LaunchedEffect(screenFullscreenDeviceId) { closeScreenFullscreen() }
        }
        if (fullscreenDevice != null) {
            BackHandler(onBack = ::closeFullscreenMap)
            PositionFullscreenScreen(
                item = fullscreenDevice,
                heartbeatIntervalSec = trackingSettings.heartbeatIntervalSec,
                fastTrackingActive = isFastTrackingActive(fullscreenDevice),
                fastHistoryActive = isFastHistoryActive(fullscreenDevice),
                onFastTrackingChange = {
                    if (it) {
                        startFastTracking(fullscreenDevice.device.id)
                    } else {
                        stopFastTracking(fullscreenDevice.device.id)
                    }
                },
                onFastHistoryChange = { setFastHistory(fullscreenDevice, it) },
                onGeofenceChange = { setGeofence(fullscreenDevice, it) },
                onOpenHistory = {
                    closeFullscreenMap()
                    historyDeviceId = fullscreenDevice.device.id
                },
                onVerifyDistance = {
                    closeFullscreenMap()
                    openDistanceVerification(fullscreenDevice.device.id)
                },
                onExit = ::closeFullscreenMap,
            )
            return
        }
        if (fullscreenDeviceId != null) {
            LaunchedEffect(fullscreenDeviceId) { closeFullscreenMap() }
        }
        if (distanceDevice != null) {
            val locationIntervalSec = if (isFastTrackingActive(distanceDevice)) {
                trackingSettings.onlineLocationIntervalSec
            } else {
                trackingSettings.offlineLocationIntervalSec
            }
            LaunchedEffect(distanceDevice.device.id, locationIntervalSec) {
                startDistanceLocationIfAllowed(locationIntervalSec)
            }
            DisposableEffect(distanceDevice.device.id) {
                onDispose {
                    receiverLocationController.stop()
                    receiverLocation = null
                }
            }
            BackHandler {
                if (distanceFullscreenActive) {
                    setDistanceFullscreen(false)
                } else {
                    closeDistanceVerification()
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (distanceFullscreenActive) {
                            Modifier
                        } else {
                            Modifier.systemBarsPadding().padding(16.dp)
                        },
                    ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!distanceFullscreenActive) {
                    Text(
                        "FindMe",
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                DistanceVerificationScreen(
                    device = distanceDevice,
                    receiverLocation = receiverLocation,
                    gpsAvailable = receiverGpsAvailable,
                    fullscreen = distanceFullscreenActive,
                    onBack = ::closeDistanceVerification,
                    onFullscreenChange = ::setDistanceFullscreen,
                    modifier = Modifier.weight(1f),
                )
            }
            return
        }
        if (distanceDeviceId != null) {
            LaunchedEffect(distanceDeviceId) { closeDistanceVerification() }
        }
        if (historyDevice != null) {
            BackHandler(enabled = !historyFullscreenActive) {
                historyDeviceId = null
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (historyFullscreenActive) {
                            Modifier
                        } else {
                            Modifier.systemBarsPadding().padding(16.dp)
                        },
                    ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!historyFullscreenActive) {
                    Text(
                        "FindMe",
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                LocationHistoryScreen(
                    device = historyDevice,
                    fullscreen = historyFullscreenActive,
                    onBack = { historyDeviceId = null },
                    onFullscreenChange = ::setHistoryFullscreen,
                    loadRoute = { from, to ->
                        repository!!.fetchLocationRoute(
                            deviceId = historyDevice.device.id,
                            from = from,
                            to = to,
                        )
                    },
                    loadPage = { from, to, offset ->
                        repository!!.fetchLocationHistory(
                            deviceId = historyDevice.device.id,
                            from = from,
                            to = to,
                            offset = offset,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            return
        }
        if (historyFullscreenActive) {
            LaunchedEffect(historyDeviceId) { setHistoryFullscreen(false) }
        }
        BackHandler(
            enabled = showSettings ||
                showGroupSelection ||
                groupMediaActive ||
                historyDeviceId != null ||
                distanceDeviceId != null ||
                selected != null,
        ) {
            when {
                showSettings -> showSettings = false
                showGroupSelection -> showGroupSelection = false
                groupMediaActive -> closeGroupMedia()
                historyDeviceId != null -> historyDeviceId = null
                distanceDeviceId != null -> closeDistanceVerification()
                else -> closeDetail()
            }
        }

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
                    showSettings -> TrackingSettingsScreen(
                        settings = trackingSettings,
                        devices = devices,
                        historyDeletionInProgress = historyDeletionInProgress,
                        historyDeletionMessage = historyDeletionMessage,
                        accessConfiguration = receiverAccessConfiguration,
                        accessUpdateInProgress = accessUpdateInProgress,
                        accessUpdateMessage = accessUpdateMessage,
                        onBack = { showSettings = false },
                        onChange = ::saveTrackingSettings,
                        onAccessUpdate = ::saveReceiverAccessConfiguration,
                        onDeleteHistory = ::deleteLocationHistory,
                        modifier = Modifier.weight(1f),
                    )
                    historyDeviceId != null -> Text("Dispositivo non più disponibile.")
                    showGroupSelection -> GroupMediaSelectionScreen(
                        devices = devices,
                        selectedDeviceIds = groupSelectedDeviceIds,
                        onSelectionChange = ::setGroupDeviceSelected,
                        onBack = { showGroupSelection = false },
                        onConfirm = ::openGroupMedia,
                        modifier = Modifier.weight(1f),
                    )
                    groupMediaActive -> GroupMediaScreen(
                        devices = groupDevices,
                        selectedTab = groupSelectedTab,
                        heartbeatIntervalSec = trackingSettings.heartbeatIntervalSec,
                        audioLevels = audioLevels,
                        videoFeedback = mediaFeedbackByDevice(MediaStreamKind.VIDEO),
                        audioFeedback = mediaFeedbackByDevice(MediaStreamKind.AUDIO),
                        cameraSwitchPending = cameraSwitchFeedback
                            .filterValues { it.phase != MediaCommandPhase.FAILED }
                            .keys,
                        videoRecordingStates = groupVideoRecordingStates,
                        audioRecordingStates = groupAudioRecordingStates,
                        videoTrackDeviceIds = cameraTracks.keys,
                        audioTrackDeviceIds = audioTracks.keys,
                        onTabSelected = { tab ->
                            if (groupSelectedTab == GroupMediaTab.VIDEO &&
                                tab != GroupMediaTab.VIDEO
                            ) {
                                groupVideoRecordingStates.keys
                                    .toList()
                                    .forEach(::stopGroupVideoRecording)
                            }
                            if (groupSelectedTab == GroupMediaTab.AUDIO &&
                                tab != GroupMediaTab.AUDIO
                            ) {
                                groupAudioRecordingStates.keys
                                    .toList()
                                    .forEach(::stopGroupAudioRecording)
                            }
                            groupSelectedTab = tab
                        },
                        onBack = ::closeGroupMedia,
                        onVideoStreamingChange = { start ->
                            sendGroupMediaCommands(
                                groupDevices,
                                if (start) CommandType.START_VIDEO else CommandType.STOP_VIDEO,
                            )
                        },
                        onAudioStreamingChange = { start ->
                            sendGroupMediaCommands(
                                groupDevices,
                                if (start) CommandType.START_AUDIO else CommandType.STOP_AUDIO,
                            )
                        },
                        onVideoRecordingToggle = ::toggleGroupVideoRecording,
                        onAudioRecordingToggle = ::toggleGroupAudioRecording,
                        onCameraSwitch = ::sendCameraSwitchCommand,
                        onTakePhoto = { takeVideoSnapshot(it) },
                        onGroupFullscreen = ::openGroupFullscreen,
                        onSingleFullscreen = ::openGroupSingleFullscreen,
                        snapshotPreview = snapshotPreview,
                        onSnapshotAnimationFinished = ::clearSnapshotAnimation,
                        videoContent = { item ->
                            CameraSurface(
                                deviceId = item.device.id,
                                streaming = item.status?.cameraStreaming == true,
                                renderTargetKey = "group-preview-${item.device.id}",
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                    selected == null -> ReceiverHomeScreen(
                        profile = receiverProfile,
                        receiverId = identity.id,
                        devices = devices,
                        heartbeatIntervalSec = trackingSettings.heartbeatIntervalSec,
                        onDeviceClick = ::openDetail,
                        onAliasSave = ::updateAlias,
                        onGroupMediaClick = {
                            groupSelectedDeviceIds = devices
                                .filter { it.status?.isMonitoring == true }
                                .mapTo(linkedSetOf()) { it.device.id }
                                .takeIf { it.size >= GroupMediaPolicy.MIN_SELECTED_DEVICES }
                                ?: emptySet()
                            showGroupSelection = true
                        },
                        onSettingsClick = {
                            historyDeletionMessage = ""
                            showSettings = true
                        },
                        modifier = Modifier.weight(1f),
                    )
                    else -> DeviceDetailScreen(
                        item = selected,
                        heartbeatIntervalSec = trackingSettings.heartbeatIntervalSec,
                        selectedTab = selectedTab,
                        audioLevel = audioLevel,
                        onTabSelected = { tab ->
                            if (selectedTab == DeviceTab.VIDEO && tab != DeviceTab.VIDEO) {
                                stopVideoRecording()
                            }
                            if (selectedTab == DeviceTab.AUDIO && tab != DeviceTab.AUDIO) {
                                stopAudioRecording()
                                if (voiceMessageState !is VoiceMessageDraftState.Sending) {
                                    discardVoiceMessage()
                                }
                            }
                            if (selectedTab == DeviceTab.SCREEN && tab != DeviceTab.SCREEN) {
                                stopScreenRecording()
                            }
                            selectedTab = tab
                        },
                        onBack = ::closeDetail,
                        onCommand = { command(selected, it) },
                        mediaCommandFeedback = mediaCommandFeedback
                            .filterKeys { it.deviceId == selected.device.id }
                            .mapKeys { it.key.stream },
                        cameraSwitchFeedback = cameraSwitchFeedback[selected.device.id],
                        fastTrackingActive = isFastTrackingActive(selected),
                        fastHistoryActive = isFastHistoryActive(selected),
                        onFastTrackingChange = {
                            if (it) {
                                startFastTracking(selected.device.id)
                            } else {
                                stopFastTracking(selected.device.id)
                            }
                        },
                        onFastHistoryChange = { setFastHistory(selected, it) },
                        onGeofenceChange = {
                            setGeofence(selected, it)
                        },
                        onFullscreen = {
                            openFullscreenMap(selected.device.id)
                        },
                        onVideoFullscreen = {
                            openVideoFullscreen(selected.device.id)
                        },
                        onScreenFullscreen = {
                            openScreenFullscreen(selected.device.id)
                        },
                        onOpenHistory = { historyDeviceId = selected.device.id },
                        onVerifyDistance = {
                            openDistanceVerification(selected.device.id)
                        },
                        onTakePhoto = { takeVideoSnapshot(selected) },
                        videoTrackAvailable = selected.device.id in cameraTracks,
                        audioTrackAvailable = selected.device.id in audioTracks,
                        screenTrackAvailable = selected.device.id in screenTracks,
                        videoRecordingState = videoRecordingState,
                        audioRecordingState = audioRecordingState,
                        screenRecordingState = screenRecordingState,
                        onVideoRecordingToggle = {
                            if (videoRecordingState.isActive) {
                                stopVideoRecording()
                            } else {
                                startVideoRecording(selected)
                            }
                        },
                        onAudioRecordingToggle = {
                            if (audioRecordingState.isActive) {
                                stopAudioRecording()
                            } else {
                                startAudioRecording(selected)
                            }
                        },
                        voiceMessageState = voiceMessageState,
                        voiceMessageVolume = voiceMessageVolume,
                        voiceMessageFeedback = voiceMessageFeedback,
                        onVoiceMessageRecordToggle = {
                            toggleVoiceMessageRecording(selected)
                        },
                        onVoiceMessageVolumeChange = {
                            voiceMessageVolume = it
                        },
                        onVoiceMessageSend = {
                            sendVoiceMessage(selected)
                        },
                        onVoiceMessageDiscard = ::discardVoiceMessage,
                        onScreenRecordingToggle = {
                            if (screenRecordingState.isActive) {
                                stopScreenRecording()
                            } else {
                                startScreenRecording(selected)
                            }
                        },
                        textMessageDraft = textMessageDraft,
                        textMessageFeedback = textMessageFeedback,
                        textMessageSending = textMessageSending,
                        onTextMessageDraftChange = {
                            textMessageDraft = it.take(TextMessagePolicy.MAX_LENGTH)
                        },
                        onTextMessageSend = { sendTextMessage(selected) },
                        snapshotPreview = snapshotPreview,
                        onSnapshotAnimationFinished = ::clearSnapshotAnimation,
                        videoContent = {
                            CameraSurface(
                                deviceId = selected.device.id,
                                streaming = selected.status?.cameraStreaming == true,
                                renderTargetKey = "detail-preview-${selected.device.id}",
                            )
                        },
                        screenContent = {
                            ScreenSurface(
                                deviceId = selected.device.id,
                                streaming = selected.status?.screenStreaming == true,
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
    private fun CameraSurface(
        deviceId: String,
        streaming: Boolean,
        aspectFit: Boolean = true,
        renderTargetKey: String,
    ) {
        val context = LocalContext.current
        val renderer = remember(deviceId, renderTargetKey) {
            TextureViewRenderer(context)
        }
        val scalingType = if (aspectFit) {
            RendererCommon.ScalingType.SCALE_ASPECT_FIT
        } else {
            RendererCommon.ScalingType.SCALE_ASPECT_FILL
        }
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = if (aspectFit) {
                    Modifier.wrapContentSize()
                } else {
                    Modifier.fillMaxSize()
                },
                factory = {
                    renderer.also { view ->
                        cameraRenderers[deviceId] = view
                        room?.let { initializeRenderer(it, view) }
                        view.setScalingType(scalingType)
                        Log.d(TAG, "Camera renderer attached target=$renderTargetKey")
                    }
                },
                update = { view ->
                    cameraRenderers[deviceId] = view
                    room?.let { initializeRenderer(it, view) }
                    view.setScalingType(scalingType)
                },
            )
        }
        val activeTrack = cameraTracks[deviceId]
        DisposableEffect(renderer, activeTrack, streaming, renderTargetKey) {
            activeTrack?.removeRenderer(renderer)
            if (streaming && activeTrack != null) {
                activeTrack.addRenderer(renderer)
                // LiveKit/WebRTC can restore SCALE_ASPECT_FILL while attaching
                // a track; enforce the requested mode after the attachment.
                renderer.setScalingType(scalingType)
                Log.d(TAG, "Camera renderer bound target=$renderTargetKey")
            } else if (!streaming) {
                renderer.clearImage()
            }
            onDispose {
                activeTrack?.removeRenderer(renderer)
            }
        }
        DisposableEffect(renderer, renderTargetKey) {
            onDispose {
                cameraTracks[deviceId]?.removeRenderer(renderer)
                renderer.clearImage()
                if (cameraRenderers[deviceId] === renderer) cameraRenderers.remove(deviceId)
                releaseRenderer(renderer)
                Log.d(TAG, "Camera renderer released target=$renderTargetKey")
            }
        }
    }

    @Composable
    private fun ScreenSurface(deviceId: String, streaming: Boolean) {
        var localRenderer by remember { mutableStateOf<TextureViewRenderer?>(null) }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                TextureViewRenderer(context).also { view ->
                    localRenderer = view
                    screenRenderer = view
                    room?.let { initializeRenderer(it, view) }
                    // LiveKit initialization can restore the default crop mode.
                    view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    view.setMirror(false)
                }
            },
            update = { view ->
                localRenderer = view
                screenRenderer = view
                room?.let { initializeRenderer(it, view) }
                view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                view.setMirror(false)
            },
        )
        val activeTrack = screenTracks[deviceId]
        LaunchedEffect(streaming, activeTrack, localRenderer) {
            localRenderer?.let { view ->
                activeTrack?.removeRenderer(view)
                if (streaming) {
                    activeTrack?.addRenderer(view)
                } else {
                    view.clearImage()
                }
            }
        }
        DisposableEffect(localRenderer) {
            val view = localRenderer
            onDispose {
                view?.let {
                    screenTracks[deviceId]?.removeRenderer(view)
                    view.clearImage()
                }
                // Keep the renderer initialized: Compose can reuse it in fullscreen.
            }
        }
    }

    private fun initializeDevice() {
        dataPlaneJob?.cancel()
        dataPlaneJob = lifecycleScope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                val activeRepository = FindMeRepository()
                var retryImmediately = false
                repository = activeRepository
                try {
                    activeRepository.ensureAuthenticated(forceRefresh = true)
                    activeRepository.registerDevice(
                        identity.id,
                        identity.name,
                        DeviceRole.RECEIVER,
                    )
                    activeRepository.registerReceiver(identity.id, identity.name)
                    receiverAccessConfiguration =
                        activeRepository.fetchReceiverAccessConfiguration(identity.id)
                    consecutiveFailures = 0
                    message = ""
                    ready = true
                    Log.i(TAG, "Receiver data plane authenticated and healthy")
                    if (!pushNotificationsInitialized) {
                        pushNotificationsInitialized = true
                        initializePushNotifications()
                    }
                    coroutineScope {
                        launch {
                            activeRepository.receiverProfile(identity.id).collect {
                                receiverProfile = it
                            }
                        }
                        launch {
                            activeRepository.receiverTrackingSettings(identity.id)
                                .collect { settings ->
                                    if (settings != null) trackingSettings = settings
                                }
                        }
                        launch {
                            activeRepository.monitoredDevices().collect(::handleDeviceRows)
                        }
                        launch {
                            delay(RECEIVER_CHANNEL_RENEWAL_INTERVAL_MS)
                            activeRepository.ensureAuthenticated(forceRefresh = true)
                            throw DataPlaneRestart("periodic authenticated channel renewal")
                        }
                        launch { throw recoverySignals.receive() }
                    }
                } catch (error: CancellationException) {
                    if (!isActive) throw error
                    consecutiveFailures++
                    Log.e(TAG, "Receiver data plane cancelled unexpectedly", error)
                } catch (restart: DataPlaneRestart) {
                    retryImmediately = true
                    Log.i(TAG, "Receiver data plane restart requested: ${restart.message}")
                } catch (error: Throwable) {
                    consecutiveFailures++
                    Log.e(TAG, "Receiver data plane failed", error)
                    message = "Connessione temporaneamente assente. Riconnessione automatica in corso…"
                } finally {
                    runCatching { activeRepository.shutdownRealtime() }
                }
                if (!isActive) break
                if (!retryImmediately) {
                    val retryDelay = ConnectionRecoveryPolicy.retryDelayMs(consecutiveFailures)
                    withTimeoutOrNull(retryDelay) { recoverySignals.receive() }
                }
            }
        }
    }

    private fun handleDeviceRows(rows: List<MonitoredDevice>) {
        devices = rows
        fastTrackingDeviceId?.let { deviceId ->
            val persistentRelationship = rows
                .firstOrNull { it.device.id == deviceId }
                ?.relationship
                ?.takeIf { it.liveTrackingPersistent }
            if (persistentRelationship != null) {
                trackingLeaseJob?.cancel()
                trackingLeaseJob = null
                fastTrackingDeviceId = null
                fastHistory = persistentRelationship.liveHistory
                updateMediaPowerProtection()
            }
        }
        fastHistoryOverrideDeviceId?.let { deviceId ->
            val serverHistory = rows
                .firstOrNull { it.device.id == deviceId }
                ?.relationship
                ?.liveHistory
            if (serverHistory == fastHistory) fastHistoryOverrideDeviceId = null
        }
        fastTrackingDisabledOverrideDeviceId?.let { deviceId ->
            val relationship = rows
                .firstOrNull { it.device.id == deviceId }
                ?.relationship
            if (relationship?.liveTrackingPersistent != true &&
                !TrackingConfigResolver.isLiveTrackingLeaseActive(
                    relationship?.liveTrackingUntil,
                )
            ) {
                fastTrackingDisabledOverrideDeviceId = null
            }
        }
        reconcileMediaCommandFeedback(rows)
        reconcileCameraSwitchFeedback(rows)
        if (message.startsWith("Connessione temporaneamente assente")) {
            message = ""
        }
        pendingNotificationDeviceId?.let { pendingId ->
            if (rows.any { it.device.id == pendingId }) {
                openDetail(pendingId)
                pendingNotificationDeviceId = null
            }
        }
        val selectedId = selectedDeviceId
        val associatedIds = rows.mapTo(mutableSetOf()) { it.device.id }
        groupSelectedDeviceIds = groupSelectedDeviceIds.intersect(associatedIds)
        if (groupMediaActive &&
            groupSelectedDeviceIds.size < GroupMediaPolicy.MIN_SELECTED_DEVICES
        ) {
            closeGroupMedia()
        }
        if (selectedId != null && rows.none { it.device.id == selectedId }) {
            closeDetail()
        } else {
            rows.firstOrNull { it.device.id == selectedId }?.let { selected ->
                if (selected.status?.cameraStreaming != true) {
                    stopVideoRecording()
                }
                if (selected.status?.microphoneStreaming != true) {
                    stopAudioRecording()
                }
                if (selected.status?.screenStreaming != true) {
                    stopScreenRecording()
                }
            }
            rows.filter { it.device.id in groupSelectedDeviceIds }.forEach { item ->
                if (item.status?.cameraStreaming != true) {
                    stopGroupVideoRecording(item.device.id)
                }
                if (item.status?.microphoneStreaming != true) {
                    stopGroupAudioRecording(item.device.id)
                }
            }
            reconcileMediaConnection()
            updateMediaPowerProtection()
        }
    }

    private fun initializePushNotifications() {
        GeofenceNotifications.createChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1201)
        }
        if (FirebaseApp.getApps(this).isEmpty()) {
            Log.w(TAG, "Firebase non configurato: google-services.json mancante")
            return
        }
        FirebaseMessaging.getInstance().register()
            .addOnFailureListener {
                Log.e(TAG, "Registrazione FCM non riuscita", it)
            }
    }

    private fun openDetail(deviceId: String) {
        showGroupSelection = false
        groupMediaActive = false
        selectedDeviceId = deviceId
        selectedTab = DeviceTab.POSITION
        message = ""
        voiceMessageFeedback = ""
        reconcileMediaConnection()
    }

    /** Aggiorna la selezione mantenendo soltanto dispositivi ancora associati. */
    private fun setGroupDeviceSelected(deviceId: String, selected: Boolean) {
        if (devices.none { it.device.id == deviceId }) return
        groupSelectedDeviceIds = if (selected) {
            groupSelectedDeviceIds + deviceId
        } else {
            groupSelectedDeviceIds - deviceId
        }
    }

    /** Apre il dettaglio multimediale dopo aver validato la selezione. */
    private fun openGroupMedia() {
        val associatedIds = devices.mapTo(mutableSetOf()) { it.device.id }
        if (!GroupMediaPolicy.isValidSelection(associatedIds, groupSelectedDeviceIds)) return
        selectedDeviceId = null
        showGroupSelection = false
        groupMediaActive = true
        groupSelectedTab = GroupMediaTab.VIDEO
        message = ""
        reconcileMediaConnection()
    }

    /** Chiude il gruppo, arrestando stream e registrazioni avviati dalla vista. */
    private fun closeGroupMedia() {
        closeGroupFullscreen()
        stopAllGroupRecordings()
        val selected = devices.filter { it.device.id in groupSelectedDeviceIds }
        sendGroupMediaCommands(selected, CommandType.STOP_VIDEO)
        sendGroupMediaCommands(selected, CommandType.STOP_AUDIO)
        groupMediaActive = false
        groupSelectedTab = GroupMediaTab.VIDEO
        disconnectMedia()
        updateMediaPowerProtection()
    }

    /** Invia lo stesso comando ai dispositivi senza serializzare le attese di rete. */
    private fun sendGroupMediaCommands(
        selectedDevices: List<MonitoredDevice>,
        type: CommandType,
    ) {
        selectedDevices.forEach { command(it, type) }
    }

    private fun mediaFeedbackByDevice(
        stream: MediaStreamKind,
    ): Map<String, MediaCommandFeedback> = mediaCommandFeedback
        .filterKeys { it.stream == stream }
        .mapKeys { it.key.deviceId }

    private fun closeDetail() {
        clearSnapshotAnimation()
        voiceMessageDeliveryJob?.cancel()
        discardVoiceMessage()
        selectedDeviceId?.let(::stopAllStreams)
        stopFastTracking()
        selectedDeviceId = null
        selectedTab = DeviceTab.POSITION
        message = ""
        disconnectMedia()
        updateMediaPowerProtection()
    }

    private fun clearSnapshotAnimation() {
        val completedPreview = snapshotPreview
        snapshotPreview = null
        window.decorView.post { completedPreview?.recycle() }
    }

    private fun saveTrackingSettings(update: TrackingSettingsUpdate) {
        val previous = trackingSettings
        trackingSettings = ReceiverTrackingSettings(
            receiverId = identity.id,
            offlineLocationIntervalSec = update.offlineLocationIntervalSec,
            onlineLocationIntervalSec = update.onlineLocationIntervalSec,
            historyMultiplier = update.historyMultiplier,
            onlyMovement = update.onlyMovement,
            heartbeatIntervalSec = update.heartbeatIntervalSec,
            commandPollIntervalSec = update.commandPollIntervalSec,
            geofenceRadiusM = update.geofenceRadiusM,
        )
        lifecycleScope.launch {
            runCatching {
                repository!!.updateReceiverTrackingSettings(identity.id, update)
            }.onFailure {
                trackingSettings = previous
                requestDataPlaneRecovery(it, "Salvataggio impostazioni non riuscito.")
            }
        }
    }

    /** Salva domanda e risposta e aggiorna subito i valori mostrati nelle impostazioni. */
    private fun saveReceiverAccessConfiguration(question: String, answer: String) {
        if (accessUpdateInProgress) return
        accessUpdateInProgress = true
        accessUpdateMessage = ""
        lifecycleScope.launch {
            runCatching {
                repository!!.updateReceiverAccessConfiguration(
                    receiverId = identity.id,
                    question = question,
                    answer = answer,
                )
            }.onSuccess {
                receiverAccessConfiguration = it
                accessUpdateMessage = "Domanda e risposta aggiornate."
            }.onFailure {
                Log.e(TAG, "Unable to update receiver access configuration", it)
                accessUpdateMessage = "Aggiornamento non riuscito. Riprova."
            }
            accessUpdateInProgress = false
        }
    }

    private fun deleteLocationHistory(selectedDeviceIds: Set<String>) {
        val associatedDeviceIds = devices.mapTo(mutableSetOf()) { it.device.id }
        if (!LocationHistoryDeletionPolicy.isValidSelection(
                associatedDeviceIds = associatedDeviceIds,
                selectedDeviceIds = selectedDeviceIds,
            )
        ) {
            Log.w(TAG, "Rejected invalid history deletion selection: $selectedDeviceIds")
            message = "Selezione trasmettitori non valida."
            return
        }

        historyDeletionInProgress = true
        historyDeletionMessage = ""
        lifecycleScope.launch {
            runCatching {
                repository!!.deleteLocationHistory(identity.id, selectedDeviceIds)
            }.onSuccess {
                Log.i(TAG, "Location history deleted for ${selectedDeviceIds.size} transmitters")
                message = ""
                historyDeletionMessage = if (selectedDeviceIds.size == 1) {
                    "Storico delle posizioni cancellato."
                } else {
                    "Storico delle posizioni cancellato per ${selectedDeviceIds.size} trasmettitori."
                }
            }.onFailure {
                Log.e(TAG, "Unable to delete location history", it)
                requestDataPlaneRecovery(it, "Cancellazione dello storico non riuscita.")
            }
            historyDeletionInProgress = false
        }
    }

    private fun setGeofence(device: MonitoredDevice, enabled: Boolean) {
        val center = device.location
        if (enabled && center == null) {
            message = "Posizione non disponibile: impossibile attivare l’avviso."
            return
        }
        lifecycleScope.launch {
            runCatching {
                repository!!.setGeofence(
                    receiverId = identity.id,
                    transmitterId = device.device.id,
                    center = center.takeIf { enabled },
                    radiusM = trackingSettings.geofenceRadiusM.takeIf { enabled },
                )
            }.onSuccess {
                message = ""
            }.onFailure {
                requestDataPlaneRecovery(it, "Configurazione avviso area non riuscita.")
            }
        }
    }

    private fun takeVideoSnapshot(device: MonitoredDevice, showPreview: Boolean = true) {
        if (showPreview && snapshotPreview != null) return
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1202)
            message = "Concedi l’accesso alle foto e premi nuovamente il pulsante."
            return
        }
        val videoRenderer = cameraRenderers[device.device.id]
        if (videoRenderer == null || !videoRenderer.isAvailable) {
            message = "Il fotogramma video non è ancora disponibile."
            return
        }
        val bitmap = videoRenderer.bitmap
        if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
            message = "Attendi la visualizzazione del video prima di scattare."
            return
        }
        val previewWidth = 320.coerceAtMost(bitmap.width)
        val previewHeight = (bitmap.height * (previewWidth.toFloat() / bitmap.width))
            .toInt()
            .coerceAtLeast(1)
        if (showPreview) {
            snapshotPreview = Bitmap.createScaledBitmap(
                bitmap,
                previewWidth,
                previewHeight,
                true,
            )
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    VideoSnapshotStorage.save(
                        context = applicationContext,
                        bitmap = bitmap,
                        deviceName = device.displayName,
                    )
                }.also { bitmap.recycle() }
            }
            result.onSuccess {
                message = ""
            }.onFailure {
                message = it.message ?: "Salvataggio della foto non riuscito."
            }
        }
    }

    private fun startVideoRecording(device: MonitoredDevice) {
        if (videoRecordingState.isActive || !ensureLegacyStoragePermission()) return
        val track = cameraTrack
        if (track == null) {
            message = "La track video non è ancora disponibile."
            return
        }
        videoRecordingState = LocalRecordingState.Starting
        updateMediaPowerProtection()
        lateinit var recorder: VideoMp4Recorder
        recorder = VideoMp4Recorder(
            context = applicationContext,
            deviceName = device.displayName,
            onStarted = {
                runOnUiThread {
                    if (videoRecorder === recorder &&
                        videoRecordingState is LocalRecordingState.Starting
                    ) {
                        val startedAt = SystemClock.elapsedRealtime()
                        videoRecordingState = LocalRecordingState.Recording(startedAt)
                        videoRecordingTimerJob = startRecordingTimer(
                            startedAtElapsedMs = startedAt,
                            update = { elapsed ->
                                videoRecordingState =
                                    LocalRecordingState.Recording(startedAt, elapsed)
                            },
                            stop = ::stopVideoRecording,
                        )
                    }
                }
            },
            onFinished = { result ->
                runOnUiThread { finishVideoRecording(recorder, result) }
            },
        )
        videoRecorder = recorder
        runCatching { recorder.start(track) }.onFailure {
            videoRecorder = null
            videoRecordingState = LocalRecordingState.Idle
            updateMediaPowerProtection()
            message = it.message ?: "Avvio registrazione video non riuscito."
        }
    }

    private fun stopVideoRecording() {
        if (!videoRecordingState.isActive ||
            videoRecordingState is LocalRecordingState.Finalizing
        ) {
            return
        }
        videoRecordingTimerJob?.cancel()
        videoRecordingTimerJob = null
        videoRecordingState = LocalRecordingState.Finalizing
        videoRecorder?.stop()
    }

    private fun finishVideoRecording(
        recorder: VideoMp4Recorder,
        result: RecordingResult,
    ) {
        if (videoRecorder !== recorder) return
        videoRecordingTimerJob?.cancel()
        videoRecordingTimerJob = null
        videoRecorder = null
        videoRecordingState = LocalRecordingState.Idle
        updateMediaPowerProtection()
        showRecordingResult("Video", result)
    }

    private fun startScreenRecording(device: MonitoredDevice) {
        if (screenRecordingState.isActive || !ensureLegacyStoragePermission()) return
        val track = screenTrack
        if (track == null) {
            message = "La track dello schermo non è ancora disponibile."
            return
        }
        screenRecordingState = LocalRecordingState.Starting
        updateMediaPowerProtection()
        lateinit var recorder: VideoMp4Recorder
        recorder = VideoMp4Recorder(
            context = applicationContext,
            deviceName = "Schermo_${device.displayName}",
            recordingKind = RecordingKind.SCREEN,
            onStarted = {
                runOnUiThread {
                    if (screenRecorder === recorder &&
                        screenRecordingState is LocalRecordingState.Starting
                    ) {
                        val startedAt = SystemClock.elapsedRealtime()
                        screenRecordingState = LocalRecordingState.Recording(startedAt)
                        screenRecordingTimerJob = startRecordingTimer(
                            startedAtElapsedMs = startedAt,
                            update = { elapsed ->
                                screenRecordingState =
                                    LocalRecordingState.Recording(startedAt, elapsed)
                            },
                            stop = ::stopScreenRecording,
                        )
                    }
                }
            },
            onFinished = { result ->
                runOnUiThread { finishScreenRecording(recorder, result) }
            },
        )
        screenRecorder = recorder
        runCatching { recorder.start(track) }.onFailure {
            screenRecorder = null
            screenRecordingState = LocalRecordingState.Idle
            updateMediaPowerProtection()
            message = it.message ?: "Avvio registrazione schermo non riuscito."
        }
    }

    private fun stopScreenRecording() {
        if (!screenRecordingState.isActive ||
            screenRecordingState is LocalRecordingState.Finalizing
        ) {
            return
        }
        screenRecordingTimerJob?.cancel()
        screenRecordingTimerJob = null
        screenRecordingState = LocalRecordingState.Finalizing
        screenRecorder?.stop()
    }

    private fun finishScreenRecording(
        recorder: VideoMp4Recorder,
        result: RecordingResult,
    ) {
        if (screenRecorder !== recorder) return
        screenRecordingTimerJob?.cancel()
        screenRecordingTimerJob = null
        screenRecorder = null
        screenRecordingState = LocalRecordingState.Idle
        updateMediaPowerProtection()
        showRecordingResult("Schermo", result)
    }

    private fun startAudioRecording(device: MonitoredDevice) {
        if (audioRecordingState.isActive || !ensureLegacyStoragePermission()) return
        val track = audioTrack
        if (track == null) {
            message = "La track audio non è ancora disponibile."
            return
        }
        audioRecordingState = LocalRecordingState.Starting
        updateMediaPowerProtection()
        lateinit var recorder: AudioM4aRecorder
        recorder = AudioM4aRecorder(
            context = applicationContext,
            deviceName = device.displayName,
            onStarted = {
                runOnUiThread {
                    if (audioRecorder === recorder &&
                        audioRecordingState is LocalRecordingState.Starting
                    ) {
                        val startedAt = SystemClock.elapsedRealtime()
                        audioRecordingState = LocalRecordingState.Recording(startedAt)
                        audioRecordingTimerJob = startRecordingTimer(
                            startedAtElapsedMs = startedAt,
                            update = { elapsed ->
                                audioRecordingState =
                                    LocalRecordingState.Recording(startedAt, elapsed)
                            },
                            stop = ::stopAudioRecording,
                        )
                    }
                }
            },
            onFinished = { result ->
                runOnUiThread { finishAudioRecording(recorder, result) }
            },
        )
        audioRecorder = recorder
        runCatching { recorder.start(track) }.onFailure {
            audioRecorder = null
            audioRecordingState = LocalRecordingState.Idle
            updateMediaPowerProtection()
            message = it.message ?: "Avvio registrazione audio non riuscito."
        }
    }

    private fun stopAudioRecording() {
        if (!audioRecordingState.isActive ||
            audioRecordingState is LocalRecordingState.Finalizing
        ) {
            return
        }
        audioRecordingTimerJob?.cancel()
        audioRecordingTimerJob = null
        audioRecordingState = LocalRecordingState.Finalizing
        audioRecorder?.stop()
    }

    private fun finishAudioRecording(
        recorder: AudioM4aRecorder,
        result: RecordingResult,
    ) {
        if (audioRecorder !== recorder) return
        audioRecordingTimerJob?.cancel()
        audioRecordingTimerJob = null
        audioRecorder = null
        audioRecordingState = LocalRecordingState.Idle
        updateMediaPowerProtection()
        showRecordingResult("Audio", result)
    }

    private fun toggleGroupVideoRecording(device: MonitoredDevice) {
        val deviceId = device.device.id
        if (groupVideoRecordingStates[deviceId]?.isActive == true) {
            stopGroupVideoRecording(deviceId)
        } else {
            startGroupVideoRecording(device)
        }
    }

    /** Avvia un MP4 indipendente rispettando il limite di due encoder simultanei. */
    private fun startGroupVideoRecording(device: MonitoredDevice) {
        val deviceId = device.device.id
        val activeIds = groupVideoRecordingStates.filterValues { it.isActive }.keys
        if (!GroupMediaPolicy.canStartRecording(activeIds, deviceId) ||
            !ensureLegacyStoragePermission()
        ) {
            message = "Sono già attive due registrazioni video."
            return
        }
        val track = cameraTracks[deviceId]
        if (track == null) {
            message = "La track video di ${device.displayName} non è ancora disponibile."
            return
        }
        groupVideoRecordingStates[deviceId] = LocalRecordingState.Starting
        lateinit var recorder: VideoMp4Recorder
        recorder = VideoMp4Recorder(
            context = applicationContext,
            deviceName = "${device.displayName}_${deviceId.take(8)}",
            onStarted = {
                runOnUiThread {
                    if (groupVideoRecorders[deviceId] === recorder) {
                        val startedAt = SystemClock.elapsedRealtime()
                        groupVideoRecordingStates[deviceId] =
                            LocalRecordingState.Recording(startedAt)
                        groupVideoRecordingTimerJobs[deviceId] = startRecordingTimer(
                            startedAtElapsedMs = startedAt,
                            update = { elapsed ->
                                groupVideoRecordingStates[deviceId] =
                                    LocalRecordingState.Recording(startedAt, elapsed)
                            },
                            stop = { stopGroupVideoRecording(deviceId) },
                        )
                    }
                }
            },
            onFinished = { result ->
                runOnUiThread {
                    if (groupVideoRecorders[deviceId] !== recorder) return@runOnUiThread
                    groupVideoRecordingTimerJobs.remove(deviceId)?.cancel()
                    groupVideoRecorders.remove(deviceId)
                    groupVideoRecordingStates.remove(deviceId)
                    updateMediaPowerProtection()
                    showRecordingResult("Video ${device.displayName}", result)
                }
            },
        )
        groupVideoRecorders[deviceId] = recorder
        updateMediaPowerProtection()
        runCatching { recorder.start(track) }.onFailure {
            groupVideoRecorders.remove(deviceId)
            groupVideoRecordingStates.remove(deviceId)
            updateMediaPowerProtection()
            message = it.message ?: "Avvio registrazione video non riuscito."
        }
    }

    private fun stopGroupVideoRecording(deviceId: String) {
        val state = groupVideoRecordingStates[deviceId] ?: return
        if (!state.isActive || state is LocalRecordingState.Finalizing) return
        groupVideoRecordingTimerJobs.remove(deviceId)?.cancel()
        groupVideoRecordingStates[deviceId] = LocalRecordingState.Finalizing
        groupVideoRecorders[deviceId]?.stop()
    }

    private fun toggleGroupAudioRecording(device: MonitoredDevice) {
        val deviceId = device.device.id
        if (groupAudioRecordingStates[deviceId]?.isActive == true) {
            stopGroupAudioRecording(deviceId)
        } else {
            startGroupAudioRecording(device)
        }
    }

    /** Avvia un M4A per trasmettitore mantenendo separati i flussi ascoltati insieme. */
    private fun startGroupAudioRecording(device: MonitoredDevice) {
        val deviceId = device.device.id
        val activeIds = groupAudioRecordingStates.filterValues { it.isActive }.keys
        if (!GroupMediaPolicy.canStartRecording(activeIds, deviceId) ||
            !ensureLegacyStoragePermission()
        ) {
            message = "Sono già attive due registrazioni audio."
            return
        }
        val track = audioTracks[deviceId]
        if (track == null) {
            message = "La track audio di ${device.displayName} non è ancora disponibile."
            return
        }
        groupAudioRecordingStates[deviceId] = LocalRecordingState.Starting
        lateinit var recorder: AudioM4aRecorder
        recorder = AudioM4aRecorder(
            context = applicationContext,
            deviceName = "${device.displayName}_${deviceId.take(8)}",
            onStarted = {
                runOnUiThread {
                    if (groupAudioRecorders[deviceId] === recorder) {
                        val startedAt = SystemClock.elapsedRealtime()
                        groupAudioRecordingStates[deviceId] =
                            LocalRecordingState.Recording(startedAt)
                        groupAudioRecordingTimerJobs[deviceId] = startRecordingTimer(
                            startedAtElapsedMs = startedAt,
                            update = { elapsed ->
                                groupAudioRecordingStates[deviceId] =
                                    LocalRecordingState.Recording(startedAt, elapsed)
                            },
                            stop = { stopGroupAudioRecording(deviceId) },
                        )
                    }
                }
            },
            onFinished = { result ->
                runOnUiThread {
                    if (groupAudioRecorders[deviceId] !== recorder) return@runOnUiThread
                    groupAudioRecordingTimerJobs.remove(deviceId)?.cancel()
                    groupAudioRecorders.remove(deviceId)
                    groupAudioRecordingStates.remove(deviceId)
                    updateMediaPowerProtection()
                    showRecordingResult("Audio ${device.displayName}", result)
                }
            },
        )
        groupAudioRecorders[deviceId] = recorder
        updateMediaPowerProtection()
        runCatching { recorder.start(track) }.onFailure {
            groupAudioRecorders.remove(deviceId)
            groupAudioRecordingStates.remove(deviceId)
            updateMediaPowerProtection()
            message = it.message ?: "Avvio registrazione audio non riuscito."
        }
    }

    private fun stopGroupAudioRecording(deviceId: String) {
        val state = groupAudioRecordingStates[deviceId] ?: return
        if (!state.isActive || state is LocalRecordingState.Finalizing) return
        groupAudioRecordingTimerJobs.remove(deviceId)?.cancel()
        groupAudioRecordingStates[deviceId] = LocalRecordingState.Finalizing
        groupAudioRecorders[deviceId]?.stop()
    }

    private fun stopAllGroupRecordings() {
        groupVideoRecordingStates.keys.toList().forEach(::stopGroupVideoRecording)
        groupAudioRecordingStates.keys.toList().forEach(::stopGroupAudioRecording)
    }

    private fun startRecordingTimer(
        startedAtElapsedMs: Long,
        update: (Long) -> Unit,
        stop: () -> Unit,
    ): Job = lifecycleScope.launch {
        while (isActive) {
            val elapsed = SystemClock.elapsedRealtime() - startedAtElapsedMs
            if (elapsed >= RecordingPolicy.MAX_DURATION_MS) {
                Toast.makeText(
                    this@ReceiverActivity,
                    "Raggiunto il limite di 30 minuti.",
                    Toast.LENGTH_SHORT,
                ).show()
                stop()
                return@launch
            }
            update(elapsed)
            delay(1_000)
        }
    }

    private fun stopAllRecordings() {
        stopVideoRecording()
        stopAudioRecording()
        stopScreenRecording()
    }

    private fun showRecordingResult(label: String, result: RecordingResult) {
        when {
            result.error != null -> {
                message = result.error.message ?: "Registrazione $label non riuscita."
            }
            result.path != null -> {
                message = ""
                Toast.makeText(
                    this,
                    "$label salvato in ${result.path}",
                    Toast.LENGTH_LONG,
                ).show()
            }
            else -> message = "Registrazione troppo breve: nessun file salvato."
        }
    }

    private fun ensureLegacyStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1202)
        message = "Concedi l’accesso ai file e riprova."
        return false
    }

    private fun openFullscreenMap(deviceId: String) {
        fullscreenDeviceId = deviceId
        enterImmersiveLandscape()
    }

    /**
     * Apre la verifica distanza e richiede il GPS del ricevitore quando necessario.
     */
    private fun openDistanceVerification(deviceId: String) {
        distanceDeviceId = deviceId
        receiverLocation = null
        message = ""
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startDistanceLocationIfAllowed()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** Chiude la verifica distanza e arresta il GPS locale. */
    private fun closeDistanceVerification() {
        if (distanceFullscreenActive) setDistanceFullscreen(false)
        distanceDeviceId = null
        receiverLocationController.stop()
        receiverLocation = null
    }

    /**
     * Avvia il GPS locale con la frequenza offline o online già selezionata.
     */
    private fun startDistanceLocationIfAllowed(intervalSec: Int? = null) {
        val deviceId = distanceDeviceId ?: return
        if (!isForeground ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val device = devices.firstOrNull { it.device.id == deviceId } ?: return
        val effectiveInterval = intervalSec ?: if (isFastTrackingActive(device)) {
            trackingSettings.onlineLocationIntervalSec
        } else {
            trackingSettings.offlineLocationIntervalSec
        }
        receiverLocationController.start(effectiveInterval)
    }

    /** Commuta la mappa distanza tra vista normale e fullscreen landscape. */
    private fun setDistanceFullscreen(enabled: Boolean) {
        if (distanceFullscreenActive == enabled) return
        distanceFullscreenActive = enabled
        if (enabled) enterImmersiveLandscape() else exitImmersiveLandscape()
    }

    private fun closeFullscreenMap() {
        if (fullscreenDeviceId == null) return
        fullscreenDeviceId = null
        if (!historyFullscreenActive) exitImmersiveLandscape()
    }

    /** Apre la griglia video in modalità landscape immersiva. */
    private fun openGroupFullscreen() {
        groupSingleFullscreenDeviceId = null
        groupFullscreenActive = true
        enterImmersiveLandscape()
    }

    /** Apre un video del gruppo in verticale, con i comandi sotto l'immagine. */
    private fun openGroupSingleFullscreen(deviceId: String) {
        groupSingleFullscreenDeviceId = deviceId
        groupFullscreenActive = true
        enterImmersivePortrait()
    }

    /** Torna dal singolo video alla griglia landscape. */
    private fun closeGroupSingleFullscreen() {
        groupSingleFullscreenDeviceId = null
        enterImmersiveLandscape()
    }

    /** Torna dal fullscreen alla pagina Video del gruppo. */
    private fun closeGroupFullscreen() {
        if (!groupFullscreenActive) return
        groupSingleFullscreenDeviceId = null
        groupFullscreenActive = false
        exitImmersiveLandscape()
    }

    private fun openVideoFullscreen(deviceId: String) {
        videoFullscreenDeviceId = deviceId
        enterImmersivePortrait()
    }

    private fun closeVideoFullscreen() {
        if (videoFullscreenDeviceId == null) return
        videoFullscreenDeviceId = null
        exitImmersiveLandscape()
    }

    private fun openScreenFullscreen(deviceId: String) {
        screenFullscreenDeviceId = deviceId
        // Keep the mirrored screen stable until the user exits fullscreen.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        Log.d(TAG, "Fullscreen orientation locked to portrait")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.post(::hideSystemBars)
    }

    private fun closeScreenFullscreen() {
        if (screenFullscreenDeviceId == null) return
        screenFullscreenDeviceId = null
        exitImmersiveLandscape()
    }

    private fun setHistoryFullscreen(enabled: Boolean) {
        if (historyFullscreenActive == enabled) return
        historyFullscreenActive = enabled
        if (enabled) enterImmersiveLandscape() else exitImmersiveLandscape()
    }

    private fun enterImmersiveLandscape() {
        // Maps stay in one landscape orientation instead of following the sensor.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Log.d(TAG, "Fullscreen orientation locked to landscape")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.post(::hideSystemBars)
    }

    private fun enterImmersivePortrait() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        Log.d(TAG, "Fullscreen orientation locked to portrait")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.post(::hideSystemBars)
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun exitImmersiveLandscape() {
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, true)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        Log.d(TAG, "Fullscreen closed; portrait orientation restored")
    }

    /**
     * Avvia il lease rapido e protegge la sessione anche durante il blocco schermo.
     */
    private fun startFastTracking(deviceId: String, historyEnabled: Boolean = false) {
        if (fastTrackingDisabledOverrideDeviceId == deviceId) {
            fastTrackingDisabledOverrideDeviceId = null
        }
        fastTrackingDeviceId = deviceId
        fastHistory = historyEnabled
        updateMediaPowerProtection()
        trackingLeaseJob?.cancel()
        trackingLeaseJob = lifecycleScope.launch {
            while (isActive && fastTrackingDeviceId == deviceId) {
                renewFastTracking()
                delay(TRACKING_LEASE_RENEW_INTERVAL_MS)
            }
        }
    }

    /**
     * Cambia lo storico rapido rispettando l'origine temporanea o persistente del tracking.
     */
    private fun setFastHistory(device: MonitoredDevice, enabled: Boolean) {
        val deviceId = device.device.id
        fastHistory = enabled
        if (device.relationship?.liveTrackingPersistent == true) {
            fastHistoryOverrideDeviceId = deviceId
            lifecycleScope.launch {
                runCatching {
                    repository!!.setLiveHistory(identity.id, deviceId, enabled)
                }.onFailure {
                    fastHistoryOverrideDeviceId = null
                    requestDataPlaneRecovery(it, "Aggiornamento storico rapido non riuscito.")
                }
            }
        } else if (fastTrackingDeviceId == deviceId) {
            renewFastTracking()
        } else {
            startFastTracking(deviceId, enabled)
        }
    }

    /**
     * Verifica tracking persistente, lease server e rinnovo locale del dispositivo.
     */
    private fun isFastTrackingActive(device: MonitoredDevice): Boolean =
        fastTrackingDisabledOverrideDeviceId != device.device.id &&
            (device.relationship?.liveTrackingPersistent == true ||
            TrackingConfigResolver.isLiveTrackingLeaseActive(
                device.relationship?.liveTrackingUntil,
            ) ||
                fastTrackingDeviceId == device.device.id)

    /**
     * Risolve lo stato dello storico rapido con feedback ottimistico per il server.
     */
    private fun isFastHistoryActive(device: MonitoredDevice): Boolean {
        if (!isFastTrackingActive(device)) return false
        val relationship = device.relationship
        return if (relationship?.liveTrackingPersistent == true) {
            if (fastHistoryOverrideDeviceId == device.device.id) {
                fastHistory
            } else {
                relationship.liveHistory
            }
        } else {
            fastTrackingDeviceId == device.device.id && fastHistory
        }
    }

    private fun renewFastTracking() {
        val deviceId = fastTrackingDeviceId ?: return
        lifecycleScope.launch {
            runCatching {
                repository!!.setLiveTracking(
                    receiverId = identity.id,
                    transmitterId = deviceId,
                    until = Instant.now().plusSeconds(TRACKING_LEASE_DURATION_SEC),
                    liveHistory = fastHistory,
                )
            }.onFailure {
                requestDataPlaneRecovery(it, "Aggiornamento rapido non riuscito.")
            }
        }
    }

    /**
     * Arresta il rinnovo rapido e rilascia la protezione se non restano altre attività.
     */
    private fun stopFastTracking(manualDeviceId: String? = null) {
        val leasedDeviceId = fastTrackingDeviceId
        val deviceId = manualDeviceId ?: leasedDeviceId
        trackingLeaseJob?.cancel()
        trackingLeaseJob = null
        fastTrackingDeviceId = null
        fastHistory = false
        if (fastHistoryOverrideDeviceId == deviceId) fastHistoryOverrideDeviceId = null
        if (manualDeviceId != null) fastTrackingDisabledOverrideDeviceId = deviceId
        updateMediaPowerProtection()
        if (deviceId != null) {
            lifecycleScope.launch {
                runCatching {
                    if (manualDeviceId != null) {
                        repository!!.clearLiveTracking(identity.id, deviceId)
                    } else {
                        repository!!.setLiveTracking(
                            receiverId = identity.id,
                            transmitterId = deviceId,
                            until = null,
                            liveHistory = false,
                        )
                    }
                }.onFailure {
                    if (manualDeviceId != null) {
                        fastTrackingDisabledOverrideDeviceId = null
                        requestDataPlaneRecovery(it, "Disattivazione rapida non riuscita.")
                    }
                }
            }
        }
    }

    private fun stopAllStreams(deviceId: String) {
        lifecycleScope.launch {
            runCatching {
                repository!!.sendCommand(deviceId, CommandType.STOP_VIDEO)
                repository!!.sendCommand(deviceId, CommandType.STOP_AUDIO)
                repository!!.sendCommand(deviceId, CommandType.STOP_SCREEN)
            }.onFailure {
                requestDataPlaneRecovery(it, "Chiusura stream non riuscita.")
            }
        }
    }

    private fun command(device: MonitoredDevice, type: CommandType) {
        if (type == CommandType.SWITCH_CAMERA) {
            sendCameraSwitchCommand(device)
            return
        }
        val target = MediaCommandPolicy.target(type)
        if (target != null) {
            sendMediaCommand(device, type, target.stream, target.enabled)
            return
        }
        lifecycleScope.launch {
            runCatching { repository!!.sendCommand(device.device.id, type) }
                .onFailure { requestDataPlaneRecovery(it, "Invio comando non riuscito.") }
        }
    }

    /**
     * Invia un comando multimediale mostrando subito lo stato richiesto e impedendo duplicati.
     */
    private fun sendMediaCommand(
        device: MonitoredDevice,
        type: CommandType,
        stream: MediaStreamKind,
        targetEnabled: Boolean,
    ) {
        val key = MediaCommandKey(device.device.id, stream)
        val activeFeedback = mediaCommandFeedback[key]
        if (activeFeedback != null && activeFeedback.phase != MediaCommandPhase.FAILED) return
        when (type) {
            CommandType.STOP_VIDEO -> {
                if (selectedDeviceId == device.device.id) stopVideoRecording()
                stopGroupVideoRecording(device.device.id)
                cameraSwitchFeedback[device.device.id]
                    ?.let(::clearCameraSwitchFeedback)
            }
            CommandType.STOP_AUDIO -> {
                if (selectedDeviceId == device.device.id) stopAudioRecording()
                stopGroupAudioRecording(device.device.id)
            }
            CommandType.STOP_SCREEN -> {
                if (selectedDeviceId == device.device.id) stopScreenRecording()
            }
            else -> Unit
        }

        val feedback = MediaCommandFeedback(
            requestId = ++mediaCommandRequestSequence,
            deviceId = device.device.id,
            stream = stream,
            targetEnabled = targetEnabled,
            phase = MediaCommandPhase.SENDING,
            startedElapsedMs = SystemClock.elapsedRealtime(),
        )
        setMediaCommandFeedback(feedback)
        mediaCommandTimeoutJobs.remove(key)?.cancel()
        mediaCommandTimeoutJobs[key] = lifecycleScope.launch {
            val sendResult = runCatching {
                withTimeout(ConnectionRecoveryPolicy.REQUEST_TIMEOUT_MS) {
                    repository!!.sendCommand(device.device.id, type)
                }
            }
            if (sendResult.isFailure) {
                val error = sendResult.exceptionOrNull()!!
                if (error is CancellationException) return@launch
                updateMediaCommandFeedback(feedback.requestId) {
                    it.copy(
                        phase = MediaCommandPhase.FAILED,
                        errorMessage = "Invio non riuscito. Tocca per riprovare.",
                    )
                }
                requestDataPlaneRecovery(
                    error,
                    "Invio comando non riuscito.",
                )
                return@launch
            }

            val currentStatus = devices.firstOrNull {
                it.device.id == feedback.deviceId
            }?.status
            if (MediaCommandPolicy.isConfirmed(feedback, currentStatus)) {
                clearMediaCommandFeedback(feedback)
                return@launch
            }
            updateMediaCommandFeedback(feedback.requestId) {
                it.copy(phase = MediaCommandPhase.AWAITING_CONFIRMATION)
            }
            val elapsedMs = SystemClock.elapsedRealtime() - feedback.startedElapsedMs
            delay((MediaCommandPolicy.CONFIRMATION_TIMEOUT_MS - elapsedMs).coerceAtLeast(0L))
            val pending = mediaCommandFeedback[key]
            if (pending?.requestId == feedback.requestId &&
                MediaCommandPolicy.hasTimedOut(pending, SystemClock.elapsedRealtime())
            ) {
                updateMediaCommandFeedback(feedback.requestId) {
                    it.copy(
                        phase = MediaCommandPhase.FAILED,
                        errorMessage = "Il dispositivo non ha confermato. Tocca per riprovare.",
                    )
                }
            }
        }
    }

    /**
     * Rimuove le attese quando lo stato pubblicato conferma il comando richiesto.
     */
    private fun reconcileMediaCommandFeedback(rows: List<MonitoredDevice>) {
        mediaCommandFeedback.values.toList().forEach { feedback ->
            val status = rows.firstOrNull { it.device.id == feedback.deviceId }?.status
            if (MediaCommandPolicy.isConfirmed(feedback, status)) {
                clearMediaCommandFeedback(feedback)
            }
        }
    }

    /**
     * Registra un nuovo feedback rendendolo osservabile dalla UI Compose.
     */
    private fun setMediaCommandFeedback(feedback: MediaCommandFeedback) {
        val key = MediaCommandKey(feedback.deviceId, feedback.stream)
        mediaCommandFeedback = mediaCommandFeedback + (key to feedback)
        updateMediaPowerProtection()
    }

    /**
     * Aggiorna soltanto la richiesta ancora corrente, evitando race con un nuovo tentativo.
     */
    private fun updateMediaCommandFeedback(
        requestId: Long,
        transform: (MediaCommandFeedback) -> MediaCommandFeedback,
    ) {
        val current = mediaCommandFeedback.values.firstOrNull { it.requestId == requestId } ?: return
        setMediaCommandFeedback(transform(current))
    }

    /**
     * Chiude l'attesa confermata e annulla il relativo timeout.
     */
    private fun clearMediaCommandFeedback(feedback: MediaCommandFeedback) {
        val key = MediaCommandKey(feedback.deviceId, feedback.stream)
        val current = mediaCommandFeedback[key]
        if (current?.requestId != feedback.requestId) return
        mediaCommandFeedback = mediaCommandFeedback - key
        mediaCommandTimeoutJobs.remove(key)?.cancel()
        updateMediaPowerProtection()
    }

    /**
     * Invia un solo cambio camera e attende che il facing remoto sia realmente variato.
     */
    private fun sendCameraSwitchCommand(device: MonitoredDevice) {
        val deviceId = device.device.id
        val active = cameraSwitchFeedback[deviceId]
        if (active != null && active.phase != MediaCommandPhase.FAILED) return
        val feedback = CameraSwitchFeedback(
            requestId = ++mediaCommandRequestSequence,
            deviceId = device.device.id,
            initialFacing = device.status?.cameraFacing ?: "front",
            phase = MediaCommandPhase.SENDING,
            startedElapsedMs = SystemClock.elapsedRealtime(),
        )
        cameraSwitchFeedback = cameraSwitchFeedback + (deviceId to feedback)
        cameraSwitchTimeoutJobs.remove(deviceId)?.cancel()
        cameraSwitchTimeoutJobs[deviceId] = lifecycleScope.launch {
            val sendResult = runCatching {
                withTimeout(ConnectionRecoveryPolicy.REQUEST_TIMEOUT_MS) {
                    repository!!.sendCommand(device.device.id, CommandType.SWITCH_CAMERA)
                }
            }
            if (sendResult.isFailure) {
                val error = sendResult.exceptionOrNull()!!
                if (error is CancellationException) return@launch
                updateCameraSwitchFeedback(feedback.requestId) {
                    it.copy(
                        phase = MediaCommandPhase.FAILED,
                        errorMessage = "Cambio fotocamera non inviato. Tocca per riprovare.",
                    )
                }
                requestDataPlaneRecovery(error, "Cambio fotocamera non riuscito.")
                return@launch
            }

            val currentStatus = devices.firstOrNull {
                it.device.id == feedback.deviceId
            }?.status
            if (MediaCommandPolicy.isCameraSwitchConfirmed(feedback, currentStatus)) {
                clearCameraSwitchFeedback(feedback)
                return@launch
            }
            updateCameraSwitchFeedback(feedback.requestId) {
                it.copy(phase = MediaCommandPhase.AWAITING_CONFIRMATION)
            }
            val elapsedMs = SystemClock.elapsedRealtime() - feedback.startedElapsedMs
            delay((MediaCommandPolicy.CONFIRMATION_TIMEOUT_MS - elapsedMs).coerceAtLeast(0L))
            val pending = cameraSwitchFeedback[deviceId]
            if (pending?.requestId == feedback.requestId &&
                MediaCommandPolicy.hasCameraSwitchTimedOut(
                    pending,
                    SystemClock.elapsedRealtime(),
                )
            ) {
                updateCameraSwitchFeedback(feedback.requestId) {
                    it.copy(
                        phase = MediaCommandPhase.FAILED,
                        errorMessage = "Cambio non confermato. Tocca per riprovare.",
                    )
                }
            }
        }
    }

    /**
     * Chiude l'attesa quando il trasmettitore pubblica il nuovo facing.
     */
    private fun reconcileCameraSwitchFeedback(rows: List<MonitoredDevice>) {
        cameraSwitchFeedback.values.toList().forEach { feedback ->
            val status = rows.firstOrNull { it.device.id == feedback.deviceId }?.status
            if (MediaCommandPolicy.isCameraSwitchConfirmed(feedback, status)) {
                clearCameraSwitchFeedback(feedback)
            }
        }
    }

    /**
     * Aggiorna il cambio camera soltanto se appartiene alla richiesta corrente.
     */
    private fun updateCameraSwitchFeedback(
        requestId: Long,
        transform: (CameraSwitchFeedback) -> CameraSwitchFeedback,
    ) {
        val current = cameraSwitchFeedback.values
            .firstOrNull { it.requestId == requestId }
            ?: return
        if (current.requestId != requestId) return
        val updated = transform(current)
        cameraSwitchFeedback = cameraSwitchFeedback + (updated.deviceId to updated)
    }

    /**
     * Rimuove il feedback camera confermato e annulla il timeout associato.
     */
    private fun clearCameraSwitchFeedback(feedback: CameraSwitchFeedback) {
        if (cameraSwitchFeedback[feedback.deviceId]?.requestId != feedback.requestId) return
        cameraSwitchFeedback = cameraSwitchFeedback - feedback.deviceId
        cameraSwitchTimeoutJobs.remove(feedback.deviceId)?.cancel()
    }

    private fun toggleVoiceMessageRecording(device: MonitoredDevice) {
        if (voiceMessageState is VoiceMessageDraftState.Recording) {
            finishVoiceMessageRecording()
            return
        }
        if (voiceMessageState !is VoiceMessageDraftState.Idle) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingVoiceRecordDeviceId = device.device.id
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceMessageRecording()
    }

    @SuppressLint("MissingPermission")
    private fun startVoiceMessageRecording() {
        val output = File(
            cacheDir,
            "voice-messages/draft-${UUID.randomUUID()}.m4a",
        )
        voiceMessageFeedback = ""
        runCatching {
            voiceMessageRecorder.start(output)
            voiceMessageDraftFile = output
            voiceMessageState = VoiceMessageDraftState.Recording(0)
            voiceMessageTimerJob?.cancel()
            voiceMessageTimerJob = lifecycleScope.launch {
                while (isActive && voiceMessageState is VoiceMessageDraftState.Recording) {
                    val elapsed = (
                        voiceMessageState as? VoiceMessageDraftState.Recording
                        )?.elapsedMs ?: break
                    if (elapsed >= VoiceMessagePolicy.MAX_DURATION_MS) {
                        finishVoiceMessageRecording()
                        break
                    }
                    delay(250)
                    val next = (
                        voiceMessageState as? VoiceMessageDraftState.Recording
                        )?.elapsedMs?.plus(250) ?: break
                    voiceMessageState = VoiceMessageDraftState.Recording(next)
                }
            }
        }.onFailure {
            output.delete()
            voiceMessageDraftFile = null
            voiceMessageState = VoiceMessageDraftState.Idle
            voiceMessageFeedback = "Registrazione non riuscita."
            Log.e(TAG, "Voice message recording start failed", it)
        }
    }

    private fun finishVoiceMessageRecording() {
        voiceMessageTimerJob?.cancel()
        voiceMessageTimerJob = null
        val output = voiceMessageDraftFile
        runCatching { voiceMessageRecorder.stop() }
            .onSuccess { durationMs ->
                if (output == null ||
                    !VoiceMessagePolicy.isValidDuration(durationMs) ||
                    !output.exists()
                ) {
                    output?.delete()
                    voiceMessageDraftFile = null
                    voiceMessageState = VoiceMessageDraftState.Idle
                    voiceMessageFeedback = "Il messaggio deve durare almeno un secondo."
                } else {
                    voiceMessageState = VoiceMessageDraftState.Ready(durationMs)
                }
            }
            .onFailure {
                output?.delete()
                voiceMessageDraftFile = null
                voiceMessageState = VoiceMessageDraftState.Idle
                voiceMessageFeedback = "Registrazione non riuscita."
                Log.e(TAG, "Voice message recording stop failed", it)
            }
    }

    private fun discardVoiceMessage() {
        if (voiceMessageState is VoiceMessageDraftState.Sending) {
            voiceMessageDeliveryJob?.cancel()
        }
        voiceMessageTimerJob?.cancel()
        voiceMessageTimerJob = null
        if (voiceMessageState is VoiceMessageDraftState.Recording) {
            voiceMessageRecorder.cancel()
        }
        voiceMessageDraftFile?.delete()
        voiceMessageDraftFile = null
        voiceMessageState = VoiceMessageDraftState.Idle
        voiceMessageFeedback = ""
    }

    private fun sendVoiceMessage(device: MonitoredDevice) {
        val ready = voiceMessageState as? VoiceMessageDraftState.Ready ?: return
        val draft = voiceMessageDraftFile ?: return
        val selectedVolume = voiceMessageVolume
        voiceMessageState = VoiceMessageDraftState.Sending
        voiceMessageFeedback = ""
        voiceMessageDeliveryJob?.cancel()
        voiceMessageDeliveryJob = lifecycleScope.launch {
            val result = runCatching {
                val audio = withContext(Dispatchers.IO) { draft.readBytes() }
                repository!!.sendVoiceMessage(
                    receiverId = identity.id,
                    transmitterId = device.device.id,
                    messageId = UUID.randomUUID().toString(),
                    audio = audio,
                    volume = selectedVolume,
                    durationMs = ready.durationMs,
                )
            }
            result.onFailure {
                voiceMessageState = ready
                voiceMessageFeedback = "Invio non riuscito. Puoi riprovare."
                Log.e(TAG, "Voice message upload failed", it)
            }
            val sent = result.getOrNull() ?: return@launch
            draft.delete()
            voiceMessageDraftFile = null
            voiceMessageState = VoiceMessageDraftState.Idle
            voiceMessageFeedback = "Messaggio inviato, in attesa di riproduzione."
            repeat(45) {
                delay(2_000)
                val current = runCatching {
                    repository!!.fetchVoiceMessage(sent.id)
                }.getOrNull() ?: return@repeat
                voiceMessageFeedback = when (current.status) {
                    VoiceMessageStatus.PENDING -> "Messaggio in attesa del trasmettitore."
                    VoiceMessageStatus.DOWNLOADING -> "Download sul trasmettitore…"
                    VoiceMessageStatus.PLAYING -> "Riproduzione in corso…"
                    VoiceMessageStatus.COMPLETED -> "Messaggio riprodotto."
                    VoiceMessageStatus.FAILED ->
                        current.errorMessage ?: "Riproduzione non riuscita."
                }
                if (current.status == VoiceMessageStatus.COMPLETED ||
                    current.status == VoiceMessageStatus.FAILED
                ) {
                    return@launch
                }
            }
        }
    }

    /**
     * Invia un messaggio testuale persistente e ne segue lo stato di consegna.
     */
    private fun sendTextMessage(device: MonitoredDevice) {
        val normalized = runCatching {
            TextMessagePolicy.normalize(textMessageDraft)
        }.getOrElse {
            textMessageFeedback = it.message ?: "Messaggio non valido."
            return
        }
        textMessageSending = true
        textMessageFeedback = ""
        textMessageDeliveryJob?.cancel()
        textMessageDeliveryJob = lifecycleScope.launch {
            val sent = runCatching {
                repository!!.sendTextMessage(
                    receiverId = identity.id,
                    transmitterId = device.device.id,
                    messageId = UUID.randomUUID().toString(),
                    body = normalized,
                )
            }.onFailure {
                Log.e(TAG, "Text message send failed", it)
                textMessageFeedback = "Invio non riuscito. Puoi riprovare."
            }.getOrNull()
            textMessageSending = false
            if (sent == null) return@launch
            textMessageDraft = ""
            textMessageFeedback = "Messaggio inviato, in attesa del trasmettitore."
            repeat(TEXT_MESSAGE_STATUS_POLL_ATTEMPTS) {
                delay(TEXT_MESSAGE_STATUS_POLL_INTERVAL_MS)
                val current = runCatching {
                    repository!!.fetchTextMessage(sent.id)
                }.getOrNull() ?: return@repeat
                textMessageFeedback = when (current.status) {
                    TextMessageStatus.PENDING ->
                        "Messaggio in attesa del trasmettitore."
                    TextMessageStatus.WAITING_PERMISSION ->
                        "Sul trasmettitore manca il permesso “Mostra sopra altre app”."
                    TextMessageStatus.DISPLAYING ->
                        "Messaggio visualizzato sul trasmettitore."
                    TextMessageStatus.DISMISSED ->
                        "Messaggio letto e chiuso."
                    TextMessageStatus.FAILED ->
                        current.errorMessage ?: "Visualizzazione non riuscita."
                }
                if (current.status == TextMessageStatus.DISMISSED ||
                    current.status == TextMessageStatus.FAILED
                ) {
                    return@launch
                }
            }
        }
    }

    private fun reconcileMediaConnection() {
        if (!isForeground && !shouldMaintainMediaSession()) {
            disconnectMedia()
            return
        }
        val mediaDevices = activeMediaDevices()
        val shouldConnect = mediaDevices.any { item ->
            item.status?.let {
                MediaConnectionPolicy.shouldConnect(
                    it.cameraStreaming,
                    it.microphoneStreaming,
                    it.screenStreaming,
                )
            } == true
        }
        when {
            shouldConnect && room == null && connectionJob?.isActive != true ->
                mediaDevices.firstOrNull()?.device?.id?.let(::connectTo)
            !shouldConnect -> disconnectMedia()
        }
    }

    private fun activeMediaDevices(): List<MonitoredDevice> {
        val activeIds = buildSet {
            selectedDeviceId?.let(::add)
            if (groupMediaActive) addAll(groupSelectedDeviceIds)
        }
        return devices.filter { it.device.id in activeIds }
    }

    /**
     * Verifica se media, registrazioni o tracking rapido richiedono una sessione protetta.
     */
    private fun shouldMaintainMediaSession(): Boolean {
        val mediaDevices = activeMediaDevices()
        val activeDeviceIds = mediaDevices.mapTo(mutableSetOf()) { it.device.id }
        val activeStatuses = mediaDevices.mapNotNull { it.status }
        val pendingStarts = mediaCommandFeedback.values.filter {
            it.deviceId in activeDeviceIds &&
                it.targetEnabled &&
                it.phase != MediaCommandPhase.FAILED
        }
        return ReceiverPowerPolicy.shouldProtectMedia(
            cameraStreaming = activeStatuses.any { it.cameraStreaming } ||
                pendingStarts.any { it.stream == MediaStreamKind.VIDEO },
            microphoneStreaming = activeStatuses.any { it.microphoneStreaming } ||
                pendingStarts.any { it.stream == MediaStreamKind.AUDIO },
            screenStreaming = activeStatuses.any { it.screenStreaming } ||
                pendingStarts.any { it.stream == MediaStreamKind.SCREEN },
            videoRecording = videoRecordingState.isActive ||
                groupVideoRecordingStates.values.any { it.isActive },
            audioRecording = audioRecordingState.isActive ||
                groupAudioRecordingStates.values.any { it.isActive },
            screenRecording = screenRecordingState.isActive,
            fastTrackingActive = fastTrackingDeviceId != null,
        )
    }

    @SuppressLint("WakelockTimeout")
    /**
     * Allinea wake lock e blocco spegnimento display allo stato della sessione.
     */
    private fun updateMediaPowerProtection() {
        val shouldProtect = shouldMaintainMediaSession()
        if (shouldProtect) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!mediaWakeLock.isHeld) mediaWakeLock.acquire()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (mediaWakeLock.isHeld) mediaWakeLock.release()
        }
        if (mediaPowerProtectionActive != shouldProtect) {
            mediaPowerProtectionActive = shouldProtect
            Log.i(TAG, "Receiver media power protection active=$shouldProtect")
        }
    }

    private fun releaseMediaPowerProtection() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::mediaWakeLock.isInitialized && mediaWakeLock.isHeld) {
            mediaWakeLock.release()
        }
        mediaPowerProtectionActive = false
        Log.i(TAG, "Receiver media power protection released")
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
                requestDataPlaneRecovery(it, "Salvataggio nome non riuscito.")
            }
        }
    }

    private fun connectTo(deviceId: String) {
        connectionJob?.cancel()
        connectionJob = lifecycleScope.launch {
            runCatching {
                disconnectMedia(cancelConnection = false)
                val credentials = repository!!.liveKitToken(deviceId, "subscribe")
                room = LiveKit.create(
                    appContext = applicationContext,
                    options = RoomOptions(adaptiveStream = true),
                ).also { newRoom ->
                    roomDeviceId = deviceId
                    initializeScreenRenderer(newRoom)
                    cameraRenderers.values.forEach { initializeRenderer(newRoom, it) }
                    roomEventsJob = lifecycleScope.launch {
                        newRoom.events.collect { event ->
                            Log.d(TAG, "LiveKit room event: ${event::class.simpleName}")
                            when {
                                event is RoomEvent.TrackSubscribed && event.track is VideoTrack -> {
                                    deviceIdFromParticipant(event.participant.identity?.value)?.let {
                                        deviceId ->
                                        bindVideoTrack(
                                            deviceId,
                                            event.track as VideoTrack,
                                            event.publication.source,
                                        )
                                    }
                                }
                                event is RoomEvent.TrackSubscribed && event.track is AudioTrack -> {
                                    deviceIdFromParticipant(event.participant.identity?.value)?.let {
                                        bindAudioTrack(it, event.track as AudioTrack)
                                    }
                                }
                                event is RoomEvent.TrackUnsubscribed &&
                                    event.track is VideoTrack -> {
                                    deviceIdFromParticipant(event.participant.identity?.value)?.let {
                                        deviceId ->
                                        unbindVideoTrack(
                                            deviceId,
                                            event.track as VideoTrack,
                                            event.publications.source,
                                        )
                                    }
                                }
                                event is RoomEvent.TrackUnsubscribed &&
                                    event.track is AudioTrack -> {
                                    deviceIdFromParticipant(event.participant.identity?.value)?.let {
                                        deviceId ->
                                        if (selectedDeviceId == deviceId) stopAudioRecording()
                                        stopGroupAudioRecording(deviceId)
                                        if (audioTracks[deviceId] === event.track) {
                                            audioTracks.remove(deviceId)
                                        }
                                        if (selectedDeviceId == deviceId) audioTrack = null
                                    }
                                }
                                event is RoomEvent.TrackMuted &&
                                    event.publication.kind == Track.Kind.VIDEO -> {
                                    deviceIdFromParticipant(event.participant.identity?.value)?.let {
                                        clearVideoSource(it, event.publication.source)
                                    }
                                }
                                event is RoomEvent.TrackUnmuted &&
                                    event.publication.kind == Track.Kind.VIDEO -> {
                                    val deviceId =
                                        deviceIdFromParticipant(event.participant.identity?.value)
                                    (event.publication.track as? VideoTrack)?.let { track ->
                                        deviceId?.let {
                                            bindVideoTrack(it, track, event.publication.source)
                                        }
                                    }
                                }
                                event is RoomEvent.TrackMuted &&
                                    event.publication.kind == Track.Kind.AUDIO -> {
                                    deviceIdFromParticipant(event.participant.identity?.value)?.let {
                                        deviceId ->
                                        if (selectedDeviceId == deviceId) stopAudioRecording()
                                        stopGroupAudioRecording(deviceId)
                                        audioLevels[deviceId] = 0f
                                    }
                                }
                                event is RoomEvent.ActiveSpeakersChanged -> {
                                    event.speakers.forEach { participant ->
                                        deviceIdFromParticipant(participant.identity?.value)?.let {
                                            audioLevels[it] = participant.audioLevel
                                        }
                                    }
                                    audioLevel = selectedDeviceId?.let { audioLevels[it] } ?: 0f
                                }
                                event is RoomEvent.Disconnected -> {
                                    stopAllRecordings()
                                    stopAllGroupRecordings()
                                    audioLevel = 0f
                                    cameraRenderers.values.forEach { it.clearImage() }
                                    screenRenderer?.clearImage()
                                    if (room === newRoom) {
                                        room = null
                                        roomDeviceId = null
                                        lifecycleScope.launch {
                                            delay(MEDIA_RECONNECT_DELAY_MS)
                                            reconcileMediaConnection()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    audioMeterJob = lifecycleScope.launch {
                        while (isActive) {
                            audioLevel = newRoom.remoteParticipants.values
                                .onEach { participant ->
                                    deviceIdFromParticipant(participant.identity?.value)?.let {
                                        audioLevels[it] = participant.audioLevel
                                    }
                                }
                                .firstOrNull {
                                    deviceIdFromParticipant(it.identity?.value) == selectedDeviceId
                                }
                                ?.audioLevel
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
                recoverySignals.trySend(it)
                room = null
                roomDeviceId = null
                lifecycleScope.launch {
                    delay(MEDIA_RECONNECT_DELAY_MS)
                    reconcileMediaConnection()
                }
            }
        }
    }

    private fun deviceIdFromParticipant(identity: String?): String? {
        val deviceId = GroupMediaPolicy.transmitterDeviceId(identity) ?: return null
        return deviceId.takeIf { candidate ->
            devices.any { it.device.id == candidate }
        }
    }

    private fun bindVideoTrack(deviceId: String, track: VideoTrack, source: Track.Source) {
        if (source == Track.Source.SCREEN_SHARE) {
            val previous = screenTracks[deviceId]
            if (previous !== track) {
                if (selectedDeviceId == deviceId && screenRecordingState.isActive) {
                    stopScreenRecording()
                }
                screenRenderer?.takeIf { selectedDeviceId == deviceId }?.let { view ->
                    previous?.removeRenderer(view)
                }
            }
            screenTracks[deviceId] = track
            if (selectedDeviceId == deviceId) screenTrack = track
            Log.i(TAG, "Bound SCREEN_SHARE track for $deviceId")
        } else {
            val previous = cameraTracks[deviceId]
            if (previous !== track) {
                if (selectedDeviceId == deviceId && videoRecordingState.isActive) {
                    stopVideoRecording()
                }
                stopGroupVideoRecording(deviceId)
                cameraRenderers[deviceId]?.let { view -> previous?.removeRenderer(view) }
            }
            cameraTracks[deviceId] = track
            if (selectedDeviceId == deviceId) cameraTrack = track
            Log.i(TAG, "Bound CAMERA track for $deviceId")
        }
    }

    private fun initializeScreenRenderer(activeRoom: Room) {
        val view = screenRenderer ?: return
        initializeRenderer(activeRoom, view)
        view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        view.setMirror(false)
        Log.d(TAG, "Screen renderer initialized")
    }

    private fun initializeRenderer(activeRoom: Room, view: TextureViewRenderer) {
        if (!initializedRenderers.add(view)) return
        try {
            activeRoom.initVideoRenderer(view)
        } catch (error: Throwable) {
            initializedRenderers.remove(view)
            throw error
        }
    }

    private fun releaseRenderer(view: TextureViewRenderer) {
        if (initializedRenderers.remove(view)) view.release()
    }

    private fun unbindVideoTrack(
        deviceId: String,
        track: VideoTrack,
        source: Track.Source,
    ) {
        if (source == Track.Source.SCREEN_SHARE) {
            if (selectedDeviceId == deviceId) stopScreenRecording()
            if (screenTracks[deviceId] === track) screenTracks.remove(deviceId)
            if (selectedDeviceId == deviceId) {
                screenTrack = null
                screenRenderer?.clearImage()
            }
        } else {
            if (selectedDeviceId == deviceId) stopVideoRecording()
            stopGroupVideoRecording(deviceId)
            if (cameraTracks[deviceId] === track) cameraTracks.remove(deviceId)
            cameraRenderers[deviceId]?.clearImage()
            if (selectedDeviceId == deviceId) cameraTrack = null
        }
    }

    private fun clearVideoSource(deviceId: String, source: Track.Source) {
        if (source == Track.Source.SCREEN_SHARE) {
            if (selectedDeviceId == deviceId) {
                stopScreenRecording()
                screenRenderer?.clearImage()
            }
        } else {
            if (selectedDeviceId == deviceId) stopVideoRecording()
            stopGroupVideoRecording(deviceId)
            cameraRenderers[deviceId]?.clearImage()
        }
    }

    private fun bindAudioTrack(deviceId: String, track: AudioTrack) {
        val previous = audioTracks[deviceId]
        if (selectedDeviceId == deviceId && previous !== track && audioRecordingState.isActive) {
            stopAudioRecording()
        }
        if (previous !== track) stopGroupAudioRecording(deviceId)
        audioTracks[deviceId] = track
        if (selectedDeviceId == deviceId) audioTrack = track
    }

    private fun disconnectMedia(cancelConnection: Boolean = true) {
        stopAllRecordings()
        stopAllGroupRecordings()
        if (cancelConnection) connectionJob?.cancel()
        cameraRenderers.forEach { (deviceId, view) ->
            cameraTracks[deviceId]?.removeRenderer(view)
        }
        screenRenderer?.let { view -> screenTrack?.removeRenderer(view) }
        roomEventsJob?.cancel()
        roomEventsJob = null
        audioMeterJob?.cancel()
        audioMeterJob = null
        room?.disconnect()
        room = null
        roomDeviceId = null
        cameraTrack = null
        screenTrack = null
        audioTrack = null
        cameraTracks.clear()
        screenTracks.clear()
        audioTracks.clear()
        audioLevels.clear()
        audioLevel = 0f
        cameraRenderers.values.forEach { it.clearImage() }
        screenRenderer?.clearImage()
        initializedRenderers.toList().forEach(::releaseRenderer)
    }

    private fun requestDataPlaneRecovery(error: Throwable, fallbackMessage: String) {
        Log.e(TAG, fallbackMessage, error)
        message = fallbackMessage
        recoverySignals.trySend(error)
    }

    private companion object {
        const val TAG = "FindMeReceiver"
        const val MEDIA_WAKE_LOCK_TAG = "FindMe:ReceiverMedia"
        const val AUDIO_METER_INTERVAL_MS = 100L
        const val MEDIA_RECONNECT_DELAY_MS = 2_000L
        const val TRACKING_LEASE_DURATION_SEC = 90L
        const val TRACKING_LEASE_RENEW_INTERVAL_MS = 20_000L
        const val RECEIVER_CHANNEL_RENEWAL_INTERVAL_MS = 15 * 60 * 1_000L
        const val ONLINE_CLOCK_INTERVAL_MS = 10_000L
        const val TEXT_MESSAGE_STATUS_POLL_INTERVAL_MS = 2_000L
        const val TEXT_MESSAGE_STATUS_POLL_ATTEMPTS = 900
    }

    private class DataPlaneRestart(reason: String) : RuntimeException(reason)
}
