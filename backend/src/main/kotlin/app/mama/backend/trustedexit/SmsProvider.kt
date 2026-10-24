package app.mama.backend.trustedexit

import java.util.concurrent.atomic.AtomicInteger

/**
 * The SMS provider seam. It runs **only on the backend**, never on the device.
 * A real provider (SMS.RU, phase 2) builds the message text and sends the code
 * to the contact's number, reading its API key from the environment. A real
 * provider must never log the code.
 */
interface SmsProvider {
    /** Sends the Trusted Exit [code] to [phone]. Returns only whether it was accepted. */
    fun send(phone: String, code: String): SmsResult
}

sealed interface SmsResult {
    /** The provider accepted the message for delivery. */
    data class Accepted(val status: DeliveryStatus = DeliveryStatus.SMS_ACCEPTED) : SmsResult

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
 * (phone, code) in-process so route tests can read the code the way only a test
 * harness can — the API itself never exposes it. A failure can be forced for
 * the provider-failure path. This is the mock-SMS provider for phase 1; the
 * real SMS.RU provider replaces it in phase 2 without touching the engine.
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

    override fun send(phone: String, code: String): SmsResult {
        count.incrementAndGet()
        if (result is SmsResult.Accepted) {
            lastPhone = phone
            lastCode = code
        }
        return result
    }
}
