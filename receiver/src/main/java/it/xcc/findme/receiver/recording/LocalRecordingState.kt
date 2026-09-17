package it.xcc.findme.receiver.recording

enum class RecordingKind(
    val extension: String,
    val mimeType: String,
) {
    VIDEO("mp4", "video/mp4"),
    AUDIO("m4a", "audio/mp4"),
}

sealed interface LocalRecordingState {
    data object Idle : LocalRecordingState
    data object Starting : LocalRecordingState
    data class Recording(
        val startedAtElapsedMs: Long,
        val elapsedMs: Long = 0,
    ) : LocalRecordingState
    data object Finalizing : LocalRecordingState

    val isActive: Boolean
        get() = this !is Idle
}

object RecordingPolicy {
    const val MAX_DURATION_MS = 30 * 60 * 1000L

    fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = elapsedMs.coerceIn(0, MAX_DURATION_MS) / 1_000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
