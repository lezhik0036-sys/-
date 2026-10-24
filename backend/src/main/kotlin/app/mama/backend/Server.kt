package app.mama.backend

import app.mama.backend.api.ErrorResponse
import app.mama.backend.api.readinessRoutes
import app.mama.backend.api.trustedExitRoutes
import app.mama.backend.trustedexit.ChallengeEngine
import app.mama.backend.trustedexit.ENV_SMSRU_API_ID
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
 *   MAMA_SMSRU_API_ID=... ./gradlew :backend:run          (real SMS.RU)
 *
 * The provider is chosen fail-closed ([resolveSmsProvider]): a missing
 * `MAMA_SMSRU_API_ID` is fatal — the process logs a clear configuration error
 * and exits without starting, so Trusted Exit never runs on a mock. The key is
 * read only from the environment and never logged.
 */
fun main() {
    val log = LoggerFactory.getLogger("app.mama.backend")
    val sms = try {
        resolveSmsProvider(System.getenv(ENV_SMSRU_API_ID))
    } catch (e: SmsConfigException) {
        log.error(e.message)
        // EX_CONFIG: fail fast on a misconfigured server.
        kotlin.system.exitProcess(78)
    }
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    log.info("MAMA Trusted Exit backend starting on port $port (SMS provider ${sms.label}, configured=${sms.configured()})")
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
        readinessRoutes(sms)
    }
}
