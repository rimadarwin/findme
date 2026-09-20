package it.xcc.findme.core

object ConnectionRecoveryPolicy {
    const val INITIAL_RETRY_DELAY_MS = 5_000L
    const val MAX_RETRY_DELAY_MS = 60_000L
    const val SESSION_REFRESH_INTERVAL_MS = 20 * 60 * 1_000L
    const val REQUEST_TIMEOUT_MS = 20_000L
    const val DEFAULT_COMMAND_POLL_INTERVAL_SEC = 60
    private const val MIN_COMMAND_POLL_INTERVAL_SEC = 15
    private const val MAX_COMMAND_POLL_INTERVAL_SEC = 300
    private const val MEDIA_UNHEALTHY_CHECK_LIMIT = 3

    fun retryDelayMs(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 4)
        return (INITIAL_RETRY_DELAY_MS shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    fun isSessionRefreshDue(lastRefreshElapsedMs: Long, nowElapsedMs: Long): Boolean =
        lastRefreshElapsedMs == 0L ||
            nowElapsedMs - lastRefreshElapsedMs >= SESSION_REFRESH_INTERVAL_MS

    fun commandPollIntervalSec(configuredIntervalSec: Int): Int =
        configuredIntervalSec.coerceIn(
            MIN_COMMAND_POLL_INTERVAL_SEC,
            MAX_COMMAND_POLL_INTERVAL_SEC,
        )

    fun shouldRebuildMedia(consecutiveUnhealthyChecks: Int): Boolean =
        consecutiveUnhealthyChecks >= MEDIA_UNHEALTHY_CHECK_LIMIT

    fun isHeartbeatStale(
        lastSuccessElapsedMs: Long,
        nowElapsedMs: Long,
        heartbeatIntervalSec: Int,
    ): Boolean {
        val maximumSilenceMs = maxOf(90_000L, heartbeatIntervalSec * 2_000L)
        return nowElapsedMs - lastSuccessElapsedMs > maximumSilenceMs
    }
}
