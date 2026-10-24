package app.mama.backend

import app.mama.backend.trustedexit.MockSmsProvider
import app.mama.backend.trustedexit.SmsProvider
import app.mama.backend.trustedexit.SmsRuProvider

/**
 * TEST and PRODUCTION are split hard, with no way for a production server to
 * quietly run on the mock provider.
 *
 * - PRODUCTION (the default when [ENV_MODE] is unset or unknown, so a
 *   misconfiguration never silently downgrades): the real SMS.RU provider is
 *   required. A missing [ENV_API_KEY] is a fatal configuration error — the
 *   server fails fast and Trusted Exit does not start. There is never a mock
 *   fallback.
 * - TEST: the mock provider is allowed when no key is set, so the flow runs
 *   locally and in CI without a real key or real delivery. A key, if present,
 *   still selects the real provider.
 */
enum class BackendMode { PRODUCTION, TEST }

/** Thrown when production is missing the SMS provider key; the server must not start. */
class SmsConfigException(message: String) : IllegalStateException(message)

object BackendConfig {
    const val ENV_MODE = "MAMA_ENV"
    const val ENV_API_KEY = "MAMA_SMSRU_API_KEY"

    fun mode(env: (String) -> String? = System::getenv): BackendMode =
        when (env(ENV_MODE)?.trim()?.lowercase()) {
            "test", "dev", "development", "debug", "ci" -> BackendMode.TEST
            else -> BackendMode.PRODUCTION
        }
}

/**
 * Picks the SMS provider for [mode]. Production demands a real key and never
 * returns the mock; a blank key throws [SmsConfigException] so the caller can
 * fail fast. [real] and [mock] are injectable for tests.
 */
fun resolveSmsProvider(
    mode: BackendMode,
    apiKey: String?,
    real: () -> SmsProvider = { SmsRuProvider() },
    mock: () -> SmsProvider = { MockSmsProvider() },
): SmsProvider = when (mode) {
    BackendMode.PRODUCTION -> {
        if (apiKey.isNullOrBlank()) {
            throw SmsConfigException(
                "${BackendConfig.ENV_API_KEY} is not set: refusing to start Trusted Exit in production without a real SMS " +
                    "provider. Set it in the server environment/secrets (never in Git, the APK or Android " +
                    "resources). For local testing set ${BackendConfig.ENV_MODE}=test to use the mock provider.",
            )
        }
        real()
    }
    BackendMode.TEST -> if (apiKey.isNullOrBlank()) mock() else real()
}
