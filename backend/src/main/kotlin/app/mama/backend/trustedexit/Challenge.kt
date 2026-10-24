package app.mama.backend.trustedexit

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Server-authoritative Trusted Exit — the real server engine.
 *
 * This is the one place the Trusted Exit code exists. The engine generates the
 * 5-digit code, stores only a salted SHA-256 of it, hands the SMS provider the
 * code to deliver, counts the three attempts, expires and locks the challenge,
 * and verifies an entered code. It returns **only** a handle (challengeId,
 * expiry, attempts) and a verification outcome — never the code, by
 * construction, and it never logs the code.
 *
 * The product rules match MAMA Core exactly (5-digit code, 3 attempts,
 * 15-minute expiry, 30-minute cooldown) so the on-device numbers never change;
 * here they are authoritative. [codeSource] is injectable so tests can submit a
 * known code without the engine ever exposing it through the API.
 */
class ChallengeEngine(
    private val store: ChallengeStore = InMemoryChallengeStore(),
    private val sms: SmsProvider = MockSmsProvider(),
    private val codeLength: Int = 5,
    private val maxAttempts: Int = 3,
    private val ttlMs: Long = 15 * 60_000L,
    private val cooldownMs: Long = 30 * 60_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val codeSource: (Int) -> String = ::secureDigits,
) {

    @Synchronized
    fun create(request: CreateRequest): CreateResult {
        val now = clockMs()
        store.byKey(request.idempotencyKey)?.let { active ->
            active.lockedUntilMs?.let { until ->
                if (now < until) return CreateResult.Rejected(RejectReason.COOLDOWN, until)
            }
            if (now < active.expiresAtMs && active.attemptsLeft > 0) {
                // Idempotent: a repeated request reuses the live challenge, so the
                // contact is never sent a second code for the same tap.
                return CreateResult.Created(active, DeliveryStatus.SMS_ACCEPTED)
            }
            store.remove(active.challengeId)
        }
        val code = codeSource(codeLength)
        val salt = secureSalt()
        val challenge = ServerChallenge(
            challengeId = "tec-" + UUID.randomUUID(),
            idempotencyKey = request.idempotencyKey,
            sessionId = request.sessionId,
            kind = request.kind,
            salt = salt,
            hash = sha256(salt, code),
            issuedAtMs = now,
            expiresAtMs = now + ttlMs,
            attemptsLeft = maxAttempts,
            lockedUntilMs = null,
        )
        // The server builds and sends the SMS itself; the code never leaves here.
        return when (val delivery = sms.send(request.contactPhone, code)) {
            is SmsResult.Accepted -> {
                store.put(challenge)
                CreateResult.Created(challenge, delivery.status)
            }
            is SmsResult.Failed ->
                // Nothing is stored, so the device must not tell the user a code was sent.
                CreateResult.Failed(delivery.reason)
        }
    }

    @Synchronized
    fun verify(challengeId: String, enteredCode: String): VerifyOutcome {
        val c = store.byId(challengeId) ?: return VerifyOutcome.Unknown
        val now = clockMs()
        c.lockedUntilMs?.let { until -> if (now < until) return VerifyOutcome.Locked(until) }
        if (now >= c.expiresAtMs) {
            store.remove(challengeId)
            return VerifyOutcome.Expired
        }
        val candidate = enteredCode.trim()
        val matches = candidate.length == codeLength && candidate.all { it.isDigit() } &&
            MessageDigest.isEqual(sha256(c.salt, candidate).toByteArray(), c.hash.toByteArray())
        if (matches) {
            // One-time: a used code is invalidated so it can never pass twice.
            store.remove(challengeId)
            return VerifyOutcome.Valid
        }
        val left = c.attemptsLeft - 1
        if (left <= 0) {
            // Third wrong try: the old challenge is now invalid; a cooldown starts.
            val until = now + cooldownMs
            store.put(c.copy(attemptsLeft = 0, lockedUntilMs = until))
            return VerifyOutcome.Locked(until)
        }
        store.put(c.copy(attemptsLeft = left))
        return VerifyOutcome.Invalid(left)
    }
}

/** What the device sends to create a challenge. No code, by construction. */
data class CreateRequest(
    val sessionId: String,
    val kind: String,
    /** Deduplicates rapid taps for the same session so the contact is not spammed. */
    val idempotencyKey: String,
    /** The trusted contact's number the server sends the code to (never a secret like the API key). */
    val contactPhone: String,
)

/** One challenge as the server keeps it: the only place a code hash lives. */
data class ServerChallenge(
    val challengeId: String,
    val idempotencyKey: String,
    val sessionId: String,
    val kind: String,
    val salt: String,
    val hash: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val attemptsLeft: Int,
    val lockedUntilMs: Long?,
)

sealed interface CreateResult {
    /** The challenge was created (or reused) and the provider accepted the SMS. */
    data class Created(val challenge: ServerChallenge, val delivery: DeliveryStatus) : CreateResult

    /** A challenge cannot be created now (one is pending, or the cooldown after 3 wrong tries is running). */
    data class Rejected(val reason: RejectReason, val retryAtMs: Long?) : CreateResult

    /** The provider refused the message; the device must not say a code was sent. */
    data class Failed(val reason: String) : CreateResult
}

enum class RejectReason { ALREADY_PENDING, COOLDOWN }

/** The only answers the device gets when it submits a code. Never the code. */
sealed interface VerifyOutcome {
    data object Valid : VerifyOutcome
    data class Invalid(val attemptsLeft: Int) : VerifyOutcome
    data object Expired : VerifyOutcome
    data class Locked(val retryAtMs: Long) : VerifyOutcome

    /** No such challenge (already used, cleared or unknown id). */
    data object Unknown : VerifyOutcome
}

private val rng = SecureRandom()

internal fun secureDigits(length: Int): String =
    buildString(length) { repeat(length) { append('0' + rng.nextInt(10)) } }

private fun secureSalt(): String {
    val bytes = ByteArray(16).also(rng::nextBytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun sha256(salt: String, code: String): String =
    MessageDigest.getInstance("SHA-256").digest("$salt:$code".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
