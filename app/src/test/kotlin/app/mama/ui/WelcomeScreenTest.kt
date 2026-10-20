package app.mama.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mama.ui.design.HeroIllustration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The welcome (Design System v2): the approved illustration on top as a view
 * of its own, whole down to its wave; the welcome as real text; "Начать свой
 * путь" always on screen and "Мой путь" under it; on a Pixel 6 and on a
 * narrower phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WelcomeScreenTest {
    private val welcome = listOf(
        "Добро пожаловать в MAMA",
        "Ты уже сделал важный шаг — выбрал больше времени для настоящей жизни.",
        "MAMA поможет сохранить решение, когда вечером захочется его отменить.",
        "Один вечер. Одно решение. Начнём.",
        "Начать свой путь",
        "Мой путь",
    )

    private fun open(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    private fun content(a: MainActivity) = a.findViewById<ViewGroup>(android.R.id.content)!!

    private fun texts(v: View): List<String> = when (v) {
        // Non-breaking spaces read as spaces.
        is TextView -> listOf(v.text.toString().replace('\u00A0', ' '))
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun bottomIn(v: View, root: View): Int {
        val at = IntArray(2).also { v.getLocationInWindow(it) }
        val r = IntArray(2).also { root.getLocationInWindow(it) }
        return at[1] - r[1] + v.height
    }

    /** The illustration is shown whole, down to its wave (900 rows of a 941 px wide art), as wide as the screen. */
    private fun assertHero(a: MainActivity) {
        val root = content(a)
        val hero = root.findViewWithTag<View>(WELCOME_HERO)
        assertTrue(hero is HeroIllustration)
        val whole = (hero.width * 900f / 941).roundToInt()
        assertTrue("illustration height ${hero.height}, whole art $whole", abs(hero.height - whole) <= 1)
        assertEquals("as wide as the screen", root.width, hero.width)
        assertNotNull("«Мой путь» stays", root.findViewWithTag<View>(OPEN_PATH))
        val cta = root.findViewsWithText("Начать свой путь")
        assertTrue("«Начать свой путь» is on screen", cta.isNotEmpty() && bottomIn(cta.first(), root) <= root.height && cta.first().isShown)
    }

    private fun ViewGroup.findViewsWithText(text: String): List<View> =
        arrayListOf<View>().also { findViewsWithText(it, text, View.FIND_VIEWS_WITH_TEXT) }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun `Pixel 6 - the welcome as real text under the illustration, nothing cut`() {
        val a = open()
        val shown = texts(content(a))
        for (t in welcome) assertTrue("«$t» in $shown", t in shown)
        assertFalse("the old welcome is gone", shown.any { it.contains("Ты уже решил") || it.contains("решил изменить вечер") })
        assertHero(a)
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-xhdpi")
    fun `a narrower phone - the same, the illustration whole`() {
        val a = open()
        val shown = texts(content(a))
        for (t in welcome) assertTrue("«$t» in $shown", t in shown)
        assertHero(a)
    }

    @Test
    fun `Начать свой путь leads into the same setup as before`() {
        val a = open()
        content(a).findViewsWithText("Начать свой путь").first().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MainActivity.Step.PERMISSIONS, a.step)
    }
}
