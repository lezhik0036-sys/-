package app.mama.lock

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.mama.platform.Calls
import app.mama.ui.BrandButtonView
import app.mama.ui.Icon
import app.mama.ui.Kit
import app.mama.ui.MamaColors
import app.mama.ui.MamaFonts
import app.mama.ui.MamaType
import app.mama.ui.PhotoBackground
import app.mama.ui.RingView
import app.mama.ui.Scene
import app.mama.ui.Texts
import app.mama.ui.Tone
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** What the lock screen shows; built by [LockOverlay] (or a preview) every tick. */
data class LockModel(
    val now: Instant,
    val start: Instant,
    val end: Instant,
    val zone: ZoneId,
    val contactName: String,
    val contactPhone: String,
    /** "День 3 из 7", "Тестовый режим", "FLEX-период"… */
    val dayLabel: String,
    val phrase: String,
    /** The user's own reason, or empty. */
    val promise: String,
    val call: Calls.CallState,
    /** Trusted Exit page open. */
    val exitOpen: Boolean,
    /** A code was sent and can be typed. */
    val codeActive: Boolean,
    val attemptsLeft: Int,
    /** When a new code may be requested (null = now). */
    val resendAt: Instant?,
    val codeLength: Int,
    val input: String,
    val message: String?,
    val diagnostics: String?,
)

interface LockActions {
    fun emergencyCall()
    fun openExit()
    fun closeExit()
    fun requestCode()
    fun callContact()
    fun key(k: String)
    fun submit()
    fun answer()
    fun hangUp()
}

/**
 * The active lock screen (design reference screens 8 and 9): dark night
 * landscape, phrase, glowing countdown ring, and only two actions —
 * "Экстренный звонок" and "Доверенный выход". The Trusted Exit page is a
 * light panel shown over it: first the user's reason and a pause, then the
 * 5-digit code entry. Pure view code: all behaviour goes through [LockActions].
 */
class LockScreenView(private val context: Context, private val actions: LockActions) {
    private val night = Kit(context, night = true)
    private val light = Kit(context)

    val root: FrameLayout = FrameLayout(context)

    // Main page.
    private lateinit var phrase: TextView
    private lateinit var subline: TextView
    private lateinit var ring: RingView
    private lateinit var countdown: TextView
    private lateinit var dayLabel: TextView
    private lateinit var callCard: LinearLayout
    private lateinit var callInfo: TextView
    private lateinit var answer: BrandButtonView
    private lateinit var diagnostics: TextView

    // Exit page.
    private lateinit var exitPage: View
    private lateinit var intro: LinearLayout
    private lateinit var promiseBlock: LinearLayout
    private lateinit var promiseText: TextView
    private lateinit var requestButton: BrandButtonView
    private lateinit var callContactButton: BrandButtonView
    private lateinit var codePage: LinearLayout
    private lateinit var codeHint: TextView
    private lateinit var boxes: List<TextView>
    private lateinit var attempts: TextView
    private lateinit var confirm: BrandButtonView
    private lateinit var resend: BrandButtonView
    private lateinit var message: TextView
    private lateinit var introMessage: TextView

    init {
        root.background = PhotoBackground(context, Scene.NIGHT)
        root.addView(buildMain(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        exitPage = buildExit()
        root.addView(exitPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun dp(v: Int) = night.dp(v)

    // ---------------- main page ----------------

    private fun buildMain(): View {
        val k = night
        val col = k.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(52), dp(24), dp(28))
        }
        col.addView(k.text("MAMA", MamaType.WORDMARK, MamaColors.OnNight, center = true), k.centered())

        callCard = k.card(16).apply { gravity = Gravity.CENTER_HORIZONTAL }
        callInfo = k.text("", MamaType.H2, MamaColors.OnNight, center = true).also { callCard.addView(it, k.fill()) }
        answer = k.primaryButton("Ответить") { actions.answer() }
        callCard.addView(answer, k.gap(12))
        callCard.addView(k.secondaryButton("Завершить звонок") { actions.hangUp() }, k.gap(10))
        col.addView(callCard, k.gap(16))

        phrase = k.text("", MamaType.QUOTE, MamaColors.OnNight, center = true).also { col.addView(it, k.gap(22)) }
        subline = k.text("", MamaType.BODY, MamaColors.OnNightMuted, center = true).also { col.addView(it, k.gap(10)) }

        // Ring with lock icon, "До окончания", countdown, day, moon.
        val dial = FrameLayout(context)
        ring = RingView(context)
        dial.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val inner = k.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        inner.addView(k.icon(Icon.LOCK, MamaColors.OnNight, 26), k.centered())
        inner.addView(k.text("До окончания", MamaType.CAPTION, MamaColors.OnNightMuted, center = true), k.centered(10))
        countdown = k.text("", MamaType.DIGITS_L, MamaColors.OnNight, center = true).also { inner.addView(it, k.centered(8)) }
        dayLabel = k.text("", MamaType.CAPTION, MamaColors.OnNightMuted, center = true).also { inner.addView(it, k.centered(8)) }
        inner.addView(k.icon(Icon.MOON, MamaColors.OnNight, 20), k.centered(14))
        dial.addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        col.addView(dial, LinearLayout.LayoutParams(dp(268), dp(268)).apply { topMargin = dp(26); gravity = Gravity.CENTER_HORIZONTAL })

        col.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))

        val actionsRow = k.row()
        actionsRow.addView(actionButton(Icon.PHONE, "Экстренный\nзвонок") { actions.emergencyCall() }, k.weight().apply { rightMargin = dp(6) })
        actionsRow.addView(actionButton(Icon.LOCK, "Доверенный\nвыход") { actions.openExit() }, k.weight().apply { leftMargin = dp(6) })
        col.addView(actionsRow, k.gap(26))
        diagnostics = k.text("", MamaType.SMALL, Color.argb(0x80, 0xB4, 0xBF, 0xB5), center = true).also {
            it.textSize = 9.5f
            col.addView(it, k.gap(14))
        }
        return ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(col)
        }
    }

    private fun actionButton(icon: Icon, label: String, onClick: () -> Unit): View = night.row().apply {
        setPadding(dp(16), dp(14), dp(14), dp(14))
        minimumHeight = dp(64)
        background = night.pressable(night.shape(MamaColors.NightGlass, 18, MamaColors.NightLine), 18)
        addView(night.icon(icon, MamaColors.OnNight, 22))
        addView(night.text(label, MamaType.CAPTION, MamaColors.OnNight).apply { typeface = MamaFonts.ui(context, 700) }, night.weight().apply { leftMargin = dp(12) })
        isClickable = true
        setOnClickListener { onClick() }
    }

    // ---------------- Trusted Exit page ----------------

    private fun buildExit(): View {
        val k = light
        val col = k.column().apply { setPadding(dp(22), dp(40), dp(22), dp(28)) }
        col.addView(k.stepHeader(null, 0) { actions.closeExit() })

        // Intro: reason + pause.
        intro = k.column()
        intro.addView(k.sectionHeader("Досрочный выход", null, center = true), k.gap(18))
        promiseBlock = k.column()
        promiseBlock.addView(k.text("Ты начал эту серию потому что:", MamaType.CAPTION, MamaColors.TextSecondary, center = true), k.fill())
        promiseText = k.text("", MamaType.H2, MamaColors.Emerald, center = true).apply {
            typeface = MamaFonts.wordmark(context, 600)
            textSize = 23f
        }
        promiseBlock.addView(k.card(20).apply { addView(promiseText, k.fill()) }, k.gap(12))
        intro.addView(promiseBlock, k.gap(20))
        intro.addView(k.infoCard("Не принимай большое решение из-за минутного импульса.", Icon.HEART, Tone.BRAND), k.gap(16))
        introMessage = k.text("", MamaType.CAPTION, MamaColors.Warning, center = true)
        intro.addView(introMessage, k.gap(12))
        intro.addView(k.primaryButton("Вернуться к блокировке") { actions.closeExit() }, k.gap(20))
        requestButton = k.secondaryButton("Запросить код") { actions.requestCode() }
        intro.addView(requestButton, k.gap(10))
        callContactButton = k.textButton("Позвонить") { actions.callContact() }
        intro.addView(callContactButton, k.gap(6))
        col.addView(intro, k.fill())

        // Code entry (reference screen 9).
        codePage = k.column()
        codePage.addView(k.sectionHeader("Доверенный выход", null, center = true), k.gap(14))
        codeHint = k.text("", MamaType.BODY, MamaColors.TextSecondary, center = true).also { codePage.addView(it, k.gap(10)) }
        val boxRow = k.row().apply { gravity = Gravity.CENTER }
        boxes = (0 until 5).map {
            k.text("", MamaType.DIGITS, MamaColors.Graphite, center = true).apply {
                gravity = Gravity.CENTER
                background = k.shape(MamaColors.PureCard, 12, MamaColors.Border, 1.5f)
            }.also { boxRow.addView(it, LinearLayout.LayoutParams(dp(50), dp(60)).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        }
        codePage.addView(boxRow, k.gap(20))
        attempts = k.text("", MamaType.CAPTION, MamaColors.TextSecondary, center = true).also { codePage.addView(it, k.gap(14)) }
        message = k.text("", MamaType.CAPTION, MamaColors.Warning, center = true).also { codePage.addView(it, k.gap(8)) }
        codePage.addView(keypad(k), k.centered(10))
        confirm = k.primaryButton("Подтвердить") { actions.submit() }
        codePage.addView(confirm, k.gap(16))
        resend = k.textButton("Отправить код ещё раз") { actions.requestCode() }
        codePage.addView(resend, k.gap(4))
        codePage.addView(k.textButton("Отмена") { actions.closeExit() }, k.gap(0))
        col.addView(codePage, k.fill())

        return ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(MamaColors.WarmIvory)
            addView(col)
        }
    }

    private fun keypad(k: Kit): View {
        val grid = k.column()
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", DEL)).forEach { r ->
            val line = k.row()
            r.forEach { key ->
                val cell: View = if (key.isEmpty()) {
                    View(context)
                } else {
                    k.text(key, MamaType.H2, MamaColors.Graphite, center = true).apply {
                        gravity = Gravity.CENTER
                        textSize = if (key == DEL) 20f else 24f
                        background = k.pressable(k.shape(MamaColors.PureCard, 16, MamaColors.Border), 16)
                        isClickable = true
                        setOnClickListener { actions.key(key) }
                    }
                }
                line.addView(cell, LinearLayout.LayoutParams(dp(84), dp(54)).apply { setMargins(dp(5), dp(4), dp(5), dp(4)) })
            }
            grid.addView(line)
        }
        return grid
    }

    // ---------------- binding ----------------

    fun bind(m: LockModel) {
        val left = Duration.between(m.now, m.end)
        val (h, mi, s) = Texts.hms(left)
        countdown.text = "$h:$mi:$s"
        val total = Duration.between(m.start, m.end).toMillis().coerceAtLeast(1)
        ring.progress = 1f - left.toMillis().coerceIn(0, total).toFloat() / total
        dayLabel.text = m.dayLabel
        phrase.text = m.phrase
        subline.text = if (m.promise.isNotBlank()) "«${m.promise}»" else Texts.LOCK_SUBLINE

        callCard.visibility = if (m.call != Calls.CallState.NONE) View.VISIBLE else View.GONE
        callInfo.text = if (m.call == Calls.CallState.RINGING) "Входящий звонок" else "Идёт звонок"
        answer.visibility = if (m.call == Calls.CallState.RINGING) View.VISIBLE else View.GONE
        diagnostics.text = m.diagnostics.orEmpty()
        diagnostics.visibility = if (m.diagnostics.isNullOrEmpty()) View.GONE else View.VISIBLE

        exitPage.visibility = if (m.exitOpen) View.VISIBLE else View.GONE
        intro.visibility = if (m.codeActive) View.GONE else View.VISIBLE
        codePage.visibility = if (m.codeActive) View.VISIBLE else View.GONE
        promiseBlock.visibility = if (m.promise.isBlank()) View.GONE else View.VISIBLE
        promiseText.text = "«${m.promise}»"
        requestButton.text = "Запросить код у: ${m.contactName}"
        val canRequest = m.resendAt == null || !m.now.isBefore(m.resendAt)
        requestButton.isEnabled = canRequest
        callContactButton.text = "Позвонить: ${m.contactName}"
        introMessage.text = m.message.orEmpty()
        introMessage.visibility = if (m.message.isNullOrEmpty() || m.codeActive) View.GONE else View.VISIBLE

        codeHint.text = "Введите код из ${m.codeLength} цифр, который отправлен доверенному контакту: " +
            "${m.contactName} ${m.contactPhone}"
        boxes.forEachIndexed { i, b ->
            b.text = m.input.getOrNull(i)?.toString().orEmpty()
            b.background = light.shape(MamaColors.PureCard, 12, if (i == m.input.length) MamaColors.Emerald else MamaColors.Border, 1.5f)
        }
        attempts.text = "Осталось попыток: ${m.attemptsLeft}"
        message.text = m.message.orEmpty()
        message.visibility = if (m.message.isNullOrEmpty()) View.GONE else View.VISIBLE
        confirm.isEnabled = m.input.length == m.codeLength
        resend.isEnabled = canRequest
        resend.text = if (canRequest) {
            "Отправить код ещё раз"
        } else {
            val (rh, rm, rs) = Texts.hms(Duration.between(m.now, m.resendAt))
            "Отправить код ещё раз (" + (if (rh != "00") "$rh:" else "") + "$rm:$rs)"
        }
    }

    companion object {
        const val DEL = "⌫"
    }
}
