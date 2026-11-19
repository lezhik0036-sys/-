package app.mama.trustedexit

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the pilot controller is idempotent (a fresh attempt provisions once; a
 * retry/cold-start with an existing context never re-provisions and never re-sends,
 * only refreshes readiness), fail-closed on a dropped refresh, and that the
 * ExitSetupSpec builder serializes the exact period with its real UTC offset.
 */
class TrustedExitPilotControllerTest {

    private fun readiness(ready: Boolean, verified: Boolean = ready) = ServerReadiness(
        active = true, contactSelected = true, contactVerified = verified,
        hasRegisteredPeriod = true, ready = ready,
    )

    private val spec = exitSetupSpec(
        contactName = "Мама", contactPhone = "+31611112222", zone = ZoneId.of("Europe/Amsterdam"),
        durationDays = 1, beginsAt = Instant.parse("2026-11-06T22:00:00Z"),
        endsAt = Instant.parse("2026-11-06T22:15:00Z"), periodKey = "te-fixed",
    )

    // --- controller idempotency ---------------------------------------------

    @Test fun freshAttempt_noContext_runsFullFlowOnce() {
        var full = 0
        var refresh = 0
        val s = TrustedExitPilotController(
            existingContext = { null },
            runFullAttempt = { full++; TrustedExitPilotOrchestrator.State.AwaitingVerification("s", "p") },
            refreshReadiness = { refresh++; readiness(ready = true) },
        ).prepare(spec)
        assertEquals(1, full)
        assertEquals(0, refresh) // the full attempt owns its own refresh; controller adds none
        assertEquals(TrustedExitPilotOrchestrator.State.AwaitingVerification("s", "p"), s)
    }

    @Test fun existingContext_ready_doesNotReprovision() {
        var full = 0
        val s = TrustedExitPilotController(
            existingContext = { ExitContext("s-1", "p-1") },
            runFullAttempt = { full++; TrustedExitPilotOrchestrator.State.Failed("should-not-run") },
            refreshReadiness = { readiness(ready = true) },
        ).prepare(spec)
        assertEquals(0, full) // no duplicate contact/series creation
        assertEquals(TrustedExitPilotOrchestrator.State.Ready("s-1", "p-1"), s)
    }

    @Test fun existingContext_refreshDropped_failsClosed() {
        val s = TrustedExitPilotController(
            existingContext = { ExitContext("s-1", "p-1") },
            runFullAttempt = { TrustedExitPilotOrchestrator.State.Failed("should-not-run") },
            refreshReadiness = { null },
        ).prepare(spec)
        assertEquals(
            TrustedExitPilotOrchestrator.State.Failed("readiness-refresh-unavailable"), s,
        )
    }

    @Test fun existingContext_notVerified_awaitsVerification_lockStaysOut() {
        val s = TrustedExitPilotController(
            existingContext = { ExitContext("s-1", "p-1") },
            runFullAttempt = { TrustedExitPilotOrchestrator.State.Failed("should-not-run") },
            refreshReadiness = { readiness(ready = false, verified = false) },
        ).prepare(spec)
        assertEquals(
            TrustedExitPilotOrchestrator.State.AwaitingVerification("s-1", "p-1"), s,
        )
    }

    @Test fun existingContext_readyFlagButUnverified_doesNotAuthorizeLock() {
        val s = TrustedExitPilotController(
            existingContext = { ExitContext("s-1", "p-1") },
            runFullAttempt = { TrustedExitPilotOrchestrator.State.Failed("x") },
            refreshReadiness = { readiness(ready = true, verified = false) },
        ).prepare(spec)
        assertEquals(
            TrustedExitPilotOrchestrator.State.AwaitingVerification("s-1", "p-1"), s,
        )
    }

    // --- spec builder --------------------------------------------------------

    @Test fun spec_serializesExactPeriodWithOffset() {
        assertEquals("Europe/Amsterdam", spec.timezone)
        assertEquals(1, spec.durationDays)
        assertEquals(true, spec.brickMode)
        // 22:00Z is 23:00 local (+01:00 in November / Amsterdam standard time).
        assertEquals("23:00:00", spec.startTime)
        assertEquals("23:15:00", spec.endTime)
        assertTrue(spec.beginsAtIso.startsWith("2026-11-06T23:00:00+01:00"))
        assertTrue(spec.endsAtIso.startsWith("2026-11-06T23:15:00+01:00"))
        assertEquals("te-fixed", spec.periodKey)
    }

    @Test fun spec_durationClampedToAtLeastOne() {
        val s = exitSetupSpec(
            contactName = "n", contactPhone = "+31600000000", zone = ZoneId.of("UTC"),
            durationDays = 0, beginsAt = Instant.parse("2026-11-06T00:00:00Z"),
            endsAt = Instant.parse("2026-11-06T00:15:00Z"),
        )
        assertEquals(1, s.durationDays)
    }
}
