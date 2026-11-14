package app.mama.trustedexit

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Build the server provisioning [ExitSetupSpec] from the user's wizard choices.
 *
 * [beginsAt]/[endsAt] are the concrete instants this device's CURRENT lock period
 * runs for (for the 15-minute test: now .. now+15m; for a series: the next window),
 * serialized as ISO-8601 offset date-times at [zone] so the server stores the exact
 * period with its real UTC offset. [durationDays] is the SERIES length (1 for the
 * single test period), independent of the period's wall-clock span. [startTime]/
 * [endTime] are the local "HH:mm:ss" window bounds.
 *
 * [periodKey] is stable per logical attempt so re-registering the same period is
 * idempotent on the server (no duplicate period). The caller reuses the same key
 * across retries of one attempt.
 */
fun exitSetupSpec(
    contactName: String,
    contactPhone: String,
    zone: ZoneId,
    durationDays: Int,
    beginsAt: Instant,
    endsAt: Instant,
    periodKey: String = "te-" + UUID.randomUUID(),
    brickMode: Boolean = true,
): ExitSetupSpec {
    val begin = beginsAt.atZone(zone)
    val end = endsAt.atZone(zone)
    val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    val hms = DateTimeFormatter.ofPattern("HH:mm:ss")
    return ExitSetupSpec(
        contactName = contactName,
        contactPhone = contactPhone,
        durationDays = if (durationDays < 1) 1 else durationDays,
        startTime = begin.toLocalTime().format(hms),
        endTime = end.toLocalTime().format(hms),
        timezone = zone.id,
        brickMode = brickMode,
        periodKey = periodKey,
        beginsAtIso = begin.format(iso),
        endsAtIso = end.format(iso),
    )
}
