package app.mama.trustedexit

import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom

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

/**
 * One challenge as the server keeps it. In production this lives in the
 * backend database. The on-device [DeviceChallengeVault] is a stand-in and is
 * NOT PRODUCTION SECURE: it is the only place a code hash exists on the phone,
 * and only because there is no real server yet. The app itself never reads it.
 */
data class ServerChallenge(
    val challengeId: String,
    val idempotencyKey: String,
    val kind: String,
    val salt: String,
    val hash: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val attemptsLeft: Int,
    val lockedUntilMs: Long?,
)

/** Where the simulated server keeps challenges. A real backend has its own DB. */
interface ChallengeVault {
    fun byId(challengeId: String): ServerChallenge?
    fun byKey(idempotencyKey: String): ServerChallenge?
    fun put(challenge: ServerChallenge)
    fun remove(challengeId: String)
}

/** In-memory vault (tests, and a backend that need not survive a restart). */
class InMemoryChallengeVault : ChallengeVault {
    private val byId = HashMap<String, ServerChallenge>()
    @Synchronized override fun byId(challengeId: String) = byId[challengeId]
    @Synchronized override fun byKey(idempotencyKey: String) = byId.values.firstOrNull { it.idempotencyKey == idempotencyKey }
    @Synchronized override fun put(challenge: ServerChallenge) { byId[challenge.challengeId] = challenge }
    @Synchronized override fun remove(challengeId: String) { byId.remove(challengeId) }
}

/**
 * Debug stand-in for the backend: runs the server on the device. It owns the
 * code, attempts, expiry and lock exactly like the real server would, and
 * never returns or logs the code. With a persistent [vault] a restart of the
 * app or a reboot does not reset the challenge or the attempt count — the same
 * property a real server gives for free. NOT PRODUCTION SECURE YET.
 *
 * The product rules are mirrored here (5-digit code, 3 attempts, 15-minute
 * expiry, 30-minute cooldown) so the on-device numbers do not change; in
 * production these live on the server. [codeSource] is injectable so tests can
 * use a known code without the backend ever exposing it through the API.
 */
class FakeTrustedExitBackend(
    private val vault: ChallengeVault = InMemoryChallengeVault(),
    private val sms: TrustedExitSmsService = SimulatedSmsService,
    private val codeLength: Int = 5,
    private val maxAttempts: Int = 3,
    private val ttlMs: Long = 15 * 60_000L,
    private val cooldownMs: Long = 30 * 60_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val codeSource: (Int) -> String = ::secureDigits,
) : TrustedExitBackend {

    @Synchronized
    override fun createChallenge(request: CreateChallengeRequest): CreateChallengeResult {
        val now = clockMs()
        vault.byKey(request.idempotencyKey)?.let { active ->
            active.lockedUntilMs?.let { until ->
                if (now < until) return CreateChallengeResult.Rejected(CreateChallengeResult.Reason.COOLDOWN, until)
            }
            if (now < active.expiresAtMs && active.attemptsLeft > 0) {
                // Idempotent: the same tap reuses the live challenge, no second SMS.
                Log.i(TAG, "reusing Trusted Exit challenge ${active.challengeId}")
                return CreateChallengeResult.Created(handle(active), DeliveryStatus.SMS_ACCEPTED)
            }
            vault.remove(active.challengeId)
        }
        val code = codeSource(codeLength)
        val salt = secureSalt()
        val challengeId = "tec-" + java.util.UUID.randomUUID()
        val challenge = ServerChallenge(
            challengeId = challengeId,
            idempotencyKey = request.idempotencyKey,
            kind = request.kind,
            salt = salt,
            hash = sha256(salt, code),
            issuedAtMs = now,
            expiresAtMs = now + ttlMs,
            attemptsLeft = maxAttempts,
            lockedUntilMs = null,
        )
        // In production the backend builds and sends the SMS itself; the code never
        // leaves the server. The simulated provider ignores the text and logs nothing about the code.
        val status = sms.sendCode(challengeId, contactPhone = "", message = code)
        return when (status) {
            DeliveryStatus.SMS_ACCEPTED, DeliveryStatus.REQUEST_CREATED, DeliveryStatus.SMS_DELIVERED -> {
                vault.put(challenge)
                CreateChallengeResult.Created(handle(challenge), status)
            }
            DeliveryStatus.SMS_FAILED, DeliveryStatus.SMS_UNDELIVERED ->
                CreateChallengeResult.Failed("provider rejected the message")
        }
    }

    @Synchronized
    override fun verify(challengeId: String, enteredCode: String): VerifyResult {
        val c = vault.byId(challengeId) ?: return VerifyResult.Unknown
        val now = clockMs()
        c.lockedUntilMs?.let { until -> if (now < until) return VerifyResult.Locked(until) }
        if (now >= c.expiresAtMs) {
            vault.remove(challengeId)
            return VerifyResult.Expired
        }
        val candidate = enteredCode.trim()
        val matches = candidate.length == codeLength && candidate.all { it.isDigit() } &&
            MessageDigest.isEqual(sha256(c.salt, candidate).toByteArray(), c.hash.toByteArray())
        if (matches) {
            // One-time: a used code is invalidated so it can never pass twice.
            vault.remove(challengeId)
            return VerifyResult.Valid
        }
        val left = c.attemptsLeft - 1
        if (left <= 0) {
            val until = now + cooldownMs
            vault.put(c.copy(attemptsLeft = 0, lockedUntilMs = until))
            return VerifyResult.Locked(until)
        }
        vault.put(c.copy(attemptsLeft = left))
        return VerifyResult.Invalid(left)
    }

    private fun handle(c: ServerChallenge) = ChallengeHandle(c.challengeId, c.kind, c.issuedAtMs, c.expiresAtMs, c.attemptsLeft)

    private companion object {
        const val TAG = "MAMA"
    }
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
