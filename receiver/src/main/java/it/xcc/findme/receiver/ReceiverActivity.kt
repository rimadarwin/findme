package it.xcc.findme.receiver

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import it.xcc.findme.core.AppConfig
import it.xcc.findme.core.CommandType
import it.xcc.findme.core.ConnectionRecoveryPolicy
import it.xcc.findme.core.DeviceIdentity
import it.xcc.findme.core.DeviceRole
import it.xcc.findme.core.FindMeRepository
import it.xcc.findme.core.MediaConnectionPolicy
import it.xcc.findme.core.MonitoredDevice
import it.xcc.findme.core.ReceiverProfile
import it.xcc.findme.core.ReceiverTrackingSettings
import it.xcc.findme.core.TrackingSettingsUpdate
import it.xcc.findme.receiver.recording.AudioM4aRecorder
import it.xcc.findme.receiver.recording.LocalRecordingState
import it.xcc.findme.receiver.recording.RecordingPolicy
import it.xcc.findme.receiver.recording.RecordingResult
import it.xcc.findme.receiver.recording.VideoMp4Recorder
import java.time.Instant
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
import kotlinx.coroutines.withTimeoutOrNull

class ReceiverActivity : ComponentActivity() {
    private var repository: FindMeRepository? = null
    private lateinit var identity: DeviceIdentity
    private var ready by mutableStateOf(false)
    private var receiverProfile by mutableStateOf<ReceiverProfile?>(null)
    private var devices by mutableStateOf<List<MonitoredDevice>>(emptyList())
    private var selectedDeviceId by mutableStateOf<String?>(null)
    private var selectedTab by mutableStateOf(DeviceTab.POSITION)
    private var showSettings by mutableStateOf(false)
    private var historyDeviceId by mutableStateOf<String?>(null)
    private var fullscreenDeviceId by mutableStateOf<String?>(null)
    private var historyFullscreenActive by mutableStateOf(false)
    private var trackingSettings by mutableStateOf(ReceiverTrackingSettings(receiverId = ""))
    private var fastTrackingDeviceId by mutableStateOf<String?>(null)
    private var fastHistory by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var audioLevel by mutableFloatStateOf(0f)
    private var videoTrack by mutableStateOf<VideoTrack?>(null)
    private var audioTrack by mutableStateOf<AudioTrack?>(null)
    private var room: Room? = null
    private var roomDeviceId: String? = null
    private var renderer: TextureViewRenderer? = null
    private var snapshotPreview by mutableStateOf<Bitmap?>(null)
    private var videoRecordingState by mutableStateOf<LocalRecordingState>(
        LocalRecordingState.Idle,
    )
    private var audioRecordingState by mutableStateOf<LocalRecordingState>(
        LocalRecordingState.Idle,
    )
    private var videoRecorder: VideoMp4Recorder? = null
    private var audioRecorder: AudioM4aRecorder? = null
    private var videoRecordingTimerJob: Job? = null
    private var audioRecordingTimerJob: Job? = null
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
    private val recoverySignals = Channel<Throwable>(Channel.CONFLATED)
    @Volatile
    private var networkWasLost = false

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
        connectivityManager = getSystemService(ConnectivityManager::class.java)
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
        if (hasFocus && (fullscreenDeviceId != null || historyFullscreenActive)) {
            hideSystemBars()
        }
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
        reconcileMediaConnection()
    }

    override fun onStop() {
        isForeground = false
        selectedDeviceId?.let(::stopAllStreams)
        stopFastTracking()
        disconnectMedia()
        super.onStop()
    }

    override fun onDestroy() {
        if (fullscreenDeviceId != null) closeFullscreenMap()
        if (historyFullscreenActive) setHistoryFullscreen(false)
        disconnectMedia()
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
        val historyDevice = historyDeviceId?.let { id ->
            devices.firstOrNull { it.device.id == id }
        }
        val fullscreenDevice = fullscreenDeviceId?.let { id ->
            devices.firstOrNull { it.device.id == id }
        }
        if (fullscreenDevice != null) {
            BackHandler(onBack = ::closeFullscreenMap)
            PositionFullscreenScreen(
                item = fullscreenDevice,
                heartbeatIntervalSec = trackingSettings.heartbeatIntervalSec,
                fastTrackingActive = fastTrackingDeviceId == fullscreenDevice.device.id,
                fastHistoryActive =
                    fastTrackingDeviceId == fullscreenDevice.device.id && fastHistory,
                onFastTrackingChange = {
                    if (it) {
                        startFastTracking(fullscreenDevice.device.id)
                    } else {
                        stopFastTracking()
                    }
                },
                onFastHistoryChange = {
                    fastHistory = it
                    renewFastTracking()
                },
                onGeofenceChange = { setGeofence(fullscreenDevice, it) },
                onOpenHistory = {
                    closeFullscreenMap()
                    historyDeviceId = fullscreenDevice.device.id
                },
                onExit = ::closeFullscreenMap,
            )
            return
        }
        if (fullscreenDeviceId != null) {
            LaunchedEffect(fullscreenDeviceId) { closeFullscreenMap() }
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
                            Modifier.padding(16.dp)
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
        BackHandler(enabled = showSettings || historyDeviceId != null || selected != null) {
            when {
                showSettings -> showSettings = false
                historyDeviceId != null -> historyDeviceId = null
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
                        onBack = { showSettings = false },
                        onChange = ::saveTrackingSettings,
                        modifier = Modifier.weight(1f),
                    )
                    historyDeviceId != null -> Text("Dispositivo non più disponibile.")
                    selected == null -> ReceiverHomeScreen(
                        profile = receiverProfile,
                        receiverId = identity.id,
                        devices = devices,
                        heartbeatIntervalSec = trackingSettings.heartbeatIntervalSec,
                        onDeviceClick = ::openDetail,
                        onAliasSave = ::updateAlias,
                        onSettingsClick = { showSettings = true },
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
                            }
                            selectedTab = tab
                        },
                        onBack = ::closeDetail,
                        onAliasSave = { updateAlias(selected.device.id, it) },
                        onCommand = { command(selected, it) },
                        fastTrackingActive = fastTrackingDeviceId == selected.device.id,
                        fastHistoryActive =
                            fastTrackingDeviceId == selected.device.id && fastHistory,
                        onFastTrackingChange = {
                            if (it) startFastTracking(selected.device.id) else stopFastTracking()
                        },
                        onFastHistoryChange = {
                            fastHistory = it
                            renewFastTracking()
                        },
                        onGeofenceChange = {
                            setGeofence(selected, it)
                        },
                        onFullscreen = {
                            openFullscreenMap(selected.device.id)
                        },
                        onOpenHistory = { historyDeviceId = selected.device.id },
                        onTakePhoto = { takeVideoSnapshot(selected) },
                        videoTrackAvailable = videoTrack != null,
                        audioTrackAvailable = audioTrack != null,
                        videoRecordingState = videoRecordingState,
                        audioRecordingState = audioRecordingState,
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
                        snapshotPreview = snapshotPreview,
                        onSnapshotAnimationFinished = ::clearSnapshotAnimation,
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
            }
            reconcileMediaConnection()
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
        selectedDeviceId = deviceId
        selectedTab = DeviceTab.POSITION
        message = ""
    }

    private fun closeDetail() {
        clearSnapshotAnimation()
        selectedDeviceId?.let(::stopAllStreams)
        stopFastTracking()
        selectedDeviceId = null
        selectedTab = DeviceTab.POSITION
        message = ""
        disconnectMedia()
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

    private fun takeVideoSnapshot(device: MonitoredDevice) {
        if (snapshotPreview != null) return
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1202)
            message = "Concedi l’accesso alle foto e premi nuovamente il pulsante."
            return
        }
        val videoRenderer = renderer
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
        snapshotPreview = Bitmap.createScaledBitmap(
            bitmap,
            previewWidth,
            previewHeight,
            true,
        )
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
        val track = videoTrack
        if (track == null) {
            message = "La track video non è ancora disponibile."
            return
        }
        videoRecordingState = LocalRecordingState.Starting
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
        showRecordingResult("Video", result)
    }

    private fun startAudioRecording(device: MonitoredDevice) {
        if (audioRecordingState.isActive || !ensureLegacyStoragePermission()) return
        val track = audioTrack
        if (track == null) {
            message = "La track audio non è ancora disponibile."
            return
        }
        audioRecordingState = LocalRecordingState.Starting
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
        showRecordingResult("Audio", result)
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

    private fun closeFullscreenMap() {
        if (fullscreenDeviceId == null) return
        fullscreenDeviceId = null
        if (!historyFullscreenActive) exitImmersiveLandscape()
    }

    private fun setHistoryFullscreen(enabled: Boolean) {
        if (historyFullscreenActive == enabled) return
        historyFullscreenActive = enabled
        if (enabled) enterImmersiveLandscape() else exitImmersiveLandscape()
    }

    private fun enterImmersiveLandscape() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun startFastTracking(deviceId: String) {
        fastTrackingDeviceId = deviceId
        fastHistory = false
        trackingLeaseJob?.cancel()
        trackingLeaseJob = lifecycleScope.launch {
            while (isActive && fastTrackingDeviceId == deviceId) {
                renewFastTracking()
                delay(TRACKING_LEASE_RENEW_INTERVAL_MS)
            }
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

    private fun stopFastTracking() {
        val deviceId = fastTrackingDeviceId
        trackingLeaseJob?.cancel()
        trackingLeaseJob = null
        fastTrackingDeviceId = null
        fastHistory = false
        if (deviceId != null) {
            lifecycleScope.launch {
                runCatching {
                    repository!!.setLiveTracking(
                        receiverId = identity.id,
                        transmitterId = deviceId,
                        until = null,
                        liveHistory = false,
                    )
                }
            }
        }
    }

    private fun stopAllStreams(deviceId: String) {
        lifecycleScope.launch {
            runCatching {
                repository!!.sendCommand(deviceId, CommandType.STOP_VIDEO)
                repository!!.sendCommand(deviceId, CommandType.STOP_AUDIO)
            }.onFailure {
                requestDataPlaneRecovery(it, "Chiusura stream non riuscita.")
            }
        }
    }

    private fun command(device: MonitoredDevice, type: CommandType) {
        when (type) {
            CommandType.STOP_VIDEO -> stopVideoRecording()
            CommandType.STOP_AUDIO -> stopAudioRecording()
            else -> Unit
        }
        lifecycleScope.launch {
            runCatching { repository!!.sendCommand(device.device.id, type) }
                .onFailure { requestDataPlaneRecovery(it, "Invio comando non riuscito.") }
        }
    }

    private fun reconcileMediaConnection() {
        if (!isForeground) {
            disconnectMedia()
            return
        }
        val selected = selectedDeviceId?.let { id ->
            devices.firstOrNull { it.device.id == id }
        }
        val shouldConnect = selected?.status?.let {
            MediaConnectionPolicy.shouldConnect(it.cameraStreaming, it.microphoneStreaming)
        } == true
        when {
            shouldConnect && roomDeviceId != selected?.device?.id ->
                selected?.device?.id?.let(::connectTo)
            shouldConnect && room == null && connectionJob?.isActive != true ->
                selected?.device?.id?.let(::connectTo)
            !shouldConnect -> disconnectMedia()
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
                room = LiveKit.create(applicationContext).also { newRoom ->
                    roomDeviceId = deviceId
                    renderer?.let(newRoom::initVideoRenderer)
                    roomEventsJob = lifecycleScope.launch {
                        newRoom.events.collect { event ->
                            Log.d(TAG, "LiveKit room event: ${event::class.simpleName}")
                            when {
                                event is RoomEvent.TrackSubscribed && event.track is VideoTrack -> {
                                    bindVideoTrack(event.track as VideoTrack)
                                }
                                event is RoomEvent.TrackSubscribed && event.track is AudioTrack -> {
                                    bindAudioTrack(event.track as AudioTrack)
                                }
                                event is RoomEvent.TrackUnsubscribed &&
                                    event.track is VideoTrack -> {
                                    stopVideoRecording()
                                    if (videoTrack === event.track) videoTrack = null
                                }
                                event is RoomEvent.TrackUnsubscribed &&
                                    event.track is AudioTrack -> {
                                    stopAudioRecording()
                                    if (audioTrack === event.track) audioTrack = null
                                }
                                event is RoomEvent.TrackMuted &&
                                    event.publication.kind == Track.Kind.VIDEO -> {
                                    stopVideoRecording()
                                    renderer?.clearImage()
                                }
                                event is RoomEvent.TrackUnmuted &&
                                    event.publication.kind == Track.Kind.VIDEO -> {
                                    (event.publication.track as? VideoTrack)?.let(::bindVideoTrack)
                                }
                                event is RoomEvent.TrackMuted &&
                                    event.publication.kind == Track.Kind.AUDIO -> {
                                    stopAudioRecording()
                                    audioLevel = 0f
                                }
                                event is RoomEvent.ActiveSpeakersChanged -> {
                                    audioLevel = event.speakers.maxOfOrNull { it.audioLevel } ?: 0f
                                }
                                event is RoomEvent.Disconnected -> {
                                    stopAllRecordings()
                                    audioLevel = 0f
                                    renderer?.clearImage()
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

    private fun bindVideoTrack(track: VideoTrack) {
        if (videoTrack !== track && videoRecordingState.isActive) {
            stopVideoRecording()
        }
        renderer?.let { view ->
            videoTrack?.removeRenderer(view)
            track.addRenderer(view)
        }
        videoTrack = track
    }

    private fun bindAudioTrack(track: AudioTrack) {
        if (audioTrack !== track && audioRecordingState.isActive) {
            stopAudioRecording()
        }
        audioTrack = track
    }

    private fun disconnectMedia(cancelConnection: Boolean = true) {
        stopAllRecordings()
        if (cancelConnection) connectionJob?.cancel()
        renderer?.let { view -> videoTrack?.removeRenderer(view) }
        roomEventsJob?.cancel()
        roomEventsJob = null
        audioMeterJob?.cancel()
        audioMeterJob = null
        room?.disconnect()
        room = null
        roomDeviceId = null
        videoTrack = null
        audioTrack = null
        audioLevel = 0f
        renderer?.clearImage()
    }

    private fun requestDataPlaneRecovery(error: Throwable, fallbackMessage: String) {
        Log.e(TAG, fallbackMessage, error)
        message = fallbackMessage
        recoverySignals.trySend(error)
    }

    private companion object {
        const val TAG = "FindMeReceiver"
        const val AUDIO_METER_INTERVAL_MS = 100L
        const val MEDIA_RECONNECT_DELAY_MS = 2_000L
        const val TRACKING_LEASE_DURATION_SEC = 90L
        const val TRACKING_LEASE_RENEW_INTERVAL_MS = 20_000L
        const val RECEIVER_CHANNEL_RENEWAL_INTERVAL_MS = 15 * 60 * 1_000L
        const val ONLINE_CLOCK_INTERVAL_MS = 10_000L
    }

    private class DataPlaneRestart(reason: String) : RuntimeException(reason)
}
