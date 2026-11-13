package app.mama.backend.trustedexit

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
    /**
     * Sends the Trusted Exit [code] to [phone]. [confirmUrl], when non-null, is the
     * contact-verification confirm-page link to include in the message (carrying only
     * the single-use challenge token, never the code); the OTP-exit path passes none
     * and its message is unchanged. Returns only the outcome, never the code back.
     */
    fun send(phone: String, code: String, confirmUrl: String? = null): SmsResult

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

    /** No confirmed acceptance. Transport failures can leave delivery unknown. */
    data class Failed(val reason: String, val deliveryUncertain: Boolean = false) : SmsResult
}

/** Delivery status the backend reports. Never carries the code. */
enum class DeliveryStatus {
    REQUEST_CREATED,
    SMS_ACCEPTED,
    SMS_FAILED,
    SMS_DELIVERED,
    SMS_UNDELIVERED,
}

