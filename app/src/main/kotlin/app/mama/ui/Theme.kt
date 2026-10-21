package app.mama.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import app.mama.ui.design.V2

/**
 * Colours of the first component kit ([Kit]: permissions, the series choice,
 * the reason, settings, pickers). Its names are historical; the values are
 * the earth palette of MAMA Design System v2 ([V2]), so these screens speak
 * the same language: "Emerald" is deep olive, "Warning" is clay, never red.
 */
object MamaColors {
    val WarmIvory = V2.Milk
    val PureCard = V2.Card
    val WarmCard = V2.WarmBg
    val Emerald = V2.OliveDeep
    val EmeraldActive = V2.Olive
    val EmeraldSoft = V2.Sage
    val Olive = V2.Olive
    val OliveLight = V2.OliveLight
    val Graphite = V2.Ink
    val TextSecondary = V2.InkMuted
    val Night = Color.rgb(0x09, 0x17, 0x14)
    val NightSurface = Color.rgb(0x10, 0x27, 0x20)
    val NightSoft = Color.rgb(0x1D, 0x3A, 0x30)
    val ProgressGlow = Color.rgb(0xC6, 0xD3, 0x9D)
    val Warning = V2.ClayInk
    val DangerSoft = V2.ClaySoft
    val Success = V2.Olive
    val White = Color.WHITE

    // Derived from the palette.

    /** Card borders on light screens. */
    val Border = V2.Line

    /** Deep olive, pressed. */
    val EmeraldDeep = Color.rgb(0x3B, 0x46, 0x37)

    /** Icon circles and selected-row tints on light screens. */
    val EmeraldTint = V2.OliveLight

    /** Text and icons on night screens. */
    val OnNight = Color.rgb(0xF3, 0xF1, 0xEA)
    val OnNightMuted = Color.rgb(0xB4, 0xBF, 0xB5)

    /** Translucent card on photographic night screens. */
    val NightGlass = Color.argb(0xC8, 0x10, 0x27, 0x20)
    val NightLine = Color.argb(0x38, 0xF3, 0xF1, 0xEA)

    val Ripple = Color.argb(0x1F, 0x47, 0x53, 0x42)
    val RippleOnDark = Color.argb(0x30, 0xFF, 0xFF, 0xFF)
}

/** Bundled fonts (SIL OFL), so the phone's system font style never changes the app. */
object MamaFonts {
    private val cache = HashMap<String, Typeface>()

    /** Manrope, the interface font, at a given weight (200–800). */
    fun ui(context: Context, weight: Int): Typeface = variable(context, "fonts/Manrope.ttf", weight)

    /** Cormorant Garamond, the serif of titles, numbers, buttons and thoughts (300–700). */
    fun serif(context: Context, weight: Int): Typeface = variable(context, "fonts/CormorantGaramond.ttf", weight)

    @Synchronized
    private fun variable(context: Context, path: String, weight: Int): Typeface = cache.getOrPut("$path@$weight") {
        runCatching {
            Typeface.Builder(context.assets, path).setFontVariationSettings("'wght' $weight").build()
        }.getOrNull() ?: Typeface.create(Typeface.SANS_SERIF, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }
}

/** MAMA type scale. */
enum class MamaType(
    val sizeSp: Float,
    private val weight: Int,
    private val tracking: Float = 0f,
    private val leading: Float = 1.25f,
    private val tabular: Boolean = false,
    private val caps: Boolean = false,
) {
    /** Hero wordmark (welcome, lock, dashboards). */
    WORDMARK_XL(52f, 500, tracking = 0.08f, leading = 1.0f),
    WORDMARK(30f, 600, tracking = 0.12f, leading = 1.0f),

    /** "MAMA" in the step header. */
    HEADER(17f, 700, tracking = 0.14f),
    DISPLAY(30f, 700, tracking = -0.01f, leading = 1.15f),
    H1(24f, 700, tracking = -0.01f, leading = 1.18f),
    H2(19f, 700, leading = 1.2f),
    TITLE(16f, 700),
    BODY(15f, 500, leading = 1.38f),
    CAPTION(13f, 500, leading = 1.32f),
    SMALL(11.5f, 500, leading = 1.3f),
    OVERLINE(11f, 700, tracking = 0.1f, caps = true),
    BUTTON(16f, 700, tracking = 0.01f),
    DIGITS(26f, 600, tabular = true, leading = 1.0f),
    DIGITS_L(46f, 500, tabular = true, leading = 1.0f, tracking = 0.01f),
    DIGITS_XL(52f, 400, tabular = true, leading = 1.0f, tracking = 0.01f),

    /** Big motivational line on night screens. */
    QUOTE(27f, 700, tracking = -0.01f, leading = 1.18f),
    ;

    fun applyTo(view: TextView) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        view.typeface = MamaFonts.ui(view.context, weight)
        view.letterSpacing = tracking
        view.setLineSpacing(0f, leading)
        view.isAllCaps = caps
        view.fontFeatureSettings = if (tabular) "tnum" else null
    }
}
