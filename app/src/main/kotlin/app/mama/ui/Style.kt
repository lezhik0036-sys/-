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
import android.graphics.drawable.Drawable
import android.view.View

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
            Color.rgb(0xE4, 0xEB, 0xDF) to Color.rgb(0xF6, 0xE6, 0xCC)
        }
        paint.shader = LinearGradient(0f, 0f, 0f, h * 0.6f, skyTop, skyBottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        if (!night) {
            paint.color = Color.argb(0xB0, 0xF7, 0xDE, 0xB0)
            canvas.drawCircle(w * 0.70f, h * 0.36f, w * 0.06f, paint)
        } else {
            paint.color = Color.argb(0x50, 0xFF, 0xFF, 0xFF)
            listOf(0.12f to 0.08f, 0.3f to 0.15f, 0.55f to 0.06f, 0.8f to 0.12f, 0.9f to 0.22f, 0.42f to 0.24f)
                .forEach { (x, y) -> canvas.drawCircle(w * x, h * y, 2.2f, paint) }
        }

        val ridges = if (night) {
            listOf(Color.rgb(0x2D, 0x37, 0x31), Color.rgb(0x22, 0x2C, 0x26), Color.rgb(0x16, 0x1F, 0x1A))
        } else {
            listOf(MamaColors.SageMuted, MamaColors.OliveSoft, MamaColors.EmeraldSecondary)
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
            ridge(canvas, w, h, 0.80f, 0.06f, 9.4f, if (night) ridges[2] else MamaColors.EmeraldPrimary)
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

/** Thin progress ring for the lock screen countdown (light MAMA style). */
class RingView(context: Context) : View(context) {
    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6 * density
        color = MamaColors.SageMuted
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6 * density
        strokeCap = Paint.Cap.ROUND
        color = MamaColors.EmeraldPrimary
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MamaColors.SurfaceCard }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val inset = track.strokeWidth
        box.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(box, fill)
        canvas.drawOval(box, track)
        canvas.drawArc(box, -90f, 360f * progress, false, arc)
    }
}
