package app.mama.backend.auth

import com.google.gson.JsonObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The challenge-bound `trusted-exit:verify` grant verifier (issue #10), checked
 * against the FastAPI `mama-fastapi` PR #14 `trusted_exit_verify_grant_format.py`
 * contract: fixed `kid=mama-ktor-verify-v1`, `purpose=trusted-exit:verify`, exact
 * claims incl. a `tec-<uuid>` `challenge_id`, canonical UUIDs, ≤60s, integer
 * NumericDate. Ephemeral keypair — no production key. Also asserts the two scopes
 * are non-interchangeable: the SMS-request verifier rejects a verify grant and the
 * verify verifier rejects an SMS-request grant.
 */
class VerifyGrantVerifierTest {

    private val kp: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val pub = Base64.getEncoder().encodeToString(kp.public.encoded)
    private val now = 1_000_000L
    private fun verifier() = GrantVerifier.forVerify(pub, now = { now })
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private fun claims(): JsonObject = JsonObject().apply {
        addProperty("iss", "mama-fastapi")
        addProperty("aud", "mama-ktor-trusted-exit")
        addProperty("purpose", "trusted-exit:verify")
        addProperty("sub", "11111111-1111-4111-8111-111111111111")
        addProperty("series_id", "22222222-2222-4222-8222-222222222222")
        addProperty("period_id", "33333333-3333-4333-8333-333333333333")
        addProperty("device_id", "44444444-4444-4444-8444-444444444444")
        addProperty("contact_id", "55555555-5555-4555-8555-555555555555")
        addProperty("challenge_id", "tec-66666666-6666-4666-8666-666666666666")
        addProperty("jti", "77777777-7777-4777-8777-777777777777")
        addProperty("iat", now); addProperty("nbf", now); addProperty("exp", now + 45)
    }

    private fun header(alg: String = "EdDSA", typ: String = "JWT", kid: String = "mama-ktor-verify-v1") =
        JsonObject().apply { addProperty("alg", alg); addProperty("typ", typ); addProperty("kid", kid) }

    private fun token(header: JsonObject = header(), payload: JsonObject = claims(), key: PrivateKey = kp.private): String {
        val h = b64.encodeToString(header.toString().toByteArray(Charsets.UTF_8))
        val p = b64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
        return "$h.$p.${sign("$h.$p", key)}"
    }

    private fun rawToken(headerJson: String, payloadJson: String, key: PrivateKey = kp.private): String {
        val h = b64.encodeToString(headerJson.toByteArray(Charsets.UTF_8))
        val p = b64.encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        return "$h.$p.${sign("$h.$p", key)}"
    }

    private fun sign(signingInput: String, key: PrivateKey): String {
        val sig = Signature.getInstance("Ed25519").apply { initSign(key); update(signingInput.toByteArray(Charsets.US_ASCII)) }.sign()
        return b64.encodeToString(sig)
    }

    private fun invalid(t: String) = assertTrue(verifier().verify("Bearer $t") is GrantVerifier.Result.Invalid)

    @Test fun `a well-formed verify grant exposes the challenge binding`() {
        val r = verifier().verify("Bearer ${token()}")
        assertTrue(r is GrantVerifier.Result.Valid)
        val g = (r as GrantVerifier.Result.Valid).grant
        assertEquals("tec-66666666-6666-4666-8666-666666666666", g.challengeId)
        assertEquals("44444444-4444-4444-8444-444444444444", g.deviceId)
        assertEquals(now + 45, g.exp)
    }

    @Test fun `the two scopes are not interchangeable`() {
        // An SMS-request grant presented to the verify verifier is rejected...
        val smsHeader = header(kid = "mama-ktor-sms-v1")
        val smsClaims = claims().apply { addProperty("purpose", "trusted-exit:sms-request"); remove("challenge_id") }
        invalid(token(header = smsHeader, payload = smsClaims))
        // ...and the verify grant is rejected by the SMS-request verifier.
        assertTrue(GrantVerifier(pub, now = { now }).verify("Bearer ${token()}") is GrantVerifier.Result.Invalid)
    }

    @Test fun `wrong purpose or kid is rejected`() {
        invalid(token(payload = claims().apply { addProperty("purpose", "trusted-exit:sms-request") }))
        invalid(token(payload = claims().apply { addProperty("purpose", "trusted-exit:unlock") }))
        invalid(token(header = header(kid = "mama-ktor-sms-v1")))
        invalid(token(header = header(alg = "none")))
    }

    @Test fun `foreign key, tamper and structure are rejected`() {
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        invalid(token(key = other.private))
        assertTrue(GrantVerifier.forVerify(Base64.getEncoder().encodeToString(other.public.encoded), now = { now })
            .verify("Bearer ${token()}") is GrantVerifier.Result.Invalid) // wrong pinned key
        invalid("${token()}.tail")
        invalid("invalid.token")
        invalid(token(header = header().apply { addProperty("jku", "https://example.invalid/jwk") }))
    }

    @Test fun `expiry and clock-window are enforced`() {
        // Past expiry: a verifier whose clock is at exp (now+45) rejects the token.
        assertTrue(GrantVerifier.forVerify(pub, now = { now + 45 }).verify("Bearer ${token()}") is GrantVerifier.Result.Invalid)
        invalid(token(payload = claims().apply { addProperty("iat", now + 100) }))
        invalid(token(payload = claims().apply { addProperty("nbf", now + 100) }))
        invalid(token(payload = claims().apply { addProperty("nbf", now - 1) })) // nbf < iat
        invalid(token(payload = claims().apply { addProperty("exp", now + 61) })) // lifetime > 60
        invalid(token(payload = claims().apply { addProperty("exp", now) }))      // exp <= iat/nbf
        invalid(token(payload = claims().apply { addProperty("exp", now - 1) }))
    }

    @Test fun `bad bindings and malformed claims are rejected`() {
        invalid(token(payload = claims().apply { addProperty("iss", "android") }))
        invalid(token(payload = claims().apply { addProperty("aud", "wrong-ktor") }))
        invalid(token(payload = claims().apply { addProperty("challenge_id", UUID.randomUUID().toString()) })) // no tec- prefix
        invalid(token(payload = claims().apply { addProperty("challenge_id", "tec-INVALID") }))
        invalid(token(payload = claims().apply { addProperty("challenge_id", "tec-" + UUID.randomUUID().toString().uppercase()) }))
        invalid(token(payload = claims().apply { addProperty("device_id", "not-a-uuid") }))
        invalid(token(payload = claims().apply { addProperty("jti", 123) }))      // not a string UUID
        invalid(token(payload = claims().apply { addProperty("exp", true) }))     // boolean, not int
        invalid(token(payload = claims().apply { addProperty("exp", "number") })) // string, not int
        invalid(token(payload = claims().apply { addProperty("phone", "+31612345678") })) // extra claim
        invalid(token(payload = claims().apply { remove("jti") }))                // missing claim
    }

    @Test fun `extreme or overflowing NumericDate values cannot bypass the lifetime gate`() {
        // iat/nbf = Long.MIN_VALUE with a near-future exp: exp - iat overflows Long, which
        // must NOT wrap past the =<60s lifetime check (ChatGPT PR #11 review).
        invalid(token(payload = claims().apply { addProperty("iat", Long.MIN_VALUE); addProperty("nbf", Long.MIN_VALUE); addProperty("exp", now + 30) }))
        // A very stale but non-overflowing iat is rejected too.
        invalid(token(payload = claims().apply { addProperty("iat", now - 100_000); addProperty("nbf", now - 100_000); addProperty("exp", now + 30) }))
        // An extreme exp (no overflow, huge lifetime) is rejected.
        invalid(token(payload = claims().apply { addProperty("exp", Long.MAX_VALUE) }))
        // Both extremes together: the subtraction itself overflows and is rejected.
        invalid(token(payload = claims().apply { addProperty("iat", Long.MIN_VALUE); addProperty("nbf", Long.MIN_VALUE); addProperty("exp", Long.MAX_VALUE) }))
    }

    @Test fun `a float NumericDate is rejected`() {
        // iat written as a float (1000000.0) — Python parses it as float and rejects; so must we.
        val raw = claims().toString().replace("\"iat\":1000000", "\"iat\":1000000.0")
        invalid(rawToken(header().toString(), raw))
    }

    @Test fun `duplicate JSON claim keys are rejected even with a valid signature`() {
        val dup = claims().toString().dropLast(1) + ",\"aud\":\"wrong\"}"
        invalid(rawToken(header().toString(), dup))
    }
}
