/**
 * @author Infinity
 * @description Verifica validazione e normalizzazione delle credenziali di accesso.
 * @modified 01.10.2026 - Infinity | Prima copertura della policy.
 */
package it.xcc.findme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverAccessPolicyTest {
    @Test
    fun `normalization trims valid values`() {
        assertEquals(
            "Dove sono nato?" to "bubbu",
            ReceiverAccessPolicy.normalize("  Dove sono nato?  ", "  bubbu  "),
        )
    }

    @Test
    fun `empty values are rejected`() {
        assertFalse(ReceiverAccessPolicy.isValid("", "risposta"))
        assertFalse(ReceiverAccessPolicy.isValid("Domanda valida?", "   "))
        assertThrows(IllegalArgumentException::class.java) {
            ReceiverAccessPolicy.normalize("No", "risposta")
        }
    }

    @Test
    fun `valid values are accepted`() {
        assertTrue(ReceiverAccessPolicy.isValid("Domanda valida?", "risposta"))
    }
}
