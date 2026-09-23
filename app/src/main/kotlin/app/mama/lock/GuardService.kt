package app.mama.lock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import app.mama.platform.Calls
import app.mama.platform.Mama

/**
 * Second line of defence behind the overlay. While locked it closes the
 * notification shade / power menu and sends any other app back home, which
 * also keeps Settings (force stop, uninstall, revoking permissions) out of reach.
 * Outside a lock it does nothing. It never reads window content.
 */
class GuardService : AccessibilityService() {

    private val homePackage: String? by lazy {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        )?.activityInfo?.packageName
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Mama.sync(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (!Mama.state(this).isLocked) return
        if (pkg == packageName) return
        // Incoming/ongoing call: everything call-related must keep working.
        if (LockService.inCall) return
        // Keyguard / PIN bouncer: never interfere, emergency calls live there too.
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return

        if (LockService.dialPassActive) {
            if (pkg in Calls.callPackages(this) || pkg == SYSTEM_UI) return
            LockService.endDialPass()
        }
        when (pkg) {
            SYSTEM_UI -> performGlobalAction(GLOBAL_ACTION_BACK)
            homePackage -> Unit // the overlay covers the launcher and recents
            else -> performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }
}
