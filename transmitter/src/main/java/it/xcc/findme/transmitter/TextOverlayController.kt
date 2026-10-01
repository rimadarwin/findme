/**
 * @author Infinity
 * @description Visualizza messaggi testuali sopra le altre applicazioni.
 * @modified 29.09.2026 - MDS | Sovrapposta la X all'angolo superiore senza ridurre il testo.
 * @modified 29.09.2026 - MDS | Corretto testo multilinea e spostata la X fuori dalla cornice.
 * @modified 29.09.2026 - MDS | Prima implementazione con cornice neon e testo adattivo.
 */
package it.xcc.findme.transmitter

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import it.xcc.findme.core.TextMessagePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TextOverlayController(
    private val context: Context,
    private val onDismissed: (String) -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var overlayView: View? = null
    private var activeMessageId: String? = null

    /** Indica se Android consente la visualizzazione sopra altre app. */
    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Mostra un solo messaggio alla volta e restituisce false se manca il permesso.
     */
    suspend fun show(messageId: String, body: String): Boolean = withContext(Dispatchers.Main) {
        if (!canDraw()) return@withContext false
        if (activeMessageId == messageId && overlayView != null) return@withContext true
        if (overlayView != null) return@withContext false

        val density = context.resources.displayMetrics.density
        val messageView = TextView(context).apply {
            text = body
            setTextColor(Color.rgb(232, 247, 255))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = true
            setLineSpacing(0f, 1.12f)
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                TextMessagePolicy.fontSizeSp(body.length),
            )
            setPadding(
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt(),
            )
            minimumHeight = (104 * density).toInt()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22 * density
                setColor(Color.rgb(2, 12, 22))
                setStroke((3 * density).toInt(), Color.rgb(0, 217, 255))
            }
            elevation = 18 * density
        }
        val closeView = TextView(context).apply {
            text = "×"
            textSize = 32f
            setTextColor(Color.rgb(0, 217, 255))
            gravity = Gravity.CENTER
            contentDescription = "Chiudi messaggio"
            setOnClickListener { dismiss(notify = true) }
        }
        val container = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            addView(
                messageView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = (40 * density).toInt()
                },
            )
        }
        container.addView(
            closeView,
            FrameLayout.LayoutParams(
                (48 * density).toInt(),
                (48 * density).toInt(),
                Gravity.TOP or Gravity.END,
            ).apply {
                marginEnd = (2 * density).toInt()
            },
        )
        val width = (context.resources.displayMetrics.widthPixels * 0.85f).toInt()
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        windowManager.addView(container, params)
        activeMessageId = messageId
        overlayView = container
        true
    }

    /** Rimuove l'overlay senza confermare il messaggio durante lo stop del servizio. */
    fun release() {
        overlayView?.post { dismiss(notify = false) }
    }

    /** Rimuove la finestra e, se richiesto, notifica la chiusura utente. */
    private fun dismiss(notify: Boolean) {
        val view = overlayView ?: return
        val messageId = activeMessageId
        runCatching { windowManager.removeView(view) }
        overlayView = null
        activeMessageId = null
        if (notify && messageId != null) onDismissed(messageId)
    }
}
