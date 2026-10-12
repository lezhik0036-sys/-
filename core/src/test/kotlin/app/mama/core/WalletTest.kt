package app.mama.core

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletTest {
    private val t0 = t("2026-10-10T15:30:00Z") // 18:30 Moscow
    private val rub = 100L

    private fun series(id: String, kind: SeriesKind = SeriesKind.THREE) = Series(
        id = id, kind = kind, window = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), MSK),
        contact = MOM, mode = SessionMode.SLEEP, startedAt = t0,
    )

    private fun ok(r: WalletEngine.Result): Wallet = assertIs<WalletEngine.Result.Ok>(r).wallet

    private fun funded(amountRub: Long) = ok(WalletEngine.topUpTest(Wallet(), amountRub * rub, "topup-1", t0))

    private fun sum(w: Wallet, type: LedgerType) = w.entries.filter { it.type == type && it.status == LedgerStatus.DONE }.sumOf { it.amountKop }

    @Test
    fun `reserving needs enough available balance and moves it to reserved`() {
        val w = funded(4_000)
        assertEquals(4_000 * rub, assertIs<WalletEngine.Result.Insufficient>(WalletEngine.reserve(w, "s1", 5_000 * rub, t0)).availableKop)
        val r = ok(WalletEngine.reserve(funded(6_000), "s1", 5_000 * rub, t0))
        assertEquals(1_000 * rub, r.availableKop)
        assertEquals(5_000 * rub, r.reservedKop)
        assertEquals(CommitmentState.SCHEDULED, r.commitmentFor("s1")!!.state, "changeable until the series is ACTIVE")
        assertEquals(5_000 * rub, r.scheduledKop)
        assertEquals(LedgerType.COMMITMENT_RESERVED, r.entries.last().type)
    }

    @Test
    fun `a series can carry only one commitment and limits are checked`() {
        val r = ok(WalletEngine.reserve(funded(20_000), "s1", 5_000 * rub, t0))
        assertIs<WalletEngine.Result.Invalid>(WalletEngine.reserve(r, "s1", 1_000 * rub, t0))
        assertIs<WalletEngine.Result.Invalid>(WalletEngine.reserve(r, "s2", 10 * rub, t0))
    }

    @Test
    fun `completed series releases the whole commitment, once`() {
        val reserved = ok(WalletEngine.reserve(funded(5_000), "s1", 5_000 * rub, t0))
        val done = series("s1").copy(status = SeriesStatus.COMPLETED, endedAt = t0 + Duration.ofDays(3))
        val w = WalletEngine.settle(reserved, done, t0 + Duration.ofDays(3))
        assertEquals(5_000 * rub, w.availableKop)
        assertEquals(0, w.reservedKop)
        assertEquals(CommitmentState.RELEASED, w.commitmentFor("s1")!!.state)
        assertEquals(w, WalletEngine.settle(w, done, t0 + Duration.ofDays(4)), "settling twice changes nothing")
        assertNull(w.nextReturnDue)
    }

    @Test
    fun `failed series forfeits the commitment and returns half after exactly 24 hours, once`() {
        val reserved = ok(WalletEngine.reserve(funded(5_000), "s1", 5_000 * rub, t0))
        val failedAt = t0 + Duration.ofHours(30)
        val broken = series("s1").copy(status = SeriesStatus.BROKEN, endedAt = failedAt)
        val w = WalletEngine.settle(reserved, broken, failedAt)
        assertEquals(0, w.availableKop)
        assertEquals(0, w.reservedKop)
        val c = w.commitmentFor("s1")!!
        assertEquals(CommitmentState.FORFEITED, c.state)
        assertEquals(2_500 * rub, c.returnKop)
        assertEquals(failedAt + Duration.ofHours(24), c.returnDueAt)
        assertEquals(5_000 * rub, sum(w, LedgerType.COMMITMENT_FORFEITED_FAILURE))

        // Not before the due moment.
        assertEquals(w, WalletEngine.processDue(w, failedAt + Duration.ofHours(24).minusSeconds(1)))
        // At the due moment: 2 500 credited.
        val back = WalletEngine.processDue(w, failedAt + Duration.ofHours(24))
        assertEquals(2_500 * rub, back.availableKop)
        assertTrue(back.commitmentFor("s1")!!.returnProcessed)
        assertEquals(2_500 * rub, sum(back, LedgerType.COMMITMENT_PARTIAL_RETURN))
        // Running again (another tick, a reboot) credits nothing more.
        assertEquals(back, WalletEngine.processDue(back, failedAt + Duration.ofDays(3)))
        assertEquals(1, back.unseenReturns.size)
        assertTrue(WalletEngine.markReturnsSeen(back).unseenReturns.isEmpty())
    }

    @Test
    fun `pending return survives a save and reload and is credited once after the reboot`() {
        val reserved = ok(WalletEngine.reserve(funded(5_000), "s1", 5_000 * rub, t0))
        val broken = series("s1").copy(status = SeriesStatus.BROKEN, endedAt = t0)
        val pending = WalletEngine.settle(reserved, broken, t0)
        val restored = StateCodec.decode(StateCodec.encode(Snapshot(wallet = pending))).wallet
        assertEquals(pending, restored)
        val credited = WalletEngine.processDue(restored, t0 + Duration.ofDays(2))
        val again = StateCodec.decode(StateCodec.encode(Snapshot(wallet = credited))).wallet
        assertEquals(credited, again)
        assertEquals(credited, WalletEngine.processDue(again, t0 + Duration.ofDays(3)))
        assertEquals(2_500 * rub, again.availableKop)
    }

    @Test
    fun `restart costs the standard price, never the commitment`() {
        // First free 3-day series with a 5 000 commitment fails; 2 500 comes back.
        val reserved = ok(WalletEngine.reserve(funded(5_000), "s1", 5_000 * rub, t0))
        val failed = WalletEngine.settle(reserved, series("s1").copy(status = SeriesStatus.BROKEN, endedAt = t0), t0)
        val back = WalletEngine.processDue(failed, t0 + Duration.ofHours(24))
        // Restart of the 3-day series: 99, not 5 000 and not 5 099.
        val price = SeriesKind.THREE.restartPriceRub * rub
        val paid = ok(WalletEngine.spend(back, price, LedgerType.RESTART_PURCHASE_TEST, "restart-s2", t0 + Duration.ofHours(25), seriesId = "s2"))
        assertEquals(2_500 * rub - 99 * rub, paid.availableKop)
        assertEquals(99 * rub, sum(paid, LedgerType.RESTART_PURCHASE_TEST))
    }

    @Test
    fun `the next series asks again - nothing carries forward`() {
        val reserved = ok(WalletEngine.reserve(funded(10_000), "s1", 5_000 * rub, t0))
        val released = WalletEngine.settle(reserved, series("s1").copy(status = SeriesStatus.COMPLETED, endedAt = t0), t0)
        assertNull(released.commitmentFor("s2"))
        // The next series runs without a commitment unless a new one is reserved for it.
        val next = series("s2").copy(status = SeriesStatus.BROKEN, endedAt = t0)
        assertEquals(released, WalletEngine.settle(released, next, t0))
        val second = ok(WalletEngine.reserve(released, "s2", 1_000 * rub, t0))
        assertEquals(1_000 * rub, second.commitmentFor("s2")!!.amountKop)
        assertEquals(9_000 * rub, second.availableKop)
    }

    @Test
    fun `odd amounts return exactly half in kopecks`() {
        val reserved = ok(WalletEngine.reserve(funded(1_001), "s1", 1_001 * rub, t0))
        val failed = WalletEngine.settle(reserved, series("s1").copy(status = SeriesStatus.BROKEN, endedAt = t0), t0)
        assertEquals(500_50L, failed.commitmentFor("s1")!!.returnKop)
    }

    @Test
    fun `spending more than available is refused`() {
        val w = funded(50)
        assertIs<WalletEngine.Result.Insufficient>(WalletEngine.spend(w, 99 * rub, LedgerType.RESTART_PURCHASE_TEST, "r", t0))
    }

    @Test
    fun `old state without wallet keys decodes to an empty wallet`() {
        val map = StateCodec.encode(Snapshot()).filterKeys { !it.startsWith("wl.") } + ("v" to "1")
        assertEquals(Wallet(), StateCodec.decode(map).wallet)
    }

    @Test
    fun `purchases use only the available balance, never the reserved one`() {
        // Balance 10 000, "Цена слова" 5 000 reserved: only 5 000 can be spent.
        val reserved = ok(WalletEngine.reserve(funded(10_000), "s1", 5_000 * rub, t0))
        assertEquals(5_000 * rub, reserved.availableKop)
        assertEquals(5_000 * rub, reserved.reservedKop)
        assertIs<WalletEngine.Result.Insufficient>(
            WalletEngine.spend(reserved, 5_001 * rub, LedgerType.FLEX_PURCHASE_TEST, "f", t0),
        )
        val spent = ok(WalletEngine.spend(reserved, 5_000 * rub, LedgerType.FLEX_PURCHASE_TEST, "f", t0))
        assertEquals(0, spent.availableKop)
        assertEquals(5_000 * rub, spent.reservedKop, "the reserve is untouched")
    }

    @Test
    fun `cancelling a scheduled series before its first lock gives the whole commitment back`() {
        val engine = engine()
        val seriesEngine = SeriesEngine(engine)
        val start = seriesEngine.start("s1", "n1", SeriesKind.THREE, DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), MSK), MOM, SessionMode.SLEEP, t0)
        val wallet = ok(WalletEngine.reserve(funded(10_000), "s1", 5_000 * rub, t0))
        // The first night has not started yet; the user cancels.
        val cancelled = seriesEngine.cancelScheduled(start.series, start.session, t0 + Duration.ofMinutes(5))!!
        assertEquals(SeriesStatus.CANCELLED_BEFORE_START, cancelled.series.status)
        val settled = WalletEngine.settle(wallet, cancelled.series, t0 + Duration.ofMinutes(5))
        assertEquals(10_000 * rub, settled.availableKop)
        assertEquals(0, settled.reservedKop)
        assertNull(settled.commitmentFor("s1"))
        assertNull(settled.nextReturnDue, "no partial return: nothing was forfeited")
        assertEquals(0, sum(settled, LedgerType.COMMITMENT_FORFEITED_FAILURE))
        assertEquals(settled, WalletEngine.settle(settled, cancelled.series, t0 + Duration.ofHours(30)), "released once")
    }

    @Test
    fun `limits come from the commitment configuration`() {
        val w = funded(CommitmentConfig.MAX_COMMITMENT_PRICE_RUB.toLong() + 1)
        assertIs<WalletEngine.Result.Ok>(WalletEngine.reserve(w, "a", CommitmentConfig.MAX_KOP, t0))
        assertIs<WalletEngine.Result.Invalid>(WalletEngine.reserve(w, "b", CommitmentConfig.MAX_KOP + 100, t0))
        assertIs<WalletEngine.Result.Invalid>(WalletEngine.reserve(w, "c", CommitmentConfig.MIN_KOP - 1, t0))
    }

    @Test
    fun `return is due 24 hours after the moment of the exit, even if processed later`() {
        val engine = engine()
        val seriesEngine = SeriesEngine(engine)
        val window = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), MSK)
        val start = seriesEngine.start("s1", "n1", SeriesKind.THREE, window, MOM, SessionMode.SLEEP, t0)
        val wallet = ok(WalletEngine.reserve(funded(5_000), "s1", 5_000 * rub, t0))
        // The first night begins and protection is confirmed: the series is ACTIVE.
        val nightStart = start.session!!.plan.start
        val begun = engine.advance(start.session!!, nightStart)
        val activating = seriesEngine.afterSession(start.series, begun, "n2", nightStart).series
        val active = seriesEngine.confirmActive(activating, begun, nightStart)!!
        // Trusted Exit 10 minutes later.
        val exitAt = nightStart + Duration.ofMinutes(10)
        val issued = assertIs<LockEngine.CodeRequestResult.Issued>(engine.requestCode(begun, ExitKind.END_SESSION, exitAt))
        val exited = assertIs<LockEngine.CodeResult.Accepted>(engine.submitCode(issued.session, issued.plainCode, exitAt)).session
        // The app only reconciles three hours later (e.g. it was not running).
        val later = exitAt + Duration.ofHours(3)
        val broken = seriesEngine.afterSession(active, exited, "n3", later).series
        assertEquals(exitAt, broken.endedAt)
        val settled = WalletEngine.settle(wallet, broken, later)
        assertEquals(exitAt + Duration.ofHours(24), settled.commitmentFor("s1")!!.returnDueAt)
        assertEquals(settled, WalletEngine.processDue(settled, exitAt + Duration.ofHours(24).minusMillis(1)), "never before the due moment")
        val credited = WalletEngine.processDue(settled, exitAt + Duration.ofHours(30))
        assertEquals(2_500 * rub, credited.availableKop)
        assertEquals(credited, WalletEngine.processDue(credited, exitAt + Duration.ofHours(31)), "exactly once")
    }

    @Test
    fun `24 hours are calendar time - time while the phone was off counts`() {
        // Failure on 12 Oct 18:00 Moscow; the return is due 13 Oct 18:00.
        val failedAt = t("2026-10-12T15:00:00Z")
        val reserved = ok(WalletEngine.reserve(funded(5_000), "s1", 5_000 * rub, failedAt))
        val pending = WalletEngine.settle(reserved, series("s1").copy(status = SeriesStatus.BROKEN, endedAt = failedAt), failedAt)
        assertEquals(t("2026-10-13T15:00:00Z"), pending.commitmentFor("s1")!!.returnDueAt)

        // Last seen by MAMA at 20:00 on 12 Oct, then the phone is off until 13 Oct 18:30.
        val lastSeen = t("2026-10-12T17:00:00Z")
        val anchorBeforeShutdown = ClockAnchor(lastSeen, elapsedMs = 9_000_000, bootId = "boot-1")
        val afterBoot = ClockGuard.trustedNow(
            anchorBeforeShutdown, lastSeen, wallNow = t("2026-10-13T15:30:00Z"), elapsedNowMs = 40_000, bootId = "boot-2",
        )
        // No pause and no extra hours: the return is credited right after power-on, once.
        val credited = WalletEngine.processDue(pending, afterBoot)
        assertEquals(2_500 * rub, credited.availableKop)
        assertEquals(credited, WalletEngine.processDue(credited, afterBoot + Duration.ofMinutes(1)))
    }
}
