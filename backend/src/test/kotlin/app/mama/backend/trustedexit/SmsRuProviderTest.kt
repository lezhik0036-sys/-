package app.mama.backend.trustedexit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * The SMS.RU provider, exercised without the network or a real key through a
 * fake transport. These check the request it builds and how it maps replies,
 * and that a reply never leaks the api key.
 */
class SmsRuProviderTest {

    private class FakeTransport(private val reply: HttpReply) : HttpTransport {
        var lastUrl: String? = null
        var lastForm: Map<String, String>? = null
        var calls = 0
        override fun postForm(url: String, form: Map<String, String>): HttpReply {
            calls++
            lastUrl = url
            lastForm = form
            return reply
        }
    }

    private fun provider(reply: HttpReply, apiId: String? = "test-key", from: String? = null) =
        FakeTransport(reply).let { it to SmsRuProvider(apiId = apiId, from = from, transport = it) }

    private val accepted = HttpReply(200, """{"status":"OK","status_code":100,"sms":{"79990000000":{"status":"OK","status_code":100,"sms_id":"1"}},"balance":100}""")

    @Test
    fun `an accepted reply is Accepted with sms_id and the form carries the right fields`() {
        val (t, p) = provider(accepted)
        val r = p.send("+7 (999) 000-00-00", "54321")
        assertTrue(r is SmsResult.Accepted)
        assertEquals("1", (r as SmsResult.Accepted).smsId) // sms_id is captured for the challenge state
        assertEquals("https://sms.ru/sms/send", t.lastUrl)
        val form = t.lastForm!!
        assertEquals("test-key", form["api_id"])
        assertEquals("79990000000", form["to"])     // normalized to digits
        assertEquals("1", form["json"])
        assertTrue(form["msg"]!!.contains("54321"))                     // the code is in the message body...
        assertTrue(form["msg"]!!.contains("код доверенного выхода"))    // ...with the agreed text
    }

    @Test
    fun `a network error means unknown SMS outcome not confirmed failure`() {
        val throwing = object : HttpTransport {
            override fun postForm(url: String, form: Map<String, String>): HttpReply =
                throw java.io.IOException("connection reset / timeout: key=test-key OTP=88888")
        }
        val p = SmsRuProvider(apiId = "test-key", transport = throwing)
        val e = assertFailsWith<SmsDeliveryOutcomeUnknown> {
            p.send("79990000000", "88888")
        }
        assertFalse(e.message.orEmpty().contains("test-key"))
        assertFalse(e.message.orEmpty().contains("88888"))
    }

    @Test
    fun `provider lost response after accepting SMS stays unknown and is not retried`() {
        var attempted = 0
        val throwingAfterSend = object : HttpTransport {
            override fun postForm(url: String, form: Map<String, String>): HttpReply {
                attempted++
                // Simulated failure AFTER upstream accepted the SMS.
                throw java.io.IOException("upstream response lost")
            }
        }
        val p = SmsRuProvider(apiId = "test-key", transport = throwingAfterSend)
        assertFailsWith<SmsDeliveryOutcomeUnknown> {
            p.send("79990000000", "00042")
        }
        assertEquals(1, attempted)
    }

    @Test
    fun `a leading 8 is normalized to 7`() {
        val (t, p) = provider(accepted)
        p.send("89990000000", "11111")
        assertEquals("79990000000", t.lastForm!!["to"])
    }

    @Test
    fun `a top-level ERROR reply is Failed`() {
        val (_, p) = provider(HttpReply(200, """{"status":"ERROR","status_code":200,"status_text":"wrong api_id"}"""))
        assertTrue(p.send("79990000000", "22222") is SmsResult.Failed)
    }

    @Test
    fun `a per-recipient error is Failed`() {
        val reply = HttpReply(200, """{"status":"OK","status_code":100,"sms":{"79990000000":{"status":"ERROR","status_code":207,"status_text":"blacklisted"}}}""")
        val (_, p) = provider(reply)
        val r = p.send("79990000000", "33333")
        assertTrue(r is SmsResult.Failed)
    }

    @Test
    fun `top level OK without a recipient acknowledgement is not accepted`() {
        val (_, p) = provider(HttpReply(200, """{"status":"OK","status_code":100,"balance":100}"""))
        assertTrue(p.send("79990000000", "12345") is SmsResult.Failed)
    }

    @Test
    fun `top level OK with empty recipient list is not accepted`() {
        val (_, p) = provider(HttpReply(200, """{"status":"OK","status_code":100,"sms":{}}"""))
        assertTrue(p.send("79990000000", "12345") is SmsResult.Failed)
    }

    @Test
    fun `a different recipients OK is not proof the target SMS was accepted`() {
        val (_, p) = provider(HttpReply(200, """{"status":"OK","status_code":100,"sms":{"79991111111":{"status":"OK","sms_id":"other"}}}"""))
        assertTrue(p.send("79990000000", "12345") is SmsResult.Failed)
    }

    @Test
    fun `recipient acknowledgement malformed is safely refused`() {
        val (_, p) = provider(HttpReply(200, """{"status":"OK","status_code":100,"sms":{"79990000000":"oops"}}"""))
        assertTrue(p.send("79990000000", "12345") is SmsResult.Failed)
    }

    @Test
    fun `object shaped top level status fails safely`() {
        val (_, p) = provider(HttpReply(200, """{"status":{"unexpected":"OK"},"sms":{}}"""))
        assertTrue(p.send("79990000000", "12345") is SmsResult.Failed)
    }

    @Test
    fun `object shaped recipient status fails safely`() {
        val (_, p) = provider(HttpReply(200, """{"status":"OK","sms":{"79990000000":{"status":{"unexpected":"OK"}}}}"""))
        assertTrue(p.send("79990000000", "12345") is SmsResult.Failed)
    }

    @Test
    fun `malformed successful sms id never crashes`() {
        val (_, p) = provider(HttpReply(200, """{"status":"OK","sms":{"79990000000":{"status":"OK","sms_id":{"invalid":1}}}}"""))
        val result = p.send("79990000000", "12345")
        assertTrue(result is SmsResult.Accepted)
        assertNull((result as SmsResult.Accepted).smsId)
    }

    @Test
    fun `provider error text that contains secrets cannot leak through returned error`() {
        val (_, p) = provider(HttpReply(200, """{"status":"ERROR","status_text":"api_id=test-key OTP=12345"}"""))
        val result = p.send("79990000000", "12345")
        assertTrue(result is SmsResult.Failed)
        assertFalse(result.toString().contains("test-key"))
        assertFalse(result.toString().contains("12345"))
    }

    @Test
    fun `a non-2xx reply is Failed`() {
        val (_, p) = provider(HttpReply(500, "oops"))
        assertTrue(p.send("79990000000", "44444") is SmsResult.Failed)
    }

    @Test
    fun `with no api key nothing is sent and the result is Failed`() {
        val (t, p) = provider(accepted, apiId = null)
        val r = p.send("79990000000", "55555")
        assertTrue(r is SmsResult.Failed)
        assertEquals(0, t.calls) // fail-closed: the transport is never called
    }

    @Test
    fun `no result ever leaks the api key or the raw reply`() {
        val (_, p) = provider(HttpReply(200, """{"status":"ERROR","status_code":200,"status_text":"wrong api_id test-key"}"""), apiId = "test-key")
        val r = p.send("79990000000", "66666")
        // The Failed reason is our own text, not the provider's echo of the key.
        assertFalse(r.toString().contains("test-key"))
    }

    @Test
    fun `a malformed JSON reply is Failed, not a crash`() {
        val (_, p) = provider(HttpReply(200, "not json"))
        assertTrue(p.send("79990000000", "77777") is SmsResult.Failed)
    }

    @Test
    fun `from is included only when configured`() {
        val (t1, p1) = provider(accepted, from = null)
        p1.send("79990000000", "10101")
        assertNull(t1.lastForm!!["from"])

        val (t2, p2) = provider(accepted, from = "MAMA")
        p2.send("79990000000", "10101")
        assertEquals("MAMA", t2.lastForm!!["from"])
    }
}
