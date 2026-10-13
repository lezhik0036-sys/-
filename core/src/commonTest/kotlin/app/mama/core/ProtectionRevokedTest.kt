package app.mama.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.LocalTime

/**
 * Protection deliberately switched off (iPhone: Screen Time access confirmed
 * withdrawn) during an ACTIVE series or FLEX period: a failure under the
 * ordinary rules, exactly once. Anything not confirmed fails nothing.
 */
class ProtectionRevokedTest {
    private val lock = engine()
    private val flow = SeriesFlow(lock)
    private val flex = FlexEngine(lock)
    private val window = DailyWindow(LocalTime(23, 0), LocalTime(7, 0), MSK)
    private val at13 = t("2026-10-12T10:00:00Z")
    private val at23 = t("2026-10-12T20:00:00Z")
    private val revokedAt = t("2026-10-12T20:30:00Z") // 23:30 Moscow, during the first night
    private val rub = 100L

    private fun done(r: SeriesFlow.Result) = assertIs<SeriesFlow.Result.Done>(r).snapshot

    /** A 7-day series with «Цена слова» 1 000, ACTIVE since 23:00. */
    private fun active(): Snapshot {
        val scheduled = done(flow.schedule(Snapshot(wallet = Wallet(availableKop = 10_000 * rub)), "s1", "n1", SeriesKind.SEVEN, window, MOM, SessionMode.SLEEP, 1_000 * rub, at13))
        val activating = flow.fold(scheduled.copy(session = lock.advance(scheduled.session!!, at23)), "n2", at23)
        return flow.confirm(activating, at23.plusSeconds(2))!!.also { assertEquals(SeriesStatus.ACTIVE, it.series!!.status) }
    }

    private fun forfeits(w: Wallet) = w.entries.count { it.type == LedgerType.COMMITMENT_FORFEITED_FAILURE }

    // ---------------- STANDARD ----------------

    @Test
    fun `ACTIVE series - access withdrawn - FAILED with the ordinary failure rules`() {
        val failed = flow.protectionRevoked(active(), "n3", revokedAt)!!
        val series = failed.series!!
        assertEquals(SeriesStatus.BROKEN, series.status)
        assertEquals(revokedAt, series.endedAt)
        assertEquals(FinishReason.PROTECTION_REVOKED, failed.session!!.finishReason)
        assertEquals(SessionStatus.FINISHED, failed.session!!.status)
        // 30-day wait from the failure, «Цена слова» forfeited with the usual return.
        assertEquals(revokedAt, failed.entitlements.standardFailedAt)
        val c = failed.wallet.commitmentFor("s1")!!
        assertEquals(CommitmentState.FORFEITED, c.state)
        assertEquals(revokedAt + 24.hours, c.returnDueAt)
        assertEquals(1, forfeits(failed.wallet))
    }

    @Test
    fun `one withdrawal fails and charges exactly once`() {
        val failed = flow.protectionRevoked(active(), "n3", revokedAt)!!
        assertNull(flow.protectionRevoked(failed, "n4", revokedAt + 1.minutes), "already failed: nothing more")
        val later = flow.fold(failed, "n5", revokedAt + 2.hours)
        assertEquals(SeriesStatus.BROKEN, later.series!!.status)
        assertEquals(revokedAt, later.series!!.endedAt)
        assertEquals(revokedAt, later.entitlements.standardFailedAt)
        assertEquals(1, forfeits(later.wallet))
    }

    @Test
    fun `withdrawn between two nights - the series fails and progress stops`() {
        val night1End = t("2026-10-13T04:00:00Z")
        val afterNight1 = flow.fold(active().let { it.copy(session = lock.advance(it.session!!, night1End)) }, "n3", night1End)
        assertEquals(1, afterNight1.series!!.completedPeriods)
        assertEquals(SessionStatus.SCHEDULED, afterNight1.session!!.status)
        val noon = t("2026-10-13T09:00:00Z")
        val failed = flow.protectionRevoked(afterNight1, "n4", noon)!!
        assertEquals(SeriesStatus.BROKEN, failed.series!!.status)
        assertEquals(1, failed.series!!.completedPeriods)
        assertEquals(FinishReason.PROTECTION_REVOKED, failed.session!!.finishReason)
        assertEquals(noon, failed.entitlements.standardFailedAt)
    }

    @Test
    fun `before ACTIVE a withdrawal fails nothing here`() {
        val scheduled = done(flow.schedule(Snapshot(), "s1", "n1", SeriesKind.THREE, window, MOM, SessionMode.SLEEP, null, at13))
        assertNull(flow.protectionRevoked(scheduled, "n2", at13 + 1.minutes))
        val activating = flow.fold(scheduled.copy(session = lock.advance(scheduled.session!!, at23)), "n2", at23)
        assertEquals(SeriesStatus.ACTIVATING, activating.series!!.status)
        assertNull(flow.protectionRevoked(activating, "n3", at23 + 10.seconds))
    }

    @Test
    fun `the lock engine ends a running or planned lock once`() {
        val running = lock.created(LockPlan(at23, at23 + 8.hours, MSK), at23 + 1.minutes)
        val ended = lock.revoke(running, revokedAt)
        assertEquals(FinishReason.PROTECTION_REVOKED, ended.finishReason)
        assertEquals(revokedAt, ended.finishedAt)
        assertEquals(ended, lock.revoke(ended, revokedAt + 1.hours), "finished stays as it was")
    }

    // ---------------- FLEX ----------------

    @Test
    fun `FLEX period running - access withdrawn - that day is burnt - nothing else`() {
        val pkg = flex.activate("p", GrantSource.TEST_NO_PAYMENT, at13)
        val planned = assertIs<FlexEngine.PlanResult.Planned>(flex.planPeriod(pkg, null, at13 + 4.hours, MSK, MOM, SessionMode.WORK, "f1", at13))
        val started = flex.afterSession(planned.pkg, planned.session, at13).pkg
        assertEquals(1, started.used)

        val r = assertNotNull(flex.protectionRevoked(started, planned.session, at13 + 1.hours))
        assertEquals(FinishReason.PROTECTION_REVOKED, r.session.finishReason)
        assertEquals(1, r.pkg.used, "consumed once, not twice")
        assertEquals(0, r.pkg.successful)
        assertEquals(at13 + 1.hours, r.pkg.lastBurntAt)
        assertNull(r.pkg.currentSessionId)
        assertNull(flex.protectionRevoked(r.pkg, r.session, at13 + 2.hours), "once")
    }

    @Test
    fun `FLEX period planned for later - a withdrawal before it starts burns nothing`() {
        val pkg = flex.activate("p", GrantSource.TEST_NO_PAYMENT, at13)
        val planned = assertIs<FlexEngine.PlanResult.Planned>(flex.planPeriod(pkg, at13 + 2.hours, at13 + 6.hours, MSK, MOM, SessionMode.WORK, "f1", at13))
        assertNull(flex.protectionRevoked(planned.pkg, planned.session, at13 + 1.hours))
    }

    // ---------------- confirmed denial vs glitch ----------------

    @Test
    fun `one denied reading is not enough - denied again later is confirmed`() {
        val first = AccessWatch.observe(AccessReading.DENIED, null, at13)
        assertEquals(AccessVerdict.UNKNOWN, first.verdict)
        assertEquals(at13, first.deniedSince)
        assertEquals(AccessVerdict.UNKNOWN, AccessWatch.observe(AccessReading.DENIED, first.deniedSince, at13 + 1.seconds).verdict)
        val confirmed = AccessWatch.observe(AccessReading.DENIED, first.deniedSince, at13 + AccessWatch.CONFIRM_AFTER)
        assertEquals(AccessVerdict.CONFIRMED_DENIED, confirmed.verdict)
    }

    @Test
    fun `approved in between clears a pending denial`() {
        val first = AccessWatch.observe(AccessReading.DENIED, null, at13)
        val back = AccessWatch.observe(AccessReading.APPROVED, first.deniedSince, at13 + 1.seconds)
        assertEquals(AccessVerdict.APPROVED, back.verdict)
        assertNull(back.deniedSince)
        assertEquals(AccessVerdict.UNKNOWN, AccessWatch.observe(AccessReading.DENIED, back.deniedSince, at13 + 10.seconds).verdict)
    }

    @Test
    fun `not determined or unreadable - launch or reboot or a killed process - is never a denial`() {
        for (reading in listOf(AccessReading.NOT_DETERMINED, AccessReading.UNREADABLE)) {
            val step = AccessWatch.observe(reading, null, at13)
            assertEquals(AccessVerdict.UNKNOWN, step.verdict)
            assertNull(step.deniedSince)
            // Neither starts nor clears a pending denial.
            val pending = AccessWatch.observe(reading, at13, at13 + 1.hours)
            assertEquals(AccessVerdict.UNKNOWN, pending.verdict)
            assertEquals(at13, pending.deniedSince)
        }
    }

    // ---------------- the iPhone bridge ----------------

    private val bridge = MamaBridge
    private fun ms(iso: String) = t(iso).toEpochMilliseconds()

    /** AUTO_NOW 7 days launched at 16:53 Moscow, ACTIVE with the shield on. */
    private fun bridgeActive(): Map<String, String> {
        val now = ms("2026-10-12T13:53:20Z")
        val scheduled = bridge.schedule(emptyMap(), 7, true, 16 * 60 + 53, 53, "Europe/Moscow", "Мама", "+79001234567", now, "s1", "n1").state!!
        return bridge.refresh(scheduled, now + 2_000, "n2", listOf(now + 1_000), "APPROVED").state!!
            .also { assertEquals("ACTIVE", bridge.view(it)!!.status) }
    }

    @Test
    fun `bridge - confirmed denial during ACTIVE fails once and stays failed`() {
        val at = ms("2026-10-12T14:30:00Z")
        val failed = bridge.refresh(bridgeActive(), at, "n3", emptyList(), "CONFIRMED_DENIED").state!!
        val v = bridge.view(failed)!!
        assertEquals("BROKEN", v.status)
        assertEquals("PROTECTION_REVOKED", v.endReason)
        assertEquals(30, bridge.daysUntilFreeStart(failed, at))
        // Opened again, still denied: nothing changes.
        assertEquals(failed, bridge.refresh(failed, at, "n4", emptyList(), "CONFIRMED_DENIED").state)
        assertEquals("BROKEN", bridge.view(bridge.refresh(failed, at + 60_000, "n5", emptyList(), "APPROVED").state!!)!!.status)
    }

    @Test
    fun `bridge - an unconfirmed reading fails nothing`() {
        val active = bridgeActive()
        val still = bridge.refresh(active, ms("2026-10-12T14:30:00Z"), "n3", emptyList(), "UNKNOWN").state!!
        assertEquals("ACTIVE", bridge.view(still)!!.status)
        assertEquals(0, bridge.daysUntilFreeStart(still, ms("2026-10-12T14:30:00Z")))
    }

    @Test
    fun `bridge - unknown access while activating waits for the usual timeout`() {
        val now = ms("2026-10-12T13:53:20Z")
        val scheduled = bridge.schedule(emptyMap(), 3, true, 16 * 60 + 53, 53, "Europe/Moscow", "Мама", "+79001234567", now, "s1", "n1").state!!
        assertEquals("ACTIVATING", bridge.view(bridge.refresh(scheduled, now + 10_000, "n2", emptyList(), "UNKNOWN").state!!)!!.status)
        val late = now + CorePolicy().activationTimeout.inWholeMilliseconds + 1_000
        assertEquals("NEEDS_ATTENTION", bridge.view(bridge.refresh(scheduled, late, "n2", emptyList(), "UNKNOWN").state!!)!!.status)
    }

    @Test
    fun `bridge - readings are folded into a verdict`() {
        val first = bridge.observeAccess("DENIED", null, 1_000)
        assertEquals("UNKNOWN", first.verdict)
        assertEquals(1_000L, first.deniedSinceMs)
        assertEquals("CONFIRMED_DENIED", bridge.observeAccess("DENIED", first.deniedSinceMs, 1_000 + bridge.accessConfirmAfterMs).verdict)
        assertEquals("UNKNOWN", bridge.observeAccess("SOMETHING_NEW", null, 1_000).verdict)
        assertEquals("APPROVED", bridge.observeAccess("APPROVED", 1_000, 2_000).verdict)
    }
}
