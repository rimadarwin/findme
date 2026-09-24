/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Gestisce la MediaProjection persistente e i frame del mirroring.
 * @modified 24.09.2026 - MDS | Aggiunti cache immediata e keepalive ottimizzato a un frame al secondo.
 */
package it.xcc.findme.transmitter.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.util.concurrent.CopyOnWriteArraySet
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink

class ScreenProjectionController(
    private val context: Context,
    private val onStopped: () -> Unit,
) {
    private val sinks = CopyOnWriteArraySet<VideoSink>()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var eglBase: EglBase? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var currentSize: ScreenCaptureSize? = null
    private val frameLock = Any()
    private val keepAliveHandler = Handler(Looper.getMainLooper())
    private val keepAlivePolicy = ScreenFrameKeepAlivePolicy(KEEPALIVE_INTERVAL_NS)
    private var cachedFrame: CachedScreenFrame? = null
    private var acceptsCachedFrames = false

    private val keepAliveRunnable = Runnable {
        if (sinks.isEmpty()) return@Runnable
        val keepAliveDue = synchronized(frameLock) {
            keepAlivePolicy.keepAliveDue(System.nanoTime())
        }
        if (keepAliveDue) dispatchCachedFrame()
        scheduleKeepAlive()
    }

    /** Indica se la proiezione è autorizzata e collegata al display virtuale. */
    val isReady: Boolean
        get() = mediaProjection != null && virtualDisplay != null

    /** Restituisce le dimensioni correnti della cattura. */
    val captureSize: ScreenCaptureSize
        get() = currentSize ?: captureSize()

    /** Avvia la proiezione persistente autorizzata dall'utente. */
    fun start(resultCode: Int, resultData: Intent) {
        require(resultCode == Activity.RESULT_OK)
        stop(notify = false)
        ScreenProjectionRuntime.update(ScreenProjectionState.STARTING)
        try {
            val projectionManager =
                context.getSystemService(MediaProjectionManager::class.java)
            val projection = checkNotNull(
                projectionManager.getMediaProjection(resultCode, resultData),
            ) { "MediaProjection authorization was rejected" }
            mediaProjection = projection
            synchronized(frameLock) {
                acceptsCachedFrames = true
            }
            projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            initializeWebRtc()

            val egl = EglBase.create()
            val helper = SurfaceTextureHelper.create(THREAD_NAME, egl.eglBaseContext)
            eglBase = egl
            surfaceTextureHelper = helper
            helper.startListening { frame ->
                onProjectionFrame(frame)
            }
            createVirtualDisplay(projection, helper)
            ScreenProjectionRuntime.update(ScreenProjectionState.READY)
            Log.i(TAG, "Persistent screen capture is ready")
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to start persistent screen capture", error)
            stop(notify = false)
            throw error
        }
    }

    /** Collega un destinatario e gli inoltra immediatamente l'ultimo frame disponibile. */
    fun addSink(sink: VideoSink) {
        val wasInactive = sinks.isEmpty()
        sinks += sink
        if (!wasInactive) {
            dispatchCachedFrame(listOf(sink))
            return
        }
        val sendCachedFrame = synchronized(frameLock) {
            keepAlivePolicy.start(
                hasCachedFrame = cachedFrame != null,
                nowNs = System.nanoTime(),
            )
        }
        if (sendCachedFrame) dispatchCachedFrame(listOf(sink))
        scheduleKeepAlive()
    }

    /** Scollega un destinatario e arresta ogni attività di keepalive se era l'ultimo. */
    fun removeSink(sink: VideoSink) {
        sinks -= sink
        if (sinks.isEmpty()) {
            keepAliveHandler.removeCallbacks(keepAliveRunnable)
            synchronized(frameLock) {
                keepAlivePolicy.stop()
            }
        }
    }

    /** Aggiorna il display virtuale quando cambiano orientamento o dimensioni. */
    fun resize() {
        val helper = surfaceTextureHelper ?: return
        val size = captureSize()
        if (size == currentSize) return
        currentSize = size
        helper.setTextureSize(size.width, size.height)
        virtualDisplay?.resize(size.width, size.height, densityDpi())
        Log.i(TAG, "Screen capture resized to ${size.width}x${size.height}")
    }

    /** Arresta la proiezione e notifica la revoca al servizio. */
    fun stop() {
        stop(notify = true)
    }

    /** Inoltra un frame reale e rinvia il keepalive finché la sorgente resta attiva. */
    private fun onProjectionFrame(frame: VideoFrame) {
        if (sinks.isEmpty()) {
            cacheInitialFrame(frame)
            return
        }
        synchronized(frameLock) {
            keepAlivePolicy.onRealFrame(System.nanoTime())
        }
        sinks.forEach { sink -> sink.onFrame(frame) }
        scheduleKeepAlive()
    }

    /**
     * Aggiorna la cache con un frame già convertito dal capturer, evitando conversioni duplicate.
     */
    internal fun cachePublishedFrame(frame: VideoFrame) {
        val nextFrame = frame.toCachedFrame() ?: return
        val previousFrame = synchronized(frameLock) {
            if (!acceptsCachedFrames) {
                nextFrame.buffer.release()
                return
            }
            cachedFrame.also { cachedFrame = nextFrame }
        }
        previousFrame?.buffer?.release()
    }

    /**
     * Memorizza il primo frame disponibile senza mantenere elaborazioni quando il mirroring è OFF.
     */
    private fun cacheInitialFrame(frame: VideoFrame) {
        synchronized(frameLock) {
            if (cachedFrame != null) return
        }
        val initialFrame = frame.toCachedFrame() ?: return
        val stored = synchronized(frameLock) {
            if (acceptsCachedFrames && cachedFrame == null) {
                cachedFrame = initialFrame
                true
            } else {
                initialFrame.buffer.release()
                false
            }
        }
        if (stored && sinks.isNotEmpty()) {
            synchronized(frameLock) {
                keepAlivePolicy.onRealFrame(System.nanoTime())
            }
            dispatchCachedFrame()
            scheduleKeepAlive()
        }
    }

    /** Crea una copia I420 indipendente dal contesto EGL della proiezione. */
    private fun VideoFrame.toCachedFrame(): CachedScreenFrame? {
        val i420Buffer = buffer.toI420() ?: return null
        return CachedScreenFrame(
            buffer = i420Buffer,
            rotation = rotation,
        )
    }

    /** Invia una copia sicura del frame memorizzato ai destinatari indicati. */
    private fun dispatchCachedFrame(targetSinks: Collection<VideoSink> = sinks) {
        val snapshot = synchronized(frameLock) {
            cachedFrame?.also { it.buffer.retain() }
        } ?: return
        val frame = VideoFrame(snapshot.buffer, snapshot.rotation, System.nanoTime())
        try {
            targetSinks.forEach { sink -> sink.onFrame(frame) }
        } finally {
            frame.release()
        }
    }

    /** Pianifica il prossimo controllo solo finché il mirroring ha destinatari attivi. */
    private fun scheduleKeepAlive() {
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
        if (sinks.isNotEmpty()) {
            keepAliveHandler.postDelayed(keepAliveRunnable, KEEPALIVE_INTERVAL_MS)
        }
    }

    /** Crea il display virtuale usato dalla MediaProjection. */
    private fun createVirtualDisplay(
        projection: MediaProjection,
        helper: SurfaceTextureHelper,
    ) {
        val size = captureSize()
        currentSize = size
        helper.setTextureSize(size.width, size.height)
        val surface = android.view.Surface(helper.surfaceTexture)
        virtualDisplay = projection.createVirtualDisplay(
            DISPLAY_NAME,
            size.width,
            size.height,
            densityDpi(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )
        surface.release()
    }

    /** Carica una sola volta le componenti native necessarie alla conversione I420. */
    private fun initializeWebRtc() {
        synchronized(WEB_RTC_INITIALIZATION_LOCK) {
            if (webRtcInitialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            webRtcInitialized = true
        }
    }

    /** Calcola dimensioni pari e proporzionate per limitare il costo della trasmissione. */
    private fun captureSize(): ScreenCaptureSize {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.let {
                DisplayMetrics().apply {
                    widthPixels = it.width()
                    heightPixels = it.height()
                }
            }
        } else {
            @Suppress("DEPRECATION")
            DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
        }
        return ScreenCaptureDimensions.fit(metrics.widthPixels, metrics.heightPixels)
    }

    /** Restituisce la densità richiesta dal display virtuale. */
    private fun densityDpi(): Int = context.resources.displayMetrics.densityDpi

    /** Rilascia tutte le risorse della proiezione ed eventualmente notifica l'arresto. */
    private fun stop(notify: Boolean) {
        val wasReady = isReady
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
        synchronized(frameLock) {
            keepAlivePolicy.stop()
            acceptsCachedFrames = false
            cachedFrame?.buffer?.release()
            cachedFrame = null
        }
        virtualDisplay?.release()
        virtualDisplay = null
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        if (mediaProjection != null) runCatching { mediaProjection?.stop() }
        mediaProjection = null
        surfaceTextureHelper?.stopListening()
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        eglBase?.release()
        eglBase = null
        currentSize = null
        ScreenProjectionRuntime.update(ScreenProjectionState.UNAVAILABLE)
        if (notify && wasReady) onStopped()
    }

    /** Gestisce la revoca della MediaProjection da parte di Android. */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection was revoked by Android")
            stop(notify = true)
        }
    }

    companion object {
        private const val TAG = "FindMeScreen"
        private const val THREAD_NAME = "FindMeScreenCapture"
        private const val DISPLAY_NAME = "FindMeScreen"
        private const val KEEPALIVE_INTERVAL_MS = 1_000L
        private const val KEEPALIVE_INTERVAL_NS = KEEPALIVE_INTERVAL_MS * 1_000_000L
        private val WEB_RTC_INITIALIZATION_LOCK = Any()
        private var webRtcInitialized = false
    }
}

private data class CachedScreenFrame(
    val buffer: VideoFrame.I420Buffer,
    val rotation: Int,
)
