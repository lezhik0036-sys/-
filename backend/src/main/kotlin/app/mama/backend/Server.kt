package app.mama.backend

import app.mama.backend.api.ErrorResponse
import app.mama.backend.api.trustedExitRoutes
import app.mama.backend.trustedexit.ChallengeEngine
import app.mama.backend.trustedexit.InMemoryChallengeStore
import app.mama.backend.trustedexit.SmsProvider
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

/**
 * The MAMA Trusted Exit backend process. Run separately from the APK:
 *   MAMA_ENV=test PORT=8080 ./gradlew :backend:run        (local, mock SMS)
 *   MAMA_SMSRU_API_KEY=... ./gradlew :backend:run         (production, real SMS)
 *
 * The SMS provider is chosen by a hard TEST/PRODUCTION split ([resolveSmsProvider]).
 * In production a missing `MAMA_SMSRU_API_KEY` is fatal: the process logs a clear
 * configuration error and exits without starting, so Trusted Exit never runs
 * on a mock in production. The key is read only from the environment.
 */
fun main() {
    val log = LoggerFactory.getLogger("app.mama.backend")
    val sms = try {
        resolveSmsProvider(BackendConfig.mode(), System.getenv(BackendConfig.ENV_API_KEY))
    } catch (e: SmsConfigException) {
        log.error(e.message)
        // EX_CONFIG: fail fast on a misconfigured production server.
        kotlin.system.exitProcess(78)
    }
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    log.info("MAMA Trusted Exit backend starting on port $port (mode ${BackendConfig.mode()}, provider ${sms::class.simpleName})")
    embeddedServer(Netty, port = port) { module(sms) }.start(wait = true)
}

/** The Ktor application, shared by [main] and the route tests (which pass the mock). */
fun Application.module(sms: SmsProvider) {
    val engine = ChallengeEngine(store = InMemoryChallengeStore(), sms = sms)

    install(ContentNegotiation) { gson { } }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // Never leak internals (or anything code-shaped) to the client.
            LoggerFactory.getLogger("app.mama.backend").error("request failed", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error"))
        }
    }
    routing {
        trustedExitRoutes(engine)
    }
}
