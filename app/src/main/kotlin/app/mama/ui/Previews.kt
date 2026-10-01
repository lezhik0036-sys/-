package app.mama.ui

import android.view.View
import app.mama.core.DailyWindow
import app.mama.core.FlexPackage
import app.mama.core.GrantSource
import app.mama.core.LockState
import app.mama.core.Series
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.SessionMode
import app.mama.core.TrustedContact
import app.mama.lock.LockActions
import app.mama.lock.LockModel
import app.mama.lock.LockScreenView
import app.mama.platform.Calls
import app.mama.ui.MainActivity.Choice
import app.mama.ui.MainActivity.Step
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Debug builds only: every screen with sample data, for the screenshot tour
 * (`am start -n app.mama/.ui.MainActivity --es mama.preview <name>`).
 * Nothing here touches the lock engine or stored state.
 */
object Previews {
    val names = listOf(
        "welcome", "permissions", "series", "time", "contact", "promise", "review",
        "dashboard", "lock", "exit", "code", "failure", "success", "flex", "flex_plan", "stats", "settings", "time_test",
    )

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val contact = TrustedContact("Жена", "+79856322062")

    private fun series(done: Int, status: SeriesStatus = SeriesStatus.ACTIVE) = Series(
        id = "preview", kind = SeriesKind.SEVEN,
        window = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), zone),
        contact = contact, mode = SessionMode.SLEEP, startedAt = Instant.now().minus(3, ChronoUnit.DAYS),
        completedPeriods = done, status = status,
    )

    fun render(a: MainActivity, name: String): Pair<View, Boolean> {
        a.contactName.setText(contact.name)
        a.contactPhone.setText(Texts.phone(contact.phone))
        a.promiseField.setText("Хочу нормально высыпаться и чувствовать себя лучше утром.")
        a.zone = zone
        a.start = LocalTime.of(23, 0)
        a.end = LocalTime.of(7, 0)
        a.choice = Choice.SEVEN
        val now = Instant.now()
        val waiting = LockState.Waiting(now + Duration.ofSeconds(13 * 3600 + 18 * 60 + 24), now + Duration.ofHours(21))
        return when (name) {
            "welcome" -> a.welcomeScreen()
            "permissions" -> a.permissionsScreen()
            "series" -> a.seriesScreen()
            "time" -> a.timeScreen()
            "time_test" -> { a.choice = Choice.TEST; a.timeScreen() }
            "contact" -> a.contactScreen()
            "promise" -> a.promiseScreen()
            "review" -> { a.agreed = true; a.step = Step.REVIEW; a.reviewScreen() }
            "dashboard" -> a.seriesDashboard(series(0)) { waiting }
            "failure" -> a.failureScreen(series(4, SeriesStatus.BROKEN), freeInDays = 30)
            "success" -> a.successScreen(series(7, SeriesStatus.COMPLETED))
            "flex" -> a.flexDashboard(FlexPackage("p", now.minus(6, ChronoUnit.DAYS), GrantSource.TEST_NO_PAYMENT, used = 5, successful = 5)) { LockState.Free }
            "flex_plan" -> { a.flexStartNow = false; a.flexPlannerScreen(FlexPackage("p", now, GrantSource.TEST_NO_PAYMENT)) }
            "stats" -> { a.tab = 1; a.statisticsScreen() }
            "settings" -> { a.tab = 2; a.settingsScreen() }
            "lock", "exit", "code" -> lock(a, name)
            else -> a.welcomeScreen()
        }
    }

    private fun lock(a: MainActivity, name: String): Pair<View, Boolean> {
        val view = LockScreenView(a, object : LockActions {
            override fun emergencyCall() = Unit
            override fun openExit() = Unit
            override fun closeExit() = Unit
            override fun requestCode() = Unit
            override fun callContact() = Unit
            override fun key(k: String) = Unit
            override fun submit() = Unit
            override fun answer() = Unit
            override fun hangUp() = Unit
        })
        val now = Instant.now()
        view.bind(
            LockModel(
                now = now, start = now.minus(Duration.ofMinutes(77)), end = now + Duration.ofSeconds(6 * 3600 + 42 * 60 + 17), zone = zone,
                contactName = contact.name, contactPhone = contact.phone, dayLabel = Texts.dayOf(3, 7),
                phrase = Texts.lockPhrases[0], promise = "Хочу нормально высыпаться и чувствовать себя лучше утром.",
                call = Calls.CallState.NONE, exitOpen = name != "lock", codeActive = name == "code",
                attemptsLeft = 3, resendAt = if (name == "code") now + Duration.ofSeconds(57) else null, codeLength = 5,
                input = if (name == "code") "47" else "", message = null, diagnostics = null,
            ),
        )
        return view.root to (name != "lock")
    }
}
