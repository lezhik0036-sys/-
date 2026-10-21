package app.mama

import android.app.Application
import app.mama.platform.Mama
import app.mama.platform.Notifications
import app.mama.trustedexit.DeviceChallengeVault
import app.mama.trustedexit.FakeTrustedExitBackend
import app.mama.trustedexit.TrustedExit

class MamaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        // The Trusted Exit backend is simulated for now (NOT PRODUCTION SECURE); it is
        // backed by device-protected storage so a reboot keeps the challenge and the
        // attempt count. A real HTTPS backend replaces this line with no other change.
        TrustedExit.backend = FakeTrustedExitBackend(vault = DeviceChallengeVault(this))
        // Any process start (including after the system killed us) re-applies the lock.
        Mama.sync(this)
    }
}
