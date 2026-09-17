package it.xcc.findme.receiver.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import io.livekit.android.room.track.VideoTrack
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.GlRectDrawer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoFrameDrawer
import livekit.org.webrtc.VideoSink

class VideoMp4Recorder(
    private val context: Context,
    private val deviceName: String,
    private val recordingKind: RecordingKind = RecordingKind.VIDEO,
    private val onStarted: () -> Unit,
    private val onFinished: (RecordingResult) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val acceptingFrames = AtomicBoolean(false)
    private val stopGate = RecordingStopGate()
    private val pendingFrames = AtomicInteger(0)
    private var track: VideoTrack? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var eglBase: EglBase? = null
    private var frameDrawer: VideoFrameDrawer? = null
    private var glDrawer: GlRectDrawer? = null
    private var muxer: MediaMuxer? = null
    private var output: PendingMediaOutput? = null
    private var muxerTrackIndex = -1
    private var muxerStarted = false
    private var wroteSample = false
    private var outputWidth = 0
    private var outputHeight = 0
    private var firstFrameTimestampNs = Long.MIN_VALUE
    private var lastPtsUs = -1L

    private val sink = VideoSink { frame ->
        if (!acceptingFrames.get() || pendingFrames.incrementAndGet() > MAX_PENDING_FRAMES) {
            pendingFrames.decrementAndGet()
            return@VideoSink
        }
        frame.retain()
        executor.execute {
            try {
                if (acceptingFrames.get()) processFrame(frame)
            } catch (error: Throwable) {
                finishWithError(error)
            } finally {
                frame.release()
                pendingFrames.decrementAndGet()
            }
        }
    }

    fun start(videoTrack: VideoTrack) {
        check(track == null) { "Registratore video già utilizzato." }
        track = videoTrack
        acceptingFrames.set(true)
        videoTrack.addRenderer(sink)
    }

    fun stop() {
        if (!stopGate.begin()) return
        acceptingFrames.set(false)
        track?.removeRenderer(sink)
        executor.execute { finishNormally() }
    }

    private fun processFrame(frame: VideoFrame) {
        if (codec == null) {
            val sourceWidth = if (frame.rotation % 180 == 0) {
                frame.buffer.width
            } else {
                frame.buffer.height
            }
            val sourceHeight = if (frame.rotation % 180 == 0) {
                frame.buffer.height
            } else {
                frame.buffer.width
            }
            initializeEncoder(sourceWidth, sourceHeight)
            firstFrameTimestampNs = frame.timestampNs
            onStarted()
        }
        drainEncoder(endOfStream = false)
        val normalizedPtsUs = RecordingTimestamps.videoPtsUs(
            firstTimestampNs = firstFrameTimestampNs,
            timestampNs = frame.timestampNs,
            previousPtsUs = lastPtsUs,
        )
        lastPtsUs = normalizedPtsUs

        val i420 = frame.buffer.toI420()
        val convertedFrame = VideoFrame(i420, frame.rotation, frame.timestampNs)
        try {
            frameDrawer!!.drawFrame(
                convertedFrame,
                glDrawer,
                null,
                0,
                0,
                outputWidth,
                outputHeight,
            )
            eglBase!!.swapBuffers(normalizedPtsUs * 1_000L)
        } finally {
            convertedFrame.release()
        }
        drainEncoder(endOfStream = false)
    }

    private fun initializeEncoder(sourceWidth: Int, sourceHeight: Int) {
        val scale = minOf(
            1f,
            MAX_VIDEO_WIDTH.toFloat() / sourceWidth,
            MAX_VIDEO_HEIGHT.toFloat() / sourceHeight,
        )
        outputWidth = ((sourceWidth * scale).toInt() / 2 * 2).coerceAtLeast(2)
        outputHeight = ((sourceHeight * scale).toInt() / 2 * 2).coerceAtLeast(2)
        output = PendingMediaOutput.create(context, recordingKind, deviceName)
        muxer = MediaMuxer(
            output!!.descriptor.fileDescriptor,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
        codec = MediaCodec.createEncoderByType(VIDEO_MIME).apply {
            configure(
                MediaFormat.createVideoFormat(VIDEO_MIME, outputWidth, outputHeight).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                    )
                    setInteger(
                        MediaFormat.KEY_BIT_RATE,
                        (outputWidth * outputHeight * 4).coerceIn(1_200_000, 6_000_000),
                    )
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                },
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            inputSurface = createInputSurface()
            start()
        }
        eglBase = EglBase.create(null, EglBase.CONFIG_RECORDABLE).apply {
            createSurface(inputSurface)
            makeCurrent()
        }
        frameDrawer = VideoFrameDrawer()
        glDrawer = GlRectDrawer()
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val activeCodec = codec ?: return
        val info = MediaCodec.BufferInfo()
        val deadlineNs = if (endOfStream) System.nanoTime() + EOS_TIMEOUT_NS else 0L
        while (true) {
            val outputIndex = activeCodec.dequeueOutputBuffer(
                info,
                if (endOfStream) OUTPUT_TIMEOUT_US else 0,
            )
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || System.nanoTime() >= deadlineNs) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Formato H.264 cambiato più di una volta." }
                    muxerTrackIndex = muxer!!.addTrack(activeCodec.outputFormat)
                    muxer!!.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val outputBuffer = checkNotNull(activeCodec.getOutputBuffer(outputIndex))
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        muxer!!.writeSampleData(muxerTrackIndex, outputBuffer, info)
                        wroteSample = true
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    activeCodec.releaseOutputBuffer(outputIndex, false)
                    if (eos) return
                }
            }
        }
    }

    private fun finishNormally() {
        try {
            codec?.signalEndOfInputStream()
            drainEncoder(endOfStream = true)
            releaseEncodingResources()
            val activeOutput = output
            if (activeOutput != null && wroteSample) {
                activeOutput.publish()
                onFinished(RecordingResult(path = activeOutput.displayPath))
            } else {
                activeOutput?.discard()
                onFinished(RecordingResult())
            }
        } catch (error: Throwable) {
            finishWithError(error)
        } finally {
            executor.shutdown()
        }
    }

    private fun finishWithError(error: Throwable) {
        if (!stopGate.isFinishing && stopGate.begin()) {
            acceptingFrames.set(false)
            track?.removeRenderer(sink)
        }
        runCatching { releaseEncodingResources() }
        output?.discard()
        onFinished(RecordingResult(error = error))
        executor.shutdown()
    }

    private fun releaseEncodingResources() {
        frameDrawer?.release()
        frameDrawer = null
        glDrawer?.release()
        glDrawer = null
        eglBase?.let {
            it.detachCurrent()
            it.releaseSurface()
            it.release()
        }
        eglBase = null
        inputSurface?.release()
        inputSurface = null
        codec?.let {
            runCatching { it.stop() }
            it.release()
        }
        codec = null
        muxer?.let { activeMuxer ->
            try {
                if (muxerStarted) {
                    activeMuxer.stop()
                }
            } finally {
                activeMuxer.release()
            }
        }
        muxer = null
    }

    private companion object {
        const val VIDEO_MIME = "video/avc"
        const val MAX_VIDEO_WIDTH = 1280
        const val MAX_VIDEO_HEIGHT = 1280
        const val MAX_PENDING_FRAMES = 3
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val EOS_TIMEOUT_NS = 3_000_000_000L
    }
}
