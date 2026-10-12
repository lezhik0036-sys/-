package app.mama.core

import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Начать серию" only schedules a series. It becomes binding (ACTIVE) when
 * its first lock has started and strict protection is confirmed; before that
 * it can be changed or cancelled with no consequences.
 */
class SeriesStartPointTest {
    private val engine = engine()
    private val series = SeriesEngine(engine)
    private val window = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), MSK)
    private val at13 = t("2026-10-12T10:00:00Z") // 13:00 Moscow
    private val at15 = t("2026-10-12T12:00:00Z") // 15:00 Moscow
    private val at23 = t("2026-10-12T20:00:00Z") // 23:00 Moscow, the first lock
    private val rub = 100L
    private val sister = TrustedContact("Сестра", "+79007654321")

    private fun ok(r: WalletEngine.Result): Wallet = assertIs<WalletEngine.Result.Ok>(r).wallet

    private fun funded(amountRub: Long) = ok(WalletEngine.topUpTest(Wallet(), amountRub * rub, "topup-1", at13))

    /** Created at 13:00 with the lock at 23:00. */
    private fun scheduled() = series.start("s1", "n1", SeriesKind.SEVEN, window, MOM, SessionMode.SLEEP, at13)

    /** 23:00: the lock starts; the device then confirms strict protection. */
    private fun begin(u: SeriesEngine.Update): SeriesEngine.Update {
        val begun = engine.advance(u.session!!, at23)
        return series.afterSession(u.series, begun, "n2", at23)
    }

    private fun active(): SeriesEngine.Update {
        val activating = begin(scheduled())
        return SeriesEngine.Update(series.confirmActive(activating.series, activating.session, at23)!!, activating.session)
    }

    @Test
    fun `1 - created at 13, lock at 23, cancelled at 15 - cancelled before start with no consequences`() {
        val u = scheduled()
        assertEquals(SeriesStatus.SCHEDULED, u.series.status, "pressing «Начать серию» does not start the series")
        assertEquals(at23, u.session!!.plan.start)
        val wallet = ok(WalletEngine.reserve(funded(10_000), "s1", 5_000 * rub, at13))

        val cancelled = series.cancelScheduled(u.series, u.session, at15)!!
        assertEquals(SeriesStatus.CANCELLED_BEFORE_START, cancelled.series.status)
        assertNotEquals(SeriesStatus.BROKEN, cancelled.series.status)
        assertEquals(FinishReason.CANCELLED_BEFORE_START, cancelled.session!!.finishReason)
        assertEquals(LockState.Free, engine.stateOf(cancelled.session, at15))
        // No 30-day wait: a new free series can start right away.
        val ent = StandardRules.afterSeries(Entitlements(), cancelled.series, at15)
        assertNull(ent.standardFailedAt)
        assertTrue(StandardRules.canStartFree(ent, at15))
        // No forfeit, nothing scheduled to come back: everything is simply available again.
        val settled = WalletEngine.settle(wallet, cancelled.series, at15)
        assertEquals(10_000 * rub, settled.availableKop)
        assertNull(settled.nextReturnDue)
        assertTrue(settled.entries.none { it.type == LedgerType.COMMITMENT_FORFEITED_FAILURE })
    }

    @Test
    fun `2 - changing the trusted contact before the start saves the new contact`() {
        val u = scheduled()
        val edited = assertIs<SeriesEngine.EditResult.Edited>(
            series.edit(u.series, u.session, u.series.kind, u.series.window, sister, u.series.mode, "n2", at15),
        ).update
        assertEquals(sister, edited.series.contact)
        assertEquals(sister, edited.session!!.contact, "the planned lock sends codes to the new contact")
        assertEquals(SeriesStatus.SCHEDULED, edited.series.status)
        // The same edit can change the series and the time too; the countdown follows the new start.
        val later = DailyWindow(LocalTime.of(23, 30), LocalTime.of(7, 30), MSK)
        val again = assertIs<SeriesEngine.EditResult.Edited>(
            series.edit(edited.series, edited.session, SeriesKind.THREE, later, sister, SessionMode.SLEEP, "n3", at15),
        ).update
        assertEquals(SeriesKind.THREE, again.series.kind)
        assertEquals(t("2026-10-12T20:30:00Z"), again.session!!.plan.start)
        assertEquals(LockState.Waiting(t("2026-10-12T20:30:00Z"), t("2026-10-13T04:30:00Z")), engine.stateOf(again.session, at15))
    }

    @Test
    fun `3 - changing the commitment before the start recalculates the reserve with no loss`() {
        val w = ok(WalletEngine.reserve(funded(10_000), "s1", 5_000 * rub, at13))
        assertEquals(5_000 * rub, w.availableKop)
        val changed = ok(WalletEngine.changeScheduled(w, "s1", 1_000 * rub, at15))
        assertEquals(9_000 * rub, changed.availableKop)
        assertEquals(1_000 * rub, changed.reservedKop)
        assertEquals(1_000 * rub, changed.scheduledKop)
        assertEquals(1_000 * rub, changed.commitmentFor("s1")!!.amountKop)
        assertEquals(CommitmentState.SCHEDULED, changed.commitmentFor("s1")!!.state)
        assertTrue(changed.entries.none { it.type == LedgerType.COMMITMENT_FORFEITED_FAILURE })
        assertEquals(changed.entries.size, changed.entries.map { it.id }.toSet().size, "ledger ids stay unique")
        // Raising it again needs the balance: 9 000 available + 1 000 already set aside.
        assertEquals(10_000 * rub, assertIs<WalletEngine.Result.Insufficient>(WalletEngine.changeScheduled(changed, "s1", 10_001 * rub, at15)).availableKop)
        // Removing it gives everything back.
        val none = ok(WalletEngine.changeScheduled(changed, "s1", null, at15))
        assertEquals(10_000 * rub, none.availableKop)
        assertEquals(0, none.reservedKop)
        assertNull(none.commitmentFor("s1"))
        // And a series without one can get one before it starts.
        val added = ok(WalletEngine.changeScheduled(none, "s1", 2_000 * rub, at15))
        assertEquals(8_000 * rub, added.availableKop)
        assertEquals(2_000 * rub, added.reservedKop)
    }

    @Test
    fun `4 - cancelling with a commitment before the start releases 100 percent of the scheduled reserve`() {
        val u = scheduled()
        val w = ok(WalletEngine.changeScheduled(ok(WalletEngine.reserve(funded(10_000), "s1", 5_000 * rub, at13)), "s1", 1_000 * rub, at13))
        assertEquals(9_000 * rub, w.availableKop)
        val cancelled = series.cancelScheduled(u.series, u.session, at15)!!
        val settled = WalletEngine.settle(w, cancelled.series, at15)
        assertEquals(10_000 * rub, settled.availableKop)
        assertEquals(0, settled.reservedKop)
        assertEquals(0, settled.scheduledKop)
        assertNull(settled.nextReturnDue)
        val released = settled.entries.last()
        assertEquals(LedgerType.COMMITMENT_RELEASED_BEFORE_START, released.type)
        assertEquals(1_000 * rub, released.amountKop)
    }

    @Test
    fun `5 - start time reached and protection confirmed - scheduled, activating, active`() {
        val u = scheduled()
        assertEquals(SeriesStatus.SCHEDULED, u.series.status)
        // 22:59: still scheduled.
        assertEquals(SeriesStatus.SCHEDULED, series.afterSession(u.series, engine.advance(u.session!!, at23.minusSeconds(60)), "x", at23.minusSeconds(60)).series.status)
        val activating = begin(u)
        assertEquals(SeriesStatus.ACTIVATING, activating.series.status)
        assertNull(activating.series.actualStartedAt)
        val confirmedAt = at23.plusSeconds(1)
        val active = series.confirmActive(activating.series, activating.session, confirmedAt)!!
        assertEquals(SeriesStatus.ACTIVE, active.status)
        assertEquals(confirmedAt, active.actualStartedAt)
        assertEquals(0, active.completedPeriods, "day 1 has begun")
        // "Цена слова" is fixed at the same moment.
        val w = ok(WalletEngine.reserve(funded(10_000), "s1", 5_000 * rub, at13))
        val fixed = WalletEngine.settle(w, active, confirmedAt)
        assertEquals(CommitmentState.RESERVED, fixed.commitmentFor("s1")!!.state)
        assertEquals(confirmedAt, fixed.commitmentFor("s1")!!.fixedAt)
        assertEquals(5_000 * rub, fixed.reservedKop)
        assertIs<WalletEngine.Result.Invalid>(WalletEngine.changeScheduled(fixed, "s1", 1_000 * rub, confirmedAt))
        // Conditions survive a reboot.
        val snap = Snapshot(activating.session, ClockAnchor(confirmedAt, 1, "b"), confirmedAt, active, wallet = fixed)
        assertEquals(snap, StateCodec.decode(StateCodec.encode(snap)))
    }

    @Test
    fun `6 - after ACTIVE the contact cannot be changed and the series cannot be cancelled`() {
        val u = active()
        assertIs<SeriesEngine.EditResult.NotAllowed>(series.edit(u.series, u.session, u.series.kind, u.series.window, sister, u.series.mode, "n3", at23.plusSeconds(5)))
        assertNull(series.cancelScheduled(u.series, u.session, at23.plusSeconds(5)))
        // Also between nights (day 2 afternoon) nothing can be changed.
        val night = engine.advance(u.session!!, u.session!!.plan.end)
        val day2 = series.afterSession(u.series, night, "n4", u.session!!.plan.end)
        assertEquals(1, day2.series.completedPeriods)
        val afternoon = t("2026-10-13T12:00:00Z")
        assertIs<SeriesEngine.EditResult.NotAllowed>(series.edit(day2.series, day2.session, day2.series.kind, day2.series.window, sister, day2.series.mode, "n5", afternoon))
        assertNull(series.cancelScheduled(day2.series, day2.session, afternoon))
        assertEquals(MOM, day2.series.contact)
    }

    @Test
    fun `7 - after ACTIVE a Trusted Exit fails the series with the existing rules`() {
        val u = active()
        val wallet = WalletEngine.settle(ok(WalletEngine.reserve(funded(10_000), "s1", 5_000 * rub, at13)), u.series, at23)
        val exitAt = at23 + Duration.ofHours(2)
        val issued = assertIs<LockEngine.CodeRequestResult.Issued>(engine.requestCode(u.session!!, ExitKind.END_SESSION, exitAt))
        val exited = assertIs<LockEngine.CodeResult.Accepted>(engine.submitCode(issued.session, issued.plainCode, exitAt)).session
        val failed = series.afterSession(u.series, exited, "n3", exitAt).series
        assertEquals(SeriesStatus.BROKEN, failed.status)
        assertEquals(exitAt, failed.endedAt)
        // 30-day wait for a free series, Restart at the series price.
        val ent = StandardRules.afterSeries(Entitlements(), failed, exitAt)
        assertEquals(exitAt, ent.standardFailedAt)
        assertEquals(30, StandardRules.daysUntilFreeStart(ent, exitAt))
        assertEquals(299, failed.kind.restartPriceRub)
        // "Цена слова": forfeited, 50% back after 24 hours.
        val settled = WalletEngine.settle(wallet, failed, exitAt)
        assertEquals(CommitmentState.FORFEITED, settled.commitmentFor("s1")!!.state)
        assertEquals(exitAt + Duration.ofHours(24), settled.nextReturnDue)
        assertEquals(7_500 * rub, WalletEngine.processDue(settled, exitAt + Duration.ofHours(24)).availableKop)
    }

    @Test
    fun `8 - start time reached but protection not confirmed - not active, not failed, no financial consequences`() {
        val activating = begin(scheduled())
        assertEquals(SeriesStatus.ACTIVATING, activating.series.status)
        val wallet = ok(WalletEngine.reserve(funded(10_000), "s1", 5_000 * rub, at13))
        assertEquals(wallet, WalletEngine.settle(wallet, activating.series, at23), "nothing is fixed while activating")
        // The lock screen never came up: the planned end passes without confirmation.
        val end = activating.session!!.plan.end
        val night = engine.advance(activating.session!!, end)
        val after = series.afterSession(activating.series, night, "n3", end)
        assertEquals(SeriesStatus.SCHEDULED, after.series.status, "not ACTIVE, not FAILED")
        assertNull(after.series.actualStartedAt)
        assertEquals(0, after.series.completedPeriods)
        assertEquals(end, after.series.protectionMissedAt)
        assertNotNull(after.session)
        assertEquals(SessionStatus.SCHEDULED, after.session!!.status, "the next night is planned")
        // No 30-day wait, the reserve is untouched and still changeable.
        assertNull(StandardRules.afterSeries(Entitlements(), after.series, end).standardFailedAt)
        val settled = WalletEngine.settle(wallet, after.series, end)
        assertEquals(wallet, settled)
        assertEquals(CommitmentState.SCHEDULED, settled.commitmentFor("s1")!!.state)
        assertNull(settled.nextReturnDue)
    }

    @Test
    fun `cancelling while protection is still not confirmed has no consequences either`() {
        val activating = begin(scheduled())
        val wallet = ok(WalletEngine.reserve(funded(10_000), "s1", 5_000 * rub, at13))
        val at = at23 + Duration.ofMinutes(20)
        val cancelled = series.cancelScheduled(activating.series, activating.session, at)!!
        assertEquals(SeriesStatus.CANCELLED_BEFORE_START, cancelled.series.status)
        assertEquals(LockState.Free, engine.stateOf(cancelled.session, at))
        assertEquals(10_000 * rub, WalletEngine.settle(wallet, cancelled.series, at).availableKop)
        assertNull(StandardRules.afterSeries(Entitlements(), cancelled.series, at).standardFailedAt)
    }

    @Test
    fun `confirmation needs a running lock`() {
        val u = scheduled()
        assertNull(series.confirmActive(u.series, u.session, at15), "nothing to confirm before the start time")
        val activating = begin(u)
        val ended = engine.advance(activating.session!!, activating.session!!.plan.end)
        assertNull(series.confirmActive(activating.series, ended, activating.session!!.plan.end))
    }

    @Test
    fun `a series confirmed inside its window starts activating at once`() {
        val lateEvening = t("2026-10-12T20:30:00Z") // 23:30 Moscow, window already running
        val u = series.start("s2", "m1", SeriesKind.THREE, window, MOM, SessionMode.SLEEP, lateEvening)
        assertEquals(SeriesStatus.ACTIVATING, u.series.status)
        assertEquals(SeriesStatus.ACTIVE, series.confirmActive(u.series, u.session, lateEvening)!!.status)
    }

    @Test
    fun `state saved by v0_6 - a series whose first lock has not started becomes scheduled`() {
        val u = scheduled()
        val w = ok(WalletEngine.reserve(funded(10_000), "s1", 5_000 * rub, at13))
        // v0.6 stored such a series as ACTIVE with a fixed reserve and no model marker.
        val old = StateCodec.encode(
            Snapshot(u.session, null, at13, u.series.copy(status = SeriesStatus.ACTIVE), wallet = w.copy(
                commitments = w.commitments.map { it.copy(state = CommitmentState.RESERVED) },
            )),
        ).filterKeys { it != "sr.model" }
        val upgraded = StateCodec.decode(old)
        assertEquals(SeriesStatus.SCHEDULED, upgraded.series!!.status)
        assertEquals(CommitmentState.SCHEDULED, upgraded.wallet.commitmentFor("s1")!!.state)
        // One that had already begun stays ACTIVE.
        val running = StateCodec.encode(
            Snapshot(engine.advance(u.session!!, at23), null, at23, u.series.copy(status = SeriesStatus.ACTIVE), wallet = w),
        ).filterKeys { it != "sr.model" }
        val kept = StateCodec.decode(running).series!!
        assertEquals(SeriesStatus.ACTIVE, kept.status)
        assertEquals(at23, kept.actualStartedAt)
    }
}
