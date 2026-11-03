package app.mama.backend

import app.mama.backend.api.ErrorResponse
import app.mama.backend.api.Readiness
import app.mama.backend.api.readinessRoutes
import app.mama.backend.api.trustedExitRoutes
import app.mama.backend.auth.GrantVerifier
import app.mama.backend.db.JdbcDb
import app.mama.backend.trustedexit.ContactDestinationResolver
import app.mama.backend.trustedexit.ENV_SMSRU_API_ID
import app.mama.backend.trustedexit.OtpHasher
import app.mama.backend.trustedexit.OtpRepository
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
    val verifier = GrantVerifier(System.getenv("MAMA_GRANT_PUBLIC_KEY").orEmpty())
    val hasher = OtpHasher(System.getenv("MAMA_OTP_PEPPER").orEmpty())
    val dbUrl = System.getenv("MAMA_DB_URL")
    val db = dbUrl?.takeIf { it.isNotBlank() }?.let {
        runCatching {
            JdbcDb(it, System.getenv("MAMA_DB_USER"), System.getenv("MAMA_DB_PASSWORD")).also { d -> d.migrate() }
        }.onFailure { e -> log.error("database not available: ${e.message}") }.getOrNull()
    }
    val sms = runCatching { resolveSmsProvider(System.getenv(ENV_SMSRU_API_ID)) }
        .onFailure { log.warn(it.message) }.getOrNull()

    val configured = verifier.configured && hasher.configured && db != null && sms != null
    val service = if (configured) {
        SecureTrustedExitService(
            verifier = verifier,
            repo = OtpRepository(db!!, hasher),
            hasher = hasher,
            sms = sms!!,
            // Fail-closed until the FastAPI pinned internal resolution is wired (Deliverable B).
            destinations = ContactDestinationResolver { null },
        )
    } else {
        null
    }
    // Trusted reverse-proxy peers whose X-Forwarded-For we honour (comma/space
    // separated). Empty by default → XFF ignored, per-IP quota keys on the socket.
    val trustedProxies = System.getenv("MAMA_TRUSTED_PROXIES").orEmpty()
        .split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    return ServerDeps(
        service,
        Readiness(grant = verifier.configured, db = db != null, sms = sms != null),
        trustedProxies,
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
        readinessRoutes(deps.readiness)
    }
}
