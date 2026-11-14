package app.mama.trustedexit

/**
 * Server-authoritative Trusted Exit.
 *
 * In production the **backend** owns the whole code lifecycle: it generates
 * the one-time code, stores only a safe representation, sends the SMS through
 * its provider, counts the 3 attempts, expires and locks the challenge, and
 * verifies the entered code. The Android app only:
 *   - calls [createChallenge] and receives a [ChallengeHandle]
 *     (challengeId, expiry, attemptsLeft) and a delivery status — never the code;
 *   - calls [verify] with `challengeId + enteredCode` and receives only
 *     `VALID / INVALID / EXPIRED / LOCKED` — never the code;
 * and never generates, stores or logs the code or any material to check it.
 *
 * Delivery therefore never depends on this device's SIM: a Wi-Fi-only, no-SIM
 * device works through the same HTTPS transport.
 *
 * The series state machine is untouched: a `VALID` result ends the series
 * through the existing [app.mama.core.LockEngine.endByTrustedExit] transition.
 *
 * NOT PRODUCTION SECURE YET: [FakeTrustedExitBackend] runs the "server" on the
 * device so the flow and tests can run; there is no real backend or SMS
 * provider. A production [TrustedExitBackend] is an HTTPS client swapped in
 * with no change to callers.
 */
interface TrustedExitBackend {
    /** `POST /trusted-exit/request`: create (or reuse) a challenge. The response never contains the code. */
    fun createChallenge(request: CreateChallengeRequest): CreateChallengeResult

    /** `POST /trusted-exit/verify`: check an entered code. Returns only the outcome, never the code. */
    fun verify(challengeId: String, enteredCode: String): VerifyResult
}

/** What the device sends to create a challenge. No code, by construction. */
data class CreateChallengeRequest(
    val sessionId: String,
    val kind: String,
    /** Deduplicates rapid taps for the same session so the contact is not spammed. */
    val idempotencyKey: String,
)

/** All the device is told about an active challenge. Never the code or any way to check it. */
data class ChallengeHandle(
    val challengeId: String,
    val kind: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val attemptsLeft: Int,
)

sealed interface CreateChallengeResult {
    /** The backend created (or reused) the challenge and the provider accepted the SMS. */
    data class Created(val handle: ChallengeHandle, val delivery: DeliveryStatus) : CreateChallengeResult

    /** A challenge cannot be created now (one is pending, or the cooldown after 3 wrong tries is running). */
    data class Rejected(val reason: Reason, val retryAtMs: Long? = null) : CreateChallengeResult

    /** The provider refused the message; the device must not tell the user the code was sent. */
    data class Failed(val reason: String) : CreateChallengeResult

    /** No internet: produced by [TrustedExit], never by a backend. Fail-closed. */
    data object Offline : CreateChallengeResult

    enum class Reason { ALREADY_PENDING, COOLDOWN }
}

/** The only answers the device gets when it submits a code. */
sealed interface VerifyResult {
    data object Valid : VerifyResult
    data class Invalid(val attemptsLeft: Int) : VerifyResult
    data object Expired : VerifyResult
    data class Locked(val retryAtMs: Long) : VerifyResult

    /** No such challenge (already used, cleared or unknown id). */
    data object Unknown : VerifyResult

    /** No internet: produced by [TrustedExit], never by a backend. */
    data object Offline : VerifyResult
}

