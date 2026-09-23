package app.mama.lock

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.mama.core.ExitKind
import app.mama.core.LockEngine
import app.mama.core.Session
import app.mama.platform.Calls
import app.mama.platform.Mama
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

    private lateinit var title: TextView
    private lateinit var countdown: TextView
    private lateinit var until: TextView
    private lateinit var status: TextView
    private lateinit var codeSection: LinearLayout
    private lateinit var codeHint: TextView
    private lateinit var codeBox: TextView
    private lateinit var requestSection: LinearLayout
    private lateinit var requestEnd: Button
    private lateinit var requestEmergency: Button
    private lateinit var callContact: Button

    private var session: Session? = null

    fun show(session: Session, now: Instant) {
        this.session = session
        if (root == null) {
            val view = build()
            wm.addView(view, params())
            root = view
            view.requestFocus()
        }
        bind(session, now)
    }

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
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.OPAQUE,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun bind(s: Session, now: Instant) {
        val zone = s.plan.zone
        title.text = "MAMA · ${Texts.mode(s.plan.mode)}"
        countdown.text = Texts.countdown(Duration.between(now, s.plan.end))
        until.text = "до ${Texts.time(s.plan.end, zone)} · ${Texts.zone(zone, now)}"
        callContact.text = "Позвонить: ${s.contact.name}"

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
            .joinToString(" ") { i -> if (i < input.length) input[i].toString() else "_" }

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
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }
        title = text(18f, MUTED).also(column::addView)
        countdown = text(52f, FG, bold = true).also { it.setPadding(0, dp(24), 0, 0); column.addView(it) }
        until = text(15f, MUTED).also(column::addView)
        status = text(15f, ACCENT).also { it.setPadding(0, dp(20), 0, 0); column.addView(it) }

        codeSection = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(24), 0, 0)
        }
        codeHint = text(15f, MUTED).also(codeSection::addView)
        codeBox = text(34f, FG, bold = true).also {
            it.typeface = Typeface.MONOSPACE
            it.setPadding(0, dp(12), 0, dp(12))
            codeSection.addView(it)
        }
        codeSection.addView(keypad())
        column.addView(codeSection)

        requestSection = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(32), 0, 0)
        }
        requestEnd = button("Попросить код: завершить досрочно") { request(ExitKind.END_SESSION) }
        requestEmergency = button(
            "Попросить код: экстренный доступ на ${Texts.duration(Mama.policy.emergencyPass)}",
        ) { request(ExitKind.EMERGENCY) }
        requestSection.addView(requestEnd)
        requestSection.addView(requestEmergency)
        column.addView(requestSection)

        callContact = button("Позвонить") { session?.let { Calls.dial(service, it.contact.phone) } }
        column.addView(spacer(24))
        column.addView(callContact)
        column.addView(button("Экстренный вызов ${Calls.EMERGENCY_NUMBER}", danger = true) {
            Calls.dial(service, Calls.EMERGENCY_NUMBER)
        })

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(column)
        }
        return FrameLayout(ctx).apply {
            setBackgroundColor(BG)
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
                    val b = Button(service).apply {
                        text = key
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                        setTextColor(FG)
                        background = rounded(if (key == OK) ACCENT_BG else KEY_BG)
                        setOnClickListener { press(key) }
                    }
                    line.addView(b, LinearLayout.LayoutParams(dp(84), dp(64)).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
                }
                grid.addView(line)
            }
        return grid
    }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, danger: Boolean = false, onClick: () -> Unit) = Button(service).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(if (danger) DANGER else FG)
        background = rounded(KEY_BG)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, dp(6), 0, dp(6)) }
        minHeight = dp(52)
    }

    private fun spacer(heightDp: Int) = View(service).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val DEL = "⌫"
        const val OK = "OK"
        val BG = Color.rgb(0x0E, 0x11, 0x16)
        val FG = Color.rgb(0xEE, 0xF0, 0xF3)
        val MUTED = Color.rgb(0x9A, 0xA3, 0xAE)
        val ACCENT = Color.rgb(0x8F, 0xB8, 0xFF)
        val ACCENT_BG = Color.rgb(0x2B, 0x4A, 0x80)
        val KEY_BG = Color.rgb(0x1C, 0x21, 0x2A)
        val DANGER = Color.rgb(0xFF, 0x8A, 0x80)
    }
}
