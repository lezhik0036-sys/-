package app.mama.account

import java.io.IOException
import java.util.Base64
import org.json.JSONObject

/**
 * Obtains and keeps a MAMA session by device-key challenge-response against the
 * FastAPI account API (/v1/account/{challenge,register,login}).
 *
 * The device key is the whole credential: no password, no phone number, so this
 * is identical on a Wi-Fi-only / no-SIM device. Each auth proves possession of
 * the private key over a one-time, purpose-bound server nonce; the exact bytes
 * signed match the server's canonical message byte-for-byte. Fail-closed: a
 * transport failure, a 5xx, or a malformed reply yields [Result.Unavailable],
 * never a forged session.
 */
class MamaAccountClient(
    private val transport: AccountHttpTransport,
    private val deviceKey: DeviceKey,
    private val store: AccountSessionStore,
) {
    sealed interface Result {
        data class Ok(val session: AccountSession) : Result
        /** No session could be established right now (offline / transport / server). Retry later. */
        data object Unavailable : Result
        /** The server refused this key's own auth — should not happen; treat as fail-closed. */
        data object Rejected : Result
    }

    /**
     * Return a usable session: the cached one if present, otherwise log in (the
     * device may already be registered), otherwise register this device. A
     * concurrent first-registration race (409) falls back to login.
     */
    fun ensureSession(): Result {
        store.load()?.let { return Result.Ok(it) }
        return when (val login = attempt(PURPOSE_LOGIN, PATH_LOGIN)) {
            is Attempt.Ok -> persist(login.session)
            Attempt.Unavailable -> Result.Unavailable
            Attempt.Conflict -> Result.Unavailable  // login never legitimately 409s
            Attempt.Unauthorized -> register()       // key not registered yet
        }
    }

    /** Drop the cached bearer (e.g. after an authenticated 401) so the next call re-auths. */
    fun forgetSession() = store.clear()

    private fun register(): Result =
        when (val reg = attempt(PURPOSE_REGISTER, PATH_REGISTER)) {
            is Attempt.Ok -> persist(reg.session)
            Attempt.Conflict -> when (val relogin = attempt(PURPOSE_LOGIN, PATH_LOGIN)) {
                is Attempt.Ok -> persist(relogin.session)
                else -> Result.Unavailable
            }
            Attempt.Unauthorized -> Result.Rejected  // our own signature rejected: fail-closed
            Attempt.Unavailable -> Result.Unavailable
        }

    private fun persist(session: AccountSession): Result {
        store.save(session)
        return Result.Ok(session)
    }

    private sealed interface Attempt {
        data class Ok(val session: AccountSession) : Attempt
        data object Unauthorized : Attempt   // 401
        data object Conflict : Attempt       // 409
        data object Unavailable : Attempt    // transport / 5xx / malformed
    }

    private fun attempt(purpose: String, path: String): Attempt {
        val pub = deviceKey.publicKeyB64()
        val challenge = runCatching {
            transport.postJson(
                PATH_CHALLENGE,
                JSONObject().put("purpose", purpose).put("public_key_b64", pub).toString(),
                null,
            )
        }.getOrElse { return Attempt.Unavailable }
        if (challenge.status != 200) return Attempt.Unavailable
        val (challengeId, nonce) = runCatching {
            val j = JSONObject(challenge.body)
            j.getString("challenge_id") to j.getString("nonce")
        }.getOrElse { return Attempt.Unavailable }

        val signature = runCatching {
            B64.encodeToString(deviceKey.sign(authMessage(purpose, challengeId, nonce, pub)))
        }.getOrElse { return Attempt.Unavailable }

        val reply = runCatching {
            transport.postJson(
                path,
                JSONObject()
                    .put("challenge_id", challengeId)
                    .put("nonce", nonce)
                    .put("public_key_b64", pub)
                    .put("signature_b64", signature)
                    .toString(),
                null,
            )
        }.getOrElse { return Attempt.Unavailable }

        return when (reply.status) {
            200 -> runCatching {
                val j = JSONObject(reply.body)
                // Never cache a malformed or partial "success" response.
                // The backend issues UUID identities and a URL-safe 32-byte
                // random bearer. Empty strings and fabricated object values
                // must not become a persisted account identity.
                val userId = j.getString("user_id")
                val deviceId = j.getString("device_id")
                val token = j.getString("session_token")
                if (!isCanonicalUuid(userId) || !isCanonicalUuid(deviceId) ||
                    !isBearerFormat(token)
                ) {
                    Attempt.Unavailable
                } else {
                    Attempt.Ok(AccountSession(userId, deviceId, token))
                }
            }.getOrElse { Attempt.Unavailable }
            401 -> Attempt.Unauthorized
            409 -> Attempt.Conflict
            else -> Attempt.Unavailable
        }
    }

    companion object {
        const val PATH_CHALLENGE = "/v1/account/challenge"
        const val PATH_REGISTER = "/v1/account/register"
        const val PATH_LOGIN = "/v1/account/login"
        const val PURPOSE_REGISTER = "register"
        const val PURPOSE_LOGIN = "login"

        // Must equal the server's account_device_auth_service.account_auth_message:
        // DOMAIN + "\n".join(purpose, challenge_id, nonce, public_key_b64).
        private const val DOMAIN = "MAMA/account/device-auth/v1\n"
        // UUID string formatting must exactly match FastAPI's canonical UUID
        // serialization; reject accidental "null", objects, or altered IDs.
        private val UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
        )
        private val BEARER_PATTERN = Regex("^[A-Za-z0-9_-]{20,256}$")

        internal fun isCanonicalUuid(value: String): Boolean = UUID_PATTERN.matches(value)
        internal fun isBearerFormat(value: String): Boolean = BEARER_PATTERN.matches(value)


        private val B64: Base64.Encoder = Base64.getEncoder()

        internal fun authMessage(
            purpose: String, challengeId: String, nonce: String, publicKeyB64: String,
        ): ByteArray =
            (DOMAIN + listOf(purpose, challengeId, nonce, publicKeyB64).joinToString("\n"))
                .toByteArray(Charsets.US_ASCII)
    }
}
