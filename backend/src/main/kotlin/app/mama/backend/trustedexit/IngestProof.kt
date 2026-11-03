package app.mama.backend.trustedexit

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The **server-only** ingest proof for the Ktor→FastAPI evidence channel (issue #12,
 * FastAPI `mama-fastapi` PR #15). A Ktor verified-OTP receipt (an Ed25519 JWT) is by
 * itself observable by the Android client, so it must NOT authorize evidence admission.
 * FastAPI therefore additionally requires a purpose-separated HMAC over the exact token
 * bytes, keyed by a secret held only in the two servers' environments / secret store —
 * never in the APK, Git, or any Android-facing response or log.
 *
 * Matches `trusted_exit_signed_evidence_service.internal_record_signed_ktor_evidence`:
 *   HMAC-SHA256(secret, MAC_DOMAIN + ascii(token))  →  32-byte digest
 * with `MAC_DOMAIN = b"MAMA/ktor-verified-otp-ingest/v1\n"` and a secret of ≥ 32 bytes.
 *
 * Off by default: with no `MAMA_INGEST_HMAC_KEY` (or a secret shorter than 32 bytes) the
 * proof is not configured and [mac] returns null, so no evidence can be delivered
 * (fail-closed). This class never unlocks anything and has no network side effects.
 */
class IngestProof(secret: ByteArray) {
    private val key: SecretKeySpec? =
        if (secret.size >= MIN_SECRET_BYTES) SecretKeySpec(secret, "HmacSHA256") else null

    val configured: Boolean get() = key != null

    /** The 32-byte HMAC over `MAC_DOMAIN + ascii(token)`, or null if not configured. */
    fun mac(token: String): ByteArray? {
        val k = key ?: return null
        val ascii = token.toByteArray(Charsets.US_ASCII)
        return Mac.getInstance("HmacSHA256").apply { init(k) }.run {
            update(MAC_DOMAIN)
            doFinal(ascii)
        }
    }

    companion object {
        /** Exact purpose-separation prefix shared with FastAPI (note the trailing LF). */
        val MAC_DOMAIN: ByteArray = "MAMA/ktor-verified-otp-ingest/v1\n".toByteArray(Charsets.US_ASCII)
        const val MIN_SECRET_BYTES = 32

        /**
         * Builds an [IngestProof] from the configured shared secret string (its UTF-8
         * bytes; both servers MUST configure the identical value). A blank/short value
         * yields an unconfigured proof. The caller must never log this value.
         */
        fun fromEnv(value: String?): IngestProof =
            IngestProof(value.orEmpty().toByteArray(Charsets.UTF_8))
    }
}
