@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package it.xcc.findme.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DeviceRole {
    @SerialName("receiver") RECEIVER,
    @SerialName("transmitter") TRANSMITTER,
}

@Serializable
data class Device(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val name: String,
    val role: DeviceRole,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class DeviceStatus(
    @SerialName("device_id") val deviceId: String,
    @SerialName("is_monitoring") val isMonitoring: Boolean,
    @SerialName("battery_percent") val batteryPercent: Int? = null,
    @SerialName("camera_available") val cameraAvailable: Boolean = false,
    @SerialName("microphone_available") val microphoneAvailable: Boolean = false,
    @EncodeDefault
    @SerialName("camera_streaming") val cameraStreaming: Boolean = false,
    @EncodeDefault
    @SerialName("microphone_streaming") val microphoneStreaming: Boolean = false,
    @EncodeDefault
    @SerialName("screen_share_ready") val screenShareReady: Boolean = false,
    @EncodeDefault
    @SerialName("screen_streaming") val screenStreaming: Boolean = false,
    @EncodeDefault
    @SerialName("camera_facing") val cameraFacing: String = "front",
    @SerialName("last_heartbeat") val lastHeartbeat: String? = null,
)

@Serializable
data class DeviceLocation(
    @SerialName("device_id") val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    @SerialName("recorded_at") val recordedAt: String? = null,
)

@Serializable
enum class CommandType {
    @SerialName("start_audio") START_AUDIO,
    @SerialName("stop_audio") STOP_AUDIO,
    @SerialName("start_video") START_VIDEO,
    @SerialName("stop_video") STOP_VIDEO,
    @SerialName("switch_camera") SWITCH_CAMERA,
    @SerialName("start_screen") START_SCREEN,
    @SerialName("stop_screen") STOP_SCREEN,
    @SerialName("start_monitoring") START_MONITORING,
    @SerialName("stop_monitoring") STOP_MONITORING,
    @SerialName("play_voice_message") PLAY_VOICE_MESSAGE,
}

@Serializable
data class DeviceCommand(
    val id: Long? = null,
    @SerialName("device_id") val deviceId: String,
    val command: CommandType,
    @SerialName("voice_message_id") val voiceMessageId: String? = null,
    val status: String = "pending",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("applied_at") val appliedAt: String? = null,
)

@Serializable
enum class VoiceMessageVolume {
    @SerialName("low") LOW,
    @SerialName("medium") MEDIUM,
    @SerialName("high") HIGH,
}

@Serializable
enum class VoiceMessageStatus {
    @SerialName("pending") PENDING,
    @SerialName("downloading") DOWNLOADING,
    @SerialName("playing") PLAYING,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
}

@Serializable
data class VoiceMessage(
    val id: String,
    @SerialName("receiver_id") val receiverId: String,
    @SerialName("transmitter_id") val transmitterId: String,
    @SerialName("storage_path") val storagePath: String,
    val volume: VoiceMessageVolume,
    @SerialName("duration_ms") val durationMs: Int,
    val status: VoiceMessageStatus = VoiceMessageStatus.PENDING,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
data class LiveKitTokenRequest(
    @SerialName("device_id") val deviceId: String,
    val mode: String,
)

@Serializable
data class LiveKitTokenResponse(
    val token: String,
    val room: String,
    val url: String,
)

@Serializable
data class ReceiverProfile(
    @SerialName("device_id") val deviceId: String,
    @SerialName("owner_id") val ownerId: String,
    val name: String,
    @SerialName("pairing_code") val pairingCode: String? = null,
)

@Serializable
data class ReceiverTrackingSettings(
    @SerialName("device_id") val receiverId: String,
    @SerialName("offline_location_interval_sec") val offlineLocationIntervalSec: Int = 60,
    @SerialName("online_location_interval_sec") val onlineLocationIntervalSec: Int = 10,
    @SerialName("history_multiplier") val historyMultiplier: Int = 2,
    @EncodeDefault
    @SerialName("only_movement") val onlyMovement: Boolean = true,
    @SerialName("heartbeat_interval_sec") val heartbeatIntervalSec: Int = 60,
    @SerialName("command_poll_interval_sec") val commandPollIntervalSec: Int = 60,
    @SerialName("geofence_radius_m") val geofenceRadiusM: Int = 100,
)

@Serializable
data class TrackingSettingsUpdate(
    @SerialName("offline_location_interval_sec") val offlineLocationIntervalSec: Int,
    @SerialName("online_location_interval_sec") val onlineLocationIntervalSec: Int,
    @SerialName("history_multiplier") val historyMultiplier: Int,
    @EncodeDefault
    @SerialName("only_movement") val onlyMovement: Boolean,
    @SerialName("heartbeat_interval_sec") val heartbeatIntervalSec: Int,
    @SerialName("command_poll_interval_sec") val commandPollIntervalSec: Int,
    @SerialName("geofence_radius_m") val geofenceRadiusM: Int,
)

@Serializable
data class ReceiverRegistration(
    @SerialName("device_id") val deviceId: String,
    @SerialName("owner_id") val ownerId: String,
    val name: String,
)

@Serializable
data class ReceiverTransmitter(
    @SerialName("receiver_id") val receiverId: String,
    @SerialName("transmitter_id") val transmitterId: String,
    val alias: String? = null,
    @SerialName("live_tracking_until") val liveTrackingUntil: String? = null,
    @EncodeDefault
    @SerialName("live_history") val liveHistory: Boolean = false,
    @EncodeDefault
    @SerialName("geofence_enabled") val geofenceEnabled: Boolean = false,
    @SerialName("geofence_center_latitude") val geofenceCenterLatitude: Double? = null,
    @SerialName("geofence_center_longitude") val geofenceCenterLongitude: Double? = null,
    @SerialName("geofence_radius_m") val geofenceRadiusM: Int? = null,
    @EncodeDefault
    @SerialName("geofence_is_outside") val geofenceIsOutside: Boolean = false,
    @SerialName("geofence_updated_at") val geofenceUpdatedAt: String? = null,
)

@Serializable
data class ReceiverPushToken(
    val token: String,
    @SerialName("receiver_id") val receiverId: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class GeofenceCheckRequest(
    @SerialName("device_id") val deviceId: String,
    val latitude: Double,
    val longitude: Double,
)

data class TrackingRuntimeState(
    val settings: ReceiverTrackingSettings,
    val relationship: ReceiverTransmitter,
)

@Serializable
data class LocationHistoryPoint(
    val id: Long,
    @SerialName("device_id") val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    @SerialName("recorded_at") val recordedAt: String,
)

data class LocationHistoryPage(
    val points: List<LocationHistoryPoint>,
    val hasMore: Boolean,
)

@Serializable
data class LocationRouteRequest(
    @SerialName("target_device_id") val deviceId: String,
    @SerialName("from_time") val fromTime: String,
    @SerialName("to_time") val toTime: String,
    @SerialName("max_points") val maxPoints: Int = 1500,
)

@Serializable
data class PairDeviceRequest(
    @SerialName("transmitter_device_id") val transmitterDeviceId: String,
    @SerialName("pairing_code") val pairingCode: String,
)

@Serializable
data class PairDeviceResponse(
    @SerialName("receiver_id") val receiverId: String,
    @SerialName("receiver_name") val receiverName: String,
)

@Serializable
data class ReceiverAccessRequest(
    @SerialName("transmitter_device_id") val transmitterDeviceId: String,
    val answer: String? = null,
)

@Serializable
data class ReceiverAccessResponse(
    @SerialName("receiver_id") val receiverId: String,
    @SerialName("receiver_name") val receiverName: String,
    val question: String,
    val unlocked: Boolean? = null,
)

data class MonitoredDevice(
    val device: Device,
    val status: DeviceStatus? = null,
    val location: DeviceLocation? = null,
    val alias: String? = null,
    val relationship: ReceiverTransmitter? = null,
) {
    val displayName: String
        get() = alias?.trim()?.takeIf { it.isNotEmpty() } ?: device.name

    val hasAlias: Boolean
        get() = !alias.isNullOrBlank()

    fun isOnline(
        nowMillis: Long = System.currentTimeMillis(),
        heartbeatIntervalSec: Int = 60,
    ): Boolean {
        val heartbeat = status?.lastHeartbeat ?: return false
        val heartbeatMillis = runCatching {
            java.time.Instant.parse(heartbeat).toEpochMilli()
        }.getOrNull() ?: return false
        return nowMillis - heartbeatMillis < heartbeatIntervalSec * 2_000L
    }

    fun isMonitoringActive(
        nowMillis: Long = System.currentTimeMillis(),
        heartbeatIntervalSec: Int = 60,
    ): Boolean = status?.isMonitoring == true && isOnline(nowMillis, heartbeatIntervalSec)
}
