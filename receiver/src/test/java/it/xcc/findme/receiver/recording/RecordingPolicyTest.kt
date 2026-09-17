package it.xcc.findme.receiver.recording

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPolicyTest {
    @Test
    fun `formats elapsed time and caps it at thirty minutes`() {
        assertEquals("00:00", RecordingPolicy.formatElapsed(0))
        assertEquals("01:05", RecordingPolicy.formatElapsed(65_999))
        assertEquals("30:00", RecordingPolicy.formatElapsed(RecordingPolicy.MAX_DURATION_MS))
        assertEquals("30:00", RecordingPolicy.formatElapsed(Long.MAX_VALUE))
    }

    @Test
    fun `creates deterministic safe recording names`() {
        val instant = Instant.parse("2026-09-17T14:03:05Z")

        assertEquals(
            "FindMe_Camera_soggiorno_20260917_140305.mp4",
            RecordingFileNames.create(
                kind = RecordingKind.VIDEO,
                deviceName = "Camera soggiorno",
                instant = instant,
                zoneId = ZoneOffset.UTC,
            ),
        )
        assertEquals(
            "FindMe_device_20260917_140305.m4a",
            RecordingFileNames.create(
                kind = RecordingKind.AUDIO,
                deviceName = "   ",
                instant = instant,
                zoneId = ZoneOffset.UTC,
            ),
        )
    }

    @Test
    fun `only idle state is inactive`() {
        assertFalse(LocalRecordingState.Idle.isActive)
        assertTrue(LocalRecordingState.Starting.isActive)
        assertTrue(LocalRecordingState.Recording(10).isActive)
        assertTrue(LocalRecordingState.Finalizing.isActive)
    }

    @Test
    fun `stop gate is idempotent`() {
        val gate = RecordingStopGate()

        assertTrue(gate.begin())
        assertFalse(gate.begin())
        assertTrue(gate.isFinishing)
    }

    @Test
    fun `audio timestamps follow encoded sample count`() {
        assertEquals(0L, RecordingTimestamps.audioPtsUs(0, 48_000))
        assertEquals(1_000_000L, RecordingTimestamps.audioPtsUs(48_000, 48_000))
    }

    @Test
    fun `video timestamps stay monotonic when source timestamp regresses`() {
        val first = 1_000_000_000L
        val initial = RecordingTimestamps.videoPtsUs(first, first, -1)
        val second = RecordingTimestamps.videoPtsUs(first, first + 33_000_000, initial)
        val regressed = RecordingTimestamps.videoPtsUs(first, first + 20_000_000, second)

        assertEquals(0L, initial)
        assertEquals(33_000L, second)
        assertEquals(33_001L, regressed)
    }
}
