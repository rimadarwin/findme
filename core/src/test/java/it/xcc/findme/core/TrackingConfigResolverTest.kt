/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Verifica la risoluzione delle frequenze di tracking e le policy correlate.
 * @modified 29.09.2026 - MDS | Coperti intervallo online 2 s e distanza nota.
 * @modified 29.09.2026 - MDS | Coperti tracking persistente e indipendenza dall'avviso area.
 */
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
        commandPollIntervalSec = 120,
    )

    @Test
    fun `offline configuration uses global defaults and dynamic heartbeat`() {
        val config = TrackingConfigResolver.resolve(settings, null)

        assertEquals(60, config.locationIntervalSec)
        assertEquals(120, config.historyIntervalSec)
        assertEquals(90, config.heartbeatIntervalSec)
        assertEquals(120, config.commandPollIntervalSec)
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
    fun `two second online interval is preserved during live tracking`() {
        val relationship = ReceiverTransmitter(
            receiverId = "receiver",
            transmitterId = "transmitter",
            liveTrackingPersistent = true,
        )

        val config = TrackingConfigResolver.resolve(
            settings.copy(onlineLocationIntervalSec = 2),
            relationship,
        )

        assertEquals(2, config.locationIntervalSec)
    }

    @Test
    fun `distance calculation returns approximately one kilometer`() {
        val from = point(41.800000, 12.600000, 5f)
        val to = point(41.809000, 12.600000, 5f)

        val distance = TrackingConfigResolver.distanceMeters(from, to)

        assertTrue(distance in 995.0..1_010.0)
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
    fun `persistent tracking enables online frequency without lease`() {
        val relationship = ReceiverTransmitter(
            receiverId = "receiver",
            transmitterId = "transmitter",
            liveTrackingPersistent = true,
        )

        val config = TrackingConfigResolver.resolve(settings, relationship)

        assertTrue(config.liveTracking)
        assertFalse(config.liveHistory)
        assertEquals(10, config.locationIntervalSec)
    }

    @Test
    fun `persistent tracking enables fast history when requested`() {
        val relationship = ReceiverTransmitter(
            receiverId = "receiver",
            transmitterId = "transmitter",
            liveTrackingUntil = "2026-09-17T07:00:00Z",
            liveTrackingPersistent = true,
            liveHistory = true,
        )

        val config = TrackingConfigResolver.resolve(
            settings,
            relationship,
            Instant.parse("2026-09-17T08:00:00Z"),
        )

        assertTrue(config.liveTracking)
        assertTrue(config.liveHistory)
        assertEquals(20, config.historyIntervalSec)
    }

    @Test
    fun `enabled geofence alone does not change tracking frequency`() {
        val relationship = ReceiverTransmitter(
            receiverId = "receiver",
            transmitterId = "transmitter",
            geofenceEnabled = true,
        )

        val config = TrackingConfigResolver.resolve(settings, relationship)

        assertFalse(config.liveTracking)
        assertFalse(config.liveHistory)
        assertEquals(60, config.locationIntervalSec)
    }

    @Test
    fun `cleared persistent tracking returns to offline frequency`() {
        val relationship = ReceiverTransmitter(
            receiverId = "receiver",
            transmitterId = "transmitter",
            liveTrackingPersistent = false,
            liveHistory = true,
        )

        val config = TrackingConfigResolver.resolve(settings, relationship)

        assertFalse(config.liveTracking)
        assertFalse(config.liveHistory)
        assertEquals(60, config.locationIntervalSec)
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
        assertFalse(MediaConnectionPolicy.shouldConnect(false, false, false))
        assertTrue(MediaConnectionPolicy.shouldConnect(true, false, false))
        assertTrue(MediaConnectionPolicy.shouldConnect(false, true, false))
        assertTrue(MediaConnectionPolicy.shouldConnect(false, false, true))
    }

    @Test
    fun `route sampling preserves endpoints and maximum size`() {
        val points = (0 until 10_000).toList()
        val sampled = RouteSampler.sample(points, 1_500)

        assertEquals(0, sampled.first())
        assertEquals(9_999, sampled.last())
        assertTrue(sampled.size <= 1_500)
    }

    @Test
    fun `geofence notifies only when crossing from inside to outside`() {
        val firstExit = GeofencePolicy.evaluate(
            wasOutside = false,
            distanceM = 101.0,
            radiusM = 100,
        )
        val stillOutside = GeofencePolicy.evaluate(
            wasOutside = true,
            distanceM = 150.0,
            radiusM = 100,
        )
        val reentered = GeofencePolicy.evaluate(
            wasOutside = true,
            distanceM = 90.0,
            radiusM = 100,
        )
        val secondExit = GeofencePolicy.evaluate(
            wasOutside = reentered.isOutside,
            distanceM = 110.0,
            radiusM = 100,
        )

        assertTrue(firstExit.shouldNotify)
        assertFalse(stillOutside.shouldNotify)
        assertFalse(reentered.isOutside)
        assertTrue(secondExit.shouldNotify)
        assertEquals(setOf(50, 100, 250, 500, 1000), GeofencePolicy.allowedRadiiM)
    }

    private fun point(latitude: Double, longitude: Double, accuracy: Float) = DeviceLocation(
        deviceId = "transmitter",
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
    )
}
