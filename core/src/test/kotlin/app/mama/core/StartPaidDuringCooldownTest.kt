package app.mama.core

import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SeriesFlow.startPaid]: starting a brand new series while the 30-day free
 * start is still on cooldown, by paying its length's Restart price. Unlike
 * [SeriesFlow.restart] it is not tied to a failed series, so it never lifts
 * the 30-day wait — the payment buys this one start, nothing more.
 */
class StartPaidDuringCooldownTest {
    private val lock = engine()
    private val flow = SeriesFlow(lock)
    private val window = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), MSK)
    private val failedAt = t("2026-10-10T21:00:00Z")
    private val now = t("2026-10-12T10:00:00Z") // 13:00 Moscow, well inside the 30-day wait
    private val at23 = t("2026-10-12T20:00:00Z")
    private val rub = 100L

    /** The 30-day wait running, the broken series already dismissed (series = null), 44 000 available. */
    private fun duringCooldown() = Snapshot(
        series = null,
        entitlements = Entitlements(standardFailedAt = failedAt),
        wallet = Wallet(availableKop = 44_000 * rub),
    )

    private fun done(r: SeriesFlow.Result) = assertIs<SeriesFlow.Result.Done>(r).snapshot

    private fun startPaid(kind: SeriesKind, s: Snapshot = duringCooldown(), commitmentKop: Long? = null) =
        flow.startPaid(s, GrantSource.INTERNAL_BALANCE, kind, LaunchTime.Fixed(window), MOM, SessionMode.SLEEP, "new", "n1", commitmentKop, now)

    @Test
    fun `a 3-day paid start holds 99 and keeps the 30-day wait running`() {
        val r = done(startPaid(SeriesKind.THREE))
        assertEquals(SeriesStatus.SCHEDULED, r.series!!.status)
        assertEquals(SeriesKind.THREE, r.series!!.kind)
        assertNull(r.series!!.restartOf, "not a continuation of any failed series")
        assertNull(r.restartedFrom)
        assertEquals(44_000 * rub - 99 * rub, r.wallet.availableKop)
        assertEquals(99 * rub, r.wallet.restartReservedKop, "the Restart price is held, like any Restart")
        assertEquals(1, r.wallet.entries.count { it.type == LedgerType.RESTART_RESERVED_TEST }, "recorded in the balance history")
        // The 30-day free-start wait is untouched: the payment did not reset it.
        assertEquals(failedAt, r.entitlements.standardFailedAt)
    }

    @Test
    fun `the cooldown survives the series becoming ACTIVE - unlike a restart, it is never lifted`() {
        val scheduled = done(startPaid(SeriesKind.THREE))
        val activating = flow.fold(scheduled.copy(session = lock.advance(scheduled.session!!, at23)), "n2", at23)
        assertEquals(SeriesStatus.ACTIVATING, activating.series!!.status)
        assertEquals(failedAt, activating.entitlements.standardFailedAt)

        val active = flow.confirm(activating, at23.plusSeconds(1))!!
        assertEquals(SeriesStatus.ACTIVE, active.series!!.status)
        // The hold is consumed at the commitment point, exactly like a Restart.
        assertEquals(0, active.wallet.restartReservedKop)
        assertEquals(99 * rub, active.wallet.entries.single { it.type == LedgerType.RESTART_PURCHASE_TEST }.amountKop)
        // But the 30-day wait stays: this paid start never lifted it.
        assertEquals(failedAt, active.entitlements.standardFailedAt)
        assertTrue(!StandardRules.canStartFree(active.entitlements, at23.plusSeconds(1)))
    }

    @Test
    fun `5-day start holds 179, 7-day start holds 299`() {
        assertEquals(179 * rub, done(startPaid(SeriesKind.FIVE)).wallet.restartReservedKop)
        assertEquals(299 * rub, done(startPaid(SeriesKind.SEVEN)).wallet.restartReservedKop)
    }

    @Test
    fun `not enough balance leaves everything untouched`() {
        val poor = duringCooldown().copy(wallet = Wallet(availableKop = 50 * rub))
        val r = flow.startPaid(poor, GrantSource.INTERNAL_BALANCE, SeriesKind.THREE, LaunchTime.Fixed(window), MOM, SessionMode.SLEEP, "new", "n1", null, now)
        assertEquals(50 * rub, assertIs<SeriesFlow.Result.NotEnoughBalance>(r).availableKop)
    }

    @Test
    fun `a test grant starts the series without touching the balance, and still keeps the wait`() {
        val r = done(
            flow.startPaid(duringCooldown(), GrantSource.TEST_NO_PAYMENT, SeriesKind.THREE, LaunchTime.Fixed(window), MOM, SessionMode.SLEEP, "new", "n1", null, now),
        )
        assertEquals(SeriesStatus.SCHEDULED, r.series!!.status)
        assertEquals(44_000 * rub, r.wallet.availableKop, "no charge for a test grant")
        assertEquals(0, r.wallet.restartReservedKop)
        assertEquals(failedAt, r.entitlements.standardFailedAt)
    }

    @Test
    fun `codec survives a paid start`() {
        val r = done(startPaid(SeriesKind.THREE, commitmentKop = 1_000 * rub))
        assertEquals(r, StateCodec.decode(StateCodec.encode(r)))
    }
}
