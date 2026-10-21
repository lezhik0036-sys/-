package app.mama.ui.design

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mama.ui.BrandButtonView
import app.mama.ui.Icon

/**
 * The palette of MAMA Design System v2 (docs/MAMA_DESIGN_SYSTEM_V2.md): the
 * warm nature of the approved welcome — cream and beige pages and cards,
 * moss green for the one main action and every choice, warm dark ink,
 * golden light in the photos. [Palette] names the roles.
 */
object V2 {
    /** Page: cream at the top, warmer towards the bottom. */
    val Milk = Color.rgb(0xFB, 0xF5, 0xEA)
    val WarmBg = Color.rgb(0xF5, 0xEB, 0xDC)

    /** Moss: the main action (its gradient runs from [MossLight] to [MossDeep]), choices, checks. */
    val Moss = Color.rgb(0x52, 0x5C, 0x33)
    val MossLight = Color.rgb(0x6A, 0x6E, 0x40)
    val MossDeep = Color.rgb(0x42, 0x4C, 0x28)

    val OliveDeep = Moss
    val Olive = Color.rgb(0x6E, 0x72, 0x48)

    /** Light olive-beige: pills, tags, the timer track. */
    val OliveLight = Color.rgb(0xE9, 0xE3, 0xCC)
    val Ink = Color.rgb(0x2B, 0x26, 0x15)
    val InkMuted = Color.rgb(0x7A, 0x72, 0x62)
    val Sand = Color.rgb(0xE6, 0xD6, 0xBD)

    /** Sage: the light end of the moss scale (arcs, marks). */
    val Sage = Color.rgb(0x9A, 0x9E, 0x6E)

    /** Mist: a chosen card, the palest olive. */
    val Mist = Color.rgb(0xF4, 0xF0, 0xDF)

    /** Beige: icon circles and quiet surfaces. */
    val SandLight = Color.rgb(0xEF, 0xE0, 0xCA)

    /** Warm earth: quote marks; its ink for text on sand. */
    val Earth = Color.rgb(0xB5, 0x9A, 0x6E)
    val EarthInk = Color.rgb(0x6E, 0x5B, 0x3C)

    /** Something still needed ("Нужно"): warm amber on pale amber, never an alarm. */
    val ClaySoft = Color.rgb(0xF3, 0xE4, 0xCB)
    val ClayInk = Color.rgb(0x8A, 0x66, 0x34)

    /** Hints, chevrons, inactive navigation. */
    val InkSoft = Color.rgb(0xA3, 0x9B, 0x89)

    /** Dividers and outlines. */
    val Line = Color.rgb(0xEA, 0xDF, 0xCD)

    /** Card surface on the cream page. */
    val Card = Color.rgb(0xFD, 0xF9, 0xF1)

    const val CARD_LARGE = 28
    const val CARD = 24
    const val BUTTON = 28
}

/**
 * What only the hero screens need on top of [DesignKit] (Welcome, the lock,
 * Recovery): the accent in a title and the [EarthPage]; the rest are the
 * shared components under the names those screens use.
 */
class DesignKitV2(val context: Context) {
    /** The shared components. */
    val k = DesignKit(context)

    fun dp(v: Int) = k.dp(v)

    fun text(value: CharSequence, style: Typo = Typo.BODY, color: Int = V2.Ink, center: Boolean = false): TextView = k.text(value, style, color, center)

    /** [text] with [accent] in moss ("Ты сделал это."). */
    fun accented(text: String, accent: String): CharSequence = SpannableString(text).apply {
        val at = text.lastIndexOf(accent)
        if (at >= 0) setSpan(ForegroundColorSpan(V2.Moss), at, at + accent.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun shape(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Float = 1f) = k.shape(fill, radiusDp, stroke, strokeDp)

    /** The one main action of a screen: the clay pill, white label, "→". */
    fun primary(label: String, arrow: Boolean = true, onClick: () -> Unit): BrandButtonView = k.primaryButton(label, arrow, large = true, onClick = onClick)

    /** A second action that does not compete: a soft outline pill. */
    fun secondary(label: String, icon: Icon? = null, onClick: () -> Unit): BrandButtonView = k.secondaryButton(label, icon, onClick)

    /** A quiet text action ("Мой путь"). */
    fun link(label: String, onClick: () -> Unit): BrandButtonView = k.linkButton(label, onClick = onClick)

    /** A secondary action that stays at hand but never leads (the lock). */
    fun quietAction(icon: Icon, label: String, onClick: () -> Unit): BrandButtonView = k.quietAction(icon, label, onClick)

    /** A status pill, fully rounded: light olive, deep olive text ("Пройдено ночей: 4 из 7"). */
    fun pill(label: String, icon: Icon? = null): View = k.badge(label, icon)

    /** A soft card: [radiusDp] 20–28, a faint sand rim and a warm shadow. */
    fun card(paddingDp: Int = 20, radiusDp: Int = V2.CARD, fill: Int = V2.Card): LinearLayout = k.card(paddingDp, fill, radiusDp)

    /** An icon in a soft round bubble. */
    fun bubble(icon: Icon, tint: Int = V2.OliveDeep, fill: Int = V2.OliveLight, sizeDp: Int = 44): View = FrameLayout(context).apply {
        background = k.oval(fill)
        addView(k.icon(icon, tint, (sizeDp * 0.46f).toInt()), FrameLayout.LayoutParams(dp((sizeDp * 0.46f).toInt()), dp((sizeDp * 0.46f).toInt()), Gravity.CENTER))
        layoutParams = k.size(sizeDp)
    }

    /** A note of facts (what "Цена слова" came to, a free series later); [warm] for "Цена слова". */
    fun note(title: String?, message: CharSequence, icon: Icon, warm: Boolean = false): View =
        k.noteCard(message, title, if (warm) NoteTone.CARE else NoteTone.INFO, icon)

    /** The lock timer (see [DesignKit.timerRing]), without the sprout: only the time and the day. */
    fun timerRing(sizeDp: Int): TimerRing = k.timerRing(sizeDp, sprout = false)

    /** The cream page with its leaf sprigs ([EarthPage]); [quiet] for the lock. */
    fun page(leaves: Boolean = true, quiet: Boolean = false): Drawable = EarthPage(context, leaves, quiet)
}
