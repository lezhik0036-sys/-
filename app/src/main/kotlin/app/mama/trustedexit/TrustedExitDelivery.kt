package app.mama.trustedexit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
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
    var backend: TrustedExitBackend = FakeTrustedExitBackend()

    /**
     * TEST/DEBUG: the guaranteed exit must work before any lock, so the backend
     * must round-trip. Production: the real backend's own readiness, treated as
     * ready here until that backend exists. Tests restore [readiness] to this.
     */
    val defaultReadiness: (Context) -> Boolean = { !FeatureFlags.TEST_MODE || backendCanRoundTrip() }

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
    fun backendCanRoundTrip(): Boolean = runCatching {
        val probe = FakeTrustedExitBackend(codeSource = { "1".repeat(it) })
        val created = probe.createChallenge(CreateChallengeRequest("readiness", "END_SESSION", "readiness"))
            as? CreateChallengeResult.Created ?: return false
        probe.verify(created.handle.challengeId, "11111") == VerifyResult.Valid
    }.getOrDefault(false)

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

/**
 * TEST/DEBUG only. Without a real SMS provider a test device would never learn
 * the code and could stay locked, so in a test build the code is shown on the
 * lock screen. [DebugCodeSmsService] captures the code the (simulated) delivery
 * would have sent; the lock screen reads it through [codeFor], gated by
 * [FeatureFlags.TEST_MODE]. Nothing here is wired in a release build.
 */
object TestCodeSink {
    @Volatile
    private var last: Pair<String, String>? = null

    /** Records the code delivered for [challengeId] (the simulated SMS body). */
    fun record(challengeId: String, code: String) {
        last = challengeId to code
    }

    /** The code for [challengeId] — only ever in a TEST build; null otherwise. */
    fun codeFor(challengeId: String?): String? = visibleCode(FeatureFlags.TEST_MODE, challengeId)

    /**
     * The gate that keeps a production build from ever showing a TEST CODE: with
     * [testMode] false it returns null no matter what was recorded. Pure, so the
     * production guarantee is unit-testable without a release build.
     */
    internal fun visibleCode(testMode: Boolean, challengeId: String?): String? {
        if (!testMode || challengeId == null) return null
        return last?.takeIf { it.first == challengeId }?.second
    }

    fun clear() {
        last = null
    }
}

/**
 * TEST/DEBUG SMS provider: it does not send anything; it stashes the code into
 * [TestCodeSink] so the lock screen can show the TEST CODE. [app.mama.MamaApp]
 * wires it only when [FeatureFlags.TEST_MODE] is true, so a release build never
 * captures or exposes a code.
 */
object DebugCodeSmsService : TrustedExitSmsService {
    override fun sendCode(requestId: String, contactPhone: String, message: String): DeliveryStatus {
        TestCodeSink.record(requestId, message)
        return DeliveryStatus.SMS_ACCEPTED
    }
}

/** The SMS provider seam. In production this runs on the backend, never on the device. */
interface TrustedExitSmsService {
    fun sendCode(requestId: String, contactPhone: String, message: String): DeliveryStatus
}

/** Debug provider: pretends the SMS was accepted. Never sends anything real, never logs the code. */
object SimulatedSmsService : TrustedExitSmsService {
    override fun sendCode(requestId: String, contactPhone: String, message: String): DeliveryStatus {
        Log.i("MAMA", "[debug] simulated Trusted Exit SMS accepted for $requestId (not really sent)")
        return DeliveryStatus.SMS_ACCEPTED
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
