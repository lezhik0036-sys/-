package app.mama.account

import java.io.IOException
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
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpReply(status, body)
        } finally {
            conn.disconnect()
        }
    }
}
