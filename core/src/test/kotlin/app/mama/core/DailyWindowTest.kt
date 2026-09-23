package app.mama.core

import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DailyWindowTest {
    private val sleep = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), MSK)

    @Test
    fun `evening before start gives tonight's window`() {
        // 18:00 MSK = 15:00Z
        val plan = sleep.nextPlan(t("2026-09-23T15:00:00Z"))
        assertEquals(t("2026-09-23T20:00:00Z"), plan.start)
        assertEquals(t("2026-09-24T04:00:00Z"), plan.end)
    }

    @Test
    fun `inside overnight window after midnight gives the running window`() {
        // 02:00 MSK on the 24th: yesterday's 23:00 window is running.
        val plan = sleep.nextPlan(t("2026-09-23T23:00:00Z"))
        assertEquals(t("2026-09-23T20:00:00Z"), plan.start)
        assertEquals(t("2026-09-24T04:00:00Z"), plan.end)
    }

    @Test
    fun `window almost over is skipped when min remaining is required`() {
        // 06:55 MSK: only 5 minutes left.
        val plan = sleep.nextPlan(t("2026-09-24T03:55:00Z"), minRemaining = Duration.ofMinutes(15))
        assertEquals(t("2026-09-24T20:00:00Z"), plan.start)
    }

    @Test
    fun `daytime window does not cross midnight`() {
        val work = DailyWindow(LocalTime.of(9, 0), LocalTime.of(13, 0), MSK)
        val plan = work.nextPlan(t("2026-09-23T12:00:00Z")) // 15:00 MSK, today's is over
        assertEquals(t("2026-09-24T06:00:00Z"), plan.start)
        assertEquals(t("2026-09-24T10:00:00Z"), plan.end)
    }

    @Test
    fun `zone matters - same wall times differ in absolute time`() {
        val berlin = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), BERLIN)
        val now = t("2026-09-23T12:00:00Z")
        // Berlin is UTC+2 in September, Moscow UTC+3.
        assertEquals(Duration.ofHours(1), Duration.between(sleep.nextPlan(now).start, berlin.nextPlan(now).start))
    }

    @Test
    fun `DST fall back night is one hour longer`() {
        // Berlin leaves summer time on 2026-10-25 at 03:00 local.
        val berlin = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), BERLIN)
        val plan = berlin.nextPlan(t("2026-10-24T12:00:00Z"))
        assertEquals(Duration.ofHours(9), Duration.between(plan.start, plan.end))
    }

    @Test
    fun `DST spring forward night is one hour shorter`() {
        val berlin = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), BERLIN)
        val plan = berlin.nextPlan(t("2026-03-28T12:00:00Z"))
        assertEquals(Duration.ofHours(7), Duration.between(plan.start, plan.end))
    }

    @Test
    fun `start in DST gap is shifted forward`() {
        val berlin = DailyWindow(LocalTime.of(2, 30), LocalTime.of(8, 0), BERLIN)
        val plan = berlin.nextPlan(t("2026-03-28T12:00:00Z"))
        // 02:30 does not exist on 2026-03-29; becomes 03:30 CEST = 01:30Z.
        assertEquals(t("2026-03-29T01:30:00Z"), plan.start)
    }

    @Test
    fun `equal start and end is rejected`() {
        assertFailsWith<IllegalArgumentException> { DailyWindow(LocalTime.NOON, LocalTime.NOON, MSK) }
    }
}
