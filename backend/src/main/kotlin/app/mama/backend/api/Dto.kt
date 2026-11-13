package app.mama.backend.api

/**
 * The HTTP/JSON wire contract. Identity and bindings are taken ONLY from the
 * signed grant in the `Authorization: Bearer` header — never from the body — so
 * there is no client-supplied `sessionId` or `contactPhone`. No response carries
 * the OTP or any material to check it.
 */

/** `POST /trusted-exit/request` response. `status` is CREATED | REJECTED | FAILED. */
data class ChallengeResponse(
    val status: String,
    val challengeId: String? = null,
    val issuedAtMs: Long? = null,
    val expiresAtMs: Long? = null,
    val attemptsLeft: Int? = null,
    val delivery: String? = null,
    val reason: String? = null,
    val retryAtMs: Long? = null,
)

/** `POST /trusted-exit/verify` body: only the challenge id and the entered code. */
data class VerifyBody(
    val challengeId: String? = null,
    val code: String? = null,
)

/**
 * `POST /trusted-exit/verify` response. `status` is VALID | INVALID | EXPIRED | LOCKED | UNKNOWN.
 * On VALID, [receipt] is the signed verified-OTP proof to forward to FastAPI (null when receipt
 * signing is not configured). The receipt is NOT an unlock by itself — see the design doc §3a.
 */
data class VerifyResponse(
    val status: String,
    val attemptsLeft: Int? = null,
    val retryAtMs: Long? = null,
    val receipt: String? = null,
)

/** `GET /health` response: liveness only (no dependency checks). */
data class HealthResponse(val status: String = "ok")

/**
 * `GET /ready` response: strict readiness. `ready` is true only when the grant
 * key, database and SMS provider are all configured. Carries only boolean flags
 * and a safe provider label — never keys, URLs or other secrets.
 */
data class ReadinessResponse(
    val status: String,
    val ready: Boolean,
    val grant: Boolean,
    val db: Boolean,
    val sms: Boolean,
    val destination: Boolean = false,
    val authoritativeReceiptDelivery: Boolean = false,
)

/** Generic error body (no internals, nothing code-shaped). */
data class ErrorResponse(val error: String)

/**
 * `POST /contact-verification/dispatch` response (server-to-server only). `status`
 * is SMS_ACCEPTED | FAILED | UNKNOWN, mapping to the FastAPI provider taxonomy
 * (sent / provably-not-sent / uncertain). Never carries the code or any secret.
 */
data class DispatchResponse(val status: String)

/**
 * Strict production readiness: provider config alone cannot mean real
 * Trusted Exit is available. A verified-contact destination resolver AND the
 * authenticated Ktor→FastAPI signed receipt channel are both mandatory,
 * otherwise Android could enter BRICK with no authoritative early exit.
 *
 * These capabilities default to FALSE. Tests can explicitly simulate both.
 */
data class Readiness(
    val grant: Boolean,
    val db: Boolean,
    val sms: Boolean,
    val destination: Boolean = false,
    val authoritativeReceiptDelivery: Boolean = false,
) {
    val ready: Boolean get() =
        grant && db && sms && destination && authoritativeReceiptDelivery
}
