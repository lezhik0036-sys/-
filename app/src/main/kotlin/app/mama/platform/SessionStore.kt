package app.mama.platform

import android.content.Context
import app.mama.core.Snapshot
import app.mama.core.StateCodec

/**
 * Persists MAMA state in device-protected storage: it is readable right after
 * a reboot, before the user unlocks the phone (Direct Boot), which is what
 * Reboot Recovery needs.
 */
class SessionStore(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("mama_state", Context.MODE_PRIVATE)

    fun load(): Snapshot {
        val map = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        return StateCodec.decode(map)
    }

    /** Writes synchronously, and only when something actually changed. */
    fun save(snapshot: Snapshot) {
        val encoded = StateCodec.encode(snapshot)
        val current = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        if (encoded == current) return
        val editor = prefs.edit().clear()
        encoded.forEach { (k, v) -> editor.putString(k, v) }
        editor.commit()
    }
}
