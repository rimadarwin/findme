package it.xcc.findme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMessagePolicyTest {
    @Test
    fun `duration accepts only messages from one to sixty seconds`() {
        assertFalse(VoiceMessagePolicy.isValidDuration(999))
        assertTrue(VoiceMessagePolicy.isValidDuration(1_000))
        assertTrue(VoiceMessagePolicy.isValidDuration(60_000))
        assertFalse(VoiceMessagePolicy.isValidDuration(60_001))
    }

    @Test
    fun `storage path binds receiver transmitter and message`() {
        assertEquals(
            "receiver/transmitter/message.m4a",
            VoiceMessagePolicy.storagePath("receiver", "transmitter", "message"),
        )
    }

    @Test
    fun `volume levels map to bounded media stream values`() {
        assertEquals(4, VoiceMessagePolicy.streamVolume(15, VoiceMessageVolume.LOW))
        assertEquals(9, VoiceMessagePolicy.streamVolume(15, VoiceMessageVolume.MEDIUM))
        assertEquals(15, VoiceMessagePolicy.streamVolume(15, VoiceMessageVolume.HIGH))
        assertEquals(0, VoiceMessagePolicy.streamVolume(0, VoiceMessageVolume.HIGH))
    }
}
