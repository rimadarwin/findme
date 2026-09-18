package it.xcc.findme.transmitter

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import it.xcc.findme.core.CommandType
import it.xcc.findme.core.ConnectionRecoveryPolicy
import it.xcc.findme.core.DeviceIdentity
import it.xcc.findme.core.DeviceLocation
import it.xcc.findme.core.DeviceRole
import it.xcc.findme.core.DeviceStatus
import it.xcc.findme.core.EffectiveTrackingConfig
import it.xcc.findme.core.FindMeRepository
import it.xcc.findme.core.MediaConnectionPolicy
import it.xcc.findme.core.TrackingConfigResolver
import it.xcc.findme.core.TrackingRuntimeState
import it.xcc.findme.transmitter.screen.ProjectionVideoCapturer
import it.xcc.findme.transmitter.screen.ScreenProjectionController
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class MonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var identity: DeviceIdentity
    private lateinit var repository: FindMeRepository
    private lateinit var locationClient: FusedLocationProviderClient
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var monitoringWakeLock: PowerManager.WakeLock
    private var room: Room? = null
    private var controlPlaneJob: Job? = null
    private var mediaEventsJob: Job? = null
    private val appliedCommands = mutableSetOf<Long>()
    private val mediaMutex = Mutex()
    private val historyMutex = Mutex()
    private var desiredCameraStreaming = false
    private var desiredMicrophoneStreaming = false
    private var desiredScreenStreaming = false
    private var cameraStreaming = false
    private var microphoneStreaming = false
    private var screenStreaming = false
    private var screenTrack: LocalVideoTrack? = null
    private var mediaRecoveryJob: Job? = null
    private lateinit var screenProjectionController: ScreenProjectionController
    private var cameraFacing = CameraPosition.FRONT
    private var trackingState: TrackingRuntimeState? = null
    private var effectiveConfig: EffectiveTrackingConfig =
        TrackingConfigResolver.resolve(TrackingConfigResolver.defaults, null)
    private var activeLocationIntervalSec: Int? = null
    private var lastHistoryPoint: DeviceLocation? = null
    private var lastHistorySavedAtMillis: Long? = null
    private val recoverySignals = Channel<Throwable>(Channel.CONFLATED)
    @Volatile
    private var networkWasLost = false
    private var lastSuccessfulHeartbeatElapsedMs = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            networkWasLost = true
            Log.w(TAG, "Network unavailable; automatic recovery armed")
        }

        override fun onAvailable(network: Network) {
            if (networkWasLost) {
                networkWasLost = false
                Log.i(TAG, "Network available again; restarting control plane")
                recoverySignals.trySend(ControlPlaneRestart("network restored"))
                requestMediaRecovery()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        identity = DeviceIdentity(this)
        repository = FindMeRepository()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        monitoringWakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG,
        ).apply {
            setReferenceCounted(false)
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        screenProjectionController = ScreenProjectionController(this, ::onScreenProjectionStopped)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasRequiredPermissions()) {
            stopSelf()
            return START_NOT_STICKY
        }
        identity.monitoringEnabled = true
        val authorizingScreen = intent?.action == ACTION_AUTHORIZE_SCREEN
        startAsForeground(includeMediaProjection = authorizingScreen)
        acquireMonitoringWakeLock()
        if (authorizingScreen) {
            activateScreenProjection(intent)
        }
        trackingState = identity.cachedTrackingState()
        applyEffectiveTrackingConfig()
        if (controlPlaneJob?.isActive != true) {
            controlPlaneJob = scope.launch { maintainControlPlane() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        locationClient.removeLocationUpdates(locationCallback)
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        if (::monitoringWakeLock.isInitialized && monitoringWakeLock.isHeld) {
            monitoringWakeLock.release()
            Log.i(TAG, "Monitoring wake lock released")
        }
        scope.cancel()
        room?.disconnect()
        screenProjectionController.stop()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screenProjectionController.resize()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    private fun acquireMonitoringWakeLock() {
        if (monitoringWakeLock.isHeld) return
        // The foreground service must keep heartbeat and reconnect timers running in deep sleep.
        monitoringWakeLock.acquire()
        Log.i(TAG, "Monitoring wake lock acquired")
    }

    private suspend fun maintainControlPlane() {
        var consecutiveFailures = 0
        while (scope.isActive && identity.monitoringEnabled) {
            val activeRepository = repository
            var retryImmediately = false
            try {
                activeRepository.ensureAuthenticated(forceRefresh = true)
                withTimeout(ConnectionRecoveryPolicy.REQUEST_TIMEOUT_MS) {
                    activeRepository.registerDevice(
                        identity.id,
                        identity.name,
                        DeviceRole.TRANSMITTER,
                    )
                    publishStatus()
                }
                lastSuccessfulHeartbeatElapsedMs = SystemClock.elapsedRealtime()
                Log.i(TAG, "Control plane healthy; initial heartbeat published")
                consecutiveFailures = 0
                coroutineScope {
                    launch { observeTrackingState() }
                    launch { evaluateTrackingState() }
                    launch { sendHeartbeats() }
                    launch { maintainSessionAndChannelHealth() }
                    launch { maintainMediaOnDemand() }
                    launch { throw recoverySignals.receive() }
                    launch {
                        repository.commands(identity.id).collect { commands ->
                            commands.filter { it.id !in appliedCommands }.forEach { command ->
                                applyCommand(command.command)
                                publishStatus()
                                command.id?.let { commandId ->
                                    repository.acknowledgeCommand(commandId)
                                    appliedCommands += commandId
                                }
                        }
                        }
                    }
                    awaitCancellation()
                }
            } catch (error: CancellationException) {
                if (!scope.isActive) throw error
                consecutiveFailures++
                Log.e(TAG, "Control plane coroutine cancelled unexpectedly", error)
            } catch (restart: ControlPlaneRestart) {
                retryImmediately = true
                Log.i(TAG, "Control plane restart requested: ${restart.message}")
            } catch (error: Throwable) {
                consecutiveFailures++
                Log.e(TAG, "Control plane connection failed", error)
            } finally {
                runCatching { activeRepository.shutdownRealtime() }
            }
            if (!scope.isActive || !identity.monitoringEnabled) break
            if (repository === activeRepository) repository = FindMeRepository()
            if (!retryImmediately) {
                val retryDelay = ConnectionRecoveryPolicy.retryDelayMs(consecutiveFailures)
                Log.i(TAG, "Retrying control plane in ${retryDelay / 1_000}s")
                withTimeoutOrNull(retryDelay) { recoverySignals.receive() }
            }
        }
    }

    private suspend fun observeTrackingState() {
        repository.transmitterTrackingState(identity.id).collect { state ->
            if (state != null) {
                trackingState = state
                identity.cacheTrackingState(state)
                applyEffectiveTrackingConfig()
            }
        }
    }

    private suspend fun evaluateTrackingState() {
        while (scope.isActive && identity.monitoringEnabled) {
            applyEffectiveTrackingConfig()
            delay(TRACKING_EVALUATION_INTERVAL_MS)
        }
    }

    private fun applyEffectiveTrackingConfig() {
        val state = trackingState
        val updated = if (state == null) {
            TrackingConfigResolver.resolve(TrackingConfigResolver.defaults, null)
        } else {
            TrackingConfigResolver.resolve(state.settings, state.relationship)
        }
        if (updated != effectiveConfig) {
            Log.i(
                TAG,
                "Tracking config: location=${updated.locationIntervalSec}s, " +
                    "history=${updated.historyIntervalSec}s, " +
                    "heartbeat=${updated.heartbeatIntervalSec}s, " +
                    "live=${updated.liveTracking}, liveHistory=${updated.liveHistory}",
            )
        }
        effectiveConfig = updated
        if (activeLocationIntervalSec != updated.locationIntervalSec) {
            startLocationUpdates(updated)
        }
    }

    private suspend fun applyCommand(command: CommandType) {
        Log.i(TAG, "Applying command: $command")
        when (command) {
            CommandType.START_AUDIO -> {
                desiredMicrophoneStreaming = true
                syncMediaState()
            }
            CommandType.STOP_AUDIO -> {
                desiredMicrophoneStreaming = false
                syncMediaState()
            }
            CommandType.START_VIDEO -> {
                desiredCameraStreaming = true
                syncMediaState()
            }
            CommandType.STOP_VIDEO -> {
                desiredCameraStreaming = false
                syncMediaState()
            }
            CommandType.SWITCH_CAMERA -> {
                val track = room
                    ?.localParticipant
                    ?.getTrackPublication(Track.Source.CAMERA)
                    ?.track as? LocalVideoTrack
                    ?: return
                cameraFacing = when (cameraFacing) {
                    CameraPosition.FRONT -> CameraPosition.BACK
                    CameraPosition.BACK -> CameraPosition.FRONT
                }
                track.switchCamera(position = cameraFacing)
            }
            CommandType.START_SCREEN -> {
                if (screenProjectionController.isReady) {
                    desiredScreenStreaming = true
                    syncMediaState()
                } else {
                    desiredScreenStreaming = false
                    screenStreaming = false
                    Log.w(TAG, "Screen stream requested without active MediaProjection")
                    startAsForeground(includeMediaProjection = false)
                }
            }
            CommandType.STOP_SCREEN -> {
                desiredScreenStreaming = false
                syncMediaState()
            }
            CommandType.START_MONITORING -> identity.monitoringEnabled = true
            CommandType.STOP_MONITORING -> {
                identity.monitoringEnabled = false
                stopSelf()
            }
        }
    }

    private suspend fun maintainMediaOnDemand() {
        while (scope.isActive && identity.monitoringEnabled) {
            runCatching { syncMediaState() }
                .onFailure { Log.e(TAG, "Media synchronization failed", it) }
            delay(MEDIA_WATCHDOG_INTERVAL_MS)
        }
    }

    private suspend fun syncMediaState() = mediaMutex.withLock {
        if (!MediaConnectionPolicy.shouldConnect(
                desiredCameraStreaming,
                desiredMicrophoneStreaming,
                desiredScreenStreaming,
            )
        ) {
            disconnectMediaRoom()
            return@withLock
        }

        val activeRoom = room ?: connectMediaRoom()
        if (cameraStreaming != desiredCameraStreaming) {
            activeRoom.localParticipant.setCameraEnabled(desiredCameraStreaming)
            cameraStreaming = desiredCameraStreaming
        }
        if (microphoneStreaming != desiredMicrophoneStreaming) {
            activeRoom.localParticipant.setMicrophoneEnabled(desiredMicrophoneStreaming)
            microphoneStreaming = desiredMicrophoneStreaming
        }
        if (screenStreaming != desiredScreenStreaming) {
            if (desiredScreenStreaming) {
                publishScreenTrack(activeRoom)
            } else {
                screenTrack?.let(activeRoom.localParticipant::unpublishTrack)
                screenTrack = null
                screenStreaming = false
            }
        }
    }

    private suspend fun publishScreenTrack(activeRoom: Room) {
        check(screenProjectionController.isReady) {
            "Screen capture authorization is not active"
        }
        val captureSize = screenProjectionController.captureSize
        val track = activeRoom.localParticipant.createVideoTrack(
            name = SCREEN_TRACK_NAME,
            capturer = ProjectionVideoCapturer(screenProjectionController),
            options = LocalVideoTrackOptions(
                isScreencast = true,
                captureParams = VideoCaptureParameter(
                    width = captureSize.width,
                    height = captureSize.height,
                    maxFps = SCREEN_FRAME_RATE,
                    adaptOutputToDimensions = false,
                ),
            ),
        )
        try {
            track.startCapture()
            activeRoom.localParticipant.publishVideoTrack(
                track = track,
                options = VideoTrackPublishOptions(
                    base = activeRoom.screenShareTrackPublishDefaults,
                    source = Track.Source.SCREEN_SHARE,
                ),
            )
            screenTrack = track
            screenStreaming = true
            Log.i(TAG, "Screen track published on demand")
        } catch (error: Throwable) {
            runCatching { track.stop() }
            throw error
        }
    }

    private suspend fun connectMediaRoom(): Room {
        val credentials = repository.liveKitToken(identity.id, "publish")
        val newRoom = LiveKit.create(applicationContext)
        mediaEventsJob?.cancel()
        mediaEventsJob = scope.launch {
            newRoom.events.collect { event ->
                if (event is RoomEvent.Disconnected && room === newRoom) {
                    room = null
                    cameraStreaming = false
                    microphoneStreaming = false
                    screenStreaming = false
                    screenTrack = null
                    runCatching { publishStatus() }
                    requestMediaRecovery()
                }
            }
        }
        return try {
            newRoom.connect(credentials.url, credentials.token)
            room = newRoom
            Log.i(TAG, "LiveKit publisher connected on demand")
            newRoom
        } catch (error: Throwable) {
            mediaEventsJob?.cancel()
            mediaEventsJob = null
            newRoom.disconnect()
            throw error
        }
    }

    private fun disconnectMediaRoom() {
        mediaRecoveryJob?.cancel()
        mediaRecoveryJob = null
        mediaEventsJob?.cancel()
        mediaEventsJob = null
        room?.disconnect()
        if (room != null) Log.i(TAG, "LiveKit publisher disconnected: no active streams")
        room = null
        cameraStreaming = false
        microphoneStreaming = false
        screenStreaming = false
        screenTrack = null
    }

    private fun requestMediaRecovery() {
        if (!MediaConnectionPolicy.shouldConnect(
                desiredCameraStreaming,
                desiredMicrophoneStreaming,
                desiredScreenStreaming,
            ) ||
            mediaRecoveryJob?.isActive == true
        ) {
            return
        }
        mediaRecoveryJob = scope.launch {
            var consecutiveFailures = 0
            while (isActive &&
                MediaConnectionPolicy.shouldConnect(
                    desiredCameraStreaming,
                    desiredMicrophoneStreaming,
                    desiredScreenStreaming,
                ) &&
                room == null
            ) {
                if (consecutiveFailures > 0) {
                    delay(ConnectionRecoveryPolicy.retryDelayMs(consecutiveFailures))
                }
                runCatching {
                    syncMediaState()
                    publishStatus()
                }.onSuccess {
                    if (room != null) {
                        Log.i(TAG, "LiveKit publisher recovered automatically")
                        return@launch
                    }
                }.onFailure {
                    consecutiveFailures++
                    Log.e(TAG, "LiveKit publisher recovery failed", it)
                }
            }
        }
    }

    private suspend fun sendHeartbeats() {
        while (scope.isActive && identity.monitoringEnabled) {
            delay(effectiveConfig.heartbeatIntervalSec * 1_000L)
            withTimeout(ConnectionRecoveryPolicy.REQUEST_TIMEOUT_MS) {
                publishStatus()
            }
            lastSuccessfulHeartbeatElapsedMs = SystemClock.elapsedRealtime()
            Log.d(TAG, "Heartbeat published successfully")
        }
    }

    private suspend fun maintainSessionAndChannelHealth() {
        while (scope.isActive && identity.monitoringEnabled) {
            delay(HEALTH_CHECK_INTERVAL_MS)
            val now = SystemClock.elapsedRealtime()
            if (ConnectionRecoveryPolicy.isHeartbeatStale(
                    lastSuccessElapsedMs = lastSuccessfulHeartbeatElapsedMs,
                    nowElapsedMs = now,
                    heartbeatIntervalSec = effectiveConfig.heartbeatIntervalSec,
                )
            ) {
                throw IllegalStateException("Heartbeat watchdog timeout")
            }
            repository.ensureAuthenticated(forceRefresh = true)
            throw ControlPlaneRestart("periodic authenticated channel renewal")
        }
    }

    private suspend fun publishStatus() {
        repository.heartbeat(
            DeviceStatus(
                deviceId = identity.id,
                isMonitoring = identity.monitoringEnabled,
                batteryPercent = batteryPercent(),
                cameraAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
                microphoneAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
                cameraStreaming = cameraStreaming,
                microphoneStreaming = microphoneStreaming,
                screenShareReady = screenProjectionController.isReady,
                screenStreaming = screenStreaming,
                cameraFacing = when (cameraFacing) {
                    CameraPosition.BACK -> "back"
                    else -> "front"
                },
                lastHeartbeat = Instant.now().toString(),
            ),
        )
    }

    private fun activateScreenProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = projectionResultData(intent)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.w(TAG, "Screen capture authorization data is missing")
            return
        }
        runCatching {
            screenProjectionController.start(resultCode, resultData)
            identity.screenProjectionEverAuthorized = true
            startAsForeground(includeMediaProjection = true)
        }.onSuccess {
            scope.launch { runCatching { publishStatus() } }
        }.onFailure {
            Log.e(TAG, "Screen capture activation failed", it)
            startAsForeground(includeMediaProjection = false)
        }
    }

    private fun onScreenProjectionStopped() {
        desiredScreenStreaming = false
        screenStreaming = false
        screenTrack = null
        startAsForeground(includeMediaProjection = false)
        scope.launch {
            runCatching { syncMediaState() }
            runCatching { publishStatus() }
        }
    }

    @Suppress("DEPRECATION")
    private fun projectionResultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_PROJECTION_RESULT_DATA)
        }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            scope.launch {
                val point = DeviceLocation(
                    deviceId = identity.id,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    recordedAt = Instant.ofEpochMilli(location.time).toString(),
                )
                runCatching {
                    withTimeout(ConnectionRecoveryPolicy.REQUEST_TIMEOUT_MS) {
                        repository.updateCurrentLocation(point)
                    }
                }.onFailure {
                    Log.e(TAG, "Location upload failed", it)
                    recoverySignals.trySend(it)
                }
                if (trackingState?.relationship?.geofenceEnabled == true) {
                    runCatching {
                        repository.checkGeofence(point)
                    }.onFailure {
                        Log.e(TAG, "Geofence evaluation failed", it)
                    }
                }
                persistHistoryIfNeeded(point)
            }
        }
    }

    private suspend fun persistHistoryIfNeeded(point: DeviceLocation) = historyMutex.withLock {
        val now = System.currentTimeMillis()
        if (!TrackingConfigResolver.shouldPersistHistory(
                previous = lastHistoryPoint,
                current = point,
                lastSavedAtMillis = lastHistorySavedAtMillis,
                nowMillis = now,
                config = effectiveConfig,
            )
        ) {
            return@withLock
        }
        runCatching {
            repository.appendLocationHistory(point)
        }.onSuccess {
            lastHistoryPoint = point
            lastHistorySavedAtMillis = now
            Log.d(TAG, "History point saved at ${point.recordedAt}")
        }.onFailure {
            Log.e(TAG, "Location history upload failed", it)
        }
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates(config: EffectiveTrackingConfig) {
        locationClient.removeLocationUpdates(locationCallback)
        val intervalMillis = config.locationIntervalSec * 1_000L
        val priority = if (config.liveTracking) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = LocationRequest.Builder(priority, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis / 2)
            .build()
        locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        activeLocationIntervalSec = config.locationIntervalSec
    }

    private fun hasRequiredPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("InlinedApi")
    private fun startAsForeground(includeMediaProjection: Boolean = screenProjectionController.isReady) {
        val projectionNeedsAttention =
            identity.screenProjectionEverAuthorized && !screenProjectionController.isReady
        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                if (projectionNeedsAttention) {
                    putExtra(MainActivity.EXTRA_REQUEST_SCREEN_PROJECTION, true)
                }
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Monitoraggio FindMe attivo")
            .setContentText(
                if (projectionNeedsAttention) {
                    "Tocca per riattivare il mirroring dello schermo"
                } else {
                    "Posizione, camera, microfono e schermo disponibili da remoto"
                },
            )
            .setContentIntent(activityIntent)
            .setOngoing(true)
            .build()
        var foregroundTypes =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (includeMediaProjection) {
            foregroundTypes = foregroundTypes or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundTypes,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Monitoraggio FindMe",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun batteryPercent(): Int? {
        val battery = getSystemService(BatteryManager::class.java)
        return battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it >= 0 }
    }

    companion object {
        private const val CHANNEL_ID = "findme_monitoring"
        private const val TAG = "FindMeMonitoring"
        private const val NOTIFICATION_ID = 1101
        private const val WAKE_LOCK_TAG = "FindMe:Monitoring"
        private const val TRACKING_EVALUATION_INTERVAL_MS = 1_000L
        private const val MEDIA_WATCHDOG_INTERVAL_MS = 5_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 15 * 60 * 1_000L
        private const val SCREEN_TRACK_NAME = "findme-screen"
        private const val SCREEN_FRAME_RATE = 15
        private const val ACTION_AUTHORIZE_SCREEN =
            "it.xcc.findme.transmitter.action.AUTHORIZE_SCREEN"
        private const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        private const val EXTRA_PROJECTION_RESULT_DATA = "projection_result_data"

        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        fun intent(context: Context) = Intent(context, MonitoringService::class.java)

        fun screenAuthorizationIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
        ) = Intent(context, MonitoringService::class.java).apply {
            action = ACTION_AUTHORIZE_SCREEN
            putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
            putExtra(EXTRA_PROJECTION_RESULT_DATA, resultData)
        }
    }

    private class ControlPlaneRestart(reason: String) : RuntimeException(reason)
}
