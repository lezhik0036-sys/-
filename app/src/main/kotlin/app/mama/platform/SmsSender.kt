package app.mama.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

object SmsSender {
    fun send(context: Context, phone: String, text: String): Boolean {
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java) ?: return false
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            sms.sendMultipartTextMessage(phone, null, sms.divideMessage(text), null, null)
            true
        } catch (e: Exception) {
            Log.e("MAMA", "SMS to trusted contact failed", e)
            false
        }
    }
}
