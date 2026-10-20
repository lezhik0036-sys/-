package app.mama.ui

import android.view.View
import app.mama.core.Commitment
import app.mama.core.CommitmentState
import app.mama.core.DailyWindow
import app.mama.core.FlexPackage
import app.mama.core.GrantSource
import app.mama.core.LockState
import app.mama.core.RestartHold
import app.mama.core.Series
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.SessionMode
import app.mama.core.TrustedContact
import app.mama.core.Wallet
import app.mama.lock.LockActions
import app.mama.lock.LockModel
import app.mama.lock.LockScreenView
import app.mama.motivation.MotivationCatalog
import app.mama.motivation.Placement
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
        "commitment", "restart_commitment", "dashboard_active", "edit", "cancel_launch",
        "needs_attention", "restart_scheduled", "emergency", "restart_time",
    ) + app.mama.ui.design.DesignPreviews.names + listOf(
        // UI v1 group 2: the home of a 7-day series at its start, middle and end; the motivation screen.
        "progress_early", "progress_middle", "progress_final", "motivation",
    )

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val contact = TrustedContact("Жена", "+79856322062")

    private fun series(done: Int, status: SeriesStatus = SeriesStatus.ACTIVE) = Series(
        id = "preview", kind = SeriesKind.SEVEN,
        window = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), zone),
        contact = contact, mode = SessionMode.SLEEP, startedAt = Instant.now().minus(3, ChronoUnit.DAYS),
        completedPeriods = done, status = status,
    )

    private fun scheduledCommitment(now: Instant) = Commitment("preview", 5_000_00, now, CommitmentState.SCHEDULED)

    /** A sample balance of MAMA, so "Цена слова" shows its usual state. */
    private val sampleWallet = Wallet(availableKop = 6_000_00)

    fun render(a: MainActivity, name: String): Pair<View, Boolean> {
        // UI v1 foundation (PHASE 1): components on sample screens.
        if (name.startsWith("ds_")) return app.mama.ui.design.DesignPreviews.render(a, name)
        a.contactName.setText(contact.name)
        a.contactPhone.setText(Texts.phone(contact.phone))
        a.promiseField.setText("Хочу нормально высыпаться и чувствовать себя лучше утром.")
        a.zone = zone
        a.start = LocalTime.of(23, 0)
        a.end = LocalTime.of(7, 0)
        a.choice = Choice.SEVEN
        a.startMode = MainActivity.StartMode.MANUAL
        val now = Instant.now()
        // The next lock of the sample window 23:00 → 07:00, so the timer agrees with the window on the screen.
        val night = java.time.LocalDate.now(zone).atTime(23, 0).atZone(zone).toInstant().let { if (it.isAfter(now)) it else it.plus(1, ChronoUnit.DAYS) }
        val waiting = LockState.Waiting(night, night + Duration.ofHours(8))
        return when (name) {
            "welcome" -> a.welcomeScreen()
            "permissions" -> a.permissionsScreen()
            "series" -> a.seriesScreen()
            "time" -> a.timeScreen()
            "time_test" -> { a.choice = Choice.TEST; a.timeScreen() }
            "contact" -> a.contactScreen()
            "promise" -> a.promiseScreen()
            "review" -> { a.agreed = true; a.step = Step.REVIEW; a.reviewScreen() }
            "dashboard" -> a.seriesDashboard(series(0, SeriesStatus.SCHEDULED), scheduledCommitment(now), protectionMissing = emptyList()) { waiting }
            "dashboard_active" -> a.seriesDashboard(
                series(2),
                Commitment("preview", 5_000_00, now.minus(3, ChronoUnit.DAYS), CommitmentState.RESERVED, fixedAt = now.minus(2, ChronoUnit.DAYS)),
                protectionMissing = emptyList(),
            ) { waiting }
            "progress_early", "progress_middle", "progress_final" -> {
                val done = when (name) {
                    "progress_early" -> 0
                    "progress_middle" -> 3
                    else -> 6
                }
                a.seriesDashboard(
                    series(done),
                    Commitment("preview", 5_000_00, now.minus(3, ChronoUnit.DAYS), CommitmentState.RESERVED, fixedAt = now.minus(2, ChronoUnit.DAYS)),
                    protectionMissing = emptyList(),
                ) { waiting }
            }
            "motivation" -> a.motivationScreen(series(3))
            "needs_attention" -> a.seriesDashboard(
                series(0, SeriesStatus.NEEDS_ATTENTION), scheduledCommitment(now), protectionMissing = listOf(Requirement.GUARD),
            ) { LockState.Free }
            "restart_scheduled" -> a.seriesDashboard(
                series(0, SeriesStatus.SCHEDULED).copy(restartOf = "failed"), null,
                RestartHold("preview", 299_00, now), protectionMissing = emptyList(),
            ) { waiting }
            "edit" -> { a.editing = true; a.commitmentKop = 5_000_00; a.step = Step.REVIEW; a.editScreen(series(0, SeriesStatus.SCHEDULED)) }
            "cancel_launch" -> {
                val s = series(0, SeriesStatus.SCHEDULED)
                a.window.decorView.post { a.confirmCancelLaunch(s) }
                a.seriesDashboard(s, scheduledCommitment(now), protectionMissing = emptyList()) { waiting }
            }
            "failure" -> a.failureScreen(
                series(4, SeriesStatus.BROKEN), freeInDays = 30,
                sampleCommitment = Commitment(
                    "preview", 5_000_00, now.minus(4, ChronoUnit.DAYS), CommitmentState.FORFEITED, now,
                    returnKop = 2_500_00, returnDueAt = now.plus(24, ChronoUnit.HOURS),
                ),
            )
            "success" -> a.successScreen(
                series(7, SeriesStatus.COMPLETED),
                Commitment("preview", 5_000_00, now.minus(7, ChronoUnit.DAYS), CommitmentState.RELEASED, now),
            )
            "commitment" -> { a.commitmentKop = 500_00; a.choice = Choice.SEVEN; a.commitmentScreen(false, SeriesKind.SEVEN, sampleWallet) }
            "restart_time" -> {
                // Restart 7/7 after a failure at 15:46: a new time draft from "now" (16:20).
                a.restartFlow = true
                a.startMode = MainActivity.StartMode.AUTO_NOW
                a.start = LocalTime.of(16, 20)
                a.end = LocalTime.of(0, 20)
                a.timeScreen()
            }
            "restart_commitment" -> { a.commitmentKop = null; a.commitmentScreen(true, SeriesKind.THREE, sampleWallet) }
            "flex" -> a.flexDashboard(FlexPackage("p", now.minus(6, ChronoUnit.DAYS), GrantSource.TEST_NO_PAYMENT, used = 5, successful = 5)) { LockState.Free }
            "flex_plan" -> { a.flexStartNow = false; a.flexPlannerScreen(FlexPackage("p", now, GrantSource.TEST_NO_PAYMENT)) }
            "stats" -> { a.tab = 1; a.statisticsScreen() }
            "settings" -> { a.tab = 2; a.settingsScreen() }
            "lock", "exit", "code", "emergency" -> lock(a, name)
            else -> a.welcomeScreen()
        }
    }

    private fun lock(a: MainActivity, name: String): Pair<View, Boolean> {
        val view = LockScreenView(a, object : LockActions {
            override fun openEmergency() = Unit
            override fun closeEmergency() = Unit
            override fun dialEmergency(number: String) = Unit
            override fun returnToCall() = Unit
            override fun openExit() = Unit
            override fun closeExit() = Unit
            override fun requestCode() = Unit
            override fun callContact() = Unit
            override fun key(k: String) = Unit
            override fun submit() = Unit
            override fun answer() = Unit
            override fun hangUp() = Unit
        })
        // A night lock 23:00 → 07:00 with 6 h 12 min left.
        val today = java.time.LocalDate.now(zone)
        val morning = today.atTime(7, 0).atZone(zone).toInstant().let { if (it.isAfter(Instant.now())) it else it.plus(1, ChronoUnit.DAYS) }
        val now = morning.minus(Duration.ofMinutes(6 * 60 + 12))
        view.bind(
            LockModel(
                now = now, start = morning.minus(Duration.ofHours(8)), end = morning, zone = zone,
                contactName = contact.name, contactPhone = Texts.phone(contact.phone), dayLabel = Texts.dayOf(3, 7),
                phrase = MotivationCatalog.pick(Placement.LOCK_NIGHT, "preview", maxLength = 60).text,
                promise = "Хочу нормально высыпаться и чувствовать себя лучше утром.",
                call = Calls.CallState.NONE, exitOpen = name == "exit" || name == "code", codeActive = name == "code",
                attemptsLeft = 3, resendAt = if (name == "code") now + Duration.ofSeconds(57) else null, codeLength = 5,
                input = if (name == "code") "47" else "", message = null, diagnostics = null,
                emergencyOpen = name == "emergency",
                emergencyServices = app.mama.core.EmergencyNumberCatalog.RUSSIA_FALLBACK,
            ),
        )
        return view.root to true
    }
}
