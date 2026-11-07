package app.mama.trustedexit

import app.mama.account.AccountHttpTransport
import app.mama.account.AccountSession
import app.mama.account.HttpReply
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-side proof of the Trusted Exit provisioning client against a fake FastAPI
 * setup server. Robolectric only for a real org.json. Proves the full build
 * order, that the (series, period) context is persisted exactly once on success,
 * that a mid-sequence failure stores nothing, that readiness stays NOT ready
 * until a provider verification exists, that a 401 transparently re-auths, and
 * that no session is fail-closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpTrustedExitSetupTest {

    private var token = "tok-1"
    private var forgetCount = 0
    private val session = object : TrustedExitSessionSource {
        override fun ensureSession(): AccountSession? =
            if (token.isEmpty()) null else AccountSession("user-1", UUID.randomUUID().toString(), token)
        override fun forgetSession() { forgetCount++; token = "tok-2" }
    }

    private var storedContext: ExitContext? = null
    private val server = FakeSetupServer()

    private fun setup() = HttpTrustedExitSetup(
        transport = server, session = session, context = { storedContext = it },
    )

    private val spec = ExitSetupSpec(
        contactName = "Мама", contactPhone = "+31611112222",
        durationDays = 3, startTime = "22:00:00", endTime = "06:00:00",
        timezone = "Europe/Amsterdam", brickMode = true,
        periodKey = "te-" + UUID.randomUUID(),
        beginsAtIso = "2026-11-06T22:00:00+00:00", endsAtIso = "2026-11-07T06:00:00+00:00",
    )

    @Test
    fun provision_happyPath_storesContext_and_readiness_notReadyUntilVerified() {
        val r = setup().provision(spec) as HttpTrustedExitSetup.Result.Provisioned
        assertEquals(listOf("contacts", "series", "contact", "activate", "periods"), server.order)
        assertNotNull(storedContext)
        assertEquals(r.seriesId, storedContext!!.seriesId)
        assertEquals(r.periodId, storedContext!!.periodId)
        // Contact is stored, NOT verified -> not a usable exit yet.
        assertEquals(false, r.readiness!!.contactVerified)
        assertEquals(false, r.readiness!!.ready)
    }

    @Test
    fun provision_readiness_reflectsProviderVerification() {
        server.verified = true
        val r = setup().provision(spec) as HttpTrustedExitSetup.Result.Provisioned
        assertEquals(true, r.readiness!!.contactVerified)
        assertEquals(true, r.readiness!!.ready)
    }

    @Test
    fun provision_midSequenceFailure_storesNoContext() {
        server.failActivate = true
        val r = setup().provision(spec)
        assertTrue(r is HttpTrustedExitSetup.Result.Failed)
        assertEquals("activate", (r as HttpTrustedExitSetup.Result.Failed).reason)
        assertNull(storedContext)
    }

    @Test
    fun provision_failsClosed_withoutSession() {
        token = ""
        val r = setup().provision(spec)
        assertTrue(r is HttpTrustedExitSetup.Result.Failed)
        assertEquals("no-session", (r as HttpTrustedExitSetup.Result.Failed).reason)
        assertEquals(0, server.order.size)
    }

    @Test
    fun provision_reauthenticates_on_401() {
        server.contactsFirst401 = true
        val r = setup().provision(spec)
        assertTrue(r is HttpTrustedExitSetup.Result.Provisioned)
        assertEquals(1, forgetCount)
    }

    @Test
    fun provision_refuses_malformed_server_owned_ids_without_crashing() {
        for (field in listOf("contact", "series", "period")) {
            server.badIdFor = field
            val result = setup().provision(spec)
            assertTrue("must fail on $field", result is HttpTrustedExitSetup.Result.Failed)
            assertNull("no context stored for $field", storedContext)
            server.badIdFor = null
            server.order.clear()
        }
    }

    @Test
    fun provision_refuses_context_persistence_failure() {
        val broken = HttpTrustedExitSetup(
            transport = server, session = session,
            context = { throw java.io.IOException("simulated disk full") },
        )
        val result = broken.provision(spec)
        assertEquals(HttpTrustedExitSetup.Result.Failed("context-storage"), result)
        assertNull(storedContext)
    }

    // --- fake FastAPI setup server -------------------------------------------

    private inner class FakeSetupServer : AccountHttpTransport {
        val order = mutableListOf<String>()
        var verified = false
        var badIdFor: String? = null
        var failActivate = false
        var contactsFirst401 = false
        private var contacts401Served = false

        override fun postJson(path: String, jsonBody: String, bearer: String?): HttpReply {
            if (bearer.isNullOrEmpty()) return HttpReply(401, "{}")
            return when {
                path == "/v1/setup/contacts" -> {
                    if (contactsFirst401 && !contacts401Served) { contacts401Served = true; return HttpReply(401, "{}") }
                    order += "contacts"
                    HttpReply(200, JSONObject().put("contact_id", if (badIdFor == "contact") "not-a-uuid" else UUID.randomUUID().toString()).toString())
                }
                path == "/v1/setup/series" -> {
                    order += "series"
                    HttpReply(200, JSONObject()
                        .put("series_id", if (badIdFor == "series") "invalid" else UUID.randomUUID().toString()).put("status", "draft").toString())
                }
                path.endsWith("/contact") -> {
                    order += "contact"
                    HttpReply(200, JSONObject().put("series_id", "s").put("contact_id", "c").toString())
                }
                path.endsWith("/activate") -> {
                    order += "activate"
                    if (failActivate) HttpReply(409, "{}")
                    else HttpReply(200, JSONObject().put("series_id", "s").put("status", "active").toString())
                }
                path.endsWith("/periods") -> {
                    order += "periods"
                    HttpReply(200, JSONObject()
                        .put("period_id", if (badIdFor == "period") "" else UUID.randomUUID().toString()).put("status", "registered").toString())
                }
                else -> HttpReply(404, "{}")
            }
        }

        override fun getJson(path: String, bearer: String?): HttpReply {
            if (bearer.isNullOrEmpty()) return HttpReply(401, "{}")
            if (!path.endsWith("/readiness")) return HttpReply(404, "{}")
            return HttpReply(200, JSONObject()
                .put("active", true).put("contact_selected", true)
                .put("contact_verified", verified)
                .put("has_registered_period", true)
                .put("ready", verified).toString())
        }
    }
}
