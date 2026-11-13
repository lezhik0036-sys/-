package app.mama.family

import app.mama.account.AccountHttpTransport
import app.mama.account.AccountSession
import app.mama.account.DeviceKey
import app.mama.account.HttpReply
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * The authenticated MAMA Family client (`/v1/family/*`). All authority is
 * server-issued: this client never sets a parent/child role flag, never grants a
 * BRICK bypass, and never fabricates a link — it only carries the device-key proof
 * and the server's answers. Pairing is backend-authoritative (mama-fastapi #41):
 *
 *  - CHILD device (the one being brought under family oversight — identical on a
 *    no-SIM Wi-Fi tablet, since the whole credential is the Ed25519 device key):
 *      1. POST /v1/family/device-key-challenges {device_id}            -> challenge
 *      2. sign the canonical message with the registered device key
 *      3. POST /v1/family/device-invitations {device_id, challenge_id, nonce,
 *         signature_b64}                                               -> invitation token
 *    The token is a single-use pairing locator the child shows to the parent.
 *  - PARENT device:
 *      POST   /v1/family/links {invitation_token}   -> link (503 until the server's
 *             guardian-consent provider is provisioned; the app shows that honestly)
 *      GET    /v1/family/devices                    -> this parent's linked child devices
 *      DELETE /v1/family/devices/{child_device_id}  -> revoke a link
 *  - CHILD recovery (lost tablet): DELETE /v1/family/my-devices/{id}/parent, which the
 *    server gates on a RECENT re-authentication; a stale bearer is refused (not a bypass).
 *
 * Fail-closed: a transport failure, a non-2xx (except the explicit 503 consent case),
 * or a malformed reply returns a failure and never a forged link. One 401 re-auth per
 * call. Call OFF the main thread (network + Keystore signing).
 */
class FamilyClient(
    private val transport: AccountHttpTransport,
    private val session: FamilySessionSource,
    private val deviceKey: DeviceKey,
) {
    // --- CHILD: create a single-use invitation by proving device-key possession ---

    sealed interface InviteResult {
        data class Created(val invitationToken: String, val expiresAt: String) : InviteResult
        /** Could not create one now (no session / proof refused / transport / malformed). */
        data class Failed(val reason: String) : InviteResult
    }

    fun createInvitation(): InviteResult {
        val sess = session.ensureSession() ?: return InviteResult.Failed("no-session")
        val deviceId = sess.deviceId
        val userId = sess.userId

        val challenge = authed("POST", PATH_CHALLENGES, JSONObject().put("device_id", deviceId).toString())
            ?: return InviteResult.Failed("transport")
        if (challenge.status != 200) return InviteResult.Failed("challenge-${challenge.status}")
        val (challengeId, nonce) = runCatching {
            val j = JSONObject(challenge.body)
            j.getString("challenge_id") to j.getString("nonce")
        }.getOrElse { return InviteResult.Failed("challenge-malformed") }
        if (!isCanonicalUuid(challengeId) || nonce.isBlank()) return InviteResult.Failed("challenge-malformed")

        val signatureB64 = runCatching {
            B64.encodeToString(deviceKey.sign(proofMessage(userId, challengeId, deviceId, nonce)))
        }.getOrElse { return InviteResult.Failed("sign") }

        val invite = authed(
            "POST", PATH_INVITATIONS,
            JSONObject()
                .put("device_id", deviceId)
                .put("challenge_id", challengeId)
                .put("nonce", nonce)
                .put("signature_b64", signatureB64)
                .toString(),
        ) ?: return InviteResult.Failed("transport")
        if (invite.status != 200) return InviteResult.Failed("invite-${invite.status}")
        return runCatching {
            val j = JSONObject(invite.body)
            val token = j.getString("invitation_token")
            val expires = j.optString("expires_at", "")
            if (token.length !in 1..256) InviteResult.Failed("invite-malformed")
            else InviteResult.Created(token, expires)
        }.getOrElse { InviteResult.Failed("invite-malformed") }
    }

    // --- PARENT: consume an invitation to link a child device ---

    sealed interface LinkResult {
        data class Linked(val childDeviceId: String) : LinkResult
        /** The server's guardian-consent provider is not provisioned (503): no link made. */
        object ConsentUnavailable : LinkResult
        data class Failed(val reason: String) : LinkResult
    }

    fun consumeInvitation(invitationToken: String): LinkResult {
        if (invitationToken.length !in 1..256) return LinkResult.Failed("bad-token")
        val reply = authed("POST", PATH_LINKS, JSONObject().put("invitation_token", invitationToken).toString())
            ?: return LinkResult.Failed("transport")
        return when (reply.status) {
            200 -> runCatching {
                val childId = JSONObject(reply.body).getString("child_device_id")
                if (isCanonicalUuid(childId)) LinkResult.Linked(childId) else LinkResult.Failed("link-malformed")
            }.getOrElse { LinkResult.Failed("link-malformed") }
            503 -> LinkResult.ConsentUnavailable
            else -> LinkResult.Failed("link-${reply.status}")
        }
    }

    // --- PARENT: list linked child devices ---

    data class LinkedDevice(val childDeviceId: String, val linkedAt: String)

    sealed interface DevicesResult {
        data class Ok(val devices: List<LinkedDevice>) : DevicesResult
        data class Failed(val reason: String) : DevicesResult
    }

    fun listDevices(): DevicesResult {
        val reply = authed("GET", PATH_DEVICES, null) ?: return DevicesResult.Failed("transport")
        if (reply.status != 200) return DevicesResult.Failed("devices-${reply.status}")
        return runCatching {
            val arr: JSONArray = JSONObject(reply.body).getJSONArray("devices")
            val out = ArrayList<LinkedDevice>(arr.length())
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                val id = d.getString("child_device_id")
                if (!isCanonicalUuid(id)) return@runCatching DevicesResult.Failed("devices-malformed")
                out.add(LinkedDevice(id, d.optString("linked_at", "")))
            }
            DevicesResult.Ok(out)
        }.getOrElse { DevicesResult.Failed("devices-malformed") }
    }

    // --- PARENT: revoke a child-device link ---

    fun revokeChildDevice(childDeviceId: String): Boolean {
        if (!isCanonicalUuid(childDeviceId)) return false
        val reply = authed("DELETE", "$PATH_DEVICES/$childDeviceId", null) ?: return false
        return reply.status == 200
    }

    // --- CHILD recovery: remove the parent link (server requires a RECENT auth) ---

    sealed interface ParentRevokeResult {
        object Revoked : ParentRevokeResult
        /** The server requires a fresh re-login for this sensitive change (401). */
        object RecentAuthRequired : ParentRevokeResult
        data class Failed(val reason: String) : ParentRevokeResult
    }

    fun revokeParentLinkAsChild(childDeviceId: String): ParentRevokeResult {
        if (!isCanonicalUuid(childDeviceId)) return ParentRevokeResult.Failed("bad-id")
        val reply = authed("DELETE", "$PATH_MY_DEVICES/$childDeviceId/parent", null)
            ?: return ParentRevokeResult.Failed("transport")
        return when (reply.status) {
            200 -> ParentRevokeResult.Revoked
            401 -> ParentRevokeResult.RecentAuthRequired
            else -> ParentRevokeResult.Failed("revoke-${reply.status}")
        }
    }

    // --- internals: bearer + one 401 re-auth, fail-closed ---

    private fun authed(verb: String, path: String, body: String?): HttpReply? {
        val sess = session.ensureSession() ?: return null
        var reply = call(verb, path, body, sess.token) ?: return null
        if (reply.status == 401) {
            // One re-auth attempt on 401. For the child parent-link revoke the server
            // also answers 401 to demand a RECENT session; a fresh device-key login is
            // itself recent, so the retry satisfies it. If it is STILL 401 the caller
            // surfaces RecentAuthRequired rather than silently failing.
            session.forgetSession()
            val fresh = session.ensureSession() ?: return reply
            reply = call(verb, path, body, fresh.token) ?: return null
        }
        return reply
    }

    private fun call(verb: String, path: String, body: String?, bearer: String): HttpReply? = runCatching {
        when (verb) {
            "GET" -> transport.getJson(path, bearer)
            "DELETE" -> transport.deleteJson(path, bearer)
            else -> transport.postJson(path, body ?: "{}", bearer)
        }
    }.getOrNull()

    companion object {
        const val PATH_CHALLENGES = "/v1/family/device-key-challenges"
        const val PATH_INVITATIONS = "/v1/family/device-invitations"
        const val PATH_LINKS = "/v1/family/links"
        const val PATH_DEVICES = "/v1/family/devices"
        const val PATH_MY_DEVICES = "/v1/family/my-devices"

        // Must equal the server's family_device_key_proof.challenge_message:
        // DOMAIN + "\n".join(user_id, challenge_id, device_id, nonce), US-ASCII.
        private const val DOMAIN = "MAMA/family/device-key-proof/v1\n"

        private val UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
        )
        private val B64: Base64.Encoder = Base64.getEncoder()

        internal fun isCanonicalUuid(value: String): Boolean = UUID_PATTERN.matches(value)

        internal fun proofMessage(
            userId: String, challengeId: String, deviceId: String, nonce: String,
        ): ByteArray =
            (DOMAIN + listOf(userId, challengeId, deviceId, nonce).joinToString("\n"))
                .toByteArray(Charsets.US_ASCII)
    }
}

/** The minimal authenticated-session capability [FamilyClient] needs. */
interface FamilySessionSource {
    /** The current MAMA account session (device-key authenticated), or null if none. */
    fun ensureSession(): AccountSession?

    /** Drop the cached bearer so the next call re-authenticates. */
    fun forgetSession()
}
