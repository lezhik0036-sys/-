package app.mama.backend.trustedexit

import app.mama.backend.auth.Grant
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The confirmed-number resolver signs the transport envelope correctly and resolves the
 * number from FastAPI's reply, failing CLOSED on any non-200 / malformed / non-E.164
 * response and when unconfigured. A local HTTP server stands in for FastAPI; no real
 * network, no SMS.
 */
class FastApiDestinationResolverTest {
    private val key = "internal-transport-key-resolver-test-0001".toByteArray(StandardCharsets.UTF_8)
    private val grant = Grant("u", "s", "p", "d", "c", "j", 0, 0, 0)

    private fun expectedMac(body: ByteArray, ts: Long, nonce: String): String {
        val wire = KtorInternalRequestSigner.canonicalBytes(
            body, KtorInternalRequestSigner.PATH_DESTINATION, ts, nonce,
        )!!
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(wire)
        return mac.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Runs [body] with a local server whose handler returns [status]/[response]; asserts the
     *  request carried a valid transport MAC over its exact bytes and the right path. */
    private fun withServer(status: Int, response: String, body: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val failure = AtomicReference<String?>(null)
        server.createContext("/internal/trusted-exit/destination") { ex ->
            try {
                assertEquals("POST", ex.requestMethod)
                val raw = ex.requestBody.readBytes()
                val ts = ex.requestHeaders.getFirst("x-mama-timestamp").toLong()
                val nonce = ex.requestHeaders.getFirst("x-mama-nonce")
                val mac = ex.requestHeaders.getFirst("x-mama-mac")
                assertEquals(expectedMac(raw, ts, nonce), mac, "transport MAC mismatch")
                assertTrue(String(raw, StandardCharsets.UTF_8).contains("\"grant\""), "grant missing from body")
            } catch (t: Throwable) {
                failure.set(t.message ?: t.toString())
            }
            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            body("http://127.0.0.1:${server.address.port}")
            assertNull(failure.get(), "server-side assertion failed: ${failure.get()}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun resolvesVerifiedNumberOnOk() {
        withServer(200, "{\"phone\":\"+70000000000\"}") { url ->
            val r = FastApiDestinationResolver(url, KtorInternalRequestSigner(key))
            assertTrue(r.configured)
            assertEquals("+70000000000", r.resolve(grant, "hdr.payload.sig"))
        }
    }

    @Test
    fun failsClosedOn404() {
        withServer(404, "{\"detail\":\"no eligible destination\"}") { url ->
            assertNull(FastApiDestinationResolver(url, KtorInternalRequestSigner(key)).resolve(grant, "a.b.c"))
        }
    }

    @Test
    fun failsClosedOnNonE164() {
        withServer(200, "{\"phone\":\"not-a-number\"}") { url ->
            assertNull(FastApiDestinationResolver(url, KtorInternalRequestSigner(key)).resolve(grant, "a.b.c"))
        }
    }

    @Test
    fun unconfiguredResolvesNull() {
        // No URL -> not configured -> null without any network call.
        val r = FastApiDestinationResolver("", KtorInternalRequestSigner(key))
        assertTrue(!r.configured)
        assertNull(r.resolve(grant, "a.b.c"))
        // Short transport key -> signer not configured -> null.
        val r2 = FastApiDestinationResolver("http://127.0.0.1:1", KtorInternalRequestSigner("short".toByteArray()))
        assertTrue(!r2.configured)
        assertNull(r2.resolve(grant, "a.b.c"))
    }
}
