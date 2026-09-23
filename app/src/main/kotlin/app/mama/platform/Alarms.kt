package app.mama.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.mama.core.ClockGuard
import java.time.Instant

/** One wake-up at the next moment the lock state changes by itself. */
object Alarms {
    fun schedule(context: Context, target: Instant?, trustedNow: Instant) {
        val am = context.getSystemService(AlarmManager::class.java)!!
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (target == null) {
            am.cancel(pi)
            return
        }
        // Alarms run on the (possibly skewed) system clock; convert from trusted time.
        val wall = ClockGuard.wallTimeFor(target, trustedNow, DeviceClock.wallNow()).toEpochMilli()
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wall, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wall, pi)
        }
    }

    fun retrySoon(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)!!
        val pi = PendingIntent.getBroadcast(
            context, 1, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = System.currentTimeMillis() + RETRY_MS
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private const val RETRY_MS = 5_000L

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java)!!.canScheduleExactAlarms()
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Mama.sync(context)
    }
}

/**
 * Reboot Recovery and clock/zone/app-update events. LOCKED_BOOT_COMPLETED
 * arrives before the user unlocks the phone, BOOT_COMPLETED after; both
 * simply re-sync from persisted state.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Mama.sync(context)
    }
}
