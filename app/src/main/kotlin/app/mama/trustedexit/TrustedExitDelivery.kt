package app.mama.trustedexit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

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
