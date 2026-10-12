package app.mama.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.mama.core.CommitmentState
import app.mama.core.DailyWindow
import app.mama.core.LockState
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.time.ZoneId

/**
 * "Начать серию" schedules a series; it becomes binding only when its first
 * lock runs with protection confirmed. Exercised through the real platform
 * entry point (storage, alarms, reconcile), not only the pure core.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledSeriesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val mom = TrustedContact("Мама", "+79001234567")
    private val sister = TrustedContact("Сестра", "+79007654321")

    /** A daily 8-hour window starting about [hoursAhead] hours from now (negative: already running). */
    private fun windowIn(hoursAhead: Long): DailyWindow {
        val start = LocalTime.now(zone).plusHours(hoursAhead).withSecond(0).withNano(0)
        return DailyWindow(start, start.plusHours(8), zone)
    }

    @Before
    fun cleanState() {
        // Test credits: 10 000 on the internal balance.
        val store = context.createDeviceProtectedStorageContext().getSharedPreferences("mama_state", Context.MODE_PRIVATE).edit().clear()
        StateCodec.encode(Snapshot(wallet = Wallet(availableKop = 10_000_00))).forEach { (k, v) -> store.putString(k, v) }
        store.commit()
        Mama.reconcile(context)
    }

    @Test
    fun `scheduled series can be changed and cancelled with no consequences`() {
        assertEquals(Mama.SeriesStart.Started, Mama.startSeries(context, SeriesKind.SEVEN, windowIn(10), mom, SessionMode.SLEEP, 5_000_00))
        assertEquals(SeriesStatus.SCHEDULED, Mama.series(context)!!.status)
        assertTrue(Mama.state(context) is LockState.Waiting)
        assertEquals(5_000_00L, Mama.wallet(context).availableKop)

        // "Изменить условия": another contact, another time, a smaller "Цена слова".
        assertEquals(Mama.SeriesEdit.Saved, Mama.editScheduledSeries(context, SeriesKind.SEVEN, windowIn(9), sister, SessionMode.SLEEP, 1_000_00))
        assertEquals(sister, Mama.series(context)!!.contact)
        assertEquals(sister, Mama.session(context)!!.contact)
        assertEquals(SeriesStatus.SCHEDULED, Mama.series(context)!!.status)
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
    fun `confirmed protection makes the series active and fixes everything`() {
        assertEquals(Mama.SeriesStart.Started, Mama.startSeries(context, SeriesKind.SEVEN, windowIn(-1), mom, SessionMode.SLEEP, 5_000_00))
        assertEquals(SeriesStatus.ACTIVATING, Mama.series(context)!!.status)
        assertTrue(Mama.confirmProtection(context))
        val series = Mama.series(context)!!
        assertEquals(SeriesStatus.ACTIVE, series.status)
        assertNotNull(series.actualStartedAt)
        assertEquals(CommitmentState.RESERVED, Mama.wallet(context).commitmentFor(series.id)!!.state)

        assertEquals(Mama.SeriesEdit.NotAllowed, Mama.editScheduledSeries(context, SeriesKind.SEVEN, series.window, sister, SessionMode.SLEEP, null))
        assertFalse(Mama.cancelScheduledSeries(context))
        assertFalse(Mama.cancelBeforeStart(context))
        assertEquals(mom, Mama.series(context)!!.contact)
        assertEquals(5_000_00L, Mama.wallet(context).reservedKop)
    }

    @Test
    fun `lock started but protection never confirmed - cancelling has no consequences`() {
        Mama.startSeries(context, SeriesKind.FIVE, windowIn(-1), mom, SessionMode.SLEEP, 2_000_00)
        assertEquals(SeriesStatus.ACTIVATING, Mama.series(context)!!.status)
        assertTrue(Mama.cancelScheduledSeries(context))
        assertNull(Mama.series(context))
        assertEquals(LockState.Free, Mama.state(context))
        assertNull(Mama.entitlements(context).standardFailedAt)
        assertEquals(10_000_00L, Mama.wallet(context).availableKop)
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
