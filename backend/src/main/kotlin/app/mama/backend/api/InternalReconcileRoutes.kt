package app.mama.backend.api

import app.mama.backend.trustedexit.DispatchStatusLookup
import app.mama.backend.trustedexit.KtorInternalRequestSigner
import com.google.gson.JsonParser
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post

/** The dispatch status returned to the authenticated FastAPI peer. Carries no code, no receipt. */
data class DispatchStatusResponse(
    val challengeId: String,
    val status: String,
    val userId: String,
    val seriesId: String,
    val periodId: String,
    val deviceId: String,
    val contactId: String,
)

/**
 * INTERNAL, read-only dispatch-status reconciliation (Ktor side of mama-fastapi
 * #47): `POST /internal/trusted-exit/evidence-status`.
 *
 * NOT a user-session route. It is authenticated by the shared internal transport
 * MAC ([KtorInternalRequestSigner.verify]) — the same envelope Ktor uses to push
 * evidence, in reverse — so only the FastAPI peer can call it. Given the stable
 * server-owned grant `jti`, it returns the original `provider_challenge_id`
 * (challengeId) and the challenge's bindings from the durable store, WITHOUT
 * creating, sending, mutating or consuming anything. Because it is a pure read,
 * a replayed request is harmless and no one-time nonce ledger is needed.
 *
 * Fail-closed: 503 when no transport key / store is configured, 401 on a
 * missing/stale/mismatched MAC, 400 without a jti, 404 for an unknown jti.
 * A real deployment MUST additionally pin mTLS at the gateway.
 */
fun Routing.internalReconcileRoutes(
    signer: KtorInternalRequestSigner?,
    lookup: DispatchStatusLookup?,
    nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    post(KtorInternalRequestSigner.PATH_EVIDENCE_STATUS) {
        if (signer == null || !signer.configured || lookup == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "not configured"))
            return@post
        }
        val body = call.receiveStream().readBytes()
        if (body.size > KtorInternalRequestSigner.MAX_BODY_BYTES) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthenticated"))
            return@post
        }
        val timestamp = call.request.headers["x-mama-timestamp"]?.toLongOrNull()
        val nonce = call.request.headers["x-mama-nonce"]
        val mac = call.request.headers["x-mama-mac"]
        if (timestamp == null || !signer.verify(
                mac, body, KtorInternalRequestSigner.PATH_EVIDENCE_STATUS,
                timestamp, nonce, nowEpochSeconds(),
            )
        ) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthenticated"))
            return@post
        }
        val jti = runCatching {
            JsonParser.parseString(String(body, Charsets.UTF_8)).asJsonObject.get("jti")?.asString
        }.getOrNull()
        if (jti.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "jti required"))
            return@post
        }
        val status = lookup.byJti(jti)
        if (status == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown"))
            return@post
        }
        call.respond(
            DispatchStatusResponse(
                challengeId = status.challengeId, status = status.status,
                userId = status.userId, seriesId = status.seriesId, periodId = status.periodId,
                deviceId = status.deviceId, contactId = status.contactId,
            ),
        )
    }
}
