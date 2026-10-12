package app.mama.core

import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** AUTO_NOW (draft start untouched) vs MANUAL (start set by hand), resolved at the final confirmation. */
class AutoNowLaunchTest {
    private val lock = engine()
    private val flow = SeriesFlow(lock)
    private val rub = 100L
    private val eight = Duration.ofHours(8)

    private fun done(r: SeriesFlow.Result) = assertIs<SeriesFlow.Result.Done>(r).snapshot

    @Test
    fun `1 - draft opened at 16_48, time untouched, confirmed at 16_53 - 16_53 to 00_53 right away, not tomorrow`() {
        val confirmedAt = t("2026-10-12T13:53:20Z") // 16:53:20 Moscow; the preview said 16:48
        val s = done(flow.schedule(Snapshot(), "s1", "n1", SeriesKind.SEVEN, LaunchTime.AutoNow(eight, MSK), MOM, SessionMode.SLEEP, null, confirmedAt))

        assertEquals(DailyWindow(LocalTime.of(16, 53), LocalTime.of(0, 53), MSK), s.series!!.window)
        assertEquals(confirmedAt, s.session!!.plan.start, "starts at the confirmation")
        assertEquals(confirmedAt + eight, s.session!!.plan.end, "the whole 8 hours")
        assertEquals(SessionStatus.ACTIVE, s.session!!.status)
        assertEquals(SeriesStatus.ACTIVATING, s.series!!.status, "binding once strict protection is confirmed")

        // From then on an ordinary fixed window: the next night is tomorrow 16:53 -> 00:53.
        val active = flow.confirm(s, confirmedAt.plusSeconds(1))!!
        assertEquals(SeriesStatus.ACTIVE, active.series!!.status)
        val firstEnd = active.session!!.plan.end
        val next = flow.fold(active.copy(session = lock.advance(active.session!!, firstEnd)), "n2", firstEnd)
        assertEquals(1, next.series!!.completedPeriods)
        assertEquals(t("2026-10-13T13:53:00Z"), next.session!!.plan.start)
        assertEquals(t("2026-10-13T21:53:00Z"), next.session!!.plan.end)
        assertEquals(s.series!!.window, next.series!!.window)
    }

    @Test
    fun `AUTO_NOW keeps the length the user chose for the end`() {
        val r = SeriesDraft.resolve(LaunchTime.AutoNow(Duration.ofMinutes(6 * 60 + 30), MSK), t("2026-10-12T13:53:59Z"))
        assertTrue(r.startNow)
        assertEquals(DailyWindow(LocalTime.of(16, 53), LocalTime.of(23, 23), MSK), r.window)
    }

    @Test
    fun `2 - start set by hand to 18_00 - 02_00, confirmed at 16_53 - a fixed window, no shift`() {
        val confirmedAt = t("2026-10-12T13:53:00Z")
        val manual = DailyWindow(LocalTime.of(18, 0), LocalTime.of(2, 0), MSK)
        val s = done(flow.schedule(Snapshot(), "s1", "n1", SeriesKind.SEVEN, LaunchTime.Fixed(manual), MOM, SessionMode.SLEEP, null, confirmedAt))
        assertEquals(manual, s.series!!.window)
        assertEquals(SeriesStatus.SCHEDULED, s.series!!.status)
        assertEquals(t("2026-10-12T15:00:00Z"), s.session!!.plan.start, "today 18:00")
        assertEquals(t("2026-10-12T23:00:00Z"), s.session!!.plan.end)
    }

    @Test
    fun `a manual start that has already passed today - the next 16_48, never moved to now`() {
        val manual = DailyWindow(LocalTime.of(16, 48), LocalTime.of(0, 48), MSK)
        val s = done(flow.schedule(Snapshot(), "s1", "n1", SeriesKind.SEVEN, LaunchTime.Fixed(manual), MOM, SessionMode.SLEEP, null, t("2026-10-12T13:53:00Z")))
        assertEquals(manual, s.series!!.window)
        assertEquals(SeriesStatus.SCHEDULED, s.series!!.status)
        assertEquals(t("2026-10-13T13:48:00Z"), s.session!!.plan.start, "tomorrow 16:48 (full-window rule)")
    }

    @Test
    fun `3 - Restart opened at 16_48, AUTO_NOW, confirmed at 16_52 - starts 16_52, not the failed 15_46 and not tomorrow`() {
        val failedAt = t("2026-10-10T21:00:00Z")
        val failed = Snapshot(
            series = Series(
                "old", SeriesKind.SEVEN, DailyWindow(LocalTime.of(15, 46), LocalTime.of(7, 0), MSK), MOM, SessionMode.SLEEP,
                startedAt = failedAt - Duration.ofDays(3), completedPeriods = 2, status = SeriesStatus.BROKEN, endedAt = failedAt,
                actualStartedAt = failedAt - Duration.ofDays(3),
            ),
            entitlements = Entitlements(standardFailedAt = failedAt),
            wallet = Wallet(availableKop = 1_000 * rub),
        )
        val confirmedAt = t("2026-10-12T13:52:05Z") // 16:52:05 Moscow
        val r = done(flow.restart(failed, GrantSource.INTERNAL_BALANCE, LaunchTime.AutoNow(eight, MSK), "new", "n1", null, confirmedAt))

        assertEquals(SeriesKind.SEVEN, r.series!!.kind, "same length")
        assertEquals("old", r.series!!.restartOf)
        assertEquals(DailyWindow(LocalTime.of(16, 52), LocalTime.of(0, 52), MSK), r.series!!.window)
        assertEquals(confirmedAt, r.session!!.plan.start)
        assertEquals(confirmedAt + eight, r.session!!.plan.end)
        assertEquals(SeriesStatus.ACTIVATING, r.series!!.status)
        assertEquals(299 * rub, r.wallet.restartReservedKop, "held until ACTIVE, as before")

        val active = flow.confirm(r, confirmedAt.plusSeconds(1))!!
        assertEquals(SeriesStatus.ACTIVE, active.series!!.status)
        assertEquals(0, active.wallet.restartReservedKop, "used at ACTIVE")
        assertEquals(1_000 * rub - 299 * rub, active.wallet.availableKop)
    }

    // ---------------- AUTO_NOW + NEEDS_ATTENTION: frozen only at ACTIVE ----------------

    @Test
    fun `AUTO_NOW 18_00, protection did not come up, relaunched at 18_20 - 18_20 to 02_20, frozen at ACTIVE`() {
        val at1800 = t("2026-10-12T15:00:00Z")
        val launched = done(flow.schedule(Snapshot(), "s1", "n1", SeriesKind.SEVEN, LaunchTime.AutoNow(eight, MSK), MOM, SessionMode.SLEEP, null, at1800))
        assertTrue(launched.series!!.autoNow)
        val missed = flow.protectionFailed(launched, at1800.plusSeconds(30))!!
        assertEquals(SeriesStatus.NEEDS_ATTENTION, missed.series!!.status)
        assertEquals(DailyWindow(LocalTime.of(18, 0), LocalTime.of(2, 0), MSK), missed.series!!.window, "not frozen yet")
        assertTrue(missed.series!!.autoNow)

        // Permission fixed, «Подтвердить запуск» at 18:20.
        val at1820 = t("2026-10-12T15:20:00Z")
        val relaunched = done(flow.reschedule(missed, "n2", at1820))
        assertEquals(DailyWindow(LocalTime.of(18, 20), LocalTime.of(2, 20), MSK), relaunched.series!!.window, "from the actual launch, not 18:00")
        assertEquals(at1820, relaunched.session!!.plan.start, "right away")
        assertEquals(at1820 + eight, relaunched.session!!.plan.end)
        assertEquals(SeriesStatus.ACTIVATING, relaunched.series!!.status)

        // ACTIVATING -> ACTIVE: the freeze point.
        val activeAt = at1820.plusSeconds(3)
        val active = flow.confirm(relaunched, activeAt)!!
        assertEquals(SeriesStatus.ACTIVE, active.series!!.status)
        assertEquals(activeAt, active.series!!.actualStartedAt)
        assertFalse(active.series!!.autoNow, "frozen")
        assertEquals(DailyWindow(LocalTime.of(18, 20), LocalTime.of(2, 20), MSK), active.series!!.window)
        // The next night keeps the frozen window, whenever it is planned.
        val firstEnd = active.session!!.plan.end
        val next = flow.fold(active.copy(session = lock.advance(active.session!!, firstEnd)), "n3", firstEnd)
        assertEquals(DailyWindow(LocalTime.of(18, 20), LocalTime.of(2, 20), MSK), next.series!!.window)
        assertEquals(t("2026-10-13T15:20:00Z"), next.session!!.plan.start)
    }

    @Test
    fun `MANUAL 23_00 - 07_00 needing attention keeps its time - the next full window after the fix`() {
        val manual = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), MSK)
        val scheduled = done(flow.schedule(Snapshot(), "s1", "n1", SeriesKind.SEVEN, LaunchTime.Fixed(manual), MOM, SessionMode.SLEEP, null, t("2026-10-12T17:00:00Z")))
        assertFalse(scheduled.series!!.autoNow)
        val at23 = t("2026-10-12T20:00:00Z")
        val activating = flow.fold(scheduled.copy(session = lock.advance(scheduled.session!!, at23)), "n2", at23)
        assertEquals(SeriesStatus.ACTIVATING, activating.series!!.status)
        val missed = flow.protectionFailed(activating, at23.plusSeconds(30))!!
        assertEquals(SeriesStatus.NEEDS_ATTENTION, missed.series!!.status)

        val relaunched = done(flow.reschedule(missed, "n3", t("2026-10-12T20:20:00Z"))) // fixed at 23:20
        assertEquals(manual, relaunched.series!!.window, "not recalculated")
        assertEquals(SeriesStatus.SCHEDULED, relaunched.series!!.status)
        assertEquals(t("2026-10-13T20:00:00Z"), relaunched.session!!.plan.start, "tomorrow 23:00 (full window)")
    }

    @Test
    fun `an unfrozen AUTO_NOW launch survives a restart of the app`() {
        val s = done(flow.schedule(Snapshot(), "s1", "n1", SeriesKind.SEVEN, LaunchTime.AutoNow(eight, MSK), MOM, SessionMode.SLEEP, null, t("2026-10-12T15:00:00Z")))
        assertTrue(StateCodec.decode(StateCodec.encode(s)).series!!.autoNow)
        val manual = done(flow.schedule(Snapshot(), "s1", "n1", SeriesKind.SEVEN, LaunchTime.Fixed(DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), MSK)), MOM, SessionMode.SLEEP, null, t("2026-10-12T15:00:00Z")))
        assertFalse(StateCodec.decode(StateCodec.encode(manual)).series!!.autoNow)
    }
}
