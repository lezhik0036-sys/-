package app.mama.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

class TestModeTest {
    private val now = t("2026-10-01T14:12:37Z")

    @Test
    fun `test mode locks immediately for exactly 15 minutes`() {
        val e = engine()
        val plan = TestMode.plan(now, MSK, SessionMode.DETOX)
        assertEquals(15.minutes, (plan.end - plan.start))
        val s = e.created(plan, now)
        assertEquals(SessionStatus.ACTIVE, s.status)
        assertEquals(LockState.Locked(now + 15.minutes), e.stateOf(s, now))
    }

    @Test
    fun `test mode completes on its own and can be left with the contact code`() {
        val e = engine()
        val s = e.created(TestMode.plan(now, MSK, SessionMode.DETOX), now)
        assertEquals(FinishReason.COMPLETED, e.advance(s, now + 15.minutes).finishReason)
        assertEquals(LockState.Free, e.stateOf(s, now + 15.minutes))

        val asked = e.requestCode(s, ExitKind.END_SESSION, now + 1.minutes)
        assertIs<LockEngine.CodeRequestResult.Issued>(asked)
        val done = e.submitCode(asked.session, asked.plainCode, now + 2.minutes)
        assertIs<LockEngine.CodeResult.Accepted>(done)
        assertEquals(FinishReason.EXITED_WITH_CONTACT, done.session.finishReason)
    }
}
