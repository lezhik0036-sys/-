package app.mama

import android.app.Application
import app.mama.platform.Mama
import app.mama.platform.Notifications

class MamaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        // Any process start (including after the system killed us) re-applies the lock.
        Mama.sync(this)
    }
}
