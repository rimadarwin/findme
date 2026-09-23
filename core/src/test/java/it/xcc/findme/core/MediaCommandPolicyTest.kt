/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Test della policy di feedback dei comandi multimediali.
 * @modified 23.09.2026 - MDS | Aggiunti test per destinazione, conferma e timeout.
 */
package it.xcc.findme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCommandPolicyTest {
    /**
     * Verifica la traduzione dei comandi di avvio e arresto.
     */
    @Test
    fun `stream commands expose their desired target`() {
        assertEquals(
            MediaCommandTarget(MediaStreamKind.VIDEO, true),
            MediaCommandPolicy.target(CommandType.START_VIDEO),
        )
        assertEquals(
            MediaCommandTarget(MediaStreamKind.AUDIO, false),
            MediaCommandPolicy.target(CommandType.STOP_AUDIO),
        )
        assertEquals(
            MediaCommandTarget(MediaStreamKind.SCREEN, true),
            MediaCommandPolicy.target(CommandType.START_SCREEN),
        )
        assertNull(MediaCommandPolicy.target(CommandType.SWITCH_CAMERA))
    }

    /**
     * Verifica che la conferma dipenda dallo stato effettivo dello stream corretto.
     */
    @Test
    fun `feedback is confirmed only by matching remote status`() {
        val feedback = feedback(stream = MediaStreamKind.VIDEO, targetEnabled = true)
        val inactive = status(cameraStreaming = false)
        val active = status(cameraStreaming = true)

        assertFalse(MediaCommandPolicy.isConfirmed(feedback, inactive))
        assertTrue(MediaCommandPolicy.isConfirmed(feedback, active))
    }

    /**
     * Verifica il valore ottimistico e il ripristino dello stato reale dopo un errore.
     */
    @Test
    fun `displayed value is optimistic only while request is active`() {
        val awaiting = feedback(targetEnabled = true)
        val failed = awaiting.copy(phase = MediaCommandPhase.FAILED)

        assertTrue(MediaCommandPolicy.displayedValue(actual = false, awaiting))
        assertFalse(MediaCommandPolicy.displayedValue(actual = false, failed))
    }

    /**
     * Verifica che il timeout scatti esattamente dopo venti secondi.
     */
    @Test
    fun `confirmation timeout uses configured boundary`() {
        val feedback = feedback(startedElapsedMs = 1_000L)

        assertFalse(MediaCommandPolicy.hasTimedOut(feedback, 20_999L))
        assertTrue(MediaCommandPolicy.hasTimedOut(feedback, 21_000L))
    }

    /**
     * Crea un feedback standard per isolare i casi di test.
     */
    private fun feedback(
        stream: MediaStreamKind = MediaStreamKind.VIDEO,
        targetEnabled: Boolean = true,
        startedElapsedMs: Long = 1_000L,
    ) = MediaCommandFeedback(
        requestId = 1L,
        deviceId = "transmitter",
        stream = stream,
        targetEnabled = targetEnabled,
        phase = MediaCommandPhase.AWAITING_CONFIRMATION,
        startedElapsedMs = startedElapsedMs,
    )

    /**
     * Crea uno stato trasmettitore minimo con il valore video richiesto.
     */
    private fun status(cameraStreaming: Boolean) = DeviceStatus(
        deviceId = "transmitter",
        isMonitoring = true,
        cameraStreaming = cameraStreaming,
    )
}
