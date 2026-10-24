package app.mama.backend

import app.mama.backend.trustedexit.ENV_SMSRU_API_ID
import app.mama.backend.trustedexit.SmsResult
import kotlin.system.exitProcess

/**
 * A one-shot SMS smoke check: send exactly ONE test SMS through the configured
 * provider to a given number, to confirm the SMS.RU integration and the server
 * secret before the first real two-phone Trusted Exit test.
 *
 * Run it ON THE SERVER, where `MAMA_SMSRU_API_ID` already lives in the
 * environment/secrets. The key is never passed on the command line and never
 * printed. It is NOT an HTTP endpoint on purpose — there is nothing to protect
 * or leave exposed; it runs once and exits.
 *
 * The target number comes from the first argument or `MAMA_SMOKE_TO`. Output is
 * deliberately minimal — a masked number and OK/FAILED — and never prints the
 * api_id, the full number or the 5-digit code (the code only ever reaches the
 * phone that receives the SMS).
 *
 * Exit codes: 0 accepted, 1 provider refused, 64 usage, 78 misconfiguration.
 */
fun main(args: Array<String>) {
    val to = (args.firstOrNull() ?: System.getenv("MAMA_SMOKE_TO"))?.trim().orEmpty()
    if (to.isEmpty()) {
        System.err.println(
            "usage: smokeSms <phone>  (or set MAMA_SMOKE_TO). " +
                "The SMS.RU api_id must be in $ENV_SMSRU_API_ID in the server environment — never on the command line.",
        )
        exitProcess(64) // EX_USAGE
    }
    // Same provider selection as the server: no key fails closed here too, and
    // never uses the mock.
    val provider = try {
        resolveSmsProvider(System.getenv(ENV_SMSRU_API_ID))
    } catch (e: SmsConfigException) {
        System.err.println(e.message)
        exitProcess(78) // EX_CONFIG
    }
    val masked = maskPhone(to)
    println("MAMA smoke: sending one test SMS to $masked via ${provider.label}")
    // A fixed, obvious test code. It is delivered in the SMS but never printed here.
    return when (val r = provider.send(to, SMOKE_CODE)) {
        is SmsResult.Accepted -> {
            println("MAMA smoke: OK — provider accepted the message (${r.status}). Check phone $masked for the test SMS.")
            exitProcess(0)
        }
        is SmsResult.Failed -> {
            System.err.println("MAMA smoke: FAILED — ${r.reason}. See the backend log for the masked detail.")
            exitProcess(1)
        }
    }
}

private const val SMOKE_CODE = "01234"

/** Digits only, last two kept, as in the provider's log masking. */
private fun maskPhone(number: String): String {
    val digits = number.filter { it.isDigit() }
    return if (digits.length <= 2) "**" else "*".repeat(digits.length - 2) + digits.takeLast(2)
}
