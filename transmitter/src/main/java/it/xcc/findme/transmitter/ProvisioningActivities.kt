package it.xcc.findme.transmitter

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import it.xcc.findme.core.DeviceIdentity

/**
 * Selects the fully managed Device Owner mode requested by Android Setup Wizard.
 */
class GetProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = ProvisioningPolicy.selectFullyManagedMode(
            intent.getIntegerArrayListExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES,
            ),
        )
        if (mode == null) {
            Log.e(TAG, "Setup Wizard did not allow fully managed provisioning")
            setResult(RESULT_CANCELED)
        } else {
            Log.i(TAG, "Fully managed provisioning mode selected")
            setResult(
                RESULT_OK,
                Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, mode),
            )
        }
        finish()
    }

    private companion object {
        const val TAG = "FindMeProvisioning"
    }
}

/**
 * Applies the initial Device Owner policy before Android completes Setup Wizard.
 */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DeviceOwnerSupport.isDeviceOwner(this)) {
            Log.e(TAG, "Policy compliance requested before Device Owner activation")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        ProvisioningPolicy.persistReceiverCode(this, intent)
        val permissionsGranted = DeviceOwnerSupport.grantMonitoringPermissions(this)
        Log.i(TAG, "Provisioning policy applied; permissionsGranted=$permissionsGranted")
        setResult(RESULT_OK)
        finish()
    }

    private companion object {
        const val TAG = "FindMeProvisioning"
    }
}

object ProvisioningPolicy {
    private val receiverCodePattern = Regex("[A-Z0-9]{10}")

    fun selectFullyManagedMode(allowedModes: List<Int>?): Int? {
        val fullyManaged = DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
        return fullyManaged.takeIf { allowedModes == null || it in allowedModes }
    }

    fun normalizeReceiverCode(rawCode: String?): String? =
        rawCode
            ?.trim()
            ?.uppercase()
            ?.takeIf(receiverCodePattern::matches)

    fun persistReceiverCode(context: Context, intent: Intent): Boolean {
        val rawCode = adminExtras(intent)?.getString(PROVISIONING_RECEIVER_CODE)
        val receiverCode = normalizeReceiverCode(rawCode)
        if (receiverCode == null) {
            Log.w(TAG, "Provisioning extras contain no valid receiver code")
            return false
        }

        DeviceIdentity(context).provisionedReceiverCode = receiverCode
        Log.i(TAG, "Provisioned receiver code stored")
        return true
    }

    @Suppress("DEPRECATION")
    private fun adminExtras(intent: Intent): PersistableBundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                PersistableBundle::class.java,
            )
        } else {
            intent.getParcelableExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
            )
        }

    const val PROVISIONING_RECEIVER_CODE = "findme_receiver_code"
    private const val TAG = "FindMeProvisioning"
}
