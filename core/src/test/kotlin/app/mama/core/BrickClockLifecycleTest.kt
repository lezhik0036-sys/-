package app.mama.core

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrickClockLifecycleTest {
    @Test
    fun `wall clock changes cannot finish a running lock within the same boot`() {
        val start = Instant.parse("2026-11-13T20:00:00Z")
        val end = start.plusSeconds(3600)
        val engine = LockEngine()
        val plan = LockPlan(start, end, ZoneId.of("Europe/Moscow"))
        val session = (engine.create("clock", plan, MOM, start) as LockEngine.CreateResult.Created).session
        val anchor = ClockGuard.reanchor(start, 100, "boot-1")
        val trusted = ClockGuard.trustedNow(anchor, start, end.plusSeconds(86400), 60_100, "boot-1")
        assertEquals(start.plusSeconds(60), trusted)
        assertTrue(engine.stateOf(session, trusted).isLocked)
        assertEquals(FinishReason.COMPLETED, engine.advance(session, end).finishReason)
    }

    @Test
    fun `reboot with a rolled back clock never moves behind the last trusted instant`() {
        val last = Instant.parse("2026-11-13T20:25:00Z")
        val anchor = ClockGuard.reanchor(last.minusSeconds(60), 120_000, "boot-1")
        assertEquals(last, ClockGuard.trustedNow(anchor, last, last.minusSeconds(7200), 1_000, "boot-2"))
    }
}
