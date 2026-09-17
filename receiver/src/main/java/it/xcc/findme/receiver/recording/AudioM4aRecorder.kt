package it.xcc.findme.receiver.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import io.livekit.android.room.track.AudioTrack
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import livekit.org.webrtc.AudioTrackSink

class AudioM4aRecorder(
    private val context: Context,
    private val deviceName: String,
    private val onStarted: () -> Unit,
    private val onFinished: (RecordingResult) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val acceptingData = AtomicBoolean(false)
    private val stopGate = RecordingStopGate()
    private val queuedBytes = AtomicLong(0)
    private var track: AudioTrack? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var output: PendingMediaOutput? = null
    private var muxerTrackIndex = -1
    private var muxerStarted = false
    private var wroteSample = false
    private var sampleRate = 0
    private var channelCount = 0
    private var cumulativeFrames = 0L

    private val sink = object : AudioTrackSink {
        override fun onData(
            audioData: ByteBuffer,
            bitsPerSample: Int,
            sampleRate: Int,
            numberOfChannels: Int,
            numberOfFrames: Int,
            absoluteCaptureTimestampMs: Long,
        ) {
            if (!acceptingData.get()) return
            val copy = ByteArray(audioData.remaining())
            audioData.slice().get(copy)
            if (queuedBytes.addAndGet(copy.size.toLong()) > MAX_QUEUED_AUDIO_BYTES) {
                queuedBytes.addAndGet(-copy.size.toLong())
                fail(IllegalStateException("Il dispositivo non riesce a codificare l’audio in tempo reale."))
                return
            }
            executor.execute {
                queuedBytes.addAndGet(-copy.size.toLong())
                if (!acceptingData.get()) return@execute
                runCatching {
                    processPcm(
                        data = copy,
                        bitsPerSample = bitsPerSample,
                        inputSampleRate = sampleRate,
                        inputChannelCount = numberOfChannels,
                        numberOfFrames = numberOfFrames,
                    )
                }.onFailure(::finishWithError)
            }
        }
    }

    fun start(audioTrack: AudioTrack) {
        check(track == null) { "Registratore audio già utilizzato." }
        track = audioTrack
        acceptingData.set(true)
        audioTrack.addSink(sink)
    }

    fun stop() {
        if (!stopGate.begin()) return
        acceptingData.set(false)
        track?.removeSink(sink)
        executor.execute { finishNormally() }
    }

    private fun processPcm(
        data: ByteArray,
        bitsPerSample: Int,
        inputSampleRate: Int,
        inputChannelCount: Int,
        numberOfFrames: Int,
    ) {
        check(bitsPerSample == 16) { "Formato audio remoto non supportato: $bitsPerSample bit." }
        val bytesPerFrame = inputChannelCount * bitsPerSample / 8
        check(data.size == numberOfFrames * bytesPerFrame) {
            "Dimensione del frame audio non valida."
        }
        if (codec == null) {
            initializeEncoder(inputSampleRate, inputChannelCount)
            onStarted()
        }
        check(sampleRate == inputSampleRate && channelCount == inputChannelCount) {
            "Il formato audio remoto è cambiato durante la registrazione."
        }

        var offset = 0
        while (offset < data.size) {
            drainEncoder(endOfStream = false)
            val inputIndex = codec!!.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex < 0) continue
            val inputBuffer = checkNotNull(codec!!.getInputBuffer(inputIndex))
            inputBuffer.clear()
            val bytesToWrite = minOf(inputBuffer.remaining(), data.size - offset)
            val alignedBytes = bytesToWrite - (bytesToWrite % bytesPerFrame)
            check(alignedBytes > 0) { "Buffer encoder audio troppo piccolo." }
            val framesInBuffer = alignedBytes / bytesPerFrame
            val ptsUs = RecordingTimestamps.audioPtsUs(cumulativeFrames, sampleRate)
            inputBuffer.put(data, offset, alignedBytes)
            codec!!.queueInputBuffer(inputIndex, 0, alignedBytes, ptsUs, 0)
            cumulativeFrames += framesInBuffer
            offset += alignedBytes
        }
        drainEncoder(endOfStream = false)
    }

    private fun initializeEncoder(inputSampleRate: Int, inputChannelCount: Int) {
        sampleRate = inputSampleRate
        channelCount = inputChannelCount
        output = PendingMediaOutput.create(context, RecordingKind.AUDIO, deviceName)
        muxer = MediaMuxer(
            output!!.descriptor.fileDescriptor,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
        codec = MediaCodec.createEncoderByType(AUDIO_MIME).apply {
            configure(
                MediaFormat.createAudioFormat(
                    AUDIO_MIME,
                    inputSampleRate,
                    inputChannelCount,
                ).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                    setInteger(
                        MediaFormat.KEY_BIT_RATE,
                        if (inputChannelCount == 1) 64_000 else 128_000,
                    )
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
                },
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            start()
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val activeCodec = codec ?: return
        val info = MediaCodec.BufferInfo()
        val deadlineNs = if (endOfStream) System.nanoTime() + EOS_TIMEOUT_NS else 0L
        while (true) {
            val outputIndex = activeCodec.dequeueOutputBuffer(
                info,
                if (endOfStream) INPUT_TIMEOUT_US else 0,
            )
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || System.nanoTime() >= deadlineNs) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Formato AAC cambiato più di una volta." }
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
            codec?.let { activeCodec ->
                var inputIndex: Int
                do {
                    drainEncoder(endOfStream = false)
                    inputIndex = activeCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                } while (inputIndex < 0)
                val ptsUs = RecordingTimestamps.audioPtsUs(cumulativeFrames, sampleRate)
                activeCodec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    ptsUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                drainEncoder(endOfStream = true)
            }
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

    private fun fail(error: Throwable) {
        if (!stopGate.begin()) return
        acceptingData.set(false)
        track?.removeSink(sink)
        executor.execute { finishWithError(error) }
    }

    private fun finishWithError(error: Throwable) {
        if (!stopGate.isFinishing && stopGate.begin()) {
            acceptingData.set(false)
            track?.removeSink(sink)
        }
        runCatching { releaseEncodingResources() }
        output?.discard()
        onFinished(RecordingResult(error = error))
        executor.shutdown()
    }

    private fun releaseEncodingResources() {
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
        const val AUDIO_MIME = "audio/mp4a-latm"
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_QUEUED_AUDIO_BYTES = 2L * 1024 * 1024
        const val EOS_TIMEOUT_NS = 2_000_000_000L
    }
}
