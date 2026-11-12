package app.mama.backend.trustedexit

import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The private, server-to-server contact-verification DISPATCH endpoint
 * (FastAPI -> this Ktor worker) — the half the FastAPI
 * `RealKtorContactVerificationProvider` (mama-fastapi #70) calls.
 *
 * FastAPI issues a single-use, short one-time code bound to a verification
 * challenge, stores only its hash, and asks this worker to DELIVER that code to
 * the trusted contact's phone over the real SMS provider. The trusted person then
 * enters the code and FastAPI verifies it server-side; this worker only delivers.
 * It NEVER verifies, NEVER returns the code to FastAPI or Android, and NEVER logs
 * it (the provider masks the phone and omits the code).
 *
 * Authentication is a domain-separated MAC the FastAPI adapter computes over the
 * EXACT request body — `HMAC-SHA256(secret, DOMAIN + rawBody)` — with a secret
 * configured independently on both servers, distinct from the OTP ingest key and
 * from any Ed25519 key, never shipped to the client.
 *
 * Disabled by default: the route is mounted only when a >= 32-byte dispatch secret
 * AND a real SMS provider are configured, so a fresh deployment (and all of CI)
 * dispatches nothing. No code here sends a real SMS by itself; wiring the real
 * provider + secret is an owner action, gated behind owner approval for paid SMS.
 */

/**
 * The user-facing Russian SMS for trusted-contact verification. **FINAL WORDING PENDING
 * OWNER REVIEW (issue #78).** With a [confirmUrl] the contact is asked to open the link
 * and enter the [code]; without one (no public confirm host configured yet) the code-only
 * message is used. The code is NEVER placed in the URL. Single source for the Server
 * wiring and the deliverability test so the two cannot drift.
 */
fun contactVerificationSmsMessage(code: String, confirmUrl: String?): String =
    if (confirmUrl != null) {
        "MAMA: код подтверждения — $code. Откройте ссылку и введите код, чтобы подтвердить, " +
            "что этот номер ваш: $confirmUrl Никому не сообщайте код."
    } else {
        "MAMA: код подтверждения номера — $code. Введите его, чтобы подтвердить, что этот номер " +
            "принадлежит вам как доверенному контакту. Никому больше не сообщайте этот код."
    }

/** Verifies the FastAPI dispatch MAC over `DOMAIN + rawBody` (constant-time). */
class ContactVerificationDispatchMac(secret: ByteArray) {
    private val key: SecretKeySpec? =
        if (secret.size >= MIN_SECRET_BYTES) SecretKeySpec(secret.copyOf(), "HmacSHA256") else null

    val configured: Boolean get() = key != null

    /** True iff [macHex] is exactly the lowercase/uppercase hex MAC over `DOMAIN + body`. */
    fun verify(body: ByteArray, macHex: String?): Boolean {
        val k = key ?: return false
        val given = macHex?.let(::decodeHex) ?: return false
        val expected = Mac.getInstance("HmacSHA256").apply { init(k) }.run {
            update(DOMAIN)
            doFinal(body)
        }
        // Constant-time; also length-safe (isEqual handles unequal lengths).
        return MessageDigest.isEqual(expected, given)
    }

    companion object {
        const val MIN_SECRET_BYTES = 32
        const val MAX_BODY_BYTES = 8192
        const val PATH = "/contact-verification/dispatch"
        const val HEADER = "x-mama-dispatch-mac"

        /** Exact purpose-separation prefix shared with the FastAPI adapter (trailing LF). */
        val DOMAIN: ByteArray =
            "MAMA/contact-verification-dispatch/v1\n".toByteArray(Charsets.US_ASCII)

        /** Builds from the configured shared secret's UTF-8 bytes; blank/short => unconfigured. */
        fun fromEnv(value: String?): ContactVerificationDispatchMac =
            ContactVerificationDispatchMac(value.orEmpty().toByteArray(Charsets.UTF_8))

        private fun decodeHex(s: String): ByteArray? {
            if (s.isEmpty() || s.length % 2 != 0) return null
            val out = ByteArray(s.length / 2)
            var i = 0
            while (i < s.length) {
                val hi = Character.digit(s[i], 16)
                val lo = Character.digit(s[i + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i / 2] = ((hi shl 4) or lo).toByte()
                i += 2
            }
            return out
        }
    }
}

/** The validated dispatch request. Identity/bindings are the MAC-authenticated FastAPI's. */
data class ContactVerificationDispatchRequest(
    val userId: String,
    val contactId: String,
    val phone: String,
    val challengeId: String,
    val code: String,
    /** The confirm-page link to include in the SMS, or null for a code-only SMS. It
     *  carries only the single-use challenge token (never the code); FastAPI already
     *  pins it to https + the api.mama-app.ru host, and this worker re-checks it is a
     *  safe https URL before putting it in a message (defence in depth). */
    val confirmUrl: String?,
)

/** How the SMS send turned out, independent of the shared [SmsResult] (keeps the OTP path untouched). */
enum class DispatchSend {
    /** The provider confirmed acceptance. */
    ACCEPTED,

    /** The provider PROVABLY did not send (refused / definite non-dispatch). */
    REFUSED,

    /** Ambiguous (timeout / lost ACK): maybe sent. Must NEVER be auto-resent. */
    UNCERTAIN,
}

/** Outcome of a dispatch call; the route maps it to the HTTP response. */
sealed interface DispatchOutcome {
    /** MAC missing/invalid, or the worker is unconfigured: HTTP 401 (definite non-dispatch). */
    data object Unauthorized : DispatchOutcome

    /** Oversized/malformed body or invalid fields: HTTP 400 (definite non-dispatch). */
    data class BadRequest(val reason: String) : DispatchOutcome

    /** Authenticated and handled: HTTP 200 with this status
     *  (`SMS_ACCEPTED` | `FAILED` | `UNKNOWN`), per the #70 taxonomy. */
    data class Status(val status: String) : DispatchOutcome
}

/**
 * Authenticates, validates and delivers one contact-verification code, idempotently
 * by challenge id so a retry or a replayed request can never double a paid SMS.
 *
 * The cross-restart anti-double-send guard is the FastAPI outbox, which commits the
 * attempt before dispatch and NEVER auto-resends a live challenge; this in-process
 * idempotency is the belt-and-suspenders for same-process retries/replays.
 */
class ContactVerificationDispatchService(
    private val mac: ContactVerificationDispatchMac,
    private val send: (phone: String, code: String, confirmUrl: String?) -> DispatchSend,
    private val idempotency: DispatchIdempotencyStore = InMemoryDispatchIdempotencyStore(),
) {
    val configured: Boolean get() = mac.configured

    fun dispatch(rawBody: ByteArray, macHex: String?): DispatchOutcome {
        if (!mac.configured) return DispatchOutcome.Unauthorized
        if (rawBody.size > ContactVerificationDispatchMac.MAX_BODY_BYTES) {
            // Oversized: reject before parsing; a definite non-dispatch, never a blind send.
            return DispatchOutcome.BadRequest("body too large")
        }
        if (!mac.verify(rawBody, macHex)) return DispatchOutcome.Unauthorized
        val req = parse(rawBody) ?: return DispatchOutcome.BadRequest("invalid dispatch body")

        // Idempotent by the single-use challenge id: a repeat returns the recorded
        // outcome and never re-sends (anti double-paid-SMS, including a replay of an
        // uncertain attempt).
        idempotency.get(req.challengeId)?.let { return DispatchOutcome.Status(it) }

        val status = when (send(req.phone, req.code, req.confirmUrl)) {
            DispatchSend.ACCEPTED -> "SMS_ACCEPTED"
            DispatchSend.REFUSED -> "FAILED"
            DispatchSend.UNCERTAIN -> "UNKNOWN"
        }
        idempotency.put(req.challengeId, status)
        return DispatchOutcome.Status(status)
    }

    private fun parse(rawBody: ByteArray): ContactVerificationDispatchRequest? = runCatching {
        val json = JsonParser.parseString(rawBody.toString(Charsets.UTF_8)).asJsonObject
        val userId = json.get("user_id")?.asString ?: return null
        val contactId = json.get("contact_id")?.asString ?: return null
        val phone = json.get("phone")?.asString ?: return null
        val challengeId = json.get("challenge_id")?.asString ?: return null
        val code = json.get("code")?.asString ?: return null
        if (!isCanonicalUuid(userId) || !isCanonicalUuid(contactId)) return null
        if (phone.isBlank() || phone.length > 32) return null
        if (challengeId.isBlank() || challengeId.length > 128) return null
        if (!CODE.matches(code)) return null
        // confirm_url is OPTIONAL (absent -> code-only SMS). When present it must be a
        // safe https link (printable ASCII, no spaces/control, bounded) before it can
        // go into a message — defence in depth over FastAPI's host allowlist.
        val confirmUrl = json.get("confirm_url")?.let { if (it.isJsonNull) null else it.asString }
        if (confirmUrl != null && !SAFE_HTTPS_URL.matches(confirmUrl)) return null
        ContactVerificationDispatchRequest(userId, contactId, phone, challengeId, code, confirmUrl)
    }.getOrNull()

    companion object {
        /** A short, human-enterable numeric one-time code. */
        private val CODE = Regex("^[0-9]{4,8}$")

        /** A safe https link to place in an SMS: https:// + printable ASCII only, bounded. */
        private val SAFE_HTTPS_URL = Regex("^https://[\\x21-\\x7E]{1,500}$")

        private fun isCanonicalUuid(s: String): Boolean =
            runCatching { UUID.fromString(s).toString() == s.lowercase() }.getOrDefault(false)

        /**
         * Adapts a real [SmsProvider] into the dispatch send taxonomy. The provider's
         * binary accepted/failed is mapped to ACCEPTED/REFUSED; a provider that can
         * signal transport uncertainty should map it to UNCERTAIN (so FastAPI records
         * `unknown` and never auto-resends). [SmsRuProvider] collapses transport errors
         * into `Failed` today, so this mapping reports REFUSED for them until the
         * provider surfaces uncertainty — a deliberately isolated follow-up.
         */
        fun fromSmsProvider(sms: SmsProvider): (String, String, String?) -> DispatchSend = { phone, code, confirmUrl ->
            when (sms.send(phone, code, confirmUrl)) {
                is SmsResult.Accepted -> DispatchSend.ACCEPTED
                is SmsResult.Failed -> DispatchSend.REFUSED
            }
        }
    }
}

/** Remembers each challenge's dispatch outcome so a retry/replay never re-sends. */
interface DispatchIdempotencyStore {
    fun get(challengeId: String): String?
    fun put(challengeId: String, status: String)
}

/**
 * Bounded, process-local idempotency. The authoritative cross-restart guard is the
 * FastAPI outbox; a production worker may back this with a TTL cache or the DB.
 */
class InMemoryDispatchIdempotencyStore(private val max: Int = 10_000) : DispatchIdempotencyStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(challengeId: String): String? = map[challengeId]
    override fun put(challengeId: String, status: String) {
        if (map.size >= max) map.clear() // crude bound; never unbounded growth
        map.putIfAbsent(challengeId, status)
    }
}
