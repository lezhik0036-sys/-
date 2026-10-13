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
import app.mama.core.minuteOfDay
import app.mama.core.plusOnClock
import app.mama.platform.Mama
import app.mama.ui.MainActivity.StartMode
import app.mama.ui.MainActivity.Step
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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
    private val zone: TimeZone = TimeZone.currentSystemDefault()
    private val mom = TrustedContact("Мама", "+79001234567")
    private val eight = 8.hours

    private fun seed(snapshot: Snapshot) {
        val store = context.createDeviceProtectedStorageContext().getSharedPreferences("mama_state", Context.MODE_PRIVATE).edit().clear()
        StateCodec.encode(snapshot).forEach { (k, v) -> store.putString(k, v) }
        store.commit()
        Mama.reconcile(context)
    }

    private fun minute(): LocalTime = Clock.System.now().minuteOfDay(zone)

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
        a.start = a.start.plusOnClock(-minutes.minutes)
        a.end = a.end.plusOnClock(-minutes.minutes)
        a.saveForm()
    }

    /** The first lock was due at the confirmation (between [from] and now), not at a later window. */
    private fun assertStartedAtConfirmation(from: Instant) {
        val r = Mama.reconcile(context)
        val plan = r.snapshot.session!!.plan
        assertFalse("first lock ${plan.start} not before the confirmation $from", plan.start < from)
        assertFalse("first lock ${plan.start} not later than now", plan.start > Clock.System.now())
        assertEquals("the whole chosen length", eight, (plan.end - plan.start))
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
        assertEquals(window.start.plusOnClock(eight), window.end)
        assertStartedAtConfirmation(from)
        // Not ACTIVE (no protection here), so the AUTO_NOW time is not frozen yet.
        if (Mama.series(context)!!.status != SeriesStatus.ACTIVE) assertTrue(Mama.series(context)!!.autoNow)
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
        assertTrue(a.start == now || a.start == now.plusOnClock(-1.minutes))
        val end = a.start.plusOnClock(6.hours)
        a.pickEnd(end)
        assertEquals(end, a.end)
        assertEquals(StartMode.AUTO_NOW, a.startMode)
        assertTrue(a.draftLength.inWholeMinutes in 359..360)
        assertEquals(LaunchTime.AutoNow(a.draftLength, zone), a.launchTime())
    }

    @Test
    fun `2 - start set by hand, confirmed later - the fixed window, no shift`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.choice = MainActivity.Choice.SEVEN
        a.beginDraft()
        a.go(Step.TIME)
        // "18:00 -> 02:00" by hand: a start some time ahead of now.
        val manualStart = minute().plusOnClock(70.minutes)
        val manualEnd = manualStart.plusOnClock(8.hours)
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
        val past = minute().plusOnClock(-5.minutes)
        a.pickStart(past)
        a.pickEnd(past.plusOnClock(8.hours))
        assertEquals(Mama.SeriesStart.Started, Mama.startSeries(context, SeriesKind.SEVEN, a.launchTime(), mom, SessionMode.SLEEP))
        val r = Mama.reconcile(context)
        assertEquals(SeriesStatus.SCHEDULED, r.snapshot.series!!.status)
        assertEquals(past, r.snapshot.series!!.window.start)
        assertTrue("next day", r.snapshot.session!!.plan.start > r.trustedNow + 23.hours)
    }

    @Test
    fun `3 - Restart, AUTO_NOW left as is, paid 4 minutes later - starts now, not the failed 15_46 and not tomorrow`() {
        val failedAt = Clock.System.now().minus(2.days)
        seed(
            Snapshot(
                series = Series(
                    "old", SeriesKind.SEVEN, DailyWindow(LocalTime(15, 46), LocalTime(7, 0), zone), mom, SessionMode.SLEEP,
                    startedAt = failedAt.minus(3.days), completedPeriods = 2, status = SeriesStatus.BROKEN, endedAt = failedAt,
                    actualStartedAt = failedAt.minus(3.days),
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
        assertNotEquals(LocalTime(15, 46), restarted.window.start)
        assertEquals(restarted.window.start.plusOnClock(eight), restarted.window.end)
        assertStartedAtConfirmation(from)
        assertEquals("held until ACTIVE, as before", 299_00L, Mama.wallet(context).restartReservedKop)
    }

    @Test
    fun `AUTO_NOW waiting in NEEDS_ATTENTION shows the time a new launch would take, MANUAL keeps its own`() {
        val missedAt = Clock.System.now().minus(20.minutes)
        val old = minute().plusOnClock(-2.hours)
        fun waiting(autoNow: Boolean) = seed(
            Snapshot(
                series = Series(
                    "s1", SeriesKind.SEVEN, DailyWindow(old, old.plusOnClock(8.hours), zone), mom, SessionMode.SLEEP, startedAt = missedAt,
                    status = SeriesStatus.NEEDS_ATTENTION, protectionMissedAt = missedAt, autoNow = autoNow,
                ),
            ),
        )
        waiting(autoNow = true)
        val before = minute()
        val shown = texts(content(Robolectric.buildActivity(MainActivity::class.java).setup().get()))
        val after = minute()
        assertTrue(shown.toString(), Texts.hhmm(before) in shown || Texts.hhmm(after) in shown)
        assertFalse(shown.toString(), Texts.hhmm(old) in shown)

        waiting(autoNow = false)
        val manual = texts(content(Robolectric.buildActivity(MainActivity::class.java).setup().get()))
        assertTrue(manual.toString(), Texts.hhmm(old) in manual)
    }
}
