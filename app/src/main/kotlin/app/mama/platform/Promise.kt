package app.mama.platform

import android.content.Context

/**
 * The user's own reason ("Зачем ты это делаешь?"), shown when they want to
 * leave early. Kept in device-protected storage so the lock screen can read
 * it right after a reboot, before the phone is unlocked.
 */
object Promise {
    const val MAX_LENGTH = 160
    private const val KEY = "promise"

    private fun prefs(context: Context) = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences("mama_ui", Context.MODE_PRIVATE)

    fun get(context: Context): String = runCatching { prefs(context).getString(KEY, "").orEmpty() }.getOrDefault("")

    fun set(context: Context, value: String) {
        prefs(context).edit().putString(KEY, value.trim().take(MAX_LENGTH)).apply()
    }
}
