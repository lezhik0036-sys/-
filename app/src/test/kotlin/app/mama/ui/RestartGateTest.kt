package app.mama.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.mama.core.DailyWindow
import app.mama.core.Entitlements
import app.mama.core.ExitKind
import app.mama.core.GrantSource
import app.mama.core.LaunchTime
import app.mama.core.LockEngine
import app.mama.core.Series
import app.mama.core.SeriesFlow
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.SessionMode
import app.mama.core.Snapshot
import app.mama.core.StandardRules
import app.mama.core.StartSource
import app.mama.core.StateCodec
import app.mama.core.TrustedContact
import app.mama.core.Wallet
import app.mama.platform.Mama
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The series-start gate while the 30-day free-start cooldown is running (after
 * a failure that was already dismissed with "Вернуться на главную", so the
 * broken series is gone but the wait remains). The regression this guards:
 * the cooldown used to be an absolute block — the normal setup wizard returned
 * a dead-end "wait 30 days" with no way to pay for a Restart. Now the gate
 * offers a paid Restart, and the cooldown is never reset by paying.
 *
 * Exercised through the real platform entry point (storage, reconcile). A
 * Fixed daily window a few hours ahead keeps the series SCHEDULED and the
 * phone free, so the wallet and the cooldown can be inspected directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestartGateTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val mom = TrustedContact("Мама", "+79001234567")

    private fun windowIn(hoursAhead: Long): DailyWindow {
        val start = LocalTime.now(zone).plusHours(hoursAhead).withSecond(0).withNano(0)
        return DailyWindow(start, start.plusHours(8), zone)
    }

    private fun seed(snapshot: Snapshot) {
        val store = context.createDeviceProtectedStorageContext().getSharedPreferences("mama_state", Context.MODE_PRIVATE).edit().clear()
        StateCodec.encode(snapshot).forEach { (k, v) -> store.putString(k, v) }
        store.commit()
    }

    /** The free start on cooldown: the broken series already dismissed (null), [balanceKop] available. */
    private fun seedCooldown(balanceKop: Long = 44_000_00, daysAgo: Long = 3) {
        seed(
            Snapshot(
                series = null,
                entitlements = Entitlements(standardFailedAt = Instant.now().minus(Duration.ofDays(daysAgo))),
                wallet = Wallet(availableKop = balanceKop),
            ),
        )
        Mama.reconcile(context)
    }

    private fun startPaid(kind: SeriesKind, source: GrantSource = GrantSource.INTERNAL_BALANCE) =
        Mama.startSeriesWithRestart(context, kind, windowIn(4), mom, SessionMode.SLEEP, source)

    @Before
    fun cleanState() {
        seed(Snapshot(wallet = Wallet(availableKop = 44_000_00)))
        Mama.reconcile(context)
    }

    // 1. Cooldown + balance 44 000: the plain start reports Waiting, so the UI offers a Restart.
    @Test
    fun `during cooldown the start gate reports Waiting with the days left, not a hard block`() {
        seedCooldown()
        val r = Mama.startSeries(context, SeriesKind.THREE, windowIn(4), mom, SessionMode.SLEEP)
        assertTrue("expected Waiting, got $r", r is Mama.SeriesStart.Waiting)
        assertTrue((r as Mama.SeriesStart.Waiting).days in 27..28)
        // The mere gate check never touched the balance or created a series.
        assertEquals(44_000_00L, Mama.wallet(context).availableKop)
        assertNull(Mama.series(context))
    }

    // 2. Confirming the paid Restart debits 99 and starts a new 3-day series at once.
    @Test
    fun `paying the Restart from the balance starts the series and debits 99`() {
        seedCooldown()
        assertEquals(Mama.SeriesStart.Started, startPaid(SeriesKind.THREE))
        assertEquals(SeriesStatus.SCHEDULED, Mama.series(context)!!.status)
        assertEquals(SeriesKind.THREE, Mama.series(context)!!.kind)
        assertEquals(44_000_00L - 99_00L, Mama.wallet(context).availableKop)
        assertEquals(99_00L, Mama.wallet(context).restartReservedKop)
    }

    // 3. Not enough balance: nothing starts and the balance is untouched.
    @Test
    fun `not enough balance does not start the series and does not touch the balance`() {
        seedCooldown(balanceKop = 50_00)
        val r = startPaid(SeriesKind.THREE)
        assertTrue("expected NotEnoughBalance, got $r", r is Mama.SeriesStart.NotEnoughBalance)
        assertNull(Mama.series(context))
        assertEquals(50_00L, Mama.wallet(context).availableKop)
    }

    // 4. Choosing to wait (never calling the paid start) leaves everything as it was.
    @Test
    fun `choosing to wait never touches the balance`() {
        seedCooldown()
        assertTrue(Mama.startSeries(context, SeriesKind.THREE, windowIn(4), mom, SessionMode.SLEEP) is Mama.SeriesStart.Waiting)
        // The user backs out of the offer: no second call, so no charge and no series.
        assertEquals(44_000_00L, Mama.wallet(context).availableKop)
        assertNull(Mama.series(context))
        assertEquals(0L, Mama.wallet(context).restartReservedKop)
    }

    // 5. A double tap charges once: the first start makes the second Busy.
    @Test
    fun `a double tap charges once and starts one series`() {
        seedCooldown()
        assertEquals(Mama.SeriesStart.Started, startPaid(SeriesKind.THREE))
        assertEquals(Mama.SeriesStart.Busy, startPaid(SeriesKind.THREE))
        assertEquals(44_000_00L - 99_00L, Mama.wallet(context).availableKop)
        assertEquals(99_00L, Mama.wallet(context).restartReservedKop)
        assertEquals(1, Mama.wallet(context).restartHolds.size)
    }

    // 6. The 30-day free-start cooldown stays after a paid Restart: paying bought only this start.
    @Test
    fun `the 30-day cooldown stays after a paid restart`() {
        seedCooldown()
        val before = Mama.entitlements(context).standardFailedAt
        assertEquals(Mama.SeriesStart.Started, startPaid(SeriesKind.THREE))
        assertEquals("the failure date is unchanged", before, Mama.entitlements(context).standardFailedAt)
        assertTrue(!StandardRules.canStartFree(Mama.entitlements(context), Mama.trustedNow(context)))
    }

    // 7 & 8. The chosen length sets the price: 5 -> 179, 7 -> 299.
    @Test
    fun `a 5-day start debits 179 and a 7-day start debits 299`() {
        seedCooldown()
        assertEquals(Mama.SeriesStart.Started, startPaid(SeriesKind.FIVE))
        assertEquals(179_00L, Mama.wallet(context).restartReservedKop)

        seedCooldown()
        assertEquals(Mama.SeriesStart.Started, startPaid(SeriesKind.SEVEN))
        assertEquals(299_00L, Mama.wallet(context).restartReservedKop)
    }

    // 9. A user who never broke a series starts free and is never sent to a paid Restart.
    @Test
    fun `a user eligible for a free start is never sent to Restart`() {
        // No failure on record: the clean state from the @Before, balance 44 000.
        assertEquals(Mama.SeriesStart.Started, Mama.startSeries(context, SeriesKind.THREE, windowIn(4), mom, SessionMode.SLEEP))
        assertEquals("a free start costs nothing", 44_000_00L, Mama.wallet(context).availableKop)

        // And even a direct paid-start call refuses when a free start is available: no charge.
        seed(Snapshot(wallet = Wallet(availableKop = 44_000_00)))
        Mama.reconcile(context)
        assertEquals(Mama.SeriesStart.Rejected, startPaid(SeriesKind.THREE))
        assertEquals(44_000_00L, Mama.wallet(context).availableKop)
        assertNull(Mama.series(context))
    }

    // Each new срыв re-anchors the 30-day wait to itself, through the real reconcile path:
    // a paid Restart series that was started during an OLD cooldown and is now Trusted-Exited
    // must move the free-start clock to THIS break, not leave it on the earlier failure.
    @Test
    fun `a new failure re-anchors the cooldown to itself, not the earlier one`() {
        val lock = LockEngine()
        val flow = SeriesFlow(lock)
        val oldFailure = Instant.now().minus(Duration.ofDays(10))
        val startedAt = Instant.now().minus(Duration.ofHours(3))
        // A paid Restart (3 дня) begun during the old cooldown, brought ACTIVE.
        val scheduled = (
            flow.startPaid(
                Snapshot(entitlements = Entitlements(standardFailedAt = oldFailure), wallet = Wallet(availableKop = 44_000_00)),
                GrantSource.INTERNAL_BALANCE, SeriesKind.THREE, LaunchTime.AutoNow(Duration.ofHours(8), zone), mom, SessionMode.SLEEP,
                "paid", "sess", null, startedAt,
            ) as SeriesFlow.Result.Done
            ).snapshot
        val active = flow.confirm(scheduled, startedAt.plusSeconds(1))!!
        // Trusted Exit a moment ago: the series fails.
        val ended = lock.endByTrustedExit(active.session!!, ExitKind.END_SESSION, Instant.now().minusSeconds(5))
        seed(active.copy(session = ended))

        val r = Mama.reconcile(context)
        assertEquals(SeriesStatus.BROKEN, r.snapshot.series!!.status)
        assertNotEquals("the clock no longer sits on the old failure", oldFailure, r.snapshot.entitlements.standardFailedAt)
        assertTrue(StandardRules.daysUntilFreeStart(r.snapshot.entitlements, Mama.trustedNow(context)) in 29..30)
    }

    // ---- "Начать заново" on the failure screen uses the same shared money resolver ----

    /** A broken series on the failure screen, with the given MAMA balance. */
    private fun seedBroken(kind: SeriesKind, balanceKop: Long = 44_000_00) {
        val failedAt = Instant.now().minus(Duration.ofDays(3))
        seed(
            Snapshot(
                series = Series(
                    "old", kind, windowIn(10), mom, SessionMode.SLEEP, startedAt = failedAt.minus(Duration.ofDays(1)),
                    status = SeriesStatus.BROKEN, endedAt = failedAt,
                ),
                entitlements = Entitlements(standardFailedAt = failedAt),
                wallet = Wallet(availableKop = balanceKop),
            ),
        )
        Mama.reconcile(context)
    }

    private fun failureTexts(): List<String> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        return texts(activity.findViewById<ViewGroup>(android.R.id.content)!!)
    }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    // The failure screen uses the shared resolver: during the cooldown it offers the paid Restart at the length's price.
    @Test
    fun `the failure screen offers the paid Restart during the cooldown`() {
        seedBroken(SeriesKind.FIVE)
        assertTrue(Mama.startSource(context, SeriesKind.FIVE) is StartSource.PaidRestart)
        val texts = failureTexts().joinToString(" | ")
        assertTrue("expected the paid Restart offer at 179: $texts", texts.contains("Restart · ") && texts.contains("179"))
    }

    // Short balance: the resolver reports the paid Restart as not yet affordable, and the card still shows its price.
    @Test
    fun `the failure screen reports insufficient funds when the balance is short`() {
        seedBroken(SeriesKind.SEVEN, balanceKop = 50_00)
        assertTrue(Mama.startSource(context, SeriesKind.SEVEN) is StartSource.PaidRestartInsufficientFunds)
        val texts = failureTexts().joinToString(" | ")
        assertTrue("expected the Restart price 299 shown: $texts", texts.contains("299"))
    }

    // Rehabilitation through the real reconcile: a completed series (e.g. a paid Restart, balance already 43 901)
    // clears the cooldown, so the next standard series starts free without any charge.
    @Test
    fun `a completed series clears the cooldown and the next start is free`() {
        val failedAt = Instant.now().minus(Duration.ofDays(3))
        val completed = Series(
            "old", SeriesKind.THREE, windowIn(10), mom, SessionMode.SLEEP, startedAt = failedAt,
            completedPeriods = 3, status = SeriesStatus.COMPLETED, endedAt = Instant.now().minusSeconds(5),
        )
        seed(Snapshot(series = completed, entitlements = Entitlements(standardFailedAt = failedAt), wallet = Wallet(availableKop = 43_901_00)))

        val r = Mama.reconcile(context)
        assertNull("success rehabilitates — the cooldown is cleared", r.snapshot.entitlements.standardFailedAt)
        assertEquals(StartSource.Free, Mama.startSource(context, SeriesKind.THREE))
        assertEquals(Mama.SeriesStart.Started, Mama.startSeries(context, SeriesKind.THREE, windowIn(4), mom, SessionMode.SLEEP))
        assertEquals("the free start charges nothing", 43_901_00L, Mama.wallet(context).availableKop)
    }
}
