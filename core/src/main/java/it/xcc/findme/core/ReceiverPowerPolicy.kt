package it.xcc.findme.core

object ReceiverPowerPolicy {
    fun shouldProtectMedia(
        cameraStreaming: Boolean,
        microphoneStreaming: Boolean,
        screenStreaming: Boolean,
        videoRecording: Boolean,
        audioRecording: Boolean,
        screenRecording: Boolean,
    ): Boolean = MediaConnectionPolicy.shouldConnect(
        cameraStreaming = cameraStreaming,
        microphoneStreaming = microphoneStreaming,
        screenStreaming = screenStreaming,
    ) || videoRecording || audioRecording || screenRecording

    fun shouldKeepSessionWhenStopped(
        screenInteractive: Boolean,
        mediaActive: Boolean,
    ): Boolean = !screenInteractive && mediaActive
}
