package app.mama.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.LocalTime

/** Whole-state transitions: Restart held until ACTIVE, cancel vs. activation, atomic results. */
class SeriesFlowTest {
    private val lock = engine()
    private val flow = SeriesFlow(lock)
    private val window = DailyWindow(LocalTime(23, 0), LocalTime(7, 0), MSK)
    private val failedAt = t("2026-10-10T21:00:00Z")
    private val now = t("2026-10-12T10:00:00Z") // 13:00 Moscow
    private val at23 = t("2026-10-12T20:00:00Z") // the first lock
    private val rub = 100L
    private val sister = TrustedContact("Сестра", "+79007654321")

    /** A failed 7-day series, the 30-day wait running, 10 000 available. */
    private fun afterFailure() = Snapshot(
        series = Series(
            "old", SeriesKind.SEVEN, window, MOM, SessionMode.SLEEP, startedAt = failedAt - 3.days,
            completedPeriods = 2, status = SeriesStatus.BROKEN, endedAt = failedAt, actualStartedAt = failedAt - 3.days,
        ),
        entitlements = Entitlements(standardFailedAt = failedAt),
        wallet = Wallet(availableKop = 10_000 * rub),
    )

    private fun done(r: SeriesFlow.Result) = assertIs<SeriesFlow.Result.Done>(r).snapshot

    /** Restart from the balance (299 for a 7-day series) with "Цена слова" 1 000. */
    private fun restarted() = done(flow.restart(afterFailure(), GrantSource.INTERNAL_BALANCE, window, "new", "n1", 1_000 * rub, now))

    /** 23:00: the first lock of [s] starts. */
    private fun lockStarts(s: Snapshot) = flow.fold(s.copy(session = lock.advance(s.session!!, at23)), "n2", at23)

    @Test
    fun `restart 299 - scheduled - cancelled before the first lock - everything back - no new wait - nothing failed`() {
        val r = restarted()
        assertEquals(SeriesStatus.SCHEDULED, r.series!!.status)
        assertEquals("old", r.series!!.restartOf)
        assertEquals(10_000 * rub - 299 * rub - 1_000 * rub, r.wallet.availableKop)
        assertEquals(299 * rub, r.wallet.restartReservedKop, "the Restart is only held")
        assertEquals(1_000 * rub, r.wallet.reservedKop)
        assertTrue(r.wallet.entries.none { it.type == LedgerType.RESTART_PURCHASE_TEST }, "not used yet")

        val c = flow.cancel(r, now + 2.hours)!!
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
        assertIs<SeriesFlow.Result.Done>(flow.restart(c.snapshot, GrantSource.INTERNAL_BALANCE, window, "new2", "n3", null, now + 3.hours))
    }

    @Test
    fun `restart 299 - scheduled - strict protection ready - active - and only then the Restart is used`() {
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
    fun `a Restart keeps the length of the failed series and takes a newly chosen time - never the failed one`() {
        // Failed series 15:46 -> 07:00; the Restart draft chose 16:20 -> 00:20.
        val failedAt1546 = afterFailure().let { it.copy(series = it.series!!.copy(window = DailyWindow(LocalTime(15, 46), LocalTime(7, 0), MSK))) }
        val chosen = DailyWindow(LocalTime(16, 20), LocalTime(0, 20), MSK)
        val fresh = done(flow.restart(failedAt1546, GrantSource.INTERNAL_BALANCE, chosen, "new", "n1", null, now))
        assertEquals(SeriesKind.SEVEN, fresh.series!!.kind, "same length")
        assertEquals(chosen, fresh.series!!.window, "new time, not 15:46")
        assertEquals(t("2026-10-12T13:20:00Z"), fresh.session!!.plan.start, "first full window today 16:20")
    }

    @Test
    fun `once confirmed - a Restart's time is fixed - contact and commitment can change before the start`() {
        val r = restarted()
        assertEquals(SeriesKind.SEVEN, r.series!!.kind)
        assertEquals(window, r.series!!.window)
        val edited = done(flow.edit(r, sister, 2_000 * rub, now))
        assertEquals(sister, edited.series!!.contact)
        assertEquals(window, edited.series!!.window)
        assertEquals(SeriesKind.SEVEN, edited.series!!.kind)
        assertEquals(r.session!!.plan, edited.session!!.plan)
        assertEquals(2_000 * rub, edited.wallet.reservedKop)
        assertEquals(299 * rub, edited.wallet.restartReservedKop)
        assertEquals(10_000 * rub - 299 * rub - 2_000 * rub, edited.wallet.availableKop)
    }

    @Test
    fun `a Restart paid from the balance needs the available balance`() {
        val poor = afterFailure().copy(wallet = Wallet(availableKop = 100 * rub))
        assertEquals(100 * rub, assertIs<SeriesFlow.Result.NotEnoughBalance>(flow.restart(poor, GrantSource.INTERNAL_BALANCE, window, "new", "n1", null, now)).availableKop)
        // A test grant holds nothing; a cancel still restores the failure as it was.
        val granted = done(flow.restart(poor, GrantSource.TEST_NO_PAYMENT, window, "new", "n1", null, now))
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

    @Test
    fun `failed 1 Oct - Restart 5 Oct - cancelled 6 Oct before the first lock - free series still from 31 Oct`() {
        val failedOn = t("2026-10-01T09:00:00Z")
        val failure = afterFailure().let {
            it.copy(series = it.series!!.copy(endedAt = failedOn), entitlements = Entitlements(standardFailedAt = failedOn))
        }
        val freeFrom = t("2026-10-31T09:00:00Z")
        assertEquals(freeFrom, StandardRules.freeStartAt(failure.entitlements))

        val oct5 = t("2026-10-05T09:00:00Z")
        val restarted = done(flow.restart(failure, GrantSource.INTERNAL_BALANCE, window, "new", "n1", 1_000 * rub, oct5))
        assertEquals(SeriesStatus.SCHEDULED, restarted.series!!.status)
        assertEquals(freeFrom, StandardRules.freeStartAt(restarted.entitlements))

        val oct6 = t("2026-10-06T09:00:00Z")
        val c = flow.cancel(restarted, oct6)!!
        assertEquals(SeriesStatus.CANCELLED_BEFORE_START, c.cancelled.status)
        // Restart reserve and «Цена слова» fully back.
        assertEquals(10_000 * rub, c.snapshot.wallet.availableKop)
        assertEquals(0, c.snapshot.wallet.restartReservedKop)
        assertEquals(0, c.snapshot.wallet.reservedKop)
        // Not a new failure, no new 30 days: the same date as before the Restart.
        assertEquals(failedOn, c.snapshot.entitlements.standardFailedAt)
        assertEquals(freeFrom, StandardRules.freeStartAt(c.snapshot.entitlements))
        assertEquals(25, StandardRules.daysUntilFreeStart(c.snapshot.entitlements, oct6))
        assertEquals(false, StandardRules.canStartFree(c.snapshot.entitlements, t("2026-10-30T09:00:00Z")))
        assertEquals(true, StandardRules.canStartFree(c.snapshot.entitlements, freeFrom))
        // Folding later never moves the date either.
        assertEquals(freeFrom, StandardRules.freeStartAt(flow.fold(c.snapshot, "n9", t("2026-10-20T09:00:00Z")).entitlements))
    }

    @Test
    fun `cancelled before start never creates a cooldown by itself - the first free series can be created again at once`() {
        val empty = Snapshot(wallet = Wallet(availableKop = 10_000 * rub))
        fun scheduled() = done(flow.schedule(empty, "s1", "n1", SeriesKind.SEVEN, window, MOM, SessionMode.SLEEP, 5_000 * rub, now))
        val cancelledStates = listOf(
            // SCHEDULED
            flow.cancel(scheduled(), now.plusSeconds(60))!!,
            // ACTIVATING
            flow.cancel(lockStarts(scheduled()), at23.plusSeconds(1))!!,
            // NEEDS_ATTENTION
            flow.cancel(flow.protectionFailed(lockStarts(scheduled()), at23.plusSeconds(2))!!, at23.plusSeconds(60))!!,
        )
        for (c in cancelledStates) {
            assertEquals(SeriesStatus.CANCELLED_BEFORE_START, c.cancelled.status)
            assertNull(c.snapshot.entitlements.standardFailedAt)
            assertTrue(StandardRules.canStartFree(c.snapshot.entitlements, at23.plusSeconds(60)))
            assertEquals(10_000 * rub, c.snapshot.wallet.availableKop)
            assertIs<SeriesFlow.Result.Done>(
                flow.schedule(c.snapshot, "s2", "n2", SeriesKind.THREE, window, MOM, SessionMode.SLEEP, null, at23.plusSeconds(60)),
            )
        }
    }
}
