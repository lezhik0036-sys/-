package app.mama.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.mama.core.CorePolicy
import app.mama.core.DailyWindow
import app.mama.core.ExitKind
import app.mama.core.LockEngine
import app.mama.core.LockPlan
import app.mama.core.Series
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.Session
import app.mama.core.SessionMode
import app.mama.core.Snapshot
import app.mama.core.StateCodec
import app.mama.core.TrustedContact
import app.mama.platform.Mama
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * 3A.1: "Мой путь" is always at hand. The welcome, success and recovery
 * screens (no tab bar) open the same statistics with a way back; the
 * history stays after a series ended and was put away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MyPathAccessTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val mom = TrustedContact("Мама", "+79001234567")
    private val engine = LockEngine(CorePolicy())
    private val now = Instant.now()

    private fun seed(snapshot: Snapshot) {
        val store = context.createDeviceProtectedStorageContext().getSharedPreferences("mama_state", Context.MODE_PRIVATE).edit().clear()
        StateCodec.encode(snapshot).forEach { (k, v) -> store.putString(k, v) }
        store.commit()
        Mama.reconcile(context)
    }

    private fun active(kind: SeriesKind, done: Int) = Series(
        "s1", kind, DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), zone), mom, SessionMode.SLEEP,
        startedAt = now - Duration.ofDays(5), completedPeriods = done, status = SeriesStatus.ACTIVE,
        actualStartedAt = now - Duration.ofDays(4), lastSessionId = "earlier-night",
    )

    /** The last night of a 3-night series ran to its end: the app settles it COMPLETED and records it. */
    private fun completedSeries() {
        val plan = LockPlan(now - Duration.ofHours(9), now - Duration.ofHours(1), zone)
        val night = engine.advance((engine.create("n3", plan, mom, now - Duration.ofHours(10)) as LockEngine.CreateResult.Created).session, now)
        seed(Snapshot(series = active(SeriesKind.THREE, done = 2), session = night))
    }

    /** Trusted Exit an hour ago on the third night of seven: the app settles it BROKEN and records it. */
    private fun brokenSeries() {
        val plan = LockPlan(now - Duration.ofHours(2), now + Duration.ofHours(6), zone)
        val running = engine.advance((engine.create("n3", plan, mom, now - Duration.ofHours(3)) as LockEngine.CreateResult.Created).session, plan.start)
        val at = now - Duration.ofHours(1)
        val issued = engine.requestCode(running, ExitKind.END_SESSION, at) as LockEngine.CodeRequestResult.Issued
        val exited: Session = (engine.submitCode(issued.session, issued.plainCode, at) as LockEngine.CodeResult.Accepted).session
        seed(Snapshot(series = active(SeriesKind.SEVEN, done = 2), session = exited))
    }

    private fun content(a: MainActivity) = a.findViewById<ViewGroup>(android.R.id.content)!!

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun tagged(v: View, tag: String): Int =
        (if (v.tag == tag) 1 else 0) + if (v is ViewGroup) (0 until v.childCount).sumOf { tagged(v.getChildAt(it), tag) } else 0

    /** Taps "Мой путь" and checks the statistics: no tab bar, a way back. */
    private fun openPath(a: MainActivity): View {
        val entry = content(a).findViewWithTag<View>(OPEN_PATH)
        assertNotNull("a quiet «Мой путь» on this screen", entry)
        entry.performClick()
        val shown = texts(content(a))
        assertFalse("no tab bar on «Мой путь»: $shown", "Статистика" in shown || "Настройки" in shown)
        return content(a)
    }

    @Test
    fun `welcome - Мой путь - the empty state before any history, back to the welcome`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(texts(content(a)).toString(), "Мой путь" in texts(content(a)))
        val path = openPath(a)
        assertNotNull(path.findViewWithTag<View>(STATISTICS_EMPTY))
        assertTrue("Твой путь только начинается" in texts(path))

        a.onBackPressed()
        assertNull(content(a).findViewWithTag<View>(STATISTICS_EMPTY))
        assertNotNull("back on the welcome", content(a).findViewWithTag<View>(OPEN_PATH))
        assertTrue("Мой путь" in texts(content(a)))
    }

    @Test
    fun `success - Посмотреть мой путь - the series just done, back to the success screen`() {
        completedSeries()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue("Ты сделал это." in texts(content(a)))
        assertTrue("Посмотреть мой путь" in texts(content(a)))
        val path = openPath(a)
        assertNotNull(path.findViewWithTag<View>(STATISTICS_SCREEN))
        assertEquals(1, tagged(path, PATH_ITEM))
        assertTrue(texts(path).toString(), "Серия пройдена" in texts(path))

        a.onBackPressed()
        assertTrue("back on the success screen", "Ты сделал это." in texts(content(a)))
    }

    @Test
    fun `recovery - Посмотреть мой путь - the series that stopped, back to the recovery screen`() {
        brokenSeries()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull(content(a).findViewWithTag<View>(RECOVERY_SCREEN))
        val path = openPath(a)
        assertNotNull(path.findViewWithTag<View>(STATISTICS_SCREEN))
        assertEquals(1, tagged(path, PATH_ITEM))
        assertTrue(texts(path).toString(), "Этот путь продолжается" in texts(path))

        a.onBackPressed()
        assertNotNull("back on the recovery screen", content(a).findViewWithTag<View>(RECOVERY_SCREEN))
        assertNotNull(content(a).findViewWithTag<View>(RESTART_CTA))
    }

    @Test
    fun `after a finished series is put away, the welcome still leads to it`() {
        completedSeries()
        Mama.dismissSeries(context)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNull("no series any more", Mama.series(context))
        val path = openPath(a)
        assertNotNull(path.findViewWithTag<View>(STATISTICS_SCREEN))
        assertEquals(1, tagged(path, PATH_ITEM))
        assertTrue(texts(path).toString(), "Серия пройдена" in texts(path))
    }
}
