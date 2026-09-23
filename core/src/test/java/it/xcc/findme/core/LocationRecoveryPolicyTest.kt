/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Test della policy di recovery delle richieste posizione.
 * @modified 23.09.2026 - MDS | Aggiunto test per timestamp PostgreSQL con offset UTC.
 * @modified 23.09.2026 - MDS | Aggiunti test per timeout, pending e registrazioni perse.
 */
package it.xcc.findme.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRecoveryPolicyTest {
    /**
     * Verifica il formato con offset UTC restituito da PostgreSQL.
     */
    @Test
    fun `tracking lease accepts postgres offset timestamp`() {
        assertTrue(
            TrackingConfigResolver.isLiveTrackingLeaseActive(
                value = "2026-09-23T10:47:29.867623+00:00",
                now = Instant.parse("2026-09-23T10:46:00Z"),
            ),
        )
    }

    /**
     * Verifica il timeout minimo per la frequenza online e il moltiplicatore offline.
     */
    @Test
    fun `stale timeout has a floor and follows configured interval`() {
        assertEquals(60_000L, LocationRecoveryPolicy.staleTimeoutMs(10))
        assertEquals(180_000L, LocationRecoveryPolicy.staleTimeoutMs(60))
        assertEquals(360_000L, LocationRecoveryPolicy.staleTimeoutMs(120))
    }

    /**
     * Verifica che una registrazione assente venga riavviata ma non durante una richiesta pending.
     */
    @Test
    fun `missing registration restarts unless another registration is pending`() {
        assertTrue(
            LocationRecoveryPolicy.shouldRestart(
                registrationActive = false,
                registrationPending = false,
                lastCallbackElapsedMs = 0L,
                registrationStartedElapsedMs = 0L,
                nowElapsedMs = 10_000L,
                locationIntervalSec = 60,
            ),
        )
        assertFalse(
            LocationRecoveryPolicy.shouldRestart(
                registrationActive = false,
                registrationPending = true,
                lastCallbackElapsedMs = 0L,
                registrationStartedElapsedMs = 0L,
                nowElapsedMs = 10_000L,
                locationIntervalSec = 60,
            ),
        )
    }

    /**
     * Verifica il riarmo dopo tre intervalli senza callback.
     */
    @Test
    fun `active registration restarts only after callback timeout`() {
        assertFalse(
            LocationRecoveryPolicy.shouldRestart(
                registrationActive = true,
                registrationPending = false,
                lastCallbackElapsedMs = 10_000L,
                registrationStartedElapsedMs = 5_000L,
                nowElapsedMs = 189_999L,
                locationIntervalSec = 60,
            ),
        )
        assertTrue(
            LocationRecoveryPolicy.shouldRestart(
                registrationActive = true,
                registrationPending = false,
                lastCallbackElapsedMs = 10_000L,
                registrationStartedElapsedMs = 5_000L,
                nowElapsedMs = 190_000L,
                locationIntervalSec = 60,
            ),
        )
    }

    /**
     * Verifica che un riarmo recente non erediti la vecchia callback come riferimento.
     */
    @Test
    fun `new registration uses its own start time as freshness reference`() {
        assertFalse(
            LocationRecoveryPolicy.shouldRestart(
                registrationActive = true,
                registrationPending = false,
                lastCallbackElapsedMs = 1_000L,
                registrationStartedElapsedMs = 200_000L,
                nowElapsedMs = 259_999L,
                locationIntervalSec = 10,
            ),
        )
        assertTrue(
            LocationRecoveryPolicy.shouldRestart(
                registrationActive = true,
                registrationPending = false,
                lastCallbackElapsedMs = 1_000L,
                registrationStartedElapsedMs = 200_000L,
                nowElapsedMs = 260_000L,
                locationIntervalSec = 10,
            ),
        )
    }
}
