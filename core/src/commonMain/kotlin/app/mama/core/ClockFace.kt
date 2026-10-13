package app.mama.core

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * Wall-clock arithmetic for daily windows, with the semantics MAMA has always
 * used (java.time on Android): times of day wrap around midnight, and a
 * moment is turned into its local minute without seconds.
 */

private const val SECONDS_PER_DAY = 24 * 60 * 60

/** [this] moved by [d] on the clock face, wrapping past midnight: 23:00 + 8 h = 07:00. */
fun LocalTime.plusOnClock(d: Duration): LocalTime =
    LocalTime.fromSecondOfDay((toSecondOfDay() + d.inWholeSeconds).mod(SECONDS_PER_DAY.toLong()).toInt())

/** Clock-face distance from [from] forward to [to]: 23:00 → 07:00 is 8 hours; zero when they are equal. */
fun clockDistance(from: LocalTime, to: LocalTime): Duration =
    (to.toSecondOfDay() - from.toSecondOfDay()).mod(SECONDS_PER_DAY).seconds

/** The local time of day of [this] in [zone], to the minute (seconds dropped). */
fun Instant.minuteOfDay(zone: TimeZone): LocalTime =
    toLocalDateTime(zone).time.let { LocalTime(it.hour, it.minute) }

/** [this] with seconds and fractions dropped (minutes since the epoch are zone-independent). */
fun Instant.truncatedToMinute(): Instant = Instant.fromEpochSeconds(epochSeconds - epochSeconds.mod(60L))
