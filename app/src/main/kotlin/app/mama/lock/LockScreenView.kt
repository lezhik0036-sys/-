package app.mama.lock

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.mama.core.EmergencyService
import app.mama.platform.Calls
import app.mama.ui.BrandButtonView
import app.mama.ui.Icon
import app.mama.ui.MamaFonts
import app.mama.ui.Texts
import app.mama.ui.design.DesignKit
import app.mama.ui.design.DesignKitV2
import app.mama.ui.design.Palette
import app.mama.ui.design.Radii
import app.mama.ui.design.SceneLayout
import app.mama.ui.design.SceneMood
import app.mama.ui.design.ScenicBackground
import app.mama.ui.design.Scenery
import app.mama.ui.design.Spacing
import app.mama.ui.design.TimerRing
import app.mama.ui.design.Typo
import app.mama.ui.design.V2
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
    /** "Экстренные службы" page open. */
    val emergencyOpen: Boolean = false,
    /** Services listed there (from the phone's own emergency list). */
    val emergencyServices: List<EmergencyService> = emptyList(),
    /** Shown instead of / under the list: no telephony, the dialer could not open… */
    val emergencyNote: String? = null,
    /** An emergency call is going on: the call card offers the call screen back. */
    val emergencyCallActive: Boolean = false,
)

interface LockActions {
    /** "Экстренный звонок": opens MAMA's "Экстренные службы" page (not the dialer). */
    fun openEmergency()
    fun closeEmergency()

    /** A service was chosen: the phone's own dialer opens with [number] entered. */
    fun dialEmergency(number: String)

    /** Back to the system in-call screen of an emergency call. */
    fun returnToCall()
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
 * The active lock screen (MAMA_DESIGN_SYSTEM_V2 «ACTIVE»): the calmest and
 * most minimal screen of MAMA, a quiet but reliable frame rather than a wall.
 * The stillest earth page (no circles, no sprigs, two faint hills), "M A M A",
 * one short phrase, the time the phone stays protected, the remaining time
 * and the day of the series in a ring like a sun over the ground, and only
 * two quiet actions — "Экстренный звонок" and "Мне действительно нужно выйти"
 * (Trusted Exit). No red, no warning signs. "Экстренный звонок" opens the
 * "Экстренные службы" page on the same page; Trusted Exit — the user's reason
 * and a pause, then the code entry — stands under a quiet morning, calm and
 * neutral. Pure view code: all behaviour goes through [LockActions].
 */
class LockScreenView(private val context: Context, private val actions: LockActions) {
    private val d = DesignKit(context)
    private val v = DesignKitV2(context)

    // The background lies on the root, so it also fills the area under the system bars:
    // the stillest page behind the lock and its emergency page, a quiet morning behind Trusted Exit.
    private val lockScene: Drawable = v.page(quiet = true)
    private val exitScene: Drawable by lazy { ScenicBackground(context, Scenery.MORNING, SceneLayout.HEADER, mood = SceneMood.QUIET) }

    val root: FrameLayout = FrameLayout(context).apply { background = lockScene }

    // Main page.
    private lateinit var mainPage: View
    private lateinit var phrase: TextView
    private lateinit var until: TextView
    private lateinit var ring: TimerRing
    private lateinit var callCard: LinearLayout
    private lateinit var callInfo: TextView
    private lateinit var answer: BrandButtonView
    private lateinit var backToCall: BrandButtonView
    private lateinit var diagnostics: TextView

    // Emergency services page.
    private lateinit var emergencyPage: View
    private lateinit var serviceList: LinearLayout
    private lateinit var emergencyNote: TextView
    private var shownServices: List<EmergencyService>? = null

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
        mainPage = buildMain()
        root.addView(mainPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        exitPage = buildExit()
        root.addView(exitPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        emergencyPage = buildEmergency()
        root.addView(emergencyPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun dp(v: Int) = d.dp(v)

    private fun page(content: View): View = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(content)
    }

    // ---------------- main page ----------------

    private fun buildMain(): View {
        val col = d.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(Spacing.SCREEN), dp(48), dp(Spacing.SCREEN), dp(26))
        }
        col.addView(d.wordmark(V2.Ink), d.centered())

        callCard = d.card(Spacing.INNER).apply { gravity = Gravity.CENTER_HORIZONTAL }
        callInfo = d.text("", Typo.HEADLINE, Palette.Ink, center = true).also { callCard.addView(it, d.fill()) }
        answer = d.primaryButton("Ответить", arrow = false) { actions.answer() }
        callCard.addView(answer, d.gap(14))
        backToCall = d.primaryButton("Вернуться к звонку", arrow = false) { actions.returnToCall() }
        callCard.addView(backToCall, d.gap(12))
        callCard.addView(d.secondaryButton("Завершить звонок") { actions.hangUp() }, d.gap(10))
        col.addView(callCard, d.gap(18))

        // One short line of support and when the phone is free again: quiet, never loud.
        phrase = v.text("", Typo.SUBTITLE, V2.Ink, center = true).apply {
            textSize = 19f
            typeface = MamaFonts.ui(context, 500)
        }
        col.addView(phrase, d.gap(40).apply { leftMargin = dp(8); rightMargin = dp(8) })
        until = v.text("", Typo.SUBTITLE, V2.InkMuted, center = true).apply { textSize = 15.5f }
        col.addView(until, d.centered(14))

        // Remaining time and the day of the series: the anchor of the screen.
        ring = v.timerRing(232)
        col.addView(ring.view, (ring.view.layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(30) })

        col.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))

        // Only two actions, both quiet: always at hand, never the main thing on the screen.
        col.addView(v.quietAction(Icon.PHONE, "Экстренный звонок") { actions.openEmergency() }, d.centered(30))
        col.addView(v.quietAction(Icon.PEOPLE, "Мне действительно нужно выйти") { actions.openExit() }, d.centered(8))
        diagnostics = d.text("", Typo.CAPTION, Color.argb(0x99, 0x5E, 0x62, 0x66), center = true).also {
            it.textSize = 9.5f
            col.addView(it, d.gap(14))
        }
        return ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(col)
        }
    }

    // ---------------- Emergency services page ----------------

    private fun buildEmergency(): View {
        val col = d.column().apply { setPadding(dp(Spacing.SCREEN), dp(48), dp(Spacing.SCREEN), dp(26)) }
        col.addView(d.wordmark(), d.centered())
        col.addView(d.titleBlock(
            "Экстренные службы",
            lead = "Номер откроется в приложении «Телефон» — останется нажать вызов. Звонок не прерывает серию и ничего не стоит.",
        ), d.gap(30))
        serviceList = d.column()
        col.addView(serviceList, d.gap(24))
        emergencyNote = d.text("", Typo.BODY, Palette.Ink)
        col.addView(emergencyNote, d.gap(16))
        col.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(d.secondaryButton("Назад в MAMA") { actions.closeEmergency() }, d.gap(24))
        return page(col)
    }

    private fun serviceRow(s: EmergencyService): View = d.row().apply {
        setPadding(dp(18), dp(14), dp(16), dp(14))
        minimumHeight = dp(72)
        background = d.pressable(d.surface(), Radii.CARD)
        d.elevate(this, 2f)
        addView(d.text(s.number, Typo.NUMBER), LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(d.text(s.name, Typo.VALUE), d.weight().apply { rightMargin = dp(8) })
        addView(d.iconCircle(Icon.PHONE, 40))
        isClickable = true
        contentDescription = "${s.name}, ${s.number}"
        setOnClickListener { actions.dialEmergency(s.number) }
    }

    // ---------------- Trusted Exit page ----------------

    private fun buildExit(): View {
        val col = d.column().apply { setPadding(0, dp(36), 0, dp(26)) }
        col.addView(d.topBar({ actions.closeExit() }))
        val body = d.column().apply { setPadding(dp(Spacing.SCREEN), 0, dp(Spacing.SCREEN), 0) }

        // Intro: the user's own reason and a pause.
        intro = d.column()
        intro.addView(d.titleBlock("Досрочный выход", lead = "Сначала — минута тишины. Решение всё ещё твоё."), d.gap(18))
        promiseBlock = d.column()
        promiseBlock.addView(d.text("Ты начал эту серию потому что:", Typo.LABEL, Palette.Ink), d.fill())
        promiseText = d.text("", Typo.QUOTE_ITALIC, Palette.Ink)
        promiseBlock.addView(d.quietCard().apply { addView(promiseText, d.fill()) }, d.gap(10))
        intro.addView(promiseBlock, d.gap(26))
        intro.addView(d.noteCard("Не принимай большое решение из-за минутного импульса.", icon = Icon.HEART), d.gap(Spacing.GUTTER))
        introMessage = d.text("", Typo.LABEL, Palette.SandInk, center = true)
        intro.addView(introMessage, d.gap(14))
        intro.addView(d.primaryButton("Вернуться к блокировке", arrow = false, large = true) { actions.closeExit() }, d.gap(26))
        requestButton = d.secondaryButton("Запросить код") { actions.requestCode() }
        intro.addView(requestButton, d.gap(12))
        callContactButton = d.linkButton("Позвонить") { actions.callContact() }
        intro.addView(callContactButton, d.gap(6))
        body.addView(intro, d.fill())

        // Code entry.
        codePage = d.column()
        codePage.addView(d.title("Доверенный выход"), d.gap(18))
        codeHint = d.text("", Typo.BODY, Palette.InkMuted).also { codePage.addView(it, d.gap(10)) }
        val boxRow = d.row().apply { gravity = Gravity.CENTER }
        boxes = (0 until 5).map {
            d.text("", Typo.NUMBER, Palette.Ink, center = true).apply {
                gravity = Gravity.CENTER
                background = d.shape(Palette.Card, Radii.CHIP, Palette.Line, 1.5f)
            }.also { boxRow.addView(it, LinearLayout.LayoutParams(dp(52), dp(62)).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        }
        codePage.addView(boxRow, d.gap(22))
        attempts = d.text("", Typo.LABEL, Palette.InkMuted, center = true).also { codePage.addView(it, d.gap(14)) }
        message = d.text("", Typo.LABEL, Palette.SandInk, center = true).also { codePage.addView(it, d.gap(8)) }
        codePage.addView(keypad(), d.centered(12))
        confirm = d.primaryButton("Подтвердить", arrow = false) { actions.submit() }
        codePage.addView(confirm, d.gap(18))
        resend = d.linkButton("Отправить код ещё раз") { actions.requestCode() }
        codePage.addView(resend, d.gap(4))
        codePage.addView(d.linkButton("Отмена", Palette.InkMuted) { actions.closeExit() }, d.gap(0))
        body.addView(codePage, d.fill())

        col.addView(body, d.fill())
        return page(col)
    }

    private fun keypad(): View {
        val grid = d.column()
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", DEL)).forEach { r ->
            val line = d.row()
            r.forEach { key ->
                val cell: View = if (key.isEmpty()) {
                    View(context)
                } else {
                    d.text(key, Typo.HEADLINE, Palette.Ink, center = true).apply {
                        gravity = Gravity.CENTER
                        textSize = if (key == DEL) 20f else 24f
                        background = d.pressable(d.shape(Palette.Card, Radii.CARD_SMALL, Palette.Line), Radii.CARD_SMALL)
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
        // Minutes, rounded up: a calm figure, no ticking seconds.
        val minutes = maxOf(0L, (left.toMillis() + 59_999) / 60_000)
        val total = Duration.between(m.start, m.end).toMillis().coerceAtLeast(1)
        val progress = 1f - left.toMillis().coerceIn(0, total).toFloat() / total
        ring.set(minutes / 60, minutes % 60, progress, m.dayLabel)
        phrase.text = m.phrase
        until.text = "Телефон под защитой до ${Texts.time(m.end, m.zone)}"

        callCard.visibility = if (m.call != Calls.CallState.NONE) View.VISIBLE else View.GONE
        callInfo.text = if (m.call == Calls.CallState.RINGING) "Входящий звонок" else "Идёт звонок"
        answer.visibility = if (m.call == Calls.CallState.RINGING) View.VISIBLE else View.GONE
        backToCall.visibility = if (m.emergencyCallActive && m.call != Calls.CallState.NONE) View.VISIBLE else View.GONE

        mainPage.visibility = if (m.emergencyOpen || m.exitOpen) View.GONE else View.VISIBLE
        val scene = if (m.exitOpen && !m.emergencyOpen) exitScene else lockScene
        if (root.background !== scene) root.background = scene
        emergencyPage.visibility = if (m.emergencyOpen) View.VISIBLE else View.GONE
        if (m.emergencyServices != shownServices) {
            shownServices = m.emergencyServices
            serviceList.removeAllViews()
            m.emergencyServices.forEachIndexed { i, s -> serviceList.addView(serviceRow(s), d.gap(if (i == 0) 0 else 10)) }
        }
        emergencyNote.text = m.emergencyNote.orEmpty()
        emergencyNote.visibility = if (m.emergencyNote.isNullOrEmpty()) View.GONE else View.VISIBLE
        diagnostics.text = m.diagnostics.orEmpty()
        diagnostics.visibility = if (m.diagnostics.isNullOrEmpty()) View.GONE else View.VISIBLE

        // The emergency page stays on top of the exit page, as before.
        exitPage.visibility = if (m.exitOpen && !m.emergencyOpen) View.VISIBLE else View.GONE
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

        codeHint.text = "Введи код из ${m.codeLength} цифр, который отправлен доверенному контакту: " +
            "${m.contactName} ${m.contactPhone}"
        boxes.forEachIndexed { i, b ->
            b.text = m.input.getOrNull(i)?.toString().orEmpty()
            b.background = d.shape(Palette.Card, Radii.CHIP, if (i == m.input.length) Palette.OliveLine else Palette.Line, 1.5f)
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
