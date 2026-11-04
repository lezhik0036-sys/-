package app.mama.backend.trustedexit

import app.mama.backend.auth.GrantVerifier
import app.mama.backend.auth.ReceiptSigner
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
    private lateinit var repo: OtpRepository

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
        repo = OtpRepository(db, hasher)
        service = newService()
    }

    private fun newService(limits: RateLimits = RateLimits()) = SecureTrustedExitService(
        verifier = GrantVerifier(pub),
        verifyVerifier = GrantVerifier.forVerify(pub),
        repo = OtpRepository(db, hasher),
        hasher = hasher,
        sms = sms,
        destinations = ContactDestinationResolver { "+70000000000" },
        limits = limits,
    )

    // A receipt-signing keypair generated in the test — no production private key.
    private val receiptKp: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private fun newServiceWithSigner() = SecureTrustedExitService(
        verifier = GrantVerifier(pub),
        verifyVerifier = GrantVerifier.forVerify(pub),
        repo = OtpRepository(db, hasher),
        hasher = hasher,
        sms = sms,
        destinations = ContactDestinationResolver { "+70000000000" },
        receiptSigner = ReceiptSigner(Base64.getEncoder().encodeToString(receiptKp.private.encoded)),
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

    /** A fresh challenge-bound `trusted-exit:verify` grant (FastAPI PR #14 format) for /verify. */
    private fun verifyBearer(
        challengeId: String,
        jti: String = newJti(),
        sub: String = this.sub,
        series: String = this.series,
        period: String = this.period,
        device: String = this.device,
        contact: String = this.contact,
        key: java.security.PrivateKey = kp.private,
    ): String {
        val nowSec = System.currentTimeMillis() / 1000
        val header = JsonObject().apply { addProperty("alg", "EdDSA"); addProperty("typ", "JWT"); addProperty("kid", "mama-ktor-verify-v1") }
        val payload = JsonObject().apply {
            addProperty("iss", "mama-fastapi"); addProperty("aud", "mama-ktor-trusted-exit")
            addProperty("purpose", "trusted-exit:verify")
            addProperty("sub", sub); addProperty("series_id", series); addProperty("period_id", period)
            addProperty("device_id", device); addProperty("contact_id", contact)
            addProperty("challenge_id", challengeId); addProperty("jti", jti)
            addProperty("iat", nowSec); addProperty("nbf", nowSec); addProperty("exp", nowSec + 45)
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
        val vb = verifyBearer(created.challengeId)
        assertTrue(service.verify(vb, created.challengeId, code) is VerifyOutcome.Valid)
        // No receipt signer here, so a consumed challenge is a denied replay.
        assertEquals(VerifyOutcome.Unknown, service.verify(vb, created.challengeId, code)) // consumed
    }

    @Test fun `three wrong codes lock the challenge and the lock persists across a restart`() {
        val b = bearer()
        val created = service.request(b, "1.2.3.4") as RequestOutcome.Created
        val vb = verifyBearer(created.challengeId) // one verify grant covers the 3-attempt budget
        assertEquals(VerifyOutcome.Invalid(2), service.verify(vb, created.challengeId, "00000"))
        assertEquals(VerifyOutcome.Invalid(1), service.verify(vb, created.challengeId, "00000"))
        assertTrue(service.verify(vb, created.challengeId, "00000") is VerifyOutcome.Locked)
        // "Restart": a brand-new service/repo over the SAME database still sees it locked.
        val restarted = newService()
        assertTrue(restarted.verify(vb, created.challengeId, sms.lastCode ?: "00000") is VerifyOutcome.Locked)
    }

    @Test fun `a challenge is owned only by its grant bindings`() {
        val b = bearer()
        val created = service.request(b, "1.2.3.4") as RequestOutcome.Created
        // A verify grant correctly bound to this challenge_id but with a different series
        // must not redeem it.
        val otherSeries = verifyBearer(created.challengeId, series = "99999999-9999-4999-8999-999999999999")
        assertEquals(VerifyOutcome.Forbidden, service.verify(otherSeries, created.challengeId, sms.lastCode!!))
    }

    @Test fun `a provider timeout yields a safe unknown status and no immediate second SMS`() {
        val throwing = object : SmsProvider {
            val n = AtomicInteger(0)
            override val label = "throwing"
            override fun configured() = true
            override fun send(phone: String, code: String): SmsResult { n.incrementAndGet(); throw java.io.IOException("timeout") }
        }
        val svc = SecureTrustedExitService(GrantVerifier(pub), GrantVerifier.forVerify(pub), OtpRepository(db, hasher), hasher, throwing, ContactDestinationResolver { "+70000000000" })
        val b = bearer()
        val r = svc.request(b, "1.2.3.4") as RequestOutcome.Created
        assertEquals("UNKNOWN", r.delivery)
        svc.request(b, "1.2.3.4") // retry: must reuse, not re-send
        assertEquals(1, throwing.n.get())
        // Fail-closed: a timed-out (SENDING/UNKNOWN) challenge is never verifiable,
        // and the failed attempt does not burn the user's attempts.
        assertEquals(VerifyOutcome.Unknown, svc.verify(verifyBearer(r.challengeId), r.challengeId, "00000"))
        assertEquals(3, repo.byId(r.challengeId)!!.attemptsLeft)
    }

    // ---- ChatGPT review item 1: only a delivered (SENT) challenge is verifiable ----

    private fun insertWithStatus(status: OtpStatus, code: String): String {
        val salt = Otp.salt()
        val now = System.currentTimeMillis()
        val id = "tec-" + UUID.randomUUID()
        assertTrue(
            repo.insertIfAbsent(
                StoredChallenge(id, newJti(), sub, series, period, device, contact, salt, hasher.hash(salt, code),
                    3, status.name, now, now + 900_000, null, "ref"),
            ),
        )
        return id
    }

    @Test fun `a challenge that was never sent cannot be verified even with the right code`() {
        for (status in listOf(OtpStatus.PENDING_SEND, OtpStatus.SENDING, OtpStatus.SEND_FAILED)) {
            val id = insertWithStatus(status, "13579")
            assertEquals(VerifyOutcome.Unknown, repo.verifyAndConsume(id, "13579", System.currentTimeMillis(), 1_800_000),
                "status $status must not verify")
            assertEquals(3, repo.byId(id)!!.attemptsLeft, "attempts preserved for status $status")
        }
    }

    @Test fun `rate limit denies beyond budget with no extra SMS`() {
        val svc = newService(RateLimits(userPerHour = 1))
        assertTrue(svc.request(bearer(), "1.2.3.4") is RequestOutcome.Created)
        assertEquals(RequestOutcome.RateLimited, svc.request(bearer(), "1.2.3.4")) // different jti, same user
        assertEquals(1, sms.calls)
    }

    @Test fun `destination that cannot be resolved fails closed with no SMS`() {
        val svc = SecureTrustedExitService(GrantVerifier(pub), GrantVerifier.forVerify(pub), OtpRepository(db, hasher), hasher, sms, ContactDestinationResolver { null })
        assertEquals(RequestOutcome.DestinationUnavailable, svc.request(bearer(), "1.2.3.4"))
        assertEquals(0, sms.calls)
    }

    @Test fun `a leading-zero 5-digit OTP verifies (no numeric truncation)`() {
        // Insert a challenge directly with a known leading-zero code.
        val salt = Otp.salt()
        val now = System.currentTimeMillis()
        val id = "tec-" + UUID.randomUUID()
        assertTrue(
            repo.insertIfAbsent(
                StoredChallenge(id, newJti(), sub, series, period, device, contact, salt, hasher.hash(salt, "00042"),
                    3, OtpStatus.SENT.name, now, now + 900_000, null, "ref"),
            ),
        )
        assertTrue(repo.verifyAndConsume(id, "00042", now, 1_800_000) is VerifyOutcome.Valid)
    }

    // ---- ChatGPT review item 5: concurrency ------------------------------------

    @Test fun `parallel verification of the correct code consumes it exactly once`() {
        val b = bearer()
        val created = service.request(b, "1.2.3.4") as RequestOutcome.Created
        val code = sms.lastCode!!
        val vb = verifyBearer(created.challengeId)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val valid = AtomicInteger(0)
        val unknown = AtomicInteger(0)
        val tasks = (1..8).map {
            pool.submit {
                start.await()
                when (service.verify(vb, created.challengeId, code)) {
                    is VerifyOutcome.Valid -> valid.incrementAndGet()
                    VerifyOutcome.Unknown -> unknown.incrementAndGet()
                    else -> {}
                }
            }
        }
        start.countDown()
        tasks.forEach { it.get() }
        pool.shutdown()
        assertEquals(1, valid.get(), "exactly one VALID")
        assertEquals(7, unknown.get(), "the rest see a consumed challenge")
    }

    @Test fun `concurrent requests with distinct grants never exceed the per-user SMS budget`() {
        val svc = newService(RateLimits(userPerHour = 2)) // same user (sub), distinct jti each
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        val created = AtomicInteger(0)
        val limited = AtomicInteger(0)
        val tasks = (1..6).map {
            pool.submit {
                start.await()
                when (svc.request(bearer(jti = newJti()), "9.9.9.9")) {
                    is RequestOutcome.Created -> created.incrementAndGet()
                    RequestOutcome.RateLimited -> limited.incrementAndGet()
                    else -> {}
                }
            }
        }
        start.countDown()
        tasks.forEach { it.get() }
        pool.shutdown()
        assertEquals(2, created.get(), "only the budgeted requests create a challenge")
        assertEquals(4, limited.get(), "the rest are rate-limited")
        assertEquals(2, sms.calls, "at most the budget is ever sent")
    }

    // ---- Issue #10: dedicated challenge-bound verify-scope grant ----------------

    @Test fun `verify requires a fresh challenge-bound verify-scope grant`() {
        // The ≤60s SMS-request grant is long expired when the user types the code, so
        // verify authenticates on a fresh trusted-exit:verify grant bound to this
        // challenge_id — never the request grant.
        val created = service.request(bearer(), "1.2.3.4") as RequestOutcome.Created
        val code = sms.lastCode!!
        assertTrue(service.verify(verifyBearer(created.challengeId), created.challengeId, code) is VerifyOutcome.Valid)
    }

    @Test fun `verify rejects an SMS-request-scope grant even with matching bindings, consuming nothing`() {
        val created = service.request(bearer(), "1.2.3.4") as RequestOutcome.Created
        val code = sms.lastCode!!
        // A perfectly valid SMS-request grant (right key, right bindings) is the WRONG
        // scope for /verify and must be rejected outright.
        assertEquals(VerifyOutcome.Unauthorized, service.verify(bearer(), created.challengeId, code))
        assertEquals(3, repo.byId(created.challengeId)!!.attemptsLeft, "a rejected scope consumes no attempt")
        // The challenge is still verifiable with the correct verify-scope grant.
        assertTrue(service.verify(verifyBearer(created.challengeId), created.challengeId, code) is VerifyOutcome.Valid)
    }

    @Test fun `request rejects a verify-scope grant with zero SMS`() {
        val created0 = service.request(bearer(), "1.2.3.4") as RequestOutcome.Created
        val before = sms.calls
        // A trusted-exit:verify grant is the wrong scope for /request — rejected, no send.
        assertEquals(RequestOutcome.Unauthorized, service.request(verifyBearer(created0.challengeId), "1.2.3.4"))
        assertEquals(before, sms.calls, "no SMS for a verify-scope grant on /request")
    }

    @Test fun `a verify grant bound to a different challenge cannot redeem this one`() {
        val created = service.request(bearer(), "1.2.3.4") as RequestOutcome.Created
        val code = sms.lastCode!!
        // Correctly signed verify grant, but its challenge_id names a DIFFERENT challenge.
        val wrongBinding = verifyBearer("tec-" + UUID.randomUUID())
        assertEquals(VerifyOutcome.Forbidden, service.verify(wrongBinding, created.challengeId, code))
        assertEquals(3, repo.byId(created.challengeId)!!.attemptsLeft, "no attempt consumed")
    }

    @Test fun `a fresh verify grant cannot bypass the 3-attempt lock`() {
        val created = service.request(bearer(), "1.2.3.4") as RequestOutcome.Created
        // Burn the 3 attempts with one verify grant...
        service.verify(verifyBearer(created.challengeId), created.challengeId, "00000")
        service.verify(verifyBearer(created.challengeId), created.challengeId, "00000")
        assertTrue(service.verify(verifyBearer(created.challengeId), created.challengeId, "00000") is VerifyOutcome.Locked)
        // ...a brand-new verify grant does NOT reset the challenge-bound attempt budget.
        assertTrue(service.verify(verifyBearer(created.challengeId), created.challengeId, sms.lastCode ?: "00000") is VerifyOutcome.Locked)
    }

    // ---- Issue #8 + PR #9 review: verified-OTP receipt, no re-exposure ----------

    @Test fun `the correct code returns the receipt exactly once and never re-exposes it`() {
        val svc = newServiceWithSigner()
        val created = svc.request(bearer(), "1.2.3.4") as RequestOutcome.Created
        val code = sms.lastCode!!
        val vb = verifyBearer(created.challengeId)
        val first = svc.verify(vb, created.challengeId, code)
        assertTrue(first is VerifyOutcome.Valid)
        val receipt = (first as VerifyOutcome.Valid).receipt
        assertTrue(receipt != null && receipt.split(".").size == 3, "a signed JWT receipt is returned once")
        // The receipt was minted exactly once (persisted), but a second call — even with
        // the correct code and the same grant — must NOT re-expose it. Fail closed.
        assertEquals(receipt, repo.byId(created.challengeId)!!.receipt, "minted once and persisted")
        assertEquals(VerifyOutcome.Unknown, svc.verify(vb, created.challengeId, code))
    }

    @Test fun `a wrong or random code against a consumed challenge never retrieves the receipt`() {
        // The attack ChatGPT flagged: obtain a binding-compatible fresh grant + the
        // challenge id, submit an arbitrary code after consumption, try to harvest the
        // stored signed receipt. Must fail closed with no receipt.
        val svc = newServiceWithSigner()
        val created = svc.request(bearer(), "1.2.3.4") as RequestOutcome.Created
        val code = sms.lastCode!!
        assertTrue(svc.verify(verifyBearer(created.challengeId), created.challengeId, code) is VerifyOutcome.Valid) // consume
        val freshGrant = verifyBearer(created.challengeId, jti = newJti()) // fresh verify grant, same bindings+challenge
        assertEquals(VerifyOutcome.Unknown, svc.verify(freshGrant, created.challengeId, "00000"))
        assertEquals(VerifyOutcome.Unknown, svc.verify(freshGrant, created.challengeId, code))
        assertEquals(VerifyOutcome.Unknown, svc.verify(freshGrant, created.challengeId, "99999"))
    }

    @Test fun `a cross-account grant never retrieves a consumed receipt`() {
        val svc = newServiceWithSigner()
        val created = svc.request(bearer(), "1.2.3.4") as RequestOutcome.Created
        val code = sms.lastCode!!
        assertTrue(svc.verify(verifyBearer(created.challengeId), created.challengeId, code) is VerifyOutcome.Valid) // consume
        val otherAccount = verifyBearer(created.challengeId, series = "99999999-9999-4999-8999-999999999999")
        assertEquals(VerifyOutcome.Forbidden, svc.verify(otherAccount, created.challengeId, code))
    }

    @Test fun `a stored receipt is never returned for an already-consumed challenge`() {
        // Even a (possibly expired) receipt already sitting on a CONSUMED row is never
        // handed back — the consume branch is the only path that returns a receipt.
        val salt = Otp.salt()
        val now = System.currentTimeMillis()
        val id = "tec-" + UUID.randomUUID()
        assertTrue(
            repo.insertIfAbsent(
                StoredChallenge(id, newJti(), sub, series, period, device, contact, salt, hasher.hash(salt, "24680"),
                    3, OtpStatus.CONSUMED.name, now, now + 900_000, null, "ref", receipt = "stale.receipt.jwt"),
            ),
        )
        assertEquals(VerifyOutcome.Unknown, repo.verifyAndConsume(id, "24680", now, 1_800_000) { _, _ -> "new.receipt.jwt" })
    }

    @Test fun `parallel verification yields exactly one authorization and one minted receipt`() {
        val svc = newServiceWithSigner()
        val created = svc.request(bearer(), "1.2.3.4") as RequestOutcome.Created
        val code = sms.lastCode!!
        val vb = verifyBearer(created.challengeId)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val receipts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val valid = AtomicInteger(0)
        val unknown = AtomicInteger(0)
        val tasks = (1..8).map {
            pool.submit {
                start.await()
                when (val r = svc.verify(vb, created.challengeId, code)) {
                    is VerifyOutcome.Valid -> { valid.incrementAndGet(); r.receipt?.let(receipts::add) }
                    VerifyOutcome.Unknown -> unknown.incrementAndGet()
                    else -> {}
                }
            }
        }
        start.countDown()
        tasks.forEach { it.get() }
        pool.shutdown()
        assertEquals(1, valid.get(), "exactly one caller is authorized (performs the consume)")
        assertEquals(7, unknown.get(), "the rest get no receipt")
        assertEquals(1, receipts.size, "exactly one receipt/jti is ever minted")
    }

    // ---- Issue #12: server-only dual-proof evidence delivery (test-only) --------

    @Test fun `a verified receipt is delivered as dual-proof evidence and a consumed challenge cannot be resubmitted`() {
        val svc = newServiceWithSigner()
        val created = svc.request(bearer(), "1.2.3.4") as RequestOutcome.Created
        val code = sms.lastCode!!
        val outcome = svc.verify(verifyBearer(created.challengeId), created.challengeId, code)
        assertTrue(outcome is VerifyOutcome.Valid)
        val receipt = (outcome as VerifyOutcome.Valid).receipt!!

        // Server-only ingest: HMAC over the REAL minted receipt, delivered via a fake transport.
        val secret = "ephemeral-integration-only-ktor-service-secret-001"
        val sent = mutableListOf<EvidenceSubmission>()
        val submitter = EvidenceSubmitter(IngestProof.fromEnv(secret), ReceiptDeliveryClient { sent.add(it); DeliveryOutcome.Accepted })
        assertEquals(DeliveryOutcome.Accepted, submitter.submit(receipt))
        assertEquals(receipt, sent.single().token, "the signed receipt is delivered verbatim")
        val expectedMac = Base64.getEncoder().encodeToString(IngestProof.fromEnv(secret).mac(receipt)!!)
        assertEquals(expectedMac, sent.single().macB64, "the server-only HMAC over the exact token")

        // CONSUMED stays fail-closed: the receipt is never re-exposed, so nothing can be resubmitted later.
        assertEquals(VerifyOutcome.Unknown, svc.verify(verifyBearer(created.challengeId), created.challengeId, code))
    }
}
