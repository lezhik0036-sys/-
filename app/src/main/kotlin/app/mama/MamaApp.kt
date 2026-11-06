package app.mama

import android.app.Application
import app.mama.account.MamaAccount
import app.mama.billing.FeatureFlags
import app.mama.platform.Mama
import app.mama.platform.Notifications
import app.mama.trustedexit.DebugCodeSmsService
import app.mama.trustedexit.DeviceChallengeVault
import app.mama.trustedexit.FakeTrustedExitBackend
import app.mama.trustedexit.ReleaseTrustedExitDisabled
import app.mama.trustedexit.TrustedExit

class MamaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        // Make the real MAMA server session available app-wide (register/login over
        // HTTPS with the Keystore-protected device key). Light + fail-closed: with no
        // configured backend URL this is disabled and touches no network or Keystore.
        // The session is fetched lazily, off the main thread, by the Trusted Exit flow.
        MamaAccount.configure(this, BuildConfig.MAMA_BACKEND_BASE_URL)
        // Fail CLOSED: a production release must never accept locally forged
        // OTPs. The authenticated FastAPI ↔ Ktor network client is not yet
        // implemented/audited. Keep the local fake strictly in debug builds.
        // Until the real client is ready, no release Trusted Exit via SMS is
        // possible; do not distribute this placeholder as a finished product.
        TrustedExit.backend = if (FeatureFlags.TEST_MODE) {
            FakeTrustedExitBackend(
                vault = DeviceChallengeVault(this),
                sms = DebugCodeSmsService,
            )
        } else {
            ReleaseTrustedExitDisabled
        }
        // Any process start (including after the system killed us) re-applies the lock.
        Mama.sync(this)
    }
}
