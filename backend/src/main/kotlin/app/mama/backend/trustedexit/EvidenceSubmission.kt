package app.mama.backend.trustedexit

import java.util.Base64

/**
 * The Ktor→FastAPI evidence channel (issue #12), **test-only and disabled by default**.
 * It carries a successfully-verified OTP receipt plus its server-only HMAC proof
 * ([IngestProof]) to FastAPI's internal admission routine over a server-authenticated
 * transport. It performs NO Android unlock: FastAPI independently re-verifies everything
 * and owns the business transition; Android only ever sees the authoritative series
 * status (not produced here).
 *
 * No real HTTP transport is implemented or enabled here. [ReceiptDeliveryClient] is an
 * abstraction whose production default ([DisabledReceiptDelivery]) sends nothing
 * (fail-closed); the pinned-mTLS transport is design-only pending review (see the design
 * doc). Tests supply a fake client.
 */

/**
 * The exact bytes delivered server-to-server. It is deliberately minimal: only the signed
 * receipt [token] and its base64 [macB64]. It NEVER carries a client-chosen destination
 * number, an Android session id, the OTP, or the HMAC key — none of those are authority.
 */
data class EvidenceSubmission(val token: String, val macB64: String)

/** Result of a delivery attempt. */
sealed interface DeliveryOutcome {
    /** FastAPI admitted the evidence (or idempotently ignored a duplicate). */
    data object Accepted : DeliveryOutcome

    /** No transport/secret configured — nothing was sent (the default, fail-closed). */
    data object Disabled : DeliveryOutcome

    /** The ACK was lost (timeout / connection drop). Safe to retry: FastAPI dedups by jti + challenge. */
    data class Unknown(val reason: String) : DeliveryOutcome

    /** FastAPI refused the evidence (bad proof, binding, replay, policy). */
    data class Rejected(val reason: String) : DeliveryOutcome
}

/**
 * The authenticated Ktor→FastAPI transport. Production would pin TLS/mTLS to the FastAPI
 * internal service and present a service credential; it is NOT implemented here.
 */
fun interface ReceiptDeliveryClient {
    fun deliver(submission: EvidenceSubmission): DeliveryOutcome
}

/** Production default: no transport wired, so nothing is ever sent (fail-closed). */
object DisabledReceiptDelivery : ReceiptDeliveryClient {
    override fun deliver(submission: EvidenceSubmission): DeliveryOutcome = DeliveryOutcome.Disabled
}

/**
 * Builds the dual-proof submission for a verified receipt and delivers it. [enabled] is
 * true only when BOTH the HMAC secret and a non-disabled transport are configured; until
 * then [submit] is a no-op returning [DeliveryOutcome.Disabled].
 *
 * Idempotency / lost-network: [submit] is a pure function of the receipt (same token →
 * same MAC), so a retry after a lost ACK re-sends byte-identical evidence. FastAPI's
 * `UNIQUE(ktor_jti)` + `UNIQUE(provider_challenge_id)` make a re-delivery a no-op, so there
 * is no double transition or double cooldown.
 */
class EvidenceSubmitter(
    private val ingest: IngestProof,
    private val delivery: ReceiptDeliveryClient = DisabledReceiptDelivery,
) {
    val enabled: Boolean get() = ingest.configured && delivery !== DisabledReceiptDelivery

    /** Submits the receipt as dual-proof evidence, or [DeliveryOutcome.Disabled] if not wired. */
    fun submit(receiptJwt: String): DeliveryOutcome {
        val mac = ingest.mac(receiptJwt) ?: return DeliveryOutcome.Disabled
        return delivery.deliver(EvidenceSubmission(receiptJwt, b64.encodeToString(mac)))
    }

    private companion object {
        val b64: Base64.Encoder = Base64.getEncoder()
    }
}
