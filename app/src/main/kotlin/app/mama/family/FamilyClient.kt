package app.mama.family

import app.mama.account.AccountHttpTransport
import app.mama.account.AccountSession
import app.mama.account.DeviceKey
import app.mama.account.HttpReply
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * The authenticated MAMA Family client (the `/v1/family` API). All authority is
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
 *      4. POST /v1/family/consents {device_id, challenge_id, nonce, signature_b64,
 *         invitation_token, parent_user_id}  -> the child DEVICE consents to a NAMED
 *         parent (gate 1 / Option A), proven by a fresh device-key signature.
 *  - PARENT device:
 *      POST   /v1/family/links {invitation_token}   -> link. 503 until BOTH gates hold:
 *             gate 1 (the child device consented to THIS parent) and gate 2 (an operator
 *             granted guardian authority). The body's detail says which is missing, so the
 *             app guides the right next step honestly; it never forges a link.
 *      GET    /v1/family/devices                    -> this parent's linked child devices
 *      DELETE /v1/family/devices/{child_device_id}  -> revoke a link
 *  - CHILD recovery (lost tablet): DELETE /v1/family/my-devices/{id}/parent, which the
 *    server gates on a RECENT re-authentication; a stale bearer is refused (not a bypass).
 *
 * Fail-closed: a transport failure, a non-2xx (except the explicit 503 gate cases),
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
        val proof = when (val p = freshDeviceKeyProof(sess.userId, sess.deviceId)) {
            is ProofResult.Ok -> p
            is ProofResult.Failed -> return InviteResult.Failed(p.reason)
        }

        val invite = authed(
            "POST", PATH_INVITATIONS,
            JSONObject()
                .put("device_id", sess.deviceId)
                .put("challenge_id", proof.challengeId)
                .put("nonce", proof.nonce)
                .put("signature_b64", proof.signatureB64)
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

    // --- CHILD (gate 1): the child DEVICE explicitly consents to a NAMED parent ---

    sealed interface ConsentResult {
        /** The child device recorded its consent to the named parent for this invitation. */
        object Recorded : ConsentResult
        /** No consent recorded (no session / bad parent id / proof refused / transport). */
        data class Failed(val reason: String) : ConsentResult
    }

    /**
     * Record this child device's explicit consent that [parentUserId] may link it,
     * for the single-use [invitationToken] (gate 1 / Option A). The consent evidence
     * is a FRESH device-key signature verified server-side — never a local flag. This
     * grants NO parent access by itself: the parent's /links still needs the separate
     * server-side guardian-authority grant (gate 2). The parent account id is read
     * from the parent's own Family screen and entered here; naming a specific parent
     * is what makes the consent parent-specific. Call OFF the main thread.
     */
    fun recordConsent(invitationToken: String, parentUserId: String): ConsentResult {
        if (invitationToken.length !in 1..256) return ConsentResult.Failed("bad-token")
        if (!isCanonicalUuid(parentUserId)) return ConsentResult.Failed("bad-parent-id")
        val sess = session.ensureSession() ?: return ConsentResult.Failed("no-session")
        val proof = when (val p = freshDeviceKeyProof(sess.userId, sess.deviceId)) {
            is ProofResult.Ok -> p
            is ProofResult.Failed -> return ConsentResult.Failed(p.reason)
        }
        val reply = authed(
            "POST", PATH_CONSENTS,
            JSONObject()
                .put("device_id", sess.deviceId)
                .put("challenge_id", proof.challengeId)
                .put("nonce", proof.nonce)
                .put("signature_b64", proof.signatureB64)
                .put("invitation_token", invitationToken)
                .put("parent_user_id", parentUserId)
                .toString(),
        ) ?: return ConsentResult.Failed("transport")
        return when (reply.status) {
            200 -> ConsentResult.Recorded
            // Generic server refusal: bad/expired proof, not this child's invitation,
            // or an unknown parent. Never surfaced as success.
            403 -> ConsentResult.Failed("refused")
            else -> ConsentResult.Failed("consent-${reply.status}")
        }
    }

    // --- PARENT: consume an invitation to link a child device ---

    sealed interface LinkResult {
        data class Linked(val childDeviceId: String) : LinkResult
        /** Gate 1 (503): the child device has not yet consented to THIS parent. The
         *  next step is on the CHILD device, not a server-provisioning wait. */
        object AwaitingChildConsent : LinkResult
        /** Gate 2 (503): no operator has granted this parent guardian authority yet. */
        object AwaitingGuardianAuthority : LinkResult
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
            // The two-gate server answers 503 for either unmet gate; the body's detail
            // distinguishes them so the app can guide the right next step. "guardian
            // authority" -> gate 2 (operator); anything else -> gate 1 (child consent).
            503 -> {
                val detail = runCatching { JSONObject(reply.body).optString("detail", "") }.getOrElse { "" }
                if (detail.contains("authority", ignoreCase = true)) LinkResult.AwaitingGuardianAuthority
                else LinkResult.AwaitingChildConsent
            }
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

    /**
     * This device's own server-side id, from the current authenticated session, or
     * null when there is no session. Used by the child-recovery UI to detach THIS
     * device from its parent ([revokeParentLinkAsChild]); it is read from the
     * server-issued session, never a local role or guess.
     */
    fun thisDeviceId(): String? = session.ensureSession()?.deviceId

    /**
     * This account's server-side user id, from the current authenticated session, or
     * null when there is no session. The PARENT reads this out so the CHILD device can
     * name it when recording consent ([recordConsent]); it is the server-issued account
     * id, never a local role or guess. It is a pairing identifier, not a secret — on its
     * own it grants nothing, since linking still needs the child's consent and the
     * operator's guardian-authority grant.
     */
    fun thisUserId(): String? = session.ensureSession()?.userId

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

    // --- shared device-key proof: fresh challenge -> signed canonical message ---

    private sealed interface ProofResult {
        data class Ok(val challengeId: String, val nonce: String, val signatureB64: String) : ProofResult
        data class Failed(val reason: String) : ProofResult
    }

    /**
     * Obtain a fresh server challenge for [deviceId] and sign its canonical message
     * with the registered device key. Used by BOTH the invitation (child proves key
     * possession) and the consent (child signs again to name a parent); each call gets
     * its own single-use challenge, as the server requires. Fail-closed: a transport
     * error, non-200, malformed challenge, or a signing failure yields a reason and
     * never a forged proof.
     */
    private fun freshDeviceKeyProof(userId: String, deviceId: String): ProofResult {
        val challenge = authed("POST", PATH_CHALLENGES, JSONObject().put("device_id", deviceId).toString())
            ?: return ProofResult.Failed("transport")
        if (challenge.status != 200) return ProofResult.Failed("challenge-${challenge.status}")
        val (challengeId, nonce) = runCatching {
            val j = JSONObject(challenge.body)
            j.getString("challenge_id") to j.getString("nonce")
        }.getOrElse { return ProofResult.Failed("challenge-malformed") }
        if (!isCanonicalUuid(challengeId) || nonce.isBlank()) return ProofResult.Failed("challenge-malformed")
        val signatureB64 = runCatching {
            B64.encodeToString(deviceKey.sign(proofMessage(userId, challengeId, deviceId, nonce)))
        }.getOrElse { return ProofResult.Failed("sign") }
        return ProofResult.Ok(challengeId, nonce, signatureB64)
    }

    companion object {
        const val PATH_CHALLENGES = "/v1/family/device-key-challenges"
        const val PATH_INVITATIONS = "/v1/family/device-invitations"
        const val PATH_CONSENTS = "/v1/family/consents"
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
