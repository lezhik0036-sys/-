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

    private fun startPeriod(p: FlexPackage, now: java.time.Instant): Pair<FlexPackage, Session> =
        flex.planPeriod(p, window, MOM, SessionMode.SLEEP, id(), now)!!

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
        assertNull(flex.planPeriod(planned, window, MOM, SessionMode.SLEEP, id(), day0))
        assertNull(flex.planPeriod(p, window, MOM, SessionMode.SLEEP, id(), day0 + Duration.ofDays(31)))
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
