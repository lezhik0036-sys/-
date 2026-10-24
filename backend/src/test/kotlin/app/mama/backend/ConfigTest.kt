package app.mama.backend

import app.mama.backend.trustedexit.MockSmsProvider
import app.mama.backend.trustedexit.SmsProvider
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Fail-closed provider selection: a real provider only when the key is present,
 * otherwise an exception — never a silent mock fallback.
 */
class ConfigTest {

    private val realMarker: SmsProvider = MockSmsProvider() // sentinel "real" provider
    private fun resolve(key: String?) = resolveSmsProvider(key, real = { realMarker })

    @Test
    fun `no key fails closed`() {
        val e = assertFailsWith<SmsConfigException> { resolve(null) }
        assert(e.message!!.contains("MAMA_SMSRU_API_ID"))
        assertFailsWith<SmsConfigException> { resolve("") }
        assertFailsWith<SmsConfigException> { resolve("   ") }
    }

    @Test
    fun `a key selects the real provider`() {
        assertSame(realMarker, resolve("a-real-api-id"))
    }
}
