package app.mama.backend

import app.mama.backend.trustedexit.ENV_SMSRU_API_ID
import app.mama.backend.trustedexit.SmsProvider
import app.mama.backend.trustedexit.SmsRuProvider

/**
 * Provider selection is fail-closed and has no silent fallback to the mock:
 *
 * - `MAMA_SMSRU_API_ID` present → the real SMS.RU provider.
 * - absent → [SmsConfigException]. The caller fails fast and Trusted Exit does
 *   not start. There is never an automatic mock fallback.
 *
 * The mock provider is used only by tests, by direct injection — never chosen
 * here for a running server.
 */
class SmsConfigException(message: String) : IllegalStateException(message)

/**
 * Picks the SMS provider from [apiId] (read from [ENV_SMSRU_API_ID] by the
 * caller). A blank key throws [SmsConfigException] — fail closed. [real] is
 * injectable for tests.
 */
fun resolveSmsProvider(
    apiId: String?,
    real: () -> SmsProvider = { SmsRuProvider() },
): SmsProvider {
    if (apiId.isNullOrBlank()) {
        throw SmsConfigException(
            "$ENV_SMSRU_API_ID is not set: refusing to start Trusted Exit without a real SMS provider (fail " +
                "closed). Set $ENV_SMSRU_API_ID in the server environment/secrets — never in Git, the APK, Gradle, " +
                "BuildConfig or Android resources. The mock provider is only for tests.",
        )
    }
    return real()
}
