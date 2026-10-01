package app.mama.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Test mode: a free 15-minute lock that starts immediately, for checking the
 * lock and the exit flow while MAMA is being tested. It is not a series and
 * not FLEX: it counts nowhere, costs nothing and has no Restart.
 */
object TestMode {
    val DURATION: Duration = Duration.ofMinutes(15)

    /** Starts at the real current moment and lasts exactly [DURATION]. */
    fun plan(now: Instant, zone: ZoneId, mode: SessionMode) = LockPlan(now, now + DURATION, zone, mode)
}
