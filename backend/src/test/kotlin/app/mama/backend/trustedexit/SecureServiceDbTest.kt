package app.mama.backend.trustedexit

import app.mama.backend.auth.GrantVerifier
import app.mama.backend.db.JdbcDb
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end security tests for the durable, grant-gated Trusted Exit service,
 * backed by the ephemeral PostgreSQL provided in CI and a FAKE SMS provider.
 * Skipped locally when MAMA_TEST_DATABASE_URL is unset; never calls real SMS.
 *
 * Signing uses a keypair generated in the test — no production private key is
 * ever present. This demonstrates Ktor can be verified without FastAPI's key.
 */
class SecureServiceDbTest {

    private val url = System.getenv("MAMA_TEST_DATABASE_URL")
    private val dbUser = System.getenv("MAMA_TEST_DATABASE_USER")
    private val dbPass = System.getenv("MAMA_TEST_DATABASE_PASSWORD")

    private val kp: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val pub = Base64.getEncoder().encodeToString(kp.public.encoded)
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private lateinit var db: JdbcDb
    private val hasher = OtpHasher("test-pepper")
    private lateinit var sms: MockSmsProvider
    private lateinit var service: SecureTrustedExitService

    private val sub = "11111111-1111-4111-8111-111111111111"
    private val series = "22222222-2222-4222-8222-222222222222"
    private val period = "33333333-3333-4333-8333-333333333333"
    private val device = "44444444-4444-4444-8444-444444444444"
    private val contact = "55555555-5555-4555-8555-555555555555"

    @BeforeEach
    fun setUp() {
        assumeTrue(!url.isNullOrBlank(), "MAMA_TEST_DATABASE_URL not set; skipping DB-backed test")
        db = JdbcDb(url!!, dbUser, dbPass).also { it.migrate() }
        db.open().use { c ->
            c.createStatement().use {
                it.execute("TRUNCATE trusted_exit.challenge, trusted_exit.jti_ledger, trusted_exit.rate_counter")
            }
        }
        sms = MockSmsProvider()
        service = newService()
    }

    private fun newService(limits: RateLimits = RateLimits()) = SecureTrustedExitService(
        verifier = GrantVerifier(pub),
        repo = OtpRepository(db, hasher),
        hasher = hasher,
        sms = sms,
        destinations = ContactDestinationResolver { "+70000000000" },
        limits = limits,
    )

    // ---- grant signing helper (test only) -------------------------------------

    private fun bearer(
        jti: String = newJti(),
        sub: String = this.sub,
        series: String = this.series,
        period: String = this.period,
        device: String = this.device,
        contact: String = this.contact,
        key: java.security.PrivateKey = kp.private,
    ): String {
        val nowSec = System.currentTimeMillis() / 1000
        val header = JsonObject().apply { addProperty("alg", "EdDSA"); addProperty("typ", "JWT"); addProperty("kid", "mama-ktor-sms-v1") }
        val payload = JsonObject().apply {
            addProperty("iss", "mama-fastapi"); addProperty("aud", "mama-ktor-trusted-exit")
            addProperty("purpose", "trusted-exit:sms-request")
            addProperty("sub", sub); addProperty("series_id", series); addProperty("period_id", period)
            addProperty("device_id", device); addProperty("contact_id", contact); addProperty("jti", jti)
            addProperty("iat", nowSec); addProperty("nbf", nowSec); addProperty("exp", nowSec + 30)
        }
        val h = b64.encodeToString(header.toString().toByteArray())
        val p = b64.encodeToString(payload.toString().toByteArray())
        val sig = Signature.getInstance("Ed25519").apply { initSign(key); update("$h.$p".toByteArray(Charsets.US_ASCII)) }.sign()
        return "Bearer $h.$p.${b64.encodeToString(sig)}"
    }

    private fun newJti(): String = UUID.randomUUID().toString()

    // ---- tests ----------------------------------------------------------------

    @Test fun `unauthenticated request and verify are rejected with zero SMS`() {
        assertEquals(RequestOutcome.Unauthorized, service.request(null, "1.2.3.4"))
        assertEquals(VerifyOutcome.Unauthorized, service.verify(null, "tec-x", "12345"))
        assertEquals(0, sms.calls)
    }

    @Test fun `a grant signed by another key is unauthorized, zero SMS`() {
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        assertEquals(RequestOutcome.Unauthorized, service.request(bearer(key = other.private), "1.2.3.4"))
        assertEquals(0, sms.calls)
    }

    @Test fun `a valid grant creates a challenge and sends exactly one SMS`() {
        val r = service.request(bearer(), "1.2.3.4")
        assertTrue(r is RequestOutcome.Created)
        assertEquals(1, sms.calls)
        assertEquals("SMS_ACCEPTED", (r as RequestOutcome.Created).delivery)
    }

    @Test fun `replay of the same grant reuses the challenge, no second SMS`() {
        val b = bearer()
        val first = service.request(b, "1.2.3.4") as RequestOutcome.Created
        val second = service.request(b, "1.2.3.4") as RequestOutcome.Created
        assertEquals(first.challengeId, second.challengeId)
        assertEquals(1, sms.calls)
    }

    @Test fun `concurrent requests with the same grant send at most one SMS`() {
        val b = bearer()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val tasks = (1..8).map {
            pool.submit {
                start.await()
                (service.request(b, "1.2.3.4") as? RequestOutcome.Created)?.let { ids.add(it.challengeId) }
            }
        }
        start.countDown()
        tasks.forEach { it.get() }
        pool.shutdown()
        assertEquals(1, ids.size, "one challenge")
        assertEquals(1, sms.calls, "one SMS")
    }

    @Test fun `the delivered code verifies as VALID and cannot be replayed`() {
        val b = bearer()
        val created = service.request(b, "1.2.3.4") as RequestOutcome.Created
        val code = sms.lastCode!! // the fake captured the OTP; the API never returns it
        assertEquals(VerifyOutcome.Valid, service.verify(b, created.challengeId, code))
        assertEquals(VerifyOutcome.Unknown, service.verify(b, created.challengeId, code)) // consumed
    }

    @Test fun `three wrong codes lock the challenge and the lock persists across a restart`() {
        val b = bearer()
        val created = service.request(b, "1.2.3.4") as RequestOutcome.Created
        assertEquals(VerifyOutcome.Invalid(2), service.verify(b, created.challengeId, "00000"))
        assertEquals(VerifyOutcome.Invalid(1), service.verify(b, created.challengeId, "00000"))
        assertTrue(service.verify(b, created.challengeId, "00000") is VerifyOutcome.Locked)
        // "Restart": a brand-new service/repo over the SAME database still sees it locked.
        val restarted = newService()
        assertTrue(restarted.verify(b, created.challengeId, sms.lastCode ?: "00000") is VerifyOutcome.Locked)
    }

    @Test fun `a challenge is owned only by its grant bindings`() {
        val b = bearer()
        val created = service.request(b, "1.2.3.4") as RequestOutcome.Created
        val otherSeries = bearer(series = "99999999-9999-4999-8999-999999999999")
        assertEquals(VerifyOutcome.Forbidden, service.verify(otherSeries, created.challengeId, sms.lastCode!!))
    }

    @Test fun `a provider timeout yields a safe unknown status and no immediate second SMS`() {
        val throwing = object : SmsProvider {
            val n = AtomicInteger(0)
            override val label = "throwing"
            override fun configured() = true
            override fun send(phone: String, code: String): SmsResult { n.incrementAndGet(); throw java.io.IOException("timeout") }
        }
        val svc = SecureTrustedExitService(GrantVerifier(pub), OtpRepository(db, hasher), hasher, throwing, ContactDestinationResolver { "+70000000000" })
        val b = bearer()
        val r = svc.request(b, "1.2.3.4") as RequestOutcome.Created
        assertEquals("UNKNOWN", r.delivery)
        svc.request(b, "1.2.3.4") // retry: must reuse, not re-send
        assertEquals(1, throwing.n.get())
    }

    @Test fun `rate limit denies beyond budget with no extra SMS`() {
        val svc = newService(RateLimits(userPerHour = 1))
        assertTrue(svc.request(bearer(), "1.2.3.4") is RequestOutcome.Created)
        assertEquals(RequestOutcome.RateLimited, svc.request(bearer(), "1.2.3.4")) // different jti, same user
        assertEquals(1, sms.calls)
    }

    @Test fun `destination that cannot be resolved fails closed with no SMS`() {
        val svc = SecureTrustedExitService(GrantVerifier(pub), OtpRepository(db, hasher), hasher, sms, ContactDestinationResolver { null })
        assertEquals(RequestOutcome.DestinationUnavailable, svc.request(bearer(), "1.2.3.4"))
        assertEquals(0, sms.calls)
    }

    @Test fun `a leading-zero 5-digit OTP verifies (no numeric truncation)`() {
        // Insert a challenge directly with a known leading-zero code.
        val salt = Otp.salt()
        val now = System.currentTimeMillis()
        val id = "tec-" + UUID.randomUUID()
        val repo = OtpRepository(db, hasher)
        assertTrue(
            repo.insertIfAbsent(
                StoredChallenge(id, newJti(), sub, series, period, device, contact, salt, hasher.hash(salt, "00042"),
                    3, OtpStatus.SENT.name, now, now + 900_000, null, "ref"),
            ),
        )
        assertEquals(VerifyOutcome.Valid, repo.verifyAndConsume(id, "00042", now, 1_800_000))
    }
}
