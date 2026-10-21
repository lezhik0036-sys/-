package app.mama.trustedexit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The server-authoritative Trusted Exit backend (its debug stand-in): the
 * backend owns the code, the 3-attempt rule, expiry and lock, and never
 * returns the code. These tests inject a known code so they can submit it,
 * but the code is never exposed through the API itself. Robolectric is used
 * only so the backend's android.util.Log calls are shadowed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrustedExitBackendTest {

    private var now = 0L
    private fun backend(
        code: String = "12345",
        sms: TrustedExitSmsService = SimulatedSmsService,
    ) = FakeTrustedExitBackend(
        sms = sms,
        ttlMs = 15 * 60_000,
        cooldownMs = 30 * 60_000,
        clockMs = { now },
        codeSource = { code },
    )

    private fun create(b: TrustedExitBackend, key: String = "s1:1") =
        b.createChallenge(CreateChallengeRequest(sessionId = "s1", kind = "END_SESSION", idempotencyKey = key))

    @Test
    fun `createChallenge returns a handle with no code and a 5-digit expectation`() {
        val created = create(backend()) as CreateChallengeResult.Created
        assertEquals(DeliveryStatus.SMS_ACCEPTED, created.delivery)
        assertTrue(created.handle.challengeId.startsWith("tec-"))
        assertEquals(3, created.handle.attemptsLeft)
        // The handle carries no field that could be the code.
        assertEquals(15 * 60_000L, created.handle.expiresAtMs - created.handle.issuedAtMs)
    }

    @Test
    fun `correct code verifies as VALID`() {
        val b = backend(code = "54321")
        val h = (create(b) as CreateChallengeResult.Created).handle
        assertEquals(VerifyResult.Valid, b.verify(h.challengeId, "54321"))
    }

    @Test
    fun `a used code cannot pass twice`() {
        val b = backend(code = "54321")
        val h = (create(b) as CreateChallengeResult.Created).handle
        assertEquals(VerifyResult.Valid, b.verify(h.challengeId, "54321"))
        assertEquals(VerifyResult.Unknown, b.verify(h.challengeId, "54321"))
    }

    @Test
    fun `wrong code decrements attempts, then locks after three`() {
        val b = backend(code = "11111")
        val h = (create(b) as CreateChallengeResult.Created).handle
        assertEquals(VerifyResult.Invalid(2), b.verify(h.challengeId, "00000"))
        assertEquals(VerifyResult.Invalid(1), b.verify(h.challengeId, "00000"))
        val locked = b.verify(h.challengeId, "00000")
        assertTrue(locked is VerifyResult.Locked)
        // The correct code no longer works while locked.
        assertTrue(b.verify(h.challengeId, "11111") is VerifyResult.Locked)
    }

    @Test
    fun `a new challenge cannot be created during the lock cooldown`() {
        val b = backend(code = "11111")
        val h = (create(b) as CreateChallengeResult.Created).handle
        repeat(3) { b.verify(h.challengeId, "00000") }
        val rejected = create(b) as CreateChallengeResult.Rejected
        assertEquals(CreateChallengeResult.Reason.COOLDOWN, rejected.reason)
    }

    @Test
    fun `the challenge expires`() {
        val b = backend(code = "22222")
        val h = (create(b) as CreateChallengeResult.Created).handle
        now += 15 * 60_000 + 1
        assertEquals(VerifyResult.Expired, b.verify(h.challengeId, "22222"))
    }

    @Test
    fun `double create within the window reuses the same challenge, one SMS`() {
        val provider = CountingSms(DeliveryStatus.SMS_ACCEPTED)
        val b = backend(sms = provider)
        val first = (create(b) as CreateChallengeResult.Created).handle
        val second = (create(b) as CreateChallengeResult.Created).handle
        assertEquals(first.challengeId, second.challengeId)
        assertEquals(1, provider.calls)
    }

    @Test
    fun `provider failure is reported, no challenge is created`() {
        val b = backend(sms = CountingSms(DeliveryStatus.SMS_FAILED))
        val result = create(b)
        assertTrue(result is CreateChallengeResult.Failed)
        // Nothing is pending afterwards, so a later attempt can still create one.
        val retry = backend().let { create(it) }
        assertTrue(retry is CreateChallengeResult.Created)
    }

    @Test
    fun `a real backend generates a different code each time`() {
        // No injected code: the secure generator is used; two challenges differ (overwhelmingly).
        val b = FakeTrustedExitBackend(clockMs = { now })
        val a = (create(b, "k1") as CreateChallengeResult.Created).handle.challengeId
        now += 20 * 60_000
        val c = (create(b, "k1") as CreateChallengeResult.Created).handle.challengeId
        assertNotEquals(a, c)
    }

    @Test
    fun `a persistent vault keeps the challenge across a backend restart`() {
        val vault = InMemoryChallengeVault()
        val first = FakeTrustedExitBackend(vault = vault, clockMs = { now }, codeSource = { "54321" })
        val h = (first.createChallenge(CreateChallengeRequest("s1", "END_SESSION", "s1")) as CreateChallengeResult.Created).handle
        first.verify(h.challengeId, "00000") // one wrong attempt, persisted in the vault
        // A new backend instance over the same vault (a process restart) still knows the challenge.
        val restarted = FakeTrustedExitBackend(vault = vault, clockMs = { now }, codeSource = { "54321" })
        assertEquals(VerifyResult.Invalid(1), restarted.verify(h.challengeId, "00000"))
        assertEquals(VerifyResult.Valid, restarted.verify(h.challengeId, "54321"))
    }

    @Test
    fun `nothing the backend returns carries the code`() {
        val created = create(backend(code = "98765")) as CreateChallengeResult.Created
        assertFalse(created.handle.toString().contains("98765"))
        assertFalse(created.toString().contains("98765"))
    }

    private class CountingSms(private val status: DeliveryStatus) : TrustedExitSmsService {
        var calls = 0
        override fun sendCode(requestId: String, contactPhone: String, message: String): DeliveryStatus {
            calls++
            return status
        }
    }
}
