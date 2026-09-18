package it.xcc.findme.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationHistoryDeletionPolicyTest {
    @Test
    fun `selection requires at least one associated transmitter`() {
        val associated = setOf("transmitter-a", "transmitter-b")

        assertFalse(LocationHistoryDeletionPolicy.isValidSelection(associated, emptySet()))
        assertTrue(
            LocationHistoryDeletionPolicy.isValidSelection(
                associated,
                setOf("transmitter-a", "transmitter-b"),
            ),
        )
    }

    @Test
    fun `selection rejects transmitters not associated with receiver`() {
        assertFalse(
            LocationHistoryDeletionPolicy.isValidSelection(
                associatedDeviceIds = setOf("transmitter-a"),
                selectedDeviceIds = setOf("transmitter-a", "transmitter-foreign"),
            ),
        )
    }
}
