package app.mama.ui

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On a real device/emulator: MainActivity opens and stays alive with no permissions granted. */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @Test
    fun mainActivityLaunchesAndStaysAlive() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            Thread.sleep(3_000)
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertTrue(activity.findViewById<android.view.ViewGroup>(android.R.id.content)!!.childCount > 0)
            }
        }
    }
}
