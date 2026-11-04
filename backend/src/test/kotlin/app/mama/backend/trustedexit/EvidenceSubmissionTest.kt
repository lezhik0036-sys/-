package app.mama.backend.trustedexit

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The disabled-by-default Ktor→FastAPI evidence submitter (issue #12). No real transport
 * is exercised; a fake records what would be delivered. Confirms the dual-proof payload,
 * fail-closed behaviour, and idempotent retry after a lost ACK.
 */
class EvidenceSubmissionTest {

    private val secret = "ephemeral-integration-only-ktor-service-secret-001"
    private val receipt = "hdr.payload.sig" // stand-in receipt JWT

    /** A fake transport that records deliveries and can simulate a lost ACK. */
    private class FakeDelivery(private val outcomes: ArrayDeque<DeliveryOutcome>) : ReceiptDeliveryClient {
        val sent = mutableListOf<EvidenceSubmission>()
        override fun deliver(submission: EvidenceSubmission): DeliveryOutcome {
            sent.add(submission)
            return if (outcomes.isEmpty()) DeliveryOutcome.Accepted else outcomes.removeFirst()
        }
    }

    private fun expectedMacB64() = Base64.getEncoder().encodeToString(IngestProof.fromEnv(secret).mac(receipt)!!)

    @Test fun `a configured submitter delivers the receipt and its HMAC, nothing else`() {
        val fake = FakeDelivery(ArrayDeque())
        val submitter = EvidenceSubmitter(IngestProof.fromEnv(secret), fake)
        assertTrue(submitter.enabled)
        assertEquals(DeliveryOutcome.Accepted, submitter.submit(receipt))
        assertEquals(1, fake.sent.size)
        // The payload is exactly {token, macB64} — no destination, session, phone, OTP or key.
        assertEquals(EvidenceSubmission(receipt, expectedMacB64()), fake.sent.single())
    }

    @Test fun `the default transport is disabled and sends nothing`() {
        val submitter = EvidenceSubmitter(IngestProof.fromEnv(secret)) // DisabledReceiptDelivery default
        assertFalse(submitter.enabled)
        assertEquals(DeliveryOutcome.Disabled, submitter.submit(receipt))
    }

    @Test fun `an unconfigured HMAC secret sends nothing even with a transport`() {
        val fake = FakeDelivery(ArrayDeque())
        val submitter = EvidenceSubmitter(IngestProof.fromEnv(null), fake)
        assertFalse(submitter.enabled)
        assertEquals(DeliveryOutcome.Disabled, submitter.submit(receipt))
        assertTrue(fake.sent.isEmpty(), "no MAC, so nothing is delivered (fail-closed)")
    }

    @Test fun `a lost-ACK retry re-sends byte-identical evidence and a dedup refusal stays unresolved`() {
        // First attempt: the ACK is lost (FastAPI may or may not have recorded the receipt).
        // Retry: FastAPI PR #15 denies the duplicate (INSERT ... ON CONFLICT DO NOTHING RETURNING id
        // → raise), so the retry comes back as Rejected — NOT Accepted. The submitter returns that
        // faithfully; it must not be upgraded to success. True state is unresolved pending a status API.
        val fake = FakeDelivery(ArrayDeque(listOf(DeliveryOutcome.Unknown("timeout"), DeliveryOutcome.Rejected("duplicate"))))
        val submitter = EvidenceSubmitter(IngestProof.fromEnv(secret), fake)
        val first = submitter.submit(receipt)
        assertTrue(first is DeliveryOutcome.Unknown)
        val second = submitter.submit(receipt) // retry
        assertTrue(second is DeliveryOutcome.Rejected, "a duplicate is refused, not acked (no permissive acceptance)")
        assertEquals(2, fake.sent.size)
        // The retry is byte-identical, so FastAPI's UNIQUE(jti)+UNIQUE(challenge) store at most one
        // receipt / one transition — the refusal prevents a duplicate, it does not mean "never recorded".
        assertEquals(fake.sent[0], fake.sent[1])
    }

    @Test fun `a first delivery with no prior record is accepted`() {
        val fake = FakeDelivery(ArrayDeque())
        val submitter = EvidenceSubmitter(IngestProof.fromEnv(secret), fake)
        assertEquals(DeliveryOutcome.Accepted, submitter.submit(receipt))
    }
}
