package app.mama.core

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** SeriesHistory: records of results the series and FLEX engines settled, without changing them. */
class SeriesHistoryTest {
    private val engine = engine()
    private val series = SeriesEngine(engine)
    private val flex = FlexEngine(engine)
    private val window = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), MSK)
    private val evening = t("2026-09-23T15:00:00Z") // 18:00 MSK
    private val later = t("2026-12-01T10:00:00Z")
    private var ids = 0
    private fun id() = "s${++ids}"

    // --- STANDARD, driven through the real series engine (as in SeriesTest) ---

    private fun start(kind: SeriesKind, restartOf: String? = null) =
        series.start("series-$kind", id(), kind, window, MOM, SessionMode.SLEEP, evening, restartOf = restartOf)

    private fun confirmNight(u: SeriesEngine.Update): SeriesEngine.Update {
        if (u.series.committed) return u
        val night = u.session!!
        val begun = engine.advance(night, night.plan.start)
        val activating = series.afterSession(u.series, begun, id(), night.plan.start)
        return SeriesEngine.Update(series.confirmActive(activating.series, begun, night.plan.start)!!, begun)
    }

    private fun completeNight(u: SeriesEngine.Update): SeriesEngine.Update {
        val running = confirmNight(u)
        val night = running.session!!
        return series.afterSession(running.series, engine.advance(night, night.plan.end), id(), night.plan.end)
    }

    /** Trusted Exit one hour into the current night. */
    private fun exitTonight(u: SeriesEngine.Update): SeriesEngine.Update {
        val running = confirmNight(u)
        val during = running.session!!.plan.start + Duration.ofHours(1)
        val issued = engine.requestCode(running.session!!, ExitKind.END_SESSION, during) as LockEngine.CodeRequestResult.Issued
        val exited = (engine.submitCode(issued.session, issued.plainCode, during) as LockEngine.CodeResult.Accepted).session
        return series.afterSession(running.series, exited, id(), during)
    }

    @Test
    fun `a completed 7-night series - the exact record`() {
        var u = start(SeriesKind.SEVEN)
        val firstLock = u.session!!.plan.start
        repeat(7) { u = completeNight(u) }
        assertEquals(SeriesStatus.COMPLETED, u.series.status)

        val r = assertNotNull(SeriesHistory.of(u.series, later))
        assertEquals("series-SEVEN", r.id)
        assertEquals(HistoryType.STANDARD_7, r.type)
        assertEquals(firstLock, r.startedAt, "the first actual switch-on of protection, not the confirmation")
        assertEquals(u.series.endedAt, r.endedAt)
        assertEquals(HistoryResult.COMPLETED, r.result)
        assertEquals(7, r.plannedNights)
        assertNull(r.consumedNights, "kept for FLEX only")
        assertEquals(7, r.successfulNights)
        assertEquals(window, r.window)
        assertNull(r.restartOf)
    }

    @Test
    fun `a broken 5-night series - BROKEN with the nights done before the exit`() {
        var u = start(SeriesKind.FIVE)
        repeat(2) { u = completeNight(u) }
        u = exitTonight(u)
        assertEquals(SeriesStatus.BROKEN, u.series.status)

        val r = assertNotNull(SeriesHistory.of(u.series, later))
        assertEquals(HistoryType.STANDARD_5, r.type)
        assertEquals(HistoryResult.BROKEN, r.result)
        assertEquals(5, r.plannedNights)
        assertEquals(2, r.successfulNights)
        assertEquals(u.series.endedAt, r.endedAt)
    }

    @Test
    fun `a Restart that really began and ended keeps the id of the BROKEN series it continues`() {
        var u = start(SeriesKind.THREE, restartOf = "failed-1")
        repeat(3) { u = completeNight(u) }
        val r = assertNotNull(SeriesHistory.of(u.series, later))
        assertEquals("failed-1", r.restartOf)
        assertEquals(HistoryType.STANDARD_3, r.type)
        assertEquals(HistoryResult.COMPLETED, r.result)
    }

    @Test
    fun `nothing is recorded for a series that has not begun or is still running`() {
        val scheduled = start(SeriesKind.SEVEN)
        assertNull(SeriesHistory.of(scheduled.series, later), "SCHEDULED")
        val active = confirmNight(scheduled)
        assertNull(SeriesHistory.of(active.series, later), "ACTIVE")
        for (status in listOf(SeriesStatus.ACTIVATING, SeriesStatus.NEEDS_ATTENTION, SeriesStatus.CANCELLED_BEFORE_START)) {
            assertNull(SeriesHistory.of(scheduled.series.copy(status = status), later), status.name)
        }
        // A Restart paid for but cancelled before its first lock never becomes a record either.
        assertNull(SeriesHistory.of(start(SeriesKind.SEVEN, restartOf = "failed-1").series.copy(status = SeriesStatus.CANCELLED_BEFORE_START), later))
    }

    @Test
    fun `building a record changes nothing in the series`() {
        var u = start(SeriesKind.THREE)
        repeat(3) { u = completeNight(u) }
        val before = u.series
        SeriesHistory.of(before, later)
        assertEquals(before, u.series)
        assertEquals(before, StateCodec.decode(StateCodec.encode(Snapshot(series = before))).series)
    }

    // --- FLEX, driven through the real FLEX engine (as in ProgramsTest) ---

    private val day0 = t("2026-10-01T15:00:00Z") // 18:00 MSK

    private fun night(p: FlexPackage, now: Instant, exitEarly: Boolean = false): FlexPackage {
        val plan = window.nextPlan(now, SessionMode.SLEEP)
        val planned = flex.planPeriod(p, plan.start, plan.end, MSK, MOM, SessionMode.SLEEP, id(), now) as FlexEngine.PlanResult.Planned
        val started = engine.advance(planned.session, planned.session.plan.start)
        var pkg = flex.afterSession(planned.pkg, started, planned.session.plan.start).pkg
        val finished = if (exitEarly) {
            val at = planned.session.plan.start + Duration.ofHours(1)
            val issued = engine.requestCode(started, ExitKind.END_SESSION, at) as LockEngine.CodeRequestResult.Issued
            (engine.submitCode(issued.session, issued.plainCode, at) as LockEngine.CodeResult.Accepted).session
        } else {
            engine.advance(started, planned.session.plan.end)
        }
        pkg = flex.afterSession(pkg, finished, planned.session.plan.end).pkg
        return pkg
    }

    @Test
    fun `FLEX - 7 used, 6 successful - COMPLETED, the successful ones kept apart`() {
        var p = flex.activate("flex-1", GrantSource.INTERNAL_BALANCE, day0)
        repeat(7) { i -> p = night(p, day0 + Duration.ofDays(i * 2L), exitEarly = i == 3) }
        assertTrue(p.over)

        val r = assertNotNull(SeriesHistory.of(p, later))
        assertEquals("flex-1", r.id)
        assertEquals(HistoryType.FLEX, r.type)
        assertEquals(HistoryResult.COMPLETED, r.result, "all 7 periods used, not all 7 successful")
        assertEquals(7, r.plannedNights)
        assertEquals(7, r.consumedNights)
        assertEquals(6, r.successfulNights)
        assertEquals(day0, r.startedAt, "activation of the package")
        assertEquals(p.endedAt, r.endedAt)
        assertNull(r.window, "FLEX has no single window")
        assertNull(r.restartOf)
    }

    @Test
    fun `FLEX - 4 used, 4 successful, the 30 days over - EXPIRED, never BROKEN`() {
        var p = flex.activate("flex-2", GrantSource.INTERNAL_BALANCE, day0)
        repeat(4) { i -> p = night(p, day0 + Duration.ofDays(i * 2L)) }
        assertNull(SeriesHistory.of(p, later), "still running")
        p = flex.afterSession(p, null, day0 + Duration.ofDays(30)).pkg
        assertTrue(p.over)

        val r = assertNotNull(SeriesHistory.of(p, later))
        assertEquals(HistoryResult.EXPIRED, r.result)
        assertEquals(4, r.consumedNights)
        assertEquals(4, r.successfulNights)
        assertEquals(7, r.plannedNights)
    }

    // --- duplicates, retention, format ---

    private fun record(id: String, endedAt: Instant, nights: Int = 7, result: HistoryResult = HistoryResult.COMPLETED) = HistoryRecord(
        id, HistoryType.STANDARD_7, endedAt - Duration.ofDays(7), endedAt, result, 7, null, nights, window, null,
    )

    @Test
    fun `the same result is recorded once`() {
        val r = record("a", later)
        val once = SeriesHistory.append(emptyList(), r)
        assertSame(once, SeriesHistory.append(once, r), "same record again")
        assertSame(once, SeriesHistory.append(once, r.copy(successfulNights = 6)), "same id, nothing overwritten")
        assertEquals(listOf(r), once)
    }

    @Test
    fun `newest first and nothing is dropped - no 100-record limit`() {
        var all = emptyList<HistoryRecord>()
        repeat(250) { i -> all = SeriesHistory.append(all, record("r$i", later + Duration.ofDays(i.toLong()))) }
        assertEquals(250, all.size)
        assertEquals("r249", all.first().id)
        assertEquals("r0", all.last().id)
        assertEquals(all, HistoryCodec.read(HistoryCodec.write(all), "", ""))
    }

    @Test
    fun `the line format keeps every field, empty ones included`() {
        val standard = record("std", later).copy(restartOf = "failed-1", window = DailyWindow(LocalTime.of(16, 20), LocalTime.of(0, 20), MSK))
        val flexRecord = HistoryRecord("flex", HistoryType.FLEX, day0, later, HistoryResult.EXPIRED, 7, 4, 3, null, null)
        val legacyLike = record("no-start", later).copy(startedAt = null, window = null)
        for (r in listOf(standard, flexRecord, legacyLike)) assertEquals(r, HistoryCodec.decode(HistoryCodec.encode(r)))
        // Instants are kept to the millisecond.
        val precise = record("ms", Instant.parse("2026-10-20T15:00:00.123456789Z"))
        assertEquals(Instant.parse("2026-10-20T15:00:00.123Z"), HistoryCodec.decode(HistoryCodec.encode(precise))!!.endedAt)
        // Unreadable lines are skipped, never a crash.
        assertNull(HistoryCodec.decode("garbage"))
        assertNull(HistoryCodec.decode("v2|x|STANDARD_9|||||||||||"))
    }

    // --- entries written before this model ---

    @Test
    fun `old entries are read and converted - start and window were not kept, so they stay empty`() {
        val ended = Instant.parse("2026-09-30T04:00:00Z").toEpochMilli()
        val oldSeries = "$ended|SEVEN|4|BROKEN\n${ended - 86_400_000}|THREE|3|COMPLETED\nbroken line"
        val oldFlex = "${ended + 1000}|FLEX|6|7\n${ended + 2000}|FLEX|4|4"

        val all = HistoryCodec.read("", oldSeries, oldFlex)
        assertEquals(4, all.size, "the unreadable line is skipped")
        val broken = all.single { it.type == HistoryType.STANDARD_7 }
        assertEquals(HistoryResult.BROKEN, broken.result)
        assertEquals(4, broken.successfulNights)
        assertEquals(7, broken.plannedNights)
        assertEquals(Instant.ofEpochMilli(ended), broken.endedAt)
        assertNull(broken.startedAt)
        assertNull(broken.window)
        assertNull(broken.restartOf)
        val flexes = all.filter { it.type == HistoryType.FLEX }
        assertEquals(HistoryResult.COMPLETED, flexes.single { it.consumedNights == 7 }.result)
        assertEquals(HistoryResult.EXPIRED, flexes.single { it.consumedNights == 4 }.result)
        assertEquals(listOf(ended + 2000, ended + 1000, ended, ended - 86_400_000), all.map { it.endedAt.toEpochMilli() })
    }

    @Test
    fun `an old entry and a new record of the same result count once`() {
        var u = start(SeriesKind.SEVEN)
        repeat(2) { u = completeNight(u) }
        u = exitTonight(u)
        val now = SeriesHistory.of(u.series, later)!!
        val old = "${u.series.endedAt!!.toEpochMilli()}|SEVEN|2|BROKEN"
        val all = HistoryCodec.read(HistoryCodec.write(listOf(now)), old, "")
        assertEquals(listOf(now.id), all.map { it.id }, "the new record wins, the old line is not counted again")
    }
}
