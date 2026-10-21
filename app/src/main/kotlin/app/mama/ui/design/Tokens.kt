package app.mama.ui.design

import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.widget.TextView
import app.mama.ui.MamaFonts

/*
 * MAMA design tokens. Since the earth direction (docs/MAMA_DESIGN_SYSTEM_V2.md:
 * nature, earth and MAMA as quiet support) every colour comes from [V2];
 * [Palette] names their roles for the components. Light only: there is no
 * night theme.
 */

/** Colour roles (MAMA_DESIGN_SYSTEM_V2 «Палитра»). */
object Palette {
    /** Milky page. */
    val Bg = V2.Milk
    val Card = V2.Card

    /** Quote cards and quiet surfaces: the warm wash of the page. */
    val CardQuiet = V2.WarmBg

    /** Filled states: a checked box, a selected circle. */
    val OliveDeep = V2.OliveDeep
    val Sage = V2.Sage

    /** Icons in circles, tag text, links. */
    val OliveInk = V2.OliveDeep

    /** Border of a selected card or chip. */
    val OliveLine = V2.Olive

    /** Icon circles: beige, a thin olive icon. */
    val SageBadge = V2.SandLight
    val SageSelected = V2.Mist
    val SageTag = V2.OliveLight

    /** Steps of a path: done ones run from deep olive to sage, the rest are sand. */
    val ProgressDark = V2.OliveDeep
    val ProgressMid = V2.Olive
    val ProgressLight = V2.Sage
    val ProgressOff = V2.Sand

    /** Timer ring. */
    val RingArc = V2.Olive
    val RingArcLight = V2.Sage
    val RingTrack = V2.OliveLight

    /** Warm earth: quote marks, gentle notices. Never red. */
    val Sand = V2.Earth
    val SandSoft = V2.SandLight
    val SandInk = V2.EarthInk

    val Olive = V2.Olive

    /** Moss: the one main action of a screen. */
    val Moss = V2.Moss
    val MossLight = V2.MossLight
    val MossDeep = V2.MossDeep

    /** Something still needed ("Нужно"): warm amber on a pale pill, never an alarm. */
    val ClaySoft = V2.ClaySoft
    val ClayInk = V2.ClayInk

    val Ink = V2.Ink
    val InkMuted = V2.InkMuted

    /** Hints, chevrons, inactive navigation. */
    val InkSoft = V2.InkSoft

    /** Dividers, chip and field outlines. */
    val Line = V2.Line
    val OnAccent = Color.WHITE

    /**
     * Depth (Android 9+): a faint, wide, warm earth shadow and a halo all
     * around — soft depth instead of a Material edge. The system scales these
     * alphas down (spot ≈ 0.19×, ambient ≈ 0.04×).
     */
    val Shadow = Color.argb(0x4A, 0x7A, 0x5E, 0x40)
    val ShadowAmbient = Color.argb(0xFF, 0x8A, 0x6A, 0x4A)

    /** Moss shadow under the main button. */
    val Glow = Color.argb(0x90, 0x42, 0x4C, 0x28)
    val GlowAmbient = Color.argb(0xFF, 0x42, 0x4C, 0x28)

    /** Soft sand rim on card edges. */
    val Rim = Color.argb(0x66, 0xE6, 0xD6, 0xBD)

    /** Behind dialogs and sheets: warm ink, lightly. */
    val Scrim = Color.argb(0x5C, 0x2B, 0x26, 0x15)

    val Ripple = Color.argb(0x1F, 0x52, 0x5C, 0x33)
    val RippleOnAccent = Color.argb(0x33, 0xFF, 0xFF, 0xFF)

    /** Test tools only (debug / pilot build): amber with a dashed outline, never on the main screens. */
    val TestInk = Color.rgb(0x8A, 0x5E, 0x1E)
    val TestFill = Color.rgb(0xFB, 0xF1, 0xDC)
    val TestLine = Color.rgb(0xD2, 0xA8, 0x62)
}

/**
 * Type scale (MAMA_DESIGN_SYSTEM_V2 «Типографика»): the serif (Cormorant
 * Garamond) for titles, values, numbers, buttons and thoughts, with lining
 * figures; Manrope for the small text (labels, captions, body). Both are
 * bundled, so the phone's font setting never changes the look. [leading] is
 * the distance between baselines in em (0 = the font's own line height).
 */
enum class Typo(
    val sizeSp: Float,
    private val weight: Int,
    private val tracking: Float = 0f,
    private val leading: Float = 0f,
    private val caps: Boolean = false,
    private val tabular: Boolean = false,
    private val serif: Boolean = false,
) {
    /** Screen title: "Всё готово", "Этот путь продолжается". */
    DISPLAY(38f, 600, tracking = -0.01f, leading = 1.06f, serif = true),

    /** Sheet title, big card title. */
    HEADLINE(28f, 600, leading = 1.1f, serif = true),

    /** Card title: "7 дней", "Начать заново". */
    TITLE(24f, 600, leading = 1.12f, serif = true),

    /** Value in a summary row: "Анна", "21:00 – 07:00". */
    VALUE(21f, 600, leading = 1.15f, serif = true),

    /** Two lines under the title. */
    SUBTITLE(21f, 500, leading = 1.22f, serif = true),
    BODY(16f, 500, leading = 1.38f),

    /** Field label: "Длительность", "Доверенный контакт". */
    LABEL(14.5f, 500, leading = 1.3f),
    CAPTION(13.5f, 500, leading = 1.32f),
    BUTTON(20f, 600, serif = true),

    /** "M A M A" in the top bar. */
    WORDMARK(19f, 600, tracking = 0.14f, serif = true),

    /** "6 из 6". */
    STEP(14.5f, 500),

    /** "РЕКОМЕНДУЕМ", "ВЕЧЕРОМ". */
    TAG(11.5f, 800, tracking = 0.06f, caps = true),

    /** "500 ₽" on the "Цена слова" card. */
    AMOUNT(46f, 500, tabular = true, serif = true),

    /** Big numbers: lock timer, values on the home screen. */
    NUMBER(40f, 500, tabular = true, serif = true),

    /** A quiet thought: quote cards, the motivation screen. */
    QUOTE(23f, 500, leading = 1.22f, serif = true),
    QUOTE_ITALIC(23f, 500, leading = 1.22f, serif = true),

    /** A short line on the home screen. */
    QUOTE_SANS(20f, 500, leading = 1.24f, serif = true),
    ;

    fun applyTo(view: TextView) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        view.typeface = if (serif) MamaFonts.serif(view.context, weight) else MamaFonts.ui(view.context, weight)
        view.letterSpacing = tracking
        if (leading > 0f) {
            // Baseline-to-baseline distance relative to the font's own line height (ascent + descent).
            val factor = leading / if (serif) CORMORANT_LINE else MANROPE_LINE
            // Before Android 9 the last line is shortened too, which could cut descenders: never tighter there.
            view.setLineSpacing(0f, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) factor else maxOf(factor, 1f))
        } else {
            view.setLineSpacing(0f, 1f)
        }
        view.isAllCaps = caps
        // The serif's figures are old-style by default: lining ones read as numbers ("12", not "I2").
        view.fontFeatureSettings = listOfNotNull("lnum".takeIf { serif }, "tnum".takeIf { tabular }).joinToString(",").ifEmpty { null }
        view.includeFontPadding = false
    }

    private companion object {
        /** (ascent + descent) / em of the bundled fonts (hhea / OS/2 typo metrics). */
        const val MANROPE_LINE = 1.366f
        const val CORMORANT_LINE = 1.211f
    }
}

/** Spacing in dp (DESIGN_SYSTEM_V1 §4). */
object Spacing {
    /** Side margins of every screen. */
    const val SCREEN = 18

    /** Between cards. */
    const val GUTTER = 14

    /** Between blocks (title → first card). */
    const val SECTION = 28

    /** Inside a card. */
    const val INNER = 18
    const val TIGHT = 8
    const val TOP_BAR = 52
    const val BUTTON = 52
}

/** Corner radii in dp (MAMA_DESIGN_SYSTEM_V2 «Скругления»). */
object Radii {
    const val CARD = V2.CARD
    const val CARD_SMALL = 18
    const val CHIP = 16

    /** Tags and pills: fully rounded at their height. */
    const val TAG = 100
    const val CHECK = 8
    const val SHEET = V2.CARD_LARGE
    const val SEGMENT = 3
}
