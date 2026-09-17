package it.xcc.findme.core

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoredDeviceTest {
    private val device = Device(
        id = "3b5113e4-1fd5-4adb-ad22-794833d12429",
        ownerId = "d0ab555c-3f38-47bc-b76a-07fc0e3cae5b",
        name = "Telefono",
        role = DeviceRole.TRANSMITTER,
    )

    @Test
    fun `online when heartbeat is recent`() {
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val monitored = MonitoredDevice(
            device,
            DeviceStatus(device.id, true, lastHeartbeat = "2026-09-16T09:59:30Z"),
        )

        assertTrue(monitored.isOnline(now.toEpochMilli()))
    }

    @Test
    fun `offline when heartbeat is stale or absent`() {
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val stale = MonitoredDevice(
            device,
            DeviceStatus(device.id, true, lastHeartbeat = "2026-09-16T09:55:00Z"),
        )

        assertFalse(stale.isOnline(now.toEpochMilli()))
        assertFalse(MonitoredDevice(device).isOnline(now.toEpochMilli()))
    }

    @Test
    fun `monitoring light requires active status and recent heartbeat`() {
        val now = Instant.parse("2026-09-16T10:00:00Z").toEpochMilli()
        val active = MonitoredDevice(
            device,
            DeviceStatus(device.id, true, lastHeartbeat = "2026-09-16T09:59:30Z"),
        )
        val stopped = MonitoredDevice(
            device,
            DeviceStatus(device.id, false, lastHeartbeat = "2026-09-16T09:59:30Z"),
        )

        assertTrue(active.isMonitoringActive(now))
        assertFalse(stopped.isMonitoringActive(now))
    }

    @Test
    fun `media state remains compatible and switch camera serializes`() {
        val oldStatus = Json.decodeFromString<DeviceStatus>(
            """{"device_id":"${device.id}","is_monitoring":true}""",
        )

        assertFalse(oldStatus.cameraStreaming)
        assertFalse(oldStatus.microphoneStreaming)
        assertFalse(oldStatus.screenShareReady)
        assertFalse(oldStatus.screenStreaming)
        assertEquals("front", oldStatus.cameraFacing)
        val encodedStatus = Json.encodeToString(DeviceStatus.serializer(), oldStatus)
        assertTrue(encodedStatus.contains("\"camera_streaming\":false"))
        assertTrue(encodedStatus.contains("\"microphone_streaming\":false"))
        assertTrue(encodedStatus.contains("\"screen_share_ready\":false"))
        assertTrue(encodedStatus.contains("\"screen_streaming\":false"))
        assertTrue(encodedStatus.contains("\"camera_facing\":\"front\""))
        assertEquals(
            "\"switch_camera\"",
            Json.encodeToString(CommandType.serializer(), CommandType.SWITCH_CAMERA),
        )
        assertEquals(
            "\"start_screen\"",
            Json.encodeToString(CommandType.serializer(), CommandType.START_SCREEN),
        )
    }

    @Test
    fun `alias replaces display name but preserves empty fallback`() {
        assertEquals("Telefono", MonitoredDevice(device, alias = null).displayName)
        assertEquals("Telefono", MonitoredDevice(device, alias = "   ").displayName)
        assertEquals("Auto", MonitoredDevice(device, alias = " Auto ").displayName)
        assertTrue(MonitoredDevice(device, alias = "Auto").hasAlias)
        assertFalse(MonitoredDevice(device, alias = "").hasAlias)
    }
}
