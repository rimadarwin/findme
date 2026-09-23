/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Modello e policy per il feedback dei comandi multimediali remoti.
 * @modified 23.09.2026 - MDS | Aggiunta gestione di invio, conferma e timeout dei comandi.
 */
package it.xcc.findme.core

enum class MediaStreamKind {
    VIDEO,
    AUDIO,
    SCREEN,
}

enum class MediaCommandPhase {
    SENDING,
    AWAITING_CONFIRMATION,
    FAILED,
}

data class MediaCommandFeedback(
    val requestId: Long,
    val deviceId: String,
    val stream: MediaStreamKind,
    val targetEnabled: Boolean,
    val phase: MediaCommandPhase,
    val startedElapsedMs: Long,
    val errorMessage: String? = null,
)

data class MediaCommandTarget(
    val stream: MediaStreamKind,
    val enabled: Boolean,
)

object MediaCommandPolicy {
    const val CONFIRMATION_TIMEOUT_MS = 20_000L

    /**
     * Traduce un comando nel relativo stream e nello stato desiderato.
     */
    fun target(command: CommandType): MediaCommandTarget? = when (command) {
        CommandType.START_VIDEO -> MediaCommandTarget(MediaStreamKind.VIDEO, true)
        CommandType.STOP_VIDEO -> MediaCommandTarget(MediaStreamKind.VIDEO, false)
        CommandType.START_AUDIO -> MediaCommandTarget(MediaStreamKind.AUDIO, true)
        CommandType.STOP_AUDIO -> MediaCommandTarget(MediaStreamKind.AUDIO, false)
        CommandType.START_SCREEN -> MediaCommandTarget(MediaStreamKind.SCREEN, true)
        CommandType.STOP_SCREEN -> MediaCommandTarget(MediaStreamKind.SCREEN, false)
        else -> null
    }

    /**
     * Restituisce lo stato effettivo dello stream pubblicato dal trasmettitore.
     */
    fun currentValue(status: DeviceStatus?, stream: MediaStreamKind): Boolean = when (stream) {
        MediaStreamKind.VIDEO -> status?.cameraStreaming == true
        MediaStreamKind.AUDIO -> status?.microphoneStreaming == true
        MediaStreamKind.SCREEN -> status?.screenStreaming == true
    }

    /**
     * Verifica che il trasmettitore abbia raggiunto lo stato richiesto.
     */
    fun isConfirmed(feedback: MediaCommandFeedback, status: DeviceStatus?): Boolean =
        currentValue(status, feedback.stream) == feedback.targetEnabled

    /**
     * Mostra subito la destinazione richiesta mentre si attende la conferma remota.
     */
    fun displayedValue(actual: Boolean, feedback: MediaCommandFeedback?): Boolean =
        feedback?.takeUnless { it.phase == MediaCommandPhase.FAILED }?.targetEnabled ?: actual

    /**
     * Stabilisce se l'attesa della conferma ha superato il limite previsto.
     */
    fun hasTimedOut(feedback: MediaCommandFeedback, nowElapsedMs: Long): Boolean =
        nowElapsedMs - feedback.startedElapsedMs >= CONFIRMATION_TIMEOUT_MS
}
