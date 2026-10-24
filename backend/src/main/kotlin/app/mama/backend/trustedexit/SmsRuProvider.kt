package app.mama.backend.trustedexit

import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * The real SMS provider: SMS.RU. It runs **only on the backend**. The API key
 * (`api_id`) is read from the environment and never appears in Git, the APK or
 * the client. The code is sent in the message body and is **never logged** —
 * only the (masked) phone and the provider's status are.
 *
 * Uses the documented `https://sms.ru/sms/send` endpoint with `json=1`. The
 * network call goes through an injectable [transport] so the request building
 * and the response mapping are unit-tested without the network or a real key.
 */
class SmsRuProvider(
    private val apiId: String? = System.getenv(ENV_SMSRU_API_ID),
    private val from: String? = System.getenv(ENV_SMS_SENDER),
    private val endpoint: String = "https://sms.ru/sms/send",
    private val transport: HttpTransport = JdkHttpTransport(),
    private val message: (String) -> String = { code ->
        "MAMA: код доверенного выхода $code. Никому не пересылайте его, кроме человека, которому вы помогаете выйти из режима."
    },
) : SmsProvider {

    override val label: String get() = "sms.ru"

    override fun configured(): Boolean = !apiId.isNullOrBlank()

    override fun send(phone: String, code: String): SmsResult {
        if (apiId.isNullOrBlank()) {
            // Fail-closed: without a configured key nothing is sent and no code is created.
            log.error("SMS.RU api_id is not configured ($ENV_SMSRU_API_ID); refusing to send")
            return SmsResult.Failed("sms provider is not configured")
        }
        val to = normalize(phone)
        val form = buildMap {
            put("api_id", apiId)
            put("to", to)
            put("msg", message(code))
            put("json", "1")
            if (!from.isNullOrBlank()) put("from", from!!)
        }
        val reply = runCatching { transport.postForm(endpoint, form) }
            .getOrElse {
                log.warn("SMS.RU request failed for ${mask(to)}: ${it.message}")
                return SmsResult.Failed("sms provider request failed")
            }
        return parse(reply, to)
    }

    /** Maps the SMS.RU JSON reply to a provider result. Never logs the code. */
    private fun parse(reply: HttpReply, to: String): SmsResult {
        if (reply.status !in 200..299) {
            log.warn("SMS.RU HTTP ${reply.status} for ${mask(to)}")
            return SmsResult.Failed("sms provider http ${reply.status}")
        }
        val json = runCatching { JsonParser.parseString(reply.body).asJsonObject }
            .getOrElse { return SmsResult.Failed("sms provider returned non-JSON") }
        val topOk = json.get("status")?.asString == "OK"
        // Per-recipient status lives under sms["<number>"].status, with its sms_id.
        val perSms = json.getAsJsonObject("sms")?.entrySet()?.firstOrNull()?.value?.asJsonObject
        val smsOk = perSms?.get("status")?.asString == "OK"
        if (topOk && (perSms == null || smsOk)) {
            val smsId = perSms?.get("sms_id")?.asString
            log.info("SMS.RU accepted a Trusted Exit code for ${mask(to)} (sms_id ${smsId ?: "n/a"})")
            return SmsResult.Accepted(DeliveryStatus.SMS_ACCEPTED, smsId)
        }
        val detail = perSms?.get("status_text")?.asString
            ?: json.get("status_text")?.asString
            ?: "status_code ${json.get("status_code")?.asString ?: "?"}"
        // The provider's own text stays in the (masked) server log; the caller — and
        // ultimately the Android client — gets only a generic reason, so no provider
        // text can carry anything sensitive outward.
        log.warn("SMS.RU refused for ${mask(to)}: $detail")
        return SmsResult.Failed("sms provider refused")
    }

    private companion object {
        val log = LoggerFactory.getLogger(SmsRuProvider::class.java)

        /** Digits only; a leading 8 on an 11-digit RU number becomes 7, as SMS.RU expects. */
        fun normalize(phone: String): String {
            val digits = phone.filter { it.isDigit() }
            return if (digits.length == 11 && digits.startsWith("8")) "7" + digits.drop(1) else digits
        }

        /** For logs: keep only the last two digits. */
        fun mask(number: String): String =
            if (number.length <= 2) "**" else "*".repeat(number.length - 2) + number.takeLast(2)
    }
}

/** A minimal HTTP seam so the provider is testable without the network. */
interface HttpTransport {
    fun postForm(url: String, form: Map<String, String>): HttpReply
}

data class HttpReply(val status: Int, val body: String)

/** The JDK HTTP client (no extra dependency), posting a URL-encoded form. */
class JdkHttpTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build(),
    private val timeout: Duration = Duration.ofSeconds(15),
) : HttpTransport {
    override fun postForm(url: String, form: Map<String, String>): HttpReply {
        val body = form.entries.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v)}"
        }
        val request = HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return HttpReply(response.statusCode(), response.body() ?: "")
    }

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
