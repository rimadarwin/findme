/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Policy energetica per sessioni remote attive sul ricevitore.
 * @modified 23.09.2026 - MDS | Estesa la protezione al tracking rapido.
 */
package it.xcc.findme.core

object ReceiverPowerPolicy {
    /**
     * Protegge CPU e sessione quando media, registrazioni o tracking rapido sono attivi.
     */
    fun shouldProtectMedia(
        cameraStreaming: Boolean,
        microphoneStreaming: Boolean,
        screenStreaming: Boolean,
        videoRecording: Boolean,
        audioRecording: Boolean,
        screenRecording: Boolean,
        fastTrackingActive: Boolean = false,
    ): Boolean = MediaConnectionPolicy.shouldConnect(
        cameraStreaming = cameraStreaming,
        microphoneStreaming = microphoneStreaming,
        screenStreaming = screenStreaming,
    ) || videoRecording || audioRecording || screenRecording || fastTrackingActive

    /**
     * Mantiene una sessione attiva durante il blocco, ma non nel normale background.
     */
    fun shouldKeepSessionWhenStopped(
        screenInteractive: Boolean,
        mediaActive: Boolean,
    ): Boolean = !screenInteractive && mediaActive
}
