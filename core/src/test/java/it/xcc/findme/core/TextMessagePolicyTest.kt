/**
 * @author Infinity
 * @description Verifica validazione testo e dimensionamento font overlay.
 * @modified 29.09.2026 - MDS | Prima copertura della policy messaggi.
 */
package it.xcc.findme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMessagePolicyTest {
    @Test
    fun `normalization trims valid text`() {
        assertEquals("Ciao", TextMessagePolicy.normalize("  Ciao  "))
    }

    @Test
    fun `empty and oversized messages are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TextMessagePolicy.normalize("   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextMessagePolicy.normalize("x".repeat(TextMessagePolicy.MAX_LENGTH + 1))
        }
    }

    @Test
    fun `font size decreases as message grows`() {
        val sizes = listOf(20, 80, 180, 400).map(TextMessagePolicy::fontSizeSp)

        assertTrue(sizes.zipWithNext().all { (first, second) -> first > second })
    }

    @Test
    fun `only dismissed and failed states are terminal`() {
        assertFalse(TextMessagePolicy.isTerminal(TextMessageStatus.PENDING))
        assertFalse(TextMessagePolicy.isTerminal(TextMessageStatus.WAITING_PERMISSION))
        assertFalse(TextMessagePolicy.isTerminal(TextMessageStatus.DISPLAYING))
        assertTrue(TextMessagePolicy.isTerminal(TextMessageStatus.DISMISSED))
        assertTrue(TextMessagePolicy.isTerminal(TextMessageStatus.FAILED))
    }
}
