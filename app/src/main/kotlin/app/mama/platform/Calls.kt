package app.mama.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import app.mama.lock.LockService

/**
 * Calls stay possible during a lock, but only these: the trusted contact
 * (dialled directly, no editable keypad), emergency numbers, and incoming calls.
 * The lock screen stays on top during every call and carries the call
 * controls itself (answer / hang up), so there is no call screen to escape through.
 */
object Calls {
    const val EMERGENCY_NUMBER = "112"

    /** What kind of screen is in front, as seen by the accessibility guard. */
    enum class Screen {
        /** The ongoing/incoming call screen. */
        IN_CALL,

        /** The system emergency dialer: can only call emergency numbers. */
        EMERGENCY,

        /** A regular dialer screen (keypad, contacts, recents): can call anyone. */
        DIALER,

        OTHER,
    }

    fun classify(pkg: String, className: String?): Screen {
        val cls = className.orEmpty().lowercase()
        val p = pkg.lowercase()
        val phoneApp = p in PHONE_PACKAGES || "dialer" in p || "incallui" in p || "telecom" in p ||
            p.endsWith(".phone") || p.endsWith(".contacts") || p.endsWith(".emergency")
        return when {
            !phoneApp -> Screen.OTHER
            "emergency" in cls -> Screen.EMERGENCY
            // Telecom only places/receives calls (e.g. the ACTION_CALL trampoline).
            "incallui" in p || "incall" in cls || p == "com.android.server.telecom" -> Screen.IN_CALL
            else -> Screen.DIALER
        }
    }

    enum class CallState { NONE, RINGING, ACTIVE }

    fun callState(context: Context): CallState {
        if (!isInCall(context)) return CallState.NONE
        val ringing = try {
            @Suppress("DEPRECATION")
            context.getSystemService(TelephonyManager::class.java)?.callState == TelephonyManager.CALL_STATE_RINGING
        } catch (e: SecurityException) {
            false
        }
        return if (ringing) CallState.RINGING else CallState.ACTIVE
    }

    @SuppressLint("MissingPermission")
    fun answer(context: Context) {
        if (!granted(context, Manifest.permission.ANSWER_PHONE_CALLS)) return
        try {
            @Suppress("DEPRECATION")
            context.getSystemService(TelecomManager::class.java)?.acceptRingingCall()
        } catch (e: SecurityException) {
            Log.w("MAMA", "Cannot answer", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun hangUp(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!granted(context, Manifest.permission.ANSWER_PHONE_CALLS)) return
        try {
            @Suppress("DEPRECATION")
            context.getSystemService(TelecomManager::class.java)?.endCall()
        } catch (e: SecurityException) {
            Log.w("MAMA", "Cannot hang up", e)
        }
    }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun isInCall(context: Context): Boolean {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            context.getSystemService(TelecomManager::class.java)?.isInCall == true
        } catch (e: SecurityException) {
            false
        }
    }

    /** Calls the trusted contact straight away: the number cannot be edited. */
    fun callContact(context: Context, number: String): Boolean {
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return start(context, Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
    }

    /**
     * Opens the system emergency dialer, which only calls emergency numbers.
     * Where it cannot be opened, falls back to the regular dialer with 112
     * filled in: emergency calls must never be blocked.
     */
    fun callEmergency(context: Context) {
        LockService.beginDialPass(emergency = true)
        val emergencyDialer = Intent("com.android.phone.EmergencyDialer.DIAL")
            .setData(Uri.parse("tel:$EMERGENCY_NUMBER"))
        if (start(context, emergencyDialer)) return
        start(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$EMERGENCY_NUMBER")))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: RuntimeException) {
        Log.w("MAMA", "Cannot start ${intent.action}", e)
        false
    }

    private val PHONE_PACKAGES = setOf(
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.android.incallui",
        "com.android.emergency",
    )
}
