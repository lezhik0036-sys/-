package app.mama.platform

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.time.Instant

/** Platform clocks fed into [app.mama.core.ClockGuard]. */
object DeviceClock {
    fun wallNow(): Instant = Instant.ofEpochMilli(System.currentTimeMillis())

    /** Monotonic, counts deep sleep, cannot be changed by the user. */
    fun elapsedMs(): Long = SystemClock.elapsedRealtime()

    @Volatile private var cachedBootId: String? = null

    /** Changes on every boot; constant for the life of this process. */
    fun bootId(context: Context): String =
        cachedBootId ?: readBootId(context).also { cachedBootId = it }

    private fun readBootId(context: Context): String {
        val count = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (count >= 0) return "boot-$count"
        // Fallback: approximate boot moment, rounded to 10 minutes.
        val bootWall = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 600_000
        return "approx-$bootWall"
    }
}
