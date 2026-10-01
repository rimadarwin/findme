/**
 * @author Infinity
 * @description Policy per rilevare e recuperare una registrazione posizione non più attiva.
 * @modified 23.09.2026 - MDS | Aggiunta policy watchdog per gli aggiornamenti posizione.
 */
package it.xcc.findme.core

import kotlin.math.max

object LocationRecoveryPolicy {
    private const val MIN_STALE_TIMEOUT_MS = 60_000L
    private const val STALE_INTERVAL_MULTIPLIER = 3L

    /**
     * Calcola dopo quanto tempo senza callback la richiesta deve essere riarmata.
     */
    fun staleTimeoutMs(locationIntervalSec: Int): Long =
        max(
            MIN_STALE_TIMEOUT_MS,
            locationIntervalSec.coerceAtLeast(1) * 1_000L * STALE_INTERVAL_MULTIPLIER,
        )

    /**
     * Indica se la registrazione è assente o non produce callback da troppo tempo.
     */
    fun shouldRestart(
        registrationActive: Boolean,
        registrationPending: Boolean,
        lastCallbackElapsedMs: Long,
        registrationStartedElapsedMs: Long,
        nowElapsedMs: Long,
        locationIntervalSec: Int,
    ): Boolean {
        if (registrationPending) return false
        if (!registrationActive) return true
        val freshnessReferenceMs = max(lastCallbackElapsedMs, registrationStartedElapsedMs)
        if (freshnessReferenceMs <= 0L) return true
        return nowElapsedMs - freshnessReferenceMs >= staleTimeoutMs(locationIntervalSec)
    }
}
