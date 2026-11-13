package app.mama.trustedexit

import android.content.Context
import android.util.Log
import app.mama.account.MamaAccount
import app.mama.billing.FeatureFlags
import java.security.MessageDigest
import java.security.SecureRandom

internal object TrustedExitVariant {
    fun initialBackend(): TrustedExitBackend = FakeTrustedExitBackend()
    fun localBackend(context: Context): TrustedExitBackend =
        FakeTrustedExitBackend(vault = DeviceChallengeVault(context), sms = DebugCodeSmsService)
    fun probe(): Boolean = runCatching {
        val probe = FakeTrustedExitBackend(codeSource = { "1".repeat(it) })
        val created = probe.createChallenge(CreateChallengeRequest("readiness", "END_SESSION", "readiness"))
            as? CreateChallengeResult.Created ?: return false
        probe.verify(created.handle.challengeId, "11111") == VerifyResult.Valid
    }.getOrDefault(false)

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
/**
 * The simulated backend's challenge store on the device. It exists only
 * because there is no real server yet, and it is the one place a code hash
 * lives on the phone — NOT PRODUCTION SECURE: in production this is the
 * backend database, never on the device. The app itself never reads it; only
 * [FakeTrustedExitBackend] does. One slot, since there is one lock at a time.
 */
class DeviceChallengeVault(context: Context) : ChallengeVault {
    private val prefs = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences("mama_fake_exit_server_NOT_PRODUCTION_SECURE", Context.MODE_PRIVATE)

    @Synchronized
    private fun current(): ServerChallenge? {
        val id = prefs.getString("challengeId", null) ?: return null
        return ServerChallenge(
            challengeId = id,
            idempotencyKey = prefs.getString("idempotencyKey", "")!!,
            kind = prefs.getString("kind", "END_SESSION")!!,
            salt = prefs.getString("salt", "")!!,
            hash = prefs.getString("hash", "")!!,
            issuedAtMs = prefs.getLong("issuedAtMs", 0),
            expiresAtMs = prefs.getLong("expiresAtMs", 0),
            attemptsLeft = prefs.getInt("attemptsLeft", 0),
            lockedUntilMs = if (prefs.contains("lockedUntilMs")) prefs.getLong("lockedUntilMs", 0) else null,
        )
    }

    @Synchronized override fun byId(challengeId: String) = current()?.takeIf { it.challengeId == challengeId }
    @Synchronized override fun byKey(idempotencyKey: String) = current()?.takeIf { it.idempotencyKey == idempotencyKey }

    @Synchronized
    override fun put(challenge: ServerChallenge) {
        prefs.edit().apply {
            putString("challengeId", challenge.challengeId)
            putString("idempotencyKey", challenge.idempotencyKey)
            putString("kind", challenge.kind)
            putString("salt", challenge.salt)
            putString("hash", challenge.hash)
            putLong("issuedAtMs", challenge.issuedAtMs)
            putLong("expiresAtMs", challenge.expiresAtMs)
            putInt("attemptsLeft", challenge.attemptsLeft)
            if (challenge.lockedUntilMs != null) putLong("lockedUntilMs", challenge.lockedUntilMs) else remove("lockedUntilMs")
        }.apply()
    }

    @Synchronized
    override fun remove(challengeId: String) {
        if (current()?.challengeId == challengeId) prefs.edit().clear().apply()
    }
}
/**
 * TEST/DEBUG only. Without a real SMS provider a test device would never learn
 * the code and could stay locked, so in a test build the code is shown on the
 * lock screen. [DebugCodeSmsService] captures the code the (simulated) delivery
 * would have sent; the lock screen reads it through [codeFor], gated by
 * [FeatureFlags.TEST_MODE]. Nothing here is wired in a release build.
 */
object TestCodeSink {
    @Volatile
    private var last: Pair<String, String>? = null

    /** Records the code delivered for [challengeId] (the simulated SMS body). */
    fun record(challengeId: String, code: String) {
        // A real-server debug build must never capture a client-visible
        // simulated OTP, even if a test provider is accidentally invoked.
        if (MamaAccount.realBackendRequested) return
        last = challengeId to code
    }

    /** The code for [challengeId] — only ever in a TEST build; null otherwise. */
    fun codeFor(challengeId: String?): String? =
        visibleCode(FeatureFlags.TEST_MODE && !MamaAccount.realBackendRequested, challengeId)

    /**
     * The gate that keeps a production build from ever showing a TEST CODE: with
     * [testMode] false it returns null no matter what was recorded. Pure, so the
     * production guarantee is unit-testable without a release build.
     */
    internal fun visibleCode(testMode: Boolean, challengeId: String?): String? {
        if (!testMode || challengeId == null) return null
        return last?.takeIf { it.first == challengeId }?.second
    }

    fun clear() {
        last = null
    }
}

/**
 * TEST/DEBUG SMS provider: it does not send anything; it stashes the code into
 * [TestCodeSink] so the lock screen can show the TEST CODE. [app.mama.MamaApp]
 * wires it only when [FeatureFlags.TEST_MODE] is true, so a release build never
 * captures or exposes a code.
 */
object DebugCodeSmsService : TrustedExitSmsService {
    override fun sendCode(requestId: String, contactPhone: String, message: String): DeliveryStatus {
        TestCodeSink.record(requestId, message)
        return DeliveryStatus.SMS_ACCEPTED
    }
}

/** The SMS provider seam. In production this runs on the backend, never on the device. */
interface TrustedExitSmsService {
    fun sendCode(requestId: String, contactPhone: String, message: String): DeliveryStatus
}

/** Debug provider: pretends the SMS was accepted. Never sends anything real, never logs the code. */
object SimulatedSmsService : TrustedExitSmsService {
    override fun sendCode(requestId: String, contactPhone: String, message: String): DeliveryStatus {
        Log.i("MAMA", "[debug] simulated Trusted Exit SMS accepted for $requestId (not really sent)")
        return DeliveryStatus.SMS_ACCEPTED
    }
}

