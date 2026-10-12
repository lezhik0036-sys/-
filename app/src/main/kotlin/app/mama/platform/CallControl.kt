package app.mama.platform

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import android.annotation.TargetApi
import app.mama.core.OutgoingCallRules

/**
 * "Контроль исходящих звонков" (Android's call redirection role, Android 10+).
 * During a lock only emergency calls and the trusted contact go through, so
 * the phone's dialer cannot be used to get around the lock. Outside a lock
 * MAMA does not touch calls at all.
 */
object CallControl {
    /** The role exists on this phone (Android 10+ and not removed by the vendor). */
    fun available(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching { context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_CALL_REDIRECTION) == true }
            .getOrDefault(false)
    }

    /** MAMA holds the role, or there is nothing to hold on this phone. */
    fun held(context: Context): Boolean {
        if (!available(context)) return true
        return runCatching { context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION) == true }
            .getOrDefault(false)
    }

    /** The system dialog that gives MAMA the role, or null where there is none. */
    fun requestIntent(context: Context): Intent? {
        if (!available(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { context.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_CALL_REDIRECTION) }
            .getOrNull()
    }

    const val BLOCKED_NOTICE = "Во время блокировки можно звонить только в экстренные службы и доверенному контакту."
}

/**
 * Android asks this service about every outgoing call that is not an
 * emergency call (Telecom places real emergency calls itself and never asks).
 * During a lock: the trusted contact goes through, anything else is
 * cancelled and the lock screen comes back. Not a failure, not a Trusted
 * Exit — just a call that did not happen.
 */
@TargetApi(Build.VERSION_CODES.Q)
class MamaCallRedirectionService : CallRedirectionService() {

    override fun onPlaceCall(handle: Uri, initialPhoneAccount: PhoneAccountHandle, allowInteractiveResponse: Boolean) {
        val number = handle.schemeSpecificPart.orEmpty()
        val decision = try {
            Mama.outgoingCall(this, number, isEmergency(number))
        } catch (e: RuntimeException) {
            // Without its own state MAMA cannot tell whether a lock runs: never break normal calls.
            Log.e("MAMA", "Call control failed, call placed unmodified", e)
            OutgoingCallRules.Decision.ALLOW
        }
        if (decision == OutgoingCallRules.Decision.ALLOW) {
            placeCallUnmodified()
            return
        }
        cancelCall()
        Log.i("MAMA", "Outgoing call cancelled: not an emergency number or the trusted contact")
        EmergencyCalls.onCallBlocked(this)
        runCatching { Toast.makeText(applicationContext, CallControl.BLOCKED_NOTICE, Toast.LENGTH_LONG).show() }
    }

    private fun isEmergency(number: String): Boolean? = try {
        getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(number)
    } catch (e: RuntimeException) {
        null
    }
}
