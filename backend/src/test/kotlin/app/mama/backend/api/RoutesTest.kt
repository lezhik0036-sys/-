package app.mama.backend.api

import app.mama.backend.module
import app.mama.backend.trustedexit.MockSmsProvider
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.gson.gson
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The HTTP API end to end through Ktor's test host. The mock provider lets the
 * test read the delivered code the way only a test harness can; the API's own
 * responses never contain it.
 */
class RoutesTest {

    private fun test(block: suspend TestScope.() -> Unit) = testApplication {
        val sms = MockSmsProvider()
        application { module(sms = sms) }
        val client = createClient { install(ContentNegotiation) { gson { } } }
        TestScope(client, sms).block()
    }

    private class TestScope(val client: io.ktor.client.HttpClient, val sms: MockSmsProvider)

    private suspend fun TestScope.request(key: String = "s1") = client.post("/trusted-exit/request") {
        contentType(ContentType.Application.Json)
        setBody(CreateChallengeBody(sessionId = "s1", kind = "END_SESSION", idempotencyKey = key, contactPhone = "+79990000000"))
    }.body<ChallengeResponse>()

    private suspend fun TestScope.verify(id: String, code: String) = client.post("/trusted-exit/verify") {
        contentType(ContentType.Application.Json)
        setBody(VerifyBody(challengeId = id, code = code))
    }.body<VerifyResponse>()

    @Test
    fun `health reports ok`() = test {
        assertEquals("ok", client.get("/health").body<HealthResponse>().status)
    }

    @Test
    fun `request creates a challenge, response carries a handle but no code`() = test {
        val r = request()
        assertEquals("CREATED", r.status)
        assertNotNull(r.challengeId)
        assertEquals(3, r.attemptsLeft)
        assertEquals("SMS_ACCEPTED", r.delivery)
        // The delivered code reached the provider, but never the client.
        assertNotNull(sms.lastCode)
        assertFalse(r.toString().contains(sms.lastCode!!))
    }

    @Test
    fun `the delivered code verifies as VALID over HTTP`() = test {
        val id = request().challengeId!!
        assertEquals("VALID", verify(id, sms.lastCode!!).status)
    }

    @Test
    fun `wrong codes decrement then lock after three`() = test {
        val id = request().challengeId!!
        val wrong = wrongCodeFor(sms.lastCode!!)
        assertEquals(2, verify(id, wrong).attemptsLeft)
        assertEquals(1, verify(id, wrong).attemptsLeft)
        val locked = verify(id, wrong)
        assertEquals("LOCKED", locked.status)
        assertNotNull(locked.retryAtMs)
        // The old challenge is now invalid: the right code is locked out too.
        assertEquals("LOCKED", verify(id, sms.lastCode!!).status)
    }

    @Test
    fun `a repeat request reuses the challenge and sends one SMS`() = test {
        val first = request().challengeId
        val second = request().challengeId
        assertEquals(first, second)
        assertEquals(1, sms.calls)
    }

    @Test
    fun `an unknown challenge is UNKNOWN, not a wrong attempt`() = test {
        val r = verify("tec-nope", "12345")
        assertEquals("UNKNOWN", r.status)
        assertNull(r.attemptsLeft)
    }

    @Test
    fun `a missing field is a 400, not a challenge`() = test {
        val status = client.post("/trusted-exit/request") {
            contentType(ContentType.Application.Json)
            setBody(CreateChallengeBody(sessionId = "s1", kind = "END_SESSION", idempotencyKey = "s1", contactPhone = null))
        }.status
        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals(0, sms.calls)
    }

    @Test
    fun `a used code cannot pass twice over HTTP`() = test {
        val id = request().challengeId!!
        assertEquals("VALID", verify(id, sms.lastCode!!).status)
        assertEquals("UNKNOWN", verify(id, sms.lastCode!!).status)
    }

    private fun wrongCodeFor(code: String): String {
        val other = if (code.first() == '0') "1" else "0"
        return other.repeat(code.length)
    }
}
