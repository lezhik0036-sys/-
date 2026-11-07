package app.mama

import android.app.Application
import app.mama.account.MamaAccount
import app.mama.billing.FeatureFlags
import app.mama.platform.Mama
import app.mama.platform.Notifications
import app.mama.trustedexit.DebugCodeSmsService
import app.mama.trustedexit.DeviceChallengeVault
import app.mama.trustedexit.FakeTrustedExitBackend
import app.mama.trustedexit.MamaTrustedExit
import app.mama.trustedexit.ReleaseTrustedExitDisabled
import app.mama.trustedexit.TrustedExit
import app.mama.trustedexit.TrustedExitReadinessGate

class MamaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        // Make the real MAMA server session available app-wide (register/login over
        // HTTPS with the Keystore-protected device key). Light + fail-closed: with no
        // configured backend URL this is disabled and touches no network or Keystore.
        // The session is fetched lazily, off the main thread, by the Trusted Exit flow.
        MamaAccount.configure(this, BuildConfig.MAMA_BACKEND_BASE_URL)
        MamaTrustedExit.configure(this, BuildConfig.MAMA_BACKEND_BASE_URL)
        // Prefer the REAL, server-authoritative HTTPS client whenever a backend URL
        // is configured — in debug AND release — so a field-test build talks to the
        // real FastAPI/Ktor server, never a local imitation of codes. The deferred
        // backend builds lazily off the main thread and is fail-closed until a
        // session + provisioned series exist. No backend URL:
        //   * debug/TEST_MODE -> the strictly-isolated local fake (no real server);
        //   * release          -> fail CLOSED (no local OTP is ever accepted).
        TrustedExit.backend = when {
            MamaAccount.isConfigured -> MamaTrustedExit.deferredBackend()
            FeatureFlags.TEST_MODE -> FakeTrustedExitBackend(
                vault = DeviceChallengeVault(this),
                sms = DebugCodeSmsService,
            )
            else -> ReleaseTrustedExitDisabled
        }
        // With the real server backend, the BRICK lock-start preflight must require
        // a RECENT server-confirmed readiness (active series + provider-VERIFIED
        // contact + registered period), never the local fake round-trip. The gate
        // runs on the main thread, so it reads only the cached readiness that
        // MamaTrustedExit.refreshReadiness() records off the main thread; it is
        // fail-closed until a fresh ready==true is recorded. (Unconfigured builds
        // keep the default readiness: fake round-trip in TEST_MODE, else disabled.)
        if (MamaAccount.isConfigured) {
            TrustedExit.readiness = { TrustedExitReadinessGate.ready() }
        }
        // Any process start (including after the system killed us) re-applies the lock.
        Mama.sync(this)
    }
}
