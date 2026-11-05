package app.mama.backend.trustedexit

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the REAL JDK HTTP transport against a loopback [HttpServer] (no VPS, no
 * TLS, no SMS). Proves the Ktor→FastAPI evidence push emits exactly the bytes and
 * headers FastAPI's `/internal/trusted-exit/evidence` endpoint authenticates, and
 * maps the FastAPI status codes to the right [DeliveryOutcome].
 */
class JdkPrivateEvidenceHttpTransportTest {
    private val transportKey = "synthetic-private-transport-secret-32bytes-001"
    private val ingestKey = "synthetic-ingest-secret-is-not-transport-secret-0001"
    private val receipt = "hdr.payload.sig"
    private val fixedTime = 1793880000L
    private val fixedNonce = "123e4567-e89b-42d3-a456-426614174000"

    private var server: HttpServer? = null

    @AfterTest fun stop() {
        server?.stop(0)
        server = null
    }

    private class Captured(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    /** Start a loopback server on [path] that captures one request and replies [status]. */
    private fun start(status: Int, path: String = KtorInternalRequestSigner.PATH_EVIDENCE): Pair<String, AtomicReference<Captured?>> {
        val captured = AtomicReference<Captured?>(null)
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext(path) { exchange ->
            val body = exchange.requestBody.readBytes()
            val headers = exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.first() }
            captured.set(Captured(exchange.requestMethod, exchange.requestURI.path, headers, body))
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        srv.start()
        server = srv
        return "http://127.0.0.1:${srv.address.port}" to captured
    }

    private fun deliver(baseUrl: String?): DeliveryOutcome {
        val adapter = AuthenticatedEvidenceDelivery(
            KtorInternalRequestSigner(transportKey.toByteArray()),
            JdkPrivateEvidenceHttpTransport(baseUrl),
            timestamp = { fixedTime }, nonce = { fixedNonce },
        )
        return EvidenceSubmitter(IngestProof.fromEnv(ingestKey), adapter).submit(receipt)
    }

    @Test fun `posts the signed envelope FastAPI authenticates and maps 204 to Accepted`() {
        val (baseUrl, captured) = start(204)
        val outcome = deliver(baseUrl)
        assertEquals(DeliveryOutcome.Accepted, outcome)

        val req = captured.get()!!
        assertEquals("POST", req.method)
        assertEquals(KtorInternalRequestSigner.PATH_EVIDENCE, req.path)
        assertEquals(fixedTime.toString(), req.headers["x-mama-timestamp"])
        assertEquals(fixedNonce, req.headers["x-mama-nonce"])
        // The MAC header must be exactly the transport MAC over the bytes actually sent.
        val expectedMac = KtorInternalRequestSigner(transportKey.toByteArray())
            .sign(req.body, KtorInternalRequestSigner.PATH_EVIDENCE, fixedTime, fixedNonce)!!.macHex
        assertEquals(expectedMac, req.headers["x-mama-mac"])
        // Body is exactly {"token","macB64"} — FastAPI's expected schema.
        val json = JsonParser.parseString(String(req.body, Charsets.UTF_8)).asJsonObject
        assertEquals(setOf("token", "macB64"), json.keySet())
        assertEquals(receipt, json.get("token").asString)
        val mac = Base64.getDecoder().decode(json.get("macB64").asString)
        assertTrue(mac.contentEquals(IngestProof.fromEnv(ingestKey).mac(receipt)))
    }

    @Test fun `maps 409 to Rejected (duplicate or refusal, never silently dropped)`() {
        val (baseUrl, _) = start(409)
        assertTrue(deliver(baseUrl) is DeliveryOutcome.Rejected)
    }

    @Test fun `maps 503 peer-not-configured to Rejected`() {
        val (baseUrl, _) = start(503)
        assertTrue(deliver(baseUrl) is DeliveryOutcome.Rejected)
    }

    @Test fun `a refused connection is an unresolved lost ACK, not success`() {
        // Port 1 on loopback refuses immediately: the ACK is lost / unknown.
        val outcome = deliver("http://127.0.0.1:1")
        assertTrue(outcome is DeliveryOutcome.Unknown)
    }

    @Test fun `a blank base url is disabled (fail-closed), sends nothing`() {
        assertEquals(DeliveryOutcome.Disabled, deliver(""))
        assertFalse(JdkPrivateEvidenceHttpTransport(null).configured)
        assertFalse(JdkPrivateEvidenceHttpTransport("  ").configured)
        assertTrue(JdkPrivateEvidenceHttpTransport("https://fastapi.internal").configured)
    }
}
