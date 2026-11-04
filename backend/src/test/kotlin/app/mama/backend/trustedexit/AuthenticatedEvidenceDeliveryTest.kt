package app.mama.backend.trustedexit

import com.google.gson.JsonParser
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** No sockets, actual SMS or VPS are touched. */
class AuthenticatedEvidenceDeliveryTest {
    private val ingestKey = "synthetic-ingest-secret-is-not-transport-secret-0001"
    private val transportKey = "synthetic-private-transport-secret-32bytes-001"
    private val testReceipt = "hdr.payload.sig"
    private val fixedTime = 1793880000L
    private val fixedNonce = "123e4567-e89b-42d3-a456-426614174000"

    @Test fun `triple proof body is identical to the bytes authenticated by transport key`() {
        var captured: PrivateEvidenceHttpRequest? = null
        val network = PrivateEvidenceHttpTransport { request ->
            captured = request
            DeliveryOutcome.Accepted
        }
        val adapter = AuthenticatedEvidenceDelivery(
            KtorInternalRequestSigner(transportKey.toByteArray()), network,
            timestamp = { fixedTime }, nonce = { fixedNonce },
        )
        val submitter = EvidenceSubmitter(IngestProof.fromEnv(ingestKey), adapter)
        assertTrue(submitter.enabled)
        assertEquals(DeliveryOutcome.Accepted, submitter.submit(testReceipt))
        val request = captured!!
        assertEquals("POST", request.method)
        assertEquals(KtorInternalRequestSigner.PATH_EVIDENCE, request.path)
        assertEquals(fixedTime, request.timestamp)
        assertEquals(fixedNonce, request.nonce)
        assertEquals(
            KtorInternalRequestSigner(transportKey.toByteArray())
                .sign(request.body, request.path, fixedTime, fixedNonce)!!.macHex,
            request.macHex,
        )
        val decoded = JsonParser.parseString(String(request.body, Charsets.UTF_8)).asJsonObject
        assertEquals(setOf("token", "macB64"), decoded.keySet())
        assertEquals(testReceipt, decoded.get("token").asString)
        val mac = Base64.getDecoder().decode(decoded.get("macB64").asString)
        assertTrue(mac.contentEquals(IngestProof.fromEnv(ingestKey).mac(testReceipt)))
    }

    @Test fun `missing either service secret or transport sends nothing`() {
        var calls = 0
        val network = PrivateEvidenceHttpTransport { calls++; DeliveryOutcome.Accepted }
        val notConfigured = AuthenticatedEvidenceDelivery(
            KtorInternalRequestSigner(byteArrayOf(1)), network,
        )
        assertEquals(DeliveryOutcome.Disabled,
            EvidenceSubmitter(IngestProof.fromEnv(ingestKey), notConfigured)
                .submit(testReceipt))
        val noNetwork = AuthenticatedEvidenceDelivery(
            KtorInternalRequestSigner(transportKey.toByteArray()),
        )
        assertFalse(EvidenceSubmitter(IngestProof.fromEnv(ingestKey), noNetwork).enabled)
        assertFalse(EvidenceSubmitter(IngestProof.fromEnv(ingestKey), notConfigured).enabled)
        assertEquals(DeliveryOutcome.Disabled,
            EvidenceSubmitter(IngestProof.fromEnv(ingestKey), noNetwork)
                .submit(testReceipt))
        val noEvidenceKey = AuthenticatedEvidenceDelivery(
            KtorInternalRequestSigner(transportKey.toByteArray()), network,
        )
        assertEquals(DeliveryOutcome.Disabled,
            EvidenceSubmitter(IngestProof.fromEnv(null), noEvidenceKey)
                .submit(testReceipt))
        assertEquals(0, calls)
    }

    @Test fun `lost transport ACK remains unresolved, never implies Android unlocked`() {
        val network = PrivateEvidenceHttpTransport { DeliveryOutcome.Unknown("simulated timeout") }
        val adapter = AuthenticatedEvidenceDelivery(
            KtorInternalRequestSigner(transportKey.toByteArray()), network,
            timestamp = { fixedTime }, nonce = { fixedNonce },
        )
        val result = EvidenceSubmitter(IngestProof.fromEnv(ingestKey), adapter).submit(testReceipt)
        assertTrue(result is DeliveryOutcome.Unknown)
    }
}
