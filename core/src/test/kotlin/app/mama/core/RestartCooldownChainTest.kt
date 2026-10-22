package app.mama.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cooldown re-anchors to the LATEST failure, not the first. Each broken
 * standard series — free or a paid Restart — sets a fresh [Entitlements.standardFailedAt],
 * so the 30-day free-start wait always runs from the most recent срыв. A paid
 * Restart lets the user start again at once during the wait but does not by
 * itself reset it; it is the next срыв that moves the clock. Paid Restarts can
 * be bought in a row without limit, one charge each.
 */
class RestartCooldownChainTest {
    private val lock = engine()
    private val flow = SeriesFlow(lock)
    private val rub = 100L
    private val eight = Duration.ofHours(8)
    private var ids = 0
    private fun id() = "x${ids++}"

    private fun done(r: SeriesFlow.Result) = assertIs<SeriesFlow.Result.Done>(r).snapshot

    /** A free AUTO_NOW series, brought straight to ACTIVE (its lock starts now). */
    private fun startFreeActive(s: Snapshot, kind: SeriesKind, now: Instant): Snapshot {
        val scheduled = done(flow.schedule(s, id(), id(), kind, LaunchTime.AutoNow(eight, MSK), MOM, SessionMode.SLEEP, null, now))
        assertEquals(SeriesStatus.ACTIVATING, scheduled.series!!.status)
        return flow.confirm(scheduled, now.plusSeconds(1)) ?: error("confirm free")
    }

    /** A paid Restart AUTO_NOW series during the cooldown, brought straight to ACTIVE. */
    private fun startPaidActive(s: Snapshot, kind: SeriesKind, now: Instant): Snapshot {
        val scheduled = done(flow.startPaid(s, GrantSource.INTERNAL_BALANCE, kind, LaunchTime.AutoNow(eight, MSK), MOM, SessionMode.SLEEP, id(), id(), null, now))
        assertEquals(SeriesStatus.ACTIVATING, scheduled.series!!.status)
        return flow.confirm(scheduled, now.plusSeconds(1)) ?: error("confirm paid")
    }

    /** Trusted Exit on the ACTIVE series at [breakTime]: it fails (BROKEN) and the series is dismissed. */
    private fun breakAndDismiss(s: Snapshot, breakTime: Instant): Snapshot {
        val ended = lock.endByTrustedExit(s.session!!, ExitKind.END_SESSION, breakTime)
        val broken = flow.fold(s.copy(session = ended), id(), breakTime)
        assertEquals(SeriesStatus.BROKEN, broken.series!!.status)
        // "Вернуться на главную": the broken series is forgotten, the failure date kept.
        return broken.copy(series = null)
    }

    /** Folds every period of an ACTIVE series to its end, completing the whole series. */
    private fun completeAll(active: Snapshot, kind: SeriesKind): Snapshot {
        var s = active
        repeat(kind.periods) {
            val end = s.session!!.plan.end
            s = flow.fold(s.copy(session = lock.advance(s.session!!, end)), id(), end)
        }
        return s
    }

    @Test
    fun `each new failure re-anchors the 30-day wait, paid Restarts unlimited and charged once each`() {
        val day0 = t("2026-10-21T10:00:00Z")
        val break1 = day0 + Duration.ofHours(2)
        val day2 = t("2026-10-23T10:00:00Z")
        val break2 = day2 + Duration.ofHours(2)
        val day4 = t("2026-10-25T10:00:00Z")
        val break3 = day4 + Duration.ofHours(2)

        // Break #1: a free 3-day series fails. The 30-day wait starts from break #1.
        var s = startFreeActive(Snapshot(wallet = Wallet(availableKop = 44_000 * rub)), SeriesKind.THREE, day0)
        s = breakAndDismiss(s, break1)
        assertEquals(break1, s.entitlements.standardFailedAt)
        assertEquals(break1 + StandardRules.FREE_START_AFTER, StandardRules.freeStartAt(s.entitlements))
        assertEquals(44_000 * rub, s.wallet.availableKop, "a free start costs nothing")

        // Paid Restart #1 during the cooldown: 99 charged, started at once. The wait does NOT reset yet.
        assertTrue(!StandardRules.canStartFree(s.entitlements, day2))
        s = startPaidActive(s, SeriesKind.THREE, day2)
        assertEquals(break1, s.entitlements.standardFailedAt, "paying does not by itself move the clock")
        assertEquals(44_000 * rub - 99 * rub, s.wallet.availableKop)
        assertEquals(0, s.wallet.restartReservedKop, "the hold is used at ACTIVE")

        // Break #2: the paid series fails. NOW the wait re-anchors to break #2, not break #1.
        s = breakAndDismiss(s, break2)
        assertEquals(break2, s.entitlements.standardFailedAt, "the clock moves to the new срыв")
        assertEquals(break2 + StandardRules.FREE_START_AFTER, StandardRules.freeStartAt(s.entitlements))
        // Just past break #1's own 30-day window: had the clock stayed on break #1 a free start would be open,
        // but it is anchored to the later break #2, so the free start is still blocked.
        val pastBreak1Window = break1 + Duration.ofDays(31)
        assertTrue(StandardRules.canStartFree(Entitlements(standardFailedAt = break1), pastBreak1Window), "break #1 alone would be free by then")
        assertTrue(!StandardRules.canStartFree(s.entitlements, pastBreak1Window), "but break #2 still blocks it")

        // Paid Restart #2 during the new cooldown: another 99, again one charge.
        s = startPaidActive(s, SeriesKind.THREE, day4)
        assertEquals(break2, s.entitlements.standardFailedAt)
        assertEquals(44_000 * rub - 2 * 99 * rub, s.wallet.availableKop, "two Restarts, 99 each")

        // Break #3: the wait re-anchors again to break #3.
        s = breakAndDismiss(s, break3)
        assertEquals(break3, s.entitlements.standardFailedAt)
        assertEquals(break3 + StandardRules.FREE_START_AFTER, StandardRules.freeStartAt(s.entitlements))

        // Exactly two paid Restarts were consumed, one charge each — no double spend across the chain.
        assertEquals(2, s.wallet.entries.count { it.type == LedgerType.RESTART_PURCHASE_TEST })
        assertEquals(0, s.wallet.restartReservedKop)
        assertEquals(44_000 * rub - 2 * 99 * rub, s.wallet.availableKop)
    }

    @Test
    fun `daysUntilFreeStart counts from the second failure, not the first`() {
        val break1 = t("2026-10-21T10:00:00Z")
        val break2 = t("2026-10-23T10:00:00Z")
        val ent = StandardRules.afterSeries(
            Entitlements(standardFailedAt = break1),
            Series("s2", SeriesKind.THREE, DailyWindow(java.time.LocalTime.of(23, 0), java.time.LocalTime.of(7, 0), MSK), MOM, SessionMode.SLEEP, startedAt = break1, status = SeriesStatus.BROKEN, endedAt = break2),
            break2,
        )
        assertEquals(break2, ent.standardFailedAt)
        // 30 days from break2 is 22 Nov; measured at break2 that is 30 days, not 28.
        assertEquals(30, StandardRules.daysUntilFreeStart(ent, break2))
    }

    // Rehabilitation: finishing a paid Restart clears the cooldown, so the next standard series is free.
    // 44000 -> break -> Restart price -> (44000 - price) -> SUCCESS -> next series FREE. For 99 / 179 / 299.
    @Test
    fun `a completed paid Restart rehabilitates - the next standard series is free`() {
        for (kind in SeriesKind.entries) {
            ids = 0
            val price = kind.restartPriceRub.toLong()
            var s = breakAndDismiss(
                startFreeActive(Snapshot(wallet = Wallet(availableKop = 44_000 * rub)), kind, t("2026-10-21T10:00:00Z")),
                t("2026-10-21T12:00:00Z"),
            )
            // Paid Restart: the length's price leaves the balance, the series starts.
            val active = startPaidActive(s, kind, t("2026-10-23T10:00:00Z"))
            assertEquals((44_000 - price) * rub, active.wallet.availableKop, "paid Restart of $kind charges its price")
            // Completed in full → rehabilitated: the cooldown is cleared.
            val done = completeAll(active, kind)
            assertEquals(SeriesStatus.COMPLETED, done.series!!.status)
            assertNull(done.entitlements.standardFailedAt, "a completed series clears the cooldown for $kind")
            val later = t("2026-11-05T10:00:00Z")
            assertTrue(StandardRules.canStartFree(done.entitlements, later), "$kind: free start available after success")
            assertEquals(StartSource.Free, StandardRules.startSource(done.entitlements, kind, later, done.wallet.availableKop))
            // And the next series actually starts free: the balance is not charged again.
            val next = startFreeActive(done.copy(series = null), kind, later)
            assertEquals((44_000 - price) * rub, next.wallet.availableKop, "$kind: the next start costs nothing")
        }
    }

    // 44000 -> break -> Restart price -> (44000 - price) -> BREAK -> Restart price -> (44000 - 2*price). For 99 / 179 / 299.
    @Test
    fun `break, Restart, break, Restart charges the length price each time`() {
        for (kind in SeriesKind.entries) {
            ids = 0
            val price = kind.restartPriceRub.toLong()
            var s = breakAndDismiss(
                startFreeActive(Snapshot(wallet = Wallet(availableKop = 44_000 * rub)), kind, t("2026-10-21T10:00:00Z")),
                t("2026-10-21T12:00:00Z"),
            )
            s = startPaidActive(s, kind, t("2026-10-23T10:00:00Z"))
            assertEquals((44_000 - price) * rub, s.wallet.availableKop)
            s = breakAndDismiss(s, t("2026-10-23T12:00:00Z"))
            s = startPaidActive(s, kind, t("2026-10-25T10:00:00Z"))
            assertEquals((44_000 - 2 * price) * rub, s.wallet.availableKop)
        }
    }
}
