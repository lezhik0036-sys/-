package app.mama.ui

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView

/**
 * MAMA brand colours. Every screen takes its colours from here; there is no
 * other source of colour in the app (res/values/colors.xml mirrors these for
 * the window theme only).
 */
object MamaColors {
    /** Warm light cream page background. */
    val BackgroundPrimary = Color.rgb(0xF6, 0xF2, 0xE9)

    /** Cards and light blocks. */
    val SurfaceCard = Color.rgb(0xFF, 0xFD, 0xF8)

    /** Main brand accent: primary buttons, selection. */
    val EmeraldPrimary = Color.rgb(0x2F, 0x5D, 0x50)

    /** Secondary accent. */
    val EmeraldSecondary = Color.rgb(0x5F, 0x8A, 0x72)

    val OliveSoft = Color.rgb(0x9C, 0xAB, 0x7D)
    val SageMuted = Color.rgb(0xC9, 0xD6, 0xC1)
    val TextPrimary = Color.rgb(0x1F, 0x2A, 0x24)
    val TextSecondary = Color.rgb(0x5E, 0x65, 0x5F)
    val BorderSoft = Color.rgb(0xDC, 0xD6, 0xCA)
    val SuccessSoft = Color.rgb(0xDD, 0xEB, 0xDD)
    val WarningSoft = Color.rgb(0xF1, 0xE3, 0xC7)
    val DangerSoft = Color.rgb(0xE8, 0xC9, 0xC1)
    val White = Color.WHITE

    // Derived tones (same hues, used for text on the soft fills and pressed states).

    /** Pressed primary button. */
    val EmeraldPressed = Color.rgb(0x24, 0x4A, 0x3F)

    /** Background of a selected card. */
    val EmeraldTint = Color.rgb(0xEC, 0xF2, 0xEA)

    /** Ripple on light surfaces. */
    val Ripple = Color.argb(0x24, 0x2F, 0x5D, 0x50)

    /** Text on [WarningSoft]. */
    val WarningInk = Color.rgb(0x7A, 0x5A, 0x22)

    /** Text on [DangerSoft]. */
    val DangerInk = Color.rgb(0x8A, 0x3E, 0x2E)

    /** Text on [SuccessSoft]. */
    val SuccessInk = EmeraldPrimary
}

/**
 * MAMA type scale. Sans-serif throughout, a letter-spaced serif only for the
 * wordmark, light tabular digits for times and countdowns.
 */
enum class MamaType(
    val sizeSp: Float,
    private val family: String,
    private val style: Int = Typeface.NORMAL,
    private val tracking: Float = 0f,
    private val leading: Float = 1.2f,
    private val tabular: Boolean = false,
    val caps: Boolean = false,
) {
    WORDMARK(30f, "serif", Typeface.BOLD, tracking = 0.18f),
    H1(26f, "sans-serif-medium", leading = 1.15f),
    H2(20f, "sans-serif-medium", leading = 1.15f),
    TITLE(17f, "sans-serif-medium"),
    BODY(15f, "sans-serif", leading = 1.35f),
    CAPTION(13f, "sans-serif", leading = 1.3f),
    OVERLINE(12f, "sans-serif-medium", tracking = 0.08f, caps = true),
    BUTTON(16f, "sans-serif-medium", tracking = 0.01f),
    DIGITS(34f, "sans-serif-light", tabular = true, leading = 1.0f),
    DIGITS_L(44f, "sans-serif-light", tabular = true, leading = 1.0f),
    DIGITS_XL(54f, "sans-serif-thin", tabular = true, leading = 1.0f),
    QUOTE(19f, "serif", Typeface.ITALIC, leading = 1.35f),
    ;

    fun applyTo(view: TextView) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        view.typeface = Typeface.create(family, style)
        view.letterSpacing = tracking
        view.setLineSpacing(0f, leading)
        view.isAllCaps = caps
        if (tabular) view.fontFeatureSettings = "tnum"
        view.includeFontPadding = true
    }
}
