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

    /** Nominal length on the clock face: 23:00–07:00 is 8 hours (a DST night is not counted). */
    val length: Duration get() = Duration.between(start, end).let { if (it.isNegative) it.plusDays(1) else it }

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

    /**
     * The first lock of a standard series: always a full window, never one
     * already in progress. If today's start time is still ahead, today's
     * window; otherwise the next day's. At 23:10 for 23:00–07:00 that is
     * tomorrow 23:00; at 06:50, today 23:00; at 22:50, today 23:00.
     */
    fun nextFullPlan(now: Instant, mode: SessionMode = SessionMode.SLEEP): LockPlan {
        val today = now.atZone(zone).toLocalDate()
        for (offset in 0L..2L) {
            val day = today.plusDays(offset)
            val s = ZonedDateTime.of(day, start, zone).toInstant()
            if (now.isBefore(s)) {
                val endDay = if (overnight) day.plusDays(1) else day
                return LockPlan(start = s, end = ZonedDateTime.of(endDay, end, zone).toInstant(), zone = zone, mode = mode)
            }
        }
        error("unreachable: a daily window always recurs within two days")
    }
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
    data class AutoNow(val length: Duration, val zone: ZoneId) : LaunchTime {
        init {
            require(!length.isNegative && !length.isZero && length < Duration.ofDays(1)) { "length must be within a day" }
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
    val DEFAULT_LENGTH: Duration = Duration.ofHours(8)

    fun freshTimes(now: Instant, zone: ZoneId): Pair<LocalTime, LocalTime> {
        val start = now.atZone(zone).toLocalTime().withSecond(0).withNano(0)
        return start to start.plus(DEFAULT_LENGTH)
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
            Resolved(DailyWindow(start, start.plus(time.length), time.zone), startNow = true)
        }
    }
}
