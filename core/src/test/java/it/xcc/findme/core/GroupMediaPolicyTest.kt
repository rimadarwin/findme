/**
 * @author Infinity
 * @description Test delle policy per la visualizzazione multimediale multipla.
 * @modified 01.10.2026 - Infinity | Prima implementazione.
 */
package it.xcc.findme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMediaPolicyTest {
    @Test
    fun `selection requires at least two associated devices`() {
        val associated = setOf("a", "b", "c")

        assertFalse(GroupMediaPolicy.isValidSelection(associated, emptySet()))
        assertFalse(GroupMediaPolicy.isValidSelection(associated, setOf("a")))
        assertTrue(GroupMediaPolicy.isValidSelection(associated, setOf("a", "b")))
        assertFalse(GroupMediaPolicy.isValidSelection(associated, setOf("a", "unknown")))
    }

    @Test
    fun `stream state distinguishes off partial and on`() {
        assertEquals(GroupStreamState.OFF, GroupMediaPolicy.streamState(emptyList()))
        assertEquals(GroupStreamState.OFF, GroupMediaPolicy.streamState(listOf(false, false)))
        assertEquals(GroupStreamState.PARTIAL, GroupMediaPolicy.streamState(listOf(true, false)))
        assertEquals(GroupStreamState.ON, GroupMediaPolicy.streamState(listOf(true, true)))
    }

    @Test
    fun `recording limit allows at most two distinct devices`() {
        assertTrue(GroupMediaPolicy.canStartRecording(emptySet(), "a"))
        assertTrue(GroupMediaPolicy.canStartRecording(setOf("a"), "b"))
        assertTrue(GroupMediaPolicy.canStartRecording(setOf("a", "b"), "a"))
        assertFalse(GroupMediaPolicy.canStartRecording(setOf("a", "b"), "c"))
    }

    @Test
    fun `fullscreen keeps at most two visible rows`() {
        assertEquals(1, GroupMediaPolicy.visibleGridRows(0))
        assertEquals(1, GroupMediaPolicy.visibleGridRows(1))
        assertEquals(1, GroupMediaPolicy.visibleGridRows(2))
        assertEquals(2, GroupMediaPolicy.visibleGridRows(3))
        assertEquals(2, GroupMediaPolicy.visibleGridRows(4))
        assertEquals(2, GroupMediaPolicy.visibleGridRows(5))
    }

    @Test
    fun `participant identity exposes only a valid transmitter uuid`() {
        val deviceId = "2a3c4e50-6172-4384-95a6-b7c8d9e0f123"

        assertEquals(
            deviceId,
            GroupMediaPolicy.transmitterDeviceId("transmitter-$deviceId"),
        )
        assertEquals(null, GroupMediaPolicy.transmitterDeviceId(null))
        assertEquals(null, GroupMediaPolicy.transmitterDeviceId("publish-$deviceId"))
        assertEquals(null, GroupMediaPolicy.transmitterDeviceId("transmitter-not-a-uuid"))
    }
}
