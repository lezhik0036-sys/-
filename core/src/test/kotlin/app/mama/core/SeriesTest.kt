package app.mama.core

import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesTest {
    private val engine = engine()
    private val series = SeriesEngine(engine)
    private val window = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), MSK)
    private val evening = t("2026-09-23T15:00:00Z") // 18:00 MSK
    private var ids = 0
    private fun id() = "s${++ids}"

    private fun startSeven() = series.start("series", id(), SeriesKind.SEVEN, window, MOM, SessionMode.SLEEP, evening)

    /** Runs the current night to its end and folds it into the series. */
    private fun completeNight(u: SeriesEngine.Update): SeriesEngine.Update {
        val night = u.session!!
        val done = engine.advance(night, night.plan.end)
        return series.afterSession(u.series, done, id(), night.plan.end)
    }

    @Test
    fun `prices for restart`() {
        assertEquals(listOf(99, 199, 299, 399), SeriesKind.entries.map { it.restartPriceRub })
        assertEquals(30, SeriesKind.FLEX.withinDays)
    }

    @Test
    fun `fixed series plans tonight on start`() {
        val u = startSeven()
        assertEquals(t("2026-09-23T20:00:00Z"), u.session!!.plan.start)
        assertEquals(0, u.series.completedPeriods)
    }

    @Test
    fun `completed night counts and plans the next one`() {
        val u = completeNight(startSeven())
        assertEquals(1, u.series.completedPeriods)
        assertEquals(t("2026-09-24T20:00:00Z"), u.session!!.plan.start)
        assertEquals(SessionStatus.SCHEDULED, u.session!!.status)
    }

    @Test
    fun `a night is counted only once`() {
        val u1 = completeNight(startSeven())
        val counted = engine.advance(startSeven().session!!.copy(id = u1.series.lastSessionId!!), t("2026-09-24T04:00:00Z"))
        val u2 = series.afterSession(u1.series, counted, id(), t("2026-09-24T04:00:01Z"))
        assertEquals(1, u2.series.completedPeriods)
    }

    @Test
    fun `seven nights complete the series`() {
        var u = startSeven()
        repeat(7) { u = completeNight(u) }
        assertEquals(7, u.series.completedPeriods)
        assertEquals(SeriesStatus.COMPLETED, u.series.status)
    }

    @Test
    fun `exit with contact code breaks the series`() {
        var u = completeNight(startSeven())
        val during = u.session!!.plan.start + Duration.ofHours(1)
        val issued = engine.requestCode(u.session!!, ExitKind.END_SESSION, during) as LockEngine.CodeRequestResult.Issued
        val exited = (engine.submitCode(issued.session, issued.plainCode, during) as LockEngine.CodeResult.Accepted).session
        u = series.afterSession(u.series, exited, id(), during)
        assertEquals(SeriesStatus.BROKEN, u.series.status)
        assertEquals(1, u.series.completedPeriods)
    }

    @Test
    fun `emergency pass does not break the series`() {
        val u = startSeven()
        val during = u.session!!.plan.start + Duration.ofHours(1)
        val issued = engine.requestCode(u.session!!, ExitKind.EMERGENCY, during) as LockEngine.CodeRequestResult.Issued
        val passed = (engine.submitCode(issued.session, issued.plainCode, during) as LockEngine.CodeResult.Accepted).session
        val after = series.afterSession(u.series, passed, id(), during)
        assertEquals(SeriesStatus.ACTIVE, after.series.status)
    }

    @Test
    fun `cancelling an upcoming night breaks the series`() {
        val u = startSeven()
        val cancelled = (engine.cancelBeforeStart(u.session!!, evening) as LockEngine.CancelResult.Cancelled).session
        assertEquals(SeriesStatus.BROKEN, series.afterSession(u.series, cancelled, id(), evening).series.status)
    }

    @Test
    fun `restart begins from zero with the same settings`() {
        val broken = series.afterSession(
            startSeven().series, null, id(), evening,
        ).series.copy(status = SeriesStatus.BROKEN, completedPeriods = 4)
        val r = series.restart(broken, "series2", id(), evening)
        assertEquals(0, r.series.completedPeriods)
        assertEquals(SeriesStatus.ACTIVE, r.series.status)
        assertEquals(SeriesKind.SEVEN, r.series.kind)
        assertNotNull(r.session)
    }

    @Test
    fun `flex waits for the user and has a 30 day deadline`() {
        val u = series.start("flex", id(), SeriesKind.FLEX, window, MOM, SessionMode.SLEEP, evening)
        assertNull(u.session)
        assertEquals(evening + Duration.ofDays(30), u.series.deadline)
        val night = series.startFlexPeriod(u.series, id(), evening)
        assertNotNull(night)
        val done = engine.advance(night, night.plan.end)
        val after = series.afterSession(u.series, done, id(), night.plan.end)
        assertEquals(1, after.series.completedPeriods)
        assertTrue(after.session!!.status == SessionStatus.FINISHED) // no auto-planned night
    }

    @Test
    fun `flex past its deadline breaks`() {
        val u = series.start("flex", id(), SeriesKind.FLEX, window, MOM, SessionMode.SLEEP, evening)
        val late = evening + Duration.ofDays(31)
        assertEquals(SeriesStatus.BROKEN, series.afterSession(u.series, null, id(), late).series.status)
        assertNull(series.startFlexPeriod(u.series, id(), late))
    }

    @Test
    fun `series survives the codec`() {
        val u = completeNight(startSeven())
        val snap = Snapshot(u.session, ClockAnchor(evening, 1, "b"), evening, u.series)
        assertEquals(snap, StateCodec.decode(StateCodec.encode(snap)))
    }
}
