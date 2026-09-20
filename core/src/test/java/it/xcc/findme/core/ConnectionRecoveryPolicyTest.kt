package it.xcc.findme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecoveryPolicyTest {
    @Test
    fun `retry delay grows and is capped at one minute`() {
        assertEquals(5_000L, ConnectionRecoveryPolicy.retryDelayMs(1))
        assertEquals(10_000L, ConnectionRecoveryPolicy.retryDelayMs(2))
        assertEquals(20_000L, ConnectionRecoveryPolicy.retryDelayMs(3))
        assertEquals(40_000L, ConnectionRecoveryPolicy.retryDelayMs(4))
        assertEquals(60_000L, ConnectionRecoveryPolicy.retryDelayMs(5))
        assertEquals(60_000L, ConnectionRecoveryPolicy.retryDelayMs(100))
    }

    @Test
    fun `session refresh becomes due after elapsed deep sleep time`() {
        assertTrue(ConnectionRecoveryPolicy.isSessionRefreshDue(0, 1))
        assertFalse(ConnectionRecoveryPolicy.isSessionRefreshDue(1_000, 1_200_999))
        assertTrue(ConnectionRecoveryPolicy.isSessionRefreshDue(1_000, 1_201_000))
    }

    @Test
    fun `heartbeat watchdog allows two configured intervals with a floor`() {
        assertFalse(ConnectionRecoveryPolicy.isHeartbeatStale(1_000, 91_000, 30))
        assertTrue(ConnectionRecoveryPolicy.isHeartbeatStale(1_000, 91_001, 30))
        assertFalse(ConnectionRecoveryPolicy.isHeartbeatStale(1_000, 241_000, 120))
        assertTrue(ConnectionRecoveryPolicy.isHeartbeatStale(1_000, 241_001, 120))
    }

    @Test
    fun `command polling interval is bounded and media rebuild needs three failures`() {
        assertEquals(15, ConnectionRecoveryPolicy.commandPollIntervalSec(1))
        assertEquals(60, ConnectionRecoveryPolicy.commandPollIntervalSec(60))
        assertEquals(300, ConnectionRecoveryPolicy.commandPollIntervalSec(600))
        assertFalse(ConnectionRecoveryPolicy.shouldRebuildMedia(2))
        assertTrue(ConnectionRecoveryPolicy.shouldRebuildMedia(3))
    }
}
