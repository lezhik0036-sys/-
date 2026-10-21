package app.mama.core

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [LockEngine.endByTrustedExit]: the additive entry point used by the
 * server-authoritative flow. It ends a locked session exactly like a correct
 * code in [LockEngine.submitCode], without the engine holding or checking the
 * code. The series state machine is unchanged.
 */
class TrustedExitEndTest {
    private val now = t("2026-10-01T23:30:00Z")

    @Test
    fun `ends a locked session as an exit with the contact`() {
        val e = engine()
        val s = e.created(LockPlan(now, now + Duration.ofHours(8), MSK), now)
        assertTrue(e.stateOf(s, now) is LockState.Locked)
        val ended = e.endByTrustedExit(s, ExitKind.END_SESSION, now + Duration.ofHours(1))
        assertEquals(SessionStatus.FINISHED, ended.status)
        assertEquals(FinishReason.EXITED_WITH_CONTACT, ended.finishReason)
        assertEquals(LockState.Free, e.stateOf(ended, now + Duration.ofHours(1)))
    }

    @Test
    fun `does nothing when the session is not locked`() {
        val e = engine()
        val s = e.created(LockPlan(now + Duration.ofHours(2), now + Duration.ofHours(10), MSK), now) // waiting, not locked
        assertEquals(s.status, e.endByTrustedExit(s, ExitKind.END_SESSION, now).status)
    }

    @Test
    fun `matches submitCode's own accepted transition`() {
        val e = engine()
        val s = e.created(LockPlan(now, now + Duration.ofHours(8), MSK), now)
        val asked = e.requestCode(s, ExitKind.END_SESSION, now) as LockEngine.CodeRequestResult.Issued
        val viaCode = (e.submitCode(asked.session, asked.plainCode, now) as LockEngine.CodeResult.Accepted).session
        val viaEnd = e.endByTrustedExit(s, ExitKind.END_SESSION, now)
        assertEquals(viaCode.status, viaEnd.status)
        assertEquals(viaCode.finishReason, viaEnd.finishReason)
    }
}
