package app.mama.trustedexit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.util.UUID

/**
 * Delivery of the Trusted Exit code to the trusted contact.
 *
 * Production architecture (the only one that must ever ship):
 *
 *     MAMA Android ──HTTPS──▶ MAMA backend ──▶ SMS provider ──▶ Trusted Contact
 *
 * The device sends only an HTTPS request. The backend checks the active
 * series / device / session, picks the current trusted contact, generates the
 * one-time code, stores only a safe representation of it, sends the SMS
 * through its own provider and returns **only a status** — never the code.
 * So delivery never depends on this device having a SIM, a phone number, a
 * carrier, SEND_SMS, SmsManager or ACTION_SENDTO.
 *
 * The Trusted Exit *business logic* is unchanged and still lives in
 * [app.mama.core.LockEngine]: the 3-attempt limit, expiry, "the right code
 * ends the series", "the user never sees the code". This layer only replaces
 * the transport of the code to the contact.
 *
 * NOT PRODUCTION SECURE YET: there is no real backend or SMS provider. The
 * debug implementation ([DebugTrustedExitApi]) only simulates the server so
 * the app flow can be exercised; it does not actually send an SMS. A real
 * [TrustedExitApi] / [TrustedExitSmsService] must be plugged in before any
 * real use. See [TrustedExit.api].
 */

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

/** What the device acts on after asking the backend to deliver a code. */
sealed interface DeliveryResult {
    /** The backend took the request and the provider accepted the SMS. */
    data class Accepted(val requestId: String) : DeliveryResult

    /** No internet: nothing was generated or sent; the lock is untouched. */
    data object Offline : DeliveryResult

    /** The provider refused: do not tell the user the code was sent. */
    data class Failed(val reason: String) : DeliveryResult
}

/**
 * What the device sends to the backend. It identifies the session so the
 * server can find the active series and its trusted contact. It carries **no
 * code**. [debugMessage] exists only for the local debug simulation and is
 * ignored by any real backend.
 */
data class ExitCodeRequest(
    val sessionId: String,
    val kind: String,
    /** Deduplicates rapid taps for the same session (server-side idempotency key). */
    val idempotencyKey: String,
    /** Debug-only: the message core already built; a real backend builds its own. */
    val debugMessage: String? = null,
)

/**
 * The device → backend contract (`POST /trusted-exit/request`). The real
 * implementation performs the HTTPS call and returns only a status; it never
 * returns the code. [requestExitCode] is called only when the device is
 * already known to be online.
 */
interface TrustedExitApi {
    fun requestExitCode(request: ExitCodeRequest): DeliveryResult
}

/**
 * The SMS provider seam. In production this runs **on the backend**, not on
 * the device, and this interface exists so the backend code has a clear shape
 * to implement; it is never given a device SIM.
 */
interface TrustedExitSmsService {
    fun sendCode(requestId: String, contactPhone: String, message: String): DeliveryStatus
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

/**
 * The single point the app calls to deliver a Trusted Exit code. Like
 * [app.mama.billing.Billing], it holds the active implementation so a real
 * backend can replace the debug one without touching callers. It never sees
 * or returns the code to the UI.
 */
object TrustedExit {
    /** Overridable for tests and for a future production backend. */
    @Volatile
    var online: (Context) -> Boolean = { Connectivity.online(it) }

    /** The debug simulation by default. Replace with a real HTTPS client in production. */
    @Volatile
    var api: TrustedExitApi = DebugTrustedExitApi()

    /**
     * Offline is fail-closed: when there is no internet nothing is generated
     * or sent and [DeliveryResult.Offline] is returned, so the caller must not
     * commit a challenge. Otherwise the request goes to [api].
     */
    fun deliver(context: Context, request: ExitCodeRequest): DeliveryResult {
        if (!online(context)) return DeliveryResult.Offline
        return runCatching { api.requestExitCode(request) }
            .getOrElse { DeliveryResult.Failed(it.message ?: "delivery error") }
    }

    fun newRequestId(): String = "te-" + UUID.randomUUID()
}

/**
 * Debug stand-in for the backend. Simulates "request created, provider
 * accepted" so the UI and tests can run, with a short idempotency window so a
 * double tap reuses the same request instead of creating a second one. It
 * does NOT send a real SMS and is NOT production secure. [sms] can be swapped
 * in tests to simulate a provider failure.
 */
class DebugTrustedExitApi(
    private val sms: TrustedExitSmsService = SimulatedSmsService,
    private val idempotencyWindowMs: Long = 60_000,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : TrustedExitApi {
    private data class Active(val requestId: String, val atMs: Long)
    private val inFlight = HashMap<String, Active>()

    @Synchronized
    override fun requestExitCode(request: ExitCodeRequest): DeliveryResult {
        val now = clockMs()
        inFlight[request.idempotencyKey]?.let { active ->
            if (now - active.atMs < idempotencyWindowMs) {
                Log.i(TAG, "reusing in-flight Trusted Exit request ${active.requestId}")
                return DeliveryResult.Accepted(active.requestId)
            }
        }
        val requestId = TrustedExit.newRequestId()
        // In production the backend generates the code and the message; here the
        // simulated provider is handed the debug message only so the flow can run.
        val status = sms.sendCode(requestId, contactPhone = "", message = request.debugMessage.orEmpty())
        return when (status) {
            DeliveryStatus.SMS_ACCEPTED, DeliveryStatus.REQUEST_CREATED, DeliveryStatus.SMS_DELIVERED -> {
                inFlight[request.idempotencyKey] = Active(requestId, now)
                DeliveryResult.Accepted(requestId)
            }
            DeliveryStatus.SMS_FAILED, DeliveryStatus.SMS_UNDELIVERED ->
                DeliveryResult.Failed("provider rejected the message")
        }
    }

    private companion object {
        const val TAG = "MAMA"
    }
}

/** Debug provider: pretends the SMS was accepted. Never sends anything real. */
object SimulatedSmsService : TrustedExitSmsService {
    override fun sendCode(requestId: String, contactPhone: String, message: String): DeliveryStatus {
        Log.i("MAMA", "[debug] simulated Trusted Exit SMS accepted for request $requestId (not really sent)")
        return DeliveryStatus.SMS_ACCEPTED
    }
}
