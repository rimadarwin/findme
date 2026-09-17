package it.xcc.findme.core

object ConnectionRecoveryPolicy {
    const val INITIAL_RETRY_DELAY_MS = 5_000L
    const val MAX_RETRY_DELAY_MS = 60_000L
    const val SESSION_REFRESH_INTERVAL_MS = 20 * 60 * 1_000L
    const val REQUEST_TIMEOUT_MS = 20_000L

    fun retryDelayMs(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 4)
        return (INITIAL_RETRY_DELAY_MS shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    fun isHeartbeatStale(
        lastSuccessElapsedMs: Long,
        nowElapsedMs: Long,
        heartbeatIntervalSec: Int,
    ): Boolean {
        val maximumSilenceMs = maxOf(90_000L, heartbeatIntervalSec * 2_000L)
        return nowElapsedMs - lastSuccessElapsedMs > maximumSilenceMs
    }
}
