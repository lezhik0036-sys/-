package app.mama.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView

/**
 * MAMA palette (design reference). Roughly 80 % neutrals, 15 % emerald,
 * 5 % olive / warning accents. Every colour in the app comes from here.
 */
object MamaColors {
    val WarmIvory = Color.rgb(0xF7, 0xF4, 0xEC)
    val PureCard = Color.rgb(0xFC, 0xFA, 0xF5)
    val WarmCard = Color.rgb(0xEE, 0xE9, 0xDF)
    val Emerald = Color.rgb(0x28, 0x59, 0x4A)
    val EmeraldActive = Color.rgb(0x3E, 0x73, 0x5F)
    val EmeraldSoft = Color.rgb(0xA8, 0xBF, 0xAF)
    val Olive = Color.rgb(0x83, 0x89, 0x6A)
    val OliveLight = Color.rgb(0xD9, 0xDC, 0xCB)
    val Graphite = Color.rgb(0x26, 0x2A, 0x27)
    val TextSecondary = Color.rgb(0x70, 0x75, 0x6E)
    val Night = Color.rgb(0x09, 0x17, 0x14)
    val NightSurface = Color.rgb(0x10, 0x27, 0x20)
    val NightSoft = Color.rgb(0x1D, 0x3A, 0x30)
    val ProgressGlow = Color.rgb(0xC6, 0xD3, 0x9D)
    val Warning = Color.rgb(0xC8, 0x75, 0x61)
    val DangerSoft = Color.rgb(0xF1, 0xDD, 0xD6)
    val Success = Color.rgb(0x5D, 0x82, 0x69)
    val White = Color.WHITE

    // Derived from the palette.

    /** Card borders on light screens. */
    val Border = Color.rgb(0xE6, 0xE0, 0xD3)

    /** Emerald, pressed. */
    val EmeraldDeep = Color.rgb(0x1E, 0x47, 0x3B)

    /** Icon circles and selected-row tints on light screens. */
    val EmeraldTint = Color.rgb(0xE4, 0xEC, 0xE4)

    /** Text and icons on night screens. */
    val OnNight = Color.rgb(0xF3, 0xF1, 0xEA)
    val OnNightMuted = Color.rgb(0xB4, 0xBF, 0xB5)

    /** Translucent card on photographic night screens. */
    val NightGlass = Color.argb(0xC8, 0x10, 0x27, 0x20)
    val NightLine = Color.argb(0x38, 0xF3, 0xF1, 0xEA)

    val Ripple = Color.argb(0x22, 0x28, 0x59, 0x4A)
    val RippleOnDark = Color.argb(0x30, 0xFF, 0xFF, 0xFF)
}

/** Bundled fonts (SIL OFL), so the phone's system font style never changes the app. */
object MamaFonts {
    private val cache = HashMap<String, Typeface>()

    /** Manrope, the interface font, at a given weight (200–800). */
    fun ui(context: Context, weight: Int): Typeface = variable(context, "fonts/Manrope.ttf", weight)

    /** Cormorant Garamond, for the MAMA wordmark (300–700). */
    fun wordmark(context: Context, weight: Int): Typeface = variable(context, "fonts/CormorantGaramond.ttf", weight)

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
    private val wordmark: Boolean = false,
    private val tracking: Float = 0f,
    private val leading: Float = 1.25f,
    private val tabular: Boolean = false,
    private val caps: Boolean = false,
) {
    /** Hero wordmark (welcome, lock, dashboards). */
    WORDMARK_XL(52f, 500, wordmark = true, tracking = 0.08f, leading = 1.0f),
    WORDMARK(30f, 600, wordmark = true, tracking = 0.12f, leading = 1.0f),

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
        view.typeface = if (wordmark) MamaFonts.wordmark(view.context, weight) else MamaFonts.ui(view.context, weight)
        view.letterSpacing = tracking
        view.setLineSpacing(0f, leading)
        view.isAllCaps = caps
        view.fontFeatureSettings = if (tabular) "tnum" else null
    }
}
