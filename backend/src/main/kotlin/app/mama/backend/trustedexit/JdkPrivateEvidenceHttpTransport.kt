package app.mama.backend.trustedexit

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The real Ktor→FastAPI evidence transport over HTTP, the production counterpart
 * of [DisabledPrivateEvidenceHttpTransport]. It POSTs the authenticated envelope
 * built by [AuthenticatedEvidenceDelivery] to `<baseUrl><path>` with the
 * `x-mama-timestamp` / `x-mama-nonce` / `x-mama-mac` headers and the exact signed
 * body bytes, and maps FastAPI's response to a [DeliveryOutcome].
 *
 * Wire contract — byte-for-byte matched to FastAPI
 * `app/trusted_exit_internal_api.py` (`POST /internal/trusted-exit/evidence`):
 *   * headers carry the transport MAC over `POST\n{path}\n{ts}\n{nonce}\n{sha256(body)}`;
 *   * body is `{"token","macB64"}`; FastAPI re-verifies the transport MAC + the
 *     ingest MAC + the Ed25519 receipt + all bindings before recording evidence.
 *
 * Response mapping:
 *   * `204` (and any other 2xx) → [DeliveryOutcome.Accepted];
 *   * `409` → [DeliveryOutcome.Rejected] ("not admissible": a genuine refusal OR
 *     the idempotent duplicate of already-recorded evidence — the caller must not
 *     read this as "never recorded", see [DeliveryOutcome.Rejected]);
 *   * `400`/`401`/`403`/`503` → [DeliveryOutcome.Rejected] with the status;
 *   * a timeout / connection drop → [DeliveryOutcome.Unknown] (the ACK is lost and
 *     stays unresolved; a blind resend cannot promote it to success).
 *
 * **TLS is still required in production.** The envelope's three MACs give
 * integrity and authentication, not confidentiality or peer identity, so a real
 * deployment constructs this with an mTLS-pinned [client] and an `https` baseUrl
 * to a private FastAPI peer. A plaintext baseUrl is only for a loopback test or a
 * trusted local network; [Readiness.authoritativeReceiptDelivery] stays the gate
 * on whether the exit path treats delivery as authoritative. Disabled (returns
 * [DeliveryOutcome.Disabled]) when no baseUrl is configured — fail-closed.
 */
class JdkPrivateEvidenceHttpTransport(
    private val baseUrl: String?,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(10),
) : PrivateEvidenceHttpTransport {

    val configured: Boolean get() = !baseUrl.isNullOrBlank()

    override fun post(request: PrivateEvidenceHttpRequest): DeliveryOutcome {
        val base = baseUrl?.trimEnd('/')
        if (base.isNullOrBlank()) return DeliveryOutcome.Disabled
        if (request.method != "POST") return DeliveryOutcome.Rejected("unsupported method")
        if (request.body.size > KtorInternalRequestSigner.MAX_BODY_BYTES) {
            return DeliveryOutcome.Rejected("body too large")
        }
        val uri = runCatching { URI.create(base + request.path) }.getOrNull()
            ?: return DeliveryOutcome.Rejected("bad url")
        val httpRequest = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(requestTimeout)
            .header("content-type", "application/json")
            .header("x-mama-timestamp", request.timestamp.toString())
            .header("x-mama-nonce", request.nonce)
            .header("x-mama-mac", request.macHex)
            .POST(HttpRequest.BodyPublishers.ofByteArray(request.body))
            .build()
        val response = runCatching {
            client.send(httpRequest, HttpResponse.BodyHandlers.discarding())
        }.getOrElse {
            // Timeout / connection drop: the ACK is lost and MUST stay unresolved.
            return DeliveryOutcome.Unknown("transport error")
        }
        return when (val code = response.statusCode()) {
            in 200..299 -> DeliveryOutcome.Accepted
            409 -> DeliveryOutcome.Rejected("not admissible")
            400, 401, 403, 503 -> DeliveryOutcome.Rejected("refused ($code)")
            else -> DeliveryOutcome.Rejected("http $code")
        }
    }
}
