package app.mama

import android.app.Application
import app.mama.billing.FeatureFlags
import app.mama.platform.Mama
import app.mama.platform.Notifications
import app.mama.trustedexit.DebugCodeSmsService
import app.mama.trustedexit.DeviceChallengeVault
import app.mama.trustedexit.FakeTrustedExitBackend
import app.mama.trustedexit.HttpTrustedExitBackend
import app.mama.trustedexit.TrustedExit

class MamaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        // Hard TEST/PROD split. There is NO automatic fallback to the fake in production.
        if (FeatureFlags.TEST_MODE) {
            // TEST/DEBUG only: the on-device fake, backed by device-protected storage so a
            // reboot keeps the challenge and the attempt count. The simulated delivery stashes
            // the code so the lock screen can show the guaranteed-exit TEST CODE. Never in release.
            TrustedExit.backend = FakeTrustedExitBackend(
                vault = DeviceChallengeVault(this),
                sms = DebugCodeSmsService,
            )
        } else {
            // PRODUCTION: the real server-authoritative backend over HTTPS. If it is
            // unreachable or unconfigured, Trusted Exit fails closed — the readiness gate
            // keeps a series from starting, and a code request/verify never bypasses the lock.
            // The base URL is configuration (not a secret); the SMS.RU key lives only on the server.
            val http = HttpTrustedExitBackend(BuildConfig.TRUSTED_EXIT_BASE_URL)
            TrustedExit.backend = http
            TrustedExit.readiness = { context -> http.isReady(context) }
        }
        // Any process start (including after the system killed us) re-applies the lock.
        Mama.sync(this)
    }
}
