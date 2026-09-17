package it.xcc.findme.transmitter

import android.Manifest
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.content.ContextCompat
import it.xcc.findme.core.DeviceIdentity

class FindMeDeviceAdminReceiver : DeviceAdminReceiver() {
    @Suppress("DEPRECATION")
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        val extras = intent.getParcelableExtra<PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
        )
        extras
            ?.getString(PROVISIONING_RECEIVER_CODE)
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.length == 10 }
            ?.let { DeviceIdentity(context).provisionedReceiverCode = it }
    }

    private companion object {
        const val PROVISIONING_RECEIVER_CODE = "findme_receiver_code"
    }
}

object DeviceOwnerSupport {
    fun isDeviceOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            .isDeviceOwnerApp(context.packageName)

    fun grantMonitoringPermissions(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, FindMeDeviceAdminReceiver::class.java)
        listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).forEach { permission ->
            manager.setPermissionGrantState(
                admin,
                context.packageName,
                permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }
        return true
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
            )
        ) return
        if (!DeviceIdentity(context).monitoringEnabled) return
        if (!DeviceOwnerSupport.isDeviceOwner(context)) return
        DeviceOwnerSupport.grantMonitoringPermissions(context)
        ContextCompat.startForegroundService(context, MonitoringService.intent(context))
    }
}
