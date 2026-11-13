package app.mama.family

import app.mama.account.AccountHttpTransport
import app.mama.account.AccountSession
import app.mama.account.AccountSessionStore
import app.mama.account.DeviceKey
import app.mama.account.HttpReply
import app.mama.account.MamaAccountClient
import app.mama.account.UrlConnectionAccountTransport
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end proof of the UNIFIED family flow against a REAL FastAPI. Adapted from the
 * parallel agent's PR #94 FamilyLiveHttpTest to this app's single, design-system-
 * integrated, server-authoritative [FamilyClient] (app.mama.family) — not the separate
 * FamilyActivity client. It supersedes #94's app.mama.account version; both exercise
 * the same server contract.
 *
 * Runs ONLY inside FastAPI's cross-repository Family job, which provisions a local
 * HTTPS FastAPI, a TLS cert and a fixture directory (MAMA_FAMILY_E2E_DIR) whose
 * request/result files drive the server-side guardian-consent approval and invitation
 * expiry. Outside that job it is SKIPPED (assumeTrue), so normal CI needs no network,
 * sends no SMS, and this cannot fake success.
 *
 * Scenario — exactly the owner's required working flow, with three INDEPENDENT
 * device-key accounts (parent, a no-SIM child, a stranger):
 *   - the child mints a one-time invitation by proving device-key possession (the whole
 *     credential is the Ed25519 device key, so no SIM/telephony is involved);
 *   - parent linking is refused 503 until guardian consent is provisioned (honest,
 *     never forged);
 *   - after consent: a stranger is refused, a bad/expired token is refused, and a second
 *     child invite while one is pending is refused;
 *   - a LOST /links reply after the server commits leaves the client UNCONFIRMED, yet the
 *     link is real (the parent sees the child in the list) and a replay is refused;
 *   - the child recovers by removing its own parent link; the parent re-links and revokes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FamilyLiveHttpTest {

    @Test
    fun `parent links a no-SIM child through real FastAPI with consent, revoke and recovery`() {
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
            var loseLinkReply = false
            // The parent's transport drops the /links response AFTER the server commits,
            // to prove the client is fail-closed (reports unconfirmed) yet the link is real.
            val parentTransport = object : AccountHttpTransport by https {
                override fun postJson(path: String, jsonBody: String, bearer: String?): HttpReply {
                    val response = https.postJson(path, jsonBody, bearer)
                    if (path == FamilyClient.PATH_LINKS && loseLinkReply && response.status == 200) {
                        loseLinkReply = false
                        throw IOException("simulated response loss after server commit")
                    }
                    return response
                }
            }
            val parent = Role(parentTransport)
            val child = Role(https)
            val stranger = Role(https)

            val parentId = parent.session()
            val childId = child.session()

            // The no-SIM child mints a one-time invitation.
            val token = created(child.family.createInvitation())

            // Parent linking is refused until guardian consent is provisioned (503).
            assertTrue(
                "linking must be refused 503 until the server has guardian consent",
                parent.family.consumeInvitation(token) is FamilyClient.LinkResult.ConsentUnavailable,
            )

            approve(fixture, parentId, childId)

            // A stranger cannot consume the child's invitation; a malformed/unknown token is refused.
            assertTrue(stranger.family.consumeInvitation(token) is FamilyClient.LinkResult.Failed)
            assertTrue(parent.family.consumeInvitation("z".repeat(43)) is FamilyClient.LinkResult.Failed)
            // A second invitation while one is pending is refused by the server.
            assertTrue(child.family.createInvitation() is FamilyClient.InviteResult.Failed)

            // Expire the pending invitation on the server; it can no longer be consumed.
            expire(fixture, token)
            assertTrue(parent.family.consumeInvitation(token) is FamilyClient.LinkResult.Failed)

            // A fresh invitation + a LOST /links reply: the client is unconfirmed, the link is real.
            val next = created(child.family.createInvitation())
            loseLinkReply = true
            assertTrue(
                "a lost reply must not be reported as a link",
                parent.family.consumeInvitation(next) is FamilyClient.LinkResult.Failed,
            )
            assertEquals(listOf(childId.deviceId), deviceIds(parent.family.listDevices()))
            // A replay of the now-consumed token is refused.
            assertTrue(parent.family.consumeInvitation(next) is FamilyClient.LinkResult.Failed)

            // The child recovers by removing its own parent link; the parent list is empty.
            assertTrue(
                child.family.revokeParentLinkAsChild(childId.deviceId) is FamilyClient.ParentRevokeResult.Revoked,
            )
            assertEquals(emptyList<String>(), deviceIds(parent.family.listDevices()))

            // The parent re-links via a fresh invitation, then revokes.
            val again = created(child.family.createInvitation())
            assertEquals(
                childId.deviceId,
                (parent.family.consumeInvitation(again) as FamilyClient.LinkResult.Linked).childDeviceId,
            )
            assertTrue(parent.family.revokeChildDevice(childId.deviceId))
            assertEquals(emptyList<String>(), deviceIds(parent.family.listDevices()))
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previous)
        }
    }

    private fun created(r: FamilyClient.InviteResult): String =
        (r as FamilyClient.InviteResult.Created).invitationToken

    private fun deviceIds(r: FamilyClient.DevicesResult): List<String> =
        (r as FamilyClient.DevicesResult.Ok).devices.map { it.childDeviceId }

    private fun approve(dir: File, parent: AccountSession, child: AccountSession) = fixtureOp(
        dir,
        JSONObject().put("op", "approve")
            .put("parent_user_id", parent.userId)
            .put("child_user_id", child.userId)
            .put("child_device_id", child.deviceId),
    )

    private fun expire(dir: File, token: String) =
        fixtureOp(dir, JSONObject().put("op", "expire").put("code", token))

    /** Drive a server-side fixture operation (guardian-consent approval / expiry) and await its ack. */
    private fun fixtureOp(directory: File, request: JSONObject) {
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

    /** One independent device-key account + the family client over it (as MamaFamily composes it). */
    private class Role(transport: AccountHttpTransport) {
        private val key = object : DeviceKey {
            private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            override fun publicKeyB64(): String =
                Base64.getEncoder().encodeToString(pair.public.encoded.takeLast(32).toByteArray())
            override fun sign(message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
                initSign(pair.private); update(message); sign()
            }
        }
        private val store = object : AccountSessionStore {
            private var s: AccountSession? = null
            override fun load() = s
            override fun save(session: AccountSession) { s = session }
            override fun clear() { s = null }
        }
        private val account = MamaAccountClient(transport, key, store)
        val family = FamilyClient(
            transport,
            object : FamilySessionSource {
                override fun ensureSession(): AccountSession? =
                    (account.ensureSession() as? MamaAccountClient.Result.Ok)?.session
                override fun forgetSession() = account.forgetSession()
            },
            key,
        )

        fun session(): AccountSession = (account.ensureSession() as MamaAccountClient.Result.Ok).session
    }
}
