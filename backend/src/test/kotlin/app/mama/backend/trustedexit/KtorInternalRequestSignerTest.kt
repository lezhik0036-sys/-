package app.mama.backend.trustedexit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fixed, independently calculated Python stdlib hmac/hashlib vector. */
class KtorInternalRequestSignerTest {
    private val secret = "synthetic-private-transport-secret-32bytes-001"
    private val body = """{"token":"hdr.payload.sig","macB64":"synthetic"}"""
        .toByteArray(Charsets.UTF_8)
    private val nonce = "123e4567-e89b-42d3-a456-426614174000"
    private val time = 1793880000L

    @Test fun `Ktor produces byte-exact Python FastAPI service request MAC`() {
        val signer = KtorInternalRequestSigner(secret.toByteArray(Charsets.UTF_8))
        assertTrue(signer.configured)
        val result = signer.sign(body, KtorInternalRequestSigner.PATH_EVIDENCE, time, nonce)!!
        assertEquals(time, result.timestamp)
        assertEquals(nonce, result.nonce)
        assertEquals(
            "6cd15297fef2918dc088dcab9d89cfbfb00e88bd2acfd012fac75d7c6cc95979",
            result.macHex,
        )
    }

    @Test fun `tampered body and cross endpoint replay require a different signature`() {
        val signer = KtorInternalRequestSigner(secret.toByteArray(Charsets.UTF_8))
        val original = signer.sign(body, KtorInternalRequestSigner.PATH_EVIDENCE, time, nonce)!!
        assertNotEquals(
            original.macHex,
            signer.sign(body + byteArrayOf(32), KtorInternalRequestSigner.PATH_EVIDENCE, time, nonce)!!.macHex,
        )
        assertNotEquals(
            original.macHex,
            signer.sign(body, KtorInternalRequestSigner.PATH_EVIDENCE_STATUS, time, nonce)!!.macHex,
        )
        assertNotEquals(
            original.macHex,
            signer.sign(body, KtorInternalRequestSigner.PATH_EVIDENCE, time+1, nonce)!!.macHex,
        )
    }

    @Test fun `not configured malformed body path nonce or time refuses signing`() {
        val disabled = KtorInternalRequestSigner(byteArrayOf(1, 2, 3))
        assertFalse(disabled.configured)
        assertNull(disabled.sign(body, KtorInternalRequestSigner.PATH_EVIDENCE, time, nonce))
        val signer = KtorInternalRequestSigner(secret.toByteArray(Charsets.UTF_8))
        assertNull(signer.sign(body, "/trusted-exit/public", time, nonce))
        assertNull(signer.sign(body, KtorInternalRequestSigner.PATH_EVIDENCE, -1, nonce))
        assertNull(signer.sign(body, KtorInternalRequestSigner.PATH_EVIDENCE, time, nonce.uppercase()))
        assertNull(signer.sign(body, KtorInternalRequestSigner.PATH_EVIDENCE, time, "not-uuid"))
        assertNull(signer.sign(ByteArray(4097), KtorInternalRequestSigner.PATH_EVIDENCE, time, nonce))
    }
}
