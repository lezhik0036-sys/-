package app.mama.core

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * "From 23:00 to 07:00 in Europe/Moscow": wall-clock times in a chosen zone.
 *
 * Resolving it gives absolute instants, so once a session is created,
 * travelling or changing the phone's time zone cannot shorten it.
 *
 * DST: a start/end that falls into a spring-forward gap is moved forward by
 * the gap length; in an autumn overlap the earlier offset is used
 * (standard [LocalDateTime.toInstant] behaviour, the same as java.time).
 */
data class DailyWindow(
    val start: LocalTime,
    val end: LocalTime,
    val zone: TimeZone,
) {
    init {
        require(start != end) { "start and end must differ" }
    }

    /** True when the window crosses midnight, e.g. 23:00–07:00. */
    val overnight: Boolean get() = end <= start

    /** Nominal length on the clock face: 23:00–07:00 is 8 hours (a DST night is not counted). */
    val length: Duration get() = clockDistance(start, end)

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
        val today = now.toLocalDateTime(zone).date
        // Yesterday's overnight window may still be running this morning.
        for (offset in -1..2) {
            val day = today.plus(offset, DateTimeUnit.DAY)
            val s = at(day, start)
            val e = at(if (overnight) day.plus(1, DateTimeUnit.DAY) else day, end)
            val effectiveStart = maxOf(s, now)
            if (e > now && e - effectiveStart >= minRemaining) {
                return LockPlan(start = s, end = e, zone = zone, mode = mode)
            }
        }
        error("unreachable: a daily window always recurs within two days")
    }

    /**
     * The first lock of a standard series: always a full window, never one
     * already in progress. If today's start time is still ahead, today's
     * window; otherwise the next day's. At 23:10 for 23:00–07:00 that is
     * tomorrow 23:00; at 06:50, today 23:00; at 22:50, today 23:00.
     */
    fun nextFullPlan(now: Instant, mode: SessionMode = SessionMode.SLEEP): LockPlan {
        val today = now.toLocalDateTime(zone).date
        for (offset in 0..2) {
            val day = today.plus(offset, DateTimeUnit.DAY)
            val s = at(day, start)
            if (now < s) {
                val e = at(if (overnight) day.plus(1, DateTimeUnit.DAY) else day, end)
                return LockPlan(start = s, end = e, zone = zone, mode = mode)
            }
        }
        error("unreachable: a daily window always recurs within two days")
    }

    private fun at(day: LocalDate, time: LocalTime): Instant = LocalDateTime(day, time).toInstant(zone)
}

/**
 * When a new standard series (or a Restart) starts, as chosen in its draft.
 * Resolved once, at the final confirmation ([SeriesDraft.resolve]); after
 * that the series has an ordinary fixed daily window.
 */
sealed interface LaunchTime {
    /**
     * MANUAL: the user set the start time. A fixed window; the first lock is
     * its next full occurrence ([DailyWindow.nextFullPlan]), never moved.
     */
    data class Fixed(val window: DailyWindow) : LaunchTime

    /**
     * AUTO_NOW, the default of a new draft while its start is untouched: the
     * series starts at the moment of the final confirmation and keeps the
     * draft's [length]. Opened at 16:48 with 8 hours, confirmed at 16:53:
     * 16:53 → 00:53 from now on, the first lock right away — not tomorrow.
     */
    data class AutoNow(val length: Duration, val zone: TimeZone) : LaunchTime {
        init {
            require(length.isPositive() && length < 1.days) { "length must be within a day" }
        }
    }
}

/**
 * Default times of a NEW standard series draft: never the previous series'
 * window. The start is the current local time to the minute, the end the
 * usual length later. An existing draft keeps whatever the user chose.
 */
object SeriesDraft {
    /** Default length of a new window (the former 23:00–07:00 default). */
    val DEFAULT_LENGTH: Duration = 8.hours

    fun freshTimes(now: Instant, zone: TimeZone): Pair<LocalTime, LocalTime> {
        val start = now.minuteOfDay(zone)
        return start to start.plusOnClock(DEFAULT_LENGTH)
    }

    /** A launch resolved at the final confirmation: the series' fixed daily window, and whether its first lock starts at once. */
    data class Resolved(val window: DailyWindow, val startNow: Boolean)

    /**
     * MANUAL keeps its window. AUTO_NOW takes the confirmation minute as the
     * daily start and the draft's length for the end; its first lock starts
     * at [now] and lasts that whole length.
     */
    fun resolve(time: LaunchTime, now: Instant): Resolved = when (time) {
        is LaunchTime.Fixed -> Resolved(time.window, startNow = false)
        is LaunchTime.AutoNow -> {
            val (start, _) = freshTimes(now, time.zone)
            Resolved(DailyWindow(start, start.plusOnClock(time.length), time.zone), startNow = true)
        }
    }
}
