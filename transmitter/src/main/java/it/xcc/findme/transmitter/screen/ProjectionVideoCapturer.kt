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
        // The projection owns an independent EGL context, so forward an I420 copy.
        val i420Buffer = frame.buffer.toI420()
        val safeFrame = VideoFrame(i420Buffer, frame.rotation, frame.timestampNs)
        try {
            observer?.onFrameCaptured(safeFrame)
        } finally {
            safeFrame.release()
        }
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context?,
        capturerObserver: CapturerObserver?,
    ) {
        observer = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        check(controller.isReady) { "Screen capture authorization is not active" }
        if (started) return
        minimumFrameIntervalNs = 1_000_000_000L / framerate.coerceIn(1, MAX_FRAME_RATE)
        lastFrameTimestampNs = Long.MIN_VALUE
        started = true
        controller.addSink(sink)
        observer?.onCapturerStarted(true)
    }

    override fun stopCapture() {
        if (!started) return
        controller.removeSink(sink)
        started = false
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        controller.resize()
    }

    override fun dispose() {
        stopCapture()
        observer = null
    }

    override fun isScreencast(): Boolean = true

    companion object {
        private const val DEFAULT_FRAME_RATE = 15
        private const val MAX_FRAME_RATE = 20
    }
}
