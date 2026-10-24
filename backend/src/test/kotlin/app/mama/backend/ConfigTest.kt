package app.mama.backend

import app.mama.backend.trustedexit.MockSmsProvider
import app.mama.backend.trustedexit.SmsProvider
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The hard TEST/PRODUCTION split: production never falls back to the mock, and a
 * missing key in production is a fatal configuration error.
 */
class ConfigTest {

    private val realMarker: SmsProvider = MockSmsProvider() // a sentinel "real" provider
    private val mockMarker: SmsProvider = MockSmsProvider()
    private fun resolve(mode: BackendMode, key: String?) =
        resolveSmsProvider(mode, key, real = { realMarker }, mock = { mockMarker })

    @Test
    fun `production without a key fails fast`() {
        val e = assertFailsWith<SmsConfigException> { resolve(BackendMode.PRODUCTION, null) }
        assert(e.message!!.contains("MAMA_SMSRU_API_KEY"))
        assertFailsWith<SmsConfigException> { resolve(BackendMode.PRODUCTION, "   ") }
    }

    @Test
    fun `production with a key uses the real provider, never the mock`() {
        assertSame(realMarker, resolve(BackendMode.PRODUCTION, "a-real-key"))
    }

    @Test
    fun `test without a key uses the mock`() {
        assertSame(mockMarker, resolve(BackendMode.TEST, null))
    }

    @Test
    fun `test with a key uses the real provider`() {
        assertSame(realMarker, resolve(BackendMode.TEST, "a-real-key"))
    }

    @Test
    fun `mode defaults to production when unset or unknown`() {
        assertEquals(BackendMode.PRODUCTION, BackendConfig.mode { null })
        assertEquals(BackendMode.PRODUCTION, BackendConfig.mode { "" })
        assertEquals(BackendMode.PRODUCTION, BackendConfig.mode { "prod" })
        assertEquals(BackendMode.PRODUCTION, BackendConfig.mode { "whatever" })
    }

    @Test
    fun `mode is test only for the known test aliases`() {
        for (v in listOf("test", "TEST", "dev", "development", "debug", "ci")) {
            assertEquals(BackendMode.TEST, BackendConfig.mode { v }, "mode for $v")
        }
    }
}
