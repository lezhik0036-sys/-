package app.mama.core

import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [CorePolicy.maxLockDuration]: the TEST/DEBUG safety cap. However long a lock
 * is planned for, a test build may never actually lock the device for more
 * than the cap (15 minutes), so a tester is never stuck. Production uses no cap.
 */
class LockCapTest {
    private val fifteen = Duration.ofMinutes(15)
    private val capped = LockEngine(CorePolicy(maxLockDuration = fifteen), FakeCodes())
    private val uncapped = LockEngine(CorePolicy(), FakeCodes())
    private val now = t("2026-10-22T10:00:00Z")

    private fun created(engine: LockEngine, plan: LockPlan) =
        assertIs<LockEngine.CreateResult.Created>(engine.create("s1", plan, MOM, now)).session

    @Test
    fun `an 8-hour lock is capped to 15 minutes in a test build`() {
        val plan = LockPlan(now, now + Duration.ofHours(8), MSK)
        val session = created(capped, plan)
        assertEquals(now, session.plan.start)
        assertEquals(now + fifteen, session.plan.end, "the lock ends 15 minutes in, not 8 hours")
    }

    @Test
    fun `without a cap the full 8 hours stand`() {
        val plan = LockPlan(now, now + Duration.ofHours(8), MSK)
        assertEquals(now + Duration.ofHours(8), created(uncapped, plan).plan.end)
    }

    @Test
    fun `a lock already shorter than the cap is untouched`() {
        val plan = LockPlan(now, now + Duration.ofMinutes(15), MSK)
        assertEquals(now + fifteen, created(capped, plan).plan.end)
    }

    @Test
    fun `a future-start lock is capped from its own start`() {
        // Scheduled for tonight (window 23:00): the cap is measured from the lock's start, not from now.
        val start = now + Duration.ofHours(13)
        val plan = LockPlan(start, start + Duration.ofHours(8), MSK)
        val session = created(capped, plan)
        assertEquals(start, session.plan.start)
        assertEquals(start + fifteen, session.plan.end)
    }

    @Test
    fun `a whole series night is capped, but the series still has all its periods`() {
        // Through SeriesEngine (AUTO_NOW): the first lock is capped, the series is still THREE periods long.
        val flow = SeriesFlow(capped)
        val window = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), MSK)
        val done = assertIs<SeriesFlow.Result.Done>(
            flow.schedule(Snapshot(), "s", "n", SeriesKind.THREE, window, MOM, SessionMode.SLEEP, null, now),
        ).snapshot
        val session = done.session!!
        assertEquals(SeriesKind.THREE, done.series!!.kind)
        assertEquals(fifteen, Duration.between(session.plan.start, session.plan.end), "the night's lock is capped to 15 min")
    }
}
