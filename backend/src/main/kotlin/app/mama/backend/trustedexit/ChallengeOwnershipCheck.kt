package app.mama.backend.trustedexit

/**
 * INTERNAL ONLY. Ktor side of FastAPI's challenge-bound verify grant issuance.
 *
 * An authenticated server peer (future mTLS+service MAC endpoint) may ask if
 * its independently authenticated account/device/period bindings match a
 * currently deliverable, unexpired Ktor OTP challenge.
 *
 * No Ktor route is exposed; no server secrets, phone numbers, OTP, salted
 * hashes or persisted receipts leave this policy. This is deliberately a
 * Boolean, not a bearer authorization token. FastAPI must additionally
 * validate fresh user session, device key possession, active BRICK lock,
 * currently verified contact and its own policy BEFORE signing a 60s grant.
 *
 * A client-supplied challengeId or untrusted direct call alone must NEVER
 * authorize /verify.
 */
data class ChallengeOwner(
    val userId: String,
    val seriesId: String,
    val periodId: String,
    val deviceId: String,
    val contactId: String,
)

class ChallengeOwnershipCheck(
    private val challengeById: (String) -> StoredChallenge?,
    private val jtiRedeemed: (String) -> Boolean = { false },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    fun matchesCurrentSentChallenge(challengeId: String, expected: ChallengeOwner): Boolean {
        // Length/shape cap before querying, to avoid arbitrary unbounded ids.
        if (!CHALLENGE_ID.matches(challengeId)) return false
        val challenge = challengeById(challengeId) ?: return false
        val time = nowMs()
        return challenge.id == challengeId &&
            challenge.status == OtpStatus.SENT.name &&
            challenge.attemptsLeft > 0 &&
            !jtiRedeemed(challenge.jti) &&
            time >= challenge.issuedAt && time < challenge.expiresAt &&
            (challenge.cooldownUntil == null || time >= challenge.cooldownUntil) &&
            challenge.userId == expected.userId &&
            challenge.seriesId == expected.seriesId &&
            challenge.periodId == expected.periodId &&
            challenge.deviceId == expected.deviceId &&
            challenge.contactId == expected.contactId
    }

    companion object {
        private val CHALLENGE_ID =
            Regex("^tec-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fun fromRepository(
            repository: OtpRepository,
            nowMs: () -> Long = { System.currentTimeMillis() },
        ) = ChallengeOwnershipCheck(repository::byId, repository::isJtiRedeemed, nowMs)
    }
}
