package app.mama.backend.trustedexit

import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * INTERNAL ONLY: Ktor→FastAPI HTTP authentication envelope, matching FastAPI PR #33.
 *
 * A MAC over exact HTTP method/private path, 30s timestamp, UUIDv4 nonce
 * and SHA256(raw UTF-8 request body). This key is DISTINCT from the OTP
 * evidence ingest HMAC key and from both Ed25519 signing keys. mTLS and
 * independent evidence signature/ownership checks are ALSO mandatory.
 *
 * This is just a pure signer. NO HTTP transport, routes, production key
 * configuration, actual SMS or Android unlocking are enabled.
 */
data class KtorInternalRequestProof(
    val timestamp: Long,
    val nonce: String,
    val macHex: String,
)

class KtorInternalRequestSigner(secret: ByteArray) {
    private val key: SecretKeySpec? =
        if (secret.size >= MIN_SECRET_BYTES) SecretKeySpec(secret.copyOf(), "HmacSHA256") else null

    val configured: Boolean get() = key != null

    fun sign(
        body: ByteArray,
        path: String,
        timestamp: Long,
        nonce: String = UUID.randomUUID().toString(),
    ): KtorInternalRequestProof? {
        val key = key ?: return null
        val message = canonicalBytes(body, path, timestamp, nonce) ?: return null
        val result = Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(message)
        val hex = result.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return KtorInternalRequestProof(timestamp, nonce, hex)
    }

    /**
     * Verify an inbound FastAPI→Ktor request envelope (the reverse direction of
     * [sign]): recompute the MAC over the exact received body/path/timestamp/nonce
     * and compare in constant time, with a ±[TIMESTAMP_WINDOW_SECONDS] freshness
     * window. Returns false (fail-closed) when unconfigured, malformed, stale or
     * mismatched. Replay is NOT gated here — this is only sound for a read-only,
     * side-effect-free request (e.g. dispatch-status reconciliation); any request
     * that changes state MUST additionally reserve a durable one-time nonce.
     */
    fun verify(
        macHex: String?, body: ByteArray, path: String, timestamp: Long, nonce: String?,
        nowEpochSeconds: Long,
    ): Boolean {
        if (key == null) return false
        if (macHex == null || !HEX64.matches(macHex)) return false
        if (nonce == null) return false
        if (kotlin.math.abs(nowEpochSeconds - timestamp) > TIMESTAMP_WINDOW_SECONDS) return false
        val expected = sign(body, path, timestamp, nonce)?.macHex ?: return false
        return MessageDigest.isEqual(
            macHex.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII),
        )
    }

    companion object {
        const val MIN_SECRET_BYTES = 32
        const val MAX_BODY_BYTES = 4096
        const val TIMESTAMP_WINDOW_SECONDS = 30L
        const val PATH_EVIDENCE = "/internal/trusted-exit/evidence"
        const val PATH_EVIDENCE_STATUS = "/internal/trusted-exit/evidence-status"
        val DOMAIN = "MAMA/ktor-fastapi-request/v1\n".toByteArray(Charsets.US_ASCII)
        private val NONCE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        /** POST only; body bytes must not be reserialized after signing. */
        internal fun canonicalBytes(
            body: ByteArray, path: String, timestamp: Long, nonce: String,
        ): ByteArray? {
            if (path != PATH_EVIDENCE && path != PATH_EVIDENCE_STATUS) return null
            if (timestamp < 0L || timestamp > 253402300799L) return null
            if (body.size > MAX_BODY_BYTES) return null
            if (!NONCE.matches(nonce)) return null
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(body)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val suffix = "POST\n$path\n$timestamp\n$nonce\n$digest".toByteArray(Charsets.US_ASCII)
            return DOMAIN + suffix
        }
    }
}
