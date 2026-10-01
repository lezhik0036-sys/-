package app.mama.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/** Which landscape a screen shows behind its content. */
enum class Scene {
    /** Warm sunrise over forest and mountains (welcome, success). */
    DAWN,

    /** Night with stars and a lake (dashboard, active lock). */
    NIGHT,

    /** Darker evening (failure). */
    DUSK,
}

/**
 * Illustrated mountain landscape that stands in for photography: sky, sun
 * or moon glow, layered hazy ridges with pine forests, mist and a lake, and
 * a readability overlay (darker at top and bottom). No image assets.
 */
class SceneryDrawable(private val scene: Scene) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private class Palette(
        val sky: IntArray,
        val glow: Int,
        val glowX: Float,
        val glowY: Float,
        val glowR: Float,
        val ridges: IntArray,
        val forest: Int,
        val water: IntArray,
        val overlayTop: Int,
        val overlayBottom: Int,
        val stars: Boolean,
    )

    private val p = when (scene) {
        Scene.DAWN -> Palette(
            sky = intArrayOf(Color.rgb(0x8A, 0x98, 0x93), Color.rgb(0xD9, 0xC3, 0x9E), Color.rgb(0xF2, 0xD3, 0xA0)),
            glow = Color.argb(0xD0, 0xFF, 0xE6, 0xB0), glowX = 0.62f, glowY = 0.40f, glowR = 0.55f,
            ridges = intArrayOf(Color.rgb(0xA7, 0xA8, 0x95), Color.rgb(0x7C, 0x84, 0x70), Color.rgb(0x4E, 0x5C, 0x4A), Color.rgb(0x2C, 0x3B, 0x2E)),
            forest = Color.rgb(0x1D, 0x2A, 0x20),
            water = intArrayOf(Color.rgb(0x6E, 0x75, 0x66), Color.rgb(0x26, 0x32, 0x28)),
            overlayTop = Color.argb(0x10, 0, 0, 0), overlayBottom = Color.argb(0xB0, 0x0D, 0x1A, 0x14),
            stars = false,
        )
        Scene.NIGHT -> Palette(
            sky = intArrayOf(Color.rgb(0x0B, 0x16, 0x14), Color.rgb(0x1F, 0x33, 0x2C), Color.rgb(0x3D, 0x4E, 0x43)),
            glow = Color.argb(0x70, 0xC6, 0xD3, 0x9D), glowX = 0.5f, glowY = 0.45f, glowR = 0.45f,
            ridges = intArrayOf(Color.rgb(0x33, 0x45, 0x3D), Color.rgb(0x22, 0x33, 0x2C), Color.rgb(0x16, 0x24, 0x1F), Color.rgb(0x0D, 0x18, 0x14)),
            forest = Color.rgb(0x07, 0x10, 0x0D),
            water = intArrayOf(Color.rgb(0x1C, 0x2C, 0x26), Color.rgb(0x05, 0x0D, 0x0A)),
            overlayTop = Color.argb(0x60, 0x09, 0x17, 0x14), overlayBottom = Color.argb(0xD8, 0x09, 0x17, 0x14),
            stars = true,
        )
        Scene.DUSK -> Palette(
            sky = intArrayOf(Color.rgb(0x14, 0x1C, 0x1A), Color.rgb(0x38, 0x3A, 0x33), Color.rgb(0x6B, 0x5A, 0x4A)),
            glow = Color.argb(0x60, 0xE0, 0x9A, 0x7A), glowX = 0.5f, glowY = 0.42f, glowR = 0.5f,
            ridges = intArrayOf(Color.rgb(0x45, 0x48, 0x40), Color.rgb(0x2E, 0x34, 0x2E), Color.rgb(0x1C, 0x23, 0x1F), Color.rgb(0x0F, 0x15, 0x12)),
            forest = Color.rgb(0x08, 0x0E, 0x0B),
            water = intArrayOf(Color.rgb(0x2A, 0x2C, 0x27), Color.rgb(0x06, 0x0B, 0x09)),
            overlayTop = Color.argb(0x70, 0x09, 0x17, 0x14), overlayBottom = Color.argb(0xE0, 0x09, 0x17, 0x14),
            stars = false,
        )
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0 || h <= 0) return
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val horizon = h * 0.62f

        // Sky.
        paint.shader = LinearGradient(0f, 0f, 0f, horizon, p.sky, floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        // Sun / moon glow.
        paint.shader = RadialGradient(w * p.glowX, h * p.glowY, w * p.glowR, p.glow, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        if (p.stars) drawStars(canvas, w, horizon)
        if (scene == Scene.NIGHT) {
            paint.color = Color.argb(0xD0, 0xEE, 0xF0, 0xE0)
            canvas.drawCircle(w * 0.74f, h * 0.17f, w * 0.022f, paint)
        }

        // Ridges, far to near, with forests on the nearer ones and mist between.
        val layers = listOf(
            Triple(0.40f, 0.11f, 1.3f), Triple(0.47f, 0.10f, 4.1f), Triple(0.54f, 0.08f, 7.9f), Triple(0.60f, 0.05f, 11.3f),
        )
        layers.forEachIndexed { i, (base, amp, seed) ->
            ridge(canvas, w, h, base, amp, seed, p.ridges[i])
            if (i >= 2) forest(canvas, w, h, base, amp, seed, if (i == 3) p.forest else p.ridges[i], i)
            mist(canvas, w, h * (base + 0.02f), h * 0.05f)
        }

        // Lake with a soft reflection, then the near shore pines.
        paint.shader = LinearGradient(0f, horizon, 0f, h, p.water[0], p.water[1], Shader.TileMode.CLAMP)
        canvas.drawRect(0f, horizon, w, h, paint)
        val g = p.glow
        paint.shader = RadialGradient(
            w * p.glowX, horizon, w * 0.5f,
            Color.argb(0x40, Color.red(g), Color.green(g), Color.blue(g)), Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, horizon, w, h, paint)
        paint.shader = null
        paint.color = Color.argb(0x22, 0xFF, 0xFF, 0xFF)
        for (i in 0 until 7) {
            val y = horizon + (h - horizon) * (0.08f + i * 0.11f)
            val half = w * (0.18f - i * 0.018f)
            canvas.drawRect(w * p.glowX - half, y, w * p.glowX + half, y + h * 0.0018f, paint)
        }
        shoreTrees(canvas, w, h, horizon)

        // Readability overlay.
        paint.shader = LinearGradient(
            0f, 0f, 0f, h, intArrayOf(p.overlayTop, Color.TRANSPARENT, p.overlayBottom), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        canvas.restore()
    }

    private fun ridgeY(t: Float, h: Float, base: Float, amp: Float, seed: Float): Float {
        val n = 0.5f * sin(t * 5.1f + seed) + 0.28f * sin(t * 11.7f + seed * 1.9f) + 0.14f * sin(t * 23.3f + seed * 2.7f) +
            0.08f * abs(sin(t * 41f + seed))
        return h * (base - amp * n)
    }

    private fun ridge(c: Canvas, w: Float, h: Float, base: Float, amp: Float, seed: Float, color: Int) {
        path.reset()
        path.moveTo(0f, h)
        val steps = 90
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            path.lineTo(w * t, ridgeY(t, h, base, amp, seed))
        }
        path.lineTo(w, h)
        path.close()
        paint.color = color
        c.drawPath(path, paint)
    }

    /** Pine silhouettes standing on a ridge line. */
    private fun forest(c: Canvas, w: Float, h: Float, base: Float, amp: Float, seed: Float, color: Int, layer: Int) {
        paint.color = color
        val count = if (layer == 3) 70 else 46
        val size = h * if (layer == 3) 0.05f else 0.028f
        var r = (seed * 1000).toInt()
        for (i in 0 until count) {
            r = r * 1103515245 + 12345
            val jitter = ((r ushr 8) and 0xFF) / 255f
            val t = (i + jitter * 0.8f) / count
            tree(c, w * t, ridgeY(t, h, base, amp, seed) + size * 0.25f, size * (0.6f + jitter * 0.8f))
        }
    }

    private fun tree(c: Canvas, x: Float, ground: Float, height: Float) {
        val half = height * 0.22f
        path.reset()
        path.moveTo(x, ground - height)
        path.lineTo(x + half * 0.7f, ground - height * 0.55f)
        path.lineTo(x + half * 0.45f, ground - height * 0.55f)
        path.lineTo(x + half, ground - height * 0.12f)
        path.lineTo(x - half, ground - height * 0.12f)
        path.lineTo(x - half * 0.45f, ground - height * 0.55f)
        path.lineTo(x - half * 0.7f, ground - height * 0.55f)
        path.close()
        c.drawPath(path, paint)
        c.drawRect(x - half * 0.08f, ground - height * 0.14f, x + half * 0.08f, ground + height * 0.05f, paint)
    }

    private fun shoreTrees(c: Canvas, w: Float, h: Float, horizon: Float) {
        paint.color = p.forest
        listOf(0.02f to 0.34f, 0.08f to 0.26f, 0.13f to 0.2f, 0.9f to 0.3f, 0.96f to 0.38f, 0.85f to 0.2f).forEach { (x, s) ->
            tree(c, w * x, horizon + h * 0.03f, h * s)
        }
        c.drawRect(0f, horizon + h * 0.025f, w * 0.18f, horizon + h * 0.04f, paint)
        c.drawRect(w * 0.82f, horizon + h * 0.025f, w, horizon + h * 0.04f, paint)
    }

    private fun mist(c: Canvas, w: Float, y: Float, height: Float) {
        paint.shader = LinearGradient(
            0f, y - height, 0f, y + height,
            intArrayOf(Color.TRANSPARENT, Color.argb(0x26, 0xFF, 0xFF, 0xF5), Color.TRANSPARENT), null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, y - height, w, y + height, paint)
        paint.shader = null
    }

    private fun drawStars(c: Canvas, w: Float, maxY: Float) {
        var r = 7
        for (i in 0 until 70) {
            r = r * 1103515245 + 12345
            val x = ((r ushr 4) and 0xFFFF) / 65535f
            r = r * 1103515245 + 12345
            val y = ((r ushr 4) and 0xFFFF) / 65535f
            val a = 0x30 + ((r ushr 20) and 0x7F)
            paint.color = Color.argb(a, 0xFF, 0xFF, 0xF2)
            c.drawCircle(w * x, maxY * 0.7f * y, 1.2f + (i % 3) * 0.5f, paint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** Thin countdown ring with a soft glow, for night screens. */
class RingView(context: Context) : View(context) {
    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
        color = Color.argb(0x40, 0xF3, 0xF1, 0xEA)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12 * density
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(0x38, 0xC6, 0xD3, 0x9D)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
        strokeCap = Paint.Cap.ROUND
        color = MamaColors.ProgressGlow
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x8C, 0x09, 0x17, 0x14) }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val inset = glow.strokeWidth
        box.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(box, fill)
        canvas.drawOval(box, track)
        canvas.drawArc(box, -90f, 360f * progress, false, glow)
        canvas.drawArc(box, -90f, 360f * progress, false, arc)
    }
}
