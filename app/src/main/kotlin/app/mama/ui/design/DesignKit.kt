package app.mama.ui.design

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.mama.ui.BrandButtonView
import app.mama.ui.Icon
import app.mama.ui.IconDrawable
import app.mama.ui.MamaFonts

/** Look of an icon circle. */
enum class CircleStyle { MINT, FILLED, SAND, WHITE, TEST }

/** Colour of a tag. */
enum class TagTone { MINT, SAND, TEST }

/** A notice card: [INFO] calm emerald, [CARE] warm sand (instead of any warning red). */
enum class NoteTone { INFO, CARE }

/** Colour of the quote mark: emerald on setup screens, warm sand on home and recovery. */
enum class QuoteAccent { EMERALD, SAND }

/** One row of a [DesignKit.summaryCard] ("Твой план"). */
data class SummaryItem(val icon: Icon, val label: String, val value: CharSequence, val onClick: (() -> Unit)? = null)

/**
 * UI v1 components (DESIGN_SYSTEM_V1 §5), built from [Palette], [Typo],
 * [Spacing] and [Radii]. Plain Android views, no Material components.
 * Screens are assembled from these in PHASE 2; nothing here holds product
 * logic — every action is a callback.
 */
class DesignKit(val context: Context) {

    fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    fun dpf(v: Float): Float = v * context.resources.displayMetrics.density

    // ---------------- layout params ----------------

    fun fill(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    fun gap(topDp: Int): LinearLayout.LayoutParams = fill().apply { topMargin = dp(topDp) }
    fun weight(w: Float = 1f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
    fun wrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    fun size(wDp: Int, hDp: Int = wDp) = LinearLayout.LayoutParams(dp(wDp), dp(hDp))
    fun centered(topDp: Int = 0) = wrap().apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(topDp) }

    fun column(paddingDp: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
    }

    fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    // ---------------- surfaces ----------------

    fun shape(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Float = 1f, dashed: Boolean = false) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dpf(radiusDp.toFloat())
        stroke?.let {
            val width = dpf(strokeDp).toInt().coerceAtLeast(1)
            if (dashed) setStroke(width, it, dpf(5f), dpf(4f)) else setStroke(width, it)
        }
    }

    fun oval(fill: Int, stroke: Int? = null, strokeDp: Float = 1.5f) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        stroke?.let { setStroke(dpf(strokeDp).toInt(), it) }
    }

    /** Touch feedback over [content], clipped to its rounded shape. */
    fun pressable(content: Drawable, radiusDp: Int, ripple: Int = Palette.Ripple): Drawable =
        RippleDrawable(ColorStateList.valueOf(ripple), content, shape(Color.WHITE, radiusDp))

    /** Soft warm shadow (emerald glow for the main button); tinted shadows need Android 9+. */
    fun elevate(view: View, levelDp: Float, color: Int = Palette.Shadow) {
        val tinted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        view.elevation = dpf(if (tinted) levelDp else levelDp * 0.5f)
        if (tinted) {
            view.outlineAmbientShadowColor = color
            view.outlineSpotShadowColor = color
        }
    }

    private fun clickable(view: View, onClick: () -> Unit) {
        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener { onClick() }
    }

    // ---------------- text ----------------

    fun text(value: CharSequence, style: Typo = Typo.BODY, color: Int = Palette.Ink, center: Boolean = false) = TextView(context).apply {
        text = value
        style.applyTo(this)
        setTextColor(color)
        if (center) gravity = Gravity.CENTER_HORIZONTAL
    }

    fun title(value: CharSequence, center: Boolean = false) = text(value, Typo.DISPLAY, Palette.Ink, center)
    fun subtitle(value: CharSequence, center: Boolean = false) = text(value, Typo.SUBTITLE, Palette.InkMuted, center)

    /**
     * Screen title and the two lines under it. [narrow]: the subtitle keeps to
     * the left part of the screen, where the landscape is veiled (as on the references).
     */
    fun titleBlock(title: CharSequence, subtitle: CharSequence? = null, center: Boolean = false, narrow: Boolean = false): LinearLayout = column().apply {
        addView(title(title, center), fill())
        subtitle?.let {
            addView(subtitle(it, center).apply {
                if (narrow) maxWidth = (context.resources.displayMetrics.widthPixels * 0.66f).toInt()
            }, if (narrow) wrap().apply { topMargin = dp(10) } else gap(10))
        }
    }

    /** "6 ч 12 мин": numbers large, units smaller (lock timer, home values). */
    fun hoursMinutes(hours: Long, minutes: Long): CharSequence = SpannableStringBuilder().apply {
        fun part(value: String, scale: Float) {
            val start = length
            append(value)
            if (scale != 1f) setSpan(RelativeSizeSpan(scale), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (hours > 0) {
            part("$hours", 1f)
            part(" ч ", 0.62f)
        }
        part("$minutes", 1f)
        part(" мин", 0.62f)
    }

    // ---------------- icons ----------------

    fun icon(icon: Icon, color: Int = Palette.EmeraldInk, sizeDp: Int = 22, solid: Boolean = true): ImageView = ImageView(context).apply {
        setImageDrawable(IconDrawable(icon, color, 2.0f, solid))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Icon in a soft circle, as on every reference card. */
    fun iconCircle(icon: Icon, sizeDp: Int = 44, style: CircleStyle = CircleStyle.MINT): FrameLayout = FrameLayout(context).apply {
        val (fill, tint) = when (style) {
            CircleStyle.MINT -> Palette.MintBadge to Palette.EmeraldInk
            CircleStyle.FILLED -> Palette.EmeraldDeep to Palette.OnEmerald
            CircleStyle.SAND -> Palette.SandSoft to Palette.Sand
            CircleStyle.WHITE -> Palette.Card to Palette.EmeraldInk
            CircleStyle.TEST -> Palette.TestFill to Palette.TestInk
        }
        background = oval(fill)
        val inner = (sizeDp * 0.48f).toInt()
        addView(ImageView(context).apply { setImageDrawable(IconDrawable(icon, tint, 2.0f, solid = true)) }, FrameLayout.LayoutParams(dp(inner), dp(inner), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun chevron(color: Int = Palette.InkSoft, sizeDp: Int = 20) = icon(Icon.CHEVRON_RIGHT, color, sizeDp, solid = false)

    // ---------------- buttons ----------------

    /** Main action: emerald gradient pill, white label in the centre, "→" at the right edge. */
    fun primaryButton(label: String, arrow: Boolean = true, onClick: () -> Unit): BrandButtonView = BrandButtonView(context).apply {
        text = label
        Typo.BUTTON.applyTo(this)
        setTextColor(Palette.OnEmerald)
        gravity = Gravity.CENTER
        minHeight = dp(Spacing.BUTTON)
        minimumHeight = dp(Spacing.BUTTON)
        val radius = dp(Spacing.BUTTON)
        val pill = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.EmeraldLight, Palette.EmeraldDeep)).apply {
            cornerRadius = radius.toFloat()
        }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(0x3A, 0xFF, 0xFF, 0xFF), Color.argb(0, 0xFF, 0xFF, 0xFF))).apply {
            cornerRadius = radius.toFloat()
            setStroke(dp(1), Color.argb(0x30, 0xFF, 0xFF, 0xFF))
        }
        background = RippleDrawable(ColorStateList.valueOf(Palette.RippleOnEmerald), LayerDrawable(arrayOf(pill, sheen)), shape(Color.WHITE, Spacing.BUTTON))
        val side = dp(22)
        if (arrow) {
            val d = IconDrawable(Icon.ARROW_RIGHT, Palette.OnEmerald, 2.0f).apply { setBounds(0, 0, dp(22), dp(22)) }
            setCompoundDrawables(null, null, d, null)
            // Balanced padding keeps the label in the centre of the pill.
            setPadding(side + dp(22) + dp(10), dp(14), side, dp(14))
            compoundDrawablePadding = dp(10)
        } else {
            setPadding(side, dp(14), side, dp(14))
        }
        elevate(this, 7f, Palette.Glow)
        clickable(this, onClick)
    }

    /** Secondary action: white pill, hairline outline, emerald label. */
    fun secondaryButton(label: String, icon: Icon? = null, onClick: () -> Unit): BrandButtonView = BrandButtonView(context).apply {
        text = label
        Typo.BUTTON.applyTo(this)
        setTextColor(Palette.EmeraldInk)
        gravity = Gravity.CENTER
        minHeight = dp(Spacing.BUTTON)
        minimumHeight = dp(Spacing.BUTTON)
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = pressable(shape(Palette.Card, Spacing.BUTTON, Palette.Line), Spacing.BUTTON)
        icon?.let {
            val d = IconDrawable(it, Palette.EmeraldInk, 2.0f, solid = true).apply { setBounds(0, 0, dp(20), dp(20)) }
            setCompoundDrawables(d, null, null, null)
            compoundDrawablePadding = dp(10)
        }
        elevate(this, 2f)
        clickable(this, onClick)
    }

    /** Quiet text action ("Отменить запуск", "Изменить"). */
    fun linkButton(label: String, color: Int = Palette.EmeraldInk, onClick: () -> Unit): BrandButtonView = BrandButtonView(context).apply {
        text = label
        Typo.BUTTON.applyTo(this)
        textSize = 15f
        setTextColor(color)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = pressable(shape(Color.TRANSPARENT, 22), 22)
        clickable(this, onClick)
    }

    // ---------------- top bar, progress ----------------

    /** "M A M A". */
    fun wordmark(color: Int = Palette.Ink): TextView = text("MAMA", Typo.WORDMARK, color)

    /** "‹" · M A M A · "N из M" (or a [trailing] view such as a gear). No back arrow when [onBack] is null. */
    fun topBar(onBack: (() -> Unit)?, counter: String? = null, trailing: View? = null): View = FrameLayout(context).apply {
        if (onBack != null) {
            addView(FrameLayout(context).apply {
                background = pressable(oval(Color.TRANSPARENT), 22)
                addView(ImageView(context).apply { setImageDrawable(IconDrawable(Icon.BACK, Palette.Ink, 2.1f)) }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
                contentDescription = "Назад"
                clickable(this, onBack)
            }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
        }
        addView(wordmark(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        when {
            trailing != null -> addView(trailing, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
            counter != null -> addView(text(counter, Typo.STEP, Palette.InkMuted).apply { setPadding(0, 0, dp(4), 0) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        }
        setPadding(dp(8), 0, dp(14), 0)
        minimumHeight = dp(Spacing.TOP_BAR)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Spacing.TOP_BAR))
    }

    /** A round icon button for the top bar (settings gear). */
    fun barButton(icon: Icon, description: String, onClick: () -> Unit): View = FrameLayout(context).apply {
        background = pressable(oval(Color.TRANSPARENT), 22)
        addView(ImageView(context).apply { setImageDrawable(IconDrawable(icon, Palette.Ink, 1.9f)) }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        contentDescription = description
        clickable(this, onClick)
        layoutParams = FrameLayout.LayoutParams(dp(44), dp(44))
        minimumWidth = dp(44)
        minimumHeight = dp(44)
    }

    /** Step header of the setup wizard: top bar and step segments ("2 из 6"). */
    fun stepHeader(step: Int, total: Int, onBack: (() -> Unit)?): View = column().apply {
        addView(topBar(onBack, "$step из $total"))
        addView(segments(step, total), gap(4).apply { leftMargin = dp(Spacing.SCREEN); rightMargin = dp(Spacing.SCREEN) })
    }

    /** Segmented progress: [done] emerald segments (dark to light), the rest sand. Steps of a wizard, days of a series. */
    fun segments(done: Int, total: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        for (i in 0 until total) {
            val color = if (i < done) progressColor(if (done <= 1) 0f else i / (done - 1f)) else Palette.ProgressOff
            addView(View(context).apply { background = shape(color, Radii.SEGMENT) }, LinearLayout.LayoutParams(0, dp(6), 1f).apply {
                if (i > 0) leftMargin = dp(4)
            })
        }
    }

    private fun progressColor(t: Float): Int =
        if (t < 0.5f) blend(Palette.ProgressDark, Palette.ProgressMid, t * 2) else blend(Palette.ProgressMid, Palette.ProgressLight, (t - 0.5f) * 2)

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    // ---------------- chips, tags, badges ----------------

    /** Amount chip ("1 000 ₽"); selected: mint with an emerald outline. */
    fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView = text(label, Typo.VALUE, if (selected) Palette.EmeraldInk else Palette.Ink, center = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        background = if (selected) {
            pressable(shape(Palette.MintSelected, Radii.CHIP, Palette.EmeraldBorder, 1.6f), Radii.CHIP)
        } else {
            pressable(shape(Palette.Card, Radii.CHIP, Palette.Line, 1f), Radii.CHIP)
        }
        if (selected) elevate(this, 2f)
        isSelected = selected
        clickable(this, onClick)
    }

    /** Chips in rows of [columns]; a short last row stretches to the full width. */
    fun chipGrid(labels: List<String>, selected: Int, columns: Int = 3, onPick: (Int) -> Unit): View = column().apply {
        labels.chunked(columns).forEachIndexed { r, line ->
            val row = row()
            line.forEachIndexed { i, label ->
                val index = r * columns + i
                row.addView(chip(label, index == selected) { onPick(index) }, weight().apply { if (i > 0) leftMargin = dp(10) })
            }
            addView(row, gap(if (r == 0) 0 else 10))
        }
    }

    /** Small capital tag: "РЕКОМЕНДУЕМ", "ВЕЧЕРОМ". */
    fun tag(label: String, tone: TagTone = TagTone.MINT): TextView {
        val (fill, ink) = when (tone) {
            TagTone.MINT -> Palette.MintTag to Palette.EmeraldInk
            TagTone.SAND -> Palette.SandSoft to Palette.SandInk
            TagTone.TEST -> Palette.TestFill to Palette.TestInk
        }
        return text(label, Typo.TAG, ink).apply {
            background = shape(fill, Radii.TAG)
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
    }

    /** A non-clickable pill ("Я держу курс"): a statement, never a button. */
    fun badge(label: String, icon: Icon? = null): View = row().apply {
        background = shape(Palette.MintTag, 16)
        setPadding(dp(12), dp(7), dp(14), dp(7))
        icon?.let { addView(icon(it, Palette.EmeraldInk, 15), size(15).apply { rightMargin = dp(7) }) }
        addView(text(label, Typo.LABEL, Palette.EmeraldInk).apply { typeface = MamaFonts.ui(context, 700) })
        isClickable = false
        isFocusable = false
    }

    // ---------------- cards ----------------

    /** White card with a soft warm shadow. */
    fun card(paddingDp: Int = Spacing.INNER, fill: Int = Palette.Card, radiusDp: Int = Radii.CARD): LinearLayout = column(paddingDp).apply {
        background = shape(fill, radiusDp)
        elevate(this, 3f)
    }

    /** Quiet card (quotes, secondary information): cream, almost no shadow. */
    fun quietCard(paddingDp: Int = Spacing.INNER): LinearLayout = column(paddingDp).apply {
        background = shape(Palette.CardQuiet, Radii.CARD)
        elevate(this, 1f)
    }

    /** Card-row action: icon circle, title, subtitle, "›" ("Вернуться на главную"). */
    fun actionRow(icon: Icon, title: String, subtitle: String? = null, style: CircleStyle = CircleStyle.MINT, onClick: (() -> Unit)?): View = row().apply {
        setPadding(dp(14), dp(14), dp(14), dp(14))
        minimumHeight = dp(76)
        val base = shape(Palette.Card, Radii.CARD)
        background = if (onClick != null) pressable(base, Radii.CARD) else base
        elevate(this, 3f)
        addView(iconCircle(icon, 50, style))
        val texts = column()
        texts.addView(text(title, Typo.VALUE))
        subtitle?.let { texts.addView(text(it, Typo.LABEL, Palette.InkMuted), gap(3)) }
        addView(texts, weight().apply { leftMargin = dp(14); rightMargin = dp(8) })
        if (onClick != null) {
            addView(chevron())
            clickable(this, onClick)
        }
    }

    /** Half-width action ("Экстренный вызов", "Доверенный контакт" on the lock screen). */
    fun actionTile(icon: Icon, label: String, onClick: () -> Unit): View = row().apply {
        setPadding(dp(12), dp(12), dp(8), dp(12))
        minimumHeight = dp(66)
        background = pressable(shape(Palette.Card, Radii.CARD), Radii.CARD)
        elevate(this, 3f)
        addView(iconCircle(icon, 38))
        addView(text(label, Typo.LABEL, Palette.Ink).apply { typeface = MamaFonts.ui(context, 600); textSize = 14f; maxLines = 2 }, weight().apply { leftMargin = dp(10); rightMargin = dp(2) })
        addView(chevron(sizeDp = 16))
        contentDescription = label.replace('\n', ' ')
        clickable(this, onClick)
    }

    /** "Твой план": rows with icon circle, label, value, "›". */
    fun summaryCard(title: String?, items: List<SummaryItem>): View = card(paddingDp = 0).apply {
        title?.let { addView(text(it, Typo.VALUE).apply { setPadding(dp(18), dp(16), dp(18), dp(4)) }) }
        items.forEachIndexed { i, item ->
            if (i > 0) addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { leftMargin = dp(16 + 40 + 14); rightMargin = dp(16) })
            addView(summaryRow(item))
        }
        setPadding(0, 0, 0, dp(4))
    }

    fun summaryRow(item: SummaryItem): View = row().apply {
        setPadding(dp(16), dp(9), dp(14), dp(9))
        minimumHeight = dp(60)
        addView(iconCircle(item.icon, 40))
        val texts = column()
        texts.addView(text(item.label, Typo.LABEL, Palette.InkMuted))
        texts.addView(text(item.value, Typo.VALUE), gap(2))
        addView(texts, weight().apply { leftMargin = dp(14); rightMargin = dp(8) })
        item.onClick?.let {
            addView(chevron())
            background = pressable(ColorDrawable(Color.TRANSPARENT), 0)
            clickable(this, it)
        }
    }

    /**
     * Choice card ("3 дня", "7 дней", "FLEX"): icon circle, title, optional
     * tag, description, landscape at the bottom. Selected: mint, emerald
     * outline, filled circle.
     */
    fun selectableCard(
        icon: Icon,
        title: String,
        body: String,
        selected: Boolean,
        tag: String? = null,
        scenery: Scenery? = Scenery.DAWN,
        onClick: () -> Unit,
    ): View = column().apply {
        val radius = dpf(Radii.CARD.toFloat())
        val base = if (selected) Palette.MintSelected else Palette.Card
        val fill: Drawable = scenery?.let { ScenicBackground(context, it, SceneLayout.CARD_BOTTOM, base, radius) } ?: shape(base, Radii.CARD)
        val layers = if (selected) LayerDrawable(arrayOf(fill, shape(Color.TRANSPARENT, Radii.CARD, Palette.EmeraldBorder, 1.8f))) else LayerDrawable(arrayOf(fill))
        background = RippleDrawable(ColorStateList.valueOf(Palette.Ripple), layers, shape(Color.WHITE, Radii.CARD))
        outlineProvider = roundOutline(radius)
        clipToOutline = false
        elevate(this, if (selected) 5f else 3f)
        setPadding(dp(12), dp(14), dp(10), dp(14))
        val head = row().apply { gravity = Gravity.TOP }
        head.addView(iconCircle(icon, 42, if (selected) CircleStyle.FILLED else CircleStyle.MINT))
        val texts = column()
        texts.addView(text(title, Typo.TITLE))
        tag?.let { texts.addView(tag(it).apply { maxLines = 1 }, wrap().apply { topMargin = dp(6) }) }
        texts.addView(text(body, Typo.LABEL, Palette.InkMuted), gap(8))
        head.addView(texts, weight().apply { leftMargin = dp(10) })
        addView(head)
        // Room for the landscape.
        if (scenery != null) addView(View(context), LinearLayout.LayoutParams(1, dp(58)))
        isSelected = selected
        clickable(this, onClick)
    }

    /**
     * An offer with its own action ("Начать заново · Restart · 299 ₽"):
     * icon circle, title, a line (price), text, the action inside the card
     * and an optional landscape on the right.
     */
    fun offerCard(icon: Icon, title: String, line: String?, message: String, action: View, scenery: Scenery? = null): View = column().apply {
        val radius = dpf(Radii.CARD.toFloat())
        background = scenery?.let { ScenicBackground(context, it, SceneLayout.CARD_RIGHT, Palette.Card, radius) } ?: shape(Palette.Card, Radii.CARD)
        outlineProvider = roundOutline(radius)
        elevate(this, 4f)
        setPadding(dp(16), dp(18), dp(16), dp(16))
        val head = row().apply { gravity = Gravity.TOP }
        head.addView(iconCircle(icon, 62))
        val texts = column()
        texts.addView(text(title, Typo.HEADLINE))
        line?.let { texts.addView(text(it, Typo.VALUE), gap(6)) }
        texts.addView(text(message, Typo.LABEL, Palette.InkMuted), gap(8))
        head.addView(texts, weight().apply { leftMargin = dp(14); rightMargin = dp(if (scenery != null) 72 else 0) })
        addView(head)
        addView(action, gap(18))
    }

    /** A real value on the home screen: icon circle, label, big number, optional note (never invented data). */
    fun valueCard(icon: Icon, label: String, value: CharSequence, note: View? = null): View = row().apply {
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = shape(Palette.Card, Radii.CARD)
        elevate(this, 3f)
        addView(iconCircle(icon, 62))
        val texts = column()
        texts.addView(text(label, Typo.LABEL, Palette.InkMuted))
        texts.addView(text(value, Typo.NUMBER), gap(6))
        note?.let { texts.addView(it, wrap().apply { topMargin = dp(8) }) }
        addView(texts, weight().apply { leftMargin = dp(16) })
    }

    /** Calm notice. [NoteTone.CARE] (protection not ready, not enough balance) is warm sand, never red. */
    fun noteCard(message: CharSequence, title: String? = null, tone: NoteTone = NoteTone.INFO, icon: Icon = if (tone == NoteTone.CARE) Icon.HEART else Icon.SHIELD): View = row().apply {
        gravity = Gravity.TOP
        setPadding(dp(14), dp(14), dp(16), dp(14))
        background = shape(if (tone == NoteTone.CARE) Palette.CardQuiet else Palette.Card, Radii.CARD_SMALL)
        elevate(this, if (tone == NoteTone.CARE) 1f else 2f)
        addView(iconCircle(icon, 38, if (tone == NoteTone.CARE) CircleStyle.SAND else CircleStyle.MINT))
        val texts = column()
        title?.let { texts.addView(text(it, Typo.VALUE).apply { textSize = 15.5f }) }
        texts.addView(text(message, Typo.LABEL, Palette.InkMuted), if (title != null) gap(4) else fill())
        addView(texts, weight().apply { leftMargin = dp(12); topMargin = dp(if (title != null) 1 else 9) })
    }

    /**
     * The motivational text component: a quote card. Serif text (or sans on
     * the home screen), a large quote mark in emerald or warm sand, an
     * optional "— MAMA" and an optional landscape on the right.
     */
    fun quoteCard(
        phrase: String,
        accent: QuoteAccent = QuoteAccent.EMERALD,
        serif: Boolean = true,
        italic: Boolean = false,
        signature: String? = null,
        scenery: Scenery? = null,
        markInCircle: Boolean = false,
    ): View = row().apply {
        gravity = Gravity.TOP
        val radius = dpf(Radii.CARD.toFloat())
        background = scenery?.let { ScenicBackground(context, it, SceneLayout.CARD_RIGHT, Palette.CardQuiet, radius) } ?: shape(Palette.CardQuiet, Radii.CARD)
        outlineProvider = roundOutline(radius)
        elevate(this, 1.5f)
        setPadding(dp(16), dp(16), dp(if (scenery != null) 120 else 18), dp(18))
        val markColor = if (accent == QuoteAccent.SAND) Palette.Sand else Color.argb(0x99, 0x5A, 0x99, 0x74)
        if (markInCircle) {
            addView(FrameLayout(context).apply {
                background = oval(if (accent == QuoteAccent.SAND) Palette.SandSoft else Palette.MintBadge)
                addView(ImageView(context).apply { setImageDrawable(IconDrawable(Icon.QUOTE, markColor)) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }, size(46))
        } else {
            addView(ImageView(context).apply { setImageDrawable(IconDrawable(Icon.QUOTE, markColor)) }, size(32).apply { topMargin = dp(2) })
        }
        val texts = column()
        val style = when {
            !serif -> Typo.QUOTE_SANS
            italic -> Typo.QUOTE_ITALIC
            else -> Typo.QUOTE
        }
        texts.addView(text(phrase, style, if (serif) Palette.Ink else Palette.InkMuted))
        signature?.let { texts.addView(text("— $it", Typo.TAG, Palette.InkSoft).apply { isAllCaps = true }, gap(10)) }
        addView(texts, weight().apply { leftMargin = dp(12); topMargin = dp(if (markInCircle) 4 else 6) })
    }

    /** A centred serif line over a landscape (lock, welcome). */
    fun motivationLine(phrase: String, color: Int = Palette.Ink): TextView = text(phrase, Typo.QUOTE, color, center = true).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }

    /** Square check box and text ("Я понимаю условия…"); the whole row toggles. */
    fun checkRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit): View = row().apply {
        setPadding(dp(2), dp(8), 0, dp(8))
        minimumHeight = dp(48)
        val box = FrameLayout(context).apply {
            background = if (checked) shape(Palette.EmeraldDeep, Radii.CHECK) else shape(Palette.Card, Radii.CHECK, Palette.EmeraldBorder, 1.6f)
            if (checked) addView(ImageView(context).apply { setImageDrawable(IconDrawable(Icon.CHECK, Palette.OnEmerald, 2.6f)) }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }
        addView(box, size(28))
        addView(text(label, Typo.LABEL, Palette.InkMuted), weight().apply { leftMargin = dp(14) })
        isSelected = checked
        clickable(this) { onToggle(!checked) }
    }

    fun divider(): View = View(context).apply {
        setBackgroundColor(Palette.Line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtMost(2))
    }

    fun textField(hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT, multiLine: Boolean = false): EditText = EditText(context).apply {
        this.hint = hint
        this.inputType = inputType or if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        if (!multiLine) setSingleLine()
        Typo.BODY.applyTo(this)
        setTextColor(Palette.Ink)
        setHintTextColor(Palette.InkSoft)
        minHeight = dp(54)
        gravity = if (multiLine) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(Palette.Card, Radii.CARD_SMALL, Palette.EmeraldBorder, 1.6f))
            addState(intArrayOf(), shape(Palette.Card, Radii.CARD_SMALL, Palette.Line))
        }
    }

    // ---------------- lock timer ----------------

    /** The lock timer: ring, sprout, "Осталось", "6 ч 12 мин", "День 2 из 7". Bind with [TimerRing.set]. */
    fun timerRing(sizeDp: Int = 196): TimerRing {
        // The disc and its shadow belong to the container, so the ring and the text are drawn above them.
        val root = FrameLayout(context).apply {
            background = oval(Color.argb(0xEE, 0xFE, 0xFD, 0xF9))
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevate(this, 4f)
        }
        val ring = TimerRingView(context)
        root.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val inner = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        inner.addView(iconCircle(Icon.SPROUT, 44), size(44).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val caption = text("Осталось", Typo.LABEL, Palette.InkMuted, center = true)
        inner.addView(caption, centered(8))
        val value = text("", Typo.NUMBER, Palette.Ink, center = true)
        inner.addView(value, centered(6))
        val day = text("", Typo.LABEL, Palette.Ink, center = true).apply { typeface = MamaFonts.ui(context, 600) }
        inner.addView(day, centered(8))
        root.addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        return TimerRing(this, root, ring, caption, value, day)
    }

    // ---------------- test build ----------------

    /** "ТЕСТОВАЯ СБОРКА": amber, dashed — clearly not part of the product screens. */
    fun testBadge(label: String = "Тестовая сборка"): View = row().apply {
        background = shape(Palette.TestFill, 12, Palette.TestLine, 1.2f, dashed = true)
        setPadding(dp(10), dp(6), dp(12), dp(6))
        addView(icon(Icon.FLASK, Palette.TestInk, 14, solid = false), size(14).apply { rightMargin = dp(6) })
        addView(text(label, Typo.TAG, Palette.TestInk))
    }

    /** Button of the test tools (Настройки → Тестирование). */
    fun testButton(label: String, onClick: () -> Unit): BrandButtonView = BrandButtonView(context).apply {
        text = label
        Typo.BUTTON.applyTo(this)
        textSize = 15f
        setTextColor(Palette.TestInk)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = pressable(shape(Palette.Card, Radii.CHIP, Palette.TestLine, 1.2f, dashed = true), Radii.CHIP, Color.argb(0x22, 0x8A, 0x5E, 0x1E))
        clickable(this, onClick)
    }

    /** "Тестирование" block: dashed amber card with a flask, a note and test buttons. */
    fun testToolsCard(title: String, note: String, buttons: List<View>): View = column(Spacing.INNER).apply {
        background = shape(Palette.TestFill, Radii.CARD, Palette.TestLine, 1.2f, dashed = true)
        val head = row()
        head.addView(iconCircle(Icon.FLASK, 38, CircleStyle.TEST))
        val texts = column()
        texts.addView(text(title, Typo.VALUE, Palette.TestInk))
        texts.addView(text(note, Typo.CAPTION, Palette.SandInk), gap(3))
        head.addView(texts, weight().apply { leftMargin = dp(12) })
        addView(head)
        buttons.forEachIndexed { i, b -> addView(b, gap(if (i == 0) 14 else 8)) }
    }

    // ---------------- navigation ----------------

    /** Light bottom navigation: Главная · Статистика · Настройки, under a hairline. */
    fun bottomNav(selected: Int, onPick: (Int) -> Unit): View = column().apply {
        setBackgroundColor(Color.argb(0xF2, 0xFD, 0xFB, 0xF5))
        addView(divider())
        val items = row().apply { setPadding(dp(8), dp(6), dp(8), dp(8)) }
        listOf(Icon.HOME to "Главная", Icon.CHART to "Статистика", Icon.GEAR to "Настройки").forEachIndexed { i, (ic, label) ->
            val on = i == selected
            val color = if (on) Palette.EmeraldInk else Palette.InkSoft
            items.addView(column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, dp(2))
                addView(FrameLayout(context).apply {
                    if (on) background = shape(Palette.MintTag, 15)
                    addView(ImageView(context).apply { setImageDrawable(IconDrawable(ic, color, 1.9f, solid = on)) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(58), dp(30)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(text(label, Typo.CAPTION, color).apply { if (on) typeface = MamaFonts.ui(context, 700) }, centered(3))
                background = pressable(ColorDrawable(Color.TRANSPARENT), 14)
                clickable(this) { onPick(i) }
            }, weight())
        }
        addView(items, fill())
    }

    // ---------------- screen scaffold ----------------

    /** Scrolling content with the screen side margins. */
    fun body(topDp: Int = 26): LinearLayout = column().apply {
        setPadding(dp(Spacing.SCREEN), dp(topDp), dp(Spacing.SCREEN), dp(24))
        clipToPadding = false
    }

    /** Bottom action area: the main button and, under it, an optional quiet action. */
    fun bottomCta(primary: View, secondary: View? = null): LinearLayout = column().apply {
        addView(primary, fill())
        secondary?.let { addView(it, gap(4)) }
    }

    /**
     * Every v1 screen: [ScenicBackground] (or the plain page), top bar,
     * scrolling body, sticky bottom action, optional bottom navigation. The
     * system bar insets are added by the activity.
     */
    fun screen(
        scenery: Scenery?,
        layout: SceneLayout = SceneLayout.HEADER,
        header: View?,
        body: View,
        cta: View? = null,
        nav: View? = null,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = scenery?.let { ScenicBackground(context, it, layout) } ?: ColorDrawable(Palette.Bg)
        header?.let { addView(it, fill()) }
        addView(ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(body)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        cta?.let {
            addView(it, fill().apply {
                leftMargin = dp(Spacing.SCREEN); rightMargin = dp(Spacing.SCREEN); topMargin = dp(8); bottomMargin = dp(16)
            })
        }
        nav?.let { addView(it, fill()) }
    }

    private fun roundOutline(radius: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}

/** Handle of [DesignKit.timerRing]. */
class TimerRing(
    private val kit: DesignKit,
    val view: FrameLayout,
    private val ring: TimerRingView,
    val caption: TextView,
    private val value: TextView,
    private val day: TextView,
) {
    /** [progress]: 0 at the start of the lock, 1 at its end. */
    fun set(hoursLeft: Long, minutesLeft: Long, progress: Float, dayLabel: String) {
        value.text = kit.hoursMinutes(hoursLeft, minutesLeft)
        ring.progress = progress
        day.text = dayLabel
        day.visibility = if (dayLabel.isEmpty()) View.GONE else View.VISIBLE
    }
}
