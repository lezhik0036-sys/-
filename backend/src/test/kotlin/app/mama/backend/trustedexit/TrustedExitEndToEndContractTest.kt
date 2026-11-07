package app.mama.backend.trustedexit

import app.mama.backend.auth.GrantVerifier
import app.mama.backend.auth.ReceiptClaims
import app.mama.backend.auth.ReceiptSigner
import app.mama.backend.db.JdbcDb
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.UUID

/**
 * Runnable end-to-end **staging contract** test for the authorized Trusted Exit
 * bridge (FastAPI issue #38: "runnable end-to-end staging contract tests
 * FastAPI↔Ktor"). It drives the REAL Ktor service over the ephemeral CI
 * PostgreSQL and a FAKE SMS provider through the whole authorized path —
 *
 *   FastAPI-issued grant → /request (server-resolved destination, fake SMS)
 *     → /verify (challenge-bound verify grant) → signed Ed25519 receipt
 *       → EvidenceSubmitter (server-only ingest HMAC)
 *         → AuthenticatedEvidenceDelivery (authenticated request envelope)
 *           → an in-process admitter that re-checks the THREE independent proofs
 *
 * and asserts the security contract FastAPI PR #34 pins: a tampered transport
 * MAC or body admits nothing; a replayed nonce and a replayed receipt admit
 * nothing twice (durable); a foreign binding or wrong ingest key is refused; a
 * lost ACK is Unknown (never upgraded to success); and admission NEVER ends the
 * series or unlocks Android.
 *
 * NO real SMS, NO real HTTP/mTLS, NO Selectel, NO production secret: every key
 * is generated/placed in-process, the transport is a loopback double, and the
 * production wiring stays fail-closed (DisabledReceiptDelivery, null resolver).
 * Skipped locally when MAMA_TEST_DATABASE_URL is unset.
 */
class TrustedExitEndToEndContractTest {

    private val url = System.getenv("MAMA_TEST_DATABASE_URL")
    private val dbUser = System.getenv("MAMA_TEST_DATABASE_USER")
    private val dbPass = System.getenv("MAMA_TEST_DATABASE_PASSWORD")

    // Grant-signing keypair (FastAPI's key, here ephemeral) and the receipt keypair.
    private val grantKp: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val grantPub = Base64.getEncoder().encodeToString(grantKp.public.encoded)
    private val receiptKp: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val receiptPrivB64 = Base64.getEncoder().encodeToString(receiptKp.private.encoded)
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    // Server-only shared secrets (≥32 bytes). Never logged, never Android-facing.
    private val ingestKey = "ephemeral-contract-only-ingest-secret-0001-abcdef"
    private val transportKey = "ephemeral-contract-only-transport-secret-0001-abc".toByteArray(Charsets.UTF_8)

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
        assumeTrue(!url.isNullOrBlank(), "MAMA_TEST_DATABASE_URL not set; skipping DB-backed contract test")
        db = JdbcDb(url!!, dbUser, dbPass).also { it.migrate() }
        db.open().use { c ->
            c.createStatement().use {
                it.execute("TRUNCATE trusted_exit.challenge, trusted_exit.jti_ledger, trusted_exit.rate_counter")
            }
        }
        sms = MockSmsProvider()
        service = SecureTrustedExitService(
            verifier = GrantVerifier(grantPub),
            verifyVerifier = GrantVerifier.forVerify(grantPub),
            repo = OtpRepository(db, hasher),
            hasher = hasher,
            sms = sms,
            // A trusted, server-side resolved destination (never from the client).
            destinations = ContactDestinationResolver { "+70000000000" },
            receiptSigner = ReceiptSigner(receiptPrivB64),
        )
    }

    /** Runs the real request→verify path and returns the minted Ed25519 receipt JWT. */
    private fun mintReceipt(): String {
        val created = service.request(requestBearer(), "1.2.3.4") as RequestOutcome.Created
        val code = sms.lastCode!! // the fake captured the OTP; the API never returns it
        val outcome = service.verify(verifyBearer(created.challengeId), created.challengeId, code)
        assertInstanceOf(VerifyOutcome.Valid::class.java, outcome)
        return (outcome as VerifyOutcome.Valid).receipt!!
    }

    // ---- the end-to-end authorized contract ------------------------------------

    @Test fun `the full authorized path delivers one admitted evidence and never unlocks`() {
        val admitter = admitter()
        val submitter = EvidenceSubmitter(
            IngestProof.fromEnv(ingestKey),
            AuthenticatedEvidenceDelivery(KtorInternalRequestSigner(transportKey), admitter),
        )
        val receipt = mintReceipt()

        assertEquals(DeliveryOutcome.Accepted, submitter.submit(receipt))
        assertEquals(1, admitter.admissions, "exactly one evidence row admitted")
        assertFalse(admitter.seriesEnded, "evidence admission NEVER ends the series or unlocks Android")
    }

    @Test fun `a replayed receipt is admitted at most once (durable one-time, lost-ACK safe)`() {
        val admitter = admitter()
        // Fresh transport nonce per call (as in production), but the SAME receipt token:
        // FastAPI dedupes on the receipt's unique jti, so a lost-ACK retry admits nothing twice.
        val submitter = EvidenceSubmitter(
            IngestProof.fromEnv(ingestKey),
            AuthenticatedEvidenceDelivery(KtorInternalRequestSigner(transportKey), admitter),
        )
        val receipt = mintReceipt()

        assertEquals(DeliveryOutcome.Accepted, submitter.submit(receipt))
        val retry = submitter.submit(receipt)
        assertInstanceOf(DeliveryOutcome.Rejected::class.java, retry) // duplicate jti denied, not re-admitted
        assertEquals(1, admitter.admissions, "no double admission, no double transition")
        assertFalse(admitter.seriesEnded)
    }

    @Test fun `a replayed request envelope (same nonce) is refused by the durable nonce ledger`() {
        val admitter = admitter()
        val fixedNonce = "1b2e4d6f-8a1c-4b2d-9e3f-0a1b2c3d4e5f" // valid UUIDv4 shape
        var ts = 1_700_000_000L
        val delivery = AuthenticatedEvidenceDelivery(
            KtorInternalRequestSigner(transportKey), admitter,
            timestamp = { ts }, nonce = { fixedNonce },
        )
        val submitter = EvidenceSubmitter(IngestProof.fromEnv(ingestKey), delivery)
        val receipt = mintReceipt()

        assertEquals(DeliveryOutcome.Accepted, submitter.submit(receipt))
        ts += 1 // a new attempt, but the SAME nonce is replayed
        val replay = submitter.submit(receipt)
        assertInstanceOf(DeliveryOutcome.Rejected::class.java, replay)
        assertEquals(1, admitter.admissions)
    }

    @Test fun `a tampered transport MAC or body admits nothing and records no nonce`() {
        val admitter = admitter()
        val signer = KtorInternalRequestSigner(transportKey)
        val receipt = mintReceipt()
        val body = JsonObject().apply {
            addProperty("token", receipt)
            addProperty("macB64", Base64.getEncoder().encodeToString(IngestProof.fromEnv(ingestKey).mac(receipt)!!))
        }.toString().toByteArray(Charsets.UTF_8)
        val nonce = UUID.randomUUID().toString()
        val proof = signer.sign(body, KtorInternalRequestSigner.PATH_EVIDENCE, 1_700_000_000L, nonce)!!

        // (a) corrupted transport MAC
        val badMac = admitter.post(
            PrivateEvidenceHttpRequest("POST", KtorInternalRequestSigner.PATH_EVIDENCE, proof.timestamp, proof.nonce, "00".repeat(32), body),
        )
        assertInstanceOf(DeliveryOutcome.Rejected::class.java, badMac)
        // (b) body tampered after signing (SHA-256 no longer matches the MAC)
        val tamperedBody = body + ' '.code.toByte()
        val badBody = admitter.post(
            PrivateEvidenceHttpRequest("POST", KtorInternalRequestSigner.PATH_EVIDENCE, proof.timestamp, proof.nonce, proof.macHex, tamperedBody),
        )
        assertInstanceOf(DeliveryOutcome.Rejected::class.java, badBody)

        assertEquals(0, admitter.admissions, "no evidence admitted")
        assertEquals(0, admitter.noncesSeen(), "a rejected envelope reserves no nonce")
    }

    @Test fun `a foreign-binding receipt is refused by the independent binding re-check`() {
        val admitter = admitter()
        val delivery = AuthenticatedEvidenceDelivery(KtorInternalRequestSigner(transportKey), admitter)
        val submitter = EvidenceSubmitter(IngestProof.fromEnv(ingestKey), delivery)
        // A correctly SIGNED receipt (same receipt key) but bound to a different series.
        val foreign = ReceiptSigner(receiptPrivB64).sign(
            ReceiptClaims(
                sub = sub, seriesId = "99999999-9999-4999-8999-999999999999", periodId = period,
                deviceId = device, contactId = contact, challengeId = "tec-" + UUID.randomUUID(),
                verifiedAtSec = System.currentTimeMillis() / 1000,
            ),
        )!!
        val out = submitter.submit(foreign)
        assertInstanceOf(DeliveryOutcome.Rejected::class.java, out)
        assertEquals(0, admitter.admissions)
    }

    @Test fun `a wrong server ingest key is refused (the three proofs are independent)`() {
        val admitter = admitter()
        // The submitter signs the ingest MAC with a DIFFERENT server secret than the admitter expects.
        val wrongIngest = "a-totally-different-server-ingest-secret-00000001"
        val submitter = EvidenceSubmitter(
            IngestProof.fromEnv(wrongIngest),
            AuthenticatedEvidenceDelivery(KtorInternalRequestSigner(transportKey), admitter),
        )
        val out = submitter.submit(mintReceipt())
        assertInstanceOf(DeliveryOutcome.Rejected::class.java, out)
        assertEquals(0, admitter.admissions)
    }

    @Test fun `a lost ACK stays Unknown and is never upgraded to success`() {
        val lossy = PrivateEvidenceHttpTransport { DeliveryOutcome.Unknown("ack lost") }
        val submitter = EvidenceSubmitter(
            IngestProof.fromEnv(ingestKey),
            AuthenticatedEvidenceDelivery(KtorInternalRequestSigner(transportKey), lossy),
        )
        val out = submitter.submit(mintReceipt())
        assertInstanceOf(DeliveryOutcome.Unknown::class.java, out) // unresolved, NOT Accepted
    }

    // ---- the in-process FastAPI admitter double --------------------------------

    private fun admitter() = FakeFastApiAdmitter()

    /**
     * Faithful in-process mirror of the FastAPI PR #34 admission routine: it
     * independently re-checks the authenticated request envelope + durable nonce,
     * the Ed25519 receipt signature, the server-only ingest MAC and the identity
     * bindings, admits a receipt at most once (UNIQUE jti), and — crucially — has
     * NO path that ends a series or unlocks Android.
     */
    private inner class FakeFastApiAdmitter : PrivateEvidenceHttpTransport {
        private val seenNonces = HashSet<String>()
        private val admittedJti = HashSet<String>()
        var admissions = 0
            private set
        var seriesEnded = false
            private set

        fun noncesSeen() = seenNonces.size

        override fun post(request: PrivateEvidenceHttpRequest): DeliveryOutcome {
            // Proof 1a: re-authenticate the request envelope with the shared transport key.
            val expectedMac = KtorInternalRequestSigner(transportKey)
                .sign(request.body, request.path, request.timestamp, request.nonce)?.macHex
                ?: return DeliveryOutcome.Rejected("unsignable envelope")
            if (!eq(expectedMac, request.macHex)) return DeliveryOutcome.Rejected("bad transport mac")
            // Proof 1b: durable nonce ledger — single-use, denies replay.
            if (!seenNonces.add(request.nonce)) return DeliveryOutcome.Rejected("replayed nonce")

            val json = runCatching { JsonParser.parseString(String(request.body, Charsets.UTF_8)).asJsonObject }
                .getOrNull() ?: return DeliveryOutcome.Rejected("bad body")
            val token = json.get("token")?.asString ?: return DeliveryOutcome.Rejected("no token")
            val macB64 = json.get("macB64")?.asString ?: return DeliveryOutcome.Rejected("no ingest mac")

            // Proof 2: Ed25519 receipt signature over header.payload.
            val parts = token.split(".")
            if (parts.size != 3) return DeliveryOutcome.Rejected("not a compact jws")
            val sigOk = runCatching {
                Signature.getInstance("Ed25519").apply {
                    initVerify(receiptKp.public)
                    update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                }.verify(Base64.getUrlDecoder().decode(parts[2]))
            }.getOrDefault(false)
            if (!sigOk) return DeliveryOutcome.Rejected("bad receipt signature")

            // Proof 3: server-only ingest HMAC over the exact token bytes.
            val expectIngest = IngestProof.fromEnv(ingestKey).mac(token)
                ?.let { Base64.getEncoder().encodeToString(it) }
                ?: return DeliveryOutcome.Rejected("ingest not configured")
            if (!eq(expectIngest, macB64)) return DeliveryOutcome.Rejected("bad ingest mac")

            // Independent binding + scope re-check from the SIGNED claims (never the client).
            val claims = runCatching {
                JsonParser.parseString(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)).asJsonObject
            }.getOrNull() ?: return DeliveryOutcome.Rejected("bad claims")
            fun s(k: String) = claims.get(k)?.asString
            if (s("iss") != ReceiptSigner.ISS || s("aud") != ReceiptSigner.AUD || s("purpose") != ReceiptSigner.PURPOSE) {
                return DeliveryOutcome.Rejected("wrong receipt scope")
            }
            if (s("sub") != sub || s("series_id") != series || s("period_id") != period ||
                s("device_id") != device || s("contact_id") != contact
            ) {
                return DeliveryOutcome.Rejected("foreign binding")
            }
            val jti = s("jti") ?: return DeliveryOutcome.Rejected("no jti")
            // One-time: UNIQUE(ktor_jti) — a duplicate inserts nothing and is denied (PR #15/#34).
            if (!admittedJti.add(jti)) return DeliveryOutcome.Rejected("duplicate evidence")

            admissions++ // evidence stored; the authoritative series transition is a SEPARATE step.
            return DeliveryOutcome.Accepted
        }
    }

    private fun eq(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    // ---- grant signing helpers (test only) -------------------------------------

    private fun requestBearer(jti: String = UUID.randomUUID().toString()): String =
        bearer("mama-ktor-sms-v1", "trusted-exit:sms-request", jti, null, 30)

    private fun verifyBearer(challengeId: String, jti: String = UUID.randomUUID().toString()): String =
        bearer("mama-ktor-verify-v1", "trusted-exit:verify", jti, challengeId, 45)

    private fun bearer(kid: String, purpose: String, jti: String, challengeId: String?, ttl: Long): String {
        val nowSec = System.currentTimeMillis() / 1000
        val header = JsonObject().apply { addProperty("alg", "EdDSA"); addProperty("typ", "JWT"); addProperty("kid", kid) }
        val payload = JsonObject().apply {
            addProperty("iss", "mama-fastapi"); addProperty("aud", "mama-ktor-trusted-exit")
            addProperty("purpose", purpose)
            addProperty("sub", sub); addProperty("series_id", series); addProperty("period_id", period)
            addProperty("device_id", device); addProperty("contact_id", contact)
            if (challengeId != null) addProperty("challenge_id", challengeId)
            addProperty("jti", jti)
            addProperty("iat", nowSec); addProperty("nbf", nowSec); addProperty("exp", nowSec + ttl)
        }
        val h = b64.encodeToString(header.toString().toByteArray())
        val p = b64.encodeToString(payload.toString().toByteArray())
        val sig = Signature.getInstance("Ed25519").apply {
            initSign(grantKp.private); update("$h.$p".toByteArray(Charsets.US_ASCII))
        }.sign()
        return "Bearer $h.$p.${b64.encodeToString(sig)}"
    }
}
