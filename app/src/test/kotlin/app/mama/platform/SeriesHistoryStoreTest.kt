package app.mama.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.mama.core.CorePolicy
import app.mama.core.DailyWindow
import app.mama.core.Entitlements
import app.mama.core.ExitKind
import app.mama.core.FinishReason
import app.mama.core.FlexPackage
import app.mama.core.GrantSource
import app.mama.core.HistoryResult
import app.mama.core.HistoryType
import app.mama.core.LockEngine
import app.mama.core.LockPlan
import app.mama.core.Series
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.Session
import app.mama.core.SessionMode
import app.mama.core.SessionStatus
import app.mama.core.Snapshot
import app.mama.core.StateCodec
import app.mama.core.TrustedContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * SeriesHistory on the device: results are recorded by the real
 * [Mama.reconcile] after the core settled them, once each, and the lock
 * state is exactly what it would be without any history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeriesHistoryStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val mom = TrustedContact("Мама", "+79001234567")
    private val window = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), zone)
    private val engine = LockEngine(CorePolicy())
    private val now = Instant.now()

    private fun history() = context.createDeviceProtectedStorageContext().getSharedPreferences("mama_history", Context.MODE_PRIVATE)

    private fun seed(snapshot: Snapshot) {
        val store = context.createDeviceProtectedStorageContext().getSharedPreferences("mama_state", Context.MODE_PRIVATE).edit().clear()
        StateCodec.encode(snapshot).forEach { (k, v) -> store.putString(k, v) }
        store.commit()
    }

    /** Tonight's lock, left an hour ago with the trusted contact's code. */
    private fun exitedNight(id: String): Session {
        val plan = LockPlan(now - Duration.ofHours(2), now + Duration.ofHours(6), zone)
        val running = engine.advance((engine.create(id, plan, mom, now - Duration.ofHours(3)) as LockEngine.CreateResult.Created).session, plan.start)
        val at = now - Duration.ofHours(1)
        val issued = engine.requestCode(running, ExitKind.END_SESSION, at) as LockEngine.CodeRequestResult.Issued
        return (engine.submitCode(issued.session, issued.plainCode, at) as LockEngine.CodeResult.Accepted).session
    }

    /** Last night's lock, run to its planned end. */
    private fun completedNight(id: String): Session {
        val plan = LockPlan(now - Duration.ofHours(9), now - Duration.ofHours(1), zone)
        val created = (engine.create(id, plan, mom, now - Duration.ofHours(10)) as LockEngine.CreateResult.Created).session
        return engine.advance(created, now - Duration.ofMinutes(30))
    }

    private fun active(id: String, kind: SeriesKind, done: Int, restartOf: String? = null) = Series(
        id, kind, window, mom, SessionMode.SLEEP, startedAt = now - Duration.ofDays(5), completedPeriods = done,
        status = SeriesStatus.ACTIVE, actualStartedAt = now - Duration.ofDays(4), lastSessionId = "earlier-night", restartOf = restartOf,
    )

    @Test
    fun `a series left with Trusted Exit is recorded once as BROKEN, with the Restart it continued`() {
        val night = exitedNight("night-3")
        val before = Snapshot(series = active("restart-1", SeriesKind.SEVEN, done = 2, restartOf = "failed-0"), session = night)
        seed(before)
        Mama.reconcile(context)
        assertEquals(SeriesStatus.BROKEN, Mama.series(context)!!.status)

        val r = History.records(context).single()
        assertEquals("restart-1", r.id)
        assertEquals(HistoryType.STANDARD_7, r.type)
        assertEquals(HistoryResult.BROKEN, r.result)
        assertEquals(7, r.plannedNights)
        assertEquals(2, r.successfulNights)
        assertNull(r.consumedNights)
        assertEquals(window, r.window)
        assertEquals("failed-0", r.restartOf)
        assertEquals((now - Duration.ofDays(4)).toEpochMilli(), r.startedAt!!.toEpochMilli())
        assertEquals(night.finishedAt!!.toEpochMilli(), r.endedAt.toEpochMilli())

        // Reconciling again, and even settling the same night again (as if the
        // state had not been saved before a crash), adds nothing.
        Mama.reconcile(context)
        seed(before)
        Mama.reconcile(context)
        assertEquals(1, History.records(context).size)
    }

    @Test
    fun `the last night of a 3-night series is recorded once as COMPLETED`() {
        seed(Snapshot(series = active("three", SeriesKind.THREE, done = 2), session = completedNight("night-3")))
        Mama.reconcile(context)
        assertEquals(SeriesStatus.COMPLETED, Mama.series(context)!!.status)
        val r = History.records(context).single()
        assertEquals(HistoryType.STANDARD_3, r.type)
        assertEquals(HistoryResult.COMPLETED, r.result)
        assertEquals(3, r.successfulNights)
        assertNull(r.restartOf)
    }

    @Test
    fun `a night that counts but does not end the series records nothing`() {
        seed(Snapshot(series = active("seven", SeriesKind.SEVEN, done = 2), session = completedNight("night-3")))
        Mama.reconcile(context)
        assertEquals(SeriesStatus.ACTIVE, Mama.series(context)!!.status)
        assertEquals(3, Mama.series(context)!!.completedPeriods)
        assertTrue(History.records(context).isEmpty())
    }

    @Test
    fun `a FLEX package whose 30 days ran out is recorded once as EXPIRED`() {
        val pkg = FlexPackage("flex-1", now - Duration.ofDays(31), GrantSource.INTERNAL_BALANCE, used = 4, successful = 4)
        seed(Snapshot(entitlements = Entitlements(flex = pkg)))
        Mama.reconcile(context)
        assertTrue(Mama.entitlements(context).flex!!.over)
        Mama.reconcile(context)

        val r = History.records(context).single()
        assertEquals("flex-1", r.id)
        assertEquals(HistoryType.FLEX, r.type)
        assertEquals(HistoryResult.EXPIRED, r.result)
        assertEquals(7, r.plannedNights)
        assertEquals(4, r.consumedNights)
        assertEquals(4, r.successfulNights)
        assertNull(r.window)
    }

    @Test
    fun `a finished 15-minute test lock is never recorded`() {
        val plan = LockPlan(now - Duration.ofMinutes(20), now - Duration.ofMinutes(5), zone, SessionMode.WORK)
        val created = (engine.create("test", plan, mom, now - Duration.ofMinutes(21)) as LockEngine.CreateResult.Created).session
        val finished = engine.advance(created, now)
        assertEquals(SessionStatus.FINISHED, finished.status)
        assertEquals(FinishReason.COMPLETED, finished.finishReason)
        seed(Snapshot(session = finished))
        Mama.reconcile(context)
        assertTrue(History.records(context).isEmpty())
    }

    @Test
    fun `entries written before this model are read, kept as they are, and counted with the new ones`() {
        val ended = (now - Duration.ofDays(40)).toEpochMilli()
        val oldSeries = "$ended|SEVEN|4|BROKEN\n${ended - 86_400_000}|FIVE|5|COMPLETED"
        val oldFlex = "${ended + 1000}|FLEX|6|7"
        history().edit().putString("series", oldSeries).putString("flex", oldFlex).commit()

        val read = History.records(context)
        assertEquals(listOf(HistoryType.FLEX, HistoryType.STANDARD_7, HistoryType.STANDARD_5), read.map { it.type })
        assertEquals(HistoryResult.COMPLETED, read[0].result)
        assertEquals(7, read[0].consumedNights)
        assertEquals(6, read[0].successfulNights)
        assertEquals(HistoryResult.BROKEN, read[1].result)
        assertNull(read[1].startedAt)
        assertNull(read[1].window)

        seed(Snapshot(series = active("new", SeriesKind.THREE, done = 2), session = completedNight("night-3")))
        Mama.reconcile(context)
        assertEquals(listOf("new") + read.map { it.id }, History.records(context).map { it.id })
        // The old lines themselves are never rewritten.
        assertEquals(oldSeries, history().getString("series", null))
        assertEquals(oldFlex, history().getString("flex", null))
    }

    @Test
    fun `no 100-record limit - every result is kept`() {
        repeat(150) { i ->
            val s = active("s$i", SeriesKind.THREE, done = 3).copy(status = SeriesStatus.COMPLETED, endedAt = now - Duration.ofDays(150L - i))
            History.record(context, s, now)
        }
        val all = History.records(context)
        assertEquals(150, all.size)
        assertEquals("s149", all.first().id)
        assertEquals("s0", all.last().id)
    }

    @Test
    fun `an unreadable history never affects the lock state`() {
        history().edit().putString("records", "garbage\nv2|broken").putString("series", "also|garbage").commit()
        seed(Snapshot(series = active("seven", SeriesKind.SEVEN, done = 2), session = exitedNight("night-3")))
        Mama.reconcile(context)
        assertEquals(SeriesStatus.BROKEN, Mama.series(context)!!.status)
        assertEquals(listOf("seven"), History.records(context).map { it.id })
        // Lines it cannot read (say, from a newer version) are kept, not dropped by the write.
        assertTrue(history().getString("records", null)!!.lines().containsAll(listOf("garbage", "v2|broken")))
    }
}
