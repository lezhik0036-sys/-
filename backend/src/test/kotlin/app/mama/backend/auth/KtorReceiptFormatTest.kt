package app.mama.backend.auth

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cross-service contract test for the verified-OTP receipt (issue #8). It proves
 * a receipt minted by [ReceiptSigner] is accepted by a faithful Kotlin port of the
 * FastAPI `trusted_exit_ktor_receipt_format.py` verifier (mama-fastapi PR #13),
 * and that every tamper/expiry/binding negative from that module's test is
 * rejected. Uses an EPHEMERAL keypair — no production private key, no network.
 *
 * The port mirrors the Python checks exactly: exact header, exact claim set,
 * fixed iss/aud/purpose, canonical base64url, raw-32-byte Ed25519 signature over
 * `h.p`, integer-only NumericDate, the timing inequalities, the `tec-<uuid>`
 * challenge format and canonical lower-case UUIDs.
 */
class KtorReceiptFormatTest {

    private val kp: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val privPkcs8 = Base64.getEncoder().encodeToString(kp.private.encoded)
    private val rawPub = ReceiptSigner.rawPublicFromSpki(kp.public.encoded)
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private val now = 1_762_380_000L // fixed epoch seconds
    private val header = linkedMapOf("alg" to "EdDSA", "typ" to "JWT", "kid" to "mama-ktor-exit-v1")

    private fun validClaims(): LinkedHashMap<String, Any> = linkedMapOf(
        "iss" to "mama-ktor-trusted-exit",
        "aud" to "mama-fastapi",
        "purpose" to "trusted-exit:verified-otp",
        "sub" to UUID.randomUUID().toString(),
        "series_id" to UUID.randomUUID().toString(),
        "period_id" to UUID.randomUUID().toString(),
        "device_id" to UUID.randomUUID().toString(),
        "contact_id" to UUID.randomUUID().toString(),
        "challenge_id" to ("tec-" + UUID.randomUUID()),
        "jti" to UUID.randomUUID().toString(),
        "iat" to now,
        "nbf" to now,
        "exp" to now + 45,
        "verified_at" to now - 1,
    )

    // ---- test-only JWT signer (mirrors the FastAPI test's sign()) --------------

    private fun json(map: Map<String, Any?>): String {
        val o = JsonObject()
        for ((k, v) in map) when (v) {
            is String -> o.addProperty(k, v)
            is Long -> o.addProperty(k, v)
            is Int -> o.addProperty(k, v)
            is Boolean -> o.addProperty(k, v)
            else -> o.addProperty(k, v.toString())
        }
        return o.toString()
    }

    private fun sign(claims: Map<String, Any?>, header: Map<String, Any?> = this.header, key: PrivateKey = kp.private): String {
        val h = b64.encodeToString(json(header).toByteArray(Charsets.UTF_8))
        val p = b64.encodeToString(json(claims).toByteArray(Charsets.UTF_8))
        val sig = Signature.getInstance("Ed25519").apply { initSign(key); update("$h.$p".toByteArray(Charsets.US_ASCII)) }.sign()
        return "$h.$p.${b64.encodeToString(sig)}"
    }

    // ---- faithful port of verify_ktor_otp_receipt ------------------------------

    private class Reject : RuntimeException()

    private fun decodeCanonical(seg: String): ByteArray {
        if (seg.isEmpty() || !Regex("^[A-Za-z0-9_-]+$").matches(seg)) throw Reject()
        val value = runCatching { Base64.getUrlDecoder().decode(seg) }.getOrElse { throw Reject() }
        if (b64.encodeToString(value) != seg) throw Reject() // non-canonical
        return value
    }

    private fun parseStrict(seg: String): JsonObject {
        val json = String(decodeCanonical(seg), Charsets.UTF_8)
        JsonReader(StringReader(json)).use { r ->
            r.isLenient = false
            if (r.peek() != JsonToken.BEGIN_OBJECT) throw Reject()
            val obj = JsonObject(); val seen = HashSet<String>()
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                if (!seen.add(name)) throw Reject() // duplicate key
                when (r.peek()) {
                    JsonToken.STRING -> obj.addProperty(name, r.nextString())
                    JsonToken.NUMBER -> obj.addProperty(name, BigDecimal(r.nextString()))
                    JsonToken.BOOLEAN -> obj.addProperty(name, r.nextBoolean())
                    JsonToken.NULL -> { r.nextNull(); obj.add(name, JsonNull.INSTANCE) }
                    else -> throw Reject()
                }
            }
            r.endObject()
            if (r.peek() != JsonToken.END_DOCUMENT) throw Reject()
            return obj
        }
    }

    private val claimKeys = setOf(
        "iss", "aud", "purpose", "sub", "series_id", "period_id",
        "device_id", "contact_id", "challenge_id", "jti", "iat", "nbf", "exp", "verified_at",
    )
    private val uuidRe = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val challengeRe = Regex("^tec-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private fun str(o: JsonObject, k: String): String {
        val e = o.get(k) ?: throw Reject()
        if (!e.isJsonPrimitive || !e.asJsonPrimitive.isString) throw Reject()
        return e.asString
    }

    private fun int(o: JsonObject, k: String): Long {
        val e = o.get(k) ?: throw Reject()
        if (!e.isJsonPrimitive || !e.asJsonPrimitive.isNumber) throw Reject()
        return runCatching { e.asBigDecimal.longValueExact() }.getOrElse { throw Reject() }
    }

    private fun canonicalUuid(o: JsonObject, k: String): String = str(o, k).also { if (!uuidRe.matches(it)) throw Reject() }

    /** Verifies like FastAPI; returns the challenge_id on success, throws [Reject] otherwise. */
    private fun verifyPort(token: String, pub: ByteArray, clock: Long): String {
        if (token.length > 2048) throw Reject()
        if (pub.size != 32) throw Reject()
        val parts = token.split(".")
        if (parts.size != 3) throw Reject()
        val (h, p, s) = parts
        val headerObj = parseStrict(h)
        if (headerObj.keySet() != header.keys) throw Reject()
        if (str(headerObj, "alg") != "EdDSA" || str(headerObj, "typ") != "JWT" || str(headerObj, "kid") != "mama-ktor-exit-v1") throw Reject()
        val claims = parseStrict(p)
        if (claims.keySet() != claimKeys) throw Reject()
        if (str(claims, "iss") != "mama-ktor-trusted-exit" ||
            str(claims, "aud") != "mama-fastapi" ||
            str(claims, "purpose") != "trusted-exit:verified-otp"
        ) throw Reject()
        val sig = decodeCanonical(s)
        if (sig.size != 64) throw Reject()
        val spki = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + pub
        val key = java.security.KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        val ok = runCatching {
            Signature.getInstance("Ed25519").apply { initVerify(key); update("$h.$p".toByteArray(Charsets.US_ASCII)) }.verify(sig)
        }.getOrDefault(false)
        if (!ok) throw Reject()
        val iat = int(claims, "iat"); val nbf = int(claims, "nbf")
        val exp = int(claims, "exp"); val verifiedAt = int(claims, "verified_at")
        if (iat > clock + 5 || nbf < iat || nbf > clock + 5 || exp <= clock || exp <= nbf ||
            exp - iat > 60 || iat < clock - 60 || verifiedAt > iat || verifiedAt < iat - 60
        ) throw Reject()
        val challengeId = str(claims, "challenge_id")
        if (!challengeRe.matches(challengeId)) throw Reject()
        canonicalUuid(claims, "sub"); canonicalUuid(claims, "series_id"); canonicalUuid(claims, "period_id")
        canonicalUuid(claims, "device_id"); canonicalUuid(claims, "contact_id"); canonicalUuid(claims, "jti")
        return challengeId
    }

    private fun reject(token: String, pub: ByteArray = rawPub, clock: Long = now) {
        try {
            verifyPort(token, pub, clock)
        } catch (_: Reject) {
            return
        }
        fail("invalid Ktor receipt accepted")
    }

    // ---- the actual ReceiptSigner output is accepted ---------------------------

    @Test fun `a ReceiptSigner-minted receipt passes the FastAPI verifier contract`() {
        val signer = ReceiptSigner(privPkcs8, ttlSec = 45, now = { now })
        val challengeId = "tec-" + UUID.randomUUID()
        val claims = ReceiptClaims(
            sub = UUID.randomUUID().toString(), seriesId = UUID.randomUUID().toString(),
            periodId = UUID.randomUUID().toString(), deviceId = UUID.randomUUID().toString(),
            contactId = UUID.randomUUID().toString(), challengeId = challengeId, verifiedAtSec = now,
        )
        val token = signer.sign(claims)!!
        assertEquals(challengeId, verifyPort(token, rawPub, now))
        // Another key must not accept it.
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        reject(token, ReceiptSigner.rawPublicFromSpki(other.public.encoded))
    }

    @Test fun `an unconfigured signer mints nothing`() {
        assertTrue(ReceiptSigner("not-a-key").sign(validClaims().toReceipt()) == null)
        assertTrue(!ReceiptSigner("not-a-key").configured)
    }

    // ---- negatives mirrored from the FastAPI module test -----------------------

    @Test fun `tamper, wrong key, structure and header negatives are rejected`() {
        val claims = validClaims()
        val token = sign(claims)
        assertEquals(claims["challenge_id"], verifyPort(token, rawPub, now)) // sanity: valid

        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        reject(token, ReceiptSigner.rawPublicFromSpki(other.public.encoded)) // wrong pinned key
        reject(sign(claims, key = other.private))                            // signed by another key
        reject("$token.extra")                                               // 4 segments
        reject("not-a-jwt")                                                  // not a JWT
        reject(sign(claims, header = header + ("jku" to "https://wrong.example/key"))) // extra header key
        reject(sign(claims, header = header + ("alg" to "none")))            // alg none (overrides EdDSA)
    }

    @Test fun `expiry and clock-window negatives are rejected`() {
        val token = sign(validClaims())
        reject(token, clock = now + 45) // exp reached
    }

    @Test fun `claim-value negatives are rejected`() {
        val c = validClaims()
        val cases = listOf<Pair<String, Any>>(
            "iss" to "client", "aud" to "android", "purpose" to "trusted-exit:sms-request",
            "exp" to now + 61, "exp" to now, "iat" to now + 100, "nbf" to now - 1,
            "verified_at" to now + 1, "verified_at" to now - 100,
            "challenge_id" to "other-challenge", "iat" to "string", "exp" to true, "sub" to "user",
        )
        for ((field, wrong) in cases) reject(sign(HashMap(c).apply { put(field, wrong) }))
        reject(sign(HashMap(c).apply { put("phone", "+31612345678") })) // extra claim
        reject(sign(HashMap(c).apply { remove("jti") }))                 // missing claim
    }

    @Test fun `duplicate claim keys are rejected even with a valid signature`() {
        val raw = json(validClaims())
        val p = b64.encodeToString((raw.dropLast(1) + ",\"aud\":\"android\"}").toByteArray(Charsets.UTF_8))
        val h = b64.encodeToString(json(header).toByteArray(Charsets.UTF_8))
        val body = "$h.$p"
        val sig = Signature.getInstance("Ed25519").apply { initSign(kp.private); update(body.toByteArray(Charsets.US_ASCII)) }.sign()
        reject("$body.${b64.encodeToString(sig)}")
    }

    private fun Map<String, Any>.toReceipt() = ReceiptClaims(
        sub = this["sub"] as String, seriesId = this["series_id"] as String,
        periodId = this["period_id"] as String, deviceId = this["device_id"] as String,
        contactId = this["contact_id"] as String, challengeId = this["challenge_id"] as String,
        verifiedAtSec = now,
    )
}
