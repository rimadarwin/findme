package it.xcc.findme.receiver.recording

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object RecordingFileNames {
    fun create(
        kind: RecordingKind,
        deviceName: String,
        instant: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val safeDeviceName = deviceName.trim()
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(40)
            .ifBlank { "device" }
        val timestamp = FILE_TIME_FORMAT.format(instant.atZone(zoneId))
        return "FindMe_${safeDeviceName}_$timestamp.${kind.extension}"
    }

    private val FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
}
