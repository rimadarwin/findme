package it.xcc.findme.transmitter

import android.app.admin.DevicePolicyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProvisioningPolicyTest {
    @Test
    fun `fully managed mode is selected when allowed`() {
        assertEquals(
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
            ProvisioningPolicy.selectFullyManagedMode(
                listOf(
                    DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
                ),
            ),
        )
        assertEquals(
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
            ProvisioningPolicy.selectFullyManagedMode(null),
        )
    }

    @Test
    fun `unsupported provisioning modes are rejected`() {
        assertNull(
            ProvisioningPolicy.selectFullyManagedMode(
                listOf(DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE),
            ),
        )
    }

    @Test
    fun `receiver codes are normalized and validated`() {
        assertEquals("7E3D42DB25", ProvisioningPolicy.normalizeReceiverCode(" 7e3d42db25 "))
        assertNull(ProvisioningPolicy.normalizeReceiverCode("short"))
        assertNull(ProvisioningPolicy.normalizeReceiverCode("INVALID-QR"))
    }
}
