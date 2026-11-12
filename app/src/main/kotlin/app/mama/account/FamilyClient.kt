package app.mama.account

import java.util.Base64
import org.json.JSONObject

/** Family uses the existing account session and the registered device key. */
class FamilyClient(
    private val account: MamaAccountClient,
    private val key: DeviceKey,
    private val transport: AccountHttpTransport,
) {
    data class Invitation(val code: String, val expiresAt: String)
    class Denied(val status: Int) : Exception()

    fun identity(): AccountSession =
        (account.ensureSession() as? MamaAccountClient.Result.Ok)?.session ?: throw Denied(0)

    private fun freshIdentity(): AccountSession {
        account.forgetSession()
        return identity()
    }

    fun invite(): Invitation {
        val session = identity()
        val device = JSONObject().put("device_id", session.deviceId)
        val challenge = post("/device-key-challenges", device, session)
        val id = challenge.getString("challenge_id")
        val nonce = challenge.getString("nonce")
        require(MamaAccountClient.isCanonicalUuid(id) && tokenPattern.matches(nonce))
        val signed = challengeMessage(session.userId, session.deviceId, id, nonce)
        val request = device
            .put("challenge_id", id)
            .put("nonce", nonce)
            .put("signature_b64", Base64.getEncoder().encodeToString(key.sign(signed)))
        val reply = post("/device-invitations", request, session)
        val code = reply.getString("invitation_token")
        require(tokenPattern.matches(code))
        return Invitation(code, reply.getString("expires_at"))
    }

    fun link(code: String): String {
        if (!tokenPattern.matches(code)) throw Denied(404)
        val session = freshIdentity()
        val reply = post("/links", JSONObject().put("invitation_token", code), session)
        require(reply.getString("status") == "linked")
        return uuid(reply.getString("child_device_id"))
    }

    fun devices(): List<String> {
        val session = identity()
        val devices = json(transport.getJson("$BASE/devices", session.token)).getJSONArray("devices")
        return List(devices.length()) { index ->
            uuid(devices.getJSONObject(index).getString("child_device_id"))
        }
    }

    fun revoke(deviceId: String) {
        val session = identity()
        revokeAt("/devices/${uuid(deviceId)}", session)
    }

    fun revokeOwnParent() {
        val session = freshIdentity()
        revokeAt("/my-devices/${uuid(session.deviceId)}/parent", session)
    }

    private fun revokeAt(path: String, session: AccountSession) {
        val reply = json(transport.deleteJson(BASE + path, session.token))
        require(reply.getString("status") == "revoked")
    }

    private fun post(path: String, body: JSONObject, session: AccountSession): JSONObject =
        json(transport.postJson(BASE + path, body.toString(), session.token))

    private fun json(reply: HttpReply): JSONObject {
        if (reply.status != 200) {
            if (reply.status == 401) account.forgetSession()
            throw Denied(reply.status)
        }
        return JSONObject(reply.body)
    }

    private fun uuid(value: String): String {
        require(MamaAccountClient.isCanonicalUuid(value))
        return value
    }

    companion object {
        private const val BASE = "/v1/family"
        private val tokenPattern = Regex("[A-Za-z0-9_-]{43}")

        internal fun challengeMessage(user: String, device: String, challenge: String, nonce: String) =
            ("MAMA/family/device-key-proof/v1\n" +
                listOf(user, challenge, device, nonce).joinToString("\n")).toByteArray(Charsets.US_ASCII)
    }
}
