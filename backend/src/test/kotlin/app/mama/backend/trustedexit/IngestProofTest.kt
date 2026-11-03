package app.mama.backend.trustedexit

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server-only HMAC ingest proof (issue #12), pinned to the FastAPI
 * `trusted_exit_signed_evidence_service` contract: HMAC-SHA256 over
 * `MAC_DOMAIN + ascii(token)` with a ≥32-byte secret. Ephemeral key, no network.
 */
class IngestProofTest {

    private val secret = "ephemeral-integration-only-ktor-service-secret-001" // 50 bytes
    private val token = "aaa.bbb.ccc" // stand-in receipt JWT

    /** Independent reference HMAC, written separately from the production code. */
    private fun reference(secret: ByteArray, token: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
            .doFinal("MAMA/ktor-verified-otp-ingest/v1\n".toByteArray(Charsets.US_ASCII) + token.toByteArray(Charsets.US_ASCII))

    @Test fun `MAC_DOMAIN is the exact purpose-separation prefix`() {
        assertContentEquals("MAMA/ktor-verified-otp-ingest/v1\n".toByteArray(Charsets.US_ASCII), IngestProof.MAC_DOMAIN)
    }

    @Test fun `the MAC is HMAC-SHA256 over the domain and exact token bytes`() {
        val mac = IngestProof.fromEnv(secret).mac(token)!!
        assertEquals(32, mac.size, "SHA-256 digest")
        assertContentEquals(reference(secret.toByteArray(Charsets.UTF_8), token), mac)
    }

    @Test fun `a modified token yields a different MAC`() {
        val p = IngestProof.fromEnv(secret)
        assertFalse(p.mac(token)!!.contentEquals(p.mac(token + "x")!!))
    }

    @Test fun `a different secret yields a different MAC`() {
        val a = IngestProof.fromEnv(secret).mac(token)!!
        val b = IngestProof.fromEnv("another-ephemeral-service-secret-00000000").mac(token)!!
        assertFalse(a.contentEquals(b))
    }

    @Test fun `an absent or too-short secret is unconfigured and mints nothing`() {
        assertFalse(IngestProof.fromEnv(null).configured)
        assertNull(IngestProof.fromEnv(null).mac(token))
        assertFalse(IngestProof.fromEnv("").configured)
        assertFalse(IngestProof.fromEnv("short-key").configured, "under 32 bytes is rejected")
        assertNull(IngestProof.fromEnv("short-key").mac(token))
        assertTrue(IngestProof.fromEnv(secret).configured)
    }
}
