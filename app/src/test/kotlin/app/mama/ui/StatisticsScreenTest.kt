package app.mama.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.mama.core.DailyWindow
import app.mama.core.Series
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.SessionMode
import app.mama.core.TrustedContact
import app.mama.platform.History
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

/** The statistics screen (3A) shows what SeriesHistory really holds, calmly, and an empty state before that. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatisticsScreenTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val mom = TrustedContact("Мама", "+79001234567")
    private val now = Instant.now()

    /** Words that judge, rate, count streaks or hours, or talk money: never on this screen. */
    private val forbidden = listOf("broken", "failed", "провал", "неудач", "сорван", "%", "подряд", "без телефона", "рейтинг", "₽")

    private fun finished(id: String, kind: SeriesKind, done: Int, status: SeriesStatus, endedDaysAgo: Long, restartOf: String? = null) = Series(
        id, kind, DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), zone), mom, SessionMode.SLEEP,
        startedAt = now - Duration.ofDays(endedDaysAgo + 8), completedPeriods = done, status = status,
        endedAt = now - Duration.ofDays(endedDaysAgo), actualStartedAt = now - Duration.ofDays(endedDaysAgo + kind.periods),
        restartOf = restartOf,
    )

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun tagged(v: View, tag: String): Int =
        (if (v.tag == tag) 1 else 0) + if (v is ViewGroup) (0 until v.childCount).sumOf { tagged(v.getChildAt(it), tag) } else 0

    private fun screen(): View {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        return a.statisticsScreen().first
    }

    private fun assertCalm(shown: List<String>) {
        for (t in shown) for (w in forbidden) assertFalse("«$t» on the statistics screen", t.lowercase().contains(w))
    }

    @Test
    fun `the history recorded by the app is what the screen shows`() {
        History.record(context, finished("first", SeriesKind.SEVEN, 4, SeriesStatus.BROKEN, endedDaysAgo = 30), now)
        History.record(context, finished("again", SeriesKind.SEVEN, 7, SeriesStatus.COMPLETED, endedDaysAgo = 10, restartOf = "first"), now)

        val view = screen()
        assertNotNull(view.findViewWithTag<View>(STATISTICS_SCREEN))
        assertNull(view.findViewWithTag<View>(STATISTICS_EMPTY))
        val shown = texts(view)
        assertTrue(shown.toString(), "Ночей пройдено" in shown)
        assertTrue(shown.toString(), "11" in shown)
        assertTrue(shown.toString(), "Серий завершено" in shown)
        assertTrue(shown.toString(), "Лучший результат" in shown)
        assertTrue(shown.toString(), "Продолжил после срыва" in shown)
        assertTrue(shown.toString(), "Твой путь" in shown)
        assertTrue(shown.toString(), "Ты продолжил путь позже" in shown)
        assertTrue(shown.toString(), "Серия пройдена" in shown)
        assertFalse("no FLEX without FLEX history", shown.any { it.contains("FLEX") })
        assertEquals(2, tagged(view, PATH_ITEM))
        assertCalm(shown)
    }

    @Test
    fun `entries written before SeriesHistory are on the path too`() {
        val ended = (now - Duration.ofDays(40)).toEpochMilli()
        context.createDeviceProtectedStorageContext().getSharedPreferences("mama_history", Context.MODE_PRIVATE).edit()
            .putString("series", "$ended|THREE|3|COMPLETED").putString("flex", "${ended + 1000}|FLEX|4|4").commit()
        val view = screen()
        assertEquals(2, tagged(view, PATH_ITEM))
        val shown = texts(view)
        assertTrue(shown.toString(), "FLEX" in shown)
        assertTrue(shown.toString(), "Срок пакета закончился" in shown)
        assertTrue(shown.toString(), "FLEX-периодов пройдено" in shown)
        assertCalm(shown)
    }

    @Test
    fun `before any history - the empty state, with no numbers at all`() {
        val view = screen()
        assertNotNull(view.findViewWithTag<View>(STATISTICS_EMPTY))
        assertNull(view.findViewWithTag<View>(STATISTICS_SCREEN))
        val shown = texts(view)
        assertTrue(shown.toString(), "Твой путь только начинается" in shown)
        assertTrue(shown.toString(), "Здесь появятся решения, которыми ты будешь гордиться." in shown)
        assertFalse(shown.toString(), shown.any { t -> t.any(Char::isDigit) })
    }
}
