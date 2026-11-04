package app.mama.backend.trustedexit

import com.google.gson.JsonObject
import java.util.UUID

/**
 * Disabled-by-default Ktor staging adapter: wraps an Ed25519 OTP receipt and
 * its independent ingest HMAC in an authenticated server-to-server request.
 *
 * No networking takes place here. A future transport implementation MUST use
 * TLS certificate validation + pinned mutual TLS with a private FastAPI peer,
 * bounded response times and no redirect following. It MUST NOT expose the
 * request MAC, receipt, or client secrets to Android or HTTP logs.
 */
data class PrivateEvidenceHttpRequest(
    val method: String,
    val path: String,
    val timestamp: Long,
    val nonce: String,
    val macHex: String,
    val body: ByteArray,
)

/** Injected mTLS transport. There is intentionally no production implementation. */
fun interface PrivateEvidenceHttpTransport {
    fun post(request: PrivateEvidenceHttpRequest): DeliveryOutcome
}

object DisabledPrivateEvidenceHttpTransport : PrivateEvidenceHttpTransport {
    override fun post(request: PrivateEvidenceHttpRequest): DeliveryOutcome = DeliveryOutcome.Disabled
}

/**
 * Maps the existing submitter's exact token/macB64 into a signed HTTP
 * envelope. A matching request authenticator exists in private FastAPI PR #33.
 *
 * The initial ACCEPTED response is evidence storage only; it does not mean
 * series ended or Android may unlock. Lost ACK remains Unknown.
 */
class AuthenticatedEvidenceDelivery(
    private val signer: KtorInternalRequestSigner,
    private val transport: PrivateEvidenceHttpTransport = DisabledPrivateEvidenceHttpTransport,
    private val timestamp: () -> Long = { System.currentTimeMillis() / 1000 },
    private val nonce: () -> String = { UUID.randomUUID().toString() },
) : ReceiptDeliveryClient {
    val configured: Boolean
        get() = signer.configured && transport !== DisabledPrivateEvidenceHttpTransport

    override fun deliver(submission: EvidenceSubmission): DeliveryOutcome {
        if (!configured) {
            return DeliveryOutcome.Disabled
        }
        val body = JsonObject().apply {
            addProperty("token", submission.token)
            addProperty("macB64", submission.macB64)
        }.toString().toByteArray(Charsets.UTF_8)
        val proof = signer.sign(
            body, KtorInternalRequestSigner.PATH_EVIDENCE,
            timestamp(), nonce(),
        ) ?: return DeliveryOutcome.Disabled
        return transport.post(
            PrivateEvidenceHttpRequest(
                method = "POST",
                path = KtorInternalRequestSigner.PATH_EVIDENCE,
                timestamp = proof.timestamp,
                nonce = proof.nonce,
                macHex = proof.macHex,
                body = body,
            ),
        )
    }
}
