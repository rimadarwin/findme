package it.xcc.findme.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingConfigResolverTest {
    private val settings = ReceiverTrackingSettings(
        receiverId = "receiver",
        offlineLocationIntervalSec = 60,
        onlineLocationIntervalSec = 10,
        historyMultiplier = 2,
        onlyMovement = true,
        heartbeatIntervalSec = 90,
    )

    @Test
    fun `offline configuration uses global defaults and dynamic heartbeat`() {
        val config = TrackingConfigResolver.resolve(settings, null)

        assertEquals(60, config.locationIntervalSec)
        assertEquals(120, config.historyIntervalSec)
        assertEquals(90, config.heartbeatIntervalSec)
        assertFalse(config.liveTracking)
    }

    @Test
    fun `valid lease enables online location and optional fast history`() {
        val now = Instant.parse("2026-09-17T08:00:00Z")
        val relationship = ReceiverTransmitter(
            receiverId = "receiver",
            transmitterId = "transmitter",
            liveTrackingUntil = "2026-09-17T08:01:30Z",
            liveHistory = true,
        )

        val config = TrackingConfigResolver.resolve(settings, relationship, now)

        assertTrue(config.liveTracking)
        assertTrue(config.liveHistory)
        assertEquals(10, config.locationIntervalSec)
        assertEquals(20, config.historyIntervalSec)
    }

    @Test
    fun `expired lease always falls back offline`() {
        val relationship = ReceiverTransmitter(
            receiverId = "receiver",
            transmitterId = "transmitter",
            liveTrackingUntil = "2026-09-17T07:59:59Z",
            liveHistory = true,
        )

        val config = TrackingConfigResolver.resolve(
            settings,
            relationship,
            Instant.parse("2026-09-17T08:00:00Z"),
        )

        assertFalse(config.liveTracking)
        assertFalse(config.liveHistory)
        assertEquals(60, config.locationIntervalSec)
        assertEquals(120, config.historyIntervalSec)
    }

    @Test
    fun `history respects interval and significant movement`() {
        val config = TrackingConfigResolver.resolve(settings, null)
        val previous = point(45.000000, 9.000000, 8f)
        val near = point(45.000010, 9.000000, 8f)
        val far = point(45.000200, 9.000000, 8f)

        assertFalse(
            TrackingConfigResolver.shouldPersistHistory(
                previous,
                far,
                lastSavedAtMillis = 0,
                nowMillis = 119_999,
                config,
            ),
        )
        assertFalse(
            TrackingConfigResolver.shouldPersistHistory(
                previous,
                near,
                lastSavedAtMillis = 0,
                nowMillis = 120_000,
                config,
            ),
        )
        assertTrue(
            TrackingConfigResolver.shouldPersistHistory(
                previous,
                far,
                lastSavedAtMillis = 0,
                nowMillis = 120_000,
                config,
            ),
        )
    }

    @Test
    fun `media connects only while at least one stream is requested`() {
        assertFalse(MediaConnectionPolicy.shouldConnect(false, false))
        assertTrue(MediaConnectionPolicy.shouldConnect(true, false))
        assertTrue(MediaConnectionPolicy.shouldConnect(false, true))
    }

    @Test
    fun `route sampling preserves endpoints and maximum size`() {
        val points = (0 until 10_000).toList()
        val sampled = RouteSampler.sample(points, 1_500)

        assertEquals(0, sampled.first())
        assertEquals(9_999, sampled.last())
        assertTrue(sampled.size <= 1_500)
    }

    private fun point(latitude: Double, longitude: Double, accuracy: Float) = DeviceLocation(
        deviceId = "transmitter",
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
    )
}
