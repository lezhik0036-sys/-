package app.mama.ui

import android.content.Context
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import app.mama.core.DailyWindow
import app.mama.core.FlexPackage
import app.mama.core.GrantSource
import app.mama.core.Entitlements
import app.mama.core.Series
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.SessionMode
import app.mama.core.Snapshot
import app.mama.core.StateCodec
import app.mama.core.TrustedContact
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Startup regression tests: MainActivity must open and render its first
 * screen with no permissions granted, on a clean install and on top of state
 * written by v0.3.x. Runs on the real framework code of each SDK level, so a
 * framework call that throws during onCreate fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 34])
class StartupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun launchAndCheck() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertFalse("MainActivity finished during startup", activity.isFinishing)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)!!
        assertTrue("first screen was not rendered", content.childCount > 0)
        controller.pause().stop().destroy()
    }

    @Test
    fun `clean install opens the first screen without permissions`() = launchAndCheck()

    @Test
    fun `upgrade over v0_3_x setup and state opens the first screen`() {
        // Setup form as v0.3.2 saved it (no "choice" key yet, series length under "series").
        context.getSharedPreferences("mama_setup", Context.MODE_PRIVATE).edit()
            .putString("mode", "SLEEP")
            .putString("series", "FIVE")
            .putString("start", "23:00")
            .putString("end", "07:00")
            .putString("zone", "Europe/Moscow")
            .putString("contactName", "Мама")
            .putString("contactPhone", "+79001234567")
            .commit()
        // Engine state in the v0.3.2 format: an active FLEX package and a finished series.
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val snapshot = Snapshot(
            lastTrusted = now,
            series = Series(
                id = "old-series", kind = SeriesKind.THREE,
                window = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), ZoneId.of("Europe/Moscow")),
                contact = TrustedContact("Мама", "+79001234567"), mode = SessionMode.SLEEP,
                startedAt = now.minus(5, ChronoUnit.DAYS), completedPeriods = 3,
                status = SeriesStatus.COMPLETED, endedAt = now.minus(1, ChronoUnit.DAYS),
            ),
            entitlements = Entitlements(
                flex = FlexPackage("old-flex", now.minus(2, ChronoUnit.DAYS), GrantSource.TEST_NO_PAYMENT, used = 1, successful = 1),
            ),
        )
        val store = context.createDeviceProtectedStorageContext().getSharedPreferences("mama_state", Context.MODE_PRIVATE).edit()
        StateCodec.encode(snapshot).forEach { (k, v) -> store.putString(k, v) }
        store.commit()
        launchAndCheck()
    }

    @Test
    fun `unknown or obsolete stored setup values fall back to defaults`() {
        context.getSharedPreferences("mama_setup", Context.MODE_PRIVATE).edit()
            .putString("mode", "NOT_A_MODE")
            .putString("choice", "TEN_DAYS")
            .putString("series", "TEN")
            .putString("start", "25:99")
            .putString("zone", "Mars/Olympus")
            .commit()
        launchAndCheck()
    }
}
