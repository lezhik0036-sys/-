package app.mama.backend

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stage 2b wiring (Ktor→FastAPI evidence push). The authoritative receipt-delivery
 * channel is live only when the private FastAPI base URL, the ≥32-byte transport
 * key, the ingest HMAC and a receipt signer are ALL configured. Any single one
 * missing fails closed, so no lone value can flip [EvidenceWiring.authoritativeDelivery]
 * — and hence Ktor /ready — on its own. No real network is touched here.
 */
class EvidenceWiringTest {
    private val url = "https://fastapi.internal"
    private val key = "synthetic-private-transport-secret-32bytes-001"
    private val ingest = "synthetic-ingest-secret-is-not-transport-secret-0001"

    @Test fun `full config makes the channel enabled and delivery authoritative`() {
        val w = buildEvidenceWiring(ingest, url, key, receiptSignerConfigured = true)
        assertTrue(w.submitter.enabled, "channel should be enabled")
        assertTrue(w.authoritativeDelivery, "delivery should be authoritative")
    }

    @Test fun `missing FastAPI url disables the channel and is never authoritative`() {
        val w = buildEvidenceWiring(ingest, null, key, receiptSignerConfigured = true)
        assertFalse(w.submitter.enabled)
        assertFalse(w.authoritativeDelivery)
    }

    @Test fun `blank transport key disables the channel and is never authoritative`() {
        val w = buildEvidenceWiring(ingest, url, "   ", receiptSignerConfigured = true)
        assertFalse(w.submitter.enabled)
        assertFalse(w.authoritativeDelivery)
    }

    @Test fun `sub-threshold transport key fails closed`() {
        val w = buildEvidenceWiring(ingest, url, "short-key", receiptSignerConfigured = true)
        assertFalse(w.submitter.enabled)
        assertFalse(w.authoritativeDelivery)
    }

    @Test fun `missing ingest HMAC is not authoritative even with transport wired`() {
        val w = buildEvidenceWiring(null, url, key, receiptSignerConfigured = true)
        assertFalse(w.authoritativeDelivery)
    }

    @Test fun `no receipt signer leaves the channel usable but delivery not authoritative`() {
        val w = buildEvidenceWiring(ingest, url, key, receiptSignerConfigured = false)
        assertFalse(w.authoritativeDelivery)
    }
}
