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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    // A копилка credit of the chosen length starts the series free during the cooldown, bypassing the wait.
    @Test
    fun `a copilka credit starts the series free during cooldown and spends one`() {
        seed(
            Snapshot(
                series = null,
                entitlements = Entitlements(standardFailedAt = Instant.now().minus(Duration.ofDays(3)), standardCredits = mapOf(SeriesKind.THREE to 1)),
                wallet = Wallet(availableKop = 0), // no money: the credit alone starts it
            ),
        )
        Mama.reconcile(context)
        assertEquals(Mama.SeriesStart.Started, Mama.startSeries(context, SeriesKind.THREE, windowIn(4), mom, SessionMode.SLEEP))
        assertEquals(SeriesStatus.SCHEDULED, Mama.series(context)!!.status)
        assertEquals(0, Mama.standardCredits(context, SeriesKind.THREE))
        assertEquals(0L, Mama.wallet(context).availableKop)
        assertNotNull("the 30-day cooldown is untouched", Mama.entitlements(context).standardFailedAt)
    }

    // The rule: a copilka credit of the needed length is used in priority — the paid Restart is never
    // offered while such a credit exists; it is spent, the balance is untouched, the cooldown stays.
    @Test
    fun `a credit of the needed length is preferred over the paid Restart`() {
        seed(
            Snapshot(
                series = null,
                entitlements = Entitlements(standardFailedAt = Instant.now().minus(Duration.ofDays(3)), standardCredits = mapOf(SeriesKind.THREE to 1)),
                wallet = Wallet(availableKop = 44_000_00), // money is available, yet the credit is used, not charged
            ),
        )
        Mama.reconcile(context)
        val r = Mama.startSeries(context, SeriesKind.THREE, windowIn(4), mom, SessionMode.SLEEP)
        assertEquals(Mama.SeriesStart.Started, r)
        assertTrue("the paid Restart must not be offered when a credit of this length exists", r !is Mama.SeriesStart.Waiting)
        assertEquals(0, Mama.standardCredits(context, SeriesKind.THREE))
        assertEquals("the balance is untouched: the credit was used, not paid", 44_000_00L, Mama.wallet(context).availableKop)
    }

    // A credit for one length does not cover another: a different length still hits the Restart/wait gate.
    @Test
    fun `a credit for one length does not cover another`() {
        seed(
            Snapshot(
                series = null,
                entitlements = Entitlements(standardFailedAt = Instant.now().minus(Duration.ofDays(3)), standardCredits = mapOf(SeriesKind.THREE to 1)),
                wallet = Wallet(availableKop = 44_000_00),
            ),
        )
        Mama.reconcile(context)
        assertTrue(Mama.startSeries(context, SeriesKind.FIVE, windowIn(4), mom, SessionMode.SLEEP) is Mama.SeriesStart.Waiting)
        assertEquals("the 3-day credit is untouched", 1, Mama.standardCredits(context, SeriesKind.THREE))
    }

    // ---- "Начать заново" on the failure screen uses the same shared resolver ----

    /** A broken series on the failure screen, with the given copilka credits and balance. */
    private fun seedBroken(kind: SeriesKind, credits: Map<SeriesKind, Int>, balanceKop: Long = 44_000_00) {
        val failedAt = Instant.now().minus(Duration.ofDays(3))
        seed(
            Snapshot(
                series = Series(
                    "old", kind, windowIn(10), mom, SessionMode.SLEEP, startedAt = failedAt.minus(Duration.ofDays(1)),
                    status = SeriesStatus.BROKEN, endedAt = failedAt,
                ),
                entitlements = Entitlements(standardFailedAt = failedAt, standardCredits = credits),
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

    // 1. Broken series + a credit of that length: "Начать заново" offers the free copilka start, not paid Restart.
    @Test
    fun `the failure screen offers a free credit start when a credit of that length exists`() {
        seedBroken(SeriesKind.THREE, credits = mapOf(SeriesKind.THREE to 1))
        assertEquals(StartSource.FromCredit, Mama.startSource(context, SeriesKind.THREE))
        val texts = failureTexts().joinToString(" | ")
        assertTrue("expected a free copilka offer, got: $texts", texts.contains("из копилки"))
        assertFalse("the paid Restart must not be offered: $texts", texts.contains("Restart · 99"))
    }

    // 2. A credit of a different length does not cover this one: the failure screen offers the paid Restart.
    @Test
    fun `the failure screen offers paid Restart when the credit is a different length`() {
        seedBroken(SeriesKind.FIVE, credits = mapOf(SeriesKind.THREE to 1))
        assertTrue(Mama.startSource(context, SeriesKind.FIVE) is StartSource.PaidRestart)
        val texts = failureTexts().joinToString(" | ")
        assertTrue("expected the paid Restart offer at 179: $texts", texts.contains("Restart · ") && texts.contains("179"))
    }

    // 3. Starting from the credit (as "Начать заново" does) spends it once; a double tap cannot spend two.
    @Test
    fun `a credit start from the failure screen spends once, a double tap is a no-op`() {
        seedBroken(SeriesKind.THREE, credits = mapOf(SeriesKind.THREE to 1))
        // What finishRestart does for a FromCredit source: start the same length, reusing the broken contact/mode.
        assertEquals(Mama.SeriesStart.Started, Mama.startSeries(context, SeriesKind.THREE, windowIn(4), mom, SessionMode.SLEEP))
        assertEquals(Mama.SeriesStart.Busy, Mama.startSeries(context, SeriesKind.THREE, windowIn(4), mom, SessionMode.SLEEP))
        assertEquals(0, Mama.standardCredits(context, SeriesKind.THREE))
        assertEquals("the credit start is free", 44_000_00L, Mama.wallet(context).availableKop)
    }

    // 4. After a credit series is broken: no credit left and the cooldown re-anchors, so a paid Restart is offered again.
    @Test
    fun `after the credit series is broken, Restart is available again from the new срыв`() {
        // Build a credit series (ACTIVE) in core, break it, seed the result, reconcile.
        val lock = LockEngine()
        val flow = SeriesFlow(lock)
        val startedAt = Instant.now().minus(Duration.ofHours(3))
        val scheduled = (
            flow.schedule(
                Snapshot(entitlements = Entitlements(standardFailedAt = startedAt.minus(Duration.ofDays(2)), standardCredits = mapOf(SeriesKind.THREE to 1)), wallet = Wallet(availableKop = 0)),
                "c", "s", SeriesKind.THREE, LaunchTime.AutoNow(Duration.ofHours(8), zone), mom, SessionMode.SLEEP, null, startedAt, fromCredit = true,
            ) as SeriesFlow.Result.Done
            ).snapshot
        val active = flow.confirm(scheduled, startedAt.plusSeconds(1))!!
        val ended = lock.endByTrustedExit(active.session!!, ExitKind.END_SESSION, Instant.now().minusSeconds(5))
        seed(active.copy(session = ended))

        val r = Mama.reconcile(context)
        assertEquals(SeriesStatus.BROKEN, r.snapshot.series!!.status)
        assertEquals("the credit stays spent", 0, Mama.standardCredits(context, SeriesKind.THREE))
        // No credit now, cooldown re-anchored to this срыв: the paid Restart is the offer.
        assertTrue(Mama.startSource(context, SeriesKind.THREE) is StartSource.PaidRestart)
    }
}
