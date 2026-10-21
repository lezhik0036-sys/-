package app.mama.trustedexit

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Trusted Exit delivery transport: the device asks a backend to deliver
 * the code, the backend returns only a status, and delivery never touches a
 * SIM or SmsManager. These tests drive the facade and the debug backend with
 * fakes and an injected connectivity flag, so they do not depend on telephony
 * at all — which is the point of the change (a no-SIM, Wi-Fi-only device must
 * still work).
 */
@RunWith(RobolectricTestRunner::class)
class TrustedExitDeliveryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun reset() {
        TrustedExit.online = { Connectivity.online(it) }
        TrustedExit.api = DebugTrustedExitApi()
    }

    private fun req(key: String = "s1:1") =
        ExitCodeRequest(sessionId = "s1", kind = "END_SESSION", idempotencyKey = key, debugMessage = "MAMA: код 12345")

    private fun deliver(online: Boolean, api: TrustedExitApi): DeliveryResult {
        TrustedExit.online = { online }
        TrustedExit.api = api
        return TrustedExit.deliver(context, req())
    }

    private class Provider(var status: DeliveryStatus) : TrustedExitSmsService {
        var calls = 0
        override fun sendCode(requestId: String, contactPhone: String, message: String): DeliveryStatus {
            calls++
            return status
        }
    }

    @Test
    fun `online, provider accepts - the code is accepted for delivery`() {
        val provider = Provider(DeliveryStatus.SMS_ACCEPTED)
        val result = deliver(online = true, api = DebugTrustedExitApi(sms = provider))
        assertTrue(result is DeliveryResult.Accepted)
        assertEquals(1, provider.calls)
    }

    @Test
    fun `offline - nothing is generated or sent, fail-closed`() {
        val provider = Provider(DeliveryStatus.SMS_ACCEPTED)
        val result = deliver(online = false, api = DebugTrustedExitApi(sms = provider))
        assertEquals(DeliveryResult.Offline, result)
        assertEquals("the provider is never called offline", 0, provider.calls)
    }

    @Test
    fun `provider failure - reported as Failed, never as sent`() {
        val provider = Provider(DeliveryStatus.SMS_FAILED)
        val result = deliver(online = true, api = DebugTrustedExitApi(sms = provider))
        assertTrue(result is DeliveryResult.Failed)
    }

    @Test
    fun `double tap within the window reuses the same request, one SMS`() {
        val provider = Provider(DeliveryStatus.SMS_ACCEPTED)
        val api = DebugTrustedExitApi(sms = provider, idempotencyWindowMs = 60_000, clockMs = { 1_000 })
        val first = api.requestExitCode(req("s1:1")) as DeliveryResult.Accepted
        val second = api.requestExitCode(req("s1:1")) as DeliveryResult.Accepted
        assertEquals(first.requestId, second.requestId)
        assertEquals("the provider is used once for the same request", 1, provider.calls)
    }

    @Test
    fun `a later request after the window is a new request`() {
        val provider = Provider(DeliveryStatus.SMS_ACCEPTED)
        var now = 1_000L
        val api = DebugTrustedExitApi(sms = provider, idempotencyWindowMs = 60_000, clockMs = { now })
        val first = api.requestExitCode(req("s1:1")) as DeliveryResult.Accepted
        now += 61_000
        val second = api.requestExitCode(req("s1:1")) as DeliveryResult.Accepted
        assertNotEquals(first.requestId, second.requestId)
        assertEquals(2, provider.calls)
    }

    @Test
    fun `the requestId is an opaque id, never the code`() {
        val provider = Provider(DeliveryStatus.SMS_ACCEPTED)
        val result = deliver(online = true, api = DebugTrustedExitApi(sms = provider)) as DeliveryResult.Accepted
        assertTrue(result.requestId.startsWith("te-"))
    }

    @Test
    fun `default facade api is the debug backend, replaceable for production`() {
        assertSame(DebugTrustedExitApi::class.java, TrustedExit.api.javaClass)
    }
}
