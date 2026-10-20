package app.mama.ui

import app.mama.core.DailyWindow
import app.mama.core.FlexPackage
import app.mama.core.GrantSource
import app.mama.core.HistoryRecord
import app.mama.core.HistoryResult
import app.mama.core.HistoryType
import app.mama.core.Series
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.SessionMode
import app.mama.core.TrustedContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Statistics (3A): only numbers that follow from the history and the series running now. */
class StatisticsTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val night = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), zone)
    private val day = DailyWindow(LocalTime.of(9, 0), LocalTime.of(17, 0), zone)
    private val now = Instant.parse("2026-10-20T12:00:00Z")
    private fun ago(days: Long) = now - Duration.ofDays(days)

    private fun standard(
        id: String, kind: HistoryType, endedDaysAgo: Long, done: Int, result: HistoryResult,
        window: DailyWindow? = night, restartOf: String? = null, planned: Int = 7,
    ) = HistoryRecord(id, kind, ago(endedDaysAgo + planned), ago(endedDaysAgo), result, planned, null, done, window, restartOf)

    private fun flex(id: String, endedDaysAgo: Long, used: Int, done: Int) = HistoryRecord(
        id, HistoryType.FLEX, ago(endedDaysAgo + 25), ago(endedDaysAgo),
        if (used >= 7) HistoryResult.COMPLETED else HistoryResult.EXPIRED, 7, used, done, null, null,
    )

    private fun running(done: Int, status: SeriesStatus = SeriesStatus.ACTIVE, restartOf: String? = null, startedDaysAgo: Long = 3) = Series(
        "now", SeriesKind.SEVEN, night, TrustedContact("Мама", "+79001234567"), SessionMode.SLEEP,
        startedAt = ago(startedDaysAgo), completedPeriods = done, status = status,
        actualStartedAt = if (status == SeriesStatus.ACTIVE) ago(startedDaysAgo) else null, restartOf = restartOf,
    )

    /** A 7 that stopped at 4, its Restart done 7 of 7, a FLEX package (7 used, 6 done) and a first 3 of 3. */
    private val path = listOf(
        standard("restart", HistoryType.STANDARD_7, 13, 7, HistoryResult.COMPLETED, restartOf = "first-7"),
        standard("first-7", HistoryType.STANDARD_7, 23, 4, HistoryResult.BROKEN),
        flex("flex", 36, used = 7, done = 6),
        standard("first-3", HistoryType.STANDARD_3, 67, 3, HistoryResult.COMPLETED, planned = 3),
    )

    @Test
    fun `the numbers of a real path, with the series running now`() {
        val s = Statistics.of(path, running(3), null)
        assertEquals("7 + 4 + 3 finished, 3 now; FLEX periods are counted apart", 17, s.nightsDone)
        assertEquals(Statistics.PeriodUnit.NIGHTS, s.unit)
        assertEquals(2, s.seriesCompleted)
        assertEquals(Statistics.Best(7, 7), s.best)
        assertEquals(1, s.restartsStarted)
        assertEquals(Statistics.FlexSummary(packages = 1, used = 7, successful = 6), s.flex)
        assertFalse(s.isEmpty)
    }

    @Test
    fun `the path - the running series first, then the finished ones, newest first`() {
        val s = Statistics.of(path, running(3), null)
        assertTrue(s.path.first() is Statistics.PathItem.RunningSeries)
        assertEquals(
            listOf("restart", "first-7", "flex", "first-3"),
            s.path.filterIsInstance<Statistics.PathItem.Finished>().map { it.record.id },
        )
    }

    @Test
    fun `a series that stopped early - continued later only when something really began after it`() {
        val continued = Statistics.of(path, null, null).path.filterIsInstance<Statistics.PathItem.Finished>().single { it.record.id == "first-7" }
        assertTrue("its Restart began", continued.continuedLater)

        val last = standard("last", HistoryType.STANDARD_7, 2, 4, HistoryResult.BROKEN)
        fun lastItem(running: Series?, pkg: FlexPackage? = null) =
            Statistics.of(path + last, running, pkg).path.filterIsInstance<Statistics.PathItem.Finished>().single { it.record.id == "last" }
        assertFalse("nothing after it", lastItem(null).continuedLater)
        assertFalse("a Restart only paid for, not begun", lastItem(running(0, SeriesStatus.SCHEDULED, restartOf = "last", startedDaysAgo = 1)).continuedLater)
        assertTrue("a series that began after it", lastItem(running(1, startedDaysAgo = 1)).continuedLater)
        val pkg = FlexPackage("f", ago(1), GrantSource.INTERNAL_BALANCE)
        assertFalse("a FLEX package with no period used yet", lastItem(null, pkg).continuedLater)
        assertTrue("a FLEX package with a period used", lastItem(null, pkg.copy(used = 1)).continuedLater)
    }

    @Test
    fun `a Restart counts only once it really began`() {
        val only = listOf(standard("first-7", HistoryType.STANDARD_7, 5, 4, HistoryResult.BROKEN))
        assertEquals(0, Statistics.of(only, running(0, SeriesStatus.SCHEDULED, restartOf = "first-7"), null).restartsStarted)
        assertEquals(0, Statistics.of(only, running(0, SeriesStatus.NEEDS_ATTENTION, restartOf = "first-7"), null).restartsStarted)
        assertEquals(1, Statistics.of(only, running(1, restartOf = "first-7"), null).restartsStarted)
    }

    @Test
    fun `FLEX is shown only when there is FLEX, with its own numbers`() {
        val standardOnly = path.filter { it.type != HistoryType.FLEX }
        assertNull(Statistics.of(standardOnly, null, null).flex)
        val pkg = FlexPackage("f", ago(4), GrantSource.INTERNAL_BALANCE, used = 2, successful = 1)
        assertEquals(Statistics.FlexSummary(1, 2, 1), Statistics.of(standardOnly, null, pkg).flex)
        val expired = Statistics.of(listOf(flex("old", 50, used = 4, done = 4)), null, null)
        assertEquals(Statistics.FlexSummary(1, 4, 4), expired.flex)
        assertEquals(0, expired.nightsDone)
        assertNull(expired.best)
        assertFalse(expired.isEmpty)
    }

    @Test
    fun `nothing done yet - the empty state, never zeros`() {
        assertTrue(Statistics.of(emptyList(), null, null).isEmpty)
        assertTrue("the first night has not ended yet", Statistics.of(emptyList(), running(0), null).isEmpty)
        assertTrue("a series only planned", Statistics.of(emptyList(), running(0, SeriesStatus.SCHEDULED), null).isEmpty)
        assertFalse(Statistics.of(emptyList(), running(1), null).isEmpty)
    }

    @Test
    fun `the best result - most nights, then the one done to the end`() {
        val s = Statistics.of(
            listOf(
                standard("a", HistoryType.STANDARD_7, 30, 3, HistoryResult.BROKEN),
                standard("b", HistoryType.STANDARD_3, 10, 3, HistoryResult.COMPLETED, planned = 3),
                standard("c", HistoryType.STANDARD_5, 20, 2, HistoryResult.BROKEN, planned = 5),
            ),
            null, null,
        )
        assertEquals(Statistics.Best(3, 3), s.best)
    }

    @Test
    fun `nights, days or both - by the windows, entries without a window read as nights`() {
        fun unit(vararg w: DailyWindow?) =
            Statistics.of(w.mapIndexed { i, it -> standard("r$i", HistoryType.STANDARD_3, 10L + i, 3, HistoryResult.COMPLETED, it, planned = 3) }, null, null).unit
        assertEquals(Statistics.PeriodUnit.NIGHTS, unit(night))
        assertEquals(Statistics.PeriodUnit.DAYS, unit(day))
        assertEquals(Statistics.PeriodUnit.NIGHTS_AND_DAYS, unit(night, day))
        assertEquals(Statistics.PeriodUnit.NIGHTS, unit(null))
        assertEquals(Statistics.PeriodUnit.DAYS, unit(null, day))
    }

    @Test
    fun `dates read calmly`() {
        val y = 2026
        assertEquals("12–19 октября", Statistics.dateRange(LocalDate.of(y, 10, 12), LocalDate.of(y, 10, 19), y))
        assertEquals("28 сентября – 4 октября", Statistics.dateRange(LocalDate.of(y, 9, 28), LocalDate.of(y, 10, 4), y))
        assertEquals("19 октября", Statistics.dateRange(null, LocalDate.of(y, 10, 19), y))
        assertEquals("12–19 октября 2025", Statistics.dateRange(LocalDate.of(2025, 10, 12), LocalDate.of(2025, 10, 19), y))
        assertEquals("28 декабря 2025 – 4 января 2026", Statistics.dateRange(LocalDate.of(2025, 12, 28), LocalDate.of(2026, 1, 4), y))
    }
}
