package app.mama.backend.api

import app.mama.backend.trustedexit.RequestOutcome
import app.mama.backend.trustedexit.SecureTrustedExitService
import app.mama.backend.trustedexit.VerifyOutcome
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * The grant-gated Trusted Exit API. Both endpoints require a valid FastAPI grant
 * in `Authorization: Bearer`; identity/bindings come only from that grant. When
 * the service is not configured (missing grant key / DB / SMS), the endpoints
 * fail closed with 503. `/health` is liveness; `/ready` is strict.
 */
fun Routing.trustedExitRoutes(service: SecureTrustedExitService?) {
    post("/trusted-exit/request") {
        if (service == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("not configured"))
            return@post
        }
        val bearer = call.request.headers["Authorization"]
        val ip = clientIp()
        when (val r = service.request(bearer, ip)) {
            RequestOutcome.Unauthorized -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            RequestOutcome.RateLimited -> call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limited"))
            RequestOutcome.DestinationUnavailable ->
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("destination unavailable"))
            is RequestOutcome.Rejected ->
                call.respond(ChallengeResponse("REJECTED", reason = r.reason, retryAtMs = r.retryAtMs))
            is RequestOutcome.Failed ->
                call.respond(ChallengeResponse("FAILED", reason = r.reason))
            is RequestOutcome.Created -> call.respond(
                ChallengeResponse(
                    status = "CREATED",
                    challengeId = r.challengeId,
                    issuedAtMs = r.issuedAtMs,
                    expiresAtMs = r.expiresAtMs,
                    attemptsLeft = r.attemptsLeft,
                    delivery = r.delivery,
                ),
            )
        }
    }

    post("/trusted-exit/verify") {
        if (service == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("not configured"))
            return@post
        }
        val body = call.receiveNullable<VerifyBody>()
        val challengeId = body?.challengeId
        val code = body?.code
        if (challengeId.isNullOrBlank() || code == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("challengeId and code are required"))
            return@post
        }
        val bearer = call.request.headers["Authorization"]
        when (val r = service.verify(bearer, challengeId, code)) {
            VerifyOutcome.Unauthorized -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            VerifyOutcome.Forbidden -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            VerifyOutcome.Valid -> call.respond(VerifyResponse("VALID"))
            is VerifyOutcome.Invalid -> call.respond(VerifyResponse("INVALID", attemptsLeft = r.attemptsLeft))
            VerifyOutcome.Expired -> call.respond(VerifyResponse("EXPIRED"))
            is VerifyOutcome.Locked -> call.respond(VerifyResponse("LOCKED", retryAtMs = r.retryAtMs))
            VerifyOutcome.Unknown -> call.respond(VerifyResponse("UNKNOWN"))
        }
    }
}

/**
 * Liveness (`/health`, always ok) and strict readiness (`/ready`, 200 only when
 * grant key + DB + SMS are all configured; 503 otherwise). Never leaks secrets.
 */
fun Routing.readinessRoutes(readiness: Readiness) {
    get("/health") { call.respond(HealthResponse("ok")) }
    get("/ready") {
        val body = ReadinessResponse(
            status = if (readiness.ready) "ready" else "not_ready",
            ready = readiness.ready, grant = readiness.grant, db = readiness.db, sms = readiness.sms,
        )
        if (readiness.ready) call.respond(body) else call.respond(HttpStatusCode.ServiceUnavailable, body)
    }
}

/** Client IP for rate limiting: X-Forwarded-For (behind the TLS proxy) then the socket. */
private fun io.ktor.server.application.ApplicationCall.clientIp(): String =
    request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.origin.remoteHost
