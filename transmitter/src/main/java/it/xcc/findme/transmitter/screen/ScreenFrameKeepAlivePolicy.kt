/**
 * @author Infinity
 * @description Regola l'invio dei frame di keepalive quando il mirroring resta statico.
 * @modified 24.09.2026 - MDS | Introdotta la policy di keepalive ottimizzato.
 */
package it.xcc.findme.transmitter.screen

internal class ScreenFrameKeepAlivePolicy(
    private val intervalNs: Long,
) {
    private var active = false
    private var cachedFrameAvailable = false
    private var lastFrameSentNs: Long? = null

    init {
        require(intervalNs > 0L) { "L'intervallo di keepalive deve essere positivo" }
    }

    /**
     * Attiva la policy e indica se il frame già memorizzato deve essere inviato subito.
     */
    fun start(hasCachedFrame: Boolean, nowNs: Long): Boolean {
        active = true
        cachedFrameAvailable = hasCachedFrame
        lastFrameSentNs = if (hasCachedFrame) nowNs else null
        return hasCachedFrame
    }

    /** Disattiva completamente il keepalive quando il mirroring viene spento. */
    fun stop() {
        active = false
        cachedFrameAvailable = false
        lastFrameSentNs = null
    }

    /** Registra un frame reale e rinvia il keepalive finché la sorgente resta attiva. */
    fun onRealFrame(nowNs: Long) {
        if (!active) return
        cachedFrameAvailable = true
        lastFrameSentNs = nowNs
    }

    /**
     * Restituisce true solo dopo un intervallo completo senza frame reali o keepalive.
     */
    fun keepAliveDue(nowNs: Long): Boolean {
        if (!active || !cachedFrameAvailable) return false
        val previousFrameNs = lastFrameSentNs ?: return false
        if (nowNs - previousFrameNs < intervalNs) return false
        lastFrameSentNs = nowNs
        return true
    }
}
