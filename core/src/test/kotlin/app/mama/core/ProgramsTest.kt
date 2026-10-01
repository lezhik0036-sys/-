package app.mama.core

import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgramsTest {
    private val engine = engine()
    private val flex = FlexEngine(engine)
    private val window = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), MSK)
    private val day0 = t("2026-10-01T15:00:00Z") // 18:00 MSK
    private var n = 0
    private fun id() = "x${++n}"

    // --- standard series after a failure ---

    @Test
    fun `free start is available without a failure`() {
        assertTrue(StandardRules.canStartFree(Entitlements(), day0))
        assertEquals(0, StandardRules.daysUntilFreeStart(Entitlements(), day0))
    }

    @Test
    fun `free start waits 30 calendar days after a failure`() {
        val e = Entitlements(standardFailedAt = day0)
        assertFalse(StandardRules.canStartFree(e, day0 + Duration.ofDays(6)))
        assertEquals(24, StandardRules.daysUntilFreeStart(e, day0 + Duration.ofDays(6)))
        assertEquals(1, StandardRules.daysUntilFreeStart(e, day0 + Duration.ofDays(29) + Duration.ofHours(1)))
        assertTrue(StandardRules.canStartFree(e, day0 + Duration.ofDays(30)))
    }

    // --- FLEX ---

    /** Tonight 23:00 → 07:00 MSK relative to [now]'s date (now must be before 20:00Z). */
    private fun startPeriod(p: FlexPackage, now: java.time.Instant): Pair<FlexPackage, Session> {
        val plan = window.nextPlan(now, SessionMode.SLEEP)
        val r = flex.planPeriod(p, plan.start, plan.end, MSK, MOM, SessionMode.SLEEP, id(), now)
        val ok = r as FlexEngine.PlanResult.Planned
        return ok.pkg to ok.session
    }

    private fun msk(iso: String) = java.time.LocalDateTime.parse(iso).atZone(MSK).toInstant()

    private fun plan(start: String?, end: String, now: String): FlexEngine.PlanResult =
        flex.planPeriod(
            flex.activate("f", GrantSource.TEST_NO_PAYMENT, msk(now)),
            start?.let(::msk), msk(end), MSK, MOM, SessionMode.SLEEP, id(), msk(now),
        )

    private fun error(r: FlexEngine.PlanResult) = (r as? FlexEngine.PlanResult.Rejected)?.error

    @Test
    fun `flex start rule - valid examples at 17 00`() {
        val now = "2026-10-01T17:00"
        for ((s, e) in listOf(
            "2026-10-01T17:00" to "2026-10-01T20:00",
            "2026-10-01T17:00" to "2026-10-01T23:00",
            "2026-10-01T19:00" to "2026-10-01T23:00",
            "2026-10-01T23:00" to "2026-10-02T07:00",
            "2026-10-02T14:00" to "2026-10-02T20:00",
        )) {
            assertTrue(plan(s, e, now) is FlexEngine.PlanResult.Planned, "$s -> $e")
        }
    }

    @Test
    fun `flex start rule - past starts are rejected, never shortened`() {
        val now = "2026-10-01T17:00"
        for ((s, e) in listOf(
            "2026-10-01T15:00" to "2026-10-01T20:00",
            "2026-10-01T14:00" to "2026-10-02T08:00",
            "2026-10-01T16:30" to "2026-10-01T22:00",
        )) {
            assertEquals(FlexEngine.PlanError.START_IN_PAST, error(plan(s, e, now)), "$s -> $e")
        }
    }

    @Test
    fun `start now uses the actual moment and needs 3 hours`() {
        val now = "2026-10-01T17:12"
        val ok = plan(null, "2026-10-01T20:12", now) as FlexEngine.PlanResult.Planned
        assertEquals(msk(now), ok.session.plan.start)
        assertEquals(FlexEngine.PlanError.TOO_SHORT, error(plan(null, "2026-10-01T20:11", now)))
    }

    @Test
    fun `minimum flex duration is 3 hours`() {
        val now = "2026-10-01T17:00"
        assertEquals(FlexEngine.PlanError.TOO_SHORT, error(plan("2026-10-01T19:00", "2026-10-01T21:59", now)))
        assertTrue(plan("2026-10-01T19:00", "2026-10-01T22:00", now) is FlexEngine.PlanResult.Planned)
        assertEquals(FlexEngine.PlanError.END_BEFORE_START, error(plan("2026-10-01T19:00", "2026-10-01T18:00", now)))
    }

    @Test
    fun `start now never backdates and default end rounds up`() {
        val now = msk("2026-10-01T17:12:00").plusSeconds(37)
        val p = flex.activate("f", GrantSource.TEST_NO_PAYMENT, now)
        val end = FlexPackage.defaultEndForStartNow(now)
        assertEquals(msk("2026-10-01T20:13"), end)
        val ok = flex.planPeriod(p, null, end, MSK, MOM, SessionMode.SLEEP, id(), now) as FlexEngine.PlanResult.Planned
        assertEquals(now, ok.session.plan.start)
        assertTrue(Duration.between(ok.session.plan.start, ok.session.plan.end) >= Duration.ofHours(3))
        // 20:12 would be 2 h 59 min 23 s from the real start.
        assertEquals(
            FlexEngine.PlanError.TOO_SHORT,
            error(flex.planPeriod(p, null, msk("2026-10-01T20:12"), MSK, MOM, SessionMode.SLEEP, id(), now)),
        )
        // On an exact minute the default end is exactly 3 hours later.
        assertEquals(msk("2026-10-01T20:12"), FlexPackage.defaultEndForStartNow(msk("2026-10-01T17:12")))
    }

    @Test
    fun `current minute counts as now and starts at the real moment`() {
        val now = msk("2026-10-01T17:12:00").plusSeconds(37)
        val p = flex.activate("f", GrantSource.TEST_NO_PAYMENT, now)
        val r = flex.planPeriod(p, msk("2026-10-01T17:12"), msk("2026-10-01T20:13"), MSK, MOM, SessionMode.SLEEP, id(), now)
        assertEquals(now, (r as FlexEngine.PlanResult.Planned).session.plan.start)
    }

    @Test
    fun `crossing midnight up to 24 hours is valid, longer is not, no automatic next day`() {
        val now = "2026-10-01T13:00"
        assertTrue(plan("2026-10-01T14:00", "2026-10-02T08:00", now) is FlexEngine.PlanResult.Planned)
        assertTrue(plan("2026-10-01T14:00", "2026-10-02T14:00", now) is FlexEngine.PlanResult.Planned)
        assertEquals(FlexEngine.PlanError.TOO_LONG, error(plan("2026-10-01T14:00", "2026-10-02T14:01", now)))
        // today 23:00 -> today 07:00 is not turned into tomorrow 07:00
        assertEquals(FlexEngine.PlanError.END_BEFORE_START, error(plan("2026-10-01T23:00", "2026-10-01T07:00", now)))
    }

    @Test
    fun `a period may end after expiry if it starts before`() {
        val activated = msk("2026-10-01T00:00")
        val p = flex.activate("f", GrantSource.TEST_NO_PAYMENT, activated) // expires 31 Oct 00:00
        val now = msk("2026-10-30T22:00")
        val r = flex.planPeriod(p, msk("2026-10-30T23:00"), msk("2026-10-31T07:00"), MSK, MOM, SessionMode.SLEEP, id(), now)
        val planned = r as FlexEngine.PlanResult.Planned
        // runs past expiry and still counts as a successful day
        val started = engine.advance(planned.session, planned.session.plan.start)
        var pkg = flex.afterSession(planned.pkg, started, planned.session.plan.start).pkg
        val midway = msk("2026-10-31T03:00")
        pkg = flex.afterSession(pkg, engine.advance(started, midway), midway).pkg
        assertFalse(pkg.over)
        assertTrue(engine.stateOf(started, midway).isLocked)
        val done = engine.advance(started, planned.session.plan.end)
        pkg = flex.afterSession(pkg, done, planned.session.plan.end).pkg
        assertEquals(1, pkg.successful)
        assertTrue(pkg.over) // expired, no more periods
    }

    @Test
    fun `expired package and start after expiry have distinct errors`() {
        val p = flex.activate("f", GrantSource.TEST_NO_PAYMENT, msk("2026-10-01T00:00"))
        val beforeExpiry = msk("2026-10-30T20:00")
        assertEquals(
            FlexEngine.PlanError.AFTER_EXPIRY,
            error(flex.planPeriod(p, msk("2026-10-31T00:00"), msk("2026-10-31T05:00"), MSK, MOM, SessionMode.SLEEP, id(), beforeExpiry)),
        )
        val afterExpiry = msk("2026-10-31T00:00")
        assertEquals(
            FlexEngine.PlanError.PACKAGE_EXPIRED,
            error(flex.planPeriod(p, null, msk("2026-10-31T05:00"), MSK, MOM, SessionMode.SLEEP, id(), afterExpiry)),
        )
    }

    @Test
    fun `flex example - success then exit keeps the rest`() {
        var p = flex.activate("f", GrantSource.TEST_NO_PAYMENT, day0)
        p = night(p, day0)
        assertEquals(1, p.successful)
        assertEquals(6, p.remaining)
        p = night(p, day0 + Duration.ofDays(1), exitEarly = true)
        assertEquals(1, p.successful)
        assertEquals(5, p.remaining)
        assertEquals(1, p.failed)
    }

    @Test
    fun `cannot start after the package expired`() {
        val now = "2026-10-01T17:00"
        assertEquals(
            FlexEngine.PlanError.AFTER_EXPIRY,
            error(plan("2026-10-31T18:00", "2026-10-31T23:00", now)),
        )
    }

    /** Plans tonight, runs it to the end (or exits early) and settles it. */
    private fun night(p: FlexPackage, now: java.time.Instant, exitEarly: Boolean = false): FlexPackage {
        val (planned, session) = startPeriod(p, now)
        val started = engine.advance(session, session.plan.start)
        var pkg = flex.afterSession(planned, started, session.plan.start).pkg
        assertTrue(pkg.inProgress)
        val finished = if (exitEarly) {
            val at = session.plan.start + Duration.ofHours(1)
            val issued = engine.requestCode(started, ExitKind.END_SESSION, at) as LockEngine.CodeRequestResult.Issued
            (engine.submitCode(issued.session, issued.plainCode, at) as LockEngine.CodeResult.Accepted).session
        } else {
            engine.advance(started, session.plan.end)
        }
        pkg = flex.afterSession(pkg, finished, session.plan.end).pkg
        return pkg
    }

    @Test
    fun `package is 7 periods for 30 days at 399`() {
        val p = flex.activate("f", GrantSource.TEST_NO_PAYMENT, day0)
        assertEquals(7, p.remaining)
        assertEquals(day0 + Duration.ofDays(30), p.expiresAt)
        assertEquals(399, FlexPackage.PRICE_RUB)
    }

    @Test
    fun `a period is consumed when it starts, not when planned`() {
        val p = flex.activate("f", GrantSource.TEST_NO_PAYMENT, day0)
        val (planned, session) = startPeriod(p, day0)
        assertEquals(0, flex.afterSession(planned, session, day0).pkg.used)
        val started = engine.advance(session, session.plan.start)
        assertEquals(1, flex.afterSession(planned, started, session.plan.start).pkg.used)
    }

    @Test
    fun `cancelling before start does not consume`() {
        val p = flex.activate("f", GrantSource.TEST_NO_PAYMENT, day0)
        val (planned, session) = startPeriod(p, day0)
        val cancelled = (engine.cancelBeforeStart(session, day0) as LockEngine.CancelResult.Cancelled).session
        val after = flex.afterSession(planned, cancelled, day0).pkg
        assertEquals(0, after.used)
        assertNull(after.currentSessionId)
        assertTrue(flex.canStartPeriod(after, day0))
    }

    @Test
    fun `successful and failed days are counted, failure burns only that day`() {
        var p = flex.activate("f", GrantSource.TEST_NO_PAYMENT, day0)
        p = night(p, day0)
        assertEquals(6, p.remaining)
        assertEquals(1, p.successful)
        p = night(p, day0 + Duration.ofDays(2), exitEarly = true)
        assertEquals(5, p.remaining)
        assertEquals(1, p.successful)
        assertEquals(1, p.failed)
        assertFalse(p.rewardStillPossible)
        assertFalse(p.over)
        assertTrue(flex.canStartPeriod(p, day0 + Duration.ofDays(3)))
    }

    @Test
    fun `7 of 7 earns the next package exactly once`() {
        var p = flex.activate("f", GrantSource.TEST_NO_PAYMENT, day0)
        var rewards = 0
        repeat(7) { i ->
            val (planned, session) = startPeriod(p, day0 + Duration.ofDays(i * 3L))
            val started = engine.advance(session, session.plan.start)
            p = flex.afterSession(planned, started, session.plan.start).pkg
            val done = engine.advance(started, session.plan.end)
            val u = flex.afterSession(p, done, session.plan.end)
            if (u.rewardEarnedNow) rewards++
            p = u.pkg
        }
        assertEquals(7, p.successful)
        assertTrue(p.earnedReward)
        assertEquals(1, rewards)
        assertTrue(p.over)
    }

    @Test
    fun `6 of 7 earns nothing`() {
        var p = flex.activate("f", GrantSource.TEST_NO_PAYMENT, day0)
        repeat(7) { i -> p = night(p, day0 + Duration.ofDays(i * 2L), exitEarly = i == 3) }
        assertEquals(6, p.successful)
        assertFalse(p.earnedReward)
        assertTrue(p.over)
    }

    @Test
    fun `unused days expire after 30 days`() {
        var p = flex.activate("f", GrantSource.TEST_NO_PAYMENT, day0)
        p = night(p, day0)
        val late = day0 + Duration.ofDays(30)
        assertFalse(flex.canStartPeriod(p, late))
        p = flex.afterSession(p, null, late).pkg
        assertTrue(p.over)
        assertEquals(6, p.remaining)
    }

    @Test
    fun `no new period after expiry or while one is planned`() {
        val p = flex.activate("f", GrantSource.TEST_NO_PAYMENT, day0)
        val (planned, _) = startPeriod(p, day0)
        val tonight = window.nextPlan(day0, SessionMode.SLEEP)
        assertEquals(
            FlexEngine.PlanError.NOT_AVAILABLE,
            error(flex.planPeriod(planned, tonight.start, tonight.end, MSK, MOM, SessionMode.SLEEP, id(), day0)),
        )
        val late = day0 + Duration.ofDays(31)
        assertEquals(
            FlexEngine.PlanError.PACKAGE_EXPIRED,
            error(flex.planPeriod(p, null, late + Duration.ofHours(4), MSK, MOM, SessionMode.SLEEP, id(), late)),
        )
    }

    @Test
    fun `entitlements survive the codec`() {
        var p = flex.activate("f", GrantSource.REWARD, day0)
        p = night(p, day0, exitEarly = true)
        val e = Entitlements(standardFailedAt = day0, flex = p, freeFlexCredits = 2)
        val snap = Snapshot(entitlements = e)
        assertEquals(snap, StateCodec.decode(StateCodec.encode(snap)))
        assertNotNull(StateCodec.decode(StateCodec.encode(snap)).entitlements.flex)
    }
}
