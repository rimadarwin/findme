package it.xcc.findme.receiver.recording

data class RecordingResult(
    val path: String? = null,
    val error: Throwable? = null,
)
