package app.mama.lock

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import app.mama.core.LockState
import app.mama.platform.Alarms
import app.mama.platform.Calls
import app.mama.platform.Mama
import app.mama.platform.Notifications
import app.mama.ui.Texts
import java.time.Duration

/**
 * Keeps the lock alive: runs in the foreground while a session is ACTIVE,
 * shows the full-screen overlay while Locked, and re-checks state every
 * second (calls) and every 15 seconds (time, via [Mama.syncFromService]).
 */
class LockService : Service() {

    companion object {
        /** True while the phone is ringing / in a call. */
        @Volatile var inCall = false
            private set

        /** What the guard last saw in front (see [Calls.classify]). */
        @Volatile var foreground: Calls.Screen = Calls.Screen.OTHER
            private set

        @Volatile private var dialPassUntil = 0L
        @Volatile private var dialPassEmergency = false

        /** Short window after tapping "call" on the lock screen, until the call starts. */
        val dialPassActive: Boolean get() = SystemClock.elapsedRealtime() < dialPassUntil

        /**
         * Whether the overlay may step aside for what is in front right now.
         * Only call screens qualify; a call alone never unlocks the phone —
         * minimise the call screen and the lock is back.
         */
        val overlayMayStepAside: Boolean
            get() = when (foreground) {
                Calls.Screen.EMERGENCY -> true
                Calls.Screen.IN_CALL -> inCall || dialPassActive
                // Only when the emergency dialer is unavailable and 112 was opened in the regular one.
                Calls.Screen.DIALER -> dialPassActive && dialPassEmergency
                Calls.Screen.OTHER -> false
            }

        @Volatile private var instance: LockService? = null

        private const val DIAL_PASS_MS = 60_000L
        private const val SYNC_EVERY_TICKS = 15

        fun beginDialPass(emergency: Boolean) {
            dialPassEmergency = emergency
            dialPassUntil = SystemClock.elapsedRealtime() + DIAL_PASS_MS
        }

        fun endDialPass() {
            dialPassUntil = 0
            dialPassEmergency = false
        }

        /** Called by the guard on every foreground change. */
        fun onForeground(screen: Calls.Screen) {
            foreground = screen
            instance?.let { it.handler.post { it.render() } }
        }

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, LockService::class.java))
            } catch (e: IllegalStateException) {
                // Background start refused (Android 12+). An exact alarm is an
                // allowed trigger, so retry from one in a few seconds.
                Alarms.retrySoon(context)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LockService::class.java))
        }
    }

    internal val handler = Handler(Looper.getMainLooper())
    private lateinit var overlay: LockOverlay
    private var ticks = 0
    private var state: LockState = LockState.Free

    private val tick = object : Runnable {
        override fun run() {
            ticks++
            val wasInCall = inCall
            inCall = Calls.isInCall(this@LockService)
            if (inCall && !wasInCall) endDialPass()
            // Keep closing the shade / quick settings / power menu while locked.
            if (state is LockState.Locked && !overlayMayStepAside) GuardService.enforceNow()
            if (ticks % SYNC_EVERY_TICKS == 0) refresh() else render()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = LockOverlay(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground("Блокировка активна")
        refresh()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1_000)
        // If the system kills us, come back and re-apply the lock.
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        overlay.hide()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun refresh() {
        state = Mama.syncFromService(this).state
        if (state !is LockState.Locked && state !is LockState.EmergencyPass) {
            overlay.hide()
            stopSelf()
            return
        }
        render()
    }

    internal fun render() {
        val s = state
        val session = Mama.session(this)
        val now = Mama.trustedNow(this)
        when {
            s is LockState.Locked && session != null -> {
                if (!s.endsAt.isAfter(now)) {
                    refresh()
                    return
                }
                if (overlayMayStepAside || !Settings.canDrawOverlays(this)) {
                    overlay.hide()
                } else {
                    overlay.show(session, now)
                }
                updateNotification("Блокировка до ${Texts.time(s.endsAt, session.plan.zone)}")
            }
            s is LockState.EmergencyPass && session != null -> {
                overlay.hide()
                if (!s.until.isAfter(now)) {
                    refresh()
                    return
                }
                updateNotification(
                    "Экстренный доступ: осталось ${Texts.duration(Duration.between(now, s.until))}",
                )
            }
            else -> overlay.hide()
        }
    }

    /** Called by the overlay after a user action changed the state. */
    fun onStateChanged() {
        refresh()
    }

    private var lastNotificationText: String? = null

    private fun updateNotification(text: String) {
        if (text == lastNotificationText) return
        goForeground(text)
    }

    private fun goForeground(text: String) {
        lastNotificationText = text
        val notification = Notifications.lock(this, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifications.LOCK_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.LOCK_ID, notification)
        }
    }
}
