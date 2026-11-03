package app.mama.backend.auth

import com.google.gson.JsonObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

/** Identity bindings + the verification timestamp a receipt is minted from. */
data class ReceiptClaims(
    val sub: String,
    val seriesId: String,
    val periodId: String,
    val deviceId: String,
    val contactId: String,
    val challengeId: String,
    val verifiedAtSec: Long,
)

/**
 * Mints the **verified-OTP receipt** — a Ktor-signed Ed25519 compact JWT that is
 * Ktor's proof to FastAPI that a one-time OTP verification succeeded. The format
 * is the FastAPI `trusted_exit_ktor_receipt_format.py` contract (proposal v1,
 * `mama-fastapi` PR #13): fixed header `{alg=EdDSA,typ=JWT,kid=mama-ktor-exit-v1}`,
 * exact claims `{iss,aud,purpose,sub,series_id,period_id,device_id,contact_id,
 * challenge_id,jti,iat,nbf,exp,verified_at}`, no phone/OTP, ≤ 60s lifetime.
 *
 * **This is NOT an unlock.** The receipt only *attests* a successful verification;
 * FastAPI must authenticate the internal caller, atomically persist it keyed by a
 * unique `jti`/`challenge_id`, re-check live series/device/period/contact policy,
 * consume it once, and only then perform the business transition. Android waits
 * for that authoritative result.
 *
 * Signing is **off by default**: with no `MAMA_RECEIPT_SIGNING_KEY` the signer is
 * unconfigured and [sign] returns null, so `/verify` yields a bare `VALID` with no
 * proof and nothing can be unlocked (fail-closed). The production private key must
 * come only from an env/secret store and never be committed. Tests use an
 * **ephemeral** keypair generated in-process.
 */
class ReceiptSigner(
    privateKeyBase64Pkcs8: String,
    private val ttlSec: Long = 45,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** Ed25519 private key (PKCS#8 DER, base64). Null = not configured → no receipts. */
    private val privateKey: PrivateKey? = runCatching {
        val der = Base64.getDecoder().decode(privateKeyBase64Pkcs8.trim())
        KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(der))
    }.getOrNull()

    val configured: Boolean get() = privateKey != null

    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /**
     * Mints the receipt for [c], or returns null when not configured. `iat=nbf=now`,
     * `exp=now+ttl` (≤ 60s), `verified_at = min(verifiedAt, iat)` so it is never after
     * `iat` (the FastAPI verifier rejects `verified_at > iat`). `jti` is a fresh UUID.
     */
    fun sign(c: ReceiptClaims): String? {
        val key = privateKey ?: return null
        val iat = now()
        val header = JsonObject().apply {
            addProperty("alg", "EdDSA"); addProperty("typ", "JWT"); addProperty("kid", KID)
        }
        val payload = JsonObject().apply {
            addProperty("iss", ISS); addProperty("aud", AUD); addProperty("purpose", PURPOSE)
            addProperty("sub", c.sub); addProperty("series_id", c.seriesId); addProperty("period_id", c.periodId)
            addProperty("device_id", c.deviceId); addProperty("contact_id", c.contactId)
            addProperty("challenge_id", c.challengeId); addProperty("jti", UUID.randomUUID().toString())
            addProperty("iat", iat); addProperty("nbf", iat); addProperty("exp", iat + ttlSec)
            addProperty("verified_at", minOf(c.verifiedAtSec, iat))
        }
        val h = b64.encodeToString(header.toString().toByteArray(Charsets.UTF_8))
        val p = b64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
        val sig = Signature.getInstance("Ed25519")
            .apply { initSign(key); update("$h.$p".toByteArray(Charsets.US_ASCII)) }
            .sign()
        return "$h.$p.${b64.encodeToString(sig)}"
    }

    companion object {
        const val KID = "mama-ktor-exit-v1"
        const val ISS = "mama-ktor-trusted-exit"
        const val AUD = "mama-fastapi"
        const val PURPOSE = "trusted-exit:verified-otp"

        /**
         * The 12-byte Ed25519 X.509 SubjectPublicKeyInfo prefix. FastAPI pins the
         * **raw 32-byte** public key; the JVM emits SPKI (`PublicKey.getEncoded()`),
         * so ops strip this prefix to export the raw key — and never share the
         * private key. `spki = PREFIX ++ raw32`.
         */
        private val SPKI_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )

        /** Extracts the raw 32-byte Ed25519 public key from its 44-byte X.509 SPKI DER. */
        fun rawPublicFromSpki(spki: ByteArray): ByteArray {
            require(spki.size == 44 && spki.copyOfRange(0, 12).contentEquals(SPKI_PREFIX)) {
                "not an Ed25519 X.509 SPKI public key"
            }
            return spki.copyOfRange(12, 44)
        }
    }
}
