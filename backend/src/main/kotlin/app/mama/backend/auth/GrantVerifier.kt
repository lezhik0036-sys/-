package app.mama.backend.auth

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
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
 * Two non-interchangeable scopes share this verifier (same pinned FastAPI key),
 * distinguished by `kid` + `purpose` + claim set:
 *  - **SMS request** (`kid=mama-ktor-sms-v1`, `purpose=trusted-exit:sms-request`)
 *    for `POST /trusted-exit/request` — the default here.
 *  - **OTP verify** (`kid=mama-ktor-verify-v1`, `purpose=trusted-exit:verify`,
 *    plus a `challenge_id` claim) for `POST /trusted-exit/verify`
 *    ([forVerify]). A fresh verify grant is needed because the ≤60s request grant
 *    is long expired by the time the user types the code 15 min later.
 *
 * Hard policy (no agility, fail-closed):
 *  - alg = EdDSA (Ed25519) only; `none` and every other alg rejected;
 *  - typ = JWT, kid = [expectedKid] only;
 *  - signature checked against the single pinned key (no `jku`, no URL fetch);
 *  - iss/aud/purpose fixed; the claim set must be EXACTLY the scope's set;
 *  - UUID claims must be canonical lower-case; `challenge_id` is `tec-<uuid>`;
 *    lifetime ≤ 60s; ≤ 5s future skew.
 *
 * No private key exists here; signing lives only in FastAPI.
 */
class GrantVerifier(
    pinnedPublicKeyBase64: String,
    private val expectedKid: String = KID,
    private val expectedPurpose: String = PURPOSE,
    private val requireChallengeId: Boolean = false,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val requiredClaims: Set<String> =
        if (requireChallengeId) REQUIRED_CLAIMS + "challenge_id" else REQUIRED_CLAIMS
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
        // Canonical base64url, no padding, no standard-base64 chars (+, /, =).
        if (parts.any { it.isEmpty() || !B64URL.matches(it) }) return Result.Invalid("non-canonical base64url")

        val header = decodeJson(h64) ?: return Result.Invalid("bad header")
        // Exact header key set — no missing, no extra (e.g. no jku/x5u/crit).
        if (header.keySet() != REQUIRED_HEADER_KEYS) return Result.Invalid("header key set mismatch")
        if (header.str("alg") != "EdDSA") return Result.Invalid("alg not allowed")
        if (header.str("typ") != "JWT") return Result.Invalid("typ not allowed")
        if (header.str("kid") != expectedKid) return Result.Invalid("kid not allowed")

        // Signature over ASCII("<h64>.<p64>") with the pinned Ed25519 key.
        val signature = runCatching { Base64.getUrlDecoder().decode(s64) }.getOrNull()
            ?: return Result.Invalid("bad signature encoding")
        val signingInput = "$h64.$p64".toByteArray(Charsets.US_ASCII)
        val ok = runCatching {
            Signature.getInstance("Ed25519").apply { initVerify(key); update(signingInput) }.verify(signature)
        }.getOrDefault(false)
        if (!ok) return Result.Invalid("bad signature")

        val payload = decodeJson(p64) ?: return Result.Invalid("bad payload")
        // Exact claim set — no missing, no extra (scope-specific; verify adds challenge_id).
        if (payload.keySet() != requiredClaims) return Result.Invalid("claim set mismatch")

        if (payload.str("iss") != ISS) return Result.Invalid("iss")
        if (payload.str("aud") != AUD) return Result.Invalid("aud")
        if (payload.str("purpose") != expectedPurpose) return Result.Invalid("purpose")

        val sub = payload.uuid("sub") ?: return Result.Invalid("sub")
        val seriesId = payload.uuid("series_id") ?: return Result.Invalid("series_id")
        val periodId = payload.uuid("period_id") ?: return Result.Invalid("period_id")
        val deviceId = payload.uuid("device_id") ?: return Result.Invalid("device_id")
        val contactId = payload.uuid("contact_id") ?: return Result.Invalid("contact_id")
        val jti = payload.uuid("jti") ?: return Result.Invalid("jti")

        val challengeId = if (requireChallengeId) {
            payload.str("challenge_id")?.takeIf { CHALLENGE_ID.matches(it) } ?: return Result.Invalid("challenge_id")
        } else {
            null
        }

        val iat = payload.epoch("iat") ?: return Result.Invalid("iat")
        val nbf = payload.epoch("nbf") ?: return Result.Invalid("nbf")
        val exp = payload.epoch("exp") ?: return Result.Invalid("exp")

        if (nbf < iat) return Result.Invalid("nbf before iat")
        if (exp - iat > MAX_LIFETIME_SEC) return Result.Invalid("lifetime too long")
        if (exp <= iat) return Result.Invalid("exp not after iat")
        val t = now()
        if (iat > t + SKEW_SEC) return Result.Invalid("iat in the future")
        if (nbf > t + SKEW_SEC) return Result.Invalid("not yet valid")
        if (exp <= t) return Result.Invalid("expired")

        return Result.Valid(Grant(sub, seriesId, periodId, deviceId, contactId, jti, iat, nbf, exp, challengeId))
    }

    /**
     * Strict, flat-object JSON decode of a JWT segment. Rejects (returns null on)
     * duplicate keys — Gson's default parser silently keeps the last — anything
     * other than a single top-level object of primitive values, and any trailing
     * content after the object. Numbers are kept exact (BigDecimal) so [epoch]
     * can require integers and reject fractional NumericDate tokens.
     */
    private fun decodeJson(b64: String): JsonObject? = runCatching {
        val json = String(Base64.getUrlDecoder().decode(b64), Charsets.UTF_8)
        JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return@runCatching null
            val obj = JsonObject()
            val seen = HashSet<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (!seen.add(name)) return@runCatching null // duplicate key
                when (reader.peek()) {
                    JsonToken.STRING -> obj.addProperty(name, reader.nextString())
                    JsonToken.NUMBER -> obj.addProperty(name, BigDecimal(reader.nextString()))
                    JsonToken.BOOLEAN -> obj.addProperty(name, reader.nextBoolean())
                    JsonToken.NULL -> { reader.nextNull(); obj.add(name, JsonNull.INSTANCE) }
                    else -> return@runCatching null // nested object/array not allowed
                }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) return@runCatching null // trailing garbage
            obj
        }
    }.getOrNull()

    private fun JsonObject.str(k: String): String? =
        get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.uuid(k: String): String? =
        str(k)?.takeIf { UUID_LOWER.matches(it) }

    /**
     * A bare-integer NumericDate only. A token written with a decimal point or an
     * exponent — `1700000000.0`, `1700000000.5`, `1.7e9` — is rejected (scale != 0),
     * matching the FastAPI verifier's `type(x) is int` check; Python parses any such
     * form as a float and rejects it.
     */
    private fun JsonObject.epoch(k: String): Long? {
        val prim = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asJsonPrimitive ?: return null
        val bd = prim.asBigDecimal
        if (bd.scale() != 0) return null
        return runCatching { bd.longValueExact() }.getOrNull()
    }

    companion object {
        const val KID = "mama-ktor-sms-v1"
        const val KID_VERIFY = "mama-ktor-verify-v1"
        const val ISS = "mama-fastapi"
        const val AUD = "mama-ktor-trusted-exit"
        const val PURPOSE = "trusted-exit:sms-request"
        const val PURPOSE_VERIFY = "trusted-exit:verify"
        const val MAX_LIFETIME_SEC = 60L
        const val SKEW_SEC = 5L
        val REQUIRED_CLAIMS = setOf(
            "iss", "aud", "purpose", "sub", "series_id", "period_id",
            "device_id", "contact_id", "jti", "iat", "nbf", "exp",
        )
        val REQUIRED_HEADER_KEYS = setOf("alg", "typ", "kid")
        private val UUID_LOWER = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val CHALLENGE_ID = Regex("^tec-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val B64URL = Regex("^[A-Za-z0-9_-]+$")

        /**
         * Verifier for the challenge-bound `trusted-exit:verify` grant that
         * `POST /trusted-exit/verify` requires (FastAPI `mama-fastapi` PR #14). Same
         * pinned key as the SMS-request verifier; a request grant is rejected here
         * (wrong kid/purpose/claims) and a verify grant is rejected on `/request`.
         */
        fun forVerify(
            pinnedPublicKeyBase64: String,
            now: () -> Long = { System.currentTimeMillis() / 1000 },
        ) = GrantVerifier(
            pinnedPublicKeyBase64,
            expectedKid = KID_VERIFY,
            expectedPurpose = PURPOSE_VERIFY,
            requireChallengeId = true,
            now = now,
        )
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
    /** Present only on a `trusted-exit:verify` grant: the `tec-<uuid>` it authorizes. */
    val challengeId: String? = null,
)
