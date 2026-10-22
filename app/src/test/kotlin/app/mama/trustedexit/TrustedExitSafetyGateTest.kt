package app.mama.trustedexit

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.mama.billing.FeatureFlags
import app.mama.core.CodeSource
import app.mama.core.CorePolicy
import app.mama.core.DailyWindow
import app.mama.core.ExitKind
import app.mama.core.LockEngine
import app.mama.core.LockPlan
import app.mama.core.LockState
import app.mama.core.SeriesKind
import app.mama.core.SessionMode
import app.mama.core.TestMode
import app.mama.core.TrustedContact
import app.mama.lock.LockActions
import app.mama.lock.LockModel
import app.mama.lock.LockScreenView
import app.mama.platform.Calls
import app.mama.platform.Mama
import app.mama.ui.Texts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * TRUSTED EXIT SAFETY GATE (TEST MODE) — the release gate for every `*-test`
 * APK. Each @Test is one gate point; if any fails, `:app:testDebugUnitTest`
 * fails and the build job never reaches the "Publish the test APK" step, so a
 * build that cannot be unlocked is never published as field-ready.
 *
 * The exit is driven through the same [LockScreenView] a tester uses — the real
 * diagnostics line that shows "TEST CODE: XXXXX", the real keypad, the real
 * "Подтвердить" button — routed to the real [Mama] / Trusted Exit / [TestCodeSink]
 * code, so this proves the human path, not a parallel one. The 15-minute cap is
 * the core rule ([CorePolicy.maxLockDuration]); under Robolectric the device cap
 * is deliberately off (see [app.mama.platform.Mama]), so point 9 proves it at the
 * engine level, exactly as a TEST build applies it on a real phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrustedExitSafetyGateTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val contact = TrustedContact("Мама", "+79001234567")

    private fun clearState() {
        context.createDeviceProtectedStorageContext().getSharedPreferences("mama_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.createDeviceProtectedStorageContext().getSharedPreferences("mama_fake_exit_server_NOT_PRODUCTION_SECURE", Context.MODE_PRIVATE).edit().clear().commit()
        TrustedExitStore(context).clear()
        TestCodeSink.clear()
    }

    @Before
    fun setUp() {
        clearState()
        TrustedExit.online = { true }
        // The TEST build wiring: the simulated delivery stashes the code so the lock screen can show it.
        TrustedExit.backend = FakeTrustedExitBackend(vault = DeviceChallengeVault(context), sms = DebugCodeSmsService)
        Mama.reconcile(context)
    }

    @After
    fun tearDown() {
        TrustedExit.online = { Connectivity.online(it) }
        TrustedExit.backend = FakeTrustedExitBackend()
        TrustedExit.readiness = TrustedExit.defaultReadiness
        TestCodeSink.clear()
    }

    // ---------------- the tester's real lock screen ----------------

    /**
     * The real [LockScreenView], bound exactly as [app.mama.lock.LockOverlay]
     * binds it (the same handle, the same TEST CODE diagnostics), with its
     * actions routed to the real [Mama] calls. Tapping its real buttons is the
     * tester's own path.
     */
    private inner class LockUi : LockActions {
        val view = LockScreenView(context, this)
        private val input = StringBuilder()
        var exitOpen = false
        private var message: String? = null

        val typed: String get() = input.toString()

        fun rebind() {
            val s = Mama.session(context) ?: return
            val now = Mama.trustedNow(context)
            val handle = TrustedExitStore(context).active(s.id)
            val lockedUntil = handle?.lockedUntilMs?.let(Instant::ofEpochMilli)
            val expiresAt = handle?.expiresAtMs?.let(Instant::ofEpochMilli)
            val codeActive = handle != null && handle.kind == ExitKind.END_SESSION.name &&
                lockedUntil == null && expiresAt != null && expiresAt.isAfter(now)
            val resendAt = (lockedUntil ?: expiresAt?.takeIf { codeActive })?.takeIf { it.isAfter(now) }
            view.bind(
                LockModel(
                    now = now, start = s.plan.start, end = s.plan.end, zone = s.plan.zone,
                    contactName = s.contact.name, contactPhone = Texts.phone(s.contact.phone),
                    dayLabel = "Тестовый режим · 15 минут", phrase = "", promise = "",
                    call = Calls.CallState.NONE, exitOpen = exitOpen, codeActive = codeActive,
                    attemptsLeft = handle?.attemptsLeft ?: Mama.policy.maxAttempts,
                    resendAt = resendAt, codeLength = Mama.policy.codeLength, input = input.toString(),
                    message = message, diagnostics = diagnostics(handle?.challengeId, codeActive),
                ),
            )
        }

        /** Mirrors LockOverlay.diagnostics: the TEST CODE and the 15-minute note, only in a TEST build. */
        private fun diagnostics(challengeId: String?, codeActive: Boolean): String = listOfNotNull(
            (if (codeActive) TestCodeSink.codeFor(challengeId) else null)?.let { "TEST CODE: $it" },
            if (FeatureFlags.TEST_MODE) "Тестовый режим — блокировка максимум 15 минут" else null,
        ).joinToString("\n")

        override fun openExit() { exitOpen = true; message = null; rebind() }
        override fun closeExit() { exitOpen = false; input.clear(); message = null; rebind() }
        override fun requestCode() { Mama.requestCode(context, ExitKind.END_SESSION); input.clear(); message = null; rebind() }
        override fun key(k: String) {
            if (k == LockScreenView.DEL) {
                if (input.isNotEmpty()) input.deleteCharAt(input.length - 1)
            } else if (input.length < Mama.policy.codeLength) {
                input.append(k)
            }
            message = null
            rebind()
        }

        override fun submit() {
            if (input.length != Mama.policy.codeLength) return
            val r = Mama.submitCode(context, input.toString())
            input.clear()
            message = when (r) {
                is Mama.SubmitResult.Locked -> "Попытки закончились."
                is Mama.SubmitResult.Wrong -> "Неверный код."
                else -> null
            }
            rebind()
        }

        override fun callContact() = Unit
        override fun openEmergency() = Unit
        override fun closeEmergency() = Unit
        override fun dialEmergency(number: String) = Unit
        override fun returnToCall() = Unit
        override fun answer() = Unit
        override fun hangUp() = Unit
    }

    private fun startTestLock() {
        val r = Mama.start(context, LockPlan(Instant.now().minus(Duration.ofMinutes(1)), Instant.now() + Duration.ofHours(1), zone), contact)
        assertTrue("a test lock started", r is Mama.StartResult.Started)
        assertTrue("the phone is locked", Mama.state(context) is LockState.Locked)
    }

    private fun sessionId() = Mama.session(context)!!.id

    private fun issuedCode(): String {
        val handle = TrustedExitStore(context).active(sessionId())!!
        return TestCodeSink.codeFor(handle.challengeId)!!
    }

    // ---------------- view helpers: find and tap exactly what a person sees ----------------

    private fun firstText(v: View, predicate: (String) -> Boolean): TextView? = when {
        v is TextView && v.text != null && predicate(v.text.toString()) -> v
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { firstText(v.getChildAt(it), predicate) }
        else -> null
    }

    private fun firstClickable(v: View, text: String): View? = when {
        v is TextView && v.isClickable && v.text?.toString() == text -> v
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { firstClickable(v.getChildAt(it), text) }
        else -> null
    }

    private fun byDescription(v: View, desc: String): View? = when {
        v.isClickable && v.contentDescription?.toString() == desc -> v
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { byDescription(v.getChildAt(it), desc) }
        else -> null
    }

    /** VISIBLE all the way up to the screen root: what the tester can actually read. */
    private fun visibleUpTo(view: View, root: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v.visibility != View.VISIBLE) return false
            if (v === root) return true
            v = v.parent as? View
        }
        return true
    }

    private fun tap(ui: LockUi, text: String) {
        (firstClickable(ui.view.root, text) ?: error("no tappable «$text» on the lock screen")).performClick()
    }

    private fun tapBack(ui: LockUi) {
        (byDescription(ui.view.root, "Назад") ?: error("no back arrow on the lock screen")).performClick()
    }

    private fun typeOnKeypad(ui: LockUi, code: String) {
        for (d in code) {
            (firstClickable(ui.view.root, d.toString()) ?: error("keypad key «$d» not found")).performClick()
        }
    }

    /** Opens Trusted Exit and requests a code; returns the ready UI and the issued TEST CODE. */
    private fun openExitWithCode(): Pair<LockUi, String> {
        val ui = LockUi()
        ui.rebind()
        tap(ui, "Мне действительно нужно выйти")
        tap(ui, "Запросить код у: ${contact.name}")
        return ui to issuedCode()
    }

    // ---------------- the ten gate points ----------------

    @Test
    fun point01_a_real_active_lock_starts_in_TEST_MODE() {
        assertTrue("this is a TEST build", FeatureFlags.TEST_MODE)
        startTestLock()
        assertTrue("the phone is actually locked", Mama.state(context) is LockState.Locked)
    }

    @Test
    fun point02_the_lock_screen_opens_Trusted_Exit() {
        startTestLock()
        val ui = LockUi()
        ui.rebind()
        tap(ui, "Мне действительно нужно выйти")
        assertTrue("Trusted Exit is open", ui.exitOpen)
    }

    @Test
    fun point03_Trusted_Exit_creates_a_challenge() {
        startTestLock()
        val ui = LockUi()
        ui.rebind()
        tap(ui, "Мне действительно нужно выйти")
        tap(ui, "Запросить код у: ${contact.name}")
        val handle = TrustedExitStore(context).active(sessionId())
        assertNotNull("a challenge was created", handle)
        assertEquals("it is an end-session challenge", ExitKind.END_SESSION.name, handle!!.kind)
    }

    @Test
    fun point04_the_TEST_CODE_is_clearly_visible_on_the_lock_screen() {
        startTestLock()
        val (ui, code) = openExitWithCode()
        assertEquals("a 5-digit TEST CODE", 5, code.length)
        assertTrue(code.all { it.isDigit() })
        // Back on the lock screen the TEST CODE is shown, so the tester can read it before entering.
        tapBack(ui)
        val shown = firstText(ui.view.root) { it.startsWith("TEST CODE: ") }
        assertNotNull("the lock screen shows a TEST CODE line", shown)
        assertTrue("it shows the real code: ${shown!!.text}", shown.text.toString().contains(code))
        assertTrue("the TEST CODE is visible to the tester", visibleUpTo(shown, ui.view.root))
    }

    @Test
    fun point05_the_TEST_CODE_is_entered_through_the_same_keypad_UI() {
        startTestLock()
        val (ui, code) = openExitWithCode()
        typeOnKeypad(ui, code)
        assertEquals("every digit was entered on the on-screen keypad", code, ui.typed)
    }

    @Test
    fun point06_confirming_the_correct_code_unlocks_the_phone() {
        startTestLock()
        val (ui, code) = openExitWithCode()
        typeOnKeypad(ui, code)
        tap(ui, "Подтвердить")
        assertEquals("the phone is unlocked", LockState.Free, Mama.state(context))
    }

    @Test
    fun point07_three_wrong_codes_lock_the_exit_and_the_phone_stays_locked() {
        startTestLock()
        val (ui, code) = openExitWithCode()
        val wrong = code.map { '0' + (((it - '0') + 1) % 10) }.joinToString("")
        repeat(3) {
            typeOnKeypad(ui, wrong)
            tap(ui, "Подтвердить")
        }
        val handle = TrustedExitStore(context).active(sessionId())
        assertNotNull(handle)
        assertTrue("the exit is locked after three wrong codes", handle!!.lockedUntilMs != null)
        assertTrue("the phone is still locked — no code did not open it", Mama.state(context) is LockState.Locked)
    }

    @Test
    fun point08_a_restart_keeps_the_active_lock_and_the_test_exit_stays_available() {
        startTestLock()
        val (_, code) = openExitWithCode()
        // Simulate an app/device restart: the in-memory TEST CODE is gone; state is re-read from storage.
        TestCodeSink.clear()
        Mama.reconcile(context)
        assertTrue("the active lock is not lost after a restart", Mama.state(context) is LockState.Locked)
        assertNotNull("the exit challenge survived the restart", TrustedExitStore(context).active(sessionId()))
        // The safe exit still works: the issued code (held server-side / delivered to the contact) unlocks.
        val ui = LockUi()
        ui.rebind()
        tap(ui, "Мне действительно нужно выйти")
        typeOnKeypad(ui, code)
        tap(ui, "Подтвердить")
        assertEquals("the test exit still unlocks after a restart", LockState.Free, Mama.state(context))
    }

    @Test
    fun point09_a_TEST_build_caps_any_lock_at_15_minutes() {
        assertEquals("the cap is 15 minutes", Duration.ofMinutes(15), TestMode.DURATION)
        assertTrue("a TEST build applies the cap", FeatureFlags.TEST_MODE)
        val codes = object : CodeSource {
            override fun nextCode(length: Int) = "1".repeat(length)
            override fun nextSalt() = "salt"
        }
        val engine = LockEngine(CorePolicy(maxLockDuration = TestMode.DURATION), codes)
        val now = Instant.parse("2026-10-22T10:00:00Z")
        val plan = LockPlan(now, now + Duration.ofHours(8), zone)
        val session = (engine.create("gate", plan, contact, now) as LockEngine.CreateResult.Created).session
        assertEquals("an 8-hour lock is capped to 15 minutes", now + TestMode.DURATION, session.plan.end)
    }

    @Test
    fun point10_without_a_ready_backend_a_TEST_build_never_leaves_the_phone_locked() {
        TrustedExit.readiness = { false }
        val window = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), zone)
        val result = Mama.startSeries(context, SeriesKind.THREE, window, contact, SessionMode.SLEEP)
        assertEquals("the series is refused", Mama.SeriesStart.TrustedExitUnavailable, result)
        assertNull("no series was scheduled", Mama.series(context))
        assertEquals("the phone was never locked", LockState.Free, Mama.state(context))
    }
}
