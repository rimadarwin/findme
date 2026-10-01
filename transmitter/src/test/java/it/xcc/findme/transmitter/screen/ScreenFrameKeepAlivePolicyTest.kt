/**
 * @author Infinity
 * @description Verifica la temporizzazione del keepalive per il mirroring dello schermo.
 * @modified 24.09.2026 - MDS | Aggiunti i test della policy di keepalive ottimizzato.
 */
package it.xcc.findme.transmitter.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFrameKeepAlivePolicyTest {
    /** Verifica l'invio immediato della cache e il limite di un keepalive al secondo. */
    @Test
    fun `cached frame is immediate and keepalive is limited to one per second`() {
        val policy = ScreenFrameKeepAlivePolicy(INTERVAL_NS)

        assertTrue(policy.start(hasCachedFrame = true, nowNs = 0L))
        assertFalse(policy.keepAliveDue(nowNs = INTERVAL_NS - 1L))
        assertTrue(policy.keepAliveDue(nowNs = INTERVAL_NS))
        assertFalse(policy.keepAliveDue(nowNs = INTERVAL_NS * 2L - 1L))
        assertTrue(policy.keepAliveDue(nowNs = INTERVAL_NS * 2L))
    }

    /** Verifica che ogni frame reale sospenda il keepalive fino a nuova inattività. */
    @Test
    fun `real frames postpone keepalive until the source becomes idle`() {
        val policy = ScreenFrameKeepAlivePolicy(INTERVAL_NS)

        policy.start(hasCachedFrame = false, nowNs = 0L)
        policy.onRealFrame(nowNs = 500_000_000L)

        assertFalse(policy.keepAliveDue(nowNs = 1_499_999_999L))
        assertTrue(policy.keepAliveDue(nowNs = 1_500_000_000L))

        policy.onRealFrame(nowNs = 1_700_000_000L)

        assertFalse(policy.keepAliveDue(nowNs = 2_699_999_999L))
        assertTrue(policy.keepAliveDue(nowNs = 2_700_000_000L))
    }

    /** Verifica che a mirroring spento non venga mai richiesto alcun keepalive. */
    @Test
    fun `stopped streaming disables keepalive activity`() {
        val policy = ScreenFrameKeepAlivePolicy(INTERVAL_NS)

        policy.start(hasCachedFrame = true, nowNs = 0L)
        policy.stop()

        assertFalse(policy.keepAliveDue(nowNs = INTERVAL_NS * 10L))
    }

    private companion object {
        const val INTERVAL_NS = 1_000_000_000L
    }
}
