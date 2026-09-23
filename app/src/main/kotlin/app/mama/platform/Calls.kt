package app.mama.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import app.mama.lock.LockService

/** Calls stay possible during a lock: the trusted contact and emergency numbers. */
object Calls {
    const val EMERGENCY_NUMBER = "112"

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

    /** Apps allowed on screen while a call from the lock screen is being made. */
    fun callPackages(context: Context): Set<String> {
        val tm = context.getSystemService(TelecomManager::class.java)
        return setOfNotNull(
            tm?.defaultDialerPackage,
            tm?.systemDialerPackage,
            "com.android.server.telecom",
            "com.android.phone",
        )
    }

    /** Opens the dialer with [number] filled in and briefly lifts the lock surface. */
    fun dial(context: Context, number: String) {
        LockService.beginDialPass()
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
