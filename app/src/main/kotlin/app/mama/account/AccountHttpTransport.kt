package app.mama.account

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP reply: the status code and the raw response body (never the TLS/transport). */
data class HttpReply(val status: Int, val body: String)

/**
 * POSTs JSON to the MAMA backend over HTTPS. A reply with ANY status (including
 * 4xx/5xx) is returned; only a genuine transport failure (no reply at all) throws
 * [IOException], which the client maps to an unavailable/fail-closed outcome —
 * never a success. Implementations MUST refuse cleartext.
 */
interface AccountHttpTransport {
    @Throws(IOException::class)
    fun postJson(path: String, jsonBody: String, bearer: String?): HttpReply
}

/**
 * Production transport over [HttpURLConnection]. HTTPS only (a cleartext base URL
 * is refused), bounded timeouts, no redirect following. No new dependency; works
 * on every supported API level. Not exercised by unit tests (needs a real peer);
 * the client's logic is tested against a fake transport.
 */
class UrlConnectionAccountTransport(
    baseUrl: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : AccountHttpTransport {

    private val base = baseUrl.trimEnd('/')

    @Throws(IOException::class)
    override fun postJson(path: String, jsonBody: String, bearer: String?): HttpReply {
        val url = URL(base + path)
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw IOException("MAMA backend must be HTTPS")
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("accept", "application/json")
            bearer?.let { setRequestProperty("authorization", "Bearer $it") }
        }
        try {
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            // A peer/proxy error response can be arbitrarily large. Do not read
            // unbounded data into the Android process before parsing JSON.
            val body = stream?.use { readBoundedBody(it) }.orEmpty()
            return HttpReply(status, body)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Read at most [MAX_ACCOUNT_RESPONSE_BYTES] + 1 bytes from an untrusted server.
 * A large response is a transport failure, never a usable account session.
 * Avoid InputStream.readNBytes (not available on all supported Android APIs).
 */
internal const val MAX_ACCOUNT_RESPONSE_BYTES = 64 * 1024

@Throws(IOException::class)
internal fun readBoundedBody(stream: InputStream): String {
    val bytes = ByteArrayOutputStream()
    val chunk = ByteArray(4096)
    var remaining = MAX_ACCOUNT_RESPONSE_BYTES + 1
    while (remaining > 0) {
        val read = stream.read(chunk, 0, minOf(chunk.size, remaining))
        if (read == -1) break
        if (read == 0) {
            // InputStream may return zero; still make progress or fail closed.
            val single = stream.read()
            if (single == -1) break
            bytes.write(single)
            remaining--
        } else {
            bytes.write(chunk, 0, read)
            remaining -= read
        }
    }
    if (bytes.size() > MAX_ACCOUNT_RESPONSE_BYTES) {
        throw IOException("MAMA account response exceeds size limit")
    }
    return bytes.toByteArray().toString(Charsets.UTF_8)
}
