package app.mama.backend.trustedexit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Future mTLS lookup is NOT exposed; this test checks only internal policy. */
class ChallengeOwnershipCheckTest {
    private val now = 1_800_000L
    private val owner = ChallengeOwner(
        "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333",
        "44444444-4444-4444-8444-444444444444",
        "55555555-5555-4555-8555-555555555555",
    )
    private val id = "tec-66666666-6666-4666-8666-666666666666"
    private val ch = StoredChallenge(
        id = id, jti = "77777777-7777-4777-8777-777777777777",
        userId = owner.userId, seriesId = owner.seriesId,
        periodId = owner.periodId, deviceId = owner.deviceId,
        contactId = owner.contactId,
        salt = "test", codeHash = "never expose",
        attemptsLeft = 3, status = OtpStatus.SENT.name,
        issuedAt = now - 1000, expiresAt = now + 15_000,
        cooldownUntil = null, smsRef = "fake-provider",
    )
    private fun policy(
        challenge: StoredChallenge? = ch, redeemed: Boolean = false,
        at: Long = now,
    ) = ChallengeOwnershipCheck(
        { challenge },
        { redeemed },
        { at },
    )

    @Test fun `only exact active delivered owner binding can be confirmed`() {
        val policy = policy()
        assertTrue(policy.matchesCurrentSentChallenge(id, owner))
        assertFalse(policy.matchesCurrentSentChallenge(id, owner.copy(userId = "another-account")))
        assertFalse(policy.matchesCurrentSentChallenge(id, owner.copy(seriesId = "another-series")))
        assertFalse(policy.matchesCurrentSentChallenge(id, owner.copy(periodId = "another-period")))
        assertFalse(policy.matchesCurrentSentChallenge(id, owner.copy(deviceId = "another-device")))
        assertFalse(policy.matchesCurrentSentChallenge(id, owner.copy(contactId = "another-contact")))
        assertFalse(policy.matchesCurrentSentChallenge("other-challenge", owner))
        assertFalse(policy.matchesCurrentSentChallenge("tec-" + "A".repeat(36), owner))
        assertFalse(policy(challenge = null).matchesCurrentSentChallenge(id, owner))
        assertFalse(policy(challenge = ch.copy(id = "unexpected")).matchesCurrentSentChallenge(id, owner))
    }

    @Test fun `unavailable spent timed out or locked challenges never authorize verify grant`() {
        val states = listOf(
            OtpStatus.PENDING_SEND.name, OtpStatus.SENDING.name,
            OtpStatus.SEND_FAILED.name, "CONSUMED", "EXPIRED", "LOCKED",
        )
        states.forEach { state ->
            assertFalse(policy(challenge = ch.copy(status = state)).matchesCurrentSentChallenge(id, owner), state)
        }
        assertFalse(policy(challenge = ch.copy(attemptsLeft = 0)).matchesCurrentSentChallenge(id, owner))
        assertFalse(policy(challenge = ch.copy(cooldownUntil = now + 500)).matchesCurrentSentChallenge(id, owner))
        assertFalse(policy(at = ch.expiresAt).matchesCurrentSentChallenge(id, owner))
        assertFalse(policy(at = ch.issuedAt - 1).matchesCurrentSentChallenge(id, owner))
        assertFalse(policy(redeemed = true).matchesCurrentSentChallenge(id, owner))
    }
}
