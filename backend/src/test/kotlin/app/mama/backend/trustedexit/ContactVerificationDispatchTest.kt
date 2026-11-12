package app.mama.backend.trustedexit

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The private contact-verification dispatch (FastAPI -> Ktor). Proves the MAC is
 * byte-compatible with the FastAPI adapter (mama-fastapi #70), that only a valid
 * MAC + well-formed request dispatches, that the status taxonomy matches #70
 * (sent / provably-not-sent / uncertain), and that a retry/replay of a challenge
 * never re-sends (anti double-paid-SMS).
 */
class ContactVerificationDispatchTest {

    // 32-byte shared dispatch secret (test only; the real one lives in the server env).
    private val secret = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8)

    // The EXACT canonical body the FastAPI adapter sends (sorted keys, compact), and
    // the MAC it computes as HMAC-SHA256(secret, DOMAIN + body). Hard-coded so a drift
    // in the Kotlin verifier's domain/canonicalization fails the build.
    private val goldenBody =
        """{"challenge_id":"chal-test-0001","code":"123456","contact_id":"22222222-2222-4222-8222-222222222222","phone":"+79991234567","user_id":"11111111-1111-4111-8111-111111111111"}"""
    private val goldenMac = "9c25a91e5d1252ff84fe3d0447efd66a03bda7ef61c28f1883b836e1ac73a105"

    private fun sign(body: ByteArray): String {
        val m = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        m.update(ContactVerificationDispatchMac.DOMAIN)
        return m.doFinal(body).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private class RecordingSend(private val result: DispatchSend) : (String, String, String?) -> DispatchSend {
        var calls = 0
        var lastPhone: String? = null
        var lastCode: String? = null
        var lastConfirmUrl: String? = null
        override fun invoke(phone: String, code: String, confirmUrl: String?): DispatchSend {
            calls++; lastPhone = phone; lastCode = code; lastConfirmUrl = confirmUrl; return result
        }
    }

    private fun service(send: (String, String, String?) -> DispatchSend) =
        ContactVerificationDispatchService(ContactVerificationDispatchMac(secret), send)

    // --- MAC cross-compatibility + authentication --------------------------------

    @Test fun `golden FastAPI MAC verifies`() {
        val mac = ContactVerificationDispatchMac(secret)
        assertTrue(mac.configured)
        assertTrue(mac.verify(goldenBody.toByteArray(Charsets.UTF_8), goldenMac))
        // Our own signer must reproduce the FastAPI MAC byte-for-byte.
        assertEquals(goldenMac, sign(goldenBody.toByteArray(Charsets.UTF_8)))
    }

    @Test fun `a tampered body or MAC fails verification`() {
        val mac = ContactVerificationDispatchMac(secret)
        assertFalse(mac.verify((goldenBody + " ").toByteArray(Charsets.UTF_8), goldenMac))
        assertFalse(mac.verify(goldenBody.toByteArray(Charsets.UTF_8), goldenMac.dropLast(2) + "00"))
        assertFalse(mac.verify(goldenBody.toByteArray(Charsets.UTF_8), null))
        assertFalse(mac.verify(goldenBody.toByteArray(Charsets.UTF_8), "nothex"))
    }

    @Test fun `a short secret is unconfigured and verifies nothing`() {
        val mac = ContactVerificationDispatchMac("too-short".toByteArray(Charsets.UTF_8))
        assertFalse(mac.configured)
        assertFalse(mac.verify(goldenBody.toByteArray(Charsets.UTF_8), goldenMac))
    }

    // --- dispatch outcomes -------------------------------------------------------

    @Test fun `valid accepted dispatch returns SMS_ACCEPTED and sends the code once`() {
        val send = RecordingSend(DispatchSend.ACCEPTED)
        val out = service(send).dispatch(goldenBody.toByteArray(Charsets.UTF_8), goldenMac)
        assertEquals(DispatchOutcome.Status("SMS_ACCEPTED"), out)
        assertEquals(1, send.calls)
        assertEquals("+79991234567", send.lastPhone)
        assertEquals("123456", send.lastCode)
    }

    @Test fun `a dispatch with confirm_url passes the link to the sender, code not in it`() {
        val send = RecordingSend(DispatchSend.ACCEPTED)
        val link = "https://api.mama-app.ru/v1/contact-verification/page?cid=chal-test-0001"
        // The sorted-compact body the FastAPI adapter emits when a confirm_url is present
        // (confirm_url sorts between code and contact_id); additive to the code-only body.
        val withUrl =
            """{"challenge_id":"chal-test-0001","code":"123456","confirm_url":"$link","contact_id":"22222222-2222-4222-8222-222222222222","phone":"+79991234567","user_id":"11111111-1111-4111-8111-111111111111"}"""
        val raw = withUrl.toByteArray(Charsets.UTF_8)
        val out = service(send).dispatch(raw, sign(raw))
        assertEquals(DispatchOutcome.Status("SMS_ACCEPTED"), out)
        assertEquals(link, send.lastConfirmUrl) // the link reaches the SMS sender
        assertEquals("123456", send.lastCode)   // the code is delivered separately
        assertFalse(link.contains("123456"))    // and never inside the link
    }

    @Test fun `a code-only body still carries no confirm_url (byte-identical legacy path)`() {
        val send = RecordingSend(DispatchSend.ACCEPTED)
        service(send).dispatch(goldenBody.toByteArray(Charsets.UTF_8), goldenMac)
        assertNull(send.lastConfirmUrl)
    }

    @Test fun `a non-https or malformed confirm_url is rejected and never sent`() {
        val send = RecordingSend(DispatchSend.ACCEPTED)
        val svc = service(send)
        for (badUrl in listOf("http://evil.example/x", "ftp://x", "not a url", "https://has space/x")) {
            val json =
                """{"challenge_id":"c1","code":"123456","confirm_url":"$badUrl","contact_id":"22222222-2222-4222-8222-222222222222","phone":"+79991234567","user_id":"11111111-1111-4111-8111-111111111111"}"""
            val raw = json.toByteArray(Charsets.UTF_8)
            assertTrue(svc.dispatch(raw, sign(raw)) is DispatchOutcome.BadRequest, badUrl)
        }
        assertEquals(0, send.calls)
    }

    @Test fun `provider refusal maps to FAILED`() {
        val out = service(RecordingSend(DispatchSend.REFUSED))
            .dispatch(goldenBody.toByteArray(Charsets.UTF_8), goldenMac)
        assertEquals(DispatchOutcome.Status("FAILED"), out)
    }

    @Test fun `provider uncertainty maps to UNKNOWN (never a definite failure)`() {
        val out = service(RecordingSend(DispatchSend.UNCERTAIN))
            .dispatch(goldenBody.toByteArray(Charsets.UTF_8), goldenMac)
        assertEquals(DispatchOutcome.Status("UNKNOWN"), out)
    }

    @Test fun `an invalid or missing MAC is Unauthorized and never sends`() {
        val send = RecordingSend(DispatchSend.ACCEPTED)
        val svc = service(send)
        assertEquals(DispatchOutcome.Unauthorized, svc.dispatch(goldenBody.toByteArray(Charsets.UTF_8), "00"))
        assertEquals(DispatchOutcome.Unauthorized, svc.dispatch(goldenBody.toByteArray(Charsets.UTF_8), null))
        assertEquals(0, send.calls)
    }

    // --- anti double-send (idempotency) ------------------------------------------

    @Test fun `a replay of the same challenge returns the recorded status without re-sending`() {
        val send = RecordingSend(DispatchSend.ACCEPTED)
        val svc = service(send)
        val body = goldenBody.toByteArray(Charsets.UTF_8)
        assertEquals(DispatchOutcome.Status("SMS_ACCEPTED"), svc.dispatch(body, goldenMac))
        assertEquals(DispatchOutcome.Status("SMS_ACCEPTED"), svc.dispatch(body, goldenMac))
        assertEquals(1, send.calls) // sent exactly once for the challenge
    }

    @Test fun `an uncertain attempt is also not re-sent on replay`() {
        val send = RecordingSend(DispatchSend.UNCERTAIN)
        val svc = service(send)
        val body = goldenBody.toByteArray(Charsets.UTF_8)
        assertEquals(DispatchOutcome.Status("UNKNOWN"), svc.dispatch(body, goldenMac))
        assertEquals(DispatchOutcome.Status("UNKNOWN"), svc.dispatch(body, goldenMac))
        assertEquals(1, send.calls)
    }

    // --- request validation (all definite non-dispatch, never a send) ------------

    @Test fun `an oversized body is rejected before sending`() {
        val send = RecordingSend(DispatchSend.ACCEPTED)
        val big = ByteArray(ContactVerificationDispatchMac.MAX_BODY_BYTES + 1) { '{'.code.toByte() }
        val out = service(send).dispatch(big, sign(big))
        assertEquals(DispatchOutcome.BadRequest("body too large"), out)
        assertEquals(0, send.calls)
    }

    @Test fun `malformed json, bad ids, missing or invalid code are BadRequest`() {
        val send = RecordingSend(DispatchSend.ACCEPTED)
        val svc = service(send)
        assertBad(svc, """not json""")
        // non-canonical / non-UUID ids
        assertBad(svc, body(userId = "not-a-uuid"))
        assertBad(svc, body(contactId = "11111111-1111-4111-8111"))
        // missing one-time code
        assertBad(svc, """{"user_id":"11111111-1111-4111-8111-111111111111","contact_id":"22222222-2222-4222-8222-222222222222","phone":"+79991234567","challenge_id":"c1"}""")
        // non-numeric / wrong-length code
        assertBad(svc, body(code = "12ab"))
        assertBad(svc, body(code = "12"))
        assertBad(svc, body(code = "123456789"))
        // blank phone / challenge
        assertBad(svc, body(phone = ""))
        assertBad(svc, body(challengeId = ""))
        assertEquals(0, send.calls)
    }

    private fun assertBad(svc: ContactVerificationDispatchService, json: String) {
        val raw = json.toByteArray(Charsets.UTF_8)
        val out = svc.dispatch(raw, sign(raw))
        assertTrue(out is DispatchOutcome.BadRequest, "expected BadRequest for: $json, got $out")
    }

    private fun body(
        userId: String = "11111111-1111-4111-8111-111111111111",
        contactId: String = "22222222-2222-4222-8222-222222222222",
        phone: String = "+79991234567",
        challengeId: String = "c1",
        code: String = "123456",
    ): String =
        """{"user_id":"$userId","contact_id":"$contactId","phone":"$phone","challenge_id":"$challengeId","code":"$code"}"""

    @Test fun `the in-memory idempotency store keeps the first outcome`() {
        val store = InMemoryDispatchIdempotencyStore()
        assertNull(store.get("c"))
        store.put("c", "SMS_ACCEPTED")
        store.put("c", "FAILED") // first write wins; a later outcome never overwrites
        assertEquals("SMS_ACCEPTED", store.get("c"))
    }
}
