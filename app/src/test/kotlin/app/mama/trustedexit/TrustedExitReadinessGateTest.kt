package app.mama.trustedexit

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock-start readiness gate, as pure policy (no Android, no network): a strict
 * BRICK lock may start ONLY on a fresh, server-confirmed `ready == true`. Proves
 * the fail-closed cases the owner's P0 preflight requires.
 */
class TrustedExitReadinessGateTest {

    @After
    fun tearDown() = TrustedExitReadinessGate.clear()

    private val t0 = 1_000_000L
    private val maxAge = TrustedExitReadinessGate.MAX_AGE_MS

    @Test
    fun neverRecorded_isNotReady() {
        TrustedExitReadinessGate.clear()
        assertFalse(TrustedExitReadinessGate.ready(t0))
    }

    @Test
    fun freshReady_isReady() {
        TrustedExitReadinessGate.record(true, t0)
        assertTrue(TrustedExitReadinessGate.ready(t0))
        assertTrue(TrustedExitReadinessGate.ready(t0 + maxAge)) // still within the window
    }

    @Test
    fun staleReady_isNotReady() {
        TrustedExitReadinessGate.record(true, t0)
        assertFalse(TrustedExitReadinessGate.ready(t0 + maxAge + 1))
    }

    @Test
    fun recordedNotReady_isNotReady() {
        TrustedExitReadinessGate.record(false, t0)
        assertFalse(TrustedExitReadinessGate.ready(t0))
    }

    @Test
    fun clockWentBackwards_isNotReady() {
        TrustedExitReadinessGate.record(true, t0)
        assertFalse(TrustedExitReadinessGate.ready(t0 - 1))
    }

    @Test
    fun clear_dropsReadiness() {
        TrustedExitReadinessGate.record(true, t0)
        TrustedExitReadinessGate.clear()
        assertFalse(TrustedExitReadinessGate.ready(t0))
    }
}
