package app.mama.account

import java.io.File
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FamilyLiveHttpTest {
    @Test
    fun pairsIndependentDeviceAccountsThroughRealFastApi() {
        val directory = System.getenv("MAMA_FAMILY_E2E_DIR")
        assumeTrue("Run in FastAPI's cross-repository Family job", directory != null)
        val fixture = File(requireNotNull(directory))
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            File(fixture, "tls.crt").inputStream().use {
                setCertificateEntry("test-api", CertificateFactory.getInstance("X.509").generateCertificate(it))
            }
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        managers.init(trustStore)
        val tls = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        val previous = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
        try {
            val https = UrlConnectionAccountTransport("https://127.0.0.1:18443")
            var loseReply = false
            val parentTransport = object : AccountHttpTransport by https {
                override fun postJson(path: String, jsonBody: String, bearer: String?): HttpReply {
                    val response = https.postJson(path, jsonBody, bearer)
                    if (path == "/v1/family/links" && loseReply && response.status == 200) {
                        loseReply = false
                        throw IOException("simulated response loss after server commit")
                    }
                    return response
                }
            }
            val parent = client(parentTransport)
            val child = client(https)
            val stranger = client(https)
            val parentId = parent.identity()
            val childId = child.identity()
            val invite = child.invite()
            denied(503) { parent.link(invite.code) }

            requestFixture(fixture, JSONObject()
                .put("op", "approve")
                .put("parent_user_id", parentId.userId)
                .put("child_user_id", childId.userId)
                .put("child_device_id", childId.deviceId))
            denied(404) { stranger.link(invite.code) }
            denied(404) { parent.link("z".repeat(43)) }
            denied(403) { child.invite() }

            requestFixture(fixture, JSONObject().put("op", "expire").put("code", invite.code))
            denied(404) { parent.link(invite.code) }
            val next = child.invite()

            loseReply = true
            assertThrows(IOException::class.java) { parent.link(next.code) }
            assertEquals(listOf(childId.deviceId), parent.devices())
            denied(404) { parent.link(next.code) }
            child.revokeOwnParent()
            assertEquals(emptyList<String>(), parent.devices())

            assertEquals(childId.deviceId, parent.link(child.invite().code))
            parent.revoke(childId.deviceId)
            assertEquals(emptyList<String>(), parent.devices())
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previous)
        }
    }

    private fun denied(status: Int, call: () -> Unit) {
        assertEquals(status, assertThrows(FamilyClient.Denied::class.java) { call() }.status)
    }

    private fun requestFixture(directory: File, request: JSONObject) {
        val pending = File(directory, "request")
        val temporary = File(directory, "request.tmp")
        val result = File(directory, "result")
        result.delete()
        temporary.writeText(request.toString())
        check(temporary.renameTo(pending))
        repeat(100) {
            if (result.isFile) {
                check(result.readText() == "ok") { "Fixture operation failed" }
                return
            }
            Thread.sleep(100)
        }
        error("Fixture operation timed out")
    }

    private fun client(transport: AccountHttpTransport): FamilyClient {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val key = object : DeviceKey {
            override fun publicKeyB64(): String =
                Base64.getEncoder().encodeToString(pair.public.encoded.takeLast(32).toByteArray())

            override fun sign(message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
                initSign(pair.private)
                update(message)
                sign()
            }
        }
        val store = object : AccountSessionStore {
            private var session: AccountSession? = null
            override fun load() = session
            override fun save(session: AccountSession) { this.session = session }
            override fun clear() { session = null }
        }
        return FamilyClient(MamaAccountClient(transport, key, store), key, transport)
    }
}
