package it.xcc.findme.core

object LocationHistoryDeletionPolicy {
    fun isValidSelection(
        associatedDeviceIds: Set<String>,
        selectedDeviceIds: Set<String>,
    ): Boolean = selectedDeviceIds.isNotEmpty() &&
        associatedDeviceIds.containsAll(selectedDeviceIds)
}
