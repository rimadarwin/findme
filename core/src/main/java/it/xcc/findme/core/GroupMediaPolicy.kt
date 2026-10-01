/**
 * @author Infinity
 * @description Policy pure per selezione e controllo multimediale di più trasmettitori.
 * @modified 01.10.2026 - Infinity | Prima implementazione.
 */
package it.xcc.findme.core

import java.util.UUID

enum class GroupStreamState {
    OFF,
    PARTIAL,
    ON,
}

data class MediaCommandKey(
    val deviceId: String,
    val stream: MediaStreamKind,
)

object GroupMediaPolicy {
    private const val TRANSMITTER_PARTICIPANT_PREFIX = "transmitter-"
    const val MIN_SELECTED_DEVICES = 2
    const val MAX_SIMULTANEOUS_RECORDINGS = 2
    const val GRID_COLUMNS = 2
    const val GRID_VISIBLE_ITEMS = 4

    /** Mantiene al massimo due righe visibili nel fullscreen, anche con più dispositivi. */
    fun visibleGridRows(itemCount: Int): Int {
        val visibleItems = itemCount.coerceIn(1, GRID_VISIBLE_ITEMS)
        return (visibleItems + GRID_COLUMNS - 1) / GRID_COLUMNS
    }

    /** Accetta solo dispositivi associati e richiede una vera selezione multipla. */
    fun isValidSelection(
        associatedDeviceIds: Set<String>,
        selectedDeviceIds: Set<String>,
    ): Boolean = selectedDeviceIds.size >= MIN_SELECTED_DEVICES &&
        selectedDeviceIds.all(associatedDeviceIds::contains)

    /** Riassume lo stato dello stream per il comando aggregato. */
    fun streamState(values: Collection<Boolean>): GroupStreamState = when {
        values.isEmpty() || values.none { it } -> GroupStreamState.OFF
        values.all { it } -> GroupStreamState.ON
        else -> GroupStreamState.PARTIAL
    }

    /** Limita separatamente ogni tipo di registrazione a due dispositivi. */
    fun canStartRecording(
        activeDeviceIds: Set<String>,
        deviceId: String,
    ): Boolean = deviceId in activeDeviceIds ||
        activeDeviceIds.size < MAX_SIMULTANEOUS_RECORDINGS

    /** Estrae soltanto UUID trasmettitore dalle identità LiveKit condivise. */
    fun transmitterDeviceId(participantIdentity: String?): String? {
        val candidate = participantIdentity
            ?.takeIf { it.startsWith(TRANSMITTER_PARTICIPANT_PREFIX) }
            ?.removePrefix(TRANSMITTER_PARTICIPANT_PREFIX)
            ?: return null
        return runCatching { UUID.fromString(candidate).toString() }.getOrNull()
    }
}
