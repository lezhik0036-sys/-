package app.mama.lock

import android.graphics.PixelFormat
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import app.mama.billing.FeatureFlags
import app.mama.core.ExitKind
import app.mama.trustedexit.TestCodeSink
import app.mama.trustedexit.TrustedExitStore
import app.mama.core.Session
import app.mama.core.TestMode
import app.mama.motivation.MotivationCatalog
import app.mama.motivation.Placement
import app.mama.platform.Calls
import app.mama.platform.EmergencyCalls
import app.mama.platform.Mama
import app.mama.platform.Promise
import app.mama.ui.Texts
import java.time.Duration
import java.time.Instant

/**
 * The lock surface: a full-screen window drawn above every app (including the
 * launcher and recents). Home/back gestures do not remove it; only the service
 * does, when the core says the phone is no longer locked.
 *
 * What it looks like is [LockScreenView]; this class owns the window, the
 * typed code and the calls into MAMA Core. The code is typed on a built-in
 * keypad, so no system keyboard is involved.
 */
class LockOverlay(private val service: LockService) : LockActions {

    private val wm = service.getSystemService(WindowManager::class.java)!!
    private var root: View? = null
    private var screen: LockScreenView? = null
    private val input = StringBuilder()
    private var message: String? = null
    private var exitOpen = false
    private var emergencyOpen = false
    private var emergency: EmergencyCalls.Availability? = null
    private var emergencyMessage: String? = null

    private var session: Session? = null

    fun show(session: Session, now: Instant) {
        this.session = session
        if (root == null) {
            val view = LockScreenView(service, this)
            val frame = view.root.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                // Swallow back; home/recents are covered because this window stays on top.
                setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
            }
            wm.addView(frame, params())
            root = frame
            screen = view
            frame.requestFocus()
        }
        hideSystemBars()
        bind(session, now)
    }

    val isShowing: Boolean get() = root != null

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        screen = null
        input.clear()
        message = null
        exitOpen = false
        emergencyOpen = false
        emergencyMessage = null
    }

    private fun params() = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.OPAQUE,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Cover the status and navigation bar areas too.
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** Full screen: no navigation buttons and no status bar while locked. */
    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        val view = root ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.windowInsetsController?.let {
                it.hide(WindowInsets.Type.navigationBars() or WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            view.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    private fun bind(s: Session, now: Instant) {
        val view = screen ?: return
        // Server-authoritative Trusted Exit: the device holds only the handle
        // (challengeId, expiry, attempts), never the code. Expiry, attempts and
        // the resend countdown come from it.
        val handle = TrustedExitStore(service).active(s.id)
        val lockedUntil = handle?.lockedUntilMs?.let(Instant::ofEpochMilli)
        val expiresAt = handle?.expiresAtMs?.let(Instant::ofEpochMilli)
        val codeActive = handle != null && handle.kind == ExitKind.END_SESSION.name &&
            lockedUntil == null && expiresAt != null && expiresAt.isAfter(now)
        val resendAt = (lockedUntil ?: expiresAt?.takeIf { codeActive })?.takeIf { it.isAfter(now) }
        view.bind(
            LockModel(
                now = now,
                start = s.plan.start,
                end = s.plan.end,
                zone = s.plan.zone,
                contactName = s.contact.name,
                contactPhone = Texts.phone(s.contact.phone),
                dayLabel = dayLabel(s),
                phrase = lockPhrase(s),
                promise = Promise.get(service),
                call = LockService.callState,
                exitOpen = exitOpen,
                codeActive = codeActive,
                attemptsLeft = handle?.attemptsLeft ?: Mama.policy.maxAttempts,
                resendAt = resendAt,
                codeLength = Mama.policy.codeLength,
                input = input.toString(),
                message = message,
                diagnostics = if (FeatureFlags.TEST_MODE_ENABLED) diagnostics(handle?.challengeId, codeActive) else null,
                emergencyOpen = emergencyOpen,
                emergencyServices = (emergency as? EmergencyCalls.Availability.Numbers)?.services.orEmpty(),
                emergencyNote = emergencyMessage ?: if (emergency is EmergencyCalls.Availability.NoTelephony) NO_TELEPHONY else null,
                emergencyCallActive = EmergencyCalls.session(service)?.callStarted == true,
            ),
        )
    }

    /**
     * One calm phrase for the whole lock (the same on every tick): sleep and
     * quiet for a lock that starts in the evening or at night, focus for a
     * daytime one. From [MotivationCatalog].
     */
    private fun lockPhrase(s: Session): String {
        val hour = s.plan.start.atZone(s.plan.zone).hour
        val placement = if (hour >= 17 || hour < 5) Placement.LOCK_NIGHT else Placement.LOCK_DAY
        return MotivationCatalog.pick(placement, s.id, maxLength = 60).text
    }

    /** "День 3 из 7", "FLEX-период", "Тестовый режим · 15 минут". */
    private fun dayLabel(s: Session): String {
        val series = Mama.series(service)
        if (series != null && series.inProgress) {
            return Texts.dayOf(minOf(series.completedPeriods + 1, series.kind.periods), series.kind.periods)
        }
        val flex = Mama.entitlements(service).flex
        if (flex != null && flex.currentSessionId == s.id) return "FLEX · день ${flex.used} из 7"
        if (Duration.between(s.plan.start, s.plan.end) == TestMode.DURATION) return "Тестовый режим · 15 минут"
        return Texts.mode(s.plan.mode)
    }

    /**
     * Test builds: the guaranteed-exit TEST CODE and the 15-minute note on top
     * (TEST_MODE only, never a release build), then what the guard saw over the
     * system lock screen, for tuning on real phones.
     */
    private fun diagnostics(challengeId: String?, codeActive: Boolean): String = listOfNotNull(
        // The code the (simulated) delivery would have sent to the contact: shown here so a test
        // device always has a way out without a real SMS. codeFor returns null outside a TEST build.
        (if (codeActive) TestCodeSink.codeFor(challengeId) else null)?.let { "TEST CODE: $it" },
        if (FeatureFlags.TEST_MODE) "Тестовый режим — блокировка максимум 15 минут" else null,
        guardDiagnostics(),
    ).joinToString("\n")

    private fun guardDiagnostics(): String = "MAMA ${Texts.version(service)} · " + if (GuardService.running) {
        "защита включена · выкл. экрана ${GuardService.screenOffs}" +
            (GuardService.keyguardApps.takeIf { it.isNotEmpty() }?.let { " · приложения: $it" } ?: "") +
            "\nэкран блокировки: ${GuardService.keyguardIds.joinToString(" ")}"
    } else {
        "защита ВЫКЛЮЧЕНА (Настройки → Спец. возможности → MAMA)"
    }

    private fun rebind() {
        val s = Mama.session(service) ?: return
        session = s
        bind(s, Mama.trustedNow(service))
    }

    // ---------------- LockActions ----------------

    override fun openEmergency() {
        emergency = EmergencyCalls.services(service)
        emergencyMessage = null
        emergencyOpen = true
        rebind()
    }

    override fun closeEmergency() {
        emergencyOpen = false
        emergencyMessage = null
        rebind()
    }

    override fun dialEmergency(number: String) {
        when (EmergencyCalls.start(service, number)) {
            EmergencyCalls.Start.Opened -> {
                emergencyOpen = false
                // Step aside right away for the dialer.
                service.render()
            }
            EmergencyCalls.Start.NoTelephony -> {
                emergencyMessage = NO_TELEPHONY
                rebind()
            }
            EmergencyCalls.Start.NoDialer -> {
                emergencyMessage = "Не удалось открыть набор номера. Заблокируй телефон кнопкой питания и нажми " +
                    "«Экстренный вызов» на экране блокировки телефона."
                rebind()
            }
        }
    }

    override fun returnToCall() {
        EmergencyCalls.returnToCall(service)
        service.render()
    }

    override fun openExit() {
        exitOpen = true
        message = null
        rebind()
    }

    override fun closeExit() {
        exitOpen = false
        input.clear()
        message = null
        rebind()
    }

    override fun requestCode() {
        val s = session ?: return
        message = when (val r = Mama.requestCode(service, ExitKind.END_SESSION)) {
            is Mama.CodeRequest.Sent -> null
            is Mama.CodeRequest.Rejected -> Texts.codeRequestError(r.reason, r.retryAt, s.plan.zone)
            Mama.CodeRequest.SmsFailed -> "Не удалось отправить код. Попробуй ещё раз."
            Mama.CodeRequest.Offline ->
                "Нет подключения к интернету. Для отправки кода доверенному контакту MAMA нужен интернет."
        }
        input.clear()
        rebind()
        service.onStateChanged()
    }

    override fun callContact() {
        val s = session ?: return
        if (!Calls.callContact(service, s.contact.phone)) {
            message = "Нет разрешения на звонки."
            rebind()
        }
    }

    override fun key(k: String) {
        when (k) {
            LockScreenView.DEL -> if (input.isNotEmpty()) input.deleteCharAt(input.length - 1)
            else -> if (input.length < Mama.policy.codeLength) input.append(k)
        }
        message = null
        rebind()
    }

    override fun submit() {
        val s = session ?: return
        if (input.length != Mama.policy.codeLength) return
        val result = Mama.submitCode(service, input.toString())
        input.clear()
        message = when (result) {
            Mama.SubmitResult.Accepted -> null
            is Mama.SubmitResult.Wrong -> "Неверный код."
            is Mama.SubmitResult.Locked ->
                "Попытки закончились. Новый код можно запросить в ${Texts.time(result.retryAt, s.plan.zone)}."
            Mama.SubmitResult.NoActiveCode -> "Код больше не действует. Запроси новый."
            Mama.SubmitResult.Offline ->
                "Нет подключения к интернету. Проверь связь и попробуй ещё раз."
        }
        service.onStateChanged()
        if (root != null) rebind()
    }

    override fun answer() = Calls.answer(service)

    override fun hangUp() = Calls.hangUp(service)

    private companion object {
        const val NO_TELEPHONY = "На этом устройстве нет телефонной связи — позвонить отсюда нельзя."
    }
}
