package app.mama.backend

import app.mama.backend.api.Readiness
import app.mama.backend.trustedexit.DispatchStatus
import app.mama.backend.trustedexit.DispatchStatusLookup
import app.mama.backend.trustedexit.KtorInternalRequestSigner
import com.google.gson.JsonParser
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the real internal dispatch-status reconciliation route over the Ktor
 * test host. No DB, no SMS, no network: the lookup is stubbed and the transport
 * key is in-process. Proves transport-MAC auth, the read-only jti → challengeId
 * recovery, and fail-closed behavior.
 */
class DispatchReconcileTest {
    private val transportKey = "internal-transport-key-contract-only-000001".toByteArray()
    private val signer = KtorInternalRequestSigner(transportKey)
    private val knownJti = "11111111-1111-4111-8111-111111111111"
    private val nonce = "123e4567-e89b-42d3-a456-426614174000"

    private val lookup = DispatchStatusLookup { jti ->
        if (jti == knownJti) DispatchStatus(
            challengeId = "tec-abc", status = "SENT",
            userId = "u", seriesId = "s", periodId = "p", deviceId = "d", contactId = "c",
        ) else null
    }

    private fun deps(withReconcile: Boolean, overrideLookup: DispatchStatusLookup? = null) = ServerDeps(
        service = null,
        readiness = Readiness(grant = false, db = false, sms = false),
        reconcileSigner = if (withReconcile) signer else null,
        reconcileLookup = if (withReconcile) overrideLookup ?: lookup else null,
    )

    private fun body(jti: String) = """{"jti":"$jti"}""".toByteArray(Charsets.UTF_8)

    private fun macFor(bodyBytes: ByteArray, ts: Long) =
        signer.sign(bodyBytes, KtorInternalRequestSigner.PATH_EVIDENCE_STATUS, ts, nonce)!!.macHex

    @Test fun `valid transport MAC recovers the challenge by jti, read-only`() = testApplication {
        application { module(deps(withReconcile = true)) }
        val b = body(knownJti)
        val ts = System.currentTimeMillis() / 1000
        val response = client.post(KtorInternalRequestSigner.PATH_EVIDENCE_STATUS) {
            header("x-mama-timestamp", ts.toString())
            header("x-mama-nonce", nonce)
            header("x-mama-mac", macFor(b, ts))
            setBody(b)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val json = JsonParser.parseString(response.bodyAsText()).asJsonObject
        assertEquals("tec-abc", json.get("challengeId").asString)
        assertEquals("SENT", json.get("status").asString)
        assertEquals("s", json.get("seriesId").asString)
    }

    @Test fun `a bad transport MAC is refused (401)`() = testApplication {
        application { module(deps(withReconcile = true)) }
        val b = body(knownJti)
        val ts = System.currentTimeMillis() / 1000
        val response = client.post(KtorInternalRequestSigner.PATH_EVIDENCE_STATUS) {
            header("x-mama-timestamp", ts.toString())
            header("x-mama-nonce", nonce)
            header("x-mama-mac", "0".repeat(64))
            setBody(b)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test fun `an unknown jti is 404 (authenticated but nothing to recover)`() = testApplication {
        application { module(deps(withReconcile = true)) }
        val b = body("22222222-2222-4222-8222-222222222222")
        val ts = System.currentTimeMillis() / 1000
        val response = client.post(KtorInternalRequestSigner.PATH_EVIDENCE_STATUS) {
            header("x-mama-timestamp", ts.toString())
            header("x-mama-nonce", nonce)
            header("x-mama-mac", macFor(b, ts))
            setBody(b)
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test fun `unconfigured reconciliation is fail-closed (503)`() = testApplication {
        application { module(deps(withReconcile = false)) }
        val b = body(knownJti)
        val ts = System.currentTimeMillis() / 1000
        val response = client.post(KtorInternalRequestSigner.PATH_EVIDENCE_STATUS) {
            header("x-mama-timestamp", ts.toString())
            header("x-mama-nonce", nonce)
            header("x-mama-mac", macFor(b, ts))
            setBody(b)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test fun `oversized body is refused before parsing or status lookup`() = testApplication {
        var lookups = 0
        val counting = DispatchStatusLookup { _ ->
            lookups++
            null
        }
        application {
            module(deps(withReconcile = true, overrideLookup = counting))
        }
        val b = ByteArray(KtorInternalRequestSigner.MAX_BODY_BYTES + 200_000) { 0x41 }
        val ts = System.currentTimeMillis() / 1000
        val response = client.post(KtorInternalRequestSigner.PATH_EVIDENCE_STATUS) {
            header("x-mama-timestamp", ts.toString())
            header("x-mama-nonce", nonce)
            header("x-mama-mac", "0".repeat(64))
            setBody(b)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, lookups)
    }

    @Test fun `a stale timestamp is refused (401)`() = testApplication {
        application { module(deps(withReconcile = true)) }
        val b = body(knownJti)
        val ts = System.currentTimeMillis() / 1000 - 120
        val response = client.post(KtorInternalRequestSigner.PATH_EVIDENCE_STATUS) {
            header("x-mama-timestamp", ts.toString())
            header("x-mama-nonce", nonce)
            header("x-mama-mac", macFor(b, ts))
            setBody(b)
        }
        assertTrue(response.status == HttpStatusCode.Unauthorized)
    }
}
