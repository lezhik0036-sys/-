package app.mama.trustedexit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The production Trusted Exit HTTP client, exercised through a fake transport
 * (no network, no server). Robolectric is used only so android's org.json works.
 *
 * The central safety property: a server error, timeout, non-2xx or malformed
 * reply is NEVER a VALID and NEVER a wrong attempt — verify maps them to Offline
 * (retry, no bypass), request maps them to Failed (no challenge, stays locked).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpTrustedExitBackendTest {

    private class FakeTransport : HttpTransport {
        var postResponse: HttpResponse = HttpResponse(200, "{}")
        var getResponse: HttpResponse = HttpResponse(200, "{}")
        var throwOnPost = false
        var throwOnGet = false
        var postCount = 0
        var getCount = 0
        val postBodies = mutableListOf<Pair<String, String>>()

        override fun post(path: String, jsonBody: String): HttpResponse {
            postCount++
            postBodies.add(path to jsonBody)
            if (throwOnPost) throw IOException("network down")
            return postResponse
        }

        override fun get(path: String): HttpResponse {
            getCount++
            if (throwOnGet) throw IOException("network down")
            return getResponse
        }
    }

    private val req = CreateChallengeRequest(
        sessionId = "s1", kind = "END_SESSION", idempotencyKey = "s1", contactPhone = "+79990000000",
    )
    private fun backend(t: FakeTransport, enabled: Boolean = true) = HttpTrustedExitBackend(t, enabled)

    // ---- request --------------------------------------------------------------

    @Test
    fun `CREATED maps to a handle with the server's fields`() {
        val t = FakeTransport().apply {
            postResponse = HttpResponse(200, """{"status":"CREATED","challengeId":"tec-1","kind":"END_SESSION","issuedAtMs":1000,"expiresAtMs":901000,"attemptsLeft":3,"delivery":"SMS_ACCEPTED"}""")
        }
        val r = backend(t).createChallenge(req)
        assertTrue(r is CreateChallengeResult.Created)
        r as CreateChallengeResult.Created
        assertEquals("tec-1", r.handle.challengeId)
        assertEquals(3, r.handle.attemptsLeft)
        assertEquals(901000L, r.handle.expiresAtMs)
        assertEquals(DeliveryStatus.SMS_ACCEPTED, r.delivery)
    }

    @Test
    fun `the request body carries sessionId, kind, idempotencyKey and contactPhone`() {
        val t = FakeTransport().apply { postResponse = HttpResponse(200, """{"status":"CREATED","challengeId":"tec-1","attemptsLeft":3}""") }
        backend(t).createChallenge(req)
        assertEquals("/trusted-exit/request", t.postBodies.last().first)
        val body = JSONObject(t.postBodies.last().second)
        assertEquals("s1", body.getString("sessionId"))
        assertEquals("END_SESSION", body.getString("kind"))
        assertEquals("s1", body.getString("idempotencyKey"))
        assertEquals("+79990000000", body.getString("contactPhone"))
    }

    @Test
    fun `REJECTED COOLDOWN and ALREADY_PENDING map through`() {
        val cooldown = FakeTransport().apply { postResponse = HttpResponse(200, """{"status":"REJECTED","reason":"COOLDOWN","retryAtMs":123456}""") }
        val r1 = backend(cooldown).createChallenge(req)
        assertTrue(r1 is CreateChallengeResult.Rejected)
        assertEquals(CreateChallengeResult.Reason.COOLDOWN, (r1 as CreateChallengeResult.Rejected).reason)
        assertEquals(123456L, r1.retryAtMs)

        val pending = FakeTransport().apply { postResponse = HttpResponse(200, """{"status":"REJECTED","reason":"ALREADY_PENDING"}""") }
        val r2 = backend(pending).createChallenge(req)
        assertEquals(CreateChallengeResult.Reason.ALREADY_PENDING, (r2 as CreateChallengeResult.Rejected).reason)
    }

    @Test
    fun `FAILED status maps to Failed`() {
        val t = FakeTransport().apply { postResponse = HttpResponse(200, """{"status":"FAILED","reason":"provider refused"}""") }
        assertTrue(backend(t).createChallenge(req) is CreateChallengeResult.Failed)
    }

    @Test
    fun `request network error is Failed, never Created`() {
        val t = FakeTransport().apply { throwOnPost = true }
        assertTrue(backend(t).createChallenge(req) is CreateChallengeResult.Failed)
    }

    @Test
    fun `request non-2xx is Failed`() {
        val t = FakeTransport().apply { postResponse = HttpResponse(500, "oops") }
        assertTrue(backend(t).createChallenge(req) is CreateChallengeResult.Failed)
    }

    @Test
    fun `request malformed JSON is Failed`() {
        val t = FakeTransport().apply { postResponse = HttpResponse(200, "not json") }
        assertTrue(backend(t).createChallenge(req) is CreateChallengeResult.Failed)
    }

    @Test
    fun `an unconfigured backend never calls the network and fails closed`() {
        val t = FakeTransport()
        assertTrue(backend(t, enabled = false).createChallenge(req) is CreateChallengeResult.Failed)
        assertEquals(0, t.postCount)
        assertEquals(VerifyResult.Offline, backend(t, enabled = false).verify("tec-1", "12345"))
        assertEquals(0, t.postCount)
        assertFalse(backend(t, enabled = false).isReadyNow())
    }

    @Test
    fun `a repeated request forwards the same idempotency key and reused challenge`() {
        val t = FakeTransport().apply { postResponse = HttpResponse(200, """{"status":"CREATED","challengeId":"tec-SAME","attemptsLeft":3}""") }
        val b = backend(t)
        val first = b.createChallenge(req) as CreateChallengeResult.Created
        val second = b.createChallenge(req) as CreateChallengeResult.Created
        assertEquals(first.handle.challengeId, second.handle.challengeId)
        assertEquals(JSONObject(t.postBodies[0].second).getString("idempotencyKey"),
            JSONObject(t.postBodies[1].second).getString("idempotencyKey"))
    }

    // ---- verify ---------------------------------------------------------------

    @Test
    fun `VALID INVALID EXPIRED LOCKED UNKNOWN map through`() {
        assertEquals(VerifyResult.Valid, backend(FakeTransport().apply { postResponse = HttpResponse(200, """{"status":"VALID"}""") }).verify("tec-1", "12345"))
        assertEquals(VerifyResult.Invalid(2), backend(FakeTransport().apply { postResponse = HttpResponse(200, """{"status":"INVALID","attemptsLeft":2}""") }).verify("tec-1", "00000"))
        assertEquals(VerifyResult.Expired, backend(FakeTransport().apply { postResponse = HttpResponse(200, """{"status":"EXPIRED"}""") }).verify("tec-1", "12345"))
        val locked = backend(FakeTransport().apply { postResponse = HttpResponse(200, """{"status":"LOCKED","retryAtMs":987654}""") }).verify("tec-1", "00000")
        assertTrue(locked is VerifyResult.Locked)
        assertEquals(987654L, (locked as VerifyResult.Locked).retryAtMs)
        assertEquals(VerifyResult.Unknown, backend(FakeTransport().apply { postResponse = HttpResponse(200, """{"status":"UNKNOWN"}""") }).verify("tec-1", "12345"))
    }

    @Test
    fun `the verify body carries challengeId and code`() {
        val t = FakeTransport().apply { postResponse = HttpResponse(200, """{"status":"INVALID","attemptsLeft":2}""") }
        backend(t).verify("tec-42", "54321")
        assertEquals("/trusted-exit/verify", t.postBodies.last().first)
        val body = JSONObject(t.postBodies.last().second)
        assertEquals("tec-42", body.getString("challengeId"))
        assertEquals("54321", body.getString("code"))
    }

    @Test
    fun `verify network error is Offline, not a wrong attempt and not a pass`() {
        val t = FakeTransport().apply { throwOnPost = true }
        val r = backend(t).verify("tec-1", "12345")
        assertEquals(VerifyResult.Offline, r)
    }

    @Test
    fun `verify non-2xx is Offline`() {
        val t = FakeTransport().apply { postResponse = HttpResponse(503, "unavailable") }
        assertEquals(VerifyResult.Offline, backend(t).verify("tec-1", "12345"))
    }

    @Test
    fun `verify malformed JSON is Offline`() {
        val t = FakeTransport().apply { postResponse = HttpResponse(200, "<html>") }
        assertEquals(VerifyResult.Offline, backend(t).verify("tec-1", "12345"))
    }

    @Test
    fun `verify unrecognized status is Offline, never a pass`() {
        val t = FakeTransport().apply { postResponse = HttpResponse(200, """{"status":"WHATEVER"}""") }
        assertEquals(VerifyResult.Offline, backend(t).verify("tec-1", "12345"))
    }

    // ---- readiness ------------------------------------------------------------

    @Test
    fun `ready is true only on a 2xx ready reply`() {
        assertTrue(backend(FakeTransport().apply { getResponse = HttpResponse(200, """{"status":"ready","smsProvider":"sms.ru"}""") }).isReadyNow())
    }

    @Test
    fun `not_ready, non-2xx and an unavailable ready all fail closed`() {
        assertFalse(backend(FakeTransport().apply { getResponse = HttpResponse(503, """{"status":"not_ready"}""") }).isReadyNow())
        assertFalse(backend(FakeTransport().apply { getResponse = HttpResponse(200, """{"status":"something"}""") }).isReadyNow())
        assertFalse(backend(FakeTransport().apply { throwOnGet = true }).isReadyNow())
    }
}
