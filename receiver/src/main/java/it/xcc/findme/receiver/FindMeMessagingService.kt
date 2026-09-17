package it.xcc.findme.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import it.xcc.findme.core.AppConfig
import it.xcc.findme.core.DeviceIdentity
import it.xcc.findme.core.FindMeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FindMeMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        if (!AppConfig.isConfigured) return
        scope.launch {
            runCatching {
                val identity = DeviceIdentity(applicationContext)
                FindMeRepository().apply {
                    ensureAuthenticated()
                    registerReceiverPushToken(identity.id, installationId)
                }
            }.onSuccess {
                Log.i(TAG, "Registrazione FCM completata")
            }.onFailure {
                Log.e(TAG, "Registrazione FCM su Supabase non riuscita", it)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (message.data["type"] != MESSAGE_TYPE_GEOFENCE_EXIT) return
        val transmitterId = message.data["device_id"] ?: return
        val deviceName = message.data["device_name"] ?: "Dispositivo"
        val distance = message.data["distance_m"]?.toDoubleOrNull()
        val radius = message.data["radius_m"]?.toIntOrNull()
        GeofenceNotifications.showExitAlert(
            context = applicationContext,
            transmitterId = transmitterId,
            deviceName = deviceName,
            distanceM = distance,
            radiusM = radius,
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FindMeFCM"
        const val EXTRA_DEVICE_ID = "geofence_device_id"
        const val MESSAGE_TYPE_GEOFENCE_EXIT = "geofence_exit"
    }
}

object GeofenceNotifications {
    private const val CHANNEL_ID = "findme_geofence_alerts"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Avvisi area FindMe",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Avvisa quando un trasmettitore esce dall’area configurata"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun showExitAlert(
        context: Context,
        transmitterId: String,
        deviceName: String,
        distanceM: Double?,
        radiusM: Int?,
    ) {
        createChannel(context)
        val intent = Intent(context, ReceiverActivity::class.java).apply {
            putExtra(FindMeMessagingService.EXTRA_DEVICE_ID, transmitterId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            transmitterId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val detail = if (distanceM != null && radiusM != null) {
            "Distanza ${distanceM.toInt()} m dal centro (raggio $radiusM m)."
        } else {
            "Il dispositivo ha superato il raggio configurato."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("$deviceName è uscito dall’area")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(transmitterId.hashCode(), notification)
    }
}
