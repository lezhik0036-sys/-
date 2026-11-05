package app.mama.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Personal MAMA is the default product for everybody. Family is an optional
 * extension and must never become a required role before the existing wizard.
 * Time can be chosen during the daytime; sleep is a use case, not a hard gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UniversalPersonalOnboardingTest {

    private fun texts(root: View): List<String> = when (root) {
        is TextView -> listOf(root.text.toString().replace('\n', ' '))
        is ViewGroup -> (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `a new person starts personal setup without parental role or login`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(MainActivity.Step.WELCOME, activity.step)
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val start = arrayListOf<View>()
        root.findViewsWithText(start, "Начать путь", View.FIND_VIEWS_WITH_TEXT)
        assertTrue("Personal MAMA start must be visible to all users", start.isNotEmpty())
        start.first().performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(MainActivity.Step.PERMISSIONS, activity.step)
        assertFalse(
            "Personal onboarding must never require Family approval",
            texts(activity.findViewById(android.R.id.content)).any {
                it.contains("Введите родительский код") || it.contains("Вход родителя обязателен")
            },
        )
    }

    @Test
    fun `intro explains daytime and any-time focus rather than night-only usage`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val screen = activity.onboardingScreen(1).first
        val shown = texts(screen).joinToString(" ")

        assertTrue("Selected local-time windows are available", shown.contains("в любой час"))
        assertTrue("Series are periods, not nights", shown.contains("3, 5 или 7 периодов"))
        assertFalse("The introduction must not promise night-only use", shown.contains("только ночью"))
    }
}
