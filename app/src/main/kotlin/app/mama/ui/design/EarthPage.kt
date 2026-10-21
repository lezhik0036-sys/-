package app.mama.ui.design

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.sin

/**
 * The page of every screen (MAMA_DESIGN_SYSTEM_V2 «Страница»): a milky wash
 * that warms towards the bottom, two soft circles of light coral and light
 * olive, and the ground — low layered hills of light olive, sage and sand
 * with two leaf sprigs growing from them. The earth holds the screen; it
 * stays light, so nothing on it ever has to compete with it.
 */
class EarthPage(
    private val density: Float,
    private val ground: Boolean = true,
    private val leaves: Boolean = true,
) : Drawable() {
    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        EarthPainter.paint(canvas, b.width().toFloat(), b.height().toFloat(), density, ground, leaves)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** Paints the [EarthPage] (also under the photo of a scenic screen). */
internal object EarthPainter {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** The hills, far to near: top above the bottom edge and wave height in dp, colour. */
    private class Hill(val topDp: Float, val ampDp: Float, val phase: Float, val waves: Float, val color: Int)

    private val hills = listOf(
        Hill(150f, 16f, 0.4f, 0.8f, Color.argb(0x8C, 0xDC, 0xE2, 0xD4)),
        Hill(112f, 14f, 2.2f, 1.1f, Color.argb(0x80, 0xC9, 0xD2, 0xBF)),
        Hill(96f, 12f, 4.0f, 0.7f, Color.argb(0x8C, 0xEB, 0xDC, 0xC9)),
        Hill(62f, 9f, 1.0f, 1.3f, Color.argb(0x99, 0xE4, 0xD3, 0xBD)),
    )

    @Synchronized
    fun paint(canvas: Canvas, w: Float, h: Float, density: Float, ground: Boolean = true, leaves: Boolean = true) {
        p.reset()
        p.isAntiAlias = true
        p.shader = LinearGradient(0f, 0f, 0f, h, V2.Milk, V2.WarmBg, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null
        p.color = Color.argb(0x2A, 0xE6, 0xA0, 0x97)
        canvas.drawCircle(-24 * density, h * 0.62f, 96 * density, p)
        p.color = Color.argb(0x34, 0xDC, 0xE2, 0xD4)
        canvas.drawCircle(w + 30 * density, h * 0.48f, 110 * density, p)
        if (ground) {
            // On a short screen the ground stays low: never more than a fifth of the page.
            val scale = minOf(1f, h / (density * 750f))
            for (hill in hills) ridge(canvas, w, h, h - hill.topDp * density * scale, hill.ampDp * density * scale, hill.phase, hill.waves, hill.color)
        }
        if (leaves) {
            sprig(canvas, density, 18 * density, h - 22 * density, -58f, Color.argb(0x8C, 0xA9, 0xB4, 0x9C), filled = true)
            sprig(canvas, density, w - 22 * density, h - 18 * density, -122f, Color.argb(0x99, 0x9C, 0xA8, 0x8E), filled = false)
        }
    }

    /** One hill: a gentle line of two waves across the width, filled down to the bottom. */
    private fun ridge(canvas: Canvas, w: Float, h: Float, top: Float, amp: Float, phase: Float, waves: Float, color: Int) {
        path.reset()
        path.moveTo(0f, h)
        val steps = 48
        for (i in 0..steps) {
            val x = w * i / steps
            val t = 2 * PI.toFloat() * waves * i / steps
            path.lineTo(x, top + amp * (0.6f * sin(t + phase) + 0.4f * sin(t * 2.3f + phase * 1.9f)))
        }
        path.lineTo(w, h)
        path.close()
        p.style = Paint.Style.FILL
        p.color = color
        canvas.drawPath(path, p)
    }

    /** A stem with five leaves, from ([x], [y]) at [angle] degrees. */
    private fun sprig(canvas: Canvas, density: Float, x: Float, y: Float, angle: Float, color: Int, filled: Boolean) {
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
            path.reset()
            path.moveTo(0f, -len * t)
            path.quadTo(side * 20 * density, -len * t - 6 * density, side * 30 * density, -len * t - 22 * density)
            path.quadTo(side * 10 * density, -len * t - 18 * density, 0f, -len * t)
            path.close()
            canvas.drawPath(path, p)
        }
        path.reset()
        path.moveTo(0f, -len)
        path.quadTo(8 * density, -len - 16 * density, 0f, -len - 30 * density)
        path.quadTo(-8 * density, -len - 16 * density, 0f, -len)
        canvas.drawPath(path, p)
        canvas.restore()
        p.style = Paint.Style.FILL
    }
}
