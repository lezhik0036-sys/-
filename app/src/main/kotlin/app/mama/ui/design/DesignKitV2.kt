package app.mama.ui.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mama.ui.BrandButtonView
import app.mama.ui.Icon
import app.mama.ui.IconDrawable
import app.mama.ui.MamaFonts

/**
 * MAMA Design System v2 (docs/MAMA_DESIGN_SYSTEM_V2.md), the source of truth
 * for the UI from here on: a milky page, MAMA Coral for the one main action,
 * calm olive, soft cards and fully rounded pills, Manrope. Screens move to it
 * one by one; the rest still use [DesignKit] until they do.
 */
object V2 {
    val Milk = Color.rgb(0xFF, 0xF9, 0xF1)
    val WarmBg = Color.rgb(0xF8, 0xF0, 0xE6)
    val Coral = Color.rgb(0xC9, 0x6F, 0x62)
    val CoralLight = Color.rgb(0xE6, 0xA0, 0x97)
    val OliveDeep = Color.rgb(0x47, 0x53, 0x42)
    val Olive = Color.rgb(0x69, 0x74, 0x5E)
    val OliveLight = Color.rgb(0xDC, 0xE2, 0xD4)
    val Ink = Color.rgb(0x17, 0x30, 0x2E)
    val InkMuted = Color.rgb(0x53, 0x61, 0x5D)
    val Sand = Color.rgb(0xEB, 0xDC, 0xC9)

    /** Card surface on the milk page. */
    val Card = Color.rgb(0xFF, 0xFD, 0xF9)

    const val CARD_LARGE = 28
    const val CARD = 22
    const val BUTTON = 26
}

class DesignKitV2(val context: Context) {
    /** Shared layout helpers and type of the first kit (same Manrope scale). */
    val k = DesignKit(context)

    fun dp(v: Int) = k.dp(v)

    fun text(value: CharSequence, style: Typo = Typo.BODY, color: Int = V2.Ink, center: Boolean = false): TextView = k.text(value, style, color, center)

    /** [text] with [accent] in MAMA Coral ("Добро пожаловать в MAMA"). */
    fun accented(text: String, accent: String): CharSequence = SpannableString(text).apply {
        val at = text.lastIndexOf(accent)
        if (at >= 0) setSpan(ForegroundColorSpan(V2.Coral), at, at + accent.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun shape(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Float = 1f) = k.shape(fill, radiusDp, stroke, strokeDp)

    /** The one main action of a screen: a MAMA Coral pill, white label, "→". */
    fun primary(label: String, arrow: Boolean = true, onClick: () -> Unit): BrandButtonView = BrandButtonView(context).apply {
        text = label
        Typo.BUTTON.applyTo(this)
        textSize = 17f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minHeight = dp(56)
        minimumHeight = dp(56)
        background = k.pressable(GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(0xD0, 0x7A, 0x6C), V2.Coral)).apply {
            cornerRadius = k.dpf(V2.BUTTON.toFloat())
        }, V2.BUTTON, Color.argb(0x40, 0xFF, 0xFF, 0xFF))
        if (arrow) {
            val d = IconDrawable(Icon.ARROW_RIGHT, Color.WHITE, 2.0f).apply { setBounds(0, 0, dp(22), dp(22)) }
            setCompoundDrawables(null, null, d, null)
            compoundDrawablePadding = dp(10)
            setPadding(dp(22 + 22 + 10), dp(14), dp(22), dp(14))
        } else {
            setPadding(dp(22), dp(14), dp(22), dp(14))
        }
        k.elevate(this, 3f, Color.argb(0x90, 0xC9, 0x6F, 0x62), Color.argb(0xFF, 0xC9, 0x6F, 0x62))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** A second action that does not compete: a soft outline pill. */
    fun secondary(label: String, icon: Icon? = null, onClick: () -> Unit): BrandButtonView = BrandButtonView(context).apply {
        text = label
        Typo.BUTTON.applyTo(this)
        textSize = 15.5f
        setTextColor(V2.OliveDeep)
        gravity = Gravity.CENTER
        minHeight = dp(52)
        minimumHeight = dp(52)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        background = k.pressable(shape(V2.Card, V2.BUTTON, V2.Sand, 1.2f), V2.BUTTON)
        icon?.let {
            val d = IconDrawable(it, V2.OliveDeep, 2.0f, solid = true).apply { setBounds(0, 0, dp(18), dp(18)) }
            setCompoundDrawables(d, null, null, null)
            compoundDrawablePadding = dp(10)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** A quiet text action ("Мой путь"). */
    fun link(label: String, onClick: () -> Unit): BrandButtonView = BrandButtonView(context).apply {
        text = label
        Typo.BUTTON.applyTo(this)
        textSize = 15.5f
        setTextColor(V2.OliveDeep)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = k.pressable(shape(Color.TRANSPARENT, 22), 22)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /**
     * A secondary action that stays at hand but never leads (the lock): a
     * translucent warm pill, a small icon, a muted label, no shadow.
     */
    fun quietAction(icon: Icon, label: String, onClick: () -> Unit): BrandButtonView = BrandButtonView(context).apply {
        text = label
        Typo.LABEL.applyTo(this)
        textSize = 15f
        setTextColor(V2.InkMuted)
        gravity = Gravity.CENTER
        minHeight = dp(46)
        minimumHeight = dp(46)
        setPadding(dp(18), dp(10), dp(20), dp(10))
        background = k.pressable(shape(Color.argb(0xCC, 0xF8, 0xF0, 0xE6), 23, Color.argb(0x99, 0xEB, 0xDC, 0xC9), 1f), 23)
        val d = IconDrawable(icon, V2.Olive, 1.8f, solid = true).apply { setBounds(0, 0, dp(18), dp(18)) }
        setCompoundDrawables(d, null, null, null)
        compoundDrawablePadding = dp(10)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** A status pill, fully rounded: light olive, deep olive text ("Пройдено ночей: 4 из 7"). */
    fun pill(label: String, icon: Icon? = null): View = k.row().apply {
        background = shape(V2.OliveLight, 100)
        setPadding(dp(14), dp(8), dp(16), dp(8))
        icon?.let { addView(k.icon(it, V2.OliveDeep, 15), k.size(15).apply { rightMargin = dp(8) }) }
        addView(text(label, Typo.LABEL, V2.OliveDeep).apply { typeface = MamaFonts.ui(context, 700) })
        isClickable = false
        isFocusable = false
    }

    /** A soft card: [radiusDp] 20–28, a faint sand rim and a warm shadow. */
    fun card(paddingDp: Int = 20, radiusDp: Int = V2.CARD, fill: Int = V2.Card): LinearLayout = k.column(paddingDp).apply {
        background = shape(fill, radiusDp, Color.argb(0x80, 0xEB, 0xDC, 0xC9), 1f)
        k.elevate(this, 2f, Color.argb(0x40, 0x8A, 0x6A, 0x4A), Color.argb(0xFF, 0x9A, 0x7A, 0x5A))
    }

    /** An icon in a soft round bubble. */
    fun bubble(icon: Icon, tint: Int = V2.OliveDeep, fill: Int = V2.OliveLight, sizeDp: Int = 44): View = FrameLayout(context).apply {
        background = k.oval(fill)
        addView(k.icon(icon, tint, (sizeDp * 0.46f).toInt()), FrameLayout.LayoutParams(dp((sizeDp * 0.46f).toInt()), dp((sizeDp * 0.46f).toInt()), Gravity.CENTER))
        layoutParams = k.size(sizeDp)
    }

    /** A note of facts on a warm card (what "Цена слова" came to, a free series later). */
    fun note(title: String?, message: CharSequence, icon: Icon, warm: Boolean = false): View = card(paddingDp = 16, fill = if (warm) V2.WarmBg else V2.Card).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        addView(bubble(icon, if (warm) V2.Coral else V2.OliveDeep, if (warm) Color.argb(0x55, 0xE6, 0xA0, 0x97) else V2.OliveLight, 38))
        val texts = k.column()
        title?.let { texts.addView(text(it, Typo.VALUE).apply { textSize = 15.5f }) }
        texts.addView(text(message, Typo.LABEL, V2.InkMuted), if (title != null) k.gap(4) else k.fill())
        addView(texts, k.weight().apply { leftMargin = dp(12); topMargin = dp(if (title != null) 1 else 9) })
    }

    /** The lock timer: a light disc, a light-olive track, an olive arc; the day of the series as a pill. */
    fun timerRing(sizeDp: Int): TimerRing {
        val root = FrameLayout(context).apply {
            background = k.oval(V2.Card)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            k.elevate(this, 4f, Color.argb(0x50, 0x8A, 0x6A, 0x4A), Color.argb(0xFF, 0x9A, 0x7A, 0x5A))
        }
        val ring = TimerRingView(context, V2.OliveLight, Color.rgb(0x8E, 0x9A, 0x80), V2.Olive)
        root.addView(ring, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val inner = k.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        inner.addView(bubble(Icon.SPROUT, V2.OliveDeep, V2.OliveLight, 44), k.size(44).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val caption = text("Осталось", Typo.LABEL, V2.InkMuted, center = true)
        inner.addView(caption, k.centered(8))
        val value = text("", Typo.NUMBER, V2.Ink, center = true)
        inner.addView(value, k.centered(6))
        val day = text("", Typo.LABEL, V2.OliveDeep, center = true).apply {
            textSize = 14f
            typeface = MamaFonts.ui(context, 700)
            background = shape(V2.OliveLight, 100)
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        inner.addView(day, k.centered(10))
        root.addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        return TimerRing(k, root, ring, caption, value, day)
    }

    /** MAMA as a round portrait (a crop of the approved art), with a soft coral halo. */
    fun portrait(asset: String, sizeDp: Int): View = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        private val matrix = Matrix()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f
            halo.shader = RadialGradient(cx, cy, r, intArrayOf(Color.argb(0x66, 0xE6, 0xA0, 0x97), Color.argb(0, 0xE6, 0xA0, 0x97)), floatArrayOf(0.72f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, halo)
            val inner = r * 0.84f
            canvas.drawCircle(cx, cy, inner + dp(3), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = V2.Card })
            val bitmap = art(context, asset) ?: return
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val s = 2 * inner / minOf(bitmap.width, bitmap.height)
            matrix.setScale(s, s)
            matrix.postTranslate(cx - bitmap.width * s / 2, cy - bitmap.height * s / 2)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            canvas.drawCircle(cx, cy, inner, paint)
        }
    }.apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    /** The milky page: a warm wash towards the bottom, soft coral and olive circles, two leaf sprigs. */
    fun page(leaves: Boolean = true): Drawable = Page(context.resources.displayMetrics.density, leaves)

    private class Page(private val density: Float, private val leaves: Boolean) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            p.shader = LinearGradient(0f, 0f, 0f, h, V2.Milk, V2.WarmBg, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, p)
            p.shader = null
            p.style = Paint.Style.FILL
            p.color = Color.argb(0x2E, 0xE6, 0xA0, 0x97)
            canvas.drawCircle(-24 * density, h * 0.70f, 96 * density, p)
            p.color = Color.argb(0x38, 0xDC, 0xE2, 0xD4)
            canvas.drawCircle(w + 30 * density, h * 0.58f, 110 * density, p)
            if (!leaves) return
            sprig(canvas, 18 * density, h - 26 * density, -58f, Color.argb(0x8C, 0xB9, 0xC2, 0xAD), filled = true)
            sprig(canvas, w - 22 * density, h - 20 * density, -122f, Color.argb(0x99, 0xA9, 0xB4, 0x9C), filled = false)
        }

        /** A stem with five leaves, from ([x], [y]) at [angle] degrees. */
        private fun sprig(canvas: Canvas, x: Float, y: Float, angle: Float, color: Int, filled: Boolean) {
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(angle + 90f)
            val len = 96 * density
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.6f * density
            p.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(0f, 0f, 0f, -len, p)
            p.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
            for (i in 0 until 5) {
                val t = 0.22f + i * 0.17f
                val side = if (i % 2 == 0) 1f else -1f
                val leaf = Path().apply {
                    moveTo(0f, -len * t)
                    quadTo(side * 20 * density, -len * t - 6 * density, side * 30 * density, -len * t - 22 * density)
                    quadTo(side * 10 * density, -len * t - 18 * density, 0f, -len * t)
                    close()
                }
                canvas.drawPath(leaf, p)
            }
            canvas.drawPath(Path().apply {
                moveTo(0f, -len)
                quadTo(8 * density, -len - 16 * density, 0f, -len - 30 * density)
                quadTo(-8 * density, -len - 16 * density, 0f, -len)
            }, p)
            canvas.restore()
            p.style = Paint.Style.FILL
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    private companion object {
        private val cache = object : LruCache<String, Bitmap>(4 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }

        fun art(context: Context, asset: String): Bitmap? = cache.get(asset) ?: runCatching {
            context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.also { cache.put(asset, it) }
    }
}
