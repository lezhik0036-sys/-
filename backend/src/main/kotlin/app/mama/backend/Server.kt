package app.mama.backend

import app.mama.backend.api.ErrorResponse
import app.mama.backend.api.Readiness
import app.mama.backend.api.contactVerificationDispatchRoutes
import app.mama.backend.api.readinessRoutes
import app.mama.backend.api.trustedExitRoutes
import app.mama.backend.auth.GrantVerifier
import app.mama.backend.auth.ReceiptSigner
import app.mama.backend.db.JdbcDb
import app.mama.backend.trustedexit.ContactDestinationResolver
import app.mama.backend.trustedexit.ContactVerificationDispatchMac
import app.mama.backend.trustedexit.ContactVerificationDispatchService
import app.mama.backend.trustedexit.contactVerificationSmsMessage
import app.mama.backend.trustedexit.ENV_SMSRU_API_ID
import app.mama.backend.trustedexit.EvidenceSubmitter
import app.mama.backend.trustedexit.IngestProof
import app.mama.backend.trustedexit.OtpHasher
import app.mama.backend.trustedexit.OtpRepository
import app.mama.backend.trustedexit.SecureTrustedExitService
import app.mama.backend.trustedexit.SmsRuProvider
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
    /**
     * The private contact-verification dispatch (FastAPI -> Ktor), **disabled by
     * default** (null): the route is mounted only when a >= 32-byte dispatch secret
     * AND a real SMS provider are configured. It delivers a FastAPI-issued one-time
     * code to the trusted contact's phone and never verifies, returns or logs it.
     */
    val contactDispatch: ContactVerificationDispatchService? = null,
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

    // Ktor→FastAPI evidence channel (issue #12): the server-only HMAC proof. Off by
    // default and never auto-invoked — no transport is wired (DisabledReceiptDelivery),
    // so even a configured key sends nothing. The HMAC key must never be logged.
    val ingest = IngestProof.fromEnv(System.getenv("MAMA_INGEST_HMAC_KEY"))
    val evidence = EvidenceSubmitter(ingest)
    log.info("evidence ingest HMAC configured: ${ingest.configured}; delivery transport: disabled")

    // Private contact-verification dispatch (FastAPI -> Ktor), DISABLED by default.
    // Built only when a >= 32-byte dispatch secret AND the real SMS provider are both
    // configured; it delivers a FastAPI-issued one-time code to the contact's phone,
    // with a verification-specific message, and never logs the code. The secret and
    // the SMS key live only in the server environment — never in Git, the APK or logs.
    val dispatchMac = ContactVerificationDispatchMac.fromEnv(System.getenv("MAMA_CONTACT_DISPATCH_SECRET"))
    val verificationSms = System.getenv(ENV_SMSRU_API_ID)?.takeIf { it.isNotBlank() }?.let {
        // The verification SMS text (code + optional confirm link) is single-sourced in
        // contactVerificationSmsMessage so the wording is reviewed and tested in one place.
        SmsRuProvider(message = ::contactVerificationSmsMessage)
    }
    val contactDispatch = if (dispatchMac.configured && verificationSms != null) {
        ContactVerificationDispatchService(
            mac = dispatchMac,
            send = ContactVerificationDispatchService.fromSmsProvider(verificationSms),
        )
    } else {
        null
    }
    log.info(
        "contact-verification dispatch: ${if (contactDispatch != null) "enabled" else "disabled"} " +
            "(secret=${dispatchMac.configured}, sms=${verificationSms != null})",
    )

    return ServerDeps(
        service,
        Readiness(
            grant = verifier.configured,
            db = db != null,
            sms = sms != null,
            // Both are OFF while the actual verified-contact resolver and
            // private FastAPI mTLS evidence delivery are not installed.
            // Never promote /ready to 200 based on an SMS API key alone.
            destination = false,
            authoritativeReceiptDelivery = false,
        ),
        trustedProxies,
        evidence,
        contactDispatch,
    )
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
        contactVerificationDispatchRoutes(deps.contactDispatch)
        readinessRoutes(deps.readiness)
    }
}
