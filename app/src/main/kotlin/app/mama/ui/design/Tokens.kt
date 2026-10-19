package app.mama.ui.design

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import app.mama.ui.MamaFonts

/*
 * MAMA UI v1 design tokens (docs/DESIGN_SYSTEM_V1.md, measured from the
 * approved references in docs/design-reference/). Every v1 component takes
 * its colours, type, spacing and radii from here. Light only: there is no
 * night theme in UI v1.
 */

/** Colours (DESIGN_SYSTEM_V1 §2). */
object Palette {
    /** Warm ivory page. */
    val Bg = Color.rgb(0xFD, 0xFB, 0xF5)
    val Card = Color.rgb(0xFE, 0xFD, 0xF9)

    /** Quote cards and quiet surfaces. */
    val CardQuiet = Color.rgb(0xFA, 0xF6, 0xEA)

    val EmeraldDeep = Color.rgb(0x16, 0x54, 0x46)
    val EmeraldLight = Color.rgb(0x5A, 0x99, 0x74)

    /** Icons in circles, tag text, links. */
    val EmeraldInk = Color.rgb(0x0F, 0x4A, 0x36)

    /** Border of a selected card or chip. */
    val EmeraldBorder = Color.rgb(0x3D, 0x7F, 0x6C)
    val MintBadge = Color.rgb(0xE0, 0xEB, 0xDB)
    val MintSelected = Color.rgb(0xE8, 0xF5, 0xEB)
    val MintTag = Color.rgb(0xD9, 0xF0, 0xE2)

    /** Progress segments: done ones run from dark to light emerald, the rest are sand. */
    val ProgressDark = Color.rgb(0x0D, 0x56, 0x47)
    val ProgressMid = Color.rgb(0x34, 0x7B, 0x63)
    val ProgressLight = Color.rgb(0x55, 0x9C, 0x7A)
    val ProgressOff = Color.rgb(0xF2, 0xEA, 0xDB)

    /** Lock timer ring. */
    val RingArc = Color.rgb(0x37, 0x82, 0x67)
    val RingArcLight = Color.rgb(0x5F, 0xA2, 0x7F)
    val RingTrack = Color.rgb(0xD5, 0xE6, 0xD2)

    /** Warm sand: quote marks on recovery and home, gentle notices. Never red. */
    val Sand = Color.rgb(0x94, 0x7F, 0x66)
    val SandSoft = Color.rgb(0xF0, 0xE4, 0xD1)
    val SandInk = Color.rgb(0x6F, 0x5B, 0x42)

    /** Olive accent for recovery screens. */
    val Olive = Color.rgb(0x7E, 0x8E, 0x4F)
    val OliveSoft = Color.rgb(0xEC, 0xEE, 0xD9)

    val Ink = Color.rgb(0x11, 0x11, 0x11)
    val InkMuted = Color.rgb(0x5E, 0x62, 0x66)

    /** Hints, chevrons, inactive navigation. */
    val InkSoft = Color.rgb(0x8B, 0x90, 0x94)

    /** Dividers, chip and field outlines. */
    val Line = Color.rgb(0xEC, 0xE7, 0xDC)
    val OnEmerald = Color.WHITE

    /** Soft warm card shadow and the emerald glow under the main button (Android 9+). */
    val Shadow = Color.argb(0x40, 0x5A, 0x48, 0x2A)
    val Glow = Color.argb(0x90, 0x1F, 0x6B, 0x55)

    /** Behind dialogs and sheets. */
    val Scrim = Color.argb(0x73, 0x1C, 0x26, 0x22)

    val Ripple = Color.argb(0x1F, 0x16, 0x54, 0x46)
    val RippleOnEmerald = Color.argb(0x33, 0xFF, 0xFF, 0xFF)

    /** Test tools only (debug / pilot build): amber with a dashed outline, never on the main screens. */
    val TestInk = Color.rgb(0x8A, 0x5E, 0x1E)
    val TestFill = Color.rgb(0xFB, 0xF1, 0xDC)
    val TestLine = Color.rgb(0xD2, 0xA8, 0x62)
}

/**
 * Type scale (DESIGN_SYSTEM_V1 §3). Manrope for the interface, Cormorant
 * Garamond for quotes; both bundled, so the phone's font setting never
 * changes the look.
 */
enum class Typo(
    val sizeSp: Float,
    private val weight: Int,
    private val tracking: Float = 0f,
    private val leading: Float = 1.25f,
    private val serif: Boolean = false,
    private val italic: Boolean = false,
    private val caps: Boolean = false,
    private val tabular: Boolean = false,
) {
    /** Screen title: "Всё готово", "Срыв — это часть пути". */
    DISPLAY(31f, 800, tracking = -0.025f, leading = 1.08f),

    /** Sheet title, big card title. */
    HEADLINE(23f, 800, tracking = -0.015f, leading = 1.14f),

    /** Card title: "7 дней", "Начать заново". */
    TITLE(20f, 800, tracking = -0.01f, leading = 1.15f),

    /** Value in a summary row: "Анна", "21:00 – 07:00". */
    VALUE(17f, 700, tracking = -0.005f, leading = 1.2f),

    /** Two lines under the title. */
    SUBTITLE(16f, 500, leading = 1.32f),
    BODY(15f, 500, leading = 1.4f),

    /** Field label: "Длительность", "Доверенный контакт". */
    LABEL(13.5f, 500, leading = 1.3f),
    CAPTION(12.5f, 500, leading = 1.35f),
    BUTTON(16f, 700, leading = 1.0f),

    /** "M A M A" in the top bar. */
    WORDMARK(16f, 600, tracking = 0.42f, leading = 1.0f),

    /** "6 из 6". */
    STEP(14f, 500, leading = 1.0f),

    /** "РЕКОМЕНДУЕМ", "ВЕЧЕРОМ". */
    TAG(11f, 800, tracking = 0.08f, leading = 1.0f, caps = true),

    /** "500 ₽" on the "Цена слова" card. */
    AMOUNT(40f, 800, tracking = -0.02f, leading = 1.0f, tabular = true),

    /** Big numbers: lock timer, values on the home screen. */
    NUMBER(30f, 800, tracking = -0.02f, leading = 1.0f, tabular = true),

    /** Serif quote: lock screen, quote cards. */
    QUOTE(21f, 500, leading = 1.2f, serif = true),
    QUOTE_ITALIC(21f, 500, leading = 1.2f, serif = true, italic = true),

    /** Sans quote: home screen. */
    QUOTE_SANS(15f, 500, leading = 1.42f),
    ;

    fun applyTo(view: TextView) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        val face = if (serif) MamaFonts.wordmark(view.context, weight) else MamaFonts.ui(view.context, weight)
        // Cormorant has no bundled italic: the framework slants the upright face.
        if (italic) view.setTypeface(face, Typeface.ITALIC) else view.typeface = face
        view.letterSpacing = tracking
        view.setLineSpacing(0f, leading)
        view.isAllCaps = caps
        view.fontFeatureSettings = if (tabular) "tnum" else null
        view.includeFontPadding = false
    }
}

/** Spacing in dp (DESIGN_SYSTEM_V1 §4). */
object Spacing {
    /** Side margins of every screen. */
    const val SCREEN = 18

    /** Between cards. */
    const val GUTTER = 12

    /** Between blocks (title → first card). */
    const val SECTION = 24

    /** Inside a card. */
    const val INNER = 16
    const val TIGHT = 8
    const val TOP_BAR = 52
    const val BUTTON = 52
}

/** Corner radii in dp. */
object Radii {
    const val CARD = 16
    const val CARD_SMALL = 14
    const val CHIP = 12
    const val TAG = 8
    const val CHECK = 7
    const val SHEET = 26
    const val SEGMENT = 3
}
