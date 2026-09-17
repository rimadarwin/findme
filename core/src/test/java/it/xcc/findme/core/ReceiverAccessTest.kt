package it.xcc.findme.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiverAccessTest {
    @Test
    fun challengeResponseDoesNotRequireUnlockedField() {
        val response = Json.decodeFromString<ReceiverAccessResponse>(
            """
            {
              "receiver_id": "531667a9-6569-4b9d-b139-7ec08304e045",
              "receiver_name": "SM-S921B",
              "question": "Dove sei nato?"
            }
            """.trimIndent(),
        )

        assertEquals("Dove sei nato?", response.question)
        assertNull(response.unlocked)
    }
}
