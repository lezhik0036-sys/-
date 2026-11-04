package app.mama.trustedexit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release safety invariant: a release cannot enter a binding series when
 * authenticated server-side early exit and account recovery are not wired.
 * Debug may continue to use its independent fake-provider round-trip.
 */
class ReleaseTrustedExitReadinessTest {
    @Test
    fun `release refuses to start a series even when fake backend works`() {
        var called = false
        assertFalse(TrustedExit.defaultReadinessForTestMode(false) {
            called = true
            true
        })
        assertFalse("release must never probe fake OTP as readiness", called)
    }

    @Test
    fun `debug requires working fake OTP before series can start`() {
        assertTrue(TrustedExit.defaultReadinessForTestMode(true) { true })
        assertFalse(TrustedExit.defaultReadinessForTestMode(true) { false })
    }
}
