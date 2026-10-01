package app.mama.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

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
 * Full-screen landscape background from app/src/main/assets/scenes, drawn
 * centre-cropped. Falls back to the vector [SceneryDrawable] if the image is
 * missing. Images are decoded once and kept in a small cache.
 */
class PhotoBackground(private val context: Context, private val scene: Scene) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fallback by lazy { SceneryDrawable(scene) }
    private val src = Rect()

    override fun draw(canvas: Canvas) {
        val b = bitmap(context, scene)
        if (b == null) {
            fallback.bounds = bounds
            fallback.draw(canvas)
            return
        }
        val vw = bounds.width().toFloat()
        val vh = bounds.height().toFloat()
        val scale = maxOf(vw / b.width, vh / b.height)
        val cw = (vw / scale).toInt()
        val ch = (vh / scale).toInt()
        val left = (b.width - cw) / 2
        val top = ((b.height - ch) * 0.35f).toInt().coerceAtLeast(0)
        src.set(left, top, left + cw, top + ch)
        canvas.drawBitmap(b, src, bounds, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        private val cache = LruCache<Scene, Bitmap>(2)

        @Synchronized
        fun bitmap(context: Context, scene: Scene): Bitmap? = cache.get(scene) ?: runCatching {
            val name = "scenes/${scene.name.lowercase()}.jpg"
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            context.assets.open(name).use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()?.also { cache.put(scene, it) }
    }
}

/**
 * The MAMA design system: every screen is assembled from these components
 * (names in brackets match the design spec). [night] switches to the dark
 * photographic variant used on dashboard, lock, failure and success screens.
 */
class Kit(val context: Context, val night: Boolean = false) {

    fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    fun dpf(v: Float): Float = v * context.resources.displayMetrics.density

    val ink get() = if (night) MamaColors.OnNight else MamaColors.Graphite
    val muted get() = if (night) MamaColors.OnNightMuted else MamaColors.TextSecondary

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

    fun gradient(from: Int, to: Int, radiusDp: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)).apply {
        cornerRadius = dpf(radiusDp.toFloat())
    }

    fun oval(fill: Int, stroke: Int? = null, strokeDp: Float = 1.5f) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        stroke?.let { setStroke(dpf(strokeDp).toInt(), it) }
    }

    fun pressable(content: Drawable, radiusDp: Int, ripple: Int = if (night) MamaColors.RippleOnDark else MamaColors.Ripple): Drawable =
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

    /** Icon in a soft circle, as on the reference list rows. */
    fun iconCircle(icon: Icon, sizeDp: Int = 42, fill: Int = if (night) MamaColors.NightSoft else MamaColors.EmeraldTint, tint: Int = if (night) MamaColors.ProgressGlow else MamaColors.Emerald): View =
        FrameLayout(context).apply {
            background = oval(fill)
            addView(
                ImageView(context).apply { setImageDrawable(IconDrawable(icon, tint)) },
                FrameLayout.LayoutParams(dp(sizeDp * 52 / 100), dp(sizeDp * 52 / 100), Gravity.CENTER),
            )
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }

    private fun clickable(view: View, onClick: () -> Unit) {
        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener { onClick() }
    }

    // ---------- [MamaCard] ----------

    fun card(paddingDp: Int = 18, radiusDp: Int = 18) = column(paddingDp).apply {
        background = if (night) shape(MamaColors.NightGlass, radiusDp, MamaColors.NightLine) else shape(MamaColors.PureCard, radiusDp, MamaColors.Border)
    }

    // ---------- [MamaPrimaryButton] / [MamaSecondaryButton] ----------

    fun primaryButton(label: String, trailing: Icon? = null, onClick: () -> Unit): BrandButtonView =
        button(label, trailing, MamaColors.Emerald, MamaColors.White, null, onClick).apply {
            background = pressable(gradient(MamaColors.EmeraldActive, MamaColors.Emerald, 28), 28, MamaColors.RippleOnDark)
        }

    fun secondaryButton(label: String, trailing: Icon? = null, onClick: () -> Unit): BrandButtonView =
        if (night) {
            button(label, trailing, MamaColors.NightGlass, MamaColors.OnNight, MamaColors.NightLine, onClick)
        } else {
            button(label, trailing, MamaColors.PureCard, MamaColors.Emerald, MamaColors.Border, onClick)
        }

    fun textButton(label: String, onClick: () -> Unit): BrandButtonView =
        button(label, null, Color.TRANSPARENT, if (night) MamaColors.OnNight else MamaColors.Emerald, null, onClick).apply { minHeight = dp(44) }

    private fun button(label: String, trailing: Icon?, fill: Int, fg: Int, stroke: Int?, onClick: () -> Unit) =
        BrandButtonView(context).apply {
            text = label
            MamaType.BUTTON.applyTo(this)
            includeFontPadding = false
            setTextColor(fg)
            gravity = Gravity.CENTER
            minHeight = dp(54)
            setPadding(dp(22), dp(14), dp(22), dp(14))
            background = pressable(shape(fill, 28, stroke), 28)
            trailing?.let {
                val d = IconDrawable(it, fg).apply { setBounds(0, 0, dp(20), dp(20)) }
                setCompoundDrawables(null, null, d, null)
                compoundDrawablePadding = dp(10)
            }
            clickable(this, onClick)
        }

    // ---------- step header + [MamaProgressIndicator] ----------

    /** Back arrow, centred MAMA, "2 из 5", thin progress line (reference screens 2–5). */
    fun stepHeader(step: Int?, total: Int, onBack: (() -> Unit)?): View = column().apply {
        val bar = FrameLayout(context)
        if (onBack != null) {
            bar.addView(FrameLayout(context).apply {
                background = pressable(oval(Color.TRANSPARENT), 22)
                addView(icon(Icon.BACK, ink, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                clickable(this, onBack)
                contentDescription = "Назад"
            }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
        }
        bar.addView(text("MAMA", MamaType.HEADER, ink), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        if (step != null) {
            bar.addView(text("$step из $total", MamaType.SMALL, muted), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        }
        addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        if (step != null) addView(progressIndicator(step, total), gap(6))
    }

    fun progressIndicator(step: Int, total: Int): View = LinearLayout(context).apply {
        background = shape(if (night) MamaColors.NightLine else MamaColors.OliveLight, 3)
        addView(View(context).apply { background = shape(MamaColors.Emerald, 3) }, LinearLayout.LayoutParams(0, dp(3), step.toFloat()))
        addView(View(context), LinearLayout.LayoutParams(0, dp(3), (total - step).toFloat()))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
    }

    // ---------- [MamaSectionHeader] ----------

    fun sectionHeader(title: String, subtitle: String? = null, center: Boolean = false): View = column().apply {
        addView(text(title, MamaType.H1, ink, center), fill())
        subtitle?.let { addView(text(it, MamaType.BODY, muted, center), gap(8)) }
    }

    // ---------- [MamaSelectableCard] ----------

    /**
     * A list row card: icon circle, title, subtitle, optional trailing text.
     * Selected = emerald gradient with light text and a check in a circle.
     */
    fun selectableCard(
        icon: Icon,
        title: String,
        subtitle: String,
        selected: Boolean,
        trailing: String? = null,
        onClick: () -> Unit,
    ): View = row().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minimumHeight = dp(72)
        background = if (selected) {
            pressable(gradient(MamaColors.EmeraldActive, MamaColors.Emerald, 18), 18, MamaColors.RippleOnDark)
        } else {
            pressable(shape(MamaColors.PureCard, 18, MamaColors.Border), 18)
        }
        addView(
            if (selected) iconCircle(icon, 42, Color.argb(0x33, 0xFF, 0xFF, 0xFF), MamaColors.White) else iconCircle(icon, 42),
        )
        val texts = column()
        texts.addView(text(title, MamaType.TITLE, if (selected) MamaColors.White else MamaColors.Graphite))
        texts.addView(text(subtitle, MamaType.SMALL, if (selected) MamaColors.OliveLight else MamaColors.TextSecondary), gap(3))
        addView(texts, weight().apply { leftMargin = dp(14) })
        trailing?.let {
            addView(text(it, MamaType.TITLE, if (selected) MamaColors.White else MamaColors.Graphite), wrap().apply { leftMargin = dp(8) })
        }
        if (selected) {
            addView(FrameLayout(context).apply {
                background = oval(MamaColors.White)
                addView(icon(Icon.CHECK, MamaColors.Emerald, 14), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
            }, size(24).apply { leftMargin = dp(10) })
        } else {
            addView(icon(Icon.CHEVRON_RIGHT, MamaColors.TextSecondary, 18), size(18).apply { leftMargin = dp(8) })
        }
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
        addView(icon(icon, if (onClick != null) MamaColors.Emerald else MamaColors.TextSecondary, 22))
        val texts = column()
        texts.addView(text(value, type, if (onClick != null) ink else muted))
        note?.let { texts.addView(text(it, MamaType.SMALL, muted), gap(2)) }
        addView(texts, weight().apply { leftMargin = dp(14) })
        if (onClick != null) {
            addView(icon(Icon.CHEVRON_RIGHT, MamaColors.TextSecondary, 18))
            background = pressable(shape(Color.TRANSPARENT, 0), 0)
            clickable(this, onClick)
        }
    }

    /** Small icon + caption line (hints under the time cards). */
    fun hintRow(icon: Icon, value: String): View = row().apply {
        gravity = Gravity.TOP
        addView(icon(icon, muted, 18), size(18).apply { topMargin = dp(1) })
        addView(text(value, MamaType.CAPTION, muted), weight().apply { leftMargin = dp(12) })
    }

    // ---------- [MamaContactCard] ----------

    fun contactCard(name: String, phone: String, caption: String? = null, onClick: (() -> Unit)?): View = row().apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        minimumHeight = dp(76)
        val base = if (night) shape(MamaColors.NightGlass, 18, MamaColors.NightLine) else shape(MamaColors.PureCard, 18, MamaColors.Border)
        background = if (onClick != null) pressable(base, 18) else base
        addView(iconCircle(Icon.PERSON, 48))
        val texts = column()
        caption?.let { texts.addView(text(it, MamaType.SMALL, muted)) }
        texts.addView(text(name, MamaType.H2, ink), if (caption != null) gap(2) else fill())
        texts.addView(text(phone, MamaType.CAPTION, muted), gap(3))
        addView(texts, weight().apply { leftMargin = dp(16) })
        if (onClick != null) {
            addView(icon(Icon.CHEVRON_RIGHT, muted, 20))
            clickable(this, onClick)
        }
    }

    // ---------- [MamaPermissionCard] ----------

    fun permissionCard(icon: Icon, title: String, why: String, status: PermissionStatus, onClick: () -> Unit): View = row().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minimumHeight = dp(72)
        val base = shape(MamaColors.PureCard, 18, MamaColors.Border)
        background = if (status == PermissionStatus.GRANTED) base else pressable(base, 18)
        addView(iconCircle(icon, 42))
        val texts = column()
        texts.addView(text(title, MamaType.TITLE))
        texts.addView(text(why, MamaType.SMALL, MamaColors.TextSecondary), gap(3))
        addView(texts, weight().apply { leftMargin = dp(14); rightMargin = dp(10) })
        when (status) {
            PermissionStatus.GRANTED -> addView(FrameLayout(context).apply {
                background = oval(MamaColors.Emerald)
                addView(icon(Icon.CHECK, MamaColors.White, 14), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
            }, size(24))
            PermissionStatus.REQUIRED -> addView(statusBadge("Нужно", Tone.WARNING))
            PermissionStatus.MISSING -> addView(icon(Icon.CHEVRON_RIGHT, MamaColors.TextSecondary, 18))
        }
        if (status != PermissionStatus.GRANTED) clickable(this, onClick)
    }

    // ---------- [MamaStatusBadge] ----------

    fun statusBadge(label: String, tone: Tone): TextView = text(label, MamaType.SMALL, inkOf(tone)).apply {
        typeface = MamaFonts.ui(context, 700)
        background = shape(fillOf(tone), 12)
        setPadding(dp(10), dp(5), dp(10), dp(5))
    }

    fun fillOf(tone: Tone) = when (tone) {
        Tone.SUCCESS -> MamaColors.EmeraldTint
        Tone.WARNING -> MamaColors.DangerSoft
        Tone.DANGER -> MamaColors.DangerSoft
        Tone.NEUTRAL -> MamaColors.WarmCard
        Tone.BRAND -> MamaColors.EmeraldTint
    }

    fun inkOf(tone: Tone) = when (tone) {
        Tone.SUCCESS -> MamaColors.Success
        Tone.WARNING -> MamaColors.Warning
        Tone.DANGER -> MamaColors.Warning
        Tone.NEUTRAL -> MamaColors.TextSecondary
        Tone.BRAND -> MamaColors.Emerald
    }

    // ---------- [MamaInfoCard] ----------

    fun infoCard(message: CharSequence, icon: Icon = Icon.INFO, tone: Tone = Tone.NEUTRAL, title: String? = null): View = row().apply {
        gravity = Gravity.TOP
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = when {
            night -> shape(MamaColors.NightGlass, 16, MamaColors.NightLine)
            tone == Tone.NEUTRAL -> shape(MamaColors.WarmCard, 16)
            else -> shape(fillOf(tone), 16)
        }
        val tint = if (night) MamaColors.OnNightMuted else if (tone == Tone.NEUTRAL) MamaColors.TextSecondary else inkOf(tone)
        addView(icon(icon, tint, 22), size(22))
        val texts = column()
        title?.let { texts.addView(text(it, MamaType.TITLE, if (night) MamaColors.OnNight else if (tone == Tone.NEUTRAL) MamaColors.Graphite else inkOf(tone))) }
        texts.addView(text(message, MamaType.CAPTION, if (night) MamaColors.OnNightMuted else MamaColors.TextSecondary), if (title != null) gap(4) else fill())
        addView(texts, weight().apply { leftMargin = dp(14) })
    }

    // ---------- summary rows (review) ----------

    fun summaryRow(icon: Icon, label: String, value: String, note: String? = null): View = row().apply {
        gravity = Gravity.TOP
        setPadding(0, dp(12), 0, dp(12))
        addView(icon(icon, MamaColors.Emerald, 20), size(20).apply { topMargin = dp(1) })
        addView(text(label, MamaType.CAPTION, MamaColors.TextSecondary), LinearLayout.LayoutParams(dp(118), ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(14) })
        val v = column()
        v.addView(text(value, MamaType.TITLE))
        note?.let { v.addView(text(it, MamaType.SMALL, MamaColors.TextSecondary), gap(3)) }
        addView(v, weight())
    }

    fun divider(): View = View(context).apply {
        setBackgroundColor(if (night) MamaColors.NightLine else MamaColors.Border)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    /** Square check box + text (review screen). */
    fun checkRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit): View = row().apply {
        gravity = Gravity.TOP
        setPadding(0, dp(6), 0, dp(6))
        val box = FrameLayout(context).apply {
            background = if (checked) shape(MamaColors.Emerald, 6) else shape(MamaColors.PureCard, 6, MamaColors.EmeraldSoft, 1.5f)
            if (checked) addView(icon(Icon.CHECK, MamaColors.White, 16), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        }
        addView(box, size(24))
        addView(text(label, MamaType.CAPTION, MamaColors.Graphite), weight().apply { leftMargin = dp(12) })
        clickable(this) { onToggle(!checked) }
    }

    // ---------- progress dots, stat tiles ----------

    fun dots(total: Int, done: Int, failed: Int = 0, sizeDp: Int = 14): View = row().apply {
        gravity = Gravity.CENTER
        repeat(total) { i ->
            val d = when {
                i < done -> oval(if (night) MamaColors.ProgressGlow else MamaColors.Emerald)
                i < done + failed -> oval(MamaColors.Warning)
                else -> oval(Color.TRANSPARENT, if (night) MamaColors.OnNightMuted else MamaColors.EmeraldSoft, 1.5f)
            }
            addView(View(context).apply { background = d }, size(sizeDp).apply { setMargins(dp(5), 0, dp(5), 0) })
        }
    }

    /** Small tile: icon, caption, value (dashboard info row). */
    fun miniTile(icon: Icon, label: String, value: String): View = row().apply {
        setPadding(dp(10), dp(10), dp(8), dp(10))
        addView(icon(icon, if (night) MamaColors.OnNightMuted else MamaColors.Emerald, 18), size(18))
        val t = column()
        t.addView(text(label, MamaType.SMALL, muted))
        t.addView(text(value, MamaType.CAPTION, ink).apply { typeface = MamaFonts.ui(context, 700) }, gap(2))
        addView(t, weight().apply { leftMargin = dp(8) })
    }

    fun stat(label: String, value: String): View = card(14).apply {
        addView(text(label, MamaType.SMALL, muted))
        addView(text(value, MamaType.H2, ink), gap(6))
    }

    // ---------- inputs ----------

    fun textField(hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT, multiLine: Boolean = false): EditText = EditText(context).apply {
        this.hint = hint
        this.inputType = inputType or if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        if (!multiLine) setSingleLine()
        MamaType.BODY.applyTo(this)
        setTextColor(MamaColors.Graphite)
        setHintTextColor(MamaColors.EmeraldSoft)
        minHeight = dp(54)
        gravity = if (multiLine) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = fieldBackground()
    }

    fun fieldBackground(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(MamaColors.PureCard, 16, MamaColors.Emerald, 1.5f))
        addState(intArrayOf(), shape(MamaColors.PureCard, 16, MamaColors.Border))
    }

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
                background = if (on) pressable(gradient(MamaColors.EmeraldActive, MamaColors.Emerald, 26), 26, MamaColors.RippleOnDark) else pressable(shape(MamaColors.WarmCard, 26), 26)
                clickable(this) { onPick(i) }
            }
            addView(b, weight().apply { if (i > 0) leftMargin = dp(10) })
        }
    }

    // ---------- bottom navigation ----------

    fun bottomNav(selected: Int, onPick: (Int) -> Unit): View = row().apply {
        background = shape(MamaColors.WarmIvory, 0)
        setPadding(dp(8), dp(8), dp(8), dp(10))
        listOf(Icon.HOME to "Главная", Icon.CHART to "Статистика", Icon.GEAR to "Настройки").forEachIndexed { i, (ic, label) ->
            val on = i == selected
            val color = if (on) MamaColors.Emerald else MamaColors.TextSecondary
            addView(column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, dp(2))
                addView(icon(ic, color, 22), centered())
                addView(text(label, MamaType.SMALL, color).apply { if (on) typeface = MamaFonts.ui(context, 700) }, centered(4))
                background = pressable(shape(Color.TRANSPARENT, 14), 14)
                clickable(this) { onPick(i) }
            }, weight())
        }
    }

    /** Large emblem: symbol in a ring (failure / success). */
    fun emblem(icon: Icon, ring: Int, sizeDp: Int = 76): View = FrameLayout(context).apply {
        background = oval(Color.argb(0x22, Color.red(ring), Color.green(ring), Color.blue(ring)), ring, 2f)
        addView(ImageView(context).apply { setImageDrawable(IconDrawable(icon, ring, 2.2f)) }, FrameLayout.LayoutParams(dp(sizeDp / 2), dp(sizeDp / 2), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }
}
