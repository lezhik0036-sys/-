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
    fun changedSeriesInvalidatesInFlightServerResponse() {
        val oldSeriesTicket = TrustedExitReadinessGate.beginRefresh()
        // Provisioning a new series/period invokes clear().
        TrustedExitReadinessGate.clear()
        assertFalse(TrustedExitReadinessGate.recordIfCurrent(oldSeriesTicket, true, t0))
        assertFalse(TrustedExitReadinessGate.ready(t0))
    }

    @Test
    fun newestRefreshWinsAndOlderReadyReplyCannotReopenGate() {
        val old = TrustedExitReadinessGate.beginRefresh()
        val newer = TrustedExitReadinessGate.beginRefresh()
        assertTrue(TrustedExitReadinessGate.recordIfCurrent(newer, false, t0))
        assertFalse(TrustedExitReadinessGate.recordIfCurrent(old, true, t0))
        assertFalse(TrustedExitReadinessGate.ready(t0))
    }

    @Test
    fun refreshedServerReadyMustMatchMostRecentTicket() {
        val old = TrustedExitReadinessGate.beginRefresh()
        assertTrue(TrustedExitReadinessGate.recordIfCurrent(old, true, t0))
        assertTrue(TrustedExitReadinessGate.ready(t0))
        val fresh = TrustedExitReadinessGate.beginRefresh()
        assertFalse(TrustedExitReadinessGate.ready(t0))
        assertTrue(TrustedExitReadinessGate.recordIfCurrent(fresh, true, t0 + 1))
        assertTrue(TrustedExitReadinessGate.ready(t0 + 1))
    }

    @Test
    fun elapsedClockCannotExtendCachedApprovalBeyondTwoMinutes() {
        // The parameter models Android's monotonic elapsedRealtime() clock;
        // changing timezone or the wall clock has no impact on this age.
        val sampledElapsed = 700_000L
        TrustedExitReadinessGate.record(true, sampledElapsed)
        assertTrue(TrustedExitReadinessGate.ready(sampledElapsed + maxAge))
        assertFalse(TrustedExitReadinessGate.ready(sampledElapsed + maxAge + 1))
        // A clock reset / reboot is also fail-closed even in an injected test.
        assertFalse(TrustedExitReadinessGate.ready(sampledElapsed - 1))
    }

    @Test
    fun clearingDuringRefreshAlwaysInvalidatesItsTicket() {
        val ticket = TrustedExitReadinessGate.beginRefresh()
        TrustedExitReadinessGate.clear()
        assertFalse(TrustedExitReadinessGate.recordIfCurrent(ticket, true, t0))
        assertFalse(TrustedExitReadinessGate.ready(t0))
    }

    @Test
    fun clear_dropsReadiness() {
        TrustedExitReadinessGate.record(true, t0)
        TrustedExitReadinessGate.clear()
        assertFalse(TrustedExitReadinessGate.ready(t0))
    }
}
