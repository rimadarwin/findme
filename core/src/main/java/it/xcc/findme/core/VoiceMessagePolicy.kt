package it.xcc.findme.core

import kotlin.math.roundToInt

object VoiceMessagePolicy {
    const val MIN_DURATION_MS = 1_000
    const val MAX_DURATION_MS = 60_000
    const val MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024

    fun isValidDuration(durationMs: Long): Boolean =
        durationMs in MIN_DURATION_MS..MAX_DURATION_MS

    fun storagePath(
        receiverId: String,
        transmitterId: String,
        messageId: String,
    ): String = "$receiverId/$transmitterId/$messageId.m4a"

    fun streamVolume(maxVolume: Int, volume: VoiceMessageVolume): Int {
        if (maxVolume <= 0) return 0
        val fraction = when (volume) {
            VoiceMessageVolume.LOW -> 0.25f
            VoiceMessageVolume.MEDIUM -> 0.60f
            VoiceMessageVolume.HIGH -> 1f
        }
        return (maxVolume * fraction).roundToInt().coerceIn(1, maxVolume)
    }
}
