package app.mama.core

import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the platform reports about MAMA's protection access (iPhone: Screen Time authorization). */
enum class AccessReading { APPROVED, DENIED, NOT_DETERMINED, UNREADABLE }

/** What MAMA concludes from the readings. Only [CONFIRMED_DENIED] may fail anything. */
enum class AccessVerdict { APPROVED, CONFIRMED_DENIED, UNKNOWN }

/**
 * Tells a real withdrawal of access from a glitch. One "denied" reading is
 * not enough: access must still read "denied" at least [CONFIRM_AFTER] after
 * the first such reading, with no "approved" in between. "Not determined"
 * and unreadable states — app launch, a reboot, a process killed by the
 * system, an extension that could not read it — are UNKNOWN: they never fail
 * anything and neither start nor clear a pending denial.
 */
object AccessWatch {
    /** TODO(product): tune after tests on real iPhones; configurable. */
    val CONFIRM_AFTER: Duration = 3.seconds

    /** The verdict after one more reading, and since when access has read "denied" (to keep for the next reading). */
    data class Step(val verdict: AccessVerdict, val deniedSince: Instant?)

    fun observe(reading: AccessReading, deniedSince: Instant?, now: Instant): Step = when (reading) {
        AccessReading.APPROVED -> Step(AccessVerdict.APPROVED, null)
        AccessReading.DENIED -> {
            // A start "in the future" (clock moved back) starts again from now.
            val since = deniedSince?.takeIf { it <= now } ?: now
            Step(if (now - since >= CONFIRM_AFTER) AccessVerdict.CONFIRMED_DENIED else AccessVerdict.UNKNOWN, since)
        }
        AccessReading.NOT_DETERMINED, AccessReading.UNREADABLE -> Step(AccessVerdict.UNKNOWN, deniedSince)
    }
}
