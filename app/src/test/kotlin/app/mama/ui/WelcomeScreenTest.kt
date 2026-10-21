package app.mama.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

/**
 * The welcome, exactly as approved: the art covers the screen; the welcome as
 * real text in its card; "Начать путь" always on screen; the three notes under
 * it; "Мой путь" still at hand; on a Pixel 6 and on a narrower phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WelcomeScreenTest {
    private val welcome = listOf(
        "Добро пожаловать в MAMA",
        "Здесь ты не один. Природа напоминает: всё можно изменить. Маленькие шаги ведут к большой, свободной и осознанной жизни.",
        "Начать путь",
        "Забота о себе",
        "Больше свободы",
        "Осознанная жизнь",
        "Мой путь",
    )

    private fun open(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    private fun content(a: MainActivity) = a.findViewById<ViewGroup>(android.R.id.content)!!

    private fun texts(v: View): List<String> = when (v) {
        // Non-breaking spaces and line breaks read as spaces.
        is TextView -> listOf(v.text.toString().replace(' ', ' ').replace('\n', ' '))
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun bottomIn(v: View, root: View): Int {
        val at = IntArray(2).also { v.getLocationInWindow(it) }
        val r = IntArray(2).also { root.getLocationInWindow(it) }
        return at[1] - r[1] + v.height
    }

    /** The art covers the whole screen; the button is on it. */
    private fun assertArt(a: MainActivity) {
        val root = content(a)
        val art = root.findViewWithTag<View>(WELCOME_HERO)
        assertNotNull("the welcome art", art)
        assertEquals("as wide as the screen", root.width, art.width)
        assertEquals("as high as the screen", root.height, art.height)
        assertNotNull("«Мой путь» stays", root.findViewWithTag<View>(OPEN_PATH))
        val cta = root.findViewsWithText("Начать путь")
        assertTrue("«Начать путь» is on screen", cta.isNotEmpty() && bottomIn(cta.first(), root) <= root.height && cta.first().isShown)
    }

    private fun ViewGroup.findViewsWithText(text: String): List<View> =
        arrayListOf<View>().also { findViewsWithText(it, text, View.FIND_VIEWS_WITH_TEXT) }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun `Pixel 6 - the approved welcome with its real text`() {
        val a = open()
        val shown = texts(content(a))
        for (t in welcome) assertTrue("«$t» in $shown", t in shown)
        assertFalse("the earlier welcomes are gone", shown.any { it.contains("Ты уже сделал важный шаг") || it.contains("Ты уже решил") })
        assertArt(a)
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-xhdpi")
    fun `a narrower phone - the same, the art covering the screen`() {
        val a = open()
        val shown = texts(content(a))
        for (t in welcome) assertTrue("«$t» in $shown", t in shown)
        assertArt(a)
    }

    @Test
    fun `Начать путь leads into the same setup as before`() {
        val a = open()
        content(a).findViewsWithText("Начать путь").first().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MainActivity.Step.PERMISSIONS, a.step)
    }
}
