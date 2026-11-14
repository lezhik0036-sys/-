package app.mama.trustedexit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.mama.billing.FeatureFlags

/**
 * The single point the app calls for Trusted Exit, over the server-authoritative
 * [TrustedExitBackend]. Like [app.mama.billing.Billing] it holds the active
 * backend so a real HTTPS client can replace the debug one without touching
 * callers. The app never sees the code: it only creates challenges and verifies
 * entered codes, getting back a handle and a status.
 *
 * Offline is fail-closed: with no internet nothing is created, sent or
 * verified, so a lost network can never be a way around the lock.
 */
object TrustedExit {
    /** Overridable for tests and for a future production backend. */
    @Volatile
    var online: (Context) -> Boolean = { Connectivity.online(it) }

    /**
     * The debug simulation by default (in-memory, does not survive a restart);
     * [app.mama.MamaApp] replaces it with one backed by device-protected
     * storage so a reboot keeps the challenge. Replace with a real HTTPS client
     * in production.
     */
    @Volatile
    var backend: TrustedExitBackend = TrustedExitVariant.initialBackend()

    /**
     * Fail-closed for production: no release series may start until an audited,
     * authenticated FastAPI/Ktor client with a tested early-exit recovery route
     * replaces the disabled adapter AND supplies a fresh server readiness gate.
     * Simply compiling with TEST_MODE=false is never an exit guarantee.
     * Debug builds retain their local fake-OTP round-trip safety check.
     */
    val defaultReadiness: (Context) -> Boolean = {
        defaultReadinessForTestMode(FeatureFlags.TEST_MODE) { backendCanRoundTrip() }
    }

    internal fun defaultReadinessForTestMode(
        testMode: Boolean, debugRoundTrip: () -> Boolean,
    ): Boolean = if (testMode) debugRoundTrip() else false

    /**
     * Readiness check run before a series is allowed to lock the device
     * (`Trusted Exit readiness check`). Overridable for tests; the default is
     * [defaultReadiness]. A false result means the series must NOT start, so the
     * device is never locked without a working way out.
     */
    @Volatile
    var readiness: (Context) -> Boolean = defaultReadiness

    fun ready(context: Context): Boolean = runCatching { readiness(context) }.getOrDefault(false)

    /**
     * Proves the Trusted Exit backend can create AND verify a challenge: a
     * throwaway probe on a fresh backend, so the real challenge store is
     * untouched. This is the concrete "test backend доступен и способен
     * создать/проверить challenge" check behind the readiness gate.
     */
    fun backendCanRoundTrip(): Boolean = TrustedExitVariant.probe()

    fun createChallenge(context: Context, request: CreateChallengeRequest): CreateChallengeResult {
        if (!online(context)) return CreateChallengeResult.Offline
        return runCatching { backend.createChallenge(request) }
            .getOrElse { CreateChallengeResult.Failed(it.message ?: "backend error") }
    }

    fun verify(context: Context, challengeId: String, enteredCode: String): VerifyResult {
        if (!online(context)) return VerifyResult.Offline
        return runCatching { backend.verify(challengeId, enteredCode) }
            .getOrElse { VerifyResult.Unknown }
    }
}

/** Status the backend reports for one delivery. Never carries the code. */
enum class DeliveryStatus {
    /** The server accepted the request and created it. */
    REQUEST_CREATED,

    /** The SMS provider accepted the message for sending. */
    SMS_ACCEPTED,

    /** The SMS provider refused the message. */
    SMS_FAILED,

    /** Reserved for delivery receipts (must not block the flow once accepted). */
    SMS_DELIVERED,
    SMS_UNDELIVERED,
}

/** Internet reachability via the official [ConnectivityManager]. No telephony. */
object Connectivity {
    fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}
