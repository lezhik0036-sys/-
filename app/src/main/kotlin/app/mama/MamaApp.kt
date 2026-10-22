package app.mama

import android.app.Application
import app.mama.billing.FeatureFlags
import app.mama.platform.Mama
import app.mama.platform.Notifications
import app.mama.trustedexit.DebugCodeSmsService
import app.mama.trustedexit.DeviceChallengeVault
import app.mama.trustedexit.FakeTrustedExitBackend
import app.mama.trustedexit.SimulatedSmsService
import app.mama.trustedexit.TrustedExit

class MamaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        // The Trusted Exit backend is simulated for now (NOT PRODUCTION SECURE); it is
        // backed by device-protected storage so a reboot keeps the challenge and the
        // attempt count. A real HTTPS backend replaces this line with no other change.
        // TEST/DEBUG: with no real SMS, the delivery stashes the code so the lock screen
        // can show the TEST CODE — the guaranteed way out. Release uses the silent provider.
        TrustedExit.backend = FakeTrustedExitBackend(
            vault = DeviceChallengeVault(this),
            sms = if (FeatureFlags.TEST_MODE) DebugCodeSmsService else SimulatedSmsService,
        )
        // Any process start (including after the system killed us) re-applies the lock.
        Mama.sync(this)
    }
}
