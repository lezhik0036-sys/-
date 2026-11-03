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

/** `POST /trusted-exit/verify` response. `status` is VALID | INVALID | EXPIRED | LOCKED | UNKNOWN. */
data class VerifyResponse(
    val status: String,
    val attemptsLeft: Int? = null,
    val retryAtMs: Long? = null,
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
)

/** Generic error body (no internals, nothing code-shaped). */
data class ErrorResponse(val error: String)

/** Strict-readiness snapshot: all three must hold for the service to be ready. */
data class Readiness(val grant: Boolean, val db: Boolean, val sms: Boolean) {
    val ready: Boolean get() = grant && db && sms
}
