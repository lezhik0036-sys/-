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
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** A button that fades when disabled, so a disabled action is visibly unavailable. */
class BrandButtonView(context: Context) : TextView(context) {
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }
}

/** How a block or badge is tinted. */
enum class Tone { SUCCESS, WARNING, DANGER, NEUTRAL, BRAND }

/** Button looks. */
enum class ButtonKind { PRIMARY, SECONDARY, GHOST, DANGER }

/** Permission state as the user sees it. */
enum class PermissionStatus { GRANTED, REQUIRED, MISSING }

/**
 * MAMA component kit: every screen is built from these, so the whole app
 * shares one look (rounded surfaces, soft borders, emerald accents, generous
 * spacing, large touch targets). Plain Android views, no system widgets look.
 */
class Kit(val context: Context) {

    fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    // ---------- primitives ----------

    fun text(value: CharSequence, type: MamaType = MamaType.BODY, color: Int = MamaColors.TextPrimary, center: Boolean = false) =
        TextView(context).apply {
            text = value
            type.applyTo(this)
            setTextColor(color)
            if (center) gravity = Gravity.CENTER_HORIZONTAL
        }

    fun caption(value: CharSequence, center: Boolean = false) =
        text(value, MamaType.CAPTION, MamaColors.TextSecondary, center)

    fun body(value: CharSequence, center: Boolean = false) =
        text(value, MamaType.BODY, MamaColors.TextSecondary, center)

    fun shape(fill: Int, radiusDp: Int = 22, stroke: Int? = null, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        stroke?.let { setStroke(dp(strokeDp), it) }
    }

    fun oval(fill: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        stroke?.let { setStroke(dp(2), it) }
    }

    /** Touch feedback over [content], clipped to the same rounded shape. */
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

    fun fill(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun gap(topDp: Int): LinearLayout.LayoutParams = fill().apply { topMargin = dp(topDp) }

    fun weight(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    fun wrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** A light rounded surface with a soft border. */
    fun card(paddingDp: Int = 18, fill: Int = MamaColors.SurfaceCard, stroke: Int? = MamaColors.BorderSoft, radiusDp: Int = 24) =
        column(paddingDp).apply { background = shape(fill, radiusDp, stroke) }

    private fun clickable(view: View, onClick: () -> Unit) {
        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener { onClick() }
    }

    // ---------- SectionTitle ----------

    /** Section heading with an optional one-line explanation. */
    fun sectionTitle(title: String, subtitle: String? = null): View = column().apply {
        setPadding(dp(4), dp(30), dp(4), dp(10))
        addView(text(title, MamaType.H2))
        subtitle?.let { addView(caption(it), gap(4)) }
    }

    // ---------- BrandButton ----------

    fun brandButton(label: String, kind: ButtonKind = ButtonKind.PRIMARY, onClick: () -> Unit): BrandButtonView =
        BrandButtonView(context).apply {
            text = label
            MamaType.BUTTON.applyTo(this)
            gravity = Gravity.CENTER
            minHeight = dp(56)
            setPadding(dp(22), dp(14), dp(22), dp(14))
            val (fill, fg, stroke) = when (kind) {
                ButtonKind.PRIMARY -> Triple(MamaColors.EmeraldPrimary, MamaColors.White, null)
                ButtonKind.SECONDARY -> Triple(MamaColors.SurfaceCard, MamaColors.EmeraldPrimary, MamaColors.BorderSoft)
                ButtonKind.GHOST -> Triple(Color.TRANSPARENT, MamaColors.EmeraldPrimary, null)
                ButtonKind.DANGER -> Triple(MamaColors.DangerSoft, MamaColors.DangerInk, null)
            }
            setTextColor(fg)
            val ripple = if (kind == ButtonKind.PRIMARY) Color.argb(0x33, 0xFF, 0xFF, 0xFF) else MamaColors.Ripple
            background = pressable(shape(fill, 28, stroke), 28, ripple)
            clickable(this) { onClick() }
        }

    // ---------- StatusBadge ----------

    fun statusBadge(label: String, tone: Tone): TextView = text(label, MamaType.CAPTION, inkOf(tone)).apply {
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        background = shape(fillOf(tone), 14)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    fun fillOf(tone: Tone) = when (tone) {
        Tone.SUCCESS -> MamaColors.SuccessSoft
        Tone.WARNING -> MamaColors.WarningSoft
        Tone.DANGER -> MamaColors.DangerSoft
        Tone.NEUTRAL -> MamaColors.BackgroundPrimary
        Tone.BRAND -> MamaColors.EmeraldTint
    }

    fun inkOf(tone: Tone) = when (tone) {
        Tone.SUCCESS -> MamaColors.SuccessInk
        Tone.WARNING -> MamaColors.WarningInk
        Tone.DANGER -> MamaColors.DangerInk
        Tone.NEUTRAL -> MamaColors.TextSecondary
        Tone.BRAND -> MamaColors.EmeraldPrimary
    }

    // ---------- StyledInfoBlock ----------

    /** A soft tinted block for explanations, warnings and outcomes. */
    fun infoBlock(message: CharSequence, tone: Tone = Tone.NEUTRAL, title: String? = null): View {
        val block = row().apply {
            gravity = Gravity.TOP
            background = shape(fillOf(tone), 20, if (tone == Tone.NEUTRAL) MamaColors.BorderSoft else null)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val mark = when (tone) {
            Tone.SUCCESS -> "✓"
            Tone.WARNING -> "!"
            Tone.DANGER -> "!"
            Tone.NEUTRAL, Tone.BRAND -> "i"
        }
        block.addView(
            text(mark, MamaType.CAPTION, MamaColors.White, center = true).apply {
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                background = oval(inkOf(tone))
            },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { topMargin = dp(1) },
        )
        val texts = column()
        title?.let { texts.addView(text(it, MamaType.TITLE, inkOf(tone).takeIf { tone != Tone.NEUTRAL } ?: MamaColors.TextPrimary)) }
        texts.addView(
            text(message, MamaType.CAPTION, if (tone == Tone.NEUTRAL || tone == Tone.BRAND) MamaColors.TextSecondary else inkOf(tone)),
            if (title != null) gap(2) else fill(),
        )
        block.addView(texts, weight().apply { leftMargin = dp(12) })
        return block
    }

    // ---------- ModeCard ----------

    /**
     * A selectable product/mode card: title, price tag, description and a
     * radio mark. The selected card gets an emerald border and tinted fill.
     */
    fun modeCard(
        title: String,
        price: String,
        description: String,
        selected: Boolean,
        tag: String? = null,
        onClick: () -> Unit,
    ): View {
        val card = column().apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            val fill = if (selected) MamaColors.EmeraldTint else MamaColors.SurfaceCard
            val stroke = if (selected) MamaColors.EmeraldPrimary else MamaColors.BorderSoft
            background = pressable(shape(fill, 24, stroke, if (selected) 2 else 1), 24)
            clickable(this, onClick)
        }
        val head = row()
        head.addView(radio(selected), LinearLayout.LayoutParams(dp(22), dp(22)))
        head.addView(text(title, MamaType.TITLE), weight().apply { leftMargin = dp(12) })
        head.addView(
            text(price, MamaType.TITLE, if (selected) MamaColors.EmeraldPrimary else MamaColors.TextPrimary),
        )
        card.addView(head)
        val sub = column().apply { setPadding(dp(34), 0, 0, 0) }
        tag?.let { sub.addView(statusBadge(it, if (selected) Tone.BRAND else Tone.NEUTRAL), wrap().apply { topMargin = dp(8) }) }
        sub.addView(caption(description), gap(6))
        card.addView(sub)
        return card
    }

    private fun radio(selected: Boolean): View = FrameLayout(context).apply {
        background = oval(if (selected) MamaColors.EmeraldPrimary else MamaColors.SurfaceCard, if (selected) null else MamaColors.OliveSoft)
        if (selected) {
            addView(
                View(context).apply { background = oval(MamaColors.White) },
                FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER),
            )
        }
    }

    // ---------- Chips ----------

    /** A row of single-choice chips that scrolls sideways if it does not fit. */
    fun chips(labels: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val line = row()
        labels.forEachIndexed { i, label ->
            val on = i == selected
            line.addView(
                text(label, MamaType.CAPTION, if (on) MamaColors.White else MamaColors.TextPrimary).apply {
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    minHeight = dp(40)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    background = pressable(
                        shape(if (on) MamaColors.EmeraldPrimary else MamaColors.SurfaceCard, 20, if (on) null else MamaColors.BorderSoft),
                        20,
                    )
                    clickable(this) { onPick(i) }
                },
                wrap().apply { if (i > 0) leftMargin = dp(8) },
            )
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(line)
        }
    }

    /** Two or three equal segments, one selected (emerald), on a soft track. */
    fun segmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit): View = row().apply {
        background = shape(MamaColors.EmeraldTint, 28)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        labels.forEachIndexed { i, label ->
            val on = i == selected
            addView(
                text(label, MamaType.BUTTON, if (on) MamaColors.White else MamaColors.EmeraldPrimary).apply {
                    textSize = 15f
                    gravity = Gravity.CENTER
                    minHeight = dp(46)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = if (on) shape(MamaColors.EmeraldPrimary, 24) else pressable(shape(Color.TRANSPARENT, 24), 24)
                    if (!on) clickable(this) { onPick(i) }
                },
                weight(),
            )
        }
    }

    // ---------- TimeCard ----------

    /**
     * A time or date shown as a large digital value on a card. Tapping it
     * opens MAMA's own digital picker (never an analog clock face).
     */
    fun timeCard(
        label: String,
        value: String,
        note: String? = null,
        enabled: Boolean = true,
        digits: Boolean = true,
        onClick: () -> Unit = {},
    ): View = column().apply {
        setPadding(dp(18), dp(14), dp(18), dp(14))
        val fill = if (enabled) MamaColors.SurfaceCard else MamaColors.BackgroundPrimary
        background = if (enabled) pressable(shape(fill, 22, MamaColors.BorderSoft), 22) else shape(fill, 22, MamaColors.BorderSoft)
        addView(text(label, MamaType.OVERLINE, MamaColors.TextSecondary))
        addView(
            text(value, if (digits) MamaType.DIGITS else MamaType.TITLE, if (enabled) MamaColors.TextPrimary else MamaColors.TextSecondary),
            gap(if (digits) 6 else 8),
        )
        note?.let { addView(caption(it), gap(4)) }
        if (enabled) clickable(this, onClick)
    }

    // ---------- Row card (zone etc.) ----------

    /** A tappable one-line setting: label, value, chevron. */
    fun settingRow(label: String, value: String, onClick: () -> Unit): View = row().apply {
        setPadding(dp(18), dp(14), dp(14), dp(14))
        minimumHeight = dp(56)
        background = pressable(shape(MamaColors.SurfaceCard, 22, MamaColors.BorderSoft), 22)
        val texts = column()
        texts.addView(text(label, MamaType.OVERLINE, MamaColors.TextSecondary))
        texts.addView(text(value, MamaType.BODY, MamaColors.TextPrimary), gap(4))
        addView(texts, weight())
        addView(text("›", MamaType.H2, MamaColors.EmeraldSecondary))
        clickable(this, onClick)
    }

    // ---------- StyledTextField ----------

    /** A text field on a white rounded surface; the border turns emerald while focused. */
    fun textField(hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(context).apply {
        this.hint = hint
        this.inputType = inputType
        setSingleLine()
        MamaType.BODY.applyTo(this)
        setTextColor(MamaColors.TextPrimary)
        setHintTextColor(MamaColors.OliveSoft)
        minHeight = dp(54)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = fieldBackground()
    }

    fun fieldBackground(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(MamaColors.White, 18, MamaColors.EmeraldPrimary, 2))
        addState(intArrayOf(), shape(MamaColors.White, 18, MamaColors.BorderSoft))
    }

    /** A small label above a field. */
    fun fieldLabel(value: String) = text(value, MamaType.OVERLINE, MamaColors.TextSecondary).apply {
        setPadding(dp(4), 0, 0, 0)
    }

    // ---------- ContactCard ----------

    fun contactCard(name: EditText, phone: EditText, onPick: () -> Unit): View = card(18).apply {
        addView(fieldLabel("Имя"))
        addView(name, gap(6))
        addView(fieldLabel("Телефон"), gap(14))
        addView(phone, gap(6))
        addView(brandButton("Выбрать из контактов", ButtonKind.SECONDARY, onPick), gap(16))
    }

    /** A read-only contact summary. */
    fun contactSummary(name: String, phone: String, caption: String = "Доверенный контакт"): View = card(16).apply {
        val line = row()
        line.addView(
            text(name.take(1).uppercase(), MamaType.TITLE, MamaColors.EmeraldPrimary, center = true).apply {
                gravity = Gravity.CENTER
                background = oval(MamaColors.EmeraldTint)
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        val texts = column()
        texts.addView(text(caption, MamaType.OVERLINE, MamaColors.TextSecondary))
        texts.addView(text(name, MamaType.TITLE), gap(2))
        texts.addView(caption(phone))
        line.addView(texts, weight().apply { leftMargin = dp(14) })
        addView(line)
    }

    // ---------- PermissionCard ----------

    fun permissionCard(title: String, why: String, status: PermissionStatus, onClick: () -> Unit): View {
        val (tone, label, mark) = when (status) {
            PermissionStatus.GRANTED -> Triple(Tone.SUCCESS, "Выдано", "✓")
            PermissionStatus.REQUIRED -> Triple(Tone.WARNING, "Требуется", "!")
            PermissionStatus.MISSING -> Triple(Tone.NEUTRAL, "Не выдано", "○")
        }
        val card = row().apply {
            gravity = Gravity.TOP
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val base = shape(MamaColors.SurfaceCard, 22, MamaColors.BorderSoft)
            background = if (status == PermissionStatus.GRANTED) base else pressable(base, 22)
            if (status != PermissionStatus.GRANTED) clickable(this, onClick)
        }
        card.addView(
            text(mark, MamaType.TITLE, inkOf(tone), center = true).apply {
                gravity = Gravity.CENTER
                background = oval(fillOf(tone).takeIf { tone != Tone.NEUTRAL } ?: MamaColors.BackgroundPrimary)
            },
            LinearLayout.LayoutParams(dp(36), dp(36)),
        )
        val texts = column()
        val head = row()
        head.addView(text(title, MamaType.TITLE).apply { textSize = 15f }, weight())
        head.addView(statusBadge(label, tone), wrap().apply { leftMargin = dp(8) })
        texts.addView(head)
        texts.addView(caption(why), gap(4))
        card.addView(texts, weight().apply { leftMargin = dp(14) })
        return card
    }

    // ---------- progress ----------

    /** Progress dots: done (emerald), failed (soft red), remaining (sage). */
    fun dots(total: Int, done: Int, failed: Int = 0): View = row().apply {
        gravity = Gravity.CENTER
        repeat(total) { i ->
            val color = when {
                i < done -> MamaColors.EmeraldPrimary
                i < done + failed -> MamaColors.DangerSoft
                else -> MamaColors.SageMuted
            }
            addView(
                View(context).apply { background = oval(color) },
                LinearLayout.LayoutParams(dp(14), dp(14)).apply { setMargins(dp(5), 0, dp(5), 0) },
            )
        }
    }

    /** A large round emblem with a symbol (result screens). */
    fun emblem(symbol: String, tone: Tone, sizeDp: Int = 72): View =
        text(symbol, MamaType.H1, inkOf(tone), center = true).apply {
            gravity = Gravity.CENTER
            background = oval(fillOf(tone))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }

    /** Small stat tile: caption + value. */
    fun stat(label: String, value: String): View = card(14).apply {
        addView(text(label, MamaType.OVERLINE, MamaColors.TextSecondary))
        addView(text(value, MamaType.H2), gap(6))
    }

    /** Hero illustration with rounded corners. */
    fun hero(heightDp: Int = 132): View = FrameLayout(context).apply {
        background = shape(MamaColors.SageMuted, 28)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        addView(
            View(context).apply { background = MountainsDrawable(night = false) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    }
}
