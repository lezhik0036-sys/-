package app.mama.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** What a history record is about. */
enum class HistoryType { STANDARD_3, STANDARD_5, STANDARD_7, FLEX }

/**
 * How it ended.
 * - STANDARD: [COMPLETED] (every night done) or [BROKEN] (left early).
 * - FLEX: [COMPLETED] (all 7 periods were used before the 30 days ran out,
 *   successful or not) or [EXPIRED] (the 30 days ran out with periods left
 *   unused). A FLEX package is never [BROKEN]: its successful periods are
 *   counted separately.
 */
enum class HistoryResult { COMPLETED, BROKEN, EXPIRED }

/**
 * One finished series or FLEX package: a journal of a result the core has
 * already settled. Nothing in the core reads it back, so it can never change
 * how a series, Restart, FLEX or the 30-day rule behaves. No trusted
 * contact, phone number, personal reason or money is kept here (money has
 * its own history in the wallet). 15-minute test locks are never recorded.
 */
data class HistoryRecord(
    /** The series or FLEX package id; `legacy-…` for entries written before this model. */
    val id: String,
    val type: HistoryType,
    /**
     * STANDARD: the first actual switch-on of protection (the series began);
     * FLEX: activation of the package. Null for legacy entries.
     */
    val startedAt: Instant?,
    val endedAt: Instant,
    val result: HistoryResult,
    /** 3 / 5 / 7; FLEX: the 7 periods of the package. */
    val plannedNights: Int,
    /** FLEX only: periods that actually started (used up), successful or not. Null for STANDARD. */
    val consumedNights: Int?,
    val successfulNights: Int,
    /** STANDARD: the window fixed for the series. Null for FLEX and for legacy entries. */
    val window: DailyWindow?,
    /** A Restart that really started: the id of the BROKEN series it continues. */
    val restartOf: String?,
) {
    /**
     * The same finished result: the same id, or (for entries written before
     * ids were kept) the same type, end to the millisecond, nights and result.
     */
    fun sameResultAs(other: HistoryRecord): Boolean =
        id == other.id || (
            type == other.type && endedAt.toEpochMilli() == other.endedAt.toEpochMilli() &&
                successfulNights == other.successfulNights && result == other.result
            )
}

/** Builds records from finished results; nothing here changes a series or a package. */
object SeriesHistory {

    /**
     * The record of a STANDARD series that ended COMPLETED or BROKEN; null
     * for any other status (it has not begun or is still running). [now]
     * only stands in for an end time the core did not set.
     */
    fun of(series: Series, now: Instant): HistoryRecord? {
        val result = when (series.status) {
            SeriesStatus.COMPLETED -> HistoryResult.COMPLETED
            SeriesStatus.BROKEN -> HistoryResult.BROKEN
            else -> return null
        }
        return HistoryRecord(
            id = series.id,
            type = typeOf(series.kind),
            startedAt = series.actualStartedAt,
            endedAt = series.endedAt ?: now,
            result = result,
            plannedNights = series.kind.periods,
            consumedNights = null,
            successfulNights = series.completedPeriods,
            window = series.window,
            restartOf = series.restartOf,
        )
    }

    /** The record of a FLEX package that is over; null while it still runs. */
    fun of(pkg: FlexPackage, now: Instant): HistoryRecord? {
        if (!pkg.over) return null
        return HistoryRecord(
            id = pkg.id,
            type = HistoryType.FLEX,
            startedAt = pkg.activatedAt,
            endedAt = pkg.endedAt ?: now,
            result = if (pkg.used >= FlexPackage.PERIODS) HistoryResult.COMPLETED else HistoryResult.EXPIRED,
            plannedNights = FlexPackage.PERIODS,
            consumedNights = pkg.used,
            successfulNights = pkg.successful,
            window = null,
            restartOf = null,
        )
    }

    /** [records] with [record] added, unless that result is already there. Newest first; no limit. */
    fun append(records: List<HistoryRecord>, record: HistoryRecord): List<HistoryRecord> =
        if (records.any { it.sameResultAs(record) }) records else newestFirst(records + record)

    fun newestFirst(records: List<HistoryRecord>): List<HistoryRecord> = records.sortedByDescending { it.endedAt }

    fun typeOf(kind: SeriesKind): HistoryType = when (kind) {
        SeriesKind.THREE -> HistoryType.STANDARD_3
        SeriesKind.FIVE -> HistoryType.STANDARD_5
        SeriesKind.SEVEN -> HistoryType.STANDARD_7
    }
}

/**
 * One record per line, so the platform can keep the history in any
 * key-value store. Also reads the two line formats written before this
 * model (they are converted when read and never rewritten).
 */
object HistoryCodec {
    private const val VERSION = "v2"
    private const val SEP = "|"

    /**
     * `v2|id|type|startedAt|endedAt|result|planned|consumed|successful|windowStart|windowEnd|zone|restartOf`;
     * instants as epoch milliseconds, an empty field for null.
     */
    fun encode(r: HistoryRecord): String {
        require(listOfNotNull(r.id, r.restartOf).none { it.contains(SEP) || it.contains('\n') }) { "id must not contain separators" }
        return listOf(
            VERSION, r.id, r.type.name, r.startedAt?.toEpochMilli()?.toString().orEmpty(), r.endedAt.toEpochMilli().toString(),
            r.result.name, r.plannedNights.toString(), r.consumedNights?.toString().orEmpty(), r.successfulNights.toString(),
            r.window?.start?.toString().orEmpty(), r.window?.end?.toString().orEmpty(), r.window?.zone?.id.orEmpty(),
            r.restartOf.orEmpty(),
        ).joinToString(SEP)
    }

    /** A line written by [encode]; null for anything unreadable (it is skipped, never a crash). */
    fun decode(line: String): HistoryRecord? = runCatching {
        val p = line.split(SEP)
        if (p.size != 13 || p[0] != VERSION) return null
        val window = if (p[9].isEmpty()) null else DailyWindow(LocalTime.parse(p[9]), LocalTime.parse(p[10]), ZoneId.of(p[11]))
        HistoryRecord(
            id = p[1].ifEmpty { return null },
            type = HistoryType.valueOf(p[2]),
            startedAt = p[3].ifEmpty { null }?.let { Instant.ofEpochMilli(it.toLong()) },
            endedAt = Instant.ofEpochMilli(p[4].toLong()),
            result = HistoryResult.valueOf(p[5]),
            plannedNights = p[6].toInt(),
            consumedNights = p[7].ifEmpty { null }?.toInt(),
            successfulNights = p[8].toInt(),
            window = window,
            restartOf = p[12].ifEmpty { null },
        )
    }.getOrNull()

    /**
     * A STANDARD entry written before this model: `endedAt|THREE|FIVE|SEVEN|nights|COMPLETED|BROKEN`.
     * Its start and window were not kept, so they stay empty.
     */
    fun decodeLegacySeries(line: String): HistoryRecord? = runCatching {
        val p = line.split(SEP)
        if (p.size != 4) return null
        val kind = SeriesKind.valueOf(p[1])
        val result = when (SeriesStatus.valueOf(p[3])) {
            SeriesStatus.COMPLETED -> HistoryResult.COMPLETED
            SeriesStatus.BROKEN -> HistoryResult.BROKEN
            else -> return null
        }
        HistoryRecord(
            id = "legacy-${p[0]}-${kind.name}", type = SeriesHistory.typeOf(kind), startedAt = null,
            endedAt = Instant.ofEpochMilli(p[0].toLong()), result = result, plannedNights = kind.periods,
            consumedNights = null, successfulNights = p[2].toInt(), window = null, restartOf = null,
        )
    }.getOrNull()

    /** A FLEX entry written before this model: `endedAt|FLEX|successful|used`. */
    fun decodeLegacyFlex(line: String): HistoryRecord? = runCatching {
        val p = line.split(SEP)
        if (p.size != 4 || p[1] != "FLEX") return null
        val used = p[3].toInt()
        HistoryRecord(
            id = "legacy-${p[0]}-FLEX", type = HistoryType.FLEX, startedAt = null,
            endedAt = Instant.ofEpochMilli(p[0].toLong()),
            result = if (used >= FlexPackage.PERIODS) HistoryResult.COMPLETED else HistoryResult.EXPIRED,
            plannedNights = FlexPackage.PERIODS, consumedNights = used, successfulNights = p[2].toInt(),
            window = null, restartOf = null,
        )
    }.getOrNull()

    /**
     * The whole history: the records of this model plus the converted legacy
     * entries, each result once, newest first. Unreadable lines are skipped.
     */
    fun read(records: String, legacySeries: String, legacyFlex: String): List<HistoryRecord> {
        var all = SeriesHistory.newestFirst(lines(records).mapNotNull(::decode).distinctBy { it.id })
        val legacy = lines(legacySeries).mapNotNull(::decodeLegacySeries) + lines(legacyFlex).mapNotNull(::decodeLegacyFlex)
        for (r in legacy) all = SeriesHistory.append(all, r)
        return all
    }

    fun write(records: List<HistoryRecord>): String = records.joinToString("\n", transform = ::encode)

    private fun lines(s: String) = s.lines().filter { it.isNotBlank() }
}
