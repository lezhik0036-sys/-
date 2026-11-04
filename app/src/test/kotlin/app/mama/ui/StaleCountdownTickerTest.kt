package app.mama.ui

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

/** Regression for a countdown callback resurrecting itself after render(). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StaleCountdownTickerTest {
    @Test
    fun `ticker cancelled by completed series screen never rearms itself`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        var oldCount = 0

        // The real dashboard countdown calls render() when the lock state
        // changes. render() cancels the old countdown via stopTicker().
        activity.startTicker {
            oldCount++
            activity.render()
        }
        assertEquals(1, oldCount)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals("Old dashboard countdown must not run on result screen", 1, oldCount)
        controller.pause().stop().destroy()
    }

    @Test
    fun `replaced ticker never runs after a new ticker takes ownership`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        var oldCount = 0
        var newCount = 0
        activity.startTicker {
            oldCount++
            if (oldCount == 1) {
                activity.startTicker { newCount++ }
            }
        }
        assertEquals(1, oldCount)
        assertEquals(1, newCount)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(2200))
        assertEquals("Replaced timer must never be rearmed", 1, oldCount)
        assertEquals(3, newCount)
        controller.pause().stop().destroy()
    }
}
