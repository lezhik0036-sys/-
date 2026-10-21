package app.mama.core

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The копилка ([Entitlements.standardCredits]): completing a PAID Restart
 * series earns one free series of the same length, which can be started at
 * once regardless of the 30-day cooldown. A credit is spent at the start,
 * given back on a cancel before the series begins, and kept spent if the free
 * series itself is broken. A free or копилка series earns nothing.
 */
class KopilkaCreditTest {
    private val lock = engine()
    private val flow = SeriesFlow(lock)
    private val eight = Duration.ofHours(8)
    private val manual = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), MSK)
    private val rub = 100L
    private var ids = 0
    private fun id() = "x${ids++}"

    private fun done(r: SeriesFlow.Result) = assertIs<SeriesFlow.Result.Done>(r).snapshot

    private fun freeActive(s: Snapshot, kind: SeriesKind, now: Instant): Snapshot =
        flow.confirm(done(flow.schedule(s, id(), id(), kind, LaunchTime.AutoNow(eight, MSK), MOM, SessionMode.SLEEP, null, now)), now.plusSeconds(1))!!

    private fun paidActive(s: Snapshot, kind: SeriesKind, now: Instant): Snapshot =
        flow.confirm(done(flow.startPaid(s, GrantSource.INTERNAL_BALANCE, kind, LaunchTime.AutoNow(eight, MSK), MOM, SessionMode.SLEEP, id(), id(), null, now)), now.plusSeconds(1))!!

    private fun creditActive(s: Snapshot, kind: SeriesKind, now: Instant): Snapshot =
        flow.confirm(done(flow.schedule(s, id(), id(), kind, LaunchTime.AutoNow(eight, MSK), MOM, SessionMode.SLEEP, null, now, fromCredit = true)), now.plusSeconds(1))!!

    /** Folds every period of an ACTIVE series to its end, completing the whole series. */
    private fun completeAll(active: Snapshot, kind: SeriesKind): Snapshot {
        var s = active
        repeat(kind.periods) {
            val end = s.session!!.plan.end
            s = flow.fold(s.copy(session = lock.advance(s.session!!, end)), id(), end)
        }
        return s
    }

    /** Trusted Exit on the ACTIVE series, folded to BROKEN, then the broken series dismissed. */
    private fun breakAndDismiss(active: Snapshot, breakTime: Instant): Snapshot {
        val ended = lock.endByTrustedExit(active.session!!, ExitKind.END_SESSION, breakTime)
        return flow.fold(active.copy(session = ended), id(), breakTime).copy(series = null)
    }

    @Test
    fun `completing a paid Restart earns one free series of the same length`() {
        val failedAt = t("2026-10-21T10:00:00Z")
        val s0 = Snapshot(entitlements = Entitlements(standardFailedAt = failedAt), wallet = Wallet(availableKop = 44_000 * rub))
        val now = failedAt + Duration.ofHours(1)
        val active = paidActive(s0, SeriesKind.THREE, now)
        assertEquals(0, active.entitlements.standardCreditsFor(SeriesKind.THREE), "not earned until completed")
        val completed = completeAll(active, SeriesKind.THREE)
        assertEquals(SeriesStatus.COMPLETED, completed.series!!.status)
        assertEquals(1, completed.entitlements.standardCreditsFor(SeriesKind.THREE), "one free 3-day series earned")
        assertEquals(0, completed.entitlements.standardCreditsFor(SeriesKind.SEVEN), "only this length")
    }

    @Test
    fun `completing a free series earns nothing`() {
        val completed = completeAll(freeActive(Snapshot(), SeriesKind.THREE, t("2026-10-21T10:00:00Z")), SeriesKind.THREE)
        assertEquals(SeriesStatus.COMPLETED, completed.series!!.status)
        assertTrue(completed.entitlements.standardCredits.isEmpty())
    }

    @Test
    fun `a credit starts a series during cooldown, free, and is spent once`() {
        val failedAt = t("2026-10-21T10:00:00Z")
        val now = failedAt + Duration.ofDays(2)
        val s0 = Snapshot(
            entitlements = Entitlements(standardFailedAt = failedAt, standardCredits = mapOf(SeriesKind.THREE to 1)),
            wallet = Wallet(availableKop = 0), // no money at all: the credit alone must be enough
        )
        assertFalse(StandardRules.canStartFree(s0.entitlements, now))
        val r = done(flow.schedule(s0, id(), id(), SeriesKind.THREE, LaunchTime.AutoNow(eight, MSK), MOM, SessionMode.SLEEP, null, now, fromCredit = true))
        assertEquals(SeriesStatus.ACTIVATING, r.series!!.status)
        assertEquals(SeriesOrigin.FROM_CREDIT, r.series!!.origin)
        assertEquals(0, r.entitlements.standardCreditsFor(SeriesKind.THREE), "the credit was spent")
        assertEquals(failedAt, r.entitlements.standardFailedAt, "the 30-day cooldown is untouched")
    }

    @Test
    fun `scheduling from a credit with none left is rejected`() {
        val r = flow.schedule(Snapshot(), id(), id(), SeriesKind.THREE, LaunchTime.Fixed(manual), MOM, SessionMode.SLEEP, null, t("2026-10-21T10:00:00Z"), fromCredit = true)
        assertIs<SeriesFlow.Result.Rejected>(r)
    }

    @Test
    fun `a cancelled credit series gives the credit back`() {
        val now = t("2026-10-21T10:00:00Z")
        val s0 = Snapshot(entitlements = Entitlements(standardFailedAt = now - Duration.ofDays(2), standardCredits = mapOf(SeriesKind.THREE to 1)))
        val scheduled = done(flow.schedule(s0, id(), id(), SeriesKind.THREE, LaunchTime.Fixed(manual), MOM, SessionMode.SLEEP, null, now, fromCredit = true))
        assertEquals(0, scheduled.entitlements.standardCreditsFor(SeriesKind.THREE))
        val c = flow.cancel(scheduled, now.plusSeconds(60))!!
        assertEquals(SeriesStatus.CANCELLED_BEFORE_START, c.cancelled.status)
        assertEquals(1, c.snapshot.entitlements.standardCreditsFor(SeriesKind.THREE), "cancel before start returns the credit")
    }

    @Test
    fun `a broken credit series keeps the credit spent and re-anchors the cooldown`() {
        val failedAt = t("2026-10-21T10:00:00Z")
        val now = failedAt + Duration.ofDays(2)
        val s0 = Snapshot(entitlements = Entitlements(standardFailedAt = failedAt, standardCredits = mapOf(SeriesKind.THREE to 1)))
        val active = creditActive(s0, SeriesKind.THREE, now)
        val breakTime = now + Duration.ofHours(2)
        val broken = breakAndDismiss(active, breakTime)
        assertEquals(0, broken.entitlements.standardCreditsFor(SeriesKind.THREE), "a failed free series is not refunded")
        assertEquals(breakTime, broken.entitlements.standardFailedAt, "the срыв re-anchors the cooldown")
    }

    @Test
    fun `completing a credit series earns no further credit`() {
        val now = t("2026-10-21T10:00:00Z")
        val s0 = Snapshot(entitlements = Entitlements(standardFailedAt = now - Duration.ofDays(2), standardCredits = mapOf(SeriesKind.THREE to 1)))
        val completed = completeAll(creditActive(s0, SeriesKind.THREE, now), SeriesKind.THREE)
        assertEquals(SeriesStatus.COMPLETED, completed.series!!.status)
        assertEquals(0, completed.entitlements.standardCreditsFor(SeriesKind.THREE), "a free копилка series earns nothing")
    }

    @Test
    fun `credits survive the codec`() {
        val s = Snapshot(entitlements = Entitlements(standardCredits = mapOf(SeriesKind.THREE to 2, SeriesKind.SEVEN to 1)))
        assertEquals(s, StateCodec.decode(StateCodec.encode(s)))
    }

    @Test
    fun `the user's example - free, break, Restart, break, Restart, complete, earn a credit`() {
        val day0 = t("2026-10-21T10:00:00Z")
        // Free 3-day series, then a срыв.
        var s = breakAndDismiss(freeActive(Snapshot(wallet = Wallet(availableKop = 44_000 * rub)), SeriesKind.THREE, day0), day0 + Duration.ofHours(2))
        // Paid Restart 99, then another срыв two days later.
        val break2 = day0 + Duration.ofDays(1) + Duration.ofHours(2)
        s = breakAndDismiss(paidActive(s, SeriesKind.THREE, day0 + Duration.ofDays(1)), break2)
        assertEquals(break2, s.entitlements.standardFailedAt, "new 30-day cooldown from the second срыв")
        // Paid Restart 99 again, this time completed in full → +1 free 3-day series.
        val completed = completeAll(paidActive(s, SeriesKind.THREE, day0 + Duration.ofDays(3)), SeriesKind.THREE)
        assertEquals(SeriesStatus.COMPLETED, completed.series!!.status)
        assertEquals(1, completed.entitlements.standardCreditsFor(SeriesKind.THREE))
        assertEquals(44_000 * rub - 2 * 99 * rub, completed.wallet.availableKop, "two paid Restarts, 99 each")
    }
}
