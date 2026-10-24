package app.mama.backend.api

import app.mama.backend.trustedexit.ChallengeEngine
import app.mama.backend.trustedexit.CreateRequest
import app.mama.backend.trustedexit.CreateResult
import app.mama.backend.trustedexit.SmsProvider
import app.mama.backend.trustedexit.VerifyOutcome
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * The Trusted Exit HTTP API. Every engine outcome is returned as 200 with a
 * `status` field so the client parses one uniform shape; only a malformed body
 * is a 400. No response carries the code.
 */
fun Routing.trustedExitRoutes(engine: ChallengeEngine) {
    get("/health") {
        call.respond(HealthResponse("ok"))
    }

    post("/trusted-exit/request") {
        val body = call.receiveNullable<CreateChallengeBody>()
        val sessionId = body?.sessionId
        val kind = body?.kind
        val idempotencyKey = body?.idempotencyKey
        val contactPhone = body?.contactPhone
        if (sessionId.isNullOrBlank() || kind.isNullOrBlank() || idempotencyKey.isNullOrBlank() || contactPhone.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("sessionId, kind, idempotencyKey and contactPhone are required"))
            return@post
        }
        val result = engine.create(CreateRequest(sessionId, kind, idempotencyKey, contactPhone))
        call.respond(result.toResponse())
    }

    post("/trusted-exit/verify") {
        val body = call.receiveNullable<VerifyBody>()
        val challengeId = body?.challengeId
        val code = body?.code
        if (challengeId.isNullOrBlank() || code == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("challengeId and code are required"))
            return@post
        }
        call.respond(engine.verify(challengeId, code).toResponse())
    }
}

/**
 * Readiness: reports whether an SMS provider is configured, so a deploy can be
 * verified before the first real test. It returns only a safe provider label,
 * never the api_id. 200 when ready, 503 when not.
 */
fun Routing.readinessRoutes(sms: SmsProvider) {
    get("/ready") {
        if (sms.configured()) {
            call.respond(ReadinessResponse("ready", sms.label))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, ReadinessResponse("not_ready", sms.label))
        }
    }
}

private fun CreateResult.toResponse(): ChallengeResponse = when (this) {
    is CreateResult.Created -> ChallengeResponse(
        status = "CREATED",
        challengeId = challenge.challengeId,
        kind = challenge.kind,
        issuedAtMs = challenge.issuedAtMs,
        expiresAtMs = challenge.expiresAtMs,
        attemptsLeft = challenge.attemptsLeft,
        delivery = delivery.name,
    )
    is CreateResult.Rejected -> ChallengeResponse(status = "REJECTED", reason = reason.name, retryAtMs = retryAtMs)
    is CreateResult.Failed -> ChallengeResponse(status = "FAILED", reason = reason)
}

private fun VerifyOutcome.toResponse(): VerifyResponse = when (this) {
    VerifyOutcome.Valid -> VerifyResponse(status = "VALID")
    is VerifyOutcome.Invalid -> VerifyResponse(status = "INVALID", attemptsLeft = attemptsLeft)
    VerifyOutcome.Expired -> VerifyResponse(status = "EXPIRED")
    is VerifyOutcome.Locked -> VerifyResponse(status = "LOCKED", retryAtMs = retryAtMs)
    VerifyOutcome.Unknown -> VerifyResponse(status = "UNKNOWN")
}
