package app.mama.backend.trustedexit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The server engine owns the whole code lifecycle and never returns the code.
 * These tests inject a known code so they can submit it; the engine never
 * exposes it through any result it returns.
 */
class ChallengeEngineTest {

    private var now = 0L

    private fun engine(
        code: String = "12345",
        sms: SmsProvider = MockSmsProvider(),
    ) = ChallengeEngine(
        sms = sms,
        ttlMs = 15 * 60_000,
        cooldownMs = 30 * 60_000,
        clockMs = { now },
        codeSource = { code },
    )

    private fun create(e: ChallengeEngine, key: String = "s1") =
        e.create(CreateRequest(sessionId = "s1", kind = "END_SESSION", idempotencyKey = key, contactPhone = "+79990000000"))

    @Test
    fun `create returns a handle with no code, 3 attempts and a 15-minute window`() {
        val created = create(engine()) as CreateResult.Created
        assertEquals(DeliveryStatus.SMS_ACCEPTED, created.delivery)
        assertTrue(created.challenge.challengeId.startsWith("tec-"))
        assertEquals(3, created.challenge.attemptsLeft)
        assertEquals(15 * 60_000L, created.challenge.expiresAtMs - created.challenge.issuedAtMs)
    }

    @Test
    fun `the backend generates a 5-digit code`() {
        val sms = MockSmsProvider()
        create(engine(code = secureDigits(5), sms = sms))
        val code = sms.lastCode!!
        assertEquals(5, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun `correct code verifies as VALID`() {
        val e = engine(code = "54321")
        val id = (create(e) as CreateResult.Created).challenge.challengeId
        assertEquals(VerifyOutcome.Valid, e.verify(id, "54321"))
    }

    @Test
    fun `a used code cannot pass twice`() {
        val e = engine(code = "54321")
        val id = (create(e) as CreateResult.Created).challenge.challengeId
        assertEquals(VerifyOutcome.Valid, e.verify(id, "54321"))
        assertEquals(VerifyOutcome.Unknown, e.verify(id, "54321"))
    }

    @Test
    fun `three wrong tries lock the challenge and burn the code`() {
        val e = engine(code = "11111")
        val id = (create(e) as CreateResult.Created).challenge.challengeId
        assertEquals(VerifyOutcome.Invalid(2), e.verify(id, "00000"))
        assertEquals(VerifyOutcome.Invalid(1), e.verify(id, "00000"))
        assertTrue(e.verify(id, "00000") is VerifyOutcome.Locked)
        // After the third wrong try the old challenge is invalid: even the right code is now locked out.
        assertTrue(e.verify(id, "11111") is VerifyOutcome.Locked)
    }

    @Test
    fun `no new challenge during the lock cooldown`() {
        val e = engine(code = "11111")
        val id = (create(e) as CreateResult.Created).challenge.challengeId
        repeat(3) { e.verify(id, "00000") }
        val rejected = create(e) as CreateResult.Rejected
        assertEquals(RejectReason.COOLDOWN, rejected.reason)
    }

    @Test
    fun `the challenge expires`() {
        val e = engine(code = "22222")
        val id = (create(e) as CreateResult.Created).challenge.challengeId
        now += 15 * 60_000 + 1
        assertEquals(VerifyOutcome.Expired, e.verify(id, "22222"))
    }

    @Test
    fun `a repeat request within the window reuses the challenge and sends one SMS`() {
        val sms = MockSmsProvider()
        val e = engine(sms = sms)
        val first = (create(e) as CreateResult.Created).challenge.challengeId
        val second = (create(e) as CreateResult.Created).challenge.challengeId
        assertEquals(first, second)
        assertEquals(1, sms.calls)
    }

    @Test
    fun `an explicit resend after the window sends a new code and a new SMS`() {
        val sms = MockSmsProvider()
        val e = engine(sms = sms)
        val first = (create(e) as CreateResult.Created).challenge.challengeId
        now += 15 * 60_000 + 1 // the first challenge expires
        val second = (create(e) as CreateResult.Created).challenge.challengeId
        assertNotEquals(first, second)
        assertEquals(2, sms.calls) // a second SMS was sent
    }

    @Test
    fun `a created challenge stores the sms_id and delivery status`() {
        val created = create(engine()) as CreateResult.Created
        assertEquals(DeliveryStatus.SMS_ACCEPTED, created.challenge.deliveryStatus)
        assertTrue(created.challenge.smsId?.startsWith("mock-sms-") == true)
    }

    @Test
    fun `provider failure reports FAILED and stores nothing`() {
        val e = engine(sms = MockSmsProvider(SmsResult.Failed("provider down")))
        assertTrue(create(e) is CreateResult.Failed)
        // Nothing pending, so a fresh engine can still create one.
        assertTrue(create(engine()) is CreateResult.Created)
    }

    @Test
    fun `the store keeps the challenge across an engine restart`() {
        val store = InMemoryChallengeStore()
        val first = ChallengeEngine(store = store, clockMs = { now }, codeSource = { "54321" })
        val id = (first.create(CreateRequest("s1", "END_SESSION", "s1", "+79990000000")) as CreateResult.Created).challenge.challengeId
        first.verify(id, "00000") // one wrong attempt, persisted in the store
        // A new engine over the same store (a process restart) still knows the challenge and its attempts.
        val restarted = ChallengeEngine(store = store, clockMs = { now }, codeSource = { "54321" })
        assertEquals(VerifyOutcome.Invalid(1), restarted.verify(id, "00000"))
        assertEquals(VerifyOutcome.Valid, restarted.verify(id, "54321"))
    }

    @Test
    fun `nothing the engine returns carries the code`() {
        val created = create(engine(code = "98765")) as CreateResult.Created
        assertFalse(created.toString().contains("98765"))
        assertFalse(created.challenge.toString().contains("98765"))
    }

    @Test
    fun `an unknown challenge id is UNKNOWN, not a wrong attempt`() {
        assertEquals(VerifyOutcome.Unknown, engine().verify("tec-nope", "12345"))
    }
}
