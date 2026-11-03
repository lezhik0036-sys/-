package app.mama.backend.auth

import com.google.gson.JsonObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grant verifier, exercised with an EPHEMERAL Ed25519 keypair generated in
 * the test — no private key is ever committed or shipped. Production holds only
 * the real FastAPI public key. This proves Ktor's grant verification can be
 * tested fully without any production signing key or live FastAPI route.
 */
class GrantVerifierTest {

    private val kp: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val pub = Base64.getEncoder().encodeToString(kp.public.encoded)
    private val now = 1_000_000L
    private fun verifier(pk: String = pub) = GrantVerifier(pk, now = { now })

    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** Valid claim template at [now]. */
    private fun claims(): JsonObject = JsonObject().apply {
        addProperty("iss", "mama-fastapi")
        addProperty("aud", "mama-ktor-trusted-exit")
        addProperty("purpose", "trusted-exit:sms-request")
        addProperty("sub", "11111111-1111-4111-8111-111111111111")
        addProperty("series_id", "22222222-2222-4222-8222-222222222222")
        addProperty("period_id", "33333333-3333-4333-8333-333333333333")
        addProperty("device_id", "44444444-4444-4444-8444-444444444444")
        addProperty("contact_id", "55555555-5555-4555-8555-555555555555")
        addProperty("jti", "66666666-6666-4666-8666-666666666666")
        addProperty("iat", now)
        addProperty("nbf", now)
        addProperty("exp", now + 30)
    }

    private fun header(alg: String = "EdDSA", typ: String = "JWT", kid: String = "mama-ktor-sms-v1") =
        JsonObject().apply { addProperty("alg", alg); addProperty("typ", typ); addProperty("kid", kid) }

    private fun sign(signingInput: String, key: PrivateKey = kp.private): String {
        val sig = Signature.getInstance("Ed25519").apply { initSign(key); update(signingInput.toByteArray(Charsets.US_ASCII)) }.sign()
        return b64.encodeToString(sig)
    }

    private fun token(header: JsonObject = header(), payload: JsonObject = claims(), key: PrivateKey = kp.private): String {
        val h = b64.encodeToString(header.toString().toByteArray(Charsets.UTF_8))
        val p = b64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
        return "$h.$p.${sign("$h.$p", key)}"
    }

    @Test fun `a well-formed grant verifies and exposes the signed bindings`() {
        val r = verifier().verify("Bearer ${token()}")
        assertTrue(r is GrantVerifier.Result.Valid)
        val g = (r as GrantVerifier.Result.Valid).grant
        assertEquals("66666666-6666-4666-8666-666666666666", g.jti)
        assertEquals("22222222-2222-4222-8222-222222222222", g.seriesId)
        assertEquals("55555555-5555-4555-8555-555555555555", g.contactId)
    }

    @Test fun `missing or malformed grant is invalid`() {
        assertTrue(verifier().verify(null) is GrantVerifier.Result.Invalid)
        assertTrue(verifier().verify("Bearer ") is GrantVerifier.Result.Invalid)
        assertTrue(verifier().verify("Bearer not.a.jwt.x") is GrantVerifier.Result.Invalid)
    }

    @Test fun `alg none and any non-EdDSA alg are rejected`() {
        assertTrue(verifier().verify("Bearer ${token(header = header(alg = "none"))}") is GrantVerifier.Result.Invalid)
        assertTrue(verifier().verify("Bearer ${token(header = header(alg = "RS256"))}") is GrantVerifier.Result.Invalid)
        assertTrue(verifier().verify("Bearer ${token(header = header(alg = "HS256"))}") is GrantVerifier.Result.Invalid)
    }

    @Test fun `wrong kid or typ is rejected`() {
        assertTrue(verifier().verify("Bearer ${token(header = header(kid = "other"))}") is GrantVerifier.Result.Invalid)
        assertTrue(verifier().verify("Bearer ${token(header = header(typ = "JWS"))}") is GrantVerifier.Result.Invalid)
    }

    @Test fun `a tampered payload fails the signature`() {
        val good = token()
        val parts = good.split(".")
        val forged = claims().apply { addProperty("sub", "99999999-9999-4999-8999-999999999999") }
        val p = b64.encodeToString(forged.toString().toByteArray(Charsets.UTF_8))
        val tampered = "${parts[0]}.$p.${parts[2]}" // original signature over the old payload
        assertTrue(verifier().verify("Bearer $tampered") is GrantVerifier.Result.Invalid)
    }

    @Test fun `a grant signed by another key is rejected`() {
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        assertTrue(verifier().verify("Bearer ${token(key = other.private)}") is GrantVerifier.Result.Invalid)
    }

    @Test fun `wrong iss, aud or purpose is rejected`() {
        assertTrue(verifier().verify("Bearer ${token(payload = claims().apply { addProperty("iss", "evil") })}") is GrantVerifier.Result.Invalid)
        assertTrue(verifier().verify("Bearer ${token(payload = claims().apply { addProperty("aud", "someone-else") })}") is GrantVerifier.Result.Invalid)
        assertTrue(verifier().verify("Bearer ${token(payload = claims().apply { addProperty("purpose", "trusted-exit:verify") })}") is GrantVerifier.Result.Invalid)
    }

    @Test fun `an extra or missing claim is rejected`() {
        assertTrue(verifier().verify("Bearer ${token(payload = claims().apply { addProperty("extra", "x") })}") is GrantVerifier.Result.Invalid)
        assertTrue(verifier().verify("Bearer ${token(payload = claims().apply { remove("device_id") })}") is GrantVerifier.Result.Invalid)
    }

    @Test fun `a non-lowercase or non-canonical UUID is rejected`() {
        assertTrue(verifier().verify("Bearer ${token(payload = claims().apply { addProperty("sub", "11111111-1111-4111-8111-11111111111X") })}") is GrantVerifier.Result.Invalid)
        assertTrue(verifier().verify("Bearer ${token(payload = claims().apply { addProperty("jti", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA") })}") is GrantVerifier.Result.Invalid)
    }

    @Test fun `a lifetime over 60s is rejected`() {
        assertTrue(verifier().verify("Bearer ${token(payload = claims().apply { addProperty("exp", now + 61) })}") is GrantVerifier.Result.Invalid)
    }

    @Test fun `a not-yet-valid or expired grant is rejected`() {
        assertTrue(verifier().verify("Bearer ${token(payload = claims().apply { addProperty("nbf", now + 30); addProperty("iat", now + 30); addProperty("exp", now + 50) })}") is GrantVerifier.Result.Invalid)
        assertTrue(verifier().verify("Bearer ${token(payload = claims().apply { addProperty("iat", now - 100); addProperty("nbf", now - 100); addProperty("exp", now - 1) })}") is GrantVerifier.Result.Invalid)
    }

    @Test fun `a small future skew within 5s is tolerated`() {
        val r = verifier().verify("Bearer ${token(payload = claims().apply { addProperty("iat", now + 3); addProperty("nbf", now + 3); addProperty("exp", now + 33) })}")
        assertTrue(r is GrantVerifier.Result.Valid)
    }

    @Test fun `an unconfigured verifier rejects everything`() {
        val bad = GrantVerifier("not-a-key", now = { now })
        assertTrue(!bad.configured)
        assertTrue(bad.verify("Bearer ${token()}") is GrantVerifier.Result.Invalid)
    }
}
