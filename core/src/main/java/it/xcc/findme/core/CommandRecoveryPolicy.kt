package it.xcc.findme.core

object CommandRecoveryPolicy {
    fun compact(commands: List<DeviceCommand>): List<DeviceCommand> {
        val latestByFamily = mutableMapOf<String, DeviceCommand>()
        val passthrough = mutableListOf<DeviceCommand>()
        commands.sortedBy { it.id ?: Long.MAX_VALUE }.forEach { command ->
            val family = family(command.command)
            if (family == null) {
                passthrough += command
            } else {
                latestByFamily[family] = command
            }
        }
        return (passthrough + latestByFamily.values).sortedBy { it.id ?: Long.MAX_VALUE }
    }

    private fun family(command: CommandType): String? = when (command) {
        CommandType.START_VIDEO,
        CommandType.STOP_VIDEO,
        -> "video"
        CommandType.START_AUDIO,
        CommandType.STOP_AUDIO,
        -> "audio"
        CommandType.START_SCREEN,
        CommandType.STOP_SCREEN,
        -> "screen"
        CommandType.START_MONITORING,
        CommandType.STOP_MONITORING,
        -> "monitoring"
        CommandType.SWITCH_CAMERA -> null
    }
}
