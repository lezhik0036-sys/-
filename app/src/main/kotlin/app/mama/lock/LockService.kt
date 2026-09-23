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
        /** True while the phone is ringing / in a call: the overlay steps aside. */
        @Volatile var inCall = false
            private set

        @Volatile private var dialPassUntil = 0L

        /** Short window after tapping "call" in which the dialer may be on screen. */
        val dialPassActive: Boolean get() = SystemClock.elapsedRealtime() < dialPassUntil

        val callPassActive: Boolean get() = inCall || dialPassActive

        private const val DIAL_PASS_MS = 60_000L
        private const val SYNC_EVERY_TICKS = 15

        fun beginDialPass() {
            dialPassUntil = SystemClock.elapsedRealtime() + DIAL_PASS_MS
        }

        fun endDialPass() {
            dialPassUntil = 0
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

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var overlay: LockOverlay
    private var ticks = 0
    private var state: LockState = LockState.Free

    private val tick = object : Runnable {
        override fun run() {
            ticks++
            val wasInCall = inCall
            inCall = Calls.isInCall(this@LockService)
            if (wasInCall && !inCall) endDialPass()
            if (ticks % SYNC_EVERY_TICKS == 0) refresh() else render()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = LockOverlay(this)
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

    private fun render() {
        val s = state
        val session = Mama.session(this)
        val now = Mama.trustedNow(this)
        when {
            s is LockState.Locked && session != null -> {
                if (!s.endsAt.isAfter(now)) {
                    refresh()
                    return
                }
                if (callPassActive || !Settings.canDrawOverlays(this)) {
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
