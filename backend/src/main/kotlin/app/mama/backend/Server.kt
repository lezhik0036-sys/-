package app.mama.backend

import app.mama.backend.api.ErrorResponse
import app.mama.backend.api.trustedExitRoutes
import app.mama.backend.trustedexit.ChallengeEngine
import app.mama.backend.trustedexit.InMemoryChallengeStore
import app.mama.backend.trustedexit.MockSmsProvider
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
 *   PORT=8080 ./gradlew :backend:run
 *
 * Phase 1 uses the mock SMS provider (no key, no real delivery) so the full
 * flow and tests run end to end. Phase 2 selects the real SMS.RU provider when
 * its API key is present in the environment — the key never reaches Git or the
 * client.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) { module() }.start(wait = true)
}

/** The Ktor application, shared by [main] and the route tests. */
fun Application.module(sms: SmsProvider = selectSmsProvider()) {
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

/**
 * Chooses the SMS provider from the environment. Phase 1: always the mock.
 * Phase 2 adds the SMS.RU provider here, selected when `MAMA_SMSRU_API_KEY` is
 * set — read from the environment only, never from Git or the client.
 */
private fun selectSmsProvider(): SmsProvider = MockSmsProvider()
