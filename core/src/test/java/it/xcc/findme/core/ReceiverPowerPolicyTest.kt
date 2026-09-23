/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Test della protezione energetica delle sessioni del ricevitore.
 * @modified 23.09.2026 - MDS | Verificata la persistenza del tracking rapido al blocco.
 */
package it.xcc.findme.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverPowerPolicyTest {
    /**
     * Verifica che ogni attività remota rilevante abiliti la protezione.
     */
    @Test
    fun `streaming or recording enables power protection`() {
        assertFalse(ReceiverPowerPolicy.shouldProtectMedia(false, false, false, false, false, false))
        assertTrue(ReceiverPowerPolicy.shouldProtectMedia(true, false, false, false, false, false))
        assertTrue(ReceiverPowerPolicy.shouldProtectMedia(false, true, false, false, false, false))
        assertTrue(ReceiverPowerPolicy.shouldProtectMedia(false, false, true, false, false, false))
        assertTrue(ReceiverPowerPolicy.shouldProtectMedia(false, false, false, true, false, false))
        assertTrue(ReceiverPowerPolicy.shouldProtectMedia(false, false, false, false, true, false))
        assertTrue(ReceiverPowerPolicy.shouldProtectMedia(false, false, false, false, false, true))
        assertTrue(
            ReceiverPowerPolicy.shouldProtectMedia(
                false,
                false,
                false,
                false,
                false,
                false,
                fastTrackingActive = true,
            ),
        )
    }

    /**
     * Verifica che la sessione sopravviva soltanto a un vero blocco schermo.
     */
    @Test
    fun `stopped activity keeps active media only while screen is locked`() {
        assertTrue(
            ReceiverPowerPolicy.shouldKeepSessionWhenStopped(
                screenInteractive = false,
                mediaActive = true,
            ),
        )
        assertFalse(
            ReceiverPowerPolicy.shouldKeepSessionWhenStopped(
                screenInteractive = true,
                mediaActive = true,
            ),
        )
        assertFalse(
            ReceiverPowerPolicy.shouldKeepSessionWhenStopped(
                screenInteractive = false,
                mediaActive = false,
            ),
        )
    }
}
