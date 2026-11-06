package app.mama.trustedexit

import android.util.Log
import app.mama.account.AccountHttpTransport
import app.mama.account.AccountSession
import app.mama.account.DeviceKey
import app.mama.account.HttpReply
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/**
 * The real, server-authoritative [TrustedExitBackend]: a thin HTTPS client for
 * the authenticated FastAPI Trusted Exit API (the `/v1/trusted-exit` routes). It replaces
 * the on-device [FakeTrustedExitBackend] with NO local code logic at all — the
 * OTP is generated, delivered (by the private Ktor worker FastAPI reaches
 * server-to-server) and verified entirely on the server. This device only:
 *   - finishes a device-operation proof (signs the exact server bytes with the
 *     registered Keystore Ed25519 key) and asks the server to dispatch the code;
 *   - forwards the user-entered 5-digit code and learns only the outcome.
 * It never sees, stores, or checks the code, and never unlocks on its own.
 *
 * Because the credential is the device key and the code travels over HTTPS, this
 * is identical on a Wi-Fi-only / no-SIM device: the phone's SIM is never used.
 *
 * Mapping to the Android [TrustedExitBackend] contract:
 *   createChallenge  ->  POST /proof-challenges  then  POST /request
 *   verify           ->  POST /verify            (+ GET /status to finalize)
 * The server `request_id` is the Android `challengeId`.
 *
 * LOST-ACK SAFETY (issue #47): a stable idempotency UUID is persisted via
 * [ExitRequestKeyStore] BEFORE the network call. If the connection drops after
 * the server already created the request (and possibly sent the SMS), a retry
 * with the same caller key recovers the SAME request by its persisted id — the
 * server short-circuits on the idempotency key, so NO second code is ever sent.
 *
 * Fail-closed throughout: no session, a transport failure, a 5xx or a malformed
 * reply never yields a success — the user is never told the code was sent unless
 * the server confirmed dispatch, and a lost outcome is surfaced as "created, not
 * confirmed" ([DeliveryStatus.REQUEST_CREATED]) rather than a false accept.
 */
class HttpTrustedExitBackend(
    private val transport: AccountHttpTransport,
    private val deviceKey: DeviceKey,
    private val session: TrustedExitSessionSource,
    private val context: ExitContextSource,
    private val requestKeys: ExitRequestKeyStore,
    /** How many times verify() polls /status to finalize an async receipt admission. */
    private val statusPollAttempts: Int = 3,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : TrustedExitBackend {

    override fun createChallenge(request: CreateChallengeRequest): CreateChallengeResult {
        var sess = session.ensureSession() ?: return CreateChallengeResult.Failed("no-session")
        val ctx = context.current() ?: return CreateChallengeResult.Failed("not-provisioned")

        // STEP 5 / issue #47: persist the stable idempotency UUID BEFORE any
        // network call. A dropped connection then recovers THIS request instead
        // of starting a new one, so the contact is never sent a second code.
        val record = runCatching {
            requestKeys.loadOrCreate(request.idempotencyKey)
        }.getOrElse {
            // No request is sent if its no-resend identity is not durable.
            return CreateChallengeResult.Failed("request-storage-unavailable")
        }
        record.requestId?.let { rid ->
            // The server already has this request: recover its state, never re-request.
            return recover(sess, rid, request.kind)
        }

        // 1) Device-operation proof challenge (one-time, 60s). The server returns
        //    the exact bytes the registered device key must sign.
        var proof = authedPost(POST_PROOF, proofBody(ctx, sess.deviceId), sess)
            ?: return CreateChallengeResult.Failed("transport")
        if (proof.status == 401) {
            sess = reauth() ?: return CreateChallengeResult.Failed("no-session")
            proof = authedPost(POST_PROOF, proofBody(ctx, sess.deviceId), sess)
                ?: return CreateChallengeResult.Failed("transport")
        }
        if (proof.status == 404) return CreateChallengeResult.Failed("not-available")
        if (proof.status != 200) return CreateChallengeResult.Failed("proof-${proof.status}")
        val challenge = runCatching {
            val j = JSONObject(proof.body)
            Triple(j.getString("proof_id"), j.getString("nonce"), j.getString("message_b64"))
        }.getOrElse { return CreateChallengeResult.Failed("proof-malformed") }
        val (proofId, nonce, messageB64) = challenge

        // 2) Sign the EXACT server bytes with the hardware-protected device key.
        val signatureB64 = runCatching {
            B64.encodeToString(deviceKey.sign(UNB64.decode(messageB64)))
        }.getOrElse { return CreateChallengeResult.Failed("sign") }

        // 3) Finish the proof + issue the grant + ask the server to dispatch the
        //    code. Idempotent on the persisted UUID.
        val body = JSONObject()
            .put("idempotency_key", record.idempotencyUuid)
            .put("proof_id", proofId)
            .put("nonce", nonce)
            .put("signature_b64", signatureB64)
            .toString()
        val reply = authedPost(POST_REQUEST, body, sess)
            // Transport dropped: the request may have reached the server and sent
            // the SMS. The idempotency UUID is already persisted; a later retry
            // recovers it. Report "not confirmed", never "sent".
            ?: return CreateChallengeResult.Failed("request-unavailable")
        return mapRequestReply(sess, record, reply, request.kind, body)
    }

    override fun verify(challengeId: String, enteredCode: String): VerifyResult {
        var sess = session.ensureSession() ?: return VerifyResult.Unknown
        val body = JSONObject().put("request_id", challengeId).put("code", enteredCode).toString()
        var reply = authedPost(POST_VERIFY, body, sess) ?: return VerifyResult.Unknown
        if (reply.status == 401) {
            sess = reauth() ?: return VerifyResult.Unknown
            reply = authedPost(POST_VERIFY, body, sess) ?: return VerifyResult.Unknown
        }
        return when (reply.status) {
            200 -> when (val status = runCatching { JSONObject(reply.body).getString("status") }
                .getOrElse { return VerifyResult.Unknown }) {
                "exit_confirmed" -> VerifyResult.Valid
                "invalid_code" ->
                    VerifyResult.Invalid(runCatching { JSONObject(reply.body).optInt("attempts_remaining", 0) }
                        .getOrDefault(0))
                // Correct code accepted, but the signed receipt admission is still
                // in flight. Finalize via /status (which spends NO attempt), never
                // by re-submitting the code.
                "pending" -> pollForExit(sess, challengeId)
                else -> { Log.i(TAG, "unexpected verify status '$status'"); VerifyResult.Unknown }
            }
            404 -> VerifyResult.Unknown
            409 -> VerifyResult.Unknown // request not open (already failed/cancelled)
            422 -> VerifyResult.Invalid(statusAttemptsRemaining(sess, challengeId)) // bad format: no attempt spent
            429 -> VerifyResult.Locked(clockMs()) // attempts exhausted; user starts a NEW request
            else -> VerifyResult.Unknown // 503/5xx/unknown: no attempt spent, retry later
        }
    }

    /**
     * The server's own readiness for the provisioned series
     * (`GET /v1/setup/series/{id}/readiness`). This is the ONLY authoritative
     * preflight for starting a real BRICK lock: the app must never start a strict
     * physical lock merely because `/activate` returned `active` or a period reads
     * `registered`. Require [ServerReadiness.ready] == true (active + a provider-
     * VERIFIED contact + a registered period). Null = could not be determined
     * (offline / transport / unprovisioned) -> fail-closed, do not lock.
     */
    fun serverReadiness(): ServerReadiness? {
        val sess = session.ensureSession() ?: return null
        val ctx = context.current() ?: return null
        val reply = runCatching { transport.getJson(readinessPath(ctx.seriesId), sess.token) }
            .getOrNull() ?: return null
        if (reply.status != 200) return null
        return runCatching {
            val j = JSONObject(reply.body)
            ServerReadiness(
                active = j.getBoolean("active"),
                contactSelected = j.getBoolean("contact_selected"),
                contactVerified = j.getBoolean("contact_verified"),
                hasRegisteredPeriod = j.getBoolean("has_registered_period"),
                ready = j.getBoolean("ready"),
            )
        }.getOrNull()
    }

    // --- internals -----------------------------------------------------------

    private fun proofBody(ctx: ExitContext, deviceId: String): String =
        JSONObject()
            .put("series_id", ctx.seriesId)
            .put("period_id", ctx.periodId)
            .put("device_id", deviceId)
            .toString()

    private fun mapRequestReply(
        sess: AccountSession, record: ExitRequestRecord, reply: HttpReply, kind: String,
        originalBody: String,
    ): CreateChallengeResult = when (reply.status) {
        200 -> {
            val j = runCatching { JSONObject(reply.body) }.getOrNull()
            val requestId = j?.let { runCatching { it.getString("request_id") }.getOrNull() }
            if (requestId == null) {
                CreateChallengeResult.Failed("request-malformed")
            } else {
                // Persist the server id the instant it is known, so recovery works
                // even if this process dies before returning.
                if (runCatching {
                    requestKeys.recordRequestId(record.idempotencyKey, requestId)
                }.isFailure) {
                    CreateChallengeResult.Failed("request-storage-unavailable")
                } else {
                    deliveryFor(j.optString("status", ""), requestId, kind)
                }
            }
        }
        403 -> CreateChallengeResult.Failed("not-authorized")
        // Delivery not configured (terminal) OR an ambiguous upstream timeout: the
        // server kept a durable row and a replay with the SAME key recovers it
        // without re-sending. We cannot claim the code was sent.
        503 -> recoverAmbiguous(sess, record, kind, originalBody)
        409 -> CreateChallengeResult.Failed("conflict")
        401 -> CreateChallengeResult.Failed("no-session")
        else -> CreateChallengeResult.Failed("request-${reply.status}")
    }

    /** Map the server's client-facing request/status vocabulary to a create result. */
    private fun deliveryFor(status: String, requestId: String, kind: String): CreateChallengeResult =
        when (status) {
            "code_sent" -> CreateChallengeResult.Created(handle(requestId, kind), DeliveryStatus.SMS_ACCEPTED)
            // Created but dispatch not confirmed (ambiguous). Honest: the code MAY
            // have been sent; the UI lets the user enter it if received and polls.
            "pending" -> CreateChallengeResult.Created(handle(requestId, kind), DeliveryStatus.REQUEST_CREATED)
            "exit_confirmed" -> CreateChallengeResult.Created(handle(requestId, kind), DeliveryStatus.SMS_ACCEPTED)
            "failed", "cancelled" -> CreateChallengeResult.Failed("request-$status")
            else -> CreateChallengeResult.Created(handle(requestId, kind), DeliveryStatus.REQUEST_CREATED)
        }

    /** Replay /request with the SAME idempotency key to recover a durable row after a 503. */
    private fun recoverAmbiguous(
        sess: AccountSession, record: ExitRequestRecord, kind: String, originalBody: String,
    ): CreateChallengeResult {
        // Replay the EXACT original body: the server validates the request shape,
        // then short-circuits on (user, idempotency_key) and returns the existing
        // row WITHOUT re-checking the one-time proof — so this never re-proves or
        // re-sends. (A body missing proof fields would fail server-side validation
        // before the idempotency check, so the full body is required.)
        val reply = authedPost(POST_REQUEST, originalBody, sess)
            ?: return CreateChallengeResult.Failed("delivery-unavailable")
        if (reply.status != 200) return CreateChallengeResult.Failed("delivery-unavailable")
        val j = runCatching { JSONObject(reply.body) }.getOrNull()
            ?: return CreateChallengeResult.Failed("delivery-unavailable")
        val requestId = runCatching { j.getString("request_id") }.getOrNull()
            ?: return CreateChallengeResult.Failed("delivery-unavailable")
        if (runCatching {
            requestKeys.recordRequestId(record.idempotencyKey, requestId)
        }.isFailure) return CreateChallengeResult.Failed("request-storage-unavailable")
        return deliveryFor(j.optString("status", ""), requestId, kind)
    }

    /** Recover an already-sent request by its persisted server id (GET /status). */
    private fun recover(sess: AccountSession, requestId: String, kind: String): CreateChallengeResult {
        val view = statusView(sess, requestId)
            ?: return CreateChallengeResult.Failed("recover-unavailable")
        return when (view.status) {
            "code_sent" -> CreateChallengeResult.Created(handle(requestId, kind), DeliveryStatus.SMS_ACCEPTED)
            "exit_confirmed" -> CreateChallengeResult.Created(handle(requestId, kind), DeliveryStatus.SMS_ACCEPTED)
            "pending" -> CreateChallengeResult.Created(handle(requestId, kind), DeliveryStatus.REQUEST_CREATED)
            "failed", "cancelled" -> CreateChallengeResult.Failed("request-${view.status}")
            else -> CreateChallengeResult.Created(handle(requestId, kind), DeliveryStatus.REQUEST_CREATED)
        }
    }

    private fun pollForExit(sess: AccountSession, requestId: String): VerifyResult {
        repeat(statusPollAttempts) {
            when (statusView(sess, requestId)?.status) {
                "exit_confirmed" -> return VerifyResult.Valid
                "failed", "cancelled" -> return VerifyResult.Unknown
                null -> return VerifyResult.Unknown
                else -> { /* still finalizing; the UI's own status poll completes it */ }
            }
        }
        // Accepted but not yet finalized within the bounded window. NOT a re-verify
        // (would spend an attempt); the authoritative /status poll resolves it.
        return VerifyResult.Unknown
    }

    private fun statusAttemptsRemaining(sess: AccountSession, requestId: String): Int =
        statusView(sess, requestId)?.attemptsRemaining ?: 0

    private data class StatusView(val status: String, val attemptsRemaining: Int)

    private fun statusView(sess: AccountSession, requestId: String): StatusView? {
        val reply = runCatching { transport.getJson(statusPath(requestId), sess.token) }.getOrNull()
            ?: return null
        if (reply.status != 200) return null
        return runCatching {
            val j = JSONObject(reply.body)
            StatusView(j.getString("status"), j.optInt("attempts_remaining", MAX_VERIFY_ATTEMPTS))
        }.getOrNull()
    }

    private fun handle(requestId: String, kind: String): ChallengeHandle {
        val now = clockMs()
        // The server is authoritative for expiry/attempts; these are UI hints only.
        return ChallengeHandle(
            challengeId = requestId, kind = kind,
            issuedAtMs = now, expiresAtMs = now + HINT_TTL_MS, attemptsLeft = MAX_VERIFY_ATTEMPTS,
        )
    }

    private fun authedPost(path: String, body: String, sess: AccountSession): HttpReply? =
        runCatching { transport.postJson(path, body, sess.token) }.getOrNull()

    private fun reauth(): AccountSession? {
        session.forgetSession()
        return session.ensureSession()
    }

    private fun statusPath(requestId: String) =
        "/v1/trusted-exit/status/" + UUID.fromString(requestId)

    private fun readinessPath(seriesId: String) =
        "/v1/setup/series/" + UUID.fromString(seriesId) + "/readiness"

    private companion object {
        const val TAG = "MAMA"
        const val POST_PROOF = "/v1/trusted-exit/proof-challenges"
        const val POST_REQUEST = "/v1/trusted-exit/request"
        const val POST_VERIFY = "/v1/trusted-exit/verify"
        // Server-side durable per-request budget (owner requirement: 3).
        const val MAX_VERIFY_ATTEMPTS = 3
        // Non-authoritative UI countdown hint; the server owns the real expiry.
        const val HINT_TTL_MS = 15 * 60_000L
        val B64: Base64.Encoder = Base64.getEncoder()
        val UNB64: Base64.Decoder = Base64.getDecoder()
    }
}

/** Source of a usable MAMA server session; null when none can be had (fail-closed). */
interface TrustedExitSessionSource {
    fun ensureSession(): AccountSession?
    fun forgetSession()
}

/**
 * The server-owned series + lock period this exit is for, provisioned via the
 * `/v1/setup` API (draft series + selected contact + activation + a registered
 * period). The device id comes from the authenticated session, never from here.
 */
data class ExitContext(val seriesId: String, val periodId: String)

fun interface ExitContextSource {
    fun current(): ExitContext?
}

/** The server's readiness summary for a series (GET /v1/setup/series/{id}/readiness). */
data class ServerReadiness(
    val active: Boolean,
    val contactSelected: Boolean,
    val contactVerified: Boolean,
    val hasRegisteredPeriod: Boolean,
    val ready: Boolean,
)

/**
 * Durable record of one logical exit attempt, keyed by the caller's idempotency
 * key. [idempotencyUuid] is the server idempotency key; [requestId] is the server
 * request id once known. Both MUST be persisted BEFORE the network call so a lost
 * connection recovers the same request and never triggers a second SMS.
 */
data class ExitRequestRecord(
    val idempotencyKey: String,
    val idempotencyUuid: String,
    val requestId: String?,
)

interface ExitRequestKeyStore {
    /** The existing record for [idempotencyKey], or a freshly created+persisted one. */
    fun loadOrCreate(idempotencyKey: String): ExitRequestRecord

    /** Persist the server [requestId] for [idempotencyKey] (the instant /request returns it). */
    fun recordRequestId(idempotencyKey: String, requestId: String)
}

/** In-memory store (tests, and flows that need not survive a restart). */
class InMemoryExitRequestKeyStore : ExitRequestKeyStore {
    private val byKey = HashMap<String, ExitRequestRecord>()

    @Synchronized
    override fun loadOrCreate(idempotencyKey: String): ExitRequestRecord =
        byKey.getOrPut(idempotencyKey) {
            ExitRequestRecord(idempotencyKey, UUID.randomUUID().toString(), null)
        }

    @Synchronized
    override fun recordRequestId(idempotencyKey: String, requestId: String) {
        val rec = byKey[idempotencyKey]
            ?: ExitRequestRecord(idempotencyKey, UUID.randomUUID().toString(), null)
        byKey[idempotencyKey] = rec.copy(requestId = requestId)
    }
}
