package app.mama.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the real Samsung setup deadlock (2026-11-04):
 * SEND_SMS was deliberately removed from AndroidManifest but Requirement.SMS
 * still demanded that undeclared permission, which Android can never grant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhonePermissionSetupTest {
    @Test
    fun `phone setup requests only declared permissions and never SEND_SMS`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS,
        )
        val declared = info.requestedPermissions.orEmpty().toSet()
        val requested = Requirement.SMS.runtimePermissions.toSet()

        assertFalse("The app must not send SMS itself", Manifest.permission.SEND_SMS in declared)
        assertFalse("Onboarding must not request undeclared SEND_SMS", Manifest.permission.SEND_SMS in requested)
        assertTrue("Every runtime permission must be present in AndroidManifest", declared.containsAll(requested))
        assertEquals(
            setOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ANSWER_PHONE_CALLS,
            ), requested,
        )
        assertEquals("Телефон и звонки", Requirement.SMS.title)
    }

    @Test
    fun `all required permissions can actually be reported granted`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadow = org.robolectric.Shadows.shadowOf(
            context as android.app.Application
        )
        shadow.grantPermissions(*Requirement.SMS.runtimePermissions)
        assertTrue("Phone requirement must become satisfiable", Requirement.SMS.isGranted(context))
    }
}
