package app.mama.core

import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Whole-state transitions: Restart held until ACTIVE, cancel vs. activation, atomic results. */
class SeriesFlowTest {
    private val lock = engine()
    private val flow = SeriesFlow(lock)
    private val window = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), MSK)
    private val failedAt = t("2026-10-10T21:00:00Z")
    private val now = t("2026-10-12T10:00:00Z") // 13:00 Moscow
    private val at23 = t("2026-10-12T20:00:00Z") // the first lock
    private val rub = 100L
    private val sister = TrustedContact("Сестра", "+79007654321")

    /** A failed 7-day series, the 30-day wait running, 10 000 available. */
    private fun afterFailure() = Snapshot(
        series = Series(
            "old", SeriesKind.SEVEN, window, MOM, SessionMode.SLEEP, startedAt = failedAt - Duration.ofDays(3),
            completedPeriods = 2, status = SeriesStatus.BROKEN, endedAt = failedAt, actualStartedAt = failedAt - Duration.ofDays(3),
        ),
        entitlements = Entitlements(standardFailedAt = failedAt),
        wallet = Wallet(availableKop = 10_000 * rub),
    )

    private fun done(r: SeriesFlow.Result) = assertIs<SeriesFlow.Result.Done>(r).snapshot

    /** Restart from the balance (299 for a 7-day series) with "Цена слова" 1 000. */
    private fun restarted() = done(flow.restart(afterFailure(), GrantSource.INTERNAL_BALANCE, "new", "n1", 1_000 * rub, now))

    /** 23:00: the first lock of [s] starts. */
    private fun lockStarts(s: Snapshot) = flow.fold(s.copy(session = lock.advance(s.session!!, at23)), "n2", at23)

    @Test
    fun `restart 299 - scheduled - cancelled before the first lock - everything back, no new wait, nothing failed`() {
        val r = restarted()
        assertEquals(SeriesStatus.SCHEDULED, r.series!!.status)
        assertEquals("old", r.series!!.restartOf)
        assertEquals(10_000 * rub - 299 * rub - 1_000 * rub, r.wallet.availableKop)
        assertEquals(299 * rub, r.wallet.restartReservedKop, "the Restart is only held")
        assertEquals(1_000 * rub, r.wallet.reservedKop)
        assertTrue(r.wallet.entries.none { it.type == LedgerType.RESTART_PURCHASE_TEST }, "not used yet")

        val c = flow.cancel(r, now + Duration.ofHours(2))!!
        assertEquals(SeriesStatus.CANCELLED_BEFORE_START, c.cancelled.status)
        val w = c.snapshot.wallet
        assertEquals(10_000 * rub, w.availableKop, "Restart 299 and «Цена слова» fully back")
        assertEquals(0, w.restartReservedKop)
        assertEquals(0, w.reservedKop)
        assertEquals(RestartHoldState.RETURNED, w.restartHoldFor("new")!!.state)
        assertEquals(299 * rub, w.entries.single { it.type == LedgerType.RESTART_RELEASED_BEFORE_START }.amountKop)
        assertTrue(w.entries.none { it.type == LedgerType.RESTART_PURCHASE_TEST })
        assertTrue(w.entries.none { it.type == LedgerType.COMMITMENT_FORFEITED_FAILURE })
        assertNull(w.nextReturnDue)
        // No new waiting period from the cancel: the original failure date is unchanged.
        assertEquals(failedAt, c.snapshot.entitlements.standardFailedAt)
        // The restarted series did not fail; the earlier failed series is back as it was, with its Restart.
        assertEquals("old", c.snapshot.series!!.id)
        assertEquals(SeriesStatus.BROKEN, c.snapshot.series!!.status)
        assertNull(c.snapshot.restartedFrom)
        assertIs<SeriesFlow.Result.Done>(flow.restart(c.snapshot, GrantSource.INTERNAL_BALANCE, "new2", "n3", null, now + Duration.ofHours(3)))
    }

    @Test
    fun `restart 299 - scheduled - strict protection ready - active, and only then the Restart is used`() {
        val activating = lockStarts(restarted())
        assertEquals(SeriesStatus.ACTIVATING, activating.series!!.status)
        assertEquals(299 * rub, activating.wallet.restartReservedKop, "still held while activating")
        assertEquals(failedAt, activating.entitlements.standardFailedAt)

        val confirmedAt = at23.plusSeconds(1)
        val active = flow.confirm(activating, confirmedAt)!!
        assertEquals(SeriesStatus.ACTIVE, active.series!!.status)
        assertEquals(confirmedAt, active.series!!.actualStartedAt)
        assertEquals(0, active.wallet.restartReservedKop)
        val used = active.wallet.entries.single { it.type == LedgerType.RESTART_PURCHASE_TEST }
        assertEquals(299 * rub, used.amountKop)
        assertEquals(confirmedAt, used.at, "used at the commitment point")
        assertEquals(RestartHoldState.CONSUMED, active.wallet.restartHoldFor("new")!!.state)
        assertEquals(activating.wallet.availableKop, active.wallet.availableKop, "nothing more is taken at that moment")
        assertEquals(CommitmentState.RESERVED, active.wallet.commitmentFor("new")!!.state)
        assertNull(active.entitlements.standardFailedAt, "the Restart lifted the 30-day wait of that failure")
        assertNull(active.restartedFrom)
        assertNull(flow.cancel(active, confirmedAt.plusSeconds(60)), "no cancel after ACTIVE")
        // Folding again changes nothing (used once).
        assertEquals(active, flow.fold(active, "n9", confirmedAt.plusSeconds(60)))
        assertEquals(active, StateCodec.decode(StateCodec.encode(active)))
    }

    @Test
    fun `a Restart whose protection could not be switched on keeps its hold until the user decides`() {
        val activating = lockStarts(restarted())
        val failed = flow.protectionFailed(activating, at23.plusSeconds(3))!!
        assertEquals(SeriesStatus.NEEDS_ATTENTION, failed.series!!.status)
        assertEquals(299 * rub, failed.wallet.restartReservedKop)
        assertEquals(failedAt, failed.entitlements.standardFailedAt)
        assertEquals(10_000 * rub, flow.cancel(failed, at23.plusSeconds(60))!!.snapshot.wallet.availableKop)
    }

    @Test
    fun `a Restart keeps the length of the failed series, other conditions can change before the start`() {
        val r = restarted()
        assertIs<SeriesFlow.Result.NotAllowed>(flow.edit(r, SeriesKind.THREE, window, MOM, SessionMode.SLEEP, 1_000 * rub, "n3", now))
        val later = DailyWindow(LocalTime.of(22, 0), LocalTime.of(6, 0), MSK)
        val edited = done(flow.edit(r, SeriesKind.SEVEN, later, sister, SessionMode.SLEEP, 2_000 * rub, "n3", now))
        assertEquals(sister, edited.series!!.contact)
        assertEquals(later, edited.series!!.window)
        assertEquals(2_000 * rub, edited.wallet.reservedKop)
        assertEquals(299 * rub, edited.wallet.restartReservedKop)
        assertEquals(10_000 * rub - 299 * rub - 2_000 * rub, edited.wallet.availableKop)
    }

    @Test
    fun `a Restart paid from the balance needs the available balance`() {
        val poor = afterFailure().copy(wallet = Wallet(availableKop = 100 * rub))
        assertEquals(100 * rub, assertIs<SeriesFlow.Result.NotEnoughBalance>(flow.restart(poor, GrantSource.INTERNAL_BALANCE, "new", "n1", null, now)).availableKop)
        // A test grant holds nothing; a cancel still restores the failure as it was.
        val granted = done(flow.restart(poor, GrantSource.TEST_NO_PAYMENT, "new", "n1", null, now))
        assertEquals(0, granted.wallet.restartReservedKop)
        val c = flow.cancel(granted, now.plusSeconds(60))!!.snapshot
        assertEquals(100 * rub, c.wallet.availableKop)
        assertEquals(failedAt, c.entitlements.standardFailedAt)
    }

    @Test
    fun `cancel and activation never both apply - cancel first`() {
        val activating = lockStarts(done(flow.schedule(Snapshot(wallet = Wallet(availableKop = 10_000 * rub)), "s1", "n1", SeriesKind.SEVEN, window, MOM, SessionMode.SLEEP, 5_000 * rub, now)))
        val at = at23.plusSeconds(1)
        val cancelled = flow.cancel(activating, at)!!
        assertEquals(SeriesStatus.CANCELLED_BEFORE_START, cancelled.cancelled.status)
        assertNull(flow.confirm(cancelled.snapshot, at), "the commitment point no longer applies")
        assertEquals(10_000 * rub, cancelled.snapshot.wallet.availableKop)
    }

    @Test
    fun `cancel and activation never both apply - activation first`() {
        val activating = lockStarts(done(flow.schedule(Snapshot(wallet = Wallet(availableKop = 10_000 * rub)), "s1", "n1", SeriesKind.SEVEN, window, MOM, SessionMode.SLEEP, 5_000 * rub, now)))
        val at = at23.plusSeconds(1)
        val active = flow.confirm(activating, at)!!
        assertEquals(SeriesStatus.ACTIVE, active.series!!.status)
        assertNull(flow.cancel(active, at), "the cancel is refused")
        assertNull(flow.protectionFailed(active, at), "and so is giving up on the activation")
        assertEquals(CommitmentState.RESERVED, active.wallet.commitmentFor("s1")!!.state)
    }

    @Test
    fun `not enough balance for the commitment leaves the state untouched`() {
        val s = Snapshot(wallet = Wallet(availableKop = 500 * rub))
        assertEquals(500 * rub, assertIs<SeriesFlow.Result.NotEnoughBalance>(flow.schedule(s, "s1", "n1", SeriesKind.THREE, window, MOM, SessionMode.SLEEP, 1_000 * rub, now)).availableKop)
    }

    @Test
    fun `restart state survives the codec`() {
        val r = lockStarts(restarted())
        val decoded = StateCodec.decode(StateCodec.encode(r))
        assertEquals(r, decoded)
        assertEquals("old", decoded.restartedFrom!!.id)
        assertEquals(at23, decoded.series!!.activatingSince)
    }
}
