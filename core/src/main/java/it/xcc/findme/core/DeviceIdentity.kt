package it.xcc.findme.core

import android.content.Context
import java.util.UUID

class DeviceIdentity(context: Context) {
    private val preferences = context.getSharedPreferences("findme_device", Context.MODE_PRIVATE)

    val id: String
        get() = preferences.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_ID, it).apply()
        }

    var name: String
        get() = preferences.getString(KEY_NAME, null) ?: android.os.Build.MODEL
        set(value) = preferences.edit().putString(KEY_NAME, value).apply()

    var monitoringEnabled: Boolean
        get() = preferences.getBoolean(KEY_MONITORING, false)
        set(value) = preferences.edit().putBoolean(KEY_MONITORING, value).apply()

    var provisionedReceiverCode: String?
        get() = preferences.getString(KEY_PROVISIONED_RECEIVER, null)
        set(value) = preferences.edit().putString(KEY_PROVISIONED_RECEIVER, value).apply()

    private companion object {
        const val KEY_ID = "device_id"
        const val KEY_NAME = "device_name"
        const val KEY_MONITORING = "monitoring_enabled"
        const val KEY_PROVISIONED_RECEIVER = "provisioned_receiver_code"
    }
}
