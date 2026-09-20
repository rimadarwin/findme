package it.xcc.findme.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandRecoveryPolicyTest {
    @Test
    fun `compaction keeps latest state per stream and all camera switches`() {
        val compacted = CommandRecoveryPolicy.compact(
            listOf(
                command(1, CommandType.START_VIDEO),
                command(2, CommandType.START_AUDIO),
                command(3, CommandType.SWITCH_CAMERA),
                command(4, CommandType.STOP_VIDEO),
                command(5, CommandType.START_VIDEO),
                command(6, CommandType.STOP_AUDIO),
                command(7, CommandType.SWITCH_CAMERA),
            ),
        )

        assertEquals(
            listOf(
                3L to CommandType.SWITCH_CAMERA,
                5L to CommandType.START_VIDEO,
                6L to CommandType.STOP_AUDIO,
                7L to CommandType.SWITCH_CAMERA,
            ),
            compacted.map { it.id to it.command },
        )
    }

    private fun command(id: Long, type: CommandType) = DeviceCommand(
        id = id,
        deviceId = "transmitter",
        command = type,
    )
}
