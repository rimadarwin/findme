/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Identità locale del dispositivo e cache delle impostazioni tracking.
 * @modified 29.09.2026 - MDS | Conservati tracking persistente e retry geofence nei riavvii offline.
 * @modified 23.09.2026 - MDS | Aggiornato il fallback del polling comandi a cinque secondi.
 */
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

    var screenProjectionEverAuthorized: Boolean
        get() = preferences.getBoolean(KEY_SCREEN_PROJECTION_AUTHORIZED, false)
        set(value) = preferences.edit().putBoolean(KEY_SCREEN_PROJECTION_AUTHORIZED, value).apply()

    var screenProjectionOnboardingAttempted: Boolean
        get() = preferences.getBoolean(KEY_SCREEN_PROJECTION_ONBOARDING, false)
        set(value) = preferences.edit().putBoolean(KEY_SCREEN_PROJECTION_ONBOARDING, value).apply()

    /**
     * Ricostruisce l'ultima configurazione tracking disponibile localmente.
     */
    fun cachedTrackingState(): TrackingRuntimeState? {
        val receiverId = preferences.getString(KEY_TRACKING_RECEIVER_ID, null) ?: return null
        return TrackingRuntimeState(
            settings = ReceiverTrackingSettings(
                receiverId = receiverId,
                offlineLocationIntervalSec = preferences.getInt(KEY_TRACKING_OFFLINE, 60),
                onlineLocationIntervalSec = preferences.getInt(KEY_TRACKING_ONLINE, 10),
                historyMultiplier = preferences.getInt(KEY_TRACKING_HISTORY_MULTIPLIER, 2),
                onlyMovement = preferences.getBoolean(KEY_TRACKING_ONLY_MOVEMENT, true),
                heartbeatIntervalSec = preferences.getInt(KEY_TRACKING_HEARTBEAT, 60),
                commandPollIntervalSec = preferences.getInt(KEY_COMMAND_POLL_INTERVAL, 5),
                geofenceRadiusM = preferences.getInt(KEY_GEOFENCE_RADIUS, 100),
            ),
            relationship = ReceiverTransmitter(
                receiverId = receiverId,
                transmitterId = id,
                liveTrackingUntil = preferences.getString(KEY_TRACKING_LIVE_UNTIL, null),
                liveTrackingPersistent = preferences.getBoolean(
                    KEY_TRACKING_LIVE_PERSISTENT,
                    false,
                ),
                liveHistory = preferences.getBoolean(KEY_TRACKING_LIVE_HISTORY, false),
                geofenceEnabled = preferences.getBoolean(KEY_GEOFENCE_ENABLED, false),
                geofenceNotificationPending = preferences.getBoolean(
                    KEY_GEOFENCE_NOTIFICATION_PENDING,
                    false,
                ),
            ),
        )
    }

    /**
     * Salva la configurazione tracking per l'avvio senza rete.
     */
    fun cacheTrackingState(state: TrackingRuntimeState) {
        preferences.edit()
            .putString(KEY_TRACKING_RECEIVER_ID, state.settings.receiverId)
            .putInt(KEY_TRACKING_OFFLINE, state.settings.offlineLocationIntervalSec)
            .putInt(KEY_TRACKING_ONLINE, state.settings.onlineLocationIntervalSec)
            .putInt(KEY_TRACKING_HISTORY_MULTIPLIER, state.settings.historyMultiplier)
            .putBoolean(KEY_TRACKING_ONLY_MOVEMENT, state.settings.onlyMovement)
            .putInt(KEY_TRACKING_HEARTBEAT, state.settings.heartbeatIntervalSec)
            .putInt(KEY_COMMAND_POLL_INTERVAL, state.settings.commandPollIntervalSec)
            .putInt(KEY_GEOFENCE_RADIUS, state.settings.geofenceRadiusM)
            .putString(KEY_TRACKING_LIVE_UNTIL, state.relationship.liveTrackingUntil)
            .putBoolean(
                KEY_TRACKING_LIVE_PERSISTENT,
                state.relationship.liveTrackingPersistent,
            )
            .putBoolean(KEY_TRACKING_LIVE_HISTORY, state.relationship.liveHistory)
            .putBoolean(KEY_GEOFENCE_ENABLED, state.relationship.geofenceEnabled)
            .putBoolean(
                KEY_GEOFENCE_NOTIFICATION_PENDING,
                state.relationship.geofenceNotificationPending,
            )
            .apply()
    }

    private companion object {
        const val KEY_ID = "device_id"
        const val KEY_NAME = "device_name"
        const val KEY_MONITORING = "monitoring_enabled"
        const val KEY_PROVISIONED_RECEIVER = "provisioned_receiver_code"
        const val KEY_SCREEN_PROJECTION_AUTHORIZED = "screen_projection_ever_authorized"
        const val KEY_SCREEN_PROJECTION_ONBOARDING = "screen_projection_onboarding_attempted"
        const val KEY_TRACKING_RECEIVER_ID = "tracking_receiver_id"
        const val KEY_TRACKING_OFFLINE = "tracking_offline_sec"
        const val KEY_TRACKING_ONLINE = "tracking_online_sec"
        const val KEY_TRACKING_HISTORY_MULTIPLIER = "tracking_history_multiplier"
        const val KEY_TRACKING_ONLY_MOVEMENT = "tracking_only_movement"
        const val KEY_TRACKING_HEARTBEAT = "tracking_heartbeat_sec"
        const val KEY_COMMAND_POLL_INTERVAL = "command_poll_interval_sec"
        const val KEY_TRACKING_LIVE_UNTIL = "tracking_live_until"
        const val KEY_TRACKING_LIVE_PERSISTENT = "tracking_live_persistent"
        const val KEY_TRACKING_LIVE_HISTORY = "tracking_live_history"
        const val KEY_GEOFENCE_RADIUS = "geofence_radius_m"
        const val KEY_GEOFENCE_ENABLED = "geofence_enabled"
        const val KEY_GEOFENCE_NOTIFICATION_PENDING = "geofence_notification_pending"
    }
}
