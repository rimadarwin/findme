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
    @SerialName("start_monitoring") START_MONITORING,
    @SerialName("stop_monitoring") STOP_MONITORING,
}

@Serializable
data class DeviceCommand(
    val id: Long? = null,
    @SerialName("device_id") val deviceId: String,
    val command: CommandType,
    val status: String = "pending",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("applied_at") val appliedAt: String? = null,
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
) {
    val displayName: String
        get() = alias?.trim()?.takeIf { it.isNotEmpty() } ?: device.name

    val hasAlias: Boolean
        get() = !alias.isNullOrBlank()

    fun isOnline(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val heartbeat = status?.lastHeartbeat ?: return false
        val heartbeatMillis = runCatching {
            java.time.Instant.parse(heartbeat).toEpochMilli()
        }.getOrNull() ?: return false
        return nowMillis - heartbeatMillis < 120_000
    }

    fun isMonitoringActive(nowMillis: Long = System.currentTimeMillis()): Boolean =
        status?.isMonitoring == true && isOnline(nowMillis)
}
