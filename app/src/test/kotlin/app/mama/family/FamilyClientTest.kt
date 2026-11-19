package app.mama.family

import app.mama.account.AccountHttpTransport
import app.mama.account.AccountSession
import app.mama.account.DeviceKey
import app.mama.account.HttpReply
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-side proof of the Family client against a fake server that verifies the
 * Ed25519 device-key proof EXACTLY as FastAPI's family_device_key_proof does. A
 * passing "invitation created" therefore proves the signed message is byte-compatible
 * with the server. Also covers fail-closed behaviour, the guardian-consent 503, the
 * revoke paths and the child-recovery recent-auth gate. Robolectric only for org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FamilyClientTest {

    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()
    private val userId = "11111111-1111-4111-8111-111111111111"
    private val deviceId = "22222222-2222-4222-8222-222222222222"

    private inner class EphemeralDeviceKey : DeviceKey {
        private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        private val priv: PrivateKey = pair.private
        val rawPub: ByteArray = pair.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }
        override fun publicKeyB64(): String = b64.encodeToString(rawPub)
        override fun sign(message: ByteArray): ByteArray =
            Signature.getInstance("Ed25519").run { initSign(priv); update(message); sign() }
    }

    private inner class FakeSession(private val token: String = "A".repeat(43)) : FamilySessionSource {
        var forgets = 0
        override fun ensureSession(): AccountSession = AccountSession(userId, deviceId, token)
        override fun forgetSession() { forgets++ }
    }

    /** In-process stand-in for the /v1/family API that verifies the real device-key proof. */
    private inner class FakeFamilyServer(private val pubB64: String) : AccountHttpTransport {
        var linkStatus = 200
        var linkBody = JSONObject().put("status", "linked")
            .put("child_device_id", "33333333-3333-4333-8333-333333333333").toString()
        var devicesBody = JSONObject().put("devices", JSONArray()).toString()
        var deleteStatus = 200
        // CHILD consent (gate 1): records the proof + named parent. 200 unless overridden.
        var consentStatus = 200
        var lastConsentParentId: String? = null
        var lastConsentToken: String? = null
        private var lastNonce: String? = null
        private var lastCid: String? = null
        var challengeCalls = 0

        override fun postJson(path: String, jsonBody: String, bearer: String?): HttpReply {
            val j = JSONObject(jsonBody)
            return when (path) {
                FamilyClient.PATH_CHALLENGES -> {
                    challengeCalls++
                    lastCid = "44444444-4444-4444-8444-00000000000${challengeCalls}"
                    lastNonce = "nonce" + "x".repeat(40) // ascii, 32..128
                    HttpReply(200, JSONObject().put("challenge_id", lastCid).put("nonce", lastNonce)
                        .put("expires_at", "2099-01-01T00:00:00+00:00").toString())
                }
                FamilyClient.PATH_INVITATIONS -> {
                    val cid = j.getString("challenge_id")
                    val nonce = j.getString("nonce")
                    val dev = j.getString("device_id")
                    val sig = j.getString("signature_b64")
                    val message = FamilyClient.proofMessage(userId, cid, dev, nonce)
                    if (cid != lastCid || nonce != lastNonce || !verify(pubB64, sig, message)) {
                        HttpReply(403, JSONObject().put("detail", "pairing not available").toString())
                    } else {
                        HttpReply(200, JSONObject().put("invitation_token", "pair-TOKEN-abc123")
                            .put("expires_at", "2099-01-01T00:00:00+00:00").toString())
                    }
                }
                FamilyClient.PATH_CONSENTS -> {
                    // Verify the device-key proof EXACTLY like the invitation path, then
                    // require a named parent + invitation token (gate 1).
                    val cid = j.getString("challenge_id")
                    val nonce = j.getString("nonce")
                    val dev = j.getString("device_id")
                    val sig = j.getString("signature_b64")
                    val message = FamilyClient.proofMessage(userId, cid, dev, nonce)
                    if (cid != lastCid || nonce != lastNonce || !verify(pubB64, sig, message)) {
                        HttpReply(403, JSONObject().put("detail", "consent not available").toString())
                    } else {
                        lastConsentParentId = j.optString("parent_user_id", null)
                        lastConsentToken = j.optString("invitation_token", null)
                        HttpReply(consentStatus, JSONObject().put("status", "consent_recorded").toString())
                    }
                }
                FamilyClient.PATH_LINKS -> HttpReply(linkStatus, linkBody)
                else -> HttpReply(404, "")
            }
        }

        override fun getJson(path: String, bearer: String?): HttpReply =
            if (path == FamilyClient.PATH_DEVICES) HttpReply(200, devicesBody) else HttpReply(404, "")

        override fun deleteJson(path: String, bearer: String?): HttpReply =
            if (path.startsWith(FamilyClient.PATH_DEVICES) || path.startsWith(FamilyClient.PATH_MY_DEVICES))
                HttpReply(deleteStatus, "") else HttpReply(404, "")

        private fun verify(pubB64: String, sigB64: String, message: ByteArray): Boolean = runCatching {
            val spki = SPKI_PREFIX + unb64.decode(pubB64)
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
            Signature.getInstance("Ed25519").run { initVerify(key); update(message); verify(unb64.decode(sigB64)) }
        }.getOrDefault(false)
    }

    @Test fun `the proof message matches the server contract byte for byte`() {
        val expected = ("MAMA/family/device-key-proof/v1\n" + "$userId\ncid-1\n$deviceId\nnonce-x")
            .toByteArray(Charsets.US_ASCII)
        assertArrayEquals(expected, FamilyClient.proofMessage(userId, "cid-1", deviceId, "nonce-x"))
    }

    @Test fun `createInvitation signs the canonical proof and returns the server token`() {
        val key = EphemeralDeviceKey()
        val client = FamilyClient(FakeFamilyServer(key.publicKeyB64()), FakeSession(), key)
        val r = client.createInvitation()
        assertTrue("got $r", r is FamilyClient.InviteResult.Created)
        assertEquals("pair-TOKEN-abc123", (r as FamilyClient.InviteResult.Created).invitationToken)
    }

    @Test fun `a wrong device key is refused by the server proof and yields Failed`() {
        val signer = EphemeralDeviceKey()
        val otherPub = EphemeralDeviceKey().publicKeyB64() // server expects a DIFFERENT key
        val client = FamilyClient(FakeFamilyServer(otherPub), FakeSession(), signer)
        assertTrue(client.createInvitation() is FamilyClient.InviteResult.Failed)
    }

    @Test fun `createInvitation is fail-closed on a transport error`() {
        val throwing = object : AccountHttpTransport {
            override fun postJson(path: String, jsonBody: String, bearer: String?): HttpReply =
                throw IOException("down")
        }
        val client = FamilyClient(throwing, FakeSession(), EphemeralDeviceKey())
        assertEquals("transport", (client.createInvitation() as FamilyClient.InviteResult.Failed).reason)
    }

    @Test fun `no session never forges an invitation`() {
        val noSession = object : FamilySessionSource {
            override fun ensureSession(): AccountSession? = null
            override fun forgetSession() {}
        }
        val client = FamilyClient(FakeFamilyServer("x"), noSession, EphemeralDeviceKey())
        assertEquals("no-session", (client.createInvitation() as FamilyClient.InviteResult.Failed).reason)
    }

    @Test fun `consumeInvitation maps linked, both 503 gates and failure`() {
        val key = EphemeralDeviceKey()
        val server = FakeFamilyServer(key.publicKeyB64())
        val client = FamilyClient(server, FakeSession(), key)

        server.linkStatus = 200
        val linked = client.consumeInvitation("pair-TOKEN-abc123")
        assertTrue(linked is FamilyClient.LinkResult.Linked)
        assertEquals("33333333-3333-4333-8333-333333333333", (linked as FamilyClient.LinkResult.Linked).childDeviceId)

        // Gate 1 (503): the server is still awaiting the child device's consent.
        server.linkStatus = 503
        server.linkBody = JSONObject().put("detail", "awaiting child-device consent").toString()
        assertTrue(
            "awaiting consent -> gate 1",
            client.consumeInvitation("pair-TOKEN-abc123") is FamilyClient.LinkResult.AwaitingChildConsent,
        )

        // Gate 2 (503): consent is in, but no operator guardian-authority grant yet.
        server.linkBody = JSONObject().put("detail", "guardian authority not granted").toString()
        assertTrue(
            "no authority -> gate 2",
            client.consumeInvitation("pair-TOKEN-abc123") is FamilyClient.LinkResult.AwaitingGuardianAuthority,
        )

        server.linkStatus = 404
        assertTrue(client.consumeInvitation("pair-TOKEN-abc123") is FamilyClient.LinkResult.Failed)
    }

    @Test fun `recordConsent signs a fresh proof and sends the named parent + token`() {
        val key = EphemeralDeviceKey()
        val server = FakeFamilyServer(key.publicKeyB64())
        val client = FamilyClient(server, FakeSession(), key)
        val parent = "88888888-8888-4888-8888-888888888888"

        val r = client.recordConsent("pair-TOKEN-abc123", parent)
        assertTrue("got $r", r is FamilyClient.ConsentResult.Recorded)
        assertEquals(parent, server.lastConsentParentId)
        assertEquals("pair-TOKEN-abc123", server.lastConsentToken)
    }

    @Test fun `recordConsent rejects a non-UUID parent id without calling the server`() {
        val key = EphemeralDeviceKey()
        val server = FakeFamilyServer(key.publicKeyB64())
        val client = FamilyClient(server, FakeSession(), key)
        val r = client.recordConsent("pair-TOKEN-abc123", "not-a-uuid")
        assertEquals("bad-parent-id", (r as FamilyClient.ConsentResult.Failed).reason)
        assertEquals("no challenge was requested", 0, server.challengeCalls)
    }

    @Test fun `recordConsent rejects an empty token without calling the server`() {
        val client = FamilyClient(FakeFamilyServer("x"), FakeSession(), EphemeralDeviceKey())
        val r = client.recordConsent("", "88888888-8888-4888-8888-888888888888")
        assertEquals("bad-token", (r as FamilyClient.ConsentResult.Failed).reason)
    }

    @Test fun `recordConsent surfaces a server refusal as Failed, never success`() {
        val key = EphemeralDeviceKey()
        val server = FakeFamilyServer(key.publicKeyB64())
        server.consentStatus = 403
        val client = FamilyClient(server, FakeSession(), key)
        val r = client.recordConsent("pair-TOKEN-abc123", "88888888-8888-4888-8888-888888888888")
        assertEquals("refused", (r as FamilyClient.ConsentResult.Failed).reason)
    }

    @Test fun `a wrong device key is refused by the consent proof and yields Failed`() {
        val signer = EphemeralDeviceKey()
        val otherPub = EphemeralDeviceKey().publicKeyB64() // server expects a DIFFERENT key
        val client = FamilyClient(FakeFamilyServer(otherPub), FakeSession(), signer)
        val r = client.recordConsent("pair-TOKEN-abc123", "88888888-8888-4888-8888-888888888888")
        assertTrue(r is FamilyClient.ConsentResult.Failed)
    }

    @Test fun `thisUserId reflects the session user and is null without one`() {
        val key = EphemeralDeviceKey()
        val linked = FamilyClient(FakeFamilyServer(key.publicKeyB64()), FakeSession(), key)
        assertEquals(userId, linked.thisUserId())

        val noSession = object : FamilySessionSource {
            override fun ensureSession(): AccountSession? = null
            override fun forgetSession() {}
        }
        assertEquals(null, FamilyClient(FakeFamilyServer("x"), noSession, key).thisUserId())
    }

    @Test fun `consumeInvitation rejects an empty token without calling the server`() {
        val client = FamilyClient(FakeFamilyServer("x"), FakeSession(), EphemeralDeviceKey())
        assertEquals("bad-token", (client.consumeInvitation("") as FamilyClient.LinkResult.Failed).reason)
    }

    @Test fun `listDevices parses the server list and rejects a malformed id`() {
        val key = EphemeralDeviceKey()
        val server = FakeFamilyServer(key.publicKeyB64())
        val client = FamilyClient(server, FakeSession(), key)
        val id = "55555555-5555-4555-8555-555555555555"
        server.devicesBody = JSONObject().put(
            "devices", JSONArray().put(JSONObject().put("child_device_id", id).put("linked_at", "2026-01-01T00:00:00+00:00")),
        ).toString()
        val ok = client.listDevices()
        assertTrue(ok is FamilyClient.DevicesResult.Ok)
        assertEquals(id, (ok as FamilyClient.DevicesResult.Ok).devices.single().childDeviceId)

        server.devicesBody = JSONObject().put(
            "devices", JSONArray().put(JSONObject().put("child_device_id", "not-a-uuid").put("linked_at", "")),
        ).toString()
        assertTrue(client.listDevices() is FamilyClient.DevicesResult.Failed)
    }

    @Test fun `revokeChildDevice is true only on 200 and validates the id`() {
        val key = EphemeralDeviceKey()
        val server = FakeFamilyServer(key.publicKeyB64())
        val client = FamilyClient(server, FakeSession(), key)
        val id = "66666666-6666-4666-8666-666666666666"
        server.deleteStatus = 200
        assertTrue(client.revokeChildDevice(id))
        server.deleteStatus = 404
        assertFalse(client.revokeChildDevice(id))
        assertFalse("a non-UUID id never hits the server", client.revokeChildDevice("nope"))
    }

    @Test fun `thisDeviceId reflects the session device and is null without one`() {
        val key = EphemeralDeviceKey()
        val linked = FamilyClient(FakeFamilyServer(key.publicKeyB64()), FakeSession(), key)
        assertEquals(deviceId, linked.thisDeviceId())

        val noSession = object : FamilySessionSource {
            override fun ensureSession(): AccountSession? = null
            override fun forgetSession() {}
        }
        assertEquals(null, FamilyClient(FakeFamilyServer("x"), noSession, key).thisDeviceId())
    }

    @Test fun `child parent-link recovery maps 200 to Revoked and 401 to RecentAuthRequired`() {
        val key = EphemeralDeviceKey()
        val server = FakeFamilyServer(key.publicKeyB64())
        val client = FamilyClient(server, FakeSession(), key)
        val id = "77777777-7777-4777-8777-777777777777"
        server.deleteStatus = 200
        assertTrue(client.revokeParentLinkAsChild(id) is FamilyClient.ParentRevokeResult.Revoked)
        server.deleteStatus = 401
        assertTrue(client.revokeParentLinkAsChild(id) is FamilyClient.ParentRevokeResult.RecentAuthRequired)
    }

    private companion object {
        // 12-byte Ed25519 SubjectPublicKeyInfo prefix for a 32-byte raw key.
        val SPKI_PREFIX: ByteArray = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
    }
}
