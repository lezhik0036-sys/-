package app.mama.backend.trustedexit

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Trusted Exit OTP primitives and the secure flow's value types. All durable
 * state lives in PostgreSQL (see [OtpRepository]); nothing here keeps auth state
 * in memory. The plaintext OTP is never stored or logged — only a peppered,
 * salted HMAC verifier is persisted.
 */

/** A 5-digit numeric OTP, leading zeros preserved. */
object Otp {
    private val rng = SecureRandom()
    fun generate(): String = "%05d".format(rng.nextInt(100_000))
    fun looksValid(code: String): Boolean = code.length == 5 && code.all { it.isDigit() }
    fun salt(): String = ByteArray(16).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
}

/**
 * Peppered + salted verifier: HMAC-SHA256 keyed by a server-side pepper over
 * `salt:code`. The pepper is a server secret (env), never in Git/logs/APK; the
 * salt is per-challenge. Compared in constant time.
 */
class OtpHasher(pepper: String) {
    private val key = SecretKeySpec(pepper.toByteArray(Charsets.UTF_8), "HmacSHA256")
    val configured: Boolean = pepper.isNotBlank()

    fun hash(salt: String, code: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        return mac.doFinal("$salt:$code".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun matches(salt: String, code: String, storedHash: String): Boolean =
        MessageDigest.isEqual(hash(salt, code).toByteArray(Charsets.UTF_8), storedHash.toByteArray(Charsets.UTF_8))
}

/** Lifecycle of one challenge row (explicit send states prevent duplicate provider calls). */
enum class OtpStatus { PENDING_SEND, SENDING, SENT, SEND_FAILED, CONSUMED, LOCKED, EXPIRED }

/** How the backend resolves the SMS destination — from a trusted server source, NEVER the client. */
fun interface ContactDestinationResolver {
    /** Resolves the destination phone for the grant's contact_id, or null if it cannot (fail-closed). */
    fun resolve(grant: app.mama.backend.auth.Grant): String?
}

/** Durable rate-limit windows/budgets (seconds + max count), enforced before any send. */
data class RateLimits(
    val userPerHour: Int = 5,
    val devicePerHour: Int = 5,
    val contactPerDay: Int = 3,
    val ipPerHour: Int = 20,
    val globalPerDay: Int = 500,
)

/** Result of a `request` (create/reuse challenge). The code is never included. */
sealed interface RequestOutcome {
    data class Created(
        val challengeId: String,
        val issuedAtMs: Long,
        val expiresAtMs: Long,
        val attemptsLeft: Int,
        val delivery: String,
    ) : RequestOutcome

    data class Rejected(val reason: String, val retryAtMs: Long? = null) : RequestOutcome
    data class Failed(val reason: String) : RequestOutcome
    data object RateLimited : RequestOutcome
    data object Unauthorized : RequestOutcome
    /** The grant verified but does not bind to a usable destination/policy (fail-closed). */
    data object DestinationUnavailable : RequestOutcome
}

/** Result of a `verify`. Only an explicit server VALID is a positive result; never a transient. */
sealed interface VerifyOutcome {
    /**
     * The entered OTP matched a delivered challenge. This is NOT an unlock
     * authorization: it carries no receipt/token, and the Android client must
     * not end the lock on it alone. The server-authoritative series/period
     * transition is owned by FastAPI and must be obtained via a one-time
     * redemption (Deliverable B); see docs/TRUSTED_EXIT_KTOR_SECURITY.md §4.
     */
    data object Valid : VerifyOutcome
    data class Invalid(val attemptsLeft: Int) : VerifyOutcome
    data object Expired : VerifyOutcome
    data class Locked(val retryAtMs: Long) : VerifyOutcome
    data object Unknown : VerifyOutcome
    data object Unauthorized : VerifyOutcome
    /** The grant does not own this challenge (binding mismatch). */
    data object Forbidden : VerifyOutcome
}
