package app.mama.backend.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies a FastAPI-issued Trusted Exit **capability grant** (protocol v0).
 *
 * This is the only trust anchor between Ktor and FastAPI: Ktor does not run its
 * own user auth and does not read FastAPI's sessions/DB. FastAPI signs a
 * short-lived grant with its Ed25519 private key; Ktor verifies it with the
 * pinned public key and takes the caller's identity and bindings ONLY from the
 * signed claims — never from client-supplied JSON (`sessionId`, `contactPhone`).
 *
 * Hard policy (no agility, fail-closed):
 *  - alg = EdDSA (Ed25519) only; `none` and every other alg rejected;
 *  - typ = JWT, kid = [KID] only;
 *  - signature checked against the single pinned key (no `jku`, no URL fetch);
 *  - iss/aud/purpose fixed; the claim set must be EXACTLY [REQUIRED_CLAIMS];
 *  - UUID claims must be canonical lower-case; lifetime ≤ 60s; ≤ 5s future skew.
 *
 * No private key exists here; signing lives only in FastAPI.
 */
class GrantVerifier(
    pinnedPublicKeyBase64: String,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** Pinned Ed25519 public key (X.509/SubjectPublicKeyInfo DER, base64). Null = not configured. */
    private val publicKey: PublicKey? = runCatching {
        val der = Base64.getDecoder().decode(pinnedPublicKeyBase64.trim())
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
    }.getOrNull()

    val configured: Boolean get() = publicKey != null

    sealed interface Result {
        data class Valid(val grant: Grant) : Result
        /** [reason] is for the server log only; the client gets a generic 401/403. */
        data class Invalid(val reason: String) : Result
    }

    fun verify(bearer: String?): Result {
        val key = publicKey ?: return Result.Invalid("grant verifier not configured")
        val token = bearer?.removePrefix("Bearer ")?.trim().orEmpty()
        if (token.isEmpty()) return Result.Invalid("missing grant")
        val parts = token.split(".")
        if (parts.size != 3) return Result.Invalid("malformed grant")
        val (h64, p64, s64) = parts

        val header = decodeJson(h64) ?: return Result.Invalid("bad header")
        if (header.str("alg") != "EdDSA") return Result.Invalid("alg not allowed")
        if (header.str("typ") != "JWT") return Result.Invalid("typ not allowed")
        if (header.str("kid") != KID) return Result.Invalid("kid not allowed")

        // Signature over ASCII("<h64>.<p64>") with the pinned Ed25519 key.
        val signature = runCatching { Base64.getUrlDecoder().decode(s64) }.getOrNull()
            ?: return Result.Invalid("bad signature encoding")
        val signingInput = "$h64.$p64".toByteArray(Charsets.US_ASCII)
        val ok = runCatching {
            Signature.getInstance("Ed25519").apply { initVerify(key); update(signingInput) }.verify(signature)
        }.getOrDefault(false)
        if (!ok) return Result.Invalid("bad signature")

        val payload = decodeJson(p64) ?: return Result.Invalid("bad payload")
        // Exact claim set — no missing, no extra.
        if (payload.keySet() != REQUIRED_CLAIMS) return Result.Invalid("claim set mismatch")

        if (payload.str("iss") != ISS) return Result.Invalid("iss")
        if (payload.str("aud") != AUD) return Result.Invalid("aud")
        if (payload.str("purpose") != PURPOSE) return Result.Invalid("purpose")

        val sub = payload.uuid("sub") ?: return Result.Invalid("sub")
        val seriesId = payload.uuid("series_id") ?: return Result.Invalid("series_id")
        val periodId = payload.uuid("period_id") ?: return Result.Invalid("period_id")
        val deviceId = payload.uuid("device_id") ?: return Result.Invalid("device_id")
        val contactId = payload.uuid("contact_id") ?: return Result.Invalid("contact_id")
        val jti = payload.uuid("jti") ?: return Result.Invalid("jti")

        val iat = payload.epoch("iat") ?: return Result.Invalid("iat")
        val nbf = payload.epoch("nbf") ?: return Result.Invalid("nbf")
        val exp = payload.epoch("exp") ?: return Result.Invalid("exp")

        if (exp - iat > MAX_LIFETIME_SEC) return Result.Invalid("lifetime too long")
        if (exp <= iat) return Result.Invalid("exp not after iat")
        val t = now()
        if (iat > t + SKEW_SEC) return Result.Invalid("iat in the future")
        if (nbf > t + SKEW_SEC) return Result.Invalid("not yet valid")
        if (exp <= t) return Result.Invalid("expired")

        return Result.Valid(Grant(sub, seriesId, periodId, deviceId, contactId, jti, iat, nbf, exp))
    }

    private fun decodeJson(b64: String): JsonObject? = runCatching {
        JsonParser.parseString(String(Base64.getUrlDecoder().decode(b64), Charsets.UTF_8)).asJsonObject
    }.getOrNull()

    private fun JsonObject.str(k: String): String? =
        get(k)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.uuid(k: String): String? =
        str(k)?.takeIf { UUID_LOWER.matches(it) }

    private fun JsonObject.epoch(k: String): Long? =
        get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    companion object {
        const val KID = "mama-ktor-sms-v1"
        const val ISS = "mama-fastapi"
        const val AUD = "mama-ktor-trusted-exit"
        const val PURPOSE = "trusted-exit:sms-request"
        const val MAX_LIFETIME_SEC = 60L
        const val SKEW_SEC = 5L
        val REQUIRED_CLAIMS = setOf(
            "iss", "aud", "purpose", "sub", "series_id", "period_id",
            "device_id", "contact_id", "jti", "iat", "nbf", "exp",
        )
        private val UUID_LOWER = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}

/** The verified, trusted identity + bindings taken from the signed grant. */
data class Grant(
    val sub: String,
    val seriesId: String,
    val periodId: String,
    val deviceId: String,
    val contactId: String,
    val jti: String,
    val iat: Long,
    val nbf: Long,
    val exp: Long,
)
