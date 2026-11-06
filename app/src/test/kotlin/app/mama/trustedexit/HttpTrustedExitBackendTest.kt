package app.mama.trustedexit

import app.mama.account.AccountHttpTransport
import app.mama.account.AccountSession
import app.mama.account.DeviceKey
import app.mama.account.HttpReply
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-side proof of the REAL server-authoritative Trusted Exit client against a
 * fake FastAPI server that verifies the device Ed25519 signature exactly as the
 * backend does. No device, no Keystore, no network: the crypto is an ephemeral
 * JVM Ed25519 key behind the same [DeviceKey] seam the production Keystore key
 * implements. Robolectric only for a real org.json.
 *
 * Covers the properties the owner's P0 gate and issue #47 require:
 *   - happy path: proof -> request (code_sent) -> verify(correct) -> exit;
 *   - lost-ACK: a persisted idempotency key recovers the SAME request with NO
 *     second SMS, both on a plain retry and after an ambiguous 503;
 *   - 3-attempt budget: wrong codes count down then close the request;
 *   - async receipt admission finalized via /status (no extra verify attempt);
 *   - transparent re-auth on a 401;
 *   - fail-closed when nothing is provisioned;
 *   - server readiness reflects a provider-VERIFIED contact only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpTrustedExitBackendTest {

    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** Ephemeral Ed25519 key standing in for the Keystore-protected device key. */
    private val deviceKey = object : DeviceKey {
        private val rawPub = keyPair.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }
        override fun publicKeyB64(): String = b64.encodeToString(rawPub)
        override fun sign(message: ByteArray): ByteArray {
            val s = Signature.getInstance("Ed25519")
            s.initSign(keyPair.private)
            s.update(message)
            return s.sign()
        }
    }

    private fun verifySig(message: ByteArray, sig: ByteArray): Boolean = runCatching {
        val s = Signature.getInstance("Ed25519")
        s.initVerify(keyPair.public)
        s.update(message)
        s.verify(sig)
    }.getOrDefault(false)

    private val deviceId = UUID.randomUUID().toString()
    private var token = "tok-1"
    private var forgetCount = 0
    private val session = object : TrustedExitSessionSource {
        override fun ensureSession(): AccountSession? =
            if (token.isEmpty()) null else AccountSession("user-1", deviceId, token)
        override fun forgetSession() { forgetCount++; token = "tok-2" }
    }

    private val seriesId = UUID.randomUUID().toString()
    private val periodId = UUID.randomUUID().toString()
    private var context: ExitContextSource =
        ExitContextSource { ExitContext(seriesId, periodId) }

    private val keyStore = InMemoryExitRequestKeyStore()
    private val server = FakeServer()

    private fun backend() = HttpTrustedExitBackend(
        transport = server, deviceKey = deviceKey, session = session,
        context = context, requestKeys = keyStore, statusPollAttempts = 3,
    )

    private fun create(key: String, kind: String = "END_SESSION") =
        backend().createChallenge(CreateChallengeRequest("sess-1", kind, key))

    // --- scenarios -----------------------------------------------------------

    @Test
    fun happyPath_codeSent_then_verify_exits() {
        val created = create("A") as CreateChallengeResult.Created
        assertEquals(DeliveryStatus.SMS_ACCEPTED, created.delivery)
        assertEquals(1, server.proofCount)
        assertEquals(1, server.sendCount)

        assertEquals(VerifyResult.Valid, backend().verify(created.handle.challengeId, server.otp))
    }

    @Test
    fun retrySameKey_recovers_without_second_send() {
        val first = create("A") as CreateChallengeResult.Created
        // Same caller key -> recover via /status, never a new proof/request/SMS.
        val second = create("A") as CreateChallengeResult.Created
        assertEquals(first.handle.challengeId, second.handle.challengeId)
        assertEquals(1, server.proofCount)
        assertEquals(1, server.requestCount)
        assertEquals(1, server.sendCount)
        assertTrue(server.statusCount >= 1)
    }

    @Test
    fun ambiguous503_recovers_same_request_no_resend() {
        server.ambiguousFirstRequest = true
        val created = create("A") as CreateChallengeResult.Created
        // The outcome is "created, not confirmed": honest, never a false "sent".
        assertEquals(DeliveryStatus.REQUEST_CREATED, created.delivery)
        // The SMS was sent at most once; the same-key replay never sends again.
        assertEquals(1, server.sendCount)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun wrongCodes_countDown_then_close_request() {
        val created = create("A") as CreateChallengeResult.Created
        val id = created.handle.challengeId
        assertEquals(VerifyResult.Invalid(2), backend().verify(id, "00000"))
        assertEquals(VerifyResult.Invalid(1), backend().verify(id, "00000"))
        assertEquals(VerifyResult.Invalid(0), backend().verify(id, "00000"))
        // Request is now closed; a further attempt is not "open" -> Unknown.
        assertEquals(VerifyResult.Unknown, backend().verify(id, server.otp))
    }

    @Test
    fun acceptedButPending_isFinalizedViaStatus() {
        server.verifyReturnsPendingThenExit = true
        val created = create("A") as CreateChallengeResult.Created
        // verify() gets "pending" (async receipt admission), then polls /status
        // which completes the exit without spending another attempt.
        assertEquals(VerifyResult.Valid, backend().verify(created.handle.challengeId, server.otp))
        assertTrue(server.statusCount >= 1)
    }

    @Test
    fun reauthenticates_on_401() {
        server.proofFirst401 = true
        val created = create("A") as CreateChallengeResult.Created
        assertEquals(DeliveryStatus.SMS_ACCEPTED, created.delivery)
        assertEquals(1, forgetCount)       // the stale bearer was dropped once
        assertEquals(1, server.proofCount) // the 401 did not count as a served proof
    }

    @Test
    fun failsClosed_whenNotProvisioned() {
        context = ExitContextSource { null }
        val r = create("A")
        assertTrue(r is CreateChallengeResult.Failed)
        assertEquals(0, server.proofCount)
        assertEquals(0, server.sendCount)
    }

    @Test
    fun failsClosed_whenNoSession() {
        token = ""
        val r = create("A")
        assertTrue(r is CreateChallengeResult.Failed)
        assertEquals(VerifyResult.Unknown, backend().verify("whatever", server.otp))
    }

    @Test
    fun deferredBackend_failsClosed_whenUnconfigured() {
        val deferred = DeferredTrustedExitBackend { null }
        val create = deferred.createChallenge(CreateChallengeRequest("s", "END_SESSION", "A"))
        assertTrue(create is CreateChallengeResult.Failed)
        assertEquals(VerifyResult.Unknown, deferred.verify("x", "12345"))
    }

    @Test
    fun deferredBackend_delegates_whenConfigured() {
        val deferred = DeferredTrustedExitBackend { backend() }
        val created = deferred.createChallenge(CreateChallengeRequest("s", "END_SESSION", "A"))
        assertTrue(created is CreateChallengeResult.Created)
    }

    @Test
    fun serverReadiness_requires_verified_contact() {
        server.readinessReady = false
        backend().serverReadiness()!!.let {
            assertEquals(false, it.contactVerified)
            assertEquals(false, it.ready)
        }
        server.readinessReady = true
        backend().serverReadiness()!!.let {
            assertEquals(true, it.contactVerified)
            assertEquals(true, it.ready)
        }
    }

    // --- fake FastAPI server -------------------------------------------------

    private inner class FakeServer : AccountHttpTransport {
        var proofCount = 0
        var requestCount = 0
        var sendCount = 0
        var statusCount = 0

        var otp = "12345"
        var ambiguousFirstRequest = false
        var proofFirst401 = false
        var verifyReturnsPendingThenExit = false
        var readinessReady = false

        private val proofs = HashMap<String, ByteArray>()
        private val rows = HashMap<String, Row>()
        private val byKey = HashMap<String, String>()
        private var proof401Served = false

        private inner class Row(
            var status: String, var attempts: Int = 0, var accepted: Boolean = false,
        )

        private fun clientStatus(s: String) = when (s) {
            "dispatching" -> "pending"
            "dispatched" -> "code_sent"
            "exited" -> "exit_confirmed"
            else -> s
        }

        override fun postJson(path: String, jsonBody: String, bearer: String?): HttpReply {
            if (bearer.isNullOrEmpty()) return HttpReply(401, "{}")
            val j = JSONObject(jsonBody)
            return when (path) {
                "/v1/trusted-exit/proof-challenges" -> {
                    if (proofFirst401 && !proof401Served) { proof401Served = true; return HttpReply(401, "{}") }
                    proofCount++
                    val proofId = UUID.randomUUID().toString()
                    val nonce = "n-" + UUID.randomUUID()
                    val message = ("MAMA/trusted-exit/op/v1\n" + listOf(
                        j.getString("series_id"), j.getString("period_id"),
                        j.getString("device_id"), "sms-request", nonce,
                    ).joinToString("\n")).toByteArray(Charsets.US_ASCII)
                    proofs[proofId] = message
                    HttpReply(200, JSONObject()
                        .put("proof_id", proofId).put("nonce", nonce)
                        .put("message_b64", b64.encodeToString(message))
                        .put("expires_at", "2099-01-01T00:00:00+00:00").toString())
                }
                "/v1/trusted-exit/request" -> {
                    val key = j.getString("idempotency_key")
                    // Idempotency / recovery short-circuit (no re-prove, no re-send).
                    byKey[key]?.let { rid ->
                        return HttpReply(200, JSONObject()
                            .put("request_id", rid).put("status", clientStatus(rows[rid]!!.status)).toString())
                    }
                    val proofId = j.optString("proof_id", "")
                    val msg = proofs[proofId] ?: return HttpReply(403, "{}")
                    if (!verifySig(msg, unb64.decode(j.getString("signature_b64")))) return HttpReply(403, "{}")
                    proofs.remove(proofId) // one-time
                    requestCount++
                    sendCount++ // the server asks Ktor to dispatch the OTP
                    val rid = UUID.randomUUID().toString()
                    if (ambiguousFirstRequest) {
                        rows[rid] = Row("dispatching"); byKey[key] = rid
                        return HttpReply(503, "{}") // ACK lost; row durable, maybe sent
                    }
                    rows[rid] = Row("dispatched"); byKey[key] = rid
                    HttpReply(200, JSONObject().put("request_id", rid).put("status", "code_sent").toString())
                }
                "/v1/trusted-exit/verify" -> {
                    val rid = j.getString("request_id")
                    val code = j.getString("code")
                    val r = rows[rid] ?: return HttpReply(404, "{}")
                    if (r.status == "exited") return HttpReply(200, JSONObject().put("status", "exit_confirmed").toString())
                    if (r.status != "dispatched") return HttpReply(409, "{}")
                    if (r.attempts >= MAX) { r.status = "failed"; return HttpReply(429, "{}") }
                    if (!Regex("^[0-9]{5}$").matches(code)) return HttpReply(422, "{}")
                    r.attempts++
                    if (code == otp) {
                        if (verifyReturnsPendingThenExit) {
                            r.accepted = true
                            return HttpReply(200, JSONObject().put("status", "pending").toString())
                        }
                        r.status = "exited"
                        return HttpReply(200, JSONObject().put("status", "exit_confirmed").toString())
                    }
                    if (r.attempts >= MAX) r.status = "failed"
                    HttpReply(200, JSONObject()
                        .put("status", "invalid_code")
                        .put("attempts_remaining", maxOf(0, MAX - r.attempts)).toString())
                }
                else -> HttpReply(404, "{}")
            }
        }

        override fun getJson(path: String, bearer: String?): HttpReply {
            if (bearer.isNullOrEmpty()) return HttpReply(401, "{}")
            if (path.startsWith("/v1/trusted-exit/status/")) {
                statusCount++
                val rid = path.substringAfterLast("/")
                val r = rows[rid] ?: return HttpReply(404, "{}")
                if (r.accepted && r.status != "exited") r.status = "exited" // receipt now admitted
                return HttpReply(200, JSONObject()
                    .put("request_id", rid).put("status", clientStatus(r.status))
                    .put("verify_attempts", r.attempts)
                    .put("attempts_remaining", maxOf(0, MAX - r.attempts)).toString())
            }
            if (path.endsWith("/readiness")) {
                return HttpReply(200, JSONObject()
                    .put("active", true).put("contact_selected", true)
                    .put("contact_verified", readinessReady)
                    .put("has_registered_period", true)
                    .put("ready", readinessReady).toString())
            }
            return HttpReply(404, "{}")
        }
    }

    private companion object {
        const val MAX = 3
    }
}
