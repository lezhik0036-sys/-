package app.mama.trustedexit

import android.content.Context

/**
 * What the device keeps about the active Trusted Exit challenge: only the
 * [ChallengeHandle] fields plus the delivery status and, after three wrong
 * tries, when a new code may be requested. It holds **no code, salt or hash**
 * and nothing that could verify a code locally — verification is the
 * backend's. Kept in device-protected storage so a reboot or app restart does
 * not lose it or the attempt count.
 */
data class StoredHandle(
    val sessionId: String,
    val challengeId: String,
    val kind: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val attemptsLeft: Int,
    val delivery: DeliveryStatus,
    /** Set only while locked after three wrong tries; null otherwise. */
    val lockedUntilMs: Long? = null,
)

/** Persists the single active [StoredHandle] (one lock at a time). */
class TrustedExitStore(context: Context) {
    private val prefs = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences("mama_trusted_exit", Context.MODE_PRIVATE)

    /** The active handle for [sessionId], or null (a handle from another session is ignored). */
    fun active(sessionId: String): StoredHandle? {
        val stored = prefs.getString("sessionId", null) ?: return null
        if (stored != sessionId) return null
        val challengeId = prefs.getString("challengeId", null) ?: return null
        return StoredHandle(
            sessionId = stored,
            challengeId = challengeId,
            kind = prefs.getString("kind", "END_SESSION")!!,
            issuedAtMs = prefs.getLong("issuedAtMs", 0),
            expiresAtMs = prefs.getLong("expiresAtMs", 0),
            attemptsLeft = prefs.getInt("attemptsLeft", 0),
            delivery = runCatching { DeliveryStatus.valueOf(prefs.getString("delivery", "")!!) }.getOrDefault(DeliveryStatus.SMS_ACCEPTED),
            lockedUntilMs = if (prefs.contains("lockedUntilMs")) prefs.getLong("lockedUntilMs", 0) else null,
        )
    }

    fun put(h: StoredHandle) {
        prefs.edit().apply {
            putString("sessionId", h.sessionId)
            putString("challengeId", h.challengeId)
            putString("kind", h.kind)
            putLong("issuedAtMs", h.issuedAtMs)
            putLong("expiresAtMs", h.expiresAtMs)
            putInt("attemptsLeft", h.attemptsLeft)
            putString("delivery", h.delivery.name)
            if (h.lockedUntilMs != null) putLong("lockedUntilMs", h.lockedUntilMs) else remove("lockedUntilMs")
        }.apply()
    }

    fun clear() = prefs.edit().clear().apply()
}

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
