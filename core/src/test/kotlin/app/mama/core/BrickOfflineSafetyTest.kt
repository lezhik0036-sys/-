package app.mama.core

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audit #8: BRICK Mode ends SAFELY with no internet and the server unreachable, and
 * never grants an EARLY exit while offline.
 *
 * The lock engine is pure and clock-driven, and the trusted clock (ClockGuard) is the
 * monotonic since-boot clock, so neither the scheduled end nor its enforcement depends on
 * a network or on the server:
 *   * once the trusted clock reaches plan.end the lock finishes and the user is freed —
 *     even with the WALL clock frozen (no NTP) and the server unreachable;
 *   * before plan.end, with no trusted-contact code and no server-authoritative exit,
 *     it stays Locked (being offline is not a way out), and a wall-clock jump within the
 *     same boot cannot end it early.
 */
class BrickOfflineSafetyTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val start = Instant.parse("2026-11-16T20:00:00Z")
    private val end = start.plusSeconds(3600) // a one-hour lock
    private val engine = LockEngine()

    private fun activeSession() = (
        engine.create("offline", LockPlan(start, end, zone), MOM, start)
            as LockEngine.CreateResult.Created
        ).session

    @Test
    fun `offline the lock ends at the scheduled end on the monotonic clock even with a frozen wall clock`() {
        val session = activeSession()
        // Offline the device is never NTP-synced, so the WALL clock stays frozen at start;
        // only the monotonic since-boot clock advances. boot-1, anchored at elapsed 0.
        val anchor = ClockGuard.reanchor(start, 0, "boot-1")

        // 59 minutes in (monotonic 3540s), wall clock still frozen -> still locked.
        val near = ClockGuard.trustedNow(anchor, start, start, 3_540_000, "boot-1")
        assertTrue(engine.stateOf(session, near).isLocked)

        // At 60 minutes the monotonic clock reaches plan.end -> the lock finishes offline.
        val atEnd = ClockGuard.trustedNow(anchor, start, start, 3_600_000, "boot-1")
        assertEquals(end, atEnd)
        val finished = engine.advance(session, atEnd)
        assertEquals(FinishReason.COMPLETED, finished.finishReason)
        assertTrue(engine.stateOf(finished, atEnd) is LockState.Free)
        assertFalse(engine.stateOf(finished, atEnd).isLocked)
    }

    @Test
    fun `offline there is no early exit and a wall-clock jump cannot end the lock early`() {
        val session = activeSession()
        val anchor = ClockGuard.reanchor(start, 0, "boot-1")

        // 30 minutes in (monotonic 1800s). The user jumps the WALL clock a day past the
        // end to try to escape; within the boot the trusted clock ignores the wall jump.
        val jumped = ClockGuard.trustedNow(anchor, start, end.plusSeconds(86_400), 1_800_000, "boot-1")
        assertEquals(start.plusSeconds(1800), jumped)
        assertTrue(engine.stateOf(session, jumped).isLocked)

        // Offline the trusted contact never receives a server-delivered OTP, so no valid
        // code exists; a guessed code must not open the lock, and the server-only exit
        // (endByTrustedExit) is never reachable. The lock stays on until its real end.
        val guessed = engine.submitCode(session, "000000", jumped)
        assertTrue(engine.stateOf(guessed.session, jumped).isLocked)
    }
}
