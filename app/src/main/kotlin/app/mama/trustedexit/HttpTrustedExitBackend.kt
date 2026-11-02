package app.mama.trustedexit

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * The production, server-authoritative Trusted Exit client. It talks to the
 * MAMA Ktor backend over HTTPS using the existing contract:
 *   - POST /trusted-exit/request  {sessionId, kind, idempotencyKey, contactPhone}
 *   - POST /trusted-exit/verify   {challengeId, code}
 *   - GET  /ready
 *
 * The backend owns the whole code lifecycle; this client only sends a challenge
 * request and an entered code and maps the server's status back. It NEVER:
 *   - receives or stores the correct code (the server returns only VALID/INVALID/…),
 *   - carries any API key or secret (it knows only the base URL, which is config),
 *   - logs request/response bodies, the contact phone or the entered code.
 *
 * Fail-closed by construction: a server error, timeout, non-2xx or malformed
 * reply is NEVER reported as VALID and NEVER as a wrong attempt — verify maps
 * such cases to [VerifyResult.Offline] (retry, no attempt consumed, no bypass),
 * and request maps them to [CreateChallengeResult.Failed] (no challenge, the
 * phone stays locked). The attempt counter lives only on the server and is
 * decremented only on an explicit INVALID response.
 *
 * Network I/O here runs on whatever thread calls it; callers (the lock overlay)
 * invoke it off the UI thread. [isReady] does its own off-thread, bounded probe.
 */
class HttpTrustedExitBackend(
    private val transport: HttpTransport,
    private val enabled: Boolean = true,
) : TrustedExitBackend {

    /** Production constructor: enabled only for an https base URL (no cleartext, no blank). */
    constructor(baseUrl: String) : this(UrlHttpTransport(baseUrl), baseUrl.startsWith("https://"))

    override fun createChallenge(request: CreateChallengeRequest): CreateChallengeResult {
        if (!enabled) return CreateChallengeResult.Failed("trusted exit backend is not configured")
        val body = JSONObject()
            .put("sessionId", request.sessionId)
            .put("kind", request.kind)
            .put("idempotencyKey", request.idempotencyKey)
            .put("contactPhone", request.contactPhone)
            .toString()
        val resp = try {
            transport.post("/trusted-exit/request", body)
        } catch (e: IOException) {
            // Server unreachable / timeout: no challenge is created, the phone stays locked.
            return CreateChallengeResult.Failed("server unavailable")
        }
        if (resp.code !in 200..299) return CreateChallengeResult.Failed("server error ${resp.code}")
        val json = try { JSONObject(resp.body) } catch (e: Exception) {
            return CreateChallengeResult.Failed("bad server response")
        }
        return when (json.optString("status")) {
            "CREATED" -> {
                // A CREATED reply must carry a usable challenge. Missing or nonsensical
                // fields must NOT become a live challenge (which would tell the user a code
                // was sent while no real challenge exists): treat them as a failure instead.
                val challengeId = json.optString("challengeId")
                val issuedAtMs = json.optLong("issuedAtMs", 0L)
                val expiresAtMs = json.optLong("expiresAtMs", -1L)
                val attemptsLeft = json.optInt("attemptsLeft", -1)
                if (challengeId.isBlank() || expiresAtMs <= 0L || expiresAtMs <= issuedAtMs || attemptsLeft <= 0) {
                    CreateChallengeResult.Failed("incomplete challenge")
                } else {
                    CreateChallengeResult.Created(
                        handle = ChallengeHandle(
                            challengeId = challengeId,
                            kind = json.optString("kind", request.kind),
                            issuedAtMs = issuedAtMs,
                            expiresAtMs = expiresAtMs,
                            attemptsLeft = attemptsLeft,
                        ),
                        delivery = parseDelivery(json.optString("delivery")),
                    )
                }
            }
            "REJECTED" -> when (json.optString("reason")) {
                "COOLDOWN" -> CreateChallengeResult.Rejected(
                    CreateChallengeResult.Reason.COOLDOWN,
                    if (json.has("retryAtMs")) json.optLong("retryAtMs") else null,
                )
                "ALREADY_PENDING" -> CreateChallengeResult.Rejected(CreateChallengeResult.Reason.ALREADY_PENDING, null)
                else -> CreateChallengeResult.Failed("rejected")
            }
            "FAILED" -> CreateChallengeResult.Failed("delivery failed")
            else -> CreateChallengeResult.Failed("unexpected status")
        }
    }

    override fun verify(challengeId: String, enteredCode: String): VerifyResult {
        // Unconfigured or any transport/parse problem must NOT unlock and must NOT
        // count as a wrong attempt: report Offline so the user simply retries.
        if (!enabled) return VerifyResult.Offline
        val body = JSONObject()
            .put("challengeId", challengeId)
            .put("code", enteredCode)
            .toString()
        val resp = try {
            transport.post("/trusted-exit/verify", body)
        } catch (e: IOException) {
            return VerifyResult.Offline
        }
        if (resp.code !in 200..299) return VerifyResult.Offline
        val json = try { JSONObject(resp.body) } catch (e: Exception) {
            return VerifyResult.Offline
        }
        return when (json.optString("status")) {
            "VALID" -> VerifyResult.Valid
            "INVALID" -> VerifyResult.Invalid(json.optInt("attemptsLeft"))
            "EXPIRED" -> VerifyResult.Expired
            "LOCKED" -> VerifyResult.Locked(
                if (json.has("retryAtMs")) json.optLong("retryAtMs") else System.currentTimeMillis(),
            )
            "UNKNOWN" -> VerifyResult.Unknown
            // Anything we do not explicitly recognize is treated as transient, never as a pass.
            else -> VerifyResult.Offline
        }
    }

    /**
     * Readiness gate used before a series may lock the device. Checks internet
     * first, then probes GET /ready on a background thread with a bounded wait,
     * so no socket runs on the caller's (UI) thread. Fail-closed: any failure or
     * timeout returns false, so a series will not start without a working way out.
     */
    fun isReady(context: Context): Boolean {
        if (!Connectivity.online(context)) return false
        return offThread(READINESS_WAIT_MS) { isReadyNow() } ?: false
    }

    /** The /ready probe itself (no threading); true only on a 2xx "ready" reply. */
    fun isReadyNow(): Boolean {
        if (!enabled) return false
        return try {
            val resp = transport.get("/ready")
            resp.code in 200..299 && JSONObject(resp.body).optString("status") == "ready"
        } catch (e: Exception) {
            false
        }
    }

    private fun <T> offThread(timeoutMs: Long, block: () -> T): T? {
        val task = FutureTask(block)
        Thread(task, "mama-te-ready").apply { isDaemon = true }.start()
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            task.cancel(true)
            null
        }
    }

    private fun parseDelivery(raw: String): DeliveryStatus =
        runCatching { DeliveryStatus.valueOf(raw) }.getOrDefault(DeliveryStatus.SMS_ACCEPTED)

    private companion object {
        const val READINESS_WAIT_MS = 6_000L
    }
}

/** A minimal HTTP seam so the client is unit-tested without the network. */
interface HttpTransport {
    /** POSTs a JSON [jsonBody] to [path]; throws [IOException] on a transport failure. */
    fun post(path: String, jsonBody: String): HttpResponse

    /** GETs [path]; throws [IOException] on a transport failure. */
    fun get(path: String): HttpResponse
}

data class HttpResponse(val code: Int, val body: String)

/**
 * The real transport over [HttpURLConnection] (no third-party dependency).
 * HTTPS only; never logs anything. Short timeouts for the readiness GET, longer
 * for the request/verify POSTs.
 */
class UrlHttpTransport(private val baseUrl: String) : HttpTransport {

    override fun post(path: String, jsonBody: String): HttpResponse =
        exchange(path, "POST", jsonBody, connectMs = 10_000, readMs = 15_000)

    override fun get(path: String): HttpResponse =
        exchange(path, "GET", null, connectMs = 2_500, readMs = 2_500)

    private fun exchange(path: String, method: String, body: String?, connectMs: Int, readMs: Int): HttpResponse {
        val url = URL(baseUrl.trimEnd('/') + path)
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResponse(code, text)
        } finally {
            c.disconnect()
        }
    }
}
