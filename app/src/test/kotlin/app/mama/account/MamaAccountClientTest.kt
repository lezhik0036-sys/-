package app.mama.account

import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-side proof of the device-key account client's orchestration against a fake
 * backend that verifies Ed25519 signatures exactly as FastAPI does. No device, no
 * Keystore, no network: the crypto is an ephemeral JVM Ed25519 key behind the same
 * [DeviceKey] seam the production Keystore key implements. Robolectric only for a
 * real org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MamaAccountClientTest {

    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    /** Ephemeral Ed25519 key standing in for the Keystore-protected device key. */
    private inner class EphemeralDeviceKey : DeviceKey {
        private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        private val priv: PrivateKey = pair.private
        private val rawPub: ByteArray =
            pair.public.encoded.let { it.copyOfRange(it.size - 32, it.size) } // strip 12-byte SPKI prefix
        override fun publicKeyB64(): String = b64.encodeToString(rawPub)
        override fun sign(message: ByteArray): ByteArray =
            Signature.getInstance("Ed25519").run { initSign(priv); update(message); sign() }
    }

    private class MemStore : AccountSessionStore {
        private var s: AccountSession? = null
        override fun load() = s
        override fun save(session: AccountSession) { s = session }
        override fun clear() { s = null }
    }

    /** In-process stand-in for /v1/account/{challenge,register,login}; verifies real signatures. */
    private inner class FakeServer : AccountHttpTransport {
        val registered = HashMap<String, Pair<String, String>>() // pub -> (userId, deviceId)
        private val challenges = HashMap<String, Triple<String, String, String>>() // cid -> (purpose, nonce, pub)
        var seq = 0
        var registerCalls = 0
        var loginCalls = 0
        var challengeCalls = 0

        override fun postJson(path: String, jsonBody: String, bearer: String?): HttpReply {
            val j = JSONObject(jsonBody)
            return when (path) {
                MamaAccountClient.PATH_CHALLENGE -> {
                    challengeCalls++
                    val cid = "cid-${++seq}"
                    val nonce = "nonce0000000000000000000000000000000000${seq}" // ascii, 32..128
                    challenges[cid] = Triple(j.getString("purpose"), nonce, j.getString("public_key_b64"))
                    HttpReply(200, JSONObject().put("challenge_id", cid).put("nonce", nonce)
                        .put("expires_at", "2099-01-01T00:00:00+00:00").toString())
                }
                MamaAccountClient.PATH_REGISTER -> complete(j, MamaAccountClient.PURPOSE_REGISTER)
                MamaAccountClient.PATH_LOGIN -> complete(j, MamaAccountClient.PURPOSE_LOGIN)
                else -> HttpReply(404, "")
            }
        }

        private fun complete(j: JSONObject, purpose: String): HttpReply {
            val cid = j.getString("challenge_id")
            val nonce = j.getString("nonce")
            val pub = j.getString("public_key_b64")
            val sig = j.getString("signature_b64")
            val ch = challenges.remove(cid) ?: return HttpReply(401, "") // one-time
            if (ch.first != purpose || ch.second != nonce || ch.third != pub) return HttpReply(401, "")
            if (!verify(pub, sig, MamaAccountClient.authMessage(purpose, cid, nonce, pub))) {
                return HttpReply(401, "")
            }
            return if (purpose == MamaAccountClient.PURPOSE_REGISTER) {
                registerCalls++
                if (registered.containsKey(pub)) HttpReply(409, "")
                else {
                    val uid = java.util.UUID.randomUUID().toString()
                    val did = java.util.UUID.randomUUID().toString()
                    registered[pub] = uid to did
                    HttpReply(200, session(uid, did))
                }
            } else {
                loginCalls++
                val r = registered[pub] ?: return HttpReply(401, "")
                HttpReply(200, session(r.first, r.second))
            }
        }

        private fun session(uid: String, did: String) = JSONObject()
            .put("user_id", uid).put("device_id", did)
            .put("session_token", "A".repeat(43)).toString()

        private fun verify(pubB64: String, sigB64: String, message: ByteArray): Boolean = runCatching {
            val spki = SPKI_PREFIX + unb64.decode(pubB64)
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
            Signature.getInstance("Ed25519").run { initVerify(key); update(message); verify(unb64.decode(sigB64)) }
        }.getOrDefault(false)
    }

    @Test fun `canonical message matches the server contract byte for byte`() {
        val expected = ("MAMA/account/device-auth/v1\n" + "login\ncid-1\nnonce-x\ncGtiNjQ=")
            .toByteArray(Charsets.US_ASCII)
        assertArrayEquals(expected, MamaAccountClient.authMessage("login", "cid-1", "nonce-x", "cGtiNjQ="))
    }

    @Test fun `no cached session registers the device then caches it`() {
        val server = FakeServer()
        val key = EphemeralDeviceKey()
        val store = MemStore()
        val client = MamaAccountClient(server, key, store)

        val r = client.ensureSession()
        assertTrue(r is MamaAccountClient.Result.Ok)
        val ok = r as MamaAccountClient.Result.Ok
        assertEquals("A".repeat(43), ok.session.token)
        assertEquals(1, server.registerCalls)
        assertEquals(1, server.loginCalls) // one 401 login probe before register
        assertTrue(server.registered.containsKey(key.publicKeyB64()))
        // Cached: a second call makes no new HTTP calls.
        val before = server.challengeCalls
        val again = client.ensureSession()
        assertTrue(again is MamaAccountClient.Result.Ok)
        assertEquals(before, server.challengeCalls)
    }

    @Test fun `an already-registered device logs in without registering`() {
        val server = FakeServer()
        val key = EphemeralDeviceKey()
        // First device registers.
        MamaAccountClient(server, key, MemStore()).ensureSession()
        server.registerCalls = 0
        // A fresh install (empty store), same key -> login path only.
        val r = MamaAccountClient(server, key, MemStore()).ensureSession()
        assertTrue(r is MamaAccountClient.Result.Ok)
        assertEquals(0, server.registerCalls)
    }

    @Test fun `a transport failure is unavailable, never a forged session`() {
        val failing = object : AccountHttpTransport {
            override fun postJson(path: String, jsonBody: String, bearer: String?): HttpReply =
                throw IOException("offline")
        }
        val store = MemStore()
        val r = MamaAccountClient(failing, EphemeralDeviceKey(), store).ensureSession()
        assertEquals(MamaAccountClient.Result.Unavailable, r)
        assertNull(store.load())
    }

    @Test fun `a 5xx is unavailable, never a forged session`() {
        val server5xx = object : AccountHttpTransport {
            override fun postJson(path: String, jsonBody: String, bearer: String?): HttpReply =
                HttpReply(503, "")
        }
        val store = MemStore()
        val r = MamaAccountClient(server5xx, EphemeralDeviceKey(), store).ensureSession()
        assertEquals(MamaAccountClient.Result.Unavailable, r)
        assertNull(store.load())
    }

    @Test fun `a register conflict falls back to login`() {
        val key = EphemeralDeviceKey()
        val server = FakeServer()
        // Pre-register the key so the client's register attempt 409s.
        MamaAccountClient(server, key, MemStore()).ensureSession()
        // Force the client down the register branch by making login 401 once, as if
        // the key were unknown, then let register 409 and re-login succeed.
        val flaky = object : AccountHttpTransport {
            var firstLogin = true
            override fun postJson(path: String, jsonBody: String, bearer: String?): HttpReply {
                if (path == MamaAccountClient.PATH_LOGIN && firstLogin) {
                    firstLogin = false
                    // Complete a real challenge but answer 401 to simulate "unknown device".
                    return HttpReply(401, "")
                }
                return server.postJson(path, jsonBody, bearer)
            }
        }
        val r = MamaAccountClient(flaky, key, MemStore()).ensureSession()
        assertTrue(r is MamaAccountClient.Result.Ok)
    }

    @Test fun `malformed HTTP 200 session is refused and never persisted`() {
        val key = EphemeralDeviceKey()
        val validServer = FakeServer()
        // Allow the full proof to finish but replace the response claiming
        // success with malformed identity or attacker-chosen bearer data.
        val responses = listOf(
            """{"user_id":"","device_id":"bad","session_token":"x"}""",
            """{"user_id":"not-uuid","device_id":"not-uuid","session_token":"Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}""",
            """{"user_id":"00000000-0000-4000-8000-000000000000","device_id":"00000000-0000-4000-8000-000000000001","session_token":""}""",
            """{"user_id":"00000000-0000-4000-8000-000000000000","device_id":"00000000-0000-4000-8000-000000000001","session_token":"invalid whitespace bearer token"}""",
            """{"user_id":null,"device_id":{},"session_token":[]}""",
        )
        for (response in responses) {
            val server = object : AccountHttpTransport {
                override fun postJson(path: String, jsonBody: String, bearer: String?): HttpReply {
                    val actual = validServer.postJson(path, jsonBody, bearer)
                    return if (path == MamaAccountClient.PATH_REGISTER ||
                        path == MamaAccountClient.PATH_LOGIN && actual.status == 200
                    ) HttpReply(200, response) else actual
                }
            }
            val store = MemStore()
            val result = MamaAccountClient(server, key, store).ensureSession()
            assertEquals(MamaAccountClient.Result.Unavailable, result)
            assertNull(store.load())
        }
    }

    @Test fun `canonical UUID and opaque bearer format must match server`() {
        assertTrue(MamaAccountClient.isCanonicalUuid("00000000-0000-4000-8000-000000000000"))
        assertTrue(!MamaAccountClient.isCanonicalUuid("00000000-0000-4000-8000-000000000000suffix"))
        assertTrue(MamaAccountClient.isBearerFormat("A".repeat(43)))
        assertTrue(!MamaAccountClient.isBearerFormat("A".repeat(257)))
        assertTrue(!MamaAccountClient.isBearerFormat("unsafe-token with spaces"))
    }

    @Test fun `forgetSession clears the cache`() {
        val store = MemStore().apply { save(AccountSession("u", "d", "t")) }
        MamaAccountClient(FakeServer(), EphemeralDeviceKey(), store).forgetSession()
        assertNull(store.load())
    }

    private companion object {
        // DER SubjectPublicKeyInfo prefix for an Ed25519 raw public key (12 bytes).
        val SPKI_PREFIX: ByteArray = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
    }
}
