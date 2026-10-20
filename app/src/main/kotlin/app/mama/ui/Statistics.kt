package app.mama.ui

import app.mama.core.DailyWindow
import app.mama.core.FlexPackage
import app.mama.core.HistoryRecord
import app.mama.core.HistoryResult
import app.mama.core.HistoryType
import app.mama.core.Series
import app.mama.core.SeriesStatus
import java.time.Instant
import java.time.LocalDate

/**
 * What the statistics screen shows (UI v1, 3A): only numbers that follow
 * from [app.mama.core.SeriesHistory] records and the series or FLEX package
 * running now. No hours "without the phone", no streaks, percentages,
 * ratings, comparisons or money.
 */
internal data class Statistics(
    /** STANDARD nights (or days) done: finished series and the one running now. */
    val nightsDone: Int,
    val unit: PeriodUnit,
    /** STANDARD series done to the end. */
    val seriesCompleted: Int,
    /** The best finished STANDARD series, or null without one. */
    val best: Best?,
    /** Restarts that really began (finished ones and a running one); a Restart only paid for is not counted. */
    val restartsStarted: Int,
    /** Null when there is no FLEX at all. */
    val flex: FlexSummary?,
    /** "Твой путь": the running series or package first, then finished ones, newest first. */
    val path: List<PathItem>,
) {
    /** Nothing done yet: the screen shows its empty state, never zeros. */
    val isEmpty: Boolean get() = path.none { it is PathItem.Finished } && nightsDone == 0 && (flex?.successful ?: 0) == 0

    data class Best(val done: Int, val planned: Int)

    data class FlexSummary(val packages: Int, val used: Int, val successful: Int)

    /** How the nights of the hero number read. */
    enum class PeriodUnit { NIGHTS, DAYS, NIGHTS_AND_DAYS }

    sealed interface PathItem {
        /** A STANDARD series that is ACTIVE now. */
        data class RunningSeries(val series: Series) : PathItem

        /** A FLEX package that is not over yet. */
        data class RunningFlex(val pkg: FlexPackage) : PathItem

        /** [continuedLater]: something began after a series that ended early. */
        data class Finished(val record: HistoryRecord, val continuedLater: Boolean) : PathItem
    }

    companion object {
        /**
         * [records] from the history; [running] and [flex] as stored now
         * (only an ACTIVE series and a package that is not over count).
         */
        fun of(records: List<HistoryRecord>, running: Series?, flex: FlexPackage?): Statistics {
            val series = running?.takeIf { it.status == SeriesStatus.ACTIVE }
            val pkg = flex?.takeIf { !it.over }
            val standard = records.filter { it.type != HistoryType.FLEX }
            val flexRecords = records.filter { it.type == HistoryType.FLEX }

            val windows = standard.mapNotNull { it.window } + listOfNotNull(series?.window)
            val unit = when {
                windows.isEmpty() || windows.all(::nightWindow) -> PeriodUnit.NIGHTS
                windows.none(::nightWindow) -> PeriodUnit.DAYS
                else -> PeriodUnit.NIGHTS_AND_DAYS
            }
            val best = standard.maxWithOrNull(
                compareBy<HistoryRecord>({ it.successfulNights }, { -(it.plannedNights - it.successfulNights) }, { it.endedAt }),
            )?.let { Best(it.successfulNights, it.plannedNights) }
            val flexSummary = if (flexRecords.isEmpty() && pkg == null) {
                null
            } else {
                FlexSummary(
                    packages = flexRecords.size + (if (pkg != null) 1 else 0),
                    used = flexRecords.sumOf { it.consumedNights ?: 0 } + (pkg?.used ?: 0),
                    successful = flexRecords.sumOf { it.successfulNights } + (pkg?.successful ?: 0),
                )
            }

            val running = listOfNotNull<PathItem>(series?.let { PathItem.RunningSeries(it) }, pkg?.let { PathItem.RunningFlex(it) })
            val finished = records.sortedByDescending { it.endedAt }.map { r ->
                PathItem.Finished(r, r.result == HistoryResult.BROKEN && continuedAfter(r, records, series, pkg))
            }
            return Statistics(
                nightsDone = standard.sumOf { it.successfulNights } + (series?.completedPeriods ?: 0),
                unit = unit,
                seriesCompleted = standard.count { it.result == HistoryResult.COMPLETED },
                best = best,
                restartsStarted = standard.count { it.restartOf != null } + (if (series?.restartOf != null) 1 else 0),
                flex = flexSummary,
                path = running + finished,
            )
        }

        /** A Restart of [r], or any series or package that began after [r] ended (FLEX: once a period was used). */
        private fun continuedAfter(r: HistoryRecord, records: List<HistoryRecord>, series: Series?, pkg: FlexPackage?): Boolean {
            fun after(start: Instant?, end: Instant) = (start ?: end).isAfter(r.endedAt)
            return records.any { it.restartOf == r.id || (it !== r && after(it.startedAt, it.endedAt)) } ||
                (series != null && (series.restartOf == r.id || after(series.actualStartedAt ?: series.startedAt, series.startedAt))) ||
                (pkg != null && pkg.used > 0 && pkg.activatedAt.isAfter(r.endedAt))
        }

        /** Night or day by the window's start: the same rule as the home screen. */
        fun nightWindow(w: DailyWindow) = w.start.hour >= 17 || w.start.hour < 5

        private val months = listOf(
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря",
        )

        /** "12–19 октября", "28 сентября – 4 октября", "19 октября" (no start), the year only when it is not [thisYear]. */
        fun dateRange(start: LocalDate?, end: LocalDate, thisYear: Int): String {
            fun day(d: LocalDate) = "${d.dayOfMonth} ${months[d.monthValue - 1]}"
            val year = if (end.year != thisYear) " ${end.year}" else ""
            return when {
                start == null || start == end -> day(end) + year
                start.year != end.year -> "${day(start)} ${start.year} – ${day(end)} ${end.year}"
                start.month == end.month -> "${start.dayOfMonth}–${day(end)}$year"
                else -> "${day(start)} – ${day(end)}$year"
            }
        }
    }
}
