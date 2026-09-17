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
import it.xcc.findme.core.FindMeRepository
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var identity: DeviceIdentity
    private lateinit var repository: FindMeRepository
    private lateinit var locationClient: FusedLocationProviderClient
    private var room: Room? = null
    private var connectionJob: Job? = null
    private val appliedCommands = mutableSetOf<Long>()
    private var cameraStreaming = false
    private var microphoneStreaming = false
    private var cameraFacing = CameraPosition.FRONT

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
        startLocationUpdates()
        if (connectionJob?.isActive != true) {
            connectionJob = scope.launch { maintainConnection() }
            scope.launch { sendHeartbeats() }
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

    private suspend fun maintainConnection() {
        while (scope.isActive && identity.monitoringEnabled) {
            runCatching {
                repository.ensureAuthenticated()
                repository.registerDevice(identity.id, identity.name, DeviceRole.TRANSMITTER)
                val credentials = repository.liveKitToken(identity.id, "publish")
                room = LiveKit.create(applicationContext).also {
                    it.connect(credentials.url, credentials.token)
                }
                cameraStreaming = false
                microphoneStreaming = false
                cameraFacing = CameraPosition.FRONT
                publishStatus()
                coroutineScope {
                    launch {
                        repository.commands(identity.id).collect { commands ->
                            commands
                                .filter { it.status == "pending" && it.id !in appliedCommands }
                                .forEach { command ->
                                    applyCommand(command.command)
                                    publishStatus()
                                    command.id?.let {
                                        appliedCommands += it
                                        repository.acknowledgeCommand(it)
                                    }
                                }
                        }
                    }
                    room!!.events.collect { event ->
                        if (event is RoomEvent.Disconnected) {
                            error("LiveKit room disconnected")
                        }
                    }
                }
            }.onFailure { Log.e(TAG, "Connection loop failed", it) }
            delay(RECONNECT_DELAY_MS)
        }
    }

    private suspend fun applyCommand(command: CommandType) {
        Log.i(TAG, "Applying command: $command")
        when (command) {
            CommandType.START_AUDIO -> {
                room?.localParticipant?.setMicrophoneEnabled(true)
                microphoneStreaming = true
            }
            CommandType.STOP_AUDIO -> {
                room?.localParticipant?.setMicrophoneEnabled(false)
                microphoneStreaming = false
            }
            CommandType.START_VIDEO -> {
                room?.localParticipant?.setCameraEnabled(true)
                cameraStreaming = true
            }
            CommandType.STOP_VIDEO -> {
                room?.localParticipant?.setCameraEnabled(false)
                cameraStreaming = false
            }
            CommandType.SWITCH_CAMERA -> {
                val track = room
                    ?.localParticipant
                    ?.getTrackPublication(Track.Source.CAMERA)
                    ?.track as? LocalVideoTrack
                    ?: error("Camera track is not active")
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

    private suspend fun sendHeartbeats() {
        while (scope.isActive && identity.monitoringEnabled) {
            runCatching { publishStatus() }
                .onFailure { Log.e(TAG, "Heartbeat failed", it) }
            delay(HEARTBEAT_INTERVAL_MS)
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
                runCatching {
                    repository.updateLocation(
                        DeviceLocation(
                            deviceId = identity.id,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy,
                            recordedAt = Instant.ofEpochMilli(location.time).toString(),
                        ),
                    )
                }.onFailure { Log.e(TAG, "Location upload failed", it) }
            }
        }
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
            .build()
        locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
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
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val LOCATION_INTERVAL_MS = 10_000L
        private const val LOCATION_FASTEST_INTERVAL_MS = 5_000L
        private const val RECONNECT_DELAY_MS = 5_000L

        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        fun intent(context: Context) = Intent(context, MonitoringService::class.java)
    }
}
