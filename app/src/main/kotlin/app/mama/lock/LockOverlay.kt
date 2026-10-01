package app.mama.lock

import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.mama.core.ExitKind
import app.mama.core.LockEngine
import app.mama.core.Session
import app.mama.platform.Calls
import app.mama.platform.Mama
import app.mama.ui.BrandButtonView
import app.mama.ui.ButtonKind
import app.mama.ui.Kit
import app.mama.ui.MamaColors
import app.mama.ui.MamaType
import app.mama.ui.RingView
import app.mama.ui.Texts
import java.time.Duration
import java.time.Instant

/**
 * The lock surface: a full-screen window drawn above every app (including the
 * launcher and recents). Home/back gestures do not remove it; only the service
 * does, when the core says the phone is no longer locked.
 *
 * The code is typed on a built-in keypad, so no system keyboard is involved.
 */
class LockOverlay(private val service: LockService) {

    private val wm = service.getSystemService(WindowManager::class.java)!!
    private var root: View? = null
    private val input = StringBuilder()
    private var message: String? = null

    private val kit = Kit(service)

    private lateinit var title: TextView
    private lateinit var ring: RingView
    private lateinit var countdown: TextView
    private lateinit var until: TextView
    private lateinit var phrase: TextView
    private lateinit var status: TextView
    private lateinit var codeSection: LinearLayout
    private lateinit var codeHint: TextView
    private lateinit var codeBox: TextView
    private lateinit var requestSection: LinearLayout
    private lateinit var requestEnd: BrandButtonView
    private lateinit var requestEmergency: BrandButtonView
    private lateinit var callContact: BrandButtonView
    private lateinit var callSection: LinearLayout
    private lateinit var callInfo: TextView
    private lateinit var answer: BrandButtonView
    private lateinit var footer: TextView

    private var session: Session? = null

    fun show(session: Session, now: Instant) {
        this.session = session
        if (root == null) {
            val view = build()
            wm.addView(view, params())
            root = view
            view.requestFocus()
        }
        hideSystemBars()
        bind(session, now)
    }

    val isShowing: Boolean get() = root != null

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        input.clear()
        message = null
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
        val zone = s.plan.zone
        title.text = "MAMA · ${Texts.mode(s.plan.mode)}"
        countdown.text = Texts.countdown(Duration.between(now, s.plan.end))
        until.text = "до ${Texts.time(s.plan.end, zone)}"
        val total = Duration.between(s.plan.start, s.plan.end).toMillis().coerceAtLeast(1)
        val left = Duration.between(now, s.plan.end).toMillis().coerceIn(0, total)
        ring.progress = 1f - left.toFloat() / total
        phrase.text = Texts.lockPhrase(s.id, Duration.between(s.plan.start, now).toMinutes())
        callContact.text = "Позвонить: ${s.contact.name}"
        val call = LockService.callState
        callSection.visibility = if (call != Calls.CallState.NONE) View.VISIBLE else View.GONE
        callInfo.text = if (call == Calls.CallState.RINGING) "Входящий звонок" else "Идёт звонок"
        answer.visibility = if (call == Calls.CallState.RINGING) View.VISIBLE else View.GONE
        footer.text = "MAMA ${Texts.version(service)} · " + if (GuardService.running) {
            "защита включена"
        } else {
            "защита ВЫКЛЮЧЕНА (Настройки → Спец. возможности → MAMA)"
        }

        val challenge = s.challenge
        codeSection.visibility = if (challenge != null) View.VISIBLE else View.GONE
        requestSection.visibility = if (challenge == null) View.VISIBLE else View.GONE
        if (challenge != null) {
            val purpose = when (challenge.kind) {
                ExitKind.END_SESSION -> "досрочное завершение"
                ExitKind.EMERGENCY -> "экстренный доступ на ${Texts.duration(Mama.policy.emergencyPass)}"
            }
            codeHint.text = "Код отправлен: ${s.contact.name} ($purpose).\n" +
                "Попыток: ${challenge.attemptsLeft} · действует до ${Texts.time(challenge.expiresAt, zone)}"
        }
        codeBox.text = (0 until Mama.policy.codeLength)
            .joinToString("  ") { i -> if (i < input.length) input[i].toString() else "·" }

        val cooldown = s.codeCooldownUntil
        requestEnd.isEnabled = cooldown == null
        requestEmergency.isEnabled = cooldown == null &&
            s.emergencyPassesUsed < Mama.policy.maxEmergencyPasses
        status.text = message ?: cooldown?.let {
            "Новый код можно запросить в ${Texts.time(it, zone)}"
        } ?: ""
        status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun rebind() {
        val s = Mama.session(service) ?: return
        bind(s, Mama.trustedNow(service))
    }

    private fun request(kind: ExitKind) {
        val s = session ?: return
        message = when (val r = Mama.requestCode(service, kind)) {
            is Mama.CodeRequest.Sent -> "SMS с кодом ушло: ${r.contact.name}. Попросите продиктовать код."
            is Mama.CodeRequest.Rejected -> Texts.codeRequestError(r.reason, r.retryAt, s.plan.zone)
            Mama.CodeRequest.SmsFailed -> "Не удалось отправить SMS. Проверьте связь и попробуйте ещё раз."
        }
        input.clear()
        rebind()
        service.onStateChanged()
    }

    private fun submit() {
        val s = session ?: return
        if (input.length != Mama.policy.codeLength) return
        val result = Mama.submitCode(service, input.toString())
        input.clear()
        message = when (result) {
            is LockEngine.CodeResult.Accepted -> null
            is LockEngine.CodeResult.Wrong -> "Неверный код. Осталось попыток: ${result.attemptsLeft}"
            is LockEngine.CodeResult.Exhausted ->
                "Код больше не действует. Новый можно запросить в ${Texts.time(result.retryAt, s.plan.zone)}"
            is LockEngine.CodeResult.NoActiveCode -> "Код истёк. Запросите новый."
            null -> null
        }
        service.onStateChanged()
        if (root != null) rebind()
    }

    private fun press(key: String) {
        when (key) {
            DEL -> if (input.isNotEmpty()) input.deleteCharAt(input.length - 1)
            OK -> {
                submit()
                return
            }
            else -> if (input.length < Mama.policy.codeLength) input.append(key)
        }
        rebind()
    }

    // ---- view building ----

    private fun build(): View {
        val ctx = service
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(44), dp(24), dp(28))
        }
        title = kit.text("", MamaType.OVERLINE, MamaColors.EmeraldPrimary, center = true).also { column.addView(it, kit.fill()) }

        // Calls are handled right here: the lock never steps aside for a call screen.
        callSection = kit.card(18).apply { gravity = Gravity.CENTER_HORIZONTAL }
        callInfo = kit.text("", MamaType.H2, MamaColors.EmeraldPrimary, center = true).also { callSection.addView(it, kit.fill()) }
        answer = kit.brandButton("Ответить") { Calls.answer(service) }
        callSection.addView(answer, kit.gap(14))
        callSection.addView(kit.brandButton("Завершить звонок", ButtonKind.DANGER) { Calls.hangUp(service) }, kit.gap(10))
        column.addView(callSection, kit.gap(16))

        // Timer inside a quiet progress ring.
        val dial = FrameLayout(ctx)
        ring = RingView(ctx)
        dial.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        inner.addView(kit.text("осталось", MamaType.OVERLINE, MamaColors.TextSecondary, center = true), kit.fill())
        countdown = kit.text("", MamaType.DIGITS_L, MamaColors.TextPrimary, center = true).also { inner.addView(it, kit.gap(6)) }
        until = kit.caption("", center = true).also { inner.addView(it, kit.gap(6)) }
        dial.addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        column.addView(dial, LinearLayout.LayoutParams(dp(250), dp(250)).apply { topMargin = dp(24) })

        phrase = kit.text("", MamaType.QUOTE, MamaColors.TextPrimary, center = true).also {
            it.setPadding(dp(12), 0, dp(12), 0)
            column.addView(it, kit.gap(26))
        }
        status = kit.text("", MamaType.CAPTION, MamaColors.WarningInk, center = true).also {
            it.background = kit.shape(MamaColors.WarningSoft, 18)
            it.setPadding(dp(16), dp(10), dp(16), dp(10))
            column.addView(it, kit.gap(18))
        }

        codeSection = kit.card(18).apply { gravity = Gravity.CENTER_HORIZONTAL }
        codeHint = kit.caption("", center = true).also { codeSection.addView(it, kit.fill()) }
        codeBox = kit.text("", MamaType.DIGITS, MamaColors.TextPrimary, center = true).also {
            it.typeface = Typeface.MONOSPACE
            it.setPadding(0, dp(12), 0, dp(8))
            codeSection.addView(it, kit.fill())
        }
        codeSection.addView(keypad(), kit.wrap().apply { gravity = Gravity.CENTER_HORIZONTAL })
        column.addView(codeSection, kit.gap(20))

        requestSection = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        requestEnd = kit.brandButton("Попросить код: завершить досрочно", ButtonKind.SECONDARY) { request(ExitKind.END_SESSION) }
        requestEmergency = kit.brandButton(
            "Попросить код: экстренный доступ на ${Texts.duration(Mama.policy.emergencyPass)}",
            ButtonKind.SECONDARY,
        ) { request(ExitKind.EMERGENCY) }
        requestSection.addView(requestEnd, kit.fill())
        requestSection.addView(requestEmergency, kit.gap(10))
        column.addView(requestSection, kit.gap(28))

        callContact = kit.brandButton("Позвонить", ButtonKind.SECONDARY) {
            val s = session ?: return@brandButton
            if (!Calls.callContact(service, s.contact.phone)) {
                message = "Нет разрешения на звонки. Позвонить можно через экстренный вызов."
                rebind()
            }
        }
        column.addView(callContact, kit.gap(10))
        column.addView(kit.brandButton("Экстренный вызов ${Calls.EMERGENCY_NUMBER}", ButtonKind.DANGER) {
            Calls.callEmergency(service)
        }, kit.gap(10))
        footer = kit.text("", MamaType.CAPTION, MamaColors.OliveSoft, center = true).also {
            it.textSize = 11f
            column.addView(it, kit.gap(24))
        }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(column)
        }
        return FrameLayout(ctx).apply {
            setBackgroundColor(MamaColors.BackgroundPrimary)
            addView(scroll)
            isFocusable = true
            isFocusableInTouchMode = true
            // Swallow back; home/recents are covered because this window stays on top.
            setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        }
    }

    private fun keypad(): View {
        val grid = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(DEL, "0", OK))
            .forEach { row ->
                val line = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
                row.forEach { key ->
                    val ok = key == OK
                    val b = kit.text(key, MamaType.H2, if (ok) MamaColors.White else MamaColors.TextPrimary, center = true).apply {
                        gravity = Gravity.CENTER
                        textSize = 24f
                        val fill = if (ok) MamaColors.EmeraldPrimary else MamaColors.BackgroundPrimary
                        background = kit.pressable(kit.shape(fill, 20, if (ok) null else MamaColors.BorderSoft), 20)
                        isClickable = true
                        setOnClickListener { press(key) }
                    }
                    line.addView(b, LinearLayout.LayoutParams(dp(80), dp(60)).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
                }
                grid.addView(line)
            }
        return grid
    }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val DEL = "⌫"
        const val OK = "OK"
    }
}
