package app.mama.lock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Log
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
            try {
                instance?.closeSystemPanels(fromSystemUi = false)
            } catch (e: RuntimeException) {
                Log.e("MAMA", "Guard enforce failed", e)
            }
        }

        /** Whether the guard is connected right now (shown on the lock screen). */
        val running: Boolean get() = instance != null

        /** Diagnostics shown on the lock screen: System UI events seen / panels closed. */
        @Volatile var systemUiEvents = 0
            private set

        @Volatile var panelsClosed = 0
            private set

        @Volatile var keyguardSkips = 0
            private set

        @Volatile var keyguardPanelsClosed = 0
            private set

        /** Last System UI view ids that identified quick settings (diagnostics). */
        @Volatile var lastPanelIds = ""
            private set

        /** View ids (substrings) that exist only in expanded quick settings. */
        private val QUICK_SETTINGS_IDS = listOf("brightness", "quick_settings", "quick_panel", "quickpanel", "qs_")

        /** View ids of PIN / pattern entry and the emergency button: never interfere. */
        private val BOUNCER_IDS = listOf(
            "bouncer", "pin_view", "pinentry", "password", "pattern", "keyguard_pin", "keyguard_sim",
            "emergency", "digit_text", "key_enter",
        )

        private const val SYSTEM_UI = "com.android.systemui"
        private const val MAX_NODES = 600
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
        try {
            handle(event)
        } catch (e: RuntimeException) {
            // A crash here would unbind the guard for the rest of the lock.
            Log.e("MAMA", "Guard event failed", e)
        }
    }

    private fun handle(event: AccessibilityEvent) {
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
            systemUiEvents++
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
        if (pkg == homePackage && className?.contains("recent", ignoreCase = true) != true) {
            return // the overlay covers the launcher
        }
        if (screen == Calls.Screen.IN_CALL) return // under the overlay; the call keeps going
        // Anything else — another app, Recents, the dialer keypad, an app
        // launched over the lock screen — goes home.
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Keeps the lock surface the topmost thing on screen. Runs on every window
     * change and every second while locked:
     * - a large System UI window (shade, quick settings, power menu) is closed;
     * - an app window above the lock surface (e.g. Samsung's Recents, which is
     *   drawn over app overlays) is sent home;
     * - if the lock surface is missing from the screen although it should be up
     *   (the system hid it), home is pressed so it comes back.
     * A window change reported by System UI itself is taken as "the shade may
     * be open" even when the size check cannot see it (varies by vendor).
     */
    fun closeSystemPanels(fromSystemUi: Boolean) {
        if (!Mama.state(this).isLocked || LockService.overlayMayStepAside) return
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            // Over the system lock screen only expanded quick settings are closed.
            // PIN entry and the emergency button must keep working.
            keyguardSkips++
            if (quickSettingsOverKeyguard()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                keyguardPanelsClosed++
            }
            return
        }

        // The expanded shade / quick settings take input focus: the active
        // window then belongs to System UI. Works where size checks do not.
        val activePkg = rootInActiveWindow?.packageName?.toString()
            ?: windows.firstOrNull { it.isActive }?.root?.packageName?.toString()
        val systemUiActive = activePkg == SYSTEM_UI

        val all = windows
        val ourLayer = all.filter { it.root?.packageName?.toString() == packageName }.maxOfOrNull { it.layer }
        val screenHeight = resources.displayMetrics.heightPixels
        val bounds = Rect()
        var panelOpen = false
        var appAbove = false
        for (w in all) {
            val pkg = w.root?.packageName?.toString()
            if (pkg == packageName) continue
            w.getBoundsInScreen(bounds)
            if (bounds.height() < screenHeight / 3) continue
            when (w.type) {
                AccessibilityWindowInfo.TYPE_SYSTEM -> panelOpen = true
                AccessibilityWindowInfo.TYPE_APPLICATION ->
                    if (ourLayer != null && w.layer > ourLayer) appAbove = true
            }
        }

        val shadeOpen = panelOpen || systemUiActive
        if (shadeOpen || fromSystemUi) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }
        }
        if (shadeOpen) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            panelsClosed++
        }
        val overlayHidden = LockService.overlayShowing && ourLayer == null
        if (appAbove || overlayHidden) performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * True when System UI shows expanded quick settings on top of the system
     * lock screen and no PIN/pattern entry is visible. Looks at view ids only.
     */
    private fun quickSettingsOverKeyguard(): Boolean {
        val roots = windows.mapNotNull { it.root }.filter { it.packageName?.toString() == SYSTEM_UI }
        var quickSettings = false
        val found = mutableListOf<String>()
        val queue = ArrayDeque(roots)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            val id = node.viewIdResourceName?.substringAfter(":id/")?.lowercase()
            if (id != null && node.isVisibleToUser) {
                if (BOUNCER_IDS.any { it in id }) return false
                if (QUICK_SETTINGS_IDS.any { it in id }) {
                    quickSettings = true
                    if (found.size < 3 && id !in found) found += id
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        if (found.isNotEmpty()) lastPanelIds = found.joinToString(",")
        return quickSettings
    }

    override fun onInterrupt() = Unit
}
