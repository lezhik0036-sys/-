package app.mama.platform

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Being an active device admin is what stops a one-tap uninstall. Turning it
 * off needs the Settings app, which the guard keeps closed during a lock.
 */
class MamaDeviceAdmin : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Без прав администратора MAMA нельзя будет защитить блокировку от удаления."
}
