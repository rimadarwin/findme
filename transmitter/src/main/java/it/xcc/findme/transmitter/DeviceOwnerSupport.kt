package it.xcc.findme.transmitter

import android.Manifest
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import it.xcc.findme.core.DeviceIdentity

class FindMeDeviceAdminReceiver : DeviceAdminReceiver() {
    @Suppress("DEPRECATION")
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        ProvisioningPolicy.persistReceiverCode(context, intent)
        DeviceOwnerSupport.grantMonitoringPermissions(context)
        Log.i(TAG, "Legacy Device Owner provisioning completed")
    }

    private companion object {
        const val TAG = "FindMeProvisioning"
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
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        var allGranted = true
        permissions.forEach { permission ->
            val granted = runCatching {
                manager.setPermissionGrantState(
                    admin,
                    context.packageName,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }.onFailure { error ->
                Log.e(TAG, "Unable to grant provisioning permission $permission", error)
            }.getOrDefault(false)
            allGranted = allGranted && granted
            Log.d(TAG, "Provisioning permission $permission granted=$granted")
        }
        return allGranted
    }

    private const val TAG = "FindMeProvisioning"
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
