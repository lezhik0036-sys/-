package app.mama.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.mama.core.DailyWindow
import app.mama.core.Entitlements
import app.mama.core.GrantSource
import app.mama.core.LockEngine
import app.mama.core.LockState
import app.mama.core.Series
import app.mama.core.SeriesFlow
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.SessionMode
import app.mama.core.Snapshot
import app.mama.core.StateCodec
import app.mama.core.TrustedContact
import app.mama.core.Wallet
import app.mama.platform.Mama
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * "Начать серию" schedules a series; it becomes binding only when its first
 * lock runs with strict protection ready. Exercised through the real platform
 * entry point (storage, alarms, reconcile, permission checks). Robolectric
 * grants no permissions, so a lock that starts here cannot be protected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledSeriesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val mom = TrustedContact("Мама", "+79001234567")
    private val sister = TrustedContact("Сестра", "+79007654321")

    /** A daily 8-hour window starting about [hoursAhead] hours from now. */
    private fun windowIn(hoursAhead: Long): DailyWindow {
        val start = LocalTime.now(zone).plusHours(hoursAhead).withSecond(0).withNano(0)
        return DailyWindow(start, start.plusHours(8), zone)
    }

    private fun seed(snapshot: Snapshot) {
        val store = context.createDeviceProtectedStorageContext().getSharedPreferences("mama_state", Context.MODE_PRIVATE).edit().clear()
        StateCodec.encode(snapshot).forEach { (k, v) -> store.putString(k, v) }
        store.commit()
    }

    @Before
    fun cleanState() {
        // Test credits: 10 000 on the internal balance.
        seed(Snapshot(wallet = Wallet(availableKop = 10_000_00)))
        Mama.reconcile(context)
    }

    @Test
    fun `scheduled series can be changed and cancelled with no consequences`() {
        assertEquals(Mama.SeriesStart.Started, Mama.startSeries(context, SeriesKind.SEVEN, windowIn(10), mom, SessionMode.SLEEP, 5_000_00))
        assertEquals(SeriesStatus.SCHEDULED, Mama.series(context)!!.status)
        assertTrue(Mama.state(context) is LockState.Waiting)
        assertEquals(5_000_00L, Mama.wallet(context).availableKop)

        // "Изменить условия": another contact and a smaller "Цена слова"; length and time stay.
        val before = Mama.series(context)!!
        val firstLock = Mama.session(context)!!.plan
        assertEquals(Mama.SeriesEdit.Saved, Mama.editScheduledSeries(context, sister, 1_000_00))
        assertEquals(sister, Mama.series(context)!!.contact)
        assertEquals(sister, Mama.session(context)!!.contact)
        assertEquals(SeriesStatus.SCHEDULED, Mama.series(context)!!.status)
        assertEquals(before.window, Mama.series(context)!!.window)
        assertEquals(before.kind, Mama.series(context)!!.kind)
        assertEquals(firstLock, Mama.session(context)!!.plan)
        assertEquals(9_000_00L, Mama.wallet(context).availableKop)
        assertEquals(1_000_00L, Mama.wallet(context).reservedKop)

        // "Отменить запуск".
        assertTrue(Mama.cancelScheduledSeries(context))
        assertNull(Mama.series(context))
        assertEquals(LockState.Free, Mama.state(context))
        assertNull("no 30-day wait", Mama.entitlements(context).standardFailedAt)
        assertEquals(10_000_00L, Mama.wallet(context).availableKop)
        assertEquals(0L, Mama.wallet(context).reservedKop)
        assertNull(Mama.wallet(context).nextReturnDue)
        // A new free series can start right away.
        assertEquals(Mama.SeriesStart.Started, Mama.startSeries(context, SeriesKind.THREE, windowIn(10), mom, SessionMode.SLEEP))
    }

    @Test
    fun `a series confirmed inside its window waits for the next full window`() {
        // The window started an hour ago: no shortened first period, the phone stays free.
        assertEquals(Mama.SeriesStart.Started, Mama.startSeries(context, SeriesKind.SEVEN, windowIn(-1), mom, SessionMode.SLEEP))
        assertEquals(SeriesStatus.SCHEDULED, Mama.series(context)!!.status)
        val waiting = Mama.state(context) as LockState.Waiting
        val untilStart = Duration.between(Instant.now(), waiting.startsAt)
        assertTrue("first lock about 23 hours ahead: $untilStart", untilStart > Duration.ofHours(22) && untilStart < Duration.ofHours(24))
    }

    @Test
    fun `start time reached without strict protection - needs attention, nothing charged, nothing failed`() {
        // A series confirmed two hours ago whose first lock started a minute ago.
        val now = Instant.now()
        val start = now.minusSeconds(60).atZone(zone).toLocalTime().withSecond(0).withNano(0)
        val scheduled = SeriesFlow(LockEngine()).schedule(
            Snapshot(wallet = Wallet(availableKop = 10_000_00)), "s1", "n1", SeriesKind.SEVEN,
            DailyWindow(start, start.plusHours(8), zone), mom, SessionMode.SLEEP, 5_000_00, now.minus(Duration.ofHours(2)),
        ) as SeriesFlow.Result.Done
        seed(scheduled.snapshot)

        val r = Mama.reconcile(context)
        assertEquals(SeriesStatus.NEEDS_ATTENTION, r.snapshot.series!!.status)
        assertEquals("the unconfirmed lock ended", LockState.Free, r.state)
        assertNull(r.snapshot.series!!.actualStartedAt)
        assertNull("no 30-day wait", r.snapshot.entitlements.standardFailedAt)
        assertEquals(5_000_00L, r.snapshot.wallet.reservedKop)
        assertEquals(5_000_00L, r.snapshot.wallet.availableKop)
        // No new launch until the protection is fixed and the user confirms.
        assertTrue(Mama.relaunchSeries(context) is Mama.SeriesEdit.ProtectionMissing)
        // Changing the contact is allowed but is not a new launch.
        assertEquals(Mama.SeriesEdit.Saved, Mama.editScheduledSeries(context, sister, 5_000_00))
        assertEquals(SeriesStatus.NEEDS_ATTENTION, Mama.series(context)!!.status)
        assertEquals(sister, Mama.series(context)!!.contact)
        assertEquals(LockState.Free, Mama.sync(context))

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val texts = texts(activity.findViewById<ViewGroup>(android.R.id.content)!!)
        for (label in listOf("Серия не началась", "MAMA не смогла включить защиту.", "Исправить защиту", "Изменить условия", "Отменить запуск")) {
            assertTrue("$label in $texts", label in texts)
        }

        // "Отменить запуск": everything back.
        assertTrue(Mama.cancelScheduledSeries(context))
        assertNull(Mama.series(context))
        assertEquals(10_000_00L, Mama.wallet(context).availableKop)
        assertNull(Mama.entitlements(context).standardFailedAt)
    }

    @Test
    fun `restart from the balance is held, and a cancel before the first lock gives it back and restores the failure`() {
        val failedAt = Instant.now().minus(Duration.ofDays(2))
        val failed = Series(
            "old", SeriesKind.SEVEN, windowIn(10), mom, SessionMode.SLEEP, startedAt = failedAt.minus(Duration.ofDays(3)),
            completedPeriods = 2, status = SeriesStatus.BROKEN, endedAt = failedAt,
        )
        seed(Snapshot(series = failed, entitlements = Entitlements(standardFailedAt = failedAt), wallet = Wallet(availableKop = 10_000_00)))

        val chosen = windowIn(3)
        assertEquals(Mama.SeriesStart.Started, Mama.restartSeries(context, GrantSource.INTERNAL_BALANCE, chosen, 1_000_00))
        val restarted = Mama.series(context)!!
        assertEquals(SeriesStatus.SCHEDULED, restarted.status)
        assertEquals("old", restarted.restartOf)
        assertEquals(10_000_00L - 299_00L - 1_000_00L, Mama.wallet(context).availableKop)
        assertEquals(299_00L, Mama.wallet(context).restartReservedKop)
        assertEquals(failedAt, Mama.entitlements(context).standardFailedAt)
        // A Restart keeps only the length of the failed series; the time is the newly chosen one.
        assertEquals(SeriesKind.SEVEN, restarted.kind)
        assertEquals(chosen, restarted.window)

        assertTrue(Mama.cancelScheduledSeries(context))
        assertEquals(10_000_00L, Mama.wallet(context).availableKop)
        assertEquals(0L, Mama.wallet(context).restartReservedKop)
        assertEquals(0L, Mama.wallet(context).reservedKop)
        assertEquals("the original failure date, no new wait", failedAt, Mama.entitlements(context).standardFailedAt)
        assertEquals("old", Mama.series(context)!!.id)
        assertEquals(SeriesStatus.BROKEN, Mama.series(context)!!.status)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue("Этот путь продолжается" in texts(activity.findViewById<ViewGroup>(android.R.id.content)!!))
    }

    @Test
    fun `scheduled dashboard offers changing and cancelling the launch`() {
        Mama.startSeries(context, SeriesKind.SEVEN, windowIn(10), mom, SessionMode.SLEEP)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val texts = texts(activity.findViewById<ViewGroup>(android.R.id.content)!!)
        assertTrue(texts.toString(), "Серия запланирована" in texts)
        assertTrue(texts.toString(), "Изменить условия" in texts)
        assertTrue(texts.toString(), "Отменить запуск" in texts)
        assertFalse(texts.toString(), texts.any { it.contains("Прервать") })
    }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }
}
