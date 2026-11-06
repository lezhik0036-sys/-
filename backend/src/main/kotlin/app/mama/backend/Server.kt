package app.mama.backend

import app.mama.backend.api.ErrorResponse
import app.mama.backend.api.Readiness
import app.mama.backend.api.readinessRoutes
import app.mama.backend.api.trustedExitRoutes
import app.mama.backend.auth.GrantVerifier
import app.mama.backend.auth.ReceiptSigner
import app.mama.backend.db.JdbcDb
import app.mama.backend.trustedexit.AuthenticatedEvidenceDelivery
import app.mama.backend.trustedexit.ContactDestinationResolver
import app.mama.backend.trustedexit.DisabledReceiptDelivery
import app.mama.backend.trustedexit.ENV_SMSRU_API_ID
import app.mama.backend.trustedexit.EvidenceSubmitter
import app.mama.backend.trustedexit.IngestProof
import app.mama.backend.trustedexit.JdkPrivateEvidenceHttpTransport
import app.mama.backend.trustedexit.KtorInternalRequestSigner
import app.mama.backend.trustedexit.OtpHasher
import app.mama.backend.trustedexit.OtpRepository
import app.mama.backend.trustedexit.ReceiptDeliveryClient
import app.mama.backend.trustedexit.SecureTrustedExitService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

/** The service + strict-readiness the server runs with (null service = fail closed). */
class ServerDeps(
    val service: SecureTrustedExitService?,
    val readiness: Readiness,
    /** Socket peers whose X-Forwarded-For may be trusted (from MAMA_TRUSTED_PROXIES); empty = ignore XFF. */
    val trustedProxies: Set<String> = emptySet(),
    /**
     * The Ktor→FastAPI evidence channel (issue #12), **disabled by default**: no transport
     * is wired and no route invokes it, so it sends nothing. The future server-authenticated
     * transfer step would call [EvidenceSubmitter.submit] with a verified receipt; it never
     * unlocks Android.
     */
    val evidence: EvidenceSubmitter = EvidenceSubmitter(IngestProof.fromEnv(null)),
)

/**
 * The MAMA Trusted Exit backend. Grant-gated, durable (PostgreSQL), fail-closed.
 *
 * Required server-side configuration (env; never in Git/APK/logs):
 *  - MAMA_GRANT_PUBLIC_KEY : pinned FastAPI Ed25519 public key (base64 X.509)
 *  - MAMA_OTP_PEPPER       : server pepper for the OTP verifier
 *  - MAMA_DB_URL/_USER/_PASSWORD : the Ktor-owned PostgreSQL (its own schema)
 *  - MAMA_SMSRU_API_ID     : SMS provider key
 *
 * If anything required is missing the server still starts (so /health works and
 * ops can see it), but /ready is 503 and the endpoints fail closed with 503.
 * The SMS destination is resolved server-side only; the stub resolver returns
 * null (fail-closed) until the FastAPI pinned internal call is implemented, so
 * this increment never actually sends.
 */
fun main() {
    val log = LoggerFactory.getLogger("app.mama.backend")
    val deps = buildDepsFromEnv(log)
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    log.info("MAMA Trusted Exit backend starting on port $port (ready=${deps.readiness.ready})")
    embeddedServer(Netty, port = port) { module(deps) }.start(wait = true)
}

/** Builds the service from the environment, migrating the DB when configured. */
fun buildDepsFromEnv(log: org.slf4j.Logger): ServerDeps {
    val grantKey = System.getenv("MAMA_GRANT_PUBLIC_KEY").orEmpty()
    val verifier = GrantVerifier(grantKey)
    // Same pinned FastAPI key; a distinct kid/purpose + challenge_id scope for /verify.
    val verifyVerifier = GrantVerifier.forVerify(grantKey)
    val hasher = OtpHasher(System.getenv("MAMA_OTP_PEPPER").orEmpty())
    val dbUrl = System.getenv("MAMA_DB_URL")
    val db = dbUrl?.takeIf { it.isNotBlank() }?.let {
        runCatching {
            JdbcDb(it, System.getenv("MAMA_DB_USER"), System.getenv("MAMA_DB_PASSWORD")).also { d -> d.migrate() }
        }.onFailure { e -> log.error("database not available: ${e.message}") }.getOrNull()
    }
    val sms = runCatching { resolveSmsProvider(System.getenv(ENV_SMSRU_API_ID)) }
        .onFailure { log.warn(it.message) }.getOrNull()

    // Verified-OTP receipt signing is OPTIONAL and OFF by default. The private key
    // (PKCS#8 DER, base64) comes only from the environment/secret store, never Git.
    // Without it, /verify returns a bare VALID with no proof and nothing can unlock.
    val receiptSigner = System.getenv("MAMA_RECEIPT_SIGNING_KEY")?.takeIf { it.isNotBlank() }
        ?.let { ReceiptSigner(it) }?.takeIf { it.configured }
    log.info("verified-OTP receipt signing: ${if (receiptSigner != null) "enabled" else "disabled"}")

    val configured = verifier.configured && hasher.configured && db != null && sms != null
    val service = if (configured) {
        SecureTrustedExitService(
            verifier = verifier,
            verifyVerifier = verifyVerifier,
            repo = OtpRepository(db!!, hasher),
            hasher = hasher,
            sms = sms!!,
            // Fail-closed until the FastAPI pinned internal resolution is wired (Deliverable B).
            destinations = ContactDestinationResolver { null },
            receiptSigner = receiptSigner,
        )
    } else {
        null
    }
    // Trusted reverse-proxy peers whose X-Forwarded-For we honour (comma/space
    // separated). Empty by default → XFF ignored, per-IP quota keys on the socket.
    val trustedProxies = System.getenv("MAMA_TRUSTED_PROXIES").orEmpty()
        .split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    // Ktor→FastAPI evidence channel (issue #12 / Stage 2b): the server-authenticated
    // HTTP push of a signed OTP receipt. Wired to the REAL JDK transport only when
    // BOTH the private FastAPI internal base URL and the ≥32-byte transport key are
    // present; with either missing it stays Disabled and sends nothing (fail-closed).
    // No route auto-invokes it yet, and neither the transport key nor any HMAC is
    // ever logged.
    val wiring = buildEvidenceWiring(
        ingestKey = System.getenv("MAMA_INGEST_HMAC_KEY"),
        fastapiInternalUrl = System.getenv("MAMA_FASTAPI_INTERNAL_URL"),
        transportKey = System.getenv("MAMA_KTOR_TRANSPORT_KEY"),
        receiptSignerConfigured = receiptSigner != null,
    )
    log.info("authoritative receipt delivery wired: ${wiring.authoritativeDelivery}")

    return ServerDeps(
        service,
        Readiness(
            grant = verifier.configured,
            db = db != null,
            sms = sms != null,
            // destination stays OFF until the verified-contact resolver is installed,
            // so /ready remains fail-closed regardless of the delivery channel.
            // authoritativeReceiptDelivery now reflects the real wired channel.
            destination = false,
            authoritativeReceiptDelivery = wiring.authoritativeDelivery,
        ),
        trustedProxies,
        wiring.submitter,
    )
}

/** The evidence channel plus whether authoritative receipt delivery is live (Stage 2b). */
data class EvidenceWiring(val submitter: EvidenceSubmitter, val authoritativeDelivery: Boolean)

/**
 * Builds the Ktor→FastAPI evidence channel from configuration, fail-closed.
 *
 * The real JDK transport is wired only when BOTH the private FastAPI internal base
 * URL and the ≥32-byte transport key are present; with either missing the channel
 * is [DisabledReceiptDelivery] and sends nothing. [EvidenceWiring.authoritativeDelivery]
 * is true only when the channel is fully live (transport + transport key + ingest
 * HMAC) AND a receipt signer is configured — there is both a signed receipt to
 * deliver and an authenticated channel to carry it — so a base URL, a key, or an
 * HMAC alone can never flip readiness. The transport key is consumed here, never
 * logged, and a sub-threshold key fails closed via the signer.
 */
fun buildEvidenceWiring(
    ingestKey: String?,
    fastapiInternalUrl: String?,
    transportKey: String?,
    receiptSignerConfigured: Boolean,
): EvidenceWiring {
    val ingest = IngestProof.fromEnv(ingestKey)
    val url = fastapiInternalUrl?.takeIf { it.isNotBlank() }
    val key = transportKey?.takeIf { it.isNotBlank() }
    val delivery: ReceiptDeliveryClient = if (url != null && key != null) {
        AuthenticatedEvidenceDelivery(
            KtorInternalRequestSigner(key.toByteArray(Charsets.UTF_8)),
            JdkPrivateEvidenceHttpTransport(url),
        )
    } else {
        DisabledReceiptDelivery
    }
    val submitter = EvidenceSubmitter(ingest, delivery)
    return EvidenceWiring(submitter, submitter.enabled && receiptSignerConfigured)
}

/** The Ktor application, shared by [main] and tests (which pass a test-wired [deps]). */
fun Application.module(deps: ServerDeps) {
    install(ContentNegotiation) { gson { } }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            LoggerFactory.getLogger("app.mama.backend").error("request failed", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error"))
        }
    }
    routing {
        trustedExitRoutes(deps.service, deps.trustedProxies)
        readinessRoutes(deps.readiness)
    }
}
