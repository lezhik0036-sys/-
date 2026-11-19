package app.mama.backend.trustedexit

import java.util.concurrent.atomic.AtomicInteger

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

    /** The last confirm link handed to the mock — for tests only (null for the OTP path). */
    @Volatile var lastConfirmUrl: String? = null
        private set

    val calls: Int get() = count.get()

    override val label: String get() = "mock"

    override fun configured(): Boolean = true

    override fun send(phone: String, code: String, confirmUrl: String?): SmsResult {
        val n = count.incrementAndGet()
        if (result is SmsResult.Accepted) {
            lastPhone = phone
            lastCode = code
            lastConfirmUrl = confirmUrl
            return result.copy(smsId = result.smsId ?: "mock-sms-$n")
        }
        return result
    }
}
