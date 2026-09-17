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
import livekit.org.webrtc.SurfaceTextureHelper
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

    val isReady: Boolean
        get() = mediaProjection != null && virtualDisplay != null

    val captureSize: ScreenCaptureSize
        get() = currentSize ?: captureSize()

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
            projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            val egl = EglBase.create()
            val helper = SurfaceTextureHelper.create(THREAD_NAME, egl.eglBaseContext)
            eglBase = egl
            surfaceTextureHelper = helper
            helper.startListening { frame ->
                sinks.forEach { sink -> sink.onFrame(frame) }
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

    fun addSink(sink: VideoSink) {
        sinks += sink
    }

    fun removeSink(sink: VideoSink) {
        sinks -= sink
    }

    fun resize() {
        val helper = surfaceTextureHelper ?: return
        val size = captureSize()
        if (size == currentSize) return
        currentSize = size
        helper.setTextureSize(size.width, size.height)
        virtualDisplay?.resize(size.width, size.height, densityDpi())
        Log.i(TAG, "Screen capture resized to ${size.width}x${size.height}")
    }

    fun stop() {
        stop(notify = true)
    }

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

    private fun densityDpi(): Int = context.resources.displayMetrics.densityDpi

    private fun stop(notify: Boolean) {
        val wasReady = isReady
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
    }
}
