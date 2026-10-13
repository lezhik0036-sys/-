package app.mama.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Test mode: a free 15-minute lock that starts immediately, for checking the
 * lock and the exit flow while MAMA is being tested. It is not a series and
 * not FLEX: it counts nowhere, costs nothing and has no Restart.
 */
object TestMode {
    val DURATION: Duration = 15.minutes

    /** Starts at the real current moment and lasts exactly [DURATION]. */
    fun plan(now: Instant, zone: TimeZone, mode: SessionMode) = LockPlan(now, now + DURATION, zone, mode)
}
