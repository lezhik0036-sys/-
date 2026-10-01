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
import app.mama.core.LockEngine
import app.mama.core.Session
import app.mama.core.TestMode
import app.mama.platform.Calls
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
        val challenge = s.challenge
        val resendAt = s.codeCooldownUntil ?: challenge?.expiresAt
        view.bind(
            LockModel(
                now = now,
                start = s.plan.start,
                end = s.plan.end,
                zone = s.plan.zone,
                contactName = s.contact.name,
                contactPhone = Texts.phone(s.contact.phone),
                dayLabel = dayLabel(s),
                phrase = Texts.lockPhrase(s.id, Duration.between(s.plan.start, now).toMinutes()),
                promise = Promise.get(service),
                call = LockService.callState,
                exitOpen = exitOpen,
                codeActive = challenge != null && challenge.kind == ExitKind.END_SESSION,
                attemptsLeft = challenge?.attemptsLeft ?: Mama.policy.maxAttempts,
                resendAt = resendAt?.takeIf { it.isAfter(now) },
                codeLength = Mama.policy.codeLength,
                input = input.toString(),
                message = message,
                diagnostics = if (FeatureFlags.TEST_MODE_ENABLED) diagnostics() else null,
            ),
        )
    }

    /** "День 3 из 7", "FLEX-период", "Тестовый режим · 15 минут". */
    private fun dayLabel(s: Session): String {
        val series = Mama.series(service)
        if (series != null && series.active) {
            return Texts.dayOf(minOf(series.completedPeriods + 1, series.kind.periods), series.kind.periods)
        }
        val flex = Mama.entitlements(service).flex
        if (flex != null && flex.currentSessionId == s.id) return "FLEX · день ${flex.used} из 7"
        if (Duration.between(s.plan.start, s.plan.end) == TestMode.DURATION) return "Тестовый режим · 15 минут"
        return Texts.mode(s.plan.mode)
    }

    /** Test builds: what the guard saw over the system lock screen, for tuning on real phones. */
    private fun diagnostics(): String = "MAMA ${Texts.version(service)} · " + if (GuardService.running) {
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

    override fun emergencyCall() = Calls.callEmergency(service)

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
            Mama.CodeRequest.SmsFailed -> "Не удалось отправить SMS. Проверьте связь и попробуйте ещё раз."
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
            is LockEngine.CodeResult.Accepted -> null
            is LockEngine.CodeResult.Wrong -> "Неверный код."
            is LockEngine.CodeResult.Exhausted ->
                "Попытки закончились. Новый код можно запросить в ${Texts.time(result.retryAt, s.plan.zone)}."
            is LockEngine.CodeResult.NoActiveCode -> "Код больше не действует. Запросите новый."
            null -> null
        }
        service.onStateChanged()
        if (root != null) rebind()
    }

    override fun answer() = Calls.answer(service)

    override fun hangUp() = Calls.hangUp(service)
}
