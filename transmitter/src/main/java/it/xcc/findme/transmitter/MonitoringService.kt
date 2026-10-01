/**
 * @author Infinity
 * @description Servizio foreground per tracking, comandi remoti e streaming del trasmettitore.
 * @modified 29.09.2026 - MDS | Aggiunta coda non bloccante dei messaggi overlay.
 * @modified 29.09.2026 - MDS | Mantenuti tracking rapido e retry notifica dopo l'uscita area.
 * @modified 23.09.2026 - MDS | Aggiunto recovery automatico delle richieste posizione bloccate.
 */
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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
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
import it.xcc.findme.core.CommandRecoveryPolicy
import it.xcc.findme.core.ConnectionRecoveryPolicy
import it.xcc.findme.core.DeviceIdentity
import it.xcc.findme.core.DeviceCommand
import it.xcc.findme.core.DeviceLocation
import it.xcc.findme.core.DeviceRole
import it.xcc.findme.core.DeviceStatus
import it.xcc.findme.core.EffectiveTrackingConfig
import it.xcc.findme.core.FindMeRepository
import it.xcc.findme.core.LocationRecoveryPolicy
import it.xcc.findme.core.MediaConnectionPolicy
import it.xcc.findme.core.TrackingConfigResolver
import it.xcc.findme.core.TrackingRuntimeState
import it.xcc.findme.core.TextMessageStatus
import it.xcc.findme.core.TextMessagePolicy
import it.xcc.findme.core.VoiceMessagePolicy
import it.xcc.findme.core.VoiceMessageStatus
import it.xcc.findme.transmitter.screen.ProjectionVideoCapturer
import it.xcc.findme.transmitter.screen.ScreenProjectionController
import java.time.Instant
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
import kotlinx.coroutines.suspendCancellableCoroutine

class MonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var identity: DeviceIdentity
    private lateinit var repository: FindMeRepository
    private lateinit var locationManager: LocationManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var monitoringWakeLock: PowerManager.WakeLock
    private var room: Room? = null
    private var controlPlaneJob: Job? = null
    private var mediaEventsJob: Job? = null
    private val appliedCommands = mutableSetOf<Long>()
    private val mediaMutex = Mutex()
    private val historyMutex = Mutex()
    private val voicePlaybackMutex = Mutex()
    private var desiredCameraStreaming = false
    private var desiredMicrophoneStreaming = false
    private var desiredScreenStreaming = false
    private var cameraStreaming = false
    private var microphoneStreaming = false
    private var screenStreaming = false
    private var voiceMessagePlaying = false
    private var screenTrack: LocalVideoTrack? = null
    private var mediaRecoveryJob: Job? = null
    private lateinit var screenProjectionController: ScreenProjectionController
    private var cameraFacing = CameraPosition.FRONT
    private var trackingState: TrackingRuntimeState? = null
    private var effectiveConfig: EffectiveTrackingConfig =
        TrackingConfigResolver.resolve(TrackingConfigResolver.defaults, null)
    @Volatile
    private var activeLocationIntervalSec: Int? = null
    @Volatile
    private var locationRegistrationActive = false
    @Volatile
    private var locationRegistrationPending = false
    @Volatile
    private var locationRegistrationStartedElapsedMs = 0L
    @Volatile
    private var lastLocationCallbackElapsedMs = 0L
    @Volatile
    private var nextLocationRegistrationAttemptElapsedMs = 0L
    private val registeredLocationProviders = mutableSetOf<String>()
    private var lastHistoryPoint: DeviceLocation? = null
    private var lastHistorySavedAtMillis: Long? = null
    private val recoverySignals = Channel<Throwable>(Channel.CONFLATED)
    @Volatile
    private var networkWasLost = false
    private var lastSuccessfulHeartbeatElapsedMs = 0L
    private lateinit var textOverlayController: TextOverlayController
    private var activeTextMessageCommandId: Long? = null
    private var activeTextMessageId: String? = null
    private var overlayPermissionRecoveryJob: Job? = null

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
        locationManager = getSystemService(LocationManager::class.java)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        monitoringWakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG,
        ).apply {
            setReferenceCounted(false)
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        screenProjectionController = ScreenProjectionController(this, ::onScreenProjectionStopped)
        textOverlayController = TextOverlayController(this, ::onTextOverlayDismissed)
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
        locationRegistrationActive = false
        locationRegistrationPending = false
        activeLocationIntervalSec = null
        locationManager.removeUpdates(locationListener)
        registeredLocationProviders.clear()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        if (::monitoringWakeLock.isInitialized && monitoringWakeLock.isHeld) {
            monitoringWakeLock.release()
            Log.i(TAG, "Monitoring wake lock released")
        }
        scope.cancel()
        room?.disconnect()
        screenProjectionController.stop()
        textOverlayController.release()
        overlayPermissionRecoveryJob?.cancel()
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
                        repository.commands(identity.id) {
                            effectiveConfig.commandPollIntervalSec
                        }.collect { commands ->
                            val pending = commands.filter { it.id !in appliedCommands }
                            val effectiveIds = CommandRecoveryPolicy.compact(pending)
                                .mapNotNullTo(mutableSetOf()) { it.id }
                            pending.forEach { command ->
                                var acknowledge = true
                                if (command.id == null || command.id in effectiveIds) {
                                    acknowledge = applyCommand(command)
                                    publishStatus()
                                } else {
                                    Log.i(
                                        TAG,
                                        "Skipping superseded command: ${command.command} id=${command.id}",
                                    )
                                }
                                if (acknowledge) {
                                    command.id?.let { commandId ->
                                        repository.acknowledgeCommand(commandId)
                                        appliedCommands += commandId
                                    }
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

    /**
     * Applica gli aggiornamenti tracking ricevuti da Realtime o dal polling REST.
     */
    private suspend fun observeTrackingState() {
        repository.transmitterTrackingState(identity.id).collect { state ->
            if (state != null) {
                trackingState = state
                identity.cacheTrackingState(state)
                Log.d(
                    TAG,
                    "Tracking state received; liveUntil=${state.relationship.liveTrackingUntil}, " +
                        "persistent=${state.relationship.liveTrackingPersistent}, " +
                        "geofence=${state.relationship.geofenceEnabled}, " +
                        "notificationPending=${state.relationship.geofenceNotificationPending}",
                )
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

    /**
     * Applica le frequenze correnti e riavvia il provider se il watchdog lo rileva fermo.
     */
    @Synchronized
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
                    "commands=${updated.commandPollIntervalSec}s, " +
                    "live=${updated.liveTracking}, liveHistory=${updated.liveHistory}",
            )
        }
        effectiveConfig = updated
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val intervalChanged = activeLocationIntervalSec != updated.locationIntervalSec
        val watchdogRestart = LocationRecoveryPolicy.shouldRestart(
            registrationActive = locationRegistrationActive,
            registrationPending = locationRegistrationPending,
            lastCallbackElapsedMs = lastLocationCallbackElapsedMs,
            registrationStartedElapsedMs = locationRegistrationStartedElapsedMs,
            nowElapsedMs = nowElapsedMs,
            locationIntervalSec = updated.locationIntervalSec,
        )
        if (nowElapsedMs >= nextLocationRegistrationAttemptElapsedMs &&
            (intervalChanged || watchdogRestart)
        ) {
            val reason = if (intervalChanged) "tracking interval changed" else "location watchdog"
            startLocationUpdates(updated, reason)
        }
    }

    private suspend fun applyCommand(command: DeviceCommand): Boolean {
        Log.i(TAG, "Applying command: ${command.command}")
        when (command.command) {
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
                    ?: return true
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
            CommandType.PLAY_VOICE_MESSAGE -> {
                val messageId = requireNotNull(command.voiceMessageId) {
                    "Voice message command without payload"
                }
                playVoiceMessage(messageId)
            }
            CommandType.SHOW_TEXT_MESSAGE -> return queueTextMessage(command)
        }
        return true
    }

    /**
     * Mostra il primo messaggio disponibile senza bloccare gli altri comandi remoti.
     */
    private suspend fun queueTextMessage(command: DeviceCommand): Boolean {
        val commandId = command.id ?: return false
        val messageId = requireNotNull(command.textMessageId) {
            "Text message command without payload"
        }
        if (activeTextMessageCommandId == commandId) return false
        if (activeTextMessageCommandId != null) return false
        val textMessage = repository.fetchTextMessage(messageId)
        if (TextMessagePolicy.isTerminal(textMessage.status)) {
            return true
        }
        if (!textOverlayController.canDraw()) {
            if (textMessage.status != TextMessageStatus.WAITING_PERMISSION) {
                repository.updateTextMessageStatus(
                    messageId,
                    TextMessageStatus.WAITING_PERMISSION,
                    "Autorizza “Mostra sopra altre app” sul trasmettitore.",
                )
            }
            watchOverlayPermission()
            Log.w(TAG, "Text message waiting for overlay permission")
            return false
        }
        activeTextMessageCommandId = commandId
        activeTextMessageId = messageId
        val shown = textOverlayController.show(messageId, textMessage.body)
        if (!shown) {
            activeTextMessageCommandId = null
            activeTextMessageId = null
            return false
        }
        repository.updateTextMessageStatus(
            messageId,
            TextMessageStatus.DISPLAYING,
        )
        Log.i(TAG, "Text message overlay displayed")
        return false
    }

    /** Riavvia il piano comandi appena Android concede il permesso overlay. */
    private fun watchOverlayPermission() {
        if (overlayPermissionRecoveryJob?.isActive == true) return
        overlayPermissionRecoveryJob = scope.launch {
            while (isActive && !textOverlayController.canDraw()) {
                delay(OVERLAY_PERMISSION_CHECK_INTERVAL_MS)
            }
            if (isActive) {
                recoverySignals.trySend(ControlPlaneRestart("overlay permission granted"))
            }
        }
    }

    /** Conferma messaggio e comando soltanto dopo la pressione della X. */
    private fun onTextOverlayDismissed(messageId: String) {
        val commandId = activeTextMessageCommandId ?: return
        if (activeTextMessageId != messageId) return
        scope.launch {
            runCatching {
                repository.updateTextMessageStatus(
                    messageId,
                    TextMessageStatus.DISMISSED,
                )
                repository.acknowledgeCommand(commandId)
                appliedCommands += commandId
            }.onFailure {
                Log.e(TAG, "Text message dismissal acknowledgement failed", it)
                recoverySignals.trySend(it)
            }
            activeTextMessageCommandId = null
            activeTextMessageId = null
        }
    }

    private suspend fun playVoiceMessage(messageId: String) = voicePlaybackMutex.withLock {
        val voiceMessage = repository.fetchVoiceMessage(messageId)
        if (voiceMessage.status == VoiceMessageStatus.COMPLETED) return@withLock
        repository.updateVoiceMessageStatus(messageId, VoiceMessageStatus.DOWNLOADING)
        val audio = repository.downloadVoiceMessage(voiceMessage)
        if (audio.isEmpty() || audio.size > VoiceMessagePolicy.MAX_FILE_SIZE_BYTES) {
            repository.updateVoiceMessageStatus(
                messageId,
                VoiceMessageStatus.FAILED,
                "File audio non valido.",
            )
            return@withLock
        }

        val localFile = File(cacheDir, "voice-messages/$messageId.m4a")
        localFile.parentFile?.mkdirs()
        localFile.writeBytes(audio)
        val audioManager = getSystemService(AudioManager::class.java)
        val previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val targetVolume = VoiceMessagePolicy.streamVolume(
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            voiceMessage.volume,
        )
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()

        voiceMessagePlaying = true
        startAsForeground()
        runCatching {
            syncMediaState()
            publishStatus()
        }
        try {
            check(audioManager.requestAudioFocus(focusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            ) {
                "Audio focus unavailable"
            }
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            repository.updateVoiceMessageStatus(messageId, VoiceMessageStatus.PLAYING)
            playAudioFile(localFile, attributes)
            repository.updateVoiceMessageStatus(messageId, VoiceMessageStatus.COMPLETED)
            runCatching { repository.deleteVoiceMessageFile(voiceMessage.storagePath) }
                .onFailure { Log.w(TAG, "Voice message cleanup failed", it) }
        } catch (error: Throwable) {
            Log.e(TAG, "Voice message playback failed", error)
            repository.updateVoiceMessageStatus(
                messageId,
                VoiceMessageStatus.FAILED,
                "Riproduzione audio non riuscita.",
            )
        } finally {
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
            }
            audioManager.abandonAudioFocusRequest(focusRequest)
            localFile.delete()
            voiceMessagePlaying = false
            startAsForeground()
            runCatching {
                syncMediaState()
                publishStatus()
            }.onFailure {
                Log.e(TAG, "Microphone restore after voice message failed", it)
                requestMediaRecovery()
            }
        }
    }

    private suspend fun playAudioFile(
        file: File,
        attributes: AudioAttributes,
    ) = suspendCancellableCoroutine { continuation ->
        val player = MediaPlayer()
        continuation.invokeOnCancellation { player.release() }
        try {
            player.setAudioAttributes(attributes)
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                it.release()
                if (continuation.isActive) continuation.resume(Unit)
            }
            player.setOnErrorListener { failedPlayer, what, extra ->
                failedPlayer.release()
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("MediaPlayer error what=$what extra=$extra"),
                    )
                }
                true
            }
            player.prepare()
            player.start()
        } catch (error: Throwable) {
            player.release()
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private suspend fun maintainMediaOnDemand() {
        var unhealthyChecks = 0
        while (scope.isActive && identity.monitoringEnabled) {
            runCatching {
                syncMediaState()
                if (isMediaStateHealthy()) {
                    unhealthyChecks = 0
                } else {
                    unhealthyChecks++
                    Log.w(TAG, "Media watchdog unhealthy check=$unhealthyChecks")
                    if (ConnectionRecoveryPolicy.shouldRebuildMedia(unhealthyChecks)) {
                        Log.w(TAG, "Media watchdog rebuilding LiveKit publisher")
                        disconnectMediaRoom()
                        syncMediaState()
                        publishStatus()
                        unhealthyChecks = 0
                    }
                }
            }.onFailure {
                unhealthyChecks++
                Log.e(TAG, "Media synchronization failed", it)
                if (ConnectionRecoveryPolicy.shouldRebuildMedia(unhealthyChecks)) {
                    disconnectMediaRoom()
                    requestMediaRecovery()
                    unhealthyChecks = 0
                }
            }
            delay(MEDIA_WATCHDOG_INTERVAL_MS)
        }
    }

    private fun isMediaStateHealthy(): Boolean {
        val effectiveMicrophoneStreaming = desiredMicrophoneStreaming && !voiceMessagePlaying
        val shouldConnect = MediaConnectionPolicy.shouldConnect(
            desiredCameraStreaming,
            desiredMicrophoneStreaming,
            desiredScreenStreaming,
        )
        val activeRoom = room
        if (!shouldConnect) return activeRoom == null
        if (activeRoom == null) return false
        if (desiredCameraStreaming &&
            (!cameraStreaming ||
                activeRoom.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track == null)
        ) {
            return false
        }
        if (effectiveMicrophoneStreaming &&
            (!microphoneStreaming ||
                activeRoom.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track == null)
        ) {
            return false
        }
        return !desiredScreenStreaming || screenStreaming && screenTrack != null
    }

    private suspend fun syncMediaState() = mediaMutex.withLock {
        val effectiveMicrophoneStreaming = desiredMicrophoneStreaming && !voiceMessagePlaying
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
        if (microphoneStreaming != effectiveMicrophoneStreaming) {
            activeRoom.localParticipant.setMicrophoneEnabled(effectiveMicrophoneStreaming)
            microphoneStreaming = effectiveMicrophoneStreaming
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

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            processLocation(location)
        }

        override fun onProviderDisabled(provider: String) {
            registeredLocationProviders -= provider
            locationRegistrationActive = registeredLocationProviders.isNotEmpty()
            Log.w(TAG, "Location provider disabled: $provider")
        }
    }

    /**
     * Pubblica una posizione ricevuta dal provider Android e valuta lo storico.
     */
    private fun processLocation(location: Location) {
        lastLocationCallbackElapsedMs = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "Location callback received; provider=${location.provider} accuracy=${location.accuracy}m",
        )
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
            val relationship = trackingState?.relationship
            if (relationship?.geofenceEnabled == true ||
                relationship?.geofenceNotificationPending == true
            ) {
                runCatching {
                    repository.checkGeofence(point)
                }.onFailure {
                    Log.e(TAG, "Geofence evaluation failed", it)
                }
            }
            persistHistoryIfNeeded(point)
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

    /**
     * Registra una nuova richiesta Fused Location e conserva lo stato solo dopo il successo.
     */
    @Suppress("MissingPermission")
    private fun startLocationUpdates(config: EffectiveTrackingConfig, reason: String) {
        if (locationRegistrationPending) return
        locationRegistrationPending = true
        locationRegistrationActive = false
        activeLocationIntervalSec = null
        locationRegistrationStartedElapsedMs = SystemClock.elapsedRealtime()
        locationManager.removeUpdates(locationListener)
        registeredLocationProviders.clear()
        val intervalMillis = config.locationIntervalSec * 1_000L
        val enabledProviders = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).filter(locationManager::isProviderEnabled)
        Log.i(
            TAG,
            "Registering location updates; interval=${config.locationIntervalSec}s " +
                "providers=$enabledProviders reason=$reason",
        )
        enabledProviders.forEach { provider ->
            runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    intervalMillis,
                    0f,
                    locationListener,
                    Looper.getMainLooper(),
                )
            }.onSuccess {
                registeredLocationProviders += provider
            }.onFailure { error ->
                Log.e(TAG, "Location provider registration failed: $provider", error)
            }
        }
        locationRegistrationPending = false
        if (registeredLocationProviders.isEmpty()) {
            onLocationRegistrationFailure(
                IllegalStateException("No Android location provider available"),
            )
            return
        }
        locationRegistrationActive = true
        activeLocationIntervalSec = config.locationIntervalSec
        nextLocationRegistrationAttemptElapsedMs = 0L
        Log.i(
            TAG,
            "Location updates registered; providers=$registeredLocationProviders " +
                "interval=${config.locationIntervalSec}s",
        )
    }

    /**
     * Registra il fallimento e programma un nuovo tentativo senza creare un loop aggressivo.
     */
    private fun onLocationRegistrationFailure(error: Throwable) {
        locationRegistrationPending = false
        locationRegistrationActive = false
        activeLocationIntervalSec = null
        nextLocationRegistrationAttemptElapsedMs =
            SystemClock.elapsedRealtime() + LOCATION_REGISTRATION_RETRY_MS
        Log.e(TAG, "Location updates registration failed; retry scheduled", error)
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
        if (voiceMessagePlaying) {
            foregroundTypes = foregroundTypes or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
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
        private const val LOCATION_REGISTRATION_RETRY_MS = 15_000L
        private const val MEDIA_WATCHDOG_INTERVAL_MS = 5_000L
        private const val OVERLAY_PERMISSION_CHECK_INTERVAL_MS = 2_000L
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
