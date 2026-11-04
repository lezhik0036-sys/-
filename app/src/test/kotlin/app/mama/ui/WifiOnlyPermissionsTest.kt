package app.mama.ui

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Wi-Fi-only tablet onboarding must not require unavailable phone services. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiOnlyPermissionsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `Wi-Fi-only device has no telephone permission or call role prerequisite`() {
        val pm = shadowOf(context.packageManager)
        pm.setSystemFeature(PackageManager.FEATURE_TELEPHONY, false)
        try {
            assertFalse(Requirement.deviceHasTelephony(context))
            assertTrue(Requirement.SMS.isGranted(context))
            assertTrue(Requirement.CALL_CONTROL.isGranted(context))
            assertTrue(Requirement.SMS !in Requirement.missingRequired(context))
            assertTrue(Requirement.CALL_CONTROL !in Requirement.missingRequired(context))
        } finally {
            pm.setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        }
    }

    @Test
    fun `telephony hardware is still guarded even when SIM is absent`() {
        val pm = shadowOf(context.packageManager)
        pm.setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        try {
            assertTrue(Requirement.deviceHasTelephony(context))
            // No test runtime permission grants. A handset with a cellular
            // modem cannot silently bypass phone permissions merely by
            // removing the SIM.
            assertFalse(Requirement.SMS.isGranted(context))
        } finally {
            pm.setSystemFeature(PackageManager.FEATURE_TELEPHONY, false)
        }
    }
}
