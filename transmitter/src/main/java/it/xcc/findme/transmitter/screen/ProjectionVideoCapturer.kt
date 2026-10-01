/**
 * @author Infinity
 * @description Adatta i frame della MediaProjection al capturer video di LiveKit.
 * @modified 24.09.2026 - MDS | Condivisa la copia I420 con la cache del keepalive.
 */
package it.xcc.findme.transmitter.screen

import android.content.Context
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink

class ProjectionVideoCapturer(
    private val controller: ScreenProjectionController,
) : VideoCapturer {
    private var observer: CapturerObserver? = null
    private var started = false
    private var minimumFrameIntervalNs = 1_000_000_000L / DEFAULT_FRAME_RATE
    private var lastFrameTimestampNs = Long.MIN_VALUE
    private val sink = VideoSink { frame ->
        if (!started ||
            lastFrameTimestampNs != Long.MIN_VALUE &&
            frame.timestampNs - lastFrameTimestampNs < minimumFrameIntervalNs
        ) {
            return@VideoSink
        }
        lastFrameTimestampNs = frame.timestampNs
        // La proiezione usa un contesto EGL indipendente: LiveKit riceve una copia I420 sicura.
        val i420Buffer = frame.buffer.toI420()
        val safeFrame = VideoFrame(i420Buffer, frame.rotation, frame.timestampNs)
        try {
            controller.cachePublishedFrame(safeFrame)
            observer?.onFrameCaptured(safeFrame)
        } finally {
            safeFrame.release()
        }
    }

    /** Registra l'observer fornito da LiveKit per i frame catturati. */
    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context?,
        capturerObserver: CapturerObserver?,
    ) {
        observer = capturerObserver
    }

    /** Avvia l'inoltro dei frame applicando la frequenza massima richiesta. */
    override fun startCapture(width: Int, height: Int, framerate: Int) {
        check(controller.isReady) { "Screen capture authorization is not active" }
        if (started) return
        minimumFrameIntervalNs = 1_000_000_000L / framerate.coerceIn(1, MAX_FRAME_RATE)
        lastFrameTimestampNs = Long.MIN_VALUE
        started = true
        controller.addSink(sink)
        observer?.onCapturerStarted(true)
    }

    /** Interrompe l'inoltro e rimuove il capturer dai destinatari della proiezione. */
    override fun stopCapture() {
        if (!started) return
        controller.removeSink(sink)
        started = false
        observer?.onCapturerStopped()
    }

    /** Ridimensiona il display virtuale dopo un cambio di formato. */
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        controller.resize()
    }

    /** Rilascia il capturer e il relativo observer. */
    override fun dispose() {
        stopCapture()
        observer = null
    }

    /** Indica a WebRTC che la sorgente rappresenta la condivisione dello schermo. */
    override fun isScreencast(): Boolean = true

    companion object {
        private const val DEFAULT_FRAME_RATE = 15
        private const val MAX_FRAME_RATE = 20
    }
}
