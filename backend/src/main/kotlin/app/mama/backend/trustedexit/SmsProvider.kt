package app.mama.backend.trustedexit

import java.util.concurrent.atomic.AtomicInteger

/** The environment variable holding the SMS.RU `api_id`. Server-side only. */
const val ENV_SMSRU_API_ID = "MAMA_SMSRU_API_ID"

/** Optional approved sender name (SMS.RU `from`). */
const val ENV_SMS_SENDER = "MAMA_SMS_SENDER"

/**
 * The SMS provider seam. It runs **only on the backend**, never on the device.
 * The real provider (SMS.RU) reads its `api_id` from [ENV_SMSRU_API_ID] in the
 * environment and never logs the code. The mock exists only for tests.
 */
interface SmsProvider {
    /** Sends the Trusted Exit [code] to [phone]. Returns only the outcome, never the code back. */
    fun send(phone: String, code: String): SmsResult

    /** A safe label for readiness and logs. Never a secret. */
    val label: String

    /** Whether the provider is configured to deliver (readiness). Never reveals the key. */
    fun configured(): Boolean
}

sealed interface SmsResult {
    /** The provider accepted the message. [smsId] is the provider's message id, when given. */
    data class Accepted(
        val status: DeliveryStatus = DeliveryStatus.SMS_ACCEPTED,
        val smsId: String? = null,
    ) : SmsResult

    /** The provider refused the message; no challenge is created. */
    data class Failed(val reason: String) : SmsResult
}

/** Delivery status the backend reports. Never carries the code. */
enum class DeliveryStatus {
    REQUEST_CREATED,
    SMS_ACCEPTED,
    SMS_FAILED,
    SMS_DELIVERED,
    SMS_UNDELIVERED,
}

/**
 * Development/test provider: it does not send anything. It records the last
 * (phone, code) in-process so tests can read the code the way only a test
 * harness can — the API itself never exposes it. A failure can be forced for
 * the provider-failure path. Used only by tests (direct injection), never
 * selected for a running server.
 */
class MockSmsProvider(
    private val result: SmsResult = SmsResult.Accepted(),
) : SmsProvider {
    private val count = AtomicInteger(0)

    @Volatile var lastPhone: String? = null
        private set

    /** The last code handed to the mock — for tests only; a real provider exposes nothing. */
    @Volatile var lastCode: String? = null
        private set

    val calls: Int get() = count.get()

    override val label: String get() = "mock"

    override fun configured(): Boolean = true

    override fun send(phone: String, code: String): SmsResult {
        val n = count.incrementAndGet()
        if (result is SmsResult.Accepted) {
            lastPhone = phone
            lastCode = code
            return result.copy(smsId = result.smsId ?: "mock-sms-$n")
        }
        return result
    }
}
