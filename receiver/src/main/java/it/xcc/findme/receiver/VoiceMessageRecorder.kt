package it.xcc.findme.receiver

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

sealed interface VoiceMessageDraftState {
    data object Idle : VoiceMessageDraftState
    data class Recording(val elapsedMs: Long) : VoiceMessageDraftState
    data class Ready(val durationMs: Long) : VoiceMessageDraftState
    data object Sending : VoiceMessageDraftState
}

class VoiceMessageRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var startedAtElapsedMs = 0L

    fun start(output: File) {
        check(recorder == null) { "Registrazione vocale già attiva" }
        output.parentFile?.mkdirs()
        @Suppress("DEPRECATION")
        val activeRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        activeRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(64_000)
            setMaxDuration(60_000)
            setOutputFile(output.absolutePath)
            prepare()
            start()
        }
        recorder = activeRecorder
        startedAtElapsedMs = SystemClock.elapsedRealtime()
    }

    fun stop(): Long {
        val activeRecorder = recorder ?: return 0L
        val durationMs = SystemClock.elapsedRealtime() - startedAtElapsedMs
        recorder = null
        runCatching { activeRecorder.stop() }
            .onFailure {
                activeRecorder.reset()
                activeRecorder.release()
                throw it
            }
        activeRecorder.reset()
        activeRecorder.release()
        return durationMs
    }

    fun cancel() {
        val activeRecorder = recorder ?: return
        recorder = null
        runCatching { activeRecorder.stop() }
        activeRecorder.reset()
        activeRecorder.release()
    }
}
