package app.mama.core

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LockEngineTest {
    private val start = t("2026-09-23T20:00:00Z")
    private val end = t("2026-09-24T04:00:00Z")
    private val plan = LockPlan(start, end, MSK)
    private val before = t("2026-09-23T19:00:00Z")
    private val during = t("2026-09-23T22:00:00Z")

    @Test
    fun `waits then locks then frees`() {
        val e = engine()
        val s = e.created(plan, before)
        assertEquals(LockState.Waiting(start, end), e.stateOf(s, before))
        assertEquals(LockState.Locked(end), e.stateOf(s, start))
        assertEquals(LockState.Locked(end), e.stateOf(s, end.minusMillis(1)))
        assertEquals(LockState.Free, e.stateOf(s, end))
        assertEquals(FinishReason.COMPLETED, e.advance(s, end).finishReason)
    }

    @Test
    fun `plan already running locks immediately`() {
        val e = engine()
        val s = e.created(plan, during)
        assertEquals(SessionStatus.ACTIVE, s.status)
        assertTrue(e.stateOf(s, during).isLocked)
    }

    @Test
    fun `too short, too long and past plans are rejected`() {
        val e = engine()
        val short = LockPlan(start, start + Duration.ofMinutes(10), MSK)
        assertEquals(LockEngine.CreateError.TOO_SHORT, (e.create("x", short, MOM, before) as LockEngine.CreateResult.Rejected).reason)
        val long = LockPlan(start, start + Duration.ofHours(25), MSK)
        assertEquals(LockEngine.CreateError.TOO_LONG, (e.create("x", long, MOM, before) as LockEngine.CreateResult.Rejected).reason)
        assertEquals(LockEngine.CreateError.ALREADY_ENDED, (e.create("x", plan, MOM, end) as LockEngine.CreateResult.Rejected).reason)
    }

    @Test
    fun `cancel is allowed only before start`() {
        val e = engine()
        val s = e.created(plan, before)
        val cancelled = e.cancelBeforeStart(s, before)
        assertIs<LockEngine.CancelResult.Cancelled>(cancelled)
        assertEquals(FinishReason.CANCELLED_BEFORE_START, cancelled.session.finishReason)
        assertEquals(LockEngine.CancelResult.NotAllowed, e.cancelBeforeStart(s, during))
    }

    @Test
    fun `correct contact code ends the session`() {
        val e = engine()
        val s = e.created(plan, during)
        val issued = e.requestCode(s, ExitKind.END_SESSION, during) as LockEngine.CodeRequestResult.Issued
        assertEquals(5, issued.plainCode.length)
        val result = e.submitCode(issued.session, issued.plainCode, during)
        assertIs<LockEngine.CodeResult.Accepted>(result)
        assertEquals(FinishReason.EXITED_WITH_CONTACT, result.session.finishReason)
        assertEquals(LockState.Free, e.stateOf(result.session, during))
    }

    @Test
    fun `plain code is not stored in the session`() {
        val e = engine()
        val issued = e.requestCode(e.created(plan, during), ExitKind.END_SESSION, during) as LockEngine.CodeRequestResult.Issued
        val encoded = StateCodec.encode(Snapshot(issued.session)).values
        assertTrue(encoded.none { it.contains(issued.plainCode) })
    }

    @Test
    fun `three wrong codes burn the code and start cooldown`() {
        val e = engine()
        var s = (e.requestCode(e.created(plan, during), ExitKind.END_SESSION, during) as LockEngine.CodeRequestResult.Issued).session
        val r1 = e.submitCode(s, "00000", during) as LockEngine.CodeResult.Wrong
        assertEquals(2, r1.attemptsLeft)
        val r2 = e.submitCode(r1.session, "00000", during) as LockEngine.CodeResult.Wrong
        assertEquals(1, r2.attemptsLeft)
        val r3 = e.submitCode(r2.session, "00000", during)
        assertIs<LockEngine.CodeResult.Exhausted>(r3)
        s = r3.session
        assertNull(s.challenge)
        assertTrue(e.stateOf(s, during).isLocked)

        // Even the right code does nothing now; a new one can't be requested yet.
        assertIs<LockEngine.CodeResult.NoActiveCode>(e.submitCode(s, "11111", during))
        val rejected = e.requestCode(s, ExitKind.END_SESSION, during) as LockEngine.CodeRequestResult.Rejected
        assertEquals(LockEngine.CodeRequestError.COOLDOWN, rejected.reason)
        assertEquals(during + Duration.ofMinutes(30), rejected.retryAt)

        // After the cooldown a new code can be requested.
        val later = during + Duration.ofMinutes(30)
        assertIs<LockEngine.CodeRequestResult.Issued>(e.requestCode(s, ExitKind.END_SESSION, later))
    }

    @Test
    fun `malformed input counts as a wrong attempt`() {
        val e = engine()
        val s = (e.requestCode(e.created(plan, during), ExitKind.END_SESSION, during) as LockEngine.CodeRequestResult.Issued).session
        assertIs<LockEngine.CodeResult.Wrong>(e.submitCode(s, "1111", during))
        assertIs<LockEngine.CodeResult.Wrong>(e.submitCode(s, "abcde", during))
    }

    @Test
    fun `code expires`() {
        val e = engine()
        val issued = e.requestCode(e.created(plan, during), ExitKind.END_SESSION, during) as LockEngine.CodeRequestResult.Issued
        val late = during + Duration.ofMinutes(15)
        assertIs<LockEngine.CodeResult.NoActiveCode>(e.submitCode(issued.session, issued.plainCode, late))
    }

    @Test
    fun `second request while one is pending is rejected`() {
        val e = engine()
        val s = (e.requestCode(e.created(plan, during), ExitKind.END_SESSION, during) as LockEngine.CodeRequestResult.Issued).session
        val r = e.requestCode(s, ExitKind.EMERGENCY, during) as LockEngine.CodeRequestResult.Rejected
        assertEquals(LockEngine.CodeRequestError.ALREADY_PENDING, r.reason)
    }

    @Test
    fun `codes cannot be requested outside a lock`() {
        val e = engine()
        val r = e.requestCode(e.created(plan, before), ExitKind.END_SESSION, before) as LockEngine.CodeRequestResult.Rejected
        assertEquals(LockEngine.CodeRequestError.NOT_LOCKED, r.reason)
    }

    @Test
    fun `emergency pass unlocks temporarily then locks again`() {
        val e = engine()
        val issued = e.requestCode(e.created(plan, during), ExitKind.EMERGENCY, during) as LockEngine.CodeRequestResult.Issued
        val s = (e.submitCode(issued.session, issued.plainCode, during) as LockEngine.CodeResult.Accepted).session
        val until = during + Duration.ofMinutes(15)
        assertEquals(LockState.EmergencyPass(until, end), e.stateOf(s, during))
        assertEquals(LockState.Locked(end), e.stateOf(s, until))
        assertEquals(SessionStatus.ACTIVE, e.advance(s, until).status)
    }

    @Test
    fun `emergency pass never outlives the session`() {
        val e = engine()
        val nearEnd = end - Duration.ofMinutes(5)
        val issued = e.requestCode(e.created(plan, during), ExitKind.EMERGENCY, nearEnd) as LockEngine.CodeRequestResult.Issued
        val s = (e.submitCode(issued.session, issued.plainCode, nearEnd) as LockEngine.CodeResult.Accepted).session
        assertEquals(end, s.emergencyUntil)
        assertEquals(LockState.Free, e.stateOf(s, end))
    }

    @Test
    fun `emergency passes are limited per session`() {
        val e = engine()
        var s = e.created(plan, during)
        var now = during
        repeat(3) {
            val issued = e.requestCode(s, ExitKind.EMERGENCY, now) as LockEngine.CodeRequestResult.Issued
            s = (e.submitCode(issued.session, issued.plainCode, now) as LockEngine.CodeResult.Accepted).session
            now += Duration.ofMinutes(20)
        }
        val r = e.requestCode(s, ExitKind.EMERGENCY, now) as LockEngine.CodeRequestResult.Rejected
        assertEquals(LockEngine.CodeRequestError.EMERGENCY_LIMIT_REACHED, r.reason)
        // Ending the session via the contact is still possible.
        assertIs<LockEngine.CodeRequestResult.Issued>(e.requestCode(s, ExitKind.END_SESSION, now))
    }
}
