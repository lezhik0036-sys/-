package app.mama.core

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TestModeTest {
    private val now = t("2026-10-01T14:12:37Z")

    @Test
    fun `test mode locks immediately for exactly 15 minutes`() {
        val e = engine()
        val plan = TestMode.plan(now, MSK, SessionMode.DETOX)
        assertEquals(Duration.ofMinutes(15), Duration.between(plan.start, plan.end))
        val s = e.created(plan, now)
        assertEquals(SessionStatus.ACTIVE, s.status)
        assertEquals(LockState.Locked(now + Duration.ofMinutes(15)), e.stateOf(s, now))
    }

    @Test
    fun `test mode completes on its own and can be left with the contact code`() {
        val e = engine()
        val s = e.created(TestMode.plan(now, MSK, SessionMode.DETOX), now)
        assertEquals(FinishReason.COMPLETED, e.advance(s, now + Duration.ofMinutes(15)).finishReason)
        assertEquals(LockState.Free, e.stateOf(s, now + Duration.ofMinutes(15)))

        val asked = e.requestCode(s, ExitKind.END_SESSION, now + Duration.ofMinutes(1))
        assertIs<LockEngine.CodeRequestResult.Issued>(asked)
        val done = e.submitCode(asked.session, asked.plainCode, now + Duration.ofMinutes(2))
        assertIs<LockEngine.CodeResult.Accepted>(done)
        assertEquals(FinishReason.EXITED_WITH_CONTACT, done.session.finishReason)
    }
}
