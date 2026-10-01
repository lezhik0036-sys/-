package app.mama.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** MAMA colours: warm paper, deep forest green, night for the lock screen. */
object Palette {
    val PAPER = Color.rgb(0xF2, 0xED, 0xE3)
    val CARD = Color.rgb(0xFA, 0xF7, 0xF1)
    val LINE = Color.rgb(0xE4, 0xDD, 0xD0)
    val INK = Color.rgb(0x1E, 0x29, 0x21)
    val MUTED = Color.rgb(0x6E, 0x74, 0x6B)
    val FOREST = Color.rgb(0x2E, 0x48, 0x35)
    val FOREST_DARK = Color.rgb(0x20, 0x33, 0x26)
    val MOSS = Color.rgb(0x6B, 0x86, 0x58)
    val CHIP = Color.rgb(0xE2, 0xE7, 0xD8)
    val ALERT = Color.rgb(0xC6, 0x55, 0x3F)
    val NIGHT = Color.rgb(0x10, 0x17, 0x13)
    val NIGHT_CARD = Color.argb(0xCC, 0x1C, 0x26, 0x20)
    val NIGHT_TEXT = Color.rgb(0xEE, 0xF0, 0xE8)
    val NIGHT_MUTED = Color.rgb(0xA6, 0xAE, 0xA2)
    val GLOW = Color.rgb(0xC9, 0xD8, 0xB0)
}

/** Small view factory so every screen shares one look. */
class Ui(val context: Context, private val dark: Boolean = false) {

    fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private val ink get() = if (dark) Palette.NIGHT_TEXT else Palette.INK
    private val muted get() = if (dark) Palette.NIGHT_MUTED else Palette.MUTED

    fun text(
        value: CharSequence,
        sizeSp: Float,
        color: Int = ink,
        bold: Boolean = false,
        center: Boolean = false,
    ) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        if (center) gravity = Gravity.CENTER_HORIZONTAL
        setLineSpacing(0f, 1.15f)
    }

    fun title(value: CharSequence, center: Boolean = false) = text(value, 26f, bold = true, center = center)

    fun body(value: CharSequence, center: Boolean = false) = text(value, 15f, muted, center = center)

    fun small(value: CharSequence, center: Boolean = false) = text(value, 12f, muted, center = center)

    fun rounded(color: Int, radiusDp: Int = 18, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    fun column(paddingDp: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
    }

    fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** A white rounded card with soft border; [selected] turns it forest green. */
    fun card(selected: Boolean = false, paddingDp: Int = 16) = column(paddingDp).apply {
        background = when {
            selected -> rounded(Palette.FOREST)
            dark -> rounded(Palette.NIGHT_CARD, stroke = Color.argb(0x40, 0xFF, 0xFF, 0xFF))
            else -> rounded(Palette.CARD, stroke = Palette.LINE)
        }
    }

    fun primary(label: String, onClick: () -> Unit) = pill(label, Palette.FOREST, Color.WHITE, onClick)

    fun secondary(label: String, onClick: () -> Unit) =
        pill(label, if (dark) Palette.NIGHT_CARD else Palette.CHIP, ink, onClick)

    private fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = rounded(bg, 28)
        setPadding(dp(20), dp(16), dp(20), dp(16))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun chip(label: String) = text(label, 12f, Palette.FOREST).apply {
        background = rounded(Palette.CHIP, 14)
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    /** Round badge with a symbol, used as an icon. */
    fun badge(symbol: String, bg: Int = Palette.FOREST, fg: Int = Color.WHITE, sizeDp: Int = 40) = TextView(context).apply {
        text = symbol
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp * 0.42f)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** "1 из 4" style progress line. */
    fun steps(current: Int, total: Int) = row().apply {
        val bar = FrameLayout(context).apply { background = rounded(Palette.LINE, 3) }
        val fill = View(context).apply { background = rounded(Palette.FOREST, 3) }
        bar.addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        bar.post {
            fill.layoutParams = (fill.layoutParams as FrameLayout.LayoutParams).apply {
                width = bar.width * current / total
            }
            fill.requestLayout()
        }
        addView(bar, LinearLayout.LayoutParams(0, dp(5), 1f))
        addView(small("$current из $total").apply { setPadding(dp(10), 0, 0, 0) })
    }

    fun space(heightDp: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(heightDp)) }

    fun fill(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    fun gap(topDp: Int): LinearLayout.LayoutParams = fill().apply { topMargin = dp(topDp) }

    fun weight(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
}

/**
 * Hand-drawn landscape: a sky gradient and layered mountain ridges, with an
 * optional lake. Stands in for photography, no image assets needed.
 */
class MountainsDrawable(private val night: Boolean, private val lake: Boolean = false) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val (skyTop, skyBottom) = if (night) {
            Color.rgb(0x1B, 0x26, 0x22) to Color.rgb(0x3E, 0x47, 0x3D)
        } else {
            Color.rgb(0x6C, 0x7A, 0x74) to Color.rgb(0xE8, 0xB0, 0x7A)
        }
        paint.shader = LinearGradient(0f, 0f, 0f, h * 0.6f, skyTop, skyBottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        if (!night) {
            paint.color = Color.argb(0x90, 0xFF, 0xE3, 0xB5)
            canvas.drawCircle(w * 0.68f, h * 0.47f, w * 0.07f, paint)
        } else {
            paint.color = Color.argb(0x50, 0xFF, 0xFF, 0xFF)
            listOf(0.12f to 0.08f, 0.3f to 0.15f, 0.55f to 0.06f, 0.8f to 0.12f, 0.9f to 0.22f, 0.42f to 0.24f)
                .forEach { (x, y) -> canvas.drawCircle(w * x, h * y, 2.2f, paint) }
        }

        val ridges = if (night) {
            listOf(Color.rgb(0x2D, 0x37, 0x31), Color.rgb(0x22, 0x2C, 0x26), Color.rgb(0x16, 0x1F, 0x1A))
        } else {
            listOf(Color.rgb(0x8E, 0x8C, 0x82), Color.rgb(0x5E, 0x69, 0x5C), Color.rgb(0x2F, 0x3D, 0x31))
        }
        ridge(canvas, w, h, 0.50f, 0.13f, 3.1f, ridges[0])
        ridge(canvas, w, h, 0.58f, 0.10f, 5.3f, ridges[1])
        ridge(canvas, w, h, 0.68f, 0.08f, 7.7f, ridges[2])

        if (lake) {
            paint.shader = LinearGradient(
                0f, h * 0.74f, 0f, h,
                if (night) Color.rgb(0x2A, 0x33, 0x30) else Color.rgb(0x9A, 0x8E, 0x7E),
                if (night) Color.rgb(0x0E, 0x14, 0x11) else Color.rgb(0x3A, 0x44, 0x3A),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, h * 0.74f, w, h, paint)
            paint.shader = null
        } else {
            paint.color = ridges[2]
            canvas.drawRect(0f, h * 0.74f, w, h, paint)
        }
    }

    /** A ridge line made of a few overlapping sine waves, filled to the bottom. */
    private fun ridge(canvas: Canvas, w: Float, h: Float, base: Float, amp: Float, seed: Float, color: Int) {
        val path = Path().apply { moveTo(0f, h) }
        val steps = 48
        for (i in 0..steps) {
            val x = w * i / steps
            val t = i / steps.toFloat()
            val y = h * (base - amp * (
                0.55f * kotlin.math.sin(t * 6.3f + seed) +
                    0.3f * kotlin.math.sin(t * 13.1f + seed * 1.7f) +
                    0.15f * kotlin.math.sin(t * 27f + seed * 2.3f)
                ))
            path.lineTo(x, y)
        }
        path.lineTo(w, h)
        path.close()
        paint.color = color
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** Thin progress ring for the lock screen countdown. */
class RingView(context: Context) : View(context) {
    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * density
        color = Color.argb(0x40, 0xFF, 0xFF, 0xFF)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * density
        strokeCap = Paint.Cap.ROUND
        color = Palette.GLOW
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x8C, 0x0E, 0x14, 0x11) }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val inset = track.strokeWidth
        box.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(box, fill)
        canvas.drawOval(box, track)
        canvas.drawArc(box, -90f, 360f * progress, false, arc)
    }
}
