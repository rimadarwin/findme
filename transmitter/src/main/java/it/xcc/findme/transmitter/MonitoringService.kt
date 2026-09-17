package it.xcc.findme.transmitter

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.IBinder
import android.os.Looper
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
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import it.xcc.findme.core.CommandType
import it.xcc.findme.core.DeviceIdentity
import it.xcc.findme.core.DeviceLocation
import it.xcc.findme.core.DeviceRole
import it.xcc.findme.core.DeviceStatus
import it.xcc.findme.core.EffectiveTrackingConfig
import it.xcc.findme.core.FindMeRepository
import it.xcc.findme.core.MediaConnectionPolicy
import it.xcc.findme.core.TrackingConfigResolver
import it.xcc.findme.core.TrackingRuntimeState
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var identity: DeviceIdentity
    private lateinit var repository: FindMeRepository
    private lateinit var locationClient: FusedLocationProviderClient
    private var room: Room? = null
    private var controlPlaneJob: Job? = null
    private var mediaEventsJob: Job? = null
    private val appliedCommands = mutableSetOf<Long>()
    private val mediaMutex = Mutex()
    private val historyMutex = Mutex()
    private var desiredCameraStreaming = false
    private var desiredMicrophoneStreaming = false
    private var cameraStreaming = false
    private var microphoneStreaming = false
    private var cameraFacing = CameraPosition.FRONT
    private var trackingState: TrackingRuntimeState? = null
    private var effectiveConfig: EffectiveTrackingConfig =
        TrackingConfigResolver.resolve(TrackingConfigResolver.defaults, null)
    private var activeLocationIntervalSec: Int? = null
    private var lastHistoryPoint: DeviceLocation? = null
    private var lastHistorySavedAtMillis: Long? = null

    override fun onCreate() {
        super.onCreate()
        identity = DeviceIdentity(this)
        repository = FindMeRepository()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasRequiredPermissions()) {
            stopSelf()
            return START_NOT_STICKY
        }
        identity.monitoringEnabled = true
        startAsForeground()
        trackingState = identity.cachedTrackingState()
        applyEffectiveTrackingConfig()
        if (controlPlaneJob?.isActive != true) {
            controlPlaneJob = scope.launch { maintainControlPlane() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        locationClient.removeLocationUpdates(locationCallback)
        scope.cancel()
        room?.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun maintainControlPlane() {
        while (scope.isActive && identity.monitoringEnabled) {
            runCatching {
                repository.ensureAuthenticated()
                repository.registerDevice(identity.id, identity.name, DeviceRole.TRANSMITTER)
                publishStatus()
                coroutineScope {
                    launch { observeTrackingState() }
                    launch { evaluateTrackingState() }
                    launch { sendHeartbeats() }
                    launch { maintainMediaOnDemand() }
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
            }.onFailure { Log.e(TAG, "Control plane connection failed", it) }
            delay(RECONNECT_DELAY_MS)
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
                    runCatching { publishStatus() }
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
        mediaEventsJob?.cancel()
        mediaEventsJob = null
        room?.disconnect()
        if (room != null) Log.i(TAG, "LiveKit publisher disconnected: no active streams")
        room = null
        cameraStreaming = false
        microphoneStreaming = false
    }

    private suspend fun sendHeartbeats() {
        while (scope.isActive && identity.monitoringEnabled) {
            runCatching { publishStatus() }
                .onFailure { Log.e(TAG, "Heartbeat failed", it) }
            delay(effectiveConfig.heartbeatIntervalSec * 1_000L)
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
                cameraFacing = when (cameraFacing) {
                    CameraPosition.BACK -> "back"
                    else -> "front"
                },
                lastHeartbeat = Instant.now().toString(),
            ),
        )
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
                    repository.updateCurrentLocation(point)
                }.onFailure { Log.e(TAG, "Location upload failed", it) }
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
    private fun startAsForeground() {
        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Monitoraggio FindMe attivo")
            .setContentText("Posizione, camera e microfono sono disponibili da remoto")
            .setContentIntent(activityIntent)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
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
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val TRACKING_EVALUATION_INTERVAL_MS = 1_000L
        private const val MEDIA_WATCHDOG_INTERVAL_MS = 5_000L

        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        fun intent(context: Context) = Intent(context, MonitoringService::class.java)
    }
}
