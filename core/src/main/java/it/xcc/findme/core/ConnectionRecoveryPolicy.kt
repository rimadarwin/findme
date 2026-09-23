/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Policy condivise per retry, polling e controllo salute connessioni.
 * @modified 23.09.2026 - MDS | Ridotto a cinque secondi il polling minimo dei comandi.
 */
package it.xcc.findme.core

object ConnectionRecoveryPolicy {
    const val INITIAL_RETRY_DELAY_MS = 5_000L
    const val MAX_RETRY_DELAY_MS = 60_000L
    const val SESSION_REFRESH_INTERVAL_MS = 20 * 60 * 1_000L
    const val REQUEST_TIMEOUT_MS = 20_000L
    const val DEFAULT_COMMAND_POLL_INTERVAL_SEC = 5
    private const val MIN_COMMAND_POLL_INTERVAL_SEC = 5
    private const val MAX_COMMAND_POLL_INTERVAL_SEC = 300
    private const val MEDIA_UNHEALTHY_CHECK_LIMIT = 3

    /**
     * Calcola il ritardo esponenziale tra tentativi di riconnessione.
     */
    fun retryDelayMs(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 4)
        return (INITIAL_RETRY_DELAY_MS shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    /**
     * Indica se la sessione autenticata deve essere aggiornata.
     */
    fun isSessionRefreshDue(lastRefreshElapsedMs: Long, nowElapsedMs: Long): Boolean =
        lastRefreshElapsedMs == 0L ||
            nowElapsedMs - lastRefreshElapsedMs >= SESSION_REFRESH_INTERVAL_MS

    /**
     * Normalizza la frequenza del polling di sicurezza dei comandi.
     */
    fun commandPollIntervalSec(configuredIntervalSec: Int): Int =
        configuredIntervalSec.coerceIn(
            MIN_COMMAND_POLL_INTERVAL_SEC,
            MAX_COMMAND_POLL_INTERVAL_SEC,
        )

    /**
     * Richiede la ricostruzione media dopo fallimenti consecutivi.
     */
    fun shouldRebuildMedia(consecutiveUnhealthyChecks: Int): Boolean =
        consecutiveUnhealthyChecks >= MEDIA_UNHEALTHY_CHECK_LIMIT

    /**
     * Rileva un heartbeat fermo oltre la tolleranza configurata.
     */
    fun isHeartbeatStale(
        lastSuccessElapsedMs: Long,
        nowElapsedMs: Long,
        heartbeatIntervalSec: Int,
    ): Boolean {
        val maximumSilenceMs = maxOf(90_000L, heartbeatIntervalSec * 2_000L)
        return nowElapsedMs - lastSuccessElapsedMs > maximumSilenceMs
    }
}
