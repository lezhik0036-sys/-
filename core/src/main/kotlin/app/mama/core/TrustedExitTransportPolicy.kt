package app.mama.core

/**
 * Trusted Exit transport policy. A cellular SIM/SMS signal is NOT proof of an
 * authenticated server connection, and cannot by itself allow a BRICK exit.
 *
 * This policy only selects which network transport MAY attempt a server-side
 * Trusted Exit. Verification remains the server's responsibility.
 *
 * carrierFallbackReady must be set exclusively by an installed/audited,
 * actually functional, cryptographically authenticated two-way SMS/IVR
 * implementation, never by SIM detection, a test flag or a user preference.
 */
object TrustedExitTransportPolicy {
    data class Capabilities(
        /** A currently validated Internet route; can be Wi-Fi or mobile data. */
        val validatedInternet: Boolean,
        /** The device has a SIM and carrier SMS/voice is currently usable. */
        val cellularService: Boolean,
        /** Separate authenticated SMS/IVR transport is deployed and ready. */
        val carrierFallbackReady: Boolean = false,
    )

    enum class Route {
        /** HTTPS request + server-side SMS to a verified trusted contact. */
        INTERNET,
        /** Only an audited, mutually authenticated two-way carrier gateway. */
        AUTHENTICATED_CARRIER,
        /** Neither means safe server authority. Do not start an exit request. */
        UNAVAILABLE,
    }

    fun routeFor(c: Capabilities): Route = when {
        c.validatedInternet -> Route.INTERNET
        c.cellularService && c.carrierFallbackReady -> Route.AUTHENTICATED_CARRIER
        else -> Route.UNAVAILABLE
    }

    /** A local code must never authorize an exit in ANY connectivity state. */
    fun localCodeCanUnlock(@Suppress("UNUSED_PARAMETER") c: Capabilities): Boolean = false
}
