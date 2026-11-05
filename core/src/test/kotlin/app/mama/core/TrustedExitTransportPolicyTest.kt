package app.mama.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TrustedExitTransportPolicyTest {
    private val p = TrustedExitTransportPolicy

    @Test fun `sim is not needed if validated internet is present`() {
        assertEquals(TrustedExitTransportPolicy.Route.INTERNET, p.routeFor(TrustedExitTransportPolicy.Capabilities(
            validatedInternet = true, cellularService = false)))
    }

    @Test fun `wifi and cellular internet take same server route`() {
        assertEquals(TrustedExitTransportPolicy.Route.INTERNET, p.routeFor(TrustedExitTransportPolicy.Capabilities(
            validatedInternet = true, cellularService = true)))
    }

    @Test fun `sim and signal alone never imply a working offline exit`() {
        assertEquals(TrustedExitTransportPolicy.Route.UNAVAILABLE, p.routeFor(TrustedExitTransportPolicy.Capabilities(
            validatedInternet = false, cellularService = true)))
    }

    @Test fun `carrier route requires independently deployed secure gateway`() {
        assertEquals(TrustedExitTransportPolicy.Route.AUTHENTICATED_CARRIER, p.routeFor(TrustedExitTransportPolicy.Capabilities(
            validatedInternet = false, cellularService = true,
            carrierFallbackReady = true)))
    }

    @Test fun `declared carrier gateway cannot work without carrier signal`() {
        assertEquals(TrustedExitTransportPolicy.Route.UNAVAILABLE, p.routeFor(TrustedExitTransportPolicy.Capabilities(
            validatedInternet = false, cellularService = false,
            carrierFallbackReady = true)))
    }

    @Test fun `internet always takes priority over carrier fallback`() {
        assertEquals(TrustedExitTransportPolicy.Route.INTERNET, p.routeFor(TrustedExitTransportPolicy.Capabilities(
            validatedInternet = true, cellularService = true,
            carrierFallbackReady = true)))
    }

    @Test fun `no sim and no internet has no server verification path`() {
        assertEquals(TrustedExitTransportPolicy.Route.UNAVAILABLE, p.routeFor(TrustedExitTransportPolicy.Capabilities(
            validatedInternet = false, cellularService = false)))
    }

    @Test fun `local code has no authority in all connectivity combinations`() {
        for (internet in listOf(false, true)) {
            for (cellular in listOf(false, true)) {
                for (fallback in listOf(false, true)) {
                    assertFalse(p.localCodeCanUnlock(TrustedExitTransportPolicy.Capabilities(internet, cellular, fallback)))
                }
            }
        }
    }
}
