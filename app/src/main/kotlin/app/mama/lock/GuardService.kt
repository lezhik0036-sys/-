package app.mama.lock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import app.mama.platform.Calls
import app.mama.platform.Mama

/**
 * Second line of defence behind the overlay. While locked it:
 * - tells [LockService] what is in front, so the overlay steps aside only for
 *   the in-call screen and the emergency dialer (never for "a call is going on");
 * - sends any other app home (the overlay covers home), including apps opened
 *   from the shade or over the lock screen;
 * - closes the notification shade, quick settings and power menu.
 * Outside a lock it does nothing. It looks at window types and sizes only.
 */
class GuardService : AccessibilityService() {

    companion object {
        @Volatile private var instance: GuardService? = null

        /** Called every second by the lock service while the overlay is up. */
        fun enforceNow() {
            instance?.closeSystemPanels(fromSystemUi = false)
        }

        /** Whether the guard is connected right now (shown on the lock screen). */
        val running: Boolean get() = instance != null

        private const val SYSTEM_UI = "com.android.systemui"
    }

    private val homePackage: String? by lazy {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        )?.activityInfo?.packageName
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Mama.sync(this)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                onWindowState(pkg, event.className?.toString())
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> closeSystemPanels(fromSystemUi = false)
        }
    }

    private fun onWindowState(pkg: String, className: String?) {
        if (pkg == SYSTEM_UI) {
            closeSystemPanels(fromSystemUi = true)
            return
        }
        if (pkg == packageName) {
            // Our own overlay/popups say nothing about what is in front.
            if (className?.startsWith("app.mama.ui") == true) LockService.onForeground(Calls.Screen.OTHER)
            return
        }
        val screen = Calls.classify(pkg, className)
        // Dialogs and popups inside the phone app keep the current classification.
        if (screen == Calls.Screen.DIALER && className?.startsWith("android.") == true) return
        LockService.onForeground(screen)

        if (!Mama.state(this).isLocked) return
        if (LockService.overlayMayStepAside) return
        if (pkg == homePackage) return // the overlay covers the launcher and recents
        if (screen == Calls.Screen.IN_CALL) return // under the overlay; the call keeps going
        // Anything else — another app, the dialer keypad, a minimised call's app
        // switch, an app launched over the lock screen — goes home.
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Closes the shade / quick settings / power menu during a lock. A window
     * change reported by System UI itself is taken as "the shade may be open"
     * even when the window-size check cannot see it (varies by vendor).
     */
    fun closeSystemPanels(fromSystemUi: Boolean) {
        if (!Mama.state(this).isLocked || LockService.overlayMayStepAside) return
        // Never fight the system lock screen: PIN entry and its emergency button live there.
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return
        val panelOpen = systemPanelOpen()
        if (!panelOpen && !fromSystemUi) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        if (panelOpen) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * A large system window is on screen. The status and navigation bars and
     * heads-up notifications are small; an expanded shade, quick settings or
     * the power menu are not.
     */
    private fun systemPanelOpen(): Boolean {
        val screenHeight = resources.displayMetrics.heightPixels
        val bounds = Rect()
        return windows.any { w ->
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) return@any false
            w.getBoundsInScreen(bounds)
            if (bounds.height() < screenHeight / 3) return@any false
            w.root?.packageName?.toString() != packageName
        }
    }

    override fun onInterrupt() = Unit
}
