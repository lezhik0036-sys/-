package app.mama.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.mama.ui.design.CircleStyle
import app.mama.ui.design.DesignKit
import app.mama.ui.design.NoteTone
import app.mama.ui.design.Palette
import app.mama.ui.design.Radii
import app.mama.ui.design.Typo
import app.mama.ui.design.V2

/** A button that fades when disabled, so an unavailable action looks unavailable. */
class BrandButtonView(context: Context) : TextView(context) {
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.42f
    }
}

enum class Tone { SUCCESS, WARNING, DANGER, NEUTRAL, BRAND }

enum class PermissionStatus { GRANTED, REQUIRED, MISSING }

/**
 * The first component kit (names in brackets match the old design spec),
 * still used by a few screens: permissions, the series choice, the reason,
 * settings, the FLEX planner, pickers and payment sheets. Its pieces are the
 * components of [DesignKit] or drawn in the same earth language
 * (MAMA_DESIGN_SYSTEM_V2), so these screens look like the rest.
 */
class Kit(val context: Context) {
    /** The shared components this kit is made of. */
    private val design by lazy { DesignKit(context) }

    fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    fun dpf(v: Float): Float = v * context.resources.displayMetrics.density

    val ink get() = MamaColors.Graphite
    val muted get() = MamaColors.TextSecondary

    // ---------- primitives ----------

    fun text(value: CharSequence, type: MamaType = MamaType.BODY, color: Int = ink, center: Boolean = false) =
        TextView(context).apply {
            text = value
            type.applyTo(this)
            setTextColor(color)
            includeFontPadding = false
            if (center) gravity = Gravity.CENTER_HORIZONTAL
        }

    fun caption(value: CharSequence, center: Boolean = false) = text(value, MamaType.CAPTION, muted, center)

    fun shape(fill: Int, radiusDp: Int = 18, stroke: Int? = null, strokeDp: Float = 1f) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dpf(radiusDp.toFloat())
        stroke?.let { setStroke(dpf(strokeDp).toInt().coerceAtLeast(1), it) }
    }

    fun oval(fill: Int, stroke: Int? = null, strokeDp: Float = 1.5f) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        stroke?.let { setStroke(dpf(strokeDp).toInt(), it) }
    }

    fun pressable(content: Drawable, radiusDp: Int, ripple: Int = MamaColors.Ripple): Drawable =
        RippleDrawable(ColorStateList.valueOf(ripple), content, shape(Color.WHITE, radiusDp))

    fun column(paddingDp: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
    }

    fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun fill(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    fun gap(topDp: Int): LinearLayout.LayoutParams = fill().apply { topMargin = dp(topDp) }
    fun weight(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    fun wrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    fun size(wDp: Int, hDp: Int = wDp) = LinearLayout.LayoutParams(dp(wDp), dp(hDp))
    fun centered(topDp: Int = 0) = wrap().apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(topDp) }

    fun icon(icon: Icon, color: Int = ink, sizeDp: Int = 22): ImageView = ImageView(context).apply {
        setImageDrawable(IconDrawable(icon, color))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Icon in a soft light olive circle ([DesignKit.iconCircle]). */
    fun iconCircle(icon: Icon, sizeDp: Int = 42): View = design.iconCircle(icon, sizeDp)

    private fun clickable(view: View, onClick: () -> Unit) {
        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener { onClick() }
    }

    // ---------- [MamaCard] ----------

    /** A milky card with a sand rim and a soft warm shadow ([DesignKit.card]). */
    fun card(paddingDp: Int = 18, radiusDp: Int = Radii.CARD) = column(paddingDp).apply {
        background = design.surface(MamaColors.PureCard, radiusDp)
        design.elevate(this, 2f)
    }

    // ---------- [MamaPrimaryButton] / [MamaSecondaryButton] ----------

    /** The clay main action ([DesignKit.primaryButton]); a [trailing] icon shows as its "→". */
    fun primaryButton(label: String, trailing: Icon? = null, onClick: () -> Unit): BrandButtonView =
        design.primaryButton(label, arrow = trailing != null, onClick = onClick)

    /** A second action ([DesignKit.secondaryButton]). */
    fun secondaryButton(label: String, icon: Icon? = null, onClick: () -> Unit): BrandButtonView =
        design.secondaryButton(label, icon, onClick)

    // ---------- [MamaSectionHeader] ----------

    /** Screen title and the lines under it ([DesignKit.titleBlock]). */
    fun sectionHeader(title: String, subtitle: String? = null, center: Boolean = false): View =
        design.titleBlock(title, subtitle, center)

    // ---------- [MamaSelectableCard] ----------

    /**
     * A choice row (reference screen 05): a sand circle with a line icon, a
     * serif title, a subtitle, optional trailing text. Selected: a moss
     * outline and a moss circle with a white icon.
     */
    fun selectableCard(
        icon: Icon,
        title: String,
        subtitle: String,
        selected: Boolean,
        trailing: String? = null,
        onClick: () -> Unit,
    ): View = row().apply {
        setPadding(dp(14), dp(13), dp(18), dp(13))
        minimumHeight = dp(80)
        // As on the approved series screen: cream rows, the chosen one outlined in moss with a moss circle.
        // The chosen row stands out at a glance: a pale olive fill and a clear moss outline.
        val base = if (selected) shape(Palette.SageSelected, Radii.CARD, Palette.Moss, 2f) else design.surface(Palette.Card, Radii.CARD)
        background = pressable(base, Radii.CARD)
        design.elevate(this, if (selected) 3f else 1.5f)
        addView(design.iconCircle(icon, 52, if (selected) CircleStyle.FILLED else CircleStyle.SAND))
        val texts = column()
        texts.addView(design.text(title, Typo.VALUE, if (selected) Palette.Moss else Palette.Ink).apply { textSize = 23f })
        texts.addView(design.text(subtitle, Typo.LABEL, Palette.InkMuted), gap(3))
        addView(texts, weight().apply { leftMargin = dp(16) })
        trailing?.let {
            addView(design.text(it, Typo.VALUE, if (selected) Palette.Moss else Palette.Ink), wrap().apply { leftMargin = dp(8) })
        }
        isSelected = selected
        clickable(this, onClick)
    }

    // ---------- [MamaTimeField] ----------

    /**
     * "Начало" / "Окончание" card from reference screen 4: a date row
     * (calendar icon) and a time row (clock icon, large digits). Rows with a
     * click handler show a chevron; read-only rows do not.
     */
    fun timeField(label: String, date: String, time: String, onDate: (() -> Unit)?, onTime: (() -> Unit)?, dateNote: String? = null): View =
        card(paddingDp = 0).apply {
            addView(text(label, MamaType.CAPTION, muted).apply { setPadding(dp(18), dp(14), dp(18), 0) })
            addView(fieldRow(Icon.CALENDAR, date, MamaType.TITLE, dateNote, onDate))
            addView(View(context).apply { setBackgroundColor(MamaColors.Border) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                leftMargin = dp(54); rightMargin = dp(18)
            })
            addView(fieldRow(Icon.CLOCK, time, MamaType.DIGITS, null, onTime))
        }

    private fun fieldRow(icon: Icon, value: String, type: MamaType, note: String?, onClick: (() -> Unit)?): View = row().apply {
        setPadding(dp(18), dp(12), dp(16), dp(12))
        minimumHeight = dp(54)
        addView(icon(icon, if (onClick != null) Palette.OliveInk else MamaColors.TextSecondary, 22))
        val texts = column()
        texts.addView(text(value, type, if (onClick != null) ink else muted))
        note?.let { texts.addView(text(it, MamaType.SMALL, muted), gap(2)) }
        addView(texts, weight().apply { leftMargin = dp(14) })
        if (onClick != null) {
            addView(icon(Icon.CHEVRON_RIGHT, Palette.InkSoft, 18))
            background = pressable(shape(Color.TRANSPARENT, 0), 0)
            clickable(this, onClick)
        }
    }

    /** Small icon + caption line (hints under the time cards). */
    fun hintRow(icon: Icon, value: String): View = row().apply {
        gravity = Gravity.TOP
        addView(icon(icon, Palette.Olive, 18), size(18).apply { topMargin = dp(1) })
        addView(text(value, MamaType.CAPTION, muted), weight().apply { leftMargin = dp(12) })
    }

    // ---------- [MamaPermissionCard] ----------

    fun permissionCard(icon: Icon, title: String, why: String, status: PermissionStatus, onClick: () -> Unit): View = row().apply {
        setPadding(dp(14), dp(14), dp(14), dp(14))
        minimumHeight = dp(76)
        val base = design.surface(MamaColors.PureCard, Radii.CARD)
        background = if (status == PermissionStatus.GRANTED) base else pressable(base, Radii.CARD)
        design.elevate(this, 2f)
        addView(design.iconCircle(icon, 44))
        val texts = column()
        texts.addView(design.text(title, Typo.VALUE).apply { textSize = 19f })
        texts.addView(design.text(why, Typo.CAPTION, Palette.InkMuted), gap(4))
        addView(texts, weight().apply { leftMargin = dp(14); rightMargin = dp(10) })
        when (status) {
            PermissionStatus.GRANTED -> addView(FrameLayout(context).apply {
                background = oval(Palette.OliveDeep)
                addView(icon(Icon.CHECK, MamaColors.White, 14), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
            }, size(24))
            PermissionStatus.REQUIRED -> addView(statusBadge("Нужно", Tone.WARNING))
            PermissionStatus.MISSING -> addView(icon(Icon.CHEVRON_RIGHT, Palette.InkSoft, 18))
        }
        if (status != PermissionStatus.GRANTED) clickable(this, onClick)
    }

    // ---------- [MamaStatusBadge] ----------

    /** A fully rounded status pill; "Нужно" is clay on pale clay, never an alarm. */
    private fun statusBadge(label: String, tone: Tone): TextView = text(label, MamaType.SMALL, inkOf(tone)).apply {
        typeface = MamaFonts.ui(context, 700)
        background = shape(fillOf(tone), Radii.TAG)
        setPadding(dp(11), dp(5), dp(11), dp(5))
    }

    private fun fillOf(tone: Tone) = when (tone) {
        Tone.SUCCESS -> MamaColors.EmeraldTint
        Tone.WARNING -> MamaColors.DangerSoft
        Tone.DANGER -> MamaColors.DangerSoft
        Tone.NEUTRAL -> MamaColors.WarmCard
        Tone.BRAND -> MamaColors.EmeraldTint
    }

    private fun inkOf(tone: Tone) = when (tone) {
        Tone.SUCCESS -> MamaColors.Success
        Tone.WARNING -> MamaColors.Warning
        Tone.DANGER -> MamaColors.Warning
        Tone.NEUTRAL -> MamaColors.TextSecondary
        Tone.BRAND -> MamaColors.Emerald
    }

    // ---------- [MamaInfoCard] ----------

    /** A calm notice ([DesignKit.noteCard]); a warning is warm earth and clay, never red. */
    fun infoCard(message: CharSequence, icon: Icon = Icon.INFO, tone: Tone = Tone.NEUTRAL, title: String? = null): View =
        design.noteCard(message, title, if (tone == Tone.WARNING || tone == Tone.DANGER) NoteTone.CARE else NoteTone.INFO, icon)

    fun divider(): View = View(context).apply {
        setBackgroundColor(MamaColors.Border)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    // ---------- progress dots ----------

    /** One dot per period: done in deep olive, ended early in warm earth, the rest an outline. */
    fun dots(total: Int, done: Int, failed: Int = 0, sizeDp: Int = 14): View = row().apply {
        gravity = Gravity.CENTER
        repeat(total) { i ->
            val d = when {
                i < done -> oval(Palette.OliveDeep)
                i < done + failed -> oval(Palette.Sand)
                else -> oval(Color.TRANSPARENT, V2.SageDeep, 1.5f)
            }
            addView(View(context).apply { background = d }, size(sizeDp).apply { setMargins(dp(5), 0, dp(5), 0) })
        }
    }

    // ---------- inputs ----------

    /** A text field ([DesignKit.textField]). */
    fun textField(hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT, multiLine: Boolean = false): EditText =
        design.textField(hint, inputType, multiLine)

    fun fieldBackground(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(MamaColors.PureCard, Radii.CARD_SMALL, Palette.OliveLine, 1.6f))
        addState(intArrayOf(), shape(MamaColors.PureCard, Radii.CARD_SMALL, MamaColors.Border))
    }

    /** Two or three options in a row; the chosen one in deep olive. */
    fun segmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit): View = row().apply {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val b = BrandButtonView(context).apply {
                text = label
                MamaType.BUTTON.applyTo(this)
                textSize = 14.5f
                includeFontPadding = false
                gravity = Gravity.CENTER
                minHeight = dp(50)
                setTextColor(if (on) MamaColors.White else MamaColors.Graphite)
                background = if (on) pressable(shape(Palette.OliveDeep, 26), 26, MamaColors.RippleOnDark) else pressable(shape(MamaColors.WarmCard, 26, V2.Sand, 1f), 26)
                clickable(this) { onPick(i) }
            }
            addView(b, weight().apply { if (i > 0) leftMargin = dp(10) })
        }
    }
}
