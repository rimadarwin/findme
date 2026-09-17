package it.xcc.findme.receiver.recording

import java.util.concurrent.atomic.AtomicBoolean

internal class RecordingStopGate {
    private val finishing = AtomicBoolean(false)

    val isFinishing: Boolean
        get() = finishing.get()

    fun begin(): Boolean = finishing.compareAndSet(false, true)
}

internal object RecordingTimestamps {
    fun audioPtsUs(encodedFrames: Long, sampleRate: Int): Long =
        encodedFrames * 1_000_000L / sampleRate.coerceAtLeast(1)

    fun videoPtsUs(
        firstTimestampNs: Long,
        timestampNs: Long,
        previousPtsUs: Long,
    ): Long = (
        (timestampNs - firstTimestampNs).coerceAtLeast(0L) / 1_000L
        ).coerceAtLeast(previousPtsUs + 1)
}
