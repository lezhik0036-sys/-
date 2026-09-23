package app.mama.core

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * "From 23:00 to 07:00 in Europe/Moscow": wall-clock times in a chosen zone.
 *
 * Resolving it gives absolute instants, so once a session is created,
 * travelling or changing the phone's time zone cannot shorten it.
 *
 * DST: a start/end that falls into a spring-forward gap is moved forward by
 * the gap length; in an autumn overlap the earlier offset is used
 * (standard [ZonedDateTime.of] behaviour).
 */
data class DailyWindow(
    val start: LocalTime,
    val end: LocalTime,
    val zone: ZoneId,
) {
    init {
        require(start != end) { "start and end must differ" }
    }

    /** True when the window crosses midnight, e.g. 23:00–07:00. */
    val overnight: Boolean get() = !end.isAfter(start)

    /**
     * The window occurrence that is in progress or comes next after [now].
     *
     * If [now] is already inside a window, the returned plan starts in the past;
     * the engine starts such a lock immediately. An in-progress window with less
     * than [minRemaining] left is skipped in favour of the next day's one.
     */
    fun nextPlan(
        now: Instant,
        mode: SessionMode = SessionMode.SLEEP,
        minRemaining: Duration = Duration.ZERO,
    ): LockPlan {
        val today = now.atZone(zone).toLocalDate()
        // Yesterday's overnight window may still be running this morning.
        for (offset in -1L..2L) {
            val day = today.plusDays(offset)
            val s = ZonedDateTime.of(day, start, zone).toInstant()
            val endDay = if (overnight) day.plusDays(1) else day
            val e = ZonedDateTime.of(endDay, end, zone).toInstant()
            val effectiveStart = maxOf(s, now)
            if (e.isAfter(now) && Duration.between(effectiveStart, e) >= minRemaining) {
                return LockPlan(start = s, end = e, zone = zone, mode = mode)
            }
        }
        error("unreachable: a daily window always recurs within two days")
    }
}
