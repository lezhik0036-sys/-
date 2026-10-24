package app.mama.backend.api

/**
 * The wire contract between the Android app and the Trusted Exit backend.
 *
 * Flat data classes (not sealed types) so the JSON is a single stable shape the
 * client parses by its `status` field. No response ever carries the code or any
 * material to check it — that stays on the server, by construction.
 *
 * The Android client mirrors these shapes; there is no shared code, only the
 * HTTP/JSON contract documented here.
 */

/** `POST /trusted-exit/request` body. */
data class CreateChallengeBody(
    val sessionId: String? = null,
    val kind: String? = null,
    val idempotencyKey: String? = null,
    val contactPhone: String? = null,
)

/** `POST /trusted-exit/request` response. `status` is CREATED | REJECTED | FAILED. */
data class ChallengeResponse(
    val status: String,
    val challengeId: String? = null,
    val kind: String? = null,
    val issuedAtMs: Long? = null,
    val expiresAtMs: Long? = null,
    val attemptsLeft: Int? = null,
    val delivery: String? = null,
    val reason: String? = null,
    val retryAtMs: Long? = null,
)

/** `POST /trusted-exit/verify` body. */
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

/** `GET /health` response, used by the production readiness gate. */
data class HealthResponse(val status: String = "ok")

/** Returned for a malformed request body. */
data class ErrorResponse(val error: String)
