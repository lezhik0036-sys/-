package app.mama.backend.trustedexit

import app.mama.backend.auth.Grant
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * The real [ContactDestinationResolver]: resolves the SMS destination from FastAPI's
 * authenticated internal endpoint `/internal/trusted-exit/destination`, NEVER from the
 * client. The grant carries no phone number, so Ktor asks FastAPI (the server of record)
 * where to send the OTP.
 *
 * Request authentication mirrors the evidence channel: the transport envelope MAC over
 * POST / path / 30s timestamp / UUIDv4 nonce / sha256(body), signed with the shared
 * internal transport key ([KtorInternalRequestSigner]). The body carries the RAW
 * FastAPI-signed sms-request grant; FastAPI re-verifies it with its own pinned key and
 * returns the server-owned verified number only when the live-lock / verified-contact /
 * non-revoked-device checks pass. Any non-200, timeout, malformed body or non-E.164 value
 * yields null -> the caller fails closed and NO SMS is sent. The grant and the resolved
 * number are never logged.
 */
class FastApiDestinationResolver(
    baseUrl: String,
    private val signer: KtorInternalRequestSigner,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ContactDestinationResolver {

    private val base = baseUrl.trimEnd('/')
    private val url = URI.create(base + KtorInternalRequestSigner.PATH_DESTINATION)
    private val e164 = Regex("^\\+[1-9][0-9]{7,14}$")

    val configured: Boolean get() = signer.configured && base.isNotBlank()

    override fun resolve(grant: Grant, bearerToken: String): String? {
        if (!configured || bearerToken.isBlank()) return null
        return runCatching {
            // Compact JSON {"grant":"<token>"}; sign the EXACT bytes we send.
            val body = ("{\"grant\":" + gsonString(bearerToken) + "}").toByteArray(StandardCharsets.UTF_8)
            val timestamp = nowMs() / 1000L
            val proof = signer.sign(body, KtorInternalRequestSigner.PATH_DESTINATION, timestamp) ?: return null
            val request = HttpRequest.newBuilder()
                .uri(url)
                .timeout(Duration.ofSeconds(8))
                .header("content-type", "application/json")
                .header("x-mama-timestamp", proof.timestamp.toString())
                .header("x-mama-nonce", proof.nonce)
                .header("x-mama-mac", proof.macHex)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() != 200) return null
            val phone = JsonParser.parseString(response.body()).asJsonObject.get("phone")?.asString
            if (phone != null && e164.matches(phone)) phone else null
        }.getOrNull()
    }

    private companion object {
        /** Minimal JSON string escaping for the single grant value (ASCII JWT). */
        fun gsonString(value: String): String {
            val sb = StringBuilder("\"")
            for (c in value) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            return sb.append("\"").toString()
        }
    }
}
