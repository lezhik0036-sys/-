package app.mama.account

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FamilyClientTest {
    private val user = "11111111-1111-4111-8111-111111111111"
    private val device = "22222222-2222-4222-8222-222222222222"
    private val challenge = "33333333-3333-4333-8333-333333333333"
    private val nonce = "n".repeat(43)
    private val code = "i".repeat(43)
    private val token = "s".repeat(43)

    @Test
    fun signsServerCanonicalProofAndLeavesLostInvitationUnconfirmed() {
        var sent = 0
        val store = object : AccountSessionStore {
            override fun load() = AccountSession(user, device, token)
            override fun save(session: AccountSession) = Unit
            override fun clear() = Unit
        }
        val key = object : DeviceKey {
            override fun publicKeyB64() = ""
            override fun sign(message: ByteArray): ByteArray {
                assertEquals(
                    "MAMA/family/device-key-proof/v1\n$user\n$challenge\n$device\n$nonce",
                    message.toString(Charsets.US_ASCII),
                )
                return ByteArray(64)
            }
        }
        val transport = object : AccountHttpTransport {
            override fun postJson(path: String, jsonBody: String, bearer: String?): HttpReply {
                assertEquals(token, bearer)
                val body = JSONObject(jsonBody)
                assertEquals(device, body.getString("device_id"))
                return when (path) {
                    "/v1/family/device-key-challenges" ->
                        HttpReply(200, """{"challenge_id":"$challenge","nonce":"$nonce"}""")
                    "/v1/family/device-invitations" -> {
                        assertEquals(challenge, body.getString("challenge_id"))
                        assertEquals(nonce, body.getString("nonce"))
                        sent++
                        if (sent == 1) throw IOException("lost response")
                        HttpReply(200, """{"invitation_token":"$code","expires_at":"2099-01-01T00:00:00Z"}""")
                    }
                    else -> error("unexpected route")
                }
            }
        }
        val client = FamilyClient(MamaAccountClient(transport, key, store), key, transport)
        assertThrows(IOException::class.java) { client.invite() }
        assertEquals(1, sent)
        assertEquals(code, client.invite().code)
        assertEquals(2, sent)
        assertEquals(404, assertThrows(FamilyClient.Denied::class.java) {
            client.link("invalid")
        }.status)
    }
}
