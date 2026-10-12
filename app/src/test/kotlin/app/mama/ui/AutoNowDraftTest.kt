package app.mama.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.mama.core.DailyWindow
import app.mama.core.Entitlements
import app.mama.core.LaunchTime
import app.mama.core.Series
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.SessionMode
import app.mama.core.Snapshot
import app.mama.core.StateCodec
import app.mama.core.TrustedContact
import app.mama.core.Wallet
import app.mama.platform.Mama
import app.mama.ui.MainActivity.StartMode
import app.mama.ui.MainActivity.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * AUTO_NOW: a new draft whose start the user did not touch starts at the
 * final confirmation, for the chosen length — a preview time that has gone
 * by while the wizard was open never pushes the series to tomorrow. A start
 * set by hand is a fixed window (MANUAL), never shifted.
 *
 * Robolectric grants no permissions, so a lock that starts here cannot be
 * protected: an AUTO_NOW series ends up in NEEDS_ATTENTION right away, which
 * is exactly what shows its first lock was due now and not tomorrow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoNowDraftTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val mom = TrustedContact("Мама", "+79001234567")
    private val eight = Duration.ofHours(8)

    private fun seed(snapshot: Snapshot) {
        val store = context.createDeviceProtectedStorageContext().getSharedPreferences("mama_state", Context.MODE_PRIVATE).edit().clear()
        StateCodec.encode(snapshot).forEach { (k, v) -> store.putString(k, v) }
        store.commit()
        Mama.reconcile(context)
    }

    private fun minute(): LocalTime = LocalTime.now(zone).withSecond(0).withNano(0)

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun find(v: View, text: String): View? = when {
        v is TextView && v.text.toString() == text -> v
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it), text) }
        else -> null
    }

    private fun content(a: MainActivity) = a.findViewById<ViewGroup>(android.R.id.content)!!

    /** The draft as if its time step had been opened [minutes] ago: the AUTO_NOW preview is that old. */
    private fun ageDraft(a: MainActivity, minutes: Long) {
        a.start = a.start.minusMinutes(minutes)
        a.end = a.end.minusMinutes(minutes)
        a.saveForm()
    }

    /** The first lock was due at the confirmation (between [from] and now), not at a later window. */
    private fun assertStartedAtConfirmation(from: Instant) {
        val r = Mama.reconcile(context)
        val plan = r.snapshot.session!!.plan
        assertFalse("first lock ${plan.start} not before the confirmation $from", plan.start.isBefore(from))
        assertFalse("first lock ${plan.start} not later than now", plan.start.isAfter(Instant.now()))
        assertEquals("the whole chosen length", eight, Duration.between(plan.start, plan.end))
        assertTrue(r.snapshot.series!!.status in setOf(SeriesStatus.ACTIVATING, SeriesStatus.NEEDS_ATTENTION, SeriesStatus.ACTIVE))
    }

    @Before
    fun clean() {
        seed(Snapshot(wallet = Wallet(availableKop = 1_000_00)))
        context.getSharedPreferences("mama_setup", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `1 - draft opened 5 minutes ago, time untouched, confirmed now - starts now for 8 hours, not tomorrow`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.choice = MainActivity.Choice.SEVEN
        a.beginDraft()
        a.go(Step.TIME)
        assertEquals(StartMode.AUTO_NOW, a.startMode)
        val shown = texts(content(a))
        assertTrue(shown.toString(), "Начать после подтверждения" in shown)
        assertTrue(shown.toString(), "Начало уточнится в момент запуска." in shown)

        ageDraft(a, 5) // e.g. 16:48 -> 00:48 on screen, confirmed at 16:53
        val stale = a.start
        val from = Mama.trustedNow(context)
        val before = minute()
        assertEquals(LaunchTime.AutoNow(eight, zone), a.launchTime())
        assertEquals(Mama.SeriesStart.Started, Mama.startSeries(context, SeriesKind.SEVEN, a.launchTime(), mom, SessionMode.SLEEP))
        val after = minute()

        val window = Mama.series(context)!!.window
        assertTrue("start ${window.start}, confirmed $before..$after", window.start == before || window.start == after)
        assertNotEquals("not the preview time", stale, window.start)
        assertEquals(window.start.plus(eight), window.end)
        assertStartedAtConfirmation(from)
    }

    @Test
    fun `the time step follows the clock while AUTO_NOW and keeps a changed end as the length`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.choice = MainActivity.Choice.SEVEN
        a.beginDraft()
        a.go(Step.TIME)
        ageDraft(a, 5)
        a.go(Step.TIME)
        val now = minute()
        assertTrue(a.start == now || a.start == now.minusMinutes(1))
        val end = a.start.plusHours(6)
        a.pickEnd(end)
        assertEquals(end, a.end)
        assertEquals(StartMode.AUTO_NOW, a.startMode)
        assertTrue(a.draftLength.toMinutes() in 359..360)
        assertEquals(LaunchTime.AutoNow(a.draftLength, zone), a.launchTime())
    }

    @Test
    fun `2 - start set by hand, confirmed later - the fixed window, no shift`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.choice = MainActivity.Choice.SEVEN
        a.beginDraft()
        a.go(Step.TIME)
        // "18:00 -> 02:00" by hand: a start some time ahead of now.
        val manualStart = minute().plusMinutes(70)
        val manualEnd = manualStart.plusHours(8)
        a.pickStart(manualStart)
        a.pickEnd(manualEnd)
        assertEquals(StartMode.MANUAL, a.startMode)
        a.go(Step.TIME)
        assertEquals("no refresh for a manual start", manualStart, a.start)
        val shown = texts(content(a))
        assertFalse(shown.toString(), "Начать после подтверждения" in shown)
        assertFalse(shown.toString(), "Начало уточнится в момент запуска." in shown)

        val manual = DailyWindow(manualStart, manualEnd, zone)
        assertEquals(LaunchTime.Fixed(manual), a.launchTime())
        assertEquals(Mama.SeriesStart.Started, Mama.startSeries(context, SeriesKind.SEVEN, a.launchTime(), mom, SessionMode.SLEEP))
        val r = Mama.reconcile(context)
        assertEquals(manual, r.snapshot.series!!.window)
        assertEquals(SeriesStatus.SCHEDULED, r.snapshot.series!!.status)
        assertEquals(manual.nextFullPlan(r.trustedNow).start, r.snapshot.session!!.plan.start)
    }

    @Test
    fun `a manual start already gone by - its next occurrence, never moved to now`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.choice = MainActivity.Choice.SEVEN
        a.beginDraft()
        a.go(Step.TIME)
        val past = minute().minusMinutes(5)
        a.pickStart(past)
        a.pickEnd(past.plusHours(8))
        assertEquals(Mama.SeriesStart.Started, Mama.startSeries(context, SeriesKind.SEVEN, a.launchTime(), mom, SessionMode.SLEEP))
        val r = Mama.reconcile(context)
        assertEquals(SeriesStatus.SCHEDULED, r.snapshot.series!!.status)
        assertEquals(past, r.snapshot.series!!.window.start)
        assertTrue("next day", r.snapshot.session!!.plan.start.isAfter(r.trustedNow.plus(Duration.ofHours(23))))
    }

    @Test
    fun `3 - Restart, AUTO_NOW left as is, paid 4 minutes later - starts now, not the failed 15_46 and not tomorrow`() {
        val failedAt = Instant.now().minus(Duration.ofDays(2))
        seed(
            Snapshot(
                series = Series(
                    "old", SeriesKind.SEVEN, DailyWindow(LocalTime.of(15, 46), LocalTime.of(7, 0), zone), mom, SessionMode.SLEEP,
                    startedAt = failedAt.minus(Duration.ofDays(3)), completedPeriods = 2, status = SeriesStatus.BROKEN, endedAt = failedAt,
                    actualStartedAt = failedAt.minus(Duration.ofDays(3)),
                ),
                entitlements = Entitlements(standardFailedAt = failedAt),
                wallet = Wallet(availableKop = 1_000_00),
            ),
        )
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        find(content(a), "Начать заново")!!.performClick()
        assertEquals(StartMode.AUTO_NOW, a.startMode)
        assertTrue(texts(content(a)).contains("Начало уточнится в момент запуска."))
        ageDraft(a, 4) // opened at 16:48, paid at 16:52
        val stale = a.start
        find(content(a), "Далее")!!.performClick()
        find(content(a), "Пропустить")!!.performClick()
        val from = Mama.trustedNow(context)
        val before = minute()
        find(ShadowDialog.getLatestDialog()!!.window!!.decorView, "Оплатить с баланса MAMA")!!.performClick()
        val after = minute()

        val restarted = Mama.series(context)!!
        assertEquals("old", restarted.restartOf)
        assertEquals(SeriesKind.SEVEN, restarted.kind)
        assertTrue("start ${restarted.window.start}, paid $before..$after", restarted.window.start == before || restarted.window.start == after)
        assertNotEquals(stale, restarted.window.start)
        assertNotEquals(LocalTime.of(15, 46), restarted.window.start)
        assertEquals(restarted.window.start.plus(eight), restarted.window.end)
        assertStartedAtConfirmation(from)
        assertEquals("held until ACTIVE, as before", 299_00L, Mama.wallet(context).restartReservedKop)
    }
}
