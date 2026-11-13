package app.mama.backend.trustedexit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // Returns the provider typed as the SmsProvider interface so send(phone, code) uses
    // the interface's default confirmUrl=null at the call sites below.
    private fun provider(reply: HttpReply, apiId: String? = "test-key", from: String? = null): Pair<FakeTransport, SmsProvider> =
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
    fun `a network error or timeout is Failed, not a crash`() {
        val throwing = object : HttpTransport {
            override fun postForm(url: String, form: Map<String, String>): HttpReply =
                throw java.io.IOException("connection reset / timeout")
        }
        val p: SmsProvider = SmsRuProvider(apiId = "test-key", transport = throwing)
        assertTrue(p.send("79990000000", "88888") is SmsResult.Failed)
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
    fun `the verification message carries the code and link, keeps the code out of the url, and fits a bounded number of SMS segments`() {
        // A realistic confirm link: origin + page path + a 32-char urlsafe challenge token
        // (chosen with no digit run equal to the code, so the "code not in url" check is real).
        val link = "https://api.mama-app.ru/v1/contact-verification/page?cid=abcdefghijkLMNOPQRSTuvwxyz-_ABCDE"
        val t = FakeTransport(accepted)
        val p: SmsProvider = SmsRuProvider(
            apiId = "test-key", transport = t, message = ::contactVerificationSmsMessage,
        )
        p.send("79990000000", "123456", link)
        val msg = t.lastForm!!["msg"]!!
        assertTrue(msg.contains("123456"))          // the code is in the body
        assertTrue(msg.contains(link))              // and so is the link
        assertFalse(link.contains("123456"))        // but the code is never inside the url
        // Cyrillic text => UCS-2 (70 chars single, 67 per concatenated segment). Keep the
        // message to a small, affordable number of segments at this token length.
        assertTrue(segments(msg) <= 4, "verification SMS too long: ${msg.length} UCS-2 units")
        // The code-only fallback (no link) stays a short message too.
        p.send("79990000000", "654321", null)
        assertTrue(segments(t.lastForm!!["msg"]!!) <= 3)
        assertFalse(t.lastForm!!["msg"]!!.contains("http"))
    }

    // UCS-2 SMS segment count for a (Cyrillic) message: 1 if <= 70 UTF-16 units, else
    // ceil(len / 67) for concatenated parts (6-byte UDH per part).
    private fun segments(msg: String): Int = if (msg.length <= 70) 1 else (msg.length + 66) / 67

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
