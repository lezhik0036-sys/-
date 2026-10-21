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
import android.text.TextUtils
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

/** A notice card: [INFO] calm olive, [CARE] warm earth (instead of any warning red). */
enum class NoteTone { INFO, CARE }

/** Colour of the quote mark: sage on setup screens, warm earth on home and recovery. */
enum class QuoteAccent { EMERALD, SAND }

/** One row of a [DesignKit.summaryCard] ("Твой план"); [note] is a small line under the value. */
data class SummaryItem(
    val icon: Icon,
    val label: String,
    val value: CharSequence,
    val note: CharSequence? = null,
    val onClick: (() -> Unit)? = null,
)

/** Handle of [DesignKit.valueRow]: its label and value can change (a live countdown). */
class ValueRow(val view: LinearLayout, val label: TextView, val value: TextView)

/**
 * The MAMA components (MAMA_DESIGN_SYSTEM_V2 «Компоненты»), built from
 * [Palette], [Typo], [Spacing] and [Radii]: one clay action per screen,
 * olive for choices and states, soft warm cards and pills on the [EarthPage].
 * Plain Android views, no Material components; nothing here holds product
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

    /**
     * Very soft depth: a wide, faint, warm shadow and a halo (a clay glow
     * for the main button). Tinted shadows need Android 9+; older phones get
     * a lower, plain elevation.
     */
    fun elevate(view: View, levelDp: Float, color: Int = Palette.Shadow, ambient: Int = Palette.ShadowAmbient) {
        val tinted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        view.elevation = dpf(if (tinted) levelDp * 2.2f else levelDp * 0.5f)
        if (tinted) {
            view.outlineAmbientShadowColor = ambient
            view.outlineSpotShadowColor = color
        }
    }

    /** Card surface: opaque fill, large radius, a soft sand rim instead of a border. */
    fun surface(fill: Int = Palette.Card, radiusDp: Int = Radii.CARD): GradientDrawable = shape(fill, radiusDp, Palette.Rim, 1f)

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
    fun titleBlock(
        title: CharSequence,
        subtitle: CharSequence? = null,
        center: Boolean = false,
        narrow: Boolean = false,
        /** A first line in ink above the muted subtitle. */
        lead: CharSequence? = null,
        /** A small neutral fact right under the title ("Серия завершилась раньше · 2 из 7"). */
        caption: CharSequence? = null,
    ): LinearLayout = column().apply {
        addView(title(title, center), fill())
        fun line(view: TextView, top: Int) = addView(view.apply {
            if (narrow) maxWidth = (context.resources.displayMetrics.widthPixels * 0.66f).toInt()
        }, if (narrow) wrap().apply { topMargin = dp(top) } else gap(top))
        caption?.let { line(text(it, Typo.LABEL, Palette.InkMuted, center), 10) }
        lead?.let { line(text(it, Typo.SUBTITLE, Palette.Ink, center), if (caption != null) 12 else 10) }
        subtitle?.let { line(subtitle(it, center), if (lead != null) 8 else if (caption != null) 12 else 10) }
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

    fun icon(icon: Icon, color: Int = Palette.OliveInk, sizeDp: Int = 22, solid: Boolean = true): ImageView = ImageView(context).apply {
        setImageDrawable(IconDrawable(icon, color, 2.0f, solid))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Icon in a soft circle, as on every reference card. */
    fun iconCircle(icon: Icon, sizeDp: Int = 44, style: CircleStyle = CircleStyle.MINT): FrameLayout = FrameLayout(context).apply {
        val (fill, tint) = when (style) {
            CircleStyle.MINT -> Palette.SageBadge to Palette.OliveInk
            CircleStyle.FILLED -> Palette.OliveDeep to Palette.OnAccent
            CircleStyle.SAND -> Palette.SandSoft to Palette.SandInk
            CircleStyle.WHITE -> Palette.Card to Palette.OliveInk
            CircleStyle.TEST -> Palette.TestFill to Palette.TestInk
        }
        background = oval(fill)
        val inner = (sizeDp * 0.52f).toInt()
        // Thin line icons in beige circles; a filled (chosen) circle keeps its solid mark.
        val filled = style == CircleStyle.FILLED
        addView(ImageView(context).apply { setImageDrawable(IconDrawable(icon, tint, if (filled) 2.3f else 1.7f, solid = filled)) }, FrameLayout.LayoutParams(dp(inner), dp(inner), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun chevron(color: Int = Palette.InkMuted, sizeDp: Int = 22) = icon(Icon.CHEVRON_RIGHT, color, sizeDp, solid = false)

    // ---------------- buttons ----------------

    /**
     * The one main action of a screen: a moss pill, lighter at the top, a
     * white serif label in the centre, "→" at the right edge, a soft moss
     * shadow; [large] for the decisive action of a screen.
     */
    fun primaryButton(label: String, arrow: Boolean = true, large: Boolean = false, onClick: () -> Unit): BrandButtonView = BrandButtonView(context).apply {
        text = label
        Typo.BUTTON.applyTo(this)
        if (large) textSize = 21f
        setTextColor(Palette.OnAccent)
        gravity = Gravity.CENTER
        val height = if (large) 58 else Spacing.BUTTON + 4
        minHeight = dp(height)
        minimumHeight = dp(height)
        val pill = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.MossLight, Palette.Moss, Palette.MossDeep)).apply {
            cornerRadius = dpf(height / 2f)
        }
        background = RippleDrawable(ColorStateList.valueOf(Palette.RippleOnAccent), pill, shape(Color.WHITE, height / 2))
        val side = dp(22)
        if (arrow) {
            val d = IconDrawable(Icon.ARROW_RIGHT, Palette.OnAccent, 2.0f).apply { setBounds(0, 0, dp(22), dp(22)) }
            setCompoundDrawables(null, null, d, null)
            // Balanced padding keeps the label in the centre of the pill.
            setPadding(side + dp(22) + dp(10), dp(14), side, dp(14))
            compoundDrawablePadding = dp(10)
        } else {
            setPadding(side, dp(14), side, dp(14))
        }
        elevate(this, 3f, Palette.Glow, Palette.GlowAmbient)
        clickable(this, onClick)
    }

    /** A round moss button with an icon (the "→" of the onboarding); [description] is what it says aloud. */
    fun roundButton(icon: Icon, description: String, sizeDp: Int = 60, onClick: () -> Unit): View = FrameLayout(context).apply {
        background = RippleDrawable(
            ColorStateList.valueOf(Palette.RippleOnAccent),
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.MossLight, Palette.Moss, Palette.MossDeep)).apply { shape = GradientDrawable.OVAL },
            oval(Color.WHITE),
        )
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevate(this, 3f, Palette.Glow, Palette.GlowAmbient)
        addView(ImageView(context).apply { setImageDrawable(IconDrawable(icon, Palette.OnAccent, 2.0f)) }, FrameLayout.LayoutParams(dp(sizeDp * 2 / 5), dp(sizeDp * 2 / 5), Gravity.CENTER))
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        clickable(this, onClick)
    }

    /** A second action that does not compete: a cream pill with a sand outline, a moss serif label. */
    fun secondaryButton(label: String, icon: Icon? = null, onClick: () -> Unit): BrandButtonView = BrandButtonView(context).apply {
        text = label
        Typo.BUTTON.applyTo(this)
        textSize = 18.5f
        setTextColor(Palette.OliveInk)
        gravity = Gravity.CENTER
        minHeight = dp(Spacing.BUTTON)
        minimumHeight = dp(Spacing.BUTTON)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        background = pressable(shape(Palette.Card, Spacing.BUTTON, V2.Sand, 1.2f), Spacing.BUTTON)
        icon?.let {
            val d = IconDrawable(it, Palette.OliveInk, 2.0f, solid = true).apply { setBounds(0, 0, dp(18), dp(18)) }
            setCompoundDrawables(d, null, null, null)
            compoundDrawablePadding = dp(10)
        }
        clickable(this, onClick)
    }

    /** Quiet text action ("Отменить запуск", "Изменить"). */
    fun linkButton(label: String, color: Int = Palette.OliveInk, onClick: () -> Unit): BrandButtonView = BrandButtonView(context).apply {
        text = label
        Typo.BUTTON.applyTo(this)
        textSize = 18f
        setTextColor(color)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = pressable(shape(Color.TRANSPARENT, 22), 22)
        clickable(this, onClick)
    }

    /**
     * A secondary action that stays at hand but never leads the screen: a
     * warm pill with a sand outline, a small olive icon, a muted label, no
     * shadow (lock).
     */
    fun quietAction(icon: Icon, label: String, onClick: () -> Unit): BrandButtonView = BrandButtonView(context).apply {
        text = label
        Typo.VALUE.applyTo(this)
        textSize = 17f
        setTextColor(Palette.InkMuted)
        gravity = Gravity.CENTER
        minHeight = dp(46)
        minimumHeight = dp(46)
        setPadding(dp(18), dp(10), dp(20), dp(10))
        // Nearly opaque: the sprigs of the earth page stay behind it, never show through.
        background = pressable(shape(Color.argb(0xF2, 0xFD, 0xF9, 0xF1), 23, Color.argb(0xB3, 0xE6, 0xD6, 0xBD), 1f), 23)
        val d = IconDrawable(icon, Palette.Olive, 1.8f, solid = true).apply { setBounds(0, 0, dp(18), dp(18)) }
        setCompoundDrawables(d, null, null, null)
        compoundDrawablePadding = dp(10)
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

    /** Steps of a path: [done] segments from deep olive to sage, the rest sand. Steps of a wizard, days of a series. */
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

    /**
     * The days of a series as a path of circles (1 … [total]): the [done]
     * ones moss with a check, the day now ([current], 1-based) ringed in moss,
     * the rest quiet beige; the number of each day under it.
     */
    fun dayCircles(done: Int, total: Int, current: Int? = null): View = row().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        val size = if (total > 7) 30 else 36
        for (i in 1..total) {
            val item = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
            val circle = FrameLayout(context).apply {
                background = when {
                    i <= done -> oval(Palette.Moss)
                    i == current -> oval(Palette.Card, Palette.Moss, 2f)
                    else -> oval(Palette.Card, V2.Sand, 1.5f)
                }
                val mark = if (i <= done) Palette.OnAccent else if (i == current) Palette.Moss else V2.Sand
                addView(ImageView(context).apply { setImageDrawable(IconDrawable(Icon.CHECK, mark, 2.2f)) }, FrameLayout.LayoutParams(dp(size / 2), dp(size / 2), Gravity.CENTER))
            }
            item.addView(circle, size(size))
            item.addView(text("$i", Typo.CAPTION, if (i <= (current ?: done)) Palette.Ink else Palette.InkSoft, center = true), centered(5))
            addView(item, weight())
        }
        contentDescription = "Пройдено $done из $total"
    }

    /** A number in a small card with its label under it ("17 · Ночей пройдено"). */
    fun tile(value: CharSequence, label: String): View = card(paddingDp = 14).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(text(value, Typo.NUMBER, Palette.Ink, center = true).apply { textSize = 34f; maxLines = 1 }, centered())
        addView(text(label, Typo.CAPTION, Palette.InkMuted, center = true), centered(4))
    }

    // ---------------- chips, tags, badges ----------------

    /** Amount chip ("1 000 ₽"); selected: sage mist with an olive outline. */
    fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView = text(label, Typo.VALUE, if (selected) Palette.OliveInk else Palette.Ink, center = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        background = if (selected) {
            pressable(shape(Palette.SageSelected, Radii.CHIP, Palette.OliveLine, 1.6f), Radii.CHIP)
        } else {
            pressable(shape(Palette.Card, Radii.CHIP, Palette.Line, 1.2f), Radii.CHIP)
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
            TagTone.MINT -> Palette.SageTag to Palette.OliveInk
            TagTone.SAND -> Palette.SandSoft to Palette.SandInk
            TagTone.TEST -> Palette.TestFill to Palette.TestInk
        }
        return text(label, Typo.TAG, ink).apply {
            background = shape(fill, Radii.TAG)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
    }

    /** A non-clickable pill ("Я держу курс"): a statement, never a button. */
    fun badge(label: String, icon: Icon? = null): View = row().apply {
        background = shape(Palette.SageTag, Radii.TAG)
        setPadding(dp(13), dp(7), dp(15), dp(7))
        icon?.let { addView(icon(it, Palette.OliveInk, 15), size(15).apply { rightMargin = dp(7) }) }
        addView(text(label, Typo.LABEL, Palette.OliveInk).apply { typeface = MamaFonts.ui(context, 700) })
        isClickable = false
        isFocusable = false
    }

    // ---------------- cards ----------------

    /** Milky card with a soft warm shadow. */
    fun card(paddingDp: Int = Spacing.INNER, fill: Int = Palette.Card, radiusDp: Int = Radii.CARD): LinearLayout = column(paddingDp).apply {
        background = surface(fill, radiusDp)
        elevate(this, 2f)
    }

    /** Quiet card (quotes, secondary information): the warm wash of the page, almost no shadow. */
    fun quietCard(paddingDp: Int = Spacing.INNER): LinearLayout = column(paddingDp).apply {
        background = surface(Palette.CardQuiet)
        elevate(this, 1f)
    }

    /** Card-row action: icon circle, title, subtitle, "›" ("Вернуться на главную"). */
    fun actionRow(icon: Icon, title: String, subtitle: String? = null, style: CircleStyle = CircleStyle.MINT, onClick: (() -> Unit)?): View = row().apply {
        setPadding(dp(16), dp(16), dp(16), dp(16))
        minimumHeight = dp(80)
        val base = surface()
        background = if (onClick != null) pressable(base, Radii.CARD) else base
        elevate(this, 2.5f)
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
    fun actionTile(icon: Icon, label: String, onClick: () -> Unit): View = column().apply {
        setPadding(dp(14), dp(14), dp(12), dp(14))
        minimumHeight = dp(104)
        background = pressable(surface(), Radii.CARD)
        elevate(this, 2.5f)
        val top = row()
        top.addView(iconCircle(icon, 40))
        top.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(chevron(sizeDp = 18))
        addView(top, fill())
        addView(text(label, Typo.LABEL, Palette.Ink).apply { textSize = 15f; maxLines = 2 }, gap(10))
        contentDescription = label.replace('\n', ' ')
        clickable(this, onClick)
    }

    /** "Твой план": rows with icon circle, label, value, "›"; with a [title], inside a light frame that carries it. */
    /** [large]: the few decisive values of a screen ("Всё готово"), bigger and with more air. */
    fun summaryCard(title: String?, items: List<SummaryItem>, large: Boolean = false): View {
        val inner = summaryRows(items, large)
        if (title == null) return inner
        return column().apply {
            background = shape(Color.argb(0xE6, 0xFE, 0xFD, 0xF9), Radii.CARD + 2)
            elevate(this, 2f)
            setPadding(0, 0, 0, 0)
            addView(text(title, Typo.VALUE).apply { setPadding(dp(18), dp(16), dp(18), dp(8)) })
            addView(inner, fill())
        }
    }

    private fun summaryRows(items: List<SummaryItem>, large: Boolean): LinearLayout = card(paddingDp = 0).apply {
        val circle = if (large) 46 else 40
        items.forEachIndexed { i, item ->
            if (i > 0) addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { leftMargin = dp(16 + circle + 14); rightMargin = dp(16) })
            addView(summaryRow(item, large))
        }
        setPadding(0, 0, 0, dp(4))
    }

    fun summaryRow(item: SummaryItem, large: Boolean = false): View = row().apply {
        setPadding(dp(16), dp(if (large) 12 else 8), dp(14), dp(if (large) 12 else 8))
        minimumHeight = dp(if (large) 70 else 58)
        addView(iconCircle(item.icon, if (large) 46 else 40))
        val texts = column()
        texts.addView(text(item.label, Typo.LABEL, Palette.InkMuted))
        texts.addView(text(item.value, if (large) Typo.TITLE else Typo.VALUE).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }, gap(if (large) 3 else 2))
        item.note?.let { texts.addView(text(it, Typo.CAPTION, Palette.InkMuted), gap(3)) }
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
        val base = if (selected) Palette.SageSelected else Palette.Card
        val fill: Drawable = scenery?.let { ScenicBackground(context, it, SceneLayout.CARD_BOTTOM, base, radius) } ?: shape(base, Radii.CARD)
        val layers = if (selected) LayerDrawable(arrayOf(fill, shape(Color.TRANSPARENT, Radii.CARD, Palette.OliveLine, 1.8f))) else LayerDrawable(arrayOf(fill))
        background = RippleDrawable(ColorStateList.valueOf(Palette.Ripple), layers, shape(Color.WHITE, Radii.CARD))
        outlineProvider = roundOutline(radius)
        clipToOutline = false
        elevate(this, if (selected) 3.5f else 2.5f)
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
        if (scenery != null) addView(View(context), LinearLayout.LayoutParams(1, dp(72)))
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
        background = scenery?.let { ScenicBackground(context, it, SceneLayout.CARD_RIGHT, Palette.Card, radius, vignette = 0.34f) } ?: surface()
        outlineProvider = roundOutline(radius)
        elevate(this, 3f)
        setPadding(dp(18), dp(20), dp(18), dp(18))
        // The action's soft glow spreads into the padding instead of being cut to a rectangle.
        clipToPadding = false
        val head = row().apply { gravity = Gravity.TOP }
        head.addView(iconCircle(icon, 62))
        val texts = column()
        texts.addView(text(title, Typo.HEADLINE).apply { maxLines = 1 })
        line?.let { texts.addView(text(it, Typo.VALUE), gap(6)) }
        texts.addView(text(message, Typo.LABEL, Palette.InkMuted), gap(8).apply { rightMargin = dp(if (scenery != null) 104 else 0) })
        head.addView(texts, weight().apply { leftMargin = dp(14) })
        addView(head)
        addView(action, gap(18))
    }

    /** A real value on the home screen: icon circle, label, big number, optional note (never invented data). */
    fun valueCard(icon: Icon, label: String, value: CharSequence, note: View? = null): View = valueRow(icon, label, value, note).view.apply {
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = surface()
        elevate(this, 2.5f)
    }

    /** The row of a [valueCard], for several values in one card ([rowsCard]). */
    fun valueRow(icon: Icon, label: String, value: CharSequence, note: View? = null, style: Typo = Typo.NUMBER): ValueRow {
        val row = row()
        row.addView(iconCircle(icon, 62))
        val texts = column()
        val l = text(label, Typo.LABEL, Palette.InkMuted)
        texts.addView(l)
        val v = text(value, style)
        texts.addView(v, gap(6))
        note?.let { texts.addView(it, wrap().apply { topMargin = dp(8) }) }
        row.addView(texts, weight().apply { leftMargin = dp(16) })
        return ValueRow(row, l, v)
    }

    /** One card of rows divided by hairlines that start after the icon circles. */
    fun rowsCard(rows: List<View>, paddingDp: Int = 0, dividerInsetDp: Int = 16 + 36 + 14): LinearLayout = card(paddingDp = 0).apply {
        rows.forEachIndexed { i, r ->
            if (i > 0) {
                addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    leftMargin = dp(dividerInsetDp); rightMargin = dp(16); topMargin = dp(paddingDp); bottomMargin = dp(paddingDp)
                })
            }
            addView(r, fill().apply { setMargins(dp(paddingDp), if (i == 0) dp(paddingDp) else 0, dp(paddingDp), if (i == rows.lastIndex) dp(paddingDp) else 0) })
        }
    }

    /** A line of information in a [rowsCard]: small icon circle, text (and a [note] under it), an optional action at the end ("Изменить"). */
    fun infoRow(icon: Icon, value: CharSequence, strong: Boolean = false, trailing: View? = null, note: CharSequence? = null): View = row().apply {
        setPadding(dp(16), dp(10), dp(if (trailing != null) 4 else 16), dp(10))
        minimumHeight = dp(58)
        addView(iconCircle(icon, 36))
        val texts = column()
        texts.addView(if (strong) text(value, Typo.VALUE) else text(value, Typo.LABEL, Palette.InkMuted))
        note?.let { texts.addView(text(it, Typo.CAPTION, Palette.InkMuted), gap(2)) }
        addView(texts, weight().apply { leftMargin = dp(14); rightMargin = dp(if (trailing != null) 4 else 0) })
        trailing?.let { addView(it) }
    }

    /**
     * One end of a lock window (reference 04), read at a glance: icon and
     * label with "›", the time in large digits, its part of day ("ВЕЧЕРОМ")
     * and a [note] under it ("сегодня"). Two side by side in [timeRange].
     * Without [onClick] it is only shown (test mode).
     */
    fun timeTile(label: String, time: String, part: String?, icon: Icon, note: String? = null, onClick: (() -> Unit)?): View = column().apply {
        background = if (onClick != null) pressable(surface(), Radii.CARD) else surface()
        elevate(this, 2.5f)
        setPadding(dp(16), dp(14), dp(12), dp(16))
        val top = row()
        top.addView(iconCircle(icon, 36))
        top.addView(text(label, Typo.LABEL, Palette.InkMuted).apply { textSize = 15f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }, weight().apply { leftMargin = dp(10) })
        if (onClick != null) top.addView(chevron(sizeDp = 18))
        addView(top, fill())
        // Its own width and one line: the time is never cut or wrapped.
        addView(text(time, Typo.AMOUNT).apply { isSingleLine = true }, wrap().apply { topMargin = dp(14) })
        part?.let { addView(tag(it).apply { maxLines = 1; setPadding(dp(10), dp(4), dp(10), dp(4)) }, wrap().apply { topMargin = dp(6) }) }
        note?.let { addView(text(it, Typo.LABEL, Palette.InkMuted).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }, wrap().apply { topMargin = dp(8) }) }
        onClick?.let { clickable(this, it) }
    }

    /** Start and end of a window side by side, equally high. */
    fun timeRange(start: View, end: View): View = row().apply {
        gravity = Gravity.TOP
        addView(start, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(end, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(12) })
    }

    /**
     * A person (reference 06): large circle, label, name, phone, "›"; a
     * landscape along the bottom only when a [scenery] is given.
     */
    fun contactCard(label: String, name: String, phone: String?, scenery: Scenery? = null, onClick: (() -> Unit)?): View = column().apply {
        val radius = dpf(Radii.CARD.toFloat())
        val fill: Drawable = scenery?.let { ScenicBackground(context, it, SceneLayout.CARD_BOTTOM, Palette.Card, radius) } ?: surface()
        background = if (onClick != null) RippleDrawable(ColorStateList.valueOf(Palette.Ripple), fill, shape(Color.WHITE, Radii.CARD)) else fill
        outlineProvider = roundOutline(radius)
        elevate(this, 2.5f)
        setPadding(dp(16), dp(18), dp(12), dp(14))
        val head = row().apply { gravity = Gravity.TOP }
        head.addView(iconCircle(Icon.PERSON, 80))
        val texts = column()
        texts.addView(text(label, Typo.LABEL, Palette.InkMuted))
        texts.addView(text(name, Typo.HEADLINE).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, gap(4))
        phone?.let { texts.addView(text(it, Typo.SUBTITLE, Palette.InkMuted), gap(6)) }
        head.addView(texts, weight().apply { leftMargin = dp(18); topMargin = dp(6); rightMargin = dp(6) })
        if (onClick != null) head.addView(chevron(), wrap().apply { topMargin = dp(24) })
        addView(head)
        // Room for the landscape.
        if (scenery != null) addView(View(context), LinearLayout.LayoutParams(1, dp(96)))
        onClick?.let { clickable(this, it) }
    }

    /** A short supportive line on a setup step (references 04, 06): sprout circle, text; a landscape on the right only with a [scenery]. */
    fun phraseCard(phrase: String, icon: Icon = Icon.SPROUT, scenery: Scenery? = null): View = row().apply {
        val radius = dpf(Radii.CARD.toFloat())
        background = scenery?.let { ScenicBackground(context, it, SceneLayout.CARD_RIGHT, Palette.CardQuiet, radius, vignette = 0.38f) } ?: surface(Palette.CardQuiet)
        outlineProvider = roundOutline(radius)
        elevate(this, 1.5f)
        setPadding(dp(16), dp(16), dp(if (scenery != null) 112 else 18), dp(16))
        addView(iconCircle(icon, 48))
        addView(text(phrase, Typo.SUBTITLE, Palette.Ink), weight().apply { leftMargin = dp(16) })
    }

    /** Calm notice. [NoteTone.CARE] (protection not ready, not enough balance) is warm earth and clay, never red. */
    fun noteCard(message: CharSequence, title: String? = null, tone: NoteTone = NoteTone.INFO, icon: Icon = if (tone == NoteTone.CARE) Icon.HEART else Icon.SHIELD): View = row().apply {
        gravity = Gravity.TOP
        setPadding(dp(14), dp(14), dp(16), dp(14))
        background = surface(if (tone == NoteTone.CARE) Palette.CardQuiet else Palette.Card, Radii.CARD_SMALL)
        elevate(this, if (tone == NoteTone.CARE) 1f else 2f)
        addView(iconCircle(icon, 38, if (tone == NoteTone.CARE) CircleStyle.SAND else CircleStyle.MINT))
        val texts = column()
        title?.let { texts.addView(text(it, Typo.VALUE).apply { textSize = 15.5f }) }
        texts.addView(text(message, Typo.LABEL, Palette.InkMuted), if (title != null) gap(4) else fill())
        addView(texts, weight().apply { leftMargin = dp(12); topMargin = dp(if (title != null) 1 else 9) })
    }

    /**
     * The motivational text component: a quote card. A quiet thought in
     * Manrope (smaller and muted on the home screen), a large quote mark in
     * sage or warm earth, an optional "— MAMA" and an optional landscape on
     * the right. [serif] and [italic] are kept for the callers: the earth
     * type has one voice.
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
        background = scenery?.let { ScenicBackground(context, it, SceneLayout.CARD_RIGHT, Palette.CardQuiet, radius, vignette = 0.36f) } ?: shape(Palette.CardQuiet, Radii.CARD)
        outlineProvider = roundOutline(radius)
        elevate(this, 1.5f)
        setPadding(dp(18), dp(18), dp(if (scenery != null) 112 else 20), dp(20))
        val markColor = if (accent == QuoteAccent.SAND) Palette.Sand else Palette.Sage
        if (markInCircle) {
            addView(FrameLayout(context).apply {
                background = oval(if (accent == QuoteAccent.SAND) Palette.SandSoft else Palette.SageBadge)
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

    /** A centred quiet line over a landscape (lock, motivation). */
    fun motivationLine(phrase: String, color: Int = Palette.Ink): TextView = text(phrase, Typo.QUOTE, color, center = true).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }

    /** Square check box and text ("Я понимаю условия…"); the whole row toggles. */
    fun checkRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit): View = row().apply {
        setPadding(dp(2), dp(8), 0, dp(8))
        minimumHeight = dp(48)
        val box = FrameLayout(context).apply {
            background = if (checked) shape(Palette.OliveDeep, Radii.CHECK) else shape(Palette.Card, Radii.CHECK, Palette.OliveLine, 1.6f)
            if (checked) addView(ImageView(context).apply { setImageDrawable(IconDrawable(Icon.CHECK, Palette.OnAccent, 2.6f)) }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
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
            addState(intArrayOf(android.R.attr.state_focused), shape(Palette.Card, Radii.CARD_SMALL, Palette.OliveLine, 1.6f))
            addState(intArrayOf(), shape(Palette.Card, Radii.CARD_SMALL, Palette.Line))
        }
    }

    // ---------------- lock timer ----------------

    /**
     * The timer: a milky disc like a sun over the ground, a light olive track,
     * a sage-to-olive arc, sprout (none on the lock: [sprout] false),
     * "Осталось", "6 ч 12 мин" and the day of the series as a pill ("День 2
     * из 7"). Bind with [TimerRing.set].
     */
    fun timerRing(sizeDp: Int = 196, sprout: Boolean = true, valueSp: Float = Typo.NUMBER.sizeSp): TimerRing {
        // The disc and its shadow belong to the container, so the ring and the text are drawn above them.
        val root = FrameLayout(context).apply {
            background = oval(Palette.Card)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevate(this, 4f)
        }
        val ring = TimerRingView(context)
        root.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val inner = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        if (sprout) inner.addView(iconCircle(Icon.SPROUT, 44), size(44).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
        val caption = text("Осталось", Typo.LABEL, Palette.InkMuted, center = true)
        inner.addView(caption, centered())
        val value = text("", Typo.NUMBER, Palette.Ink, center = true).apply { textSize = valueSp }
        inner.addView(value, centered(6))
        val day = text("", Typo.LABEL, Palette.OliveInk, center = true).apply {
            textSize = 14f
            typeface = MamaFonts.ui(context, 700)
            background = shape(Palette.SageTag, Radii.TAG)
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        inner.addView(day, centered(10))
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

    /** Cream bottom navigation: Главная · Статистика · Настройки, under a hairline. */
    fun bottomNav(selected: Int, onPick: (Int) -> Unit): View = column().apply {
        setBackgroundColor(Color.argb(0xF5, 0xFB, 0xF5, 0xEA))
        addView(divider())
        val items = row().apply { setPadding(dp(8), dp(6), dp(8), dp(8)) }
        listOf(Icon.HOME to "Главная", Icon.CHART to "Статистика", Icon.GEAR to "Настройки").forEachIndexed { i, (ic, label) ->
            val on = i == selected
            val color = if (on) Palette.OliveInk else Palette.InkSoft
            items.addView(column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, dp(2))
                addView(FrameLayout(context).apply {
                    if (on) background = shape(Palette.SageTag, 15)
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
     * Every screen: the [EarthPage] (under a nature window, [ScenicBackground],
     * when there is a [scenery]), top bar, scrolling body, sticky bottom
     * action, optional bottom navigation. The system bar insets are added by
     * the activity.
     */
    fun screen(
        scenery: Scenery?,
        layout: SceneLayout = SceneLayout.HEADER,
        header: View?,
        body: View,
        cta: View? = null,
        nav: View? = null,
        mood: SceneMood = SceneMood.CALM,
        /** [SceneLayout.HERO]: where the sun sits in the window ([ScenicBackground]). */
        focusY: Float? = null,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = scenery?.let { ScenicBackground(context, it, layout, mood = mood, focusY = focusY) } ?: EarthPage(context, leaves = nav == null)
        header?.let { addView(it, fill()) }
        addView(ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(body)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        cta?.let {
            // Padding instead of margins: the footer reaches the screen edges, so the button's
            // soft glow has room inside it instead of being cut to a rectangle.
            addView(FrameLayout(context).apply {
                setPadding(dp(Spacing.SCREEN), dp(8), dp(Spacing.SCREEN), dp(16))
                clipToPadding = false
                clipChildren = false
                addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, fill())
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
