package app.mama.ui.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Which landscape a v1 screen or card shows (DESIGN_SYSTEM_V1 §7). */
enum class Scenery {
    /** Warm sunrise: setup steps, review. */
    DAWN,

    /** Brighter morning: home of a running series. */
    MORNING,

    /** Quiet: almost only sky and leaves (settings, statistics, long lists). */
    DAY,

    /** Soft twilight, still light: the lock at night. */
    EVENING,

    /** Sunrise with a road up the hill: failure / recovery, success. */
    RECOVERY,
}

/** Where the landscape sits; the content layout never depends on it. */
enum class SceneLayout {
    /** Top of the screen behind the title, fading into the page under the cards. */
    HEADER,

    /** Larger top landscape (home). */
    HERO,

    /** The whole screen, lightly veiled (lock). */
    FULL,

    /** Bottom of a card (choice cards). */
    CARD_BOTTOM,

    /** Right side of a card (quote cards). */
    CARD_RIGHT,
}

/**
 * The one background of UI v1. Phase 1 paints a light landscape in code
 * (controlled gradients and soft shapes: sky, sun, hazy mountains, lake,
 * hills, conifers, meadow, a leafy branch). A production image replaces it
 * without any layout change: put `assets/scenes/v1/<scenery>.webp` (or
 * `.jpg`), e.g. `scenes/v1/dawn.webp`; it is drawn in the same place with
 * the same fades. Never a screenshot of a reference.
 *
 * Rendered once per size into a cached bitmap, then only drawn.
 */
class ScenicBackground(
    private val context: Context,
    val scenery: Scenery,
    val layout: SceneLayout = SceneLayout.HEADER,
    /** Colour the landscape fades into: the page ([Palette.Bg]) or the card behind it. */
    private val base: Int = Palette.Bg,
    /** Rounded corners in px (cards); the outline follows them, so the view casts a matching shadow. */
    private val cornerRadius: Float = 0f,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val box = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val bitmap = SceneCache.get(context, scenery, layout, base, b.width(), b.height()) ?: run {
            paint.shader = null
            paint.color = base
            box.set(b)
            canvas.drawRoundRect(box, cornerRadius, cornerRadius, paint)
            return
        }
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        matrix.setScale(b.width() / bitmap.width.toFloat(), b.height() / bitmap.height.toFloat())
        matrix.postTranslate(b.left.toFloat(), b.top.toFloat())
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        box.set(b)
        if (cornerRadius > 0f) canvas.drawRoundRect(box, cornerRadius, cornerRadius, paint) else canvas.drawRect(box, paint)
        paint.shader = null
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, cornerRadius)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = if (cornerRadius > 0f) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
}

/** Rendered scenes, by scenery, layout, base colour and size. */
internal object SceneCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val photos = HashMap<Scenery, Bitmap?>()

    @Synchronized
    fun get(context: Context, scenery: Scenery, layout: SceneLayout, base: Int, w: Int, h: Int): Bitmap? {
        // Screen backgrounds are soft: half resolution is enough. Cards are small: full.
        val q = if (layout == SceneLayout.CARD_BOTTOM || layout == SceneLayout.CARD_RIGHT) 1f else 0.5f
        val bw = max(1, (w * q).roundToInt())
        val bh = max(1, (h * q).roundToInt())
        val key = "$scenery/$layout/$base/$bw x $bh"
        cache.get(key)?.let { return it }
        val bitmap = runCatching {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
                ScenePainter.paint(Canvas(it), bw.toFloat(), bh.toFloat(), scenery, layout, base, photo(context, scenery))
            }
        }.getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /** A production image for [scenery], if one is bundled. */
    private fun photo(context: Context, scenery: Scenery): Bitmap? = photos.getOrPut(scenery) {
        val name = scenery.name.lowercase()
        listOf("webp", "jpg", "png").firstNotNullOfOrNull { ext ->
            runCatching { context.assets.open("scenes/v1/$name.$ext").use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
    }
}

/**
 * Paints a light landscape. Geometry is relative to the landscape box
 * (width [w], height H); see the prototype notes in DESIGN_SYSTEM_V1 §7.
 * Only gradients, filled paths and soft (blurred) shapes.
 */
internal object ScenePainter {

    private class Look(
        val sky: IntArray,
        val sunX: Float,
        val sunY: Float,
        val glow: Int,
        val far: Int,
        val far2: Int,
        val haze: Int,
        val mid: Int,
        val midTree: Int,
        val lake: Int,
        val hill: Int,
        val hill2: Int,
        val tree: Int,
        val tree2: Int,
        val clouds: Int,
        val moon: Boolean = false,
        val road: Boolean = false,
        /** Sky, a far ridge and leaves only. */
        val quiet: Boolean = false,
    )

    private fun c(hex: Int) = Color.rgb(hex shr 16 and 0xFF, hex shr 8 and 0xFF, hex and 0xFF)

    private fun look(s: Scenery): Look = when (s) {
        Scenery.DAWN -> Look(
            sky = intArrayOf(c(0xF3F5F0), c(0xFBF4E6), c(0xFCE8C6), c(0xF8DDB4)), sunX = 0.80f, sunY = 0.34f, glow = c(0xFFE2A6),
            far = c(0xC9BFD3), far2 = c(0xB7B1C9), haze = c(0xF6EDE6), mid = c(0xAEBBB0), midTree = c(0x94A799), lake = c(0xF7E8CA),
            hill = c(0xA9C083), hill2 = c(0x86A56D), tree = c(0x68885C), tree2 = c(0x7F9B6B), clouds = c(0xF7D8CF),
        )
        Scenery.MORNING -> Look(
            sky = intArrayOf(c(0xEEF5F5), c(0xF8F6EC), c(0xFBECCB), c(0xF8E2BA)), sunX = 0.78f, sunY = 0.40f, glow = c(0xFFEAB4),
            far = c(0xC3C3D6), far2 = c(0xB0B4CA), haze = c(0xF3F0EA), mid = c(0xA9BCB2), midTree = c(0x8EA598), lake = c(0xF3EBD3),
            hill = c(0xA6C285), hill2 = c(0x84A76D), tree = c(0x62855A), tree2 = c(0x7C9C6A), clouds = c(0xF4DDD6),
        )
        Scenery.DAY -> Look(
            sky = intArrayOf(c(0xF4F7F3), c(0xFAF7EE), c(0xFBF1DE), c(0xFAEBD2)), sunX = 0.82f, sunY = 0.30f, glow = c(0xFFEFC8),
            far = c(0xD9D3DE), far2 = c(0xCDC9D8), haze = c(0xF8F2EC), mid = c(0xC3CDC3), midTree = c(0xAFBDB1), lake = c(0xF8EEDA),
            hill = c(0xBCCDA0), hill2 = c(0xA3BB8C), tree = c(0x86A07A), tree2 = c(0x97AE87), clouds = c(0xF7E4DC), quiet = true,
        )
        Scenery.EVENING -> Look(
            sky = intArrayOf(c(0xE5E7F1), c(0xEEE5EC), c(0xF6DDD3), c(0xF3D3C2)), sunX = 0.24f, sunY = 0.42f, glow = c(0xF8D4BA),
            far = c(0xB7B2CD), far2 = c(0xA6A5C1), haze = c(0xEFE6EA), mid = c(0xA0AAB6), midTree = c(0x8796A2), lake = c(0xEEDFDC),
            hill = c(0x96AC90), hill2 = c(0x7A9379), tree = c(0x5E7863), tree2 = c(0x71897A), clouds = c(0xEED3D8), moon = true,
        )
        Scenery.RECOVERY -> Look(
            sky = intArrayOf(c(0xF7F4EB), c(0xFBF1DC), c(0xFCE5B4), c(0xF8D9A2)), sunX = 0.80f, sunY = 0.44f, glow = c(0xFFE09A),
            far = c(0xD3C6CE), far2 = c(0xC4B7C1), haze = c(0xF8EEE2), mid = c(0xB9C0A8), midTree = c(0x9DAA8F), lake = c(0xF8E9C6),
            hill = c(0xBAC685), hill2 = c(0x98AB6B), tree = c(0x71895A), tree2 = c(0x8A9E6A), clouds = c(0xF8DCC9), road = true,
        )
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private fun alpha(color: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun soft(radius: Float) {
        p.maskFilter = if (radius >= 0.5f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
    }

    private fun reset() {
        p.shader = null
        p.maskFilter = null
        p.xfermode = null
        p.style = Paint.Style.FILL
        p.alpha = 255
    }

    fun paint(canvas: Canvas, w: Float, h: Float, scenery: Scenery, layout: SceneLayout, base: Int, photo: Bitmap?) {
        reset()
        canvas.drawColor(base)
        val l = look(scenery)
        when (layout) {
            SceneLayout.HEADER, SceneLayout.HERO -> {
                val H = w * if (layout == SceneLayout.HERO) 1.20f else 0.95f
                if (photo != null) drawPhoto(canvas, photo, 0f, 0f, w, H) else landscape(canvas, w, H, l)
                // Fade into the page, then the page itself.
                fadeDown(canvas, 0f, H * 0.66f, w, H, base)
                p.color = base
                canvas.drawRect(0f, H, w, h, p)
                titleVeil(canvas, w, H, base)
                if (photo == null) {
                    leaves(canvas, w, H * 0.34f)
                    bokeh(canvas, w, h)
                }
            }
            SceneLayout.FULL -> {
                val H = h * 0.80f
                if (photo != null) {
                    drawPhoto(canvas, photo, 0f, 0f, w, h)
                } else {
                    landscape(canvas, w, H, l)
                    // The meadow goes on to the bottom.
                    p.shader = LinearGradient(0f, H * 0.95f, 0f, h, l.hill, c(0xDCE5C8), Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, H * 0.95f, w, h, p)
                    p.shader = null
                    flowers(canvas, w, H * 0.95f, h, 120, 9)
                }
                // Light veils: the top (title) and the lower half (cards) stay readable.
                gradientRect(canvas, 0f, 0f, w, H * 0.40f, alpha(base, 150), alpha(base, 0))
                gradientRect(canvas, 0f, H * 0.72f, w, h, alpha(base, 0), alpha(base, 225), mid = alpha(base, 150), midAt = 0.4f)
                if (photo == null) {
                    leaves(canvas, w, h * 0.22f)
                    bokeh(canvas, w, h)
                }
            }
            SceneLayout.CARD_BOTTOM -> {
                // Landscape in the lower part of the card, sky dissolving into the card.
                val H = h * 1.6f
                canvas.save()
                canvas.translate(0f, h * 1.15f - H)
                if (photo != null) drawPhoto(canvas, photo, 0f, 0f, w, H) else landscape(canvas, w, H, l)
                canvas.restore()
                gradientRect(canvas, 0f, 0f, w, h * 0.62f, base, alpha(base, 0), mid = alpha(base, 235), midAt = 0.55f)
            }
            SceneLayout.CARD_RIGHT -> {
                val bw = w * 0.66f
                val H = max(h * 1.5f, bw * 0.95f)
                canvas.save()
                canvas.translate(w - bw, h * 1.12f - H)
                if (photo != null) drawPhoto(canvas, photo, 0f, 0f, bw, H) else landscape(canvas, bw, H, l)
                canvas.restore()
                // Text side stays the card colour.
                p.shader = LinearGradient(w - bw, 0f, w - bw * 0.35f, 0f, base, alpha(base, 0), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, p)
                p.shader = null
                p.color = base
                canvas.drawRect(0f, 0f, w - bw, h, p)
                gradientRect(canvas, 0f, 0f, w, h * 0.45f, alpha(base, 200), alpha(base, 0))
            }
        }
        reset()
    }

    // ---------------- landscape ----------------

    private fun landscape(c: Canvas, w: Float, H: Float, l: Look) {
        val horizon = H * 0.60f
        // Sky.
        p.shader = LinearGradient(0f, 0f, 0f, horizon, l.sky, floatArrayOf(0f, 0.45f, 0.8f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, horizon + 2f, p)
        p.shader = null
        // Sun (or moon) with its glow.
        val sx = l.sunX * w
        val sy = l.sunY * H
        glow(c, sx, sy, w * 0.75f, alpha(l.glow, 170), 1.8f)
        glow(c, sx, sy, w * 0.28f, alpha(c(0xFFF5D6), 210), 1.3f)
        if (l.moon) {
            soft(w * 0.003f); p.color = alpha(c(0xFFFDF6), 240)
            c.drawCircle(sx, sy, w * 0.03f, p)
        } else {
            soft(w * 0.006f); p.color = alpha(c(0xFFFBEC), 250)
            c.drawCircle(sx, sy, w * 0.065f, p)
        }
        // Long soft clouds.
        listOf(
            floatArrayOf(0.22f, 0.16f, 0.32f, 0.03f, 120f), floatArrayOf(0.60f, 0.10f, 0.26f, 0.022f, 100f),
            floatArrayOf(0.92f, 0.22f, 0.24f, 0.028f, 90f), floatArrayOf(0.50f, 0.27f, 0.30f, 0.018f, 80f),
        ).forEach { (cx, cy, rw, rh, a) ->
            soft(w * 0.03f); p.color = alpha(l.clouds, a.toInt())
            c.drawOval(w * (cx - rw), H * cy - w * rh, w * (cx + rw), H * cy + w * rh, p)
        }
        p.maskFilter = null
        // Far ranges with haze at their feet.
        for ((base, amp, seed, color, a) in listOf(
            Ridge(0.47f, 0.14f, 1.3f, l.far, 210), Ridge(0.53f, 0.11f, 4.2f, l.far2, 225),
        )) {
            ridgePath(w, H, base, amp, seed, if (seed < 2f) 0.8f else 0.7f)
            soft(w * 0.004f)
            p.shader = LinearGradient(0f, H * (base - amp), 0f, H * (base + 0.07f), alpha(color, a), alpha(l.haze, 160), Shader.TileMode.CLAMP)
            c.drawPath(path, p)
            p.shader = null
            band(c, w, H * (base + 0.01f), H * (base + 0.06f), alpha(l.haze, 150), H * 0.025f)
        }
        if (l.quiet) {
            p.maskFilter = null
            return
        }
        // Middle ridge with a soft tree line.
        val mBase = 0.585f
        val mAmp = 0.045f
        val mSeed = 7.9f
        ridgePath(w, H, mBase, mAmp, mSeed, 0.3f)
        soft(w * 0.002f)
        p.shader = LinearGradient(0f, H * (mBase - mAmp), 0f, H * mBase, alpha(l.mid, 235), alpha(l.mid, 210), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        val r = Rnd(42)
        soft(w * 0.0025f)
        p.color = alpha(l.midTree, 230)
        for (i in 0 until 70) {
            val t = (i + r.next() * 0.8f) / 70f
            val size = H * (0.025f + 0.03f * r.next())
            if (t > 0.28f && t < 0.52f) continue
            val g = H * (mBase - mAmp * noise(t, mSeed, 0.3f))
            tree(c, w * t, g + H * 0.008f, size)
        }
        band(c, w, H * 0.575f, H * 0.62f, alpha(l.haze, 140), H * 0.018f)
        // Lake with the sun's reflection.
        val lt = H * 0.605f
        p.maskFilter = null
        p.shader = LinearGradient(0f, lt, 0f, H * 0.7f, alpha(l.lake, 240), alpha(l.lake, 225), Shader.TileMode.CLAMP)
        c.drawRect(0f, lt, w, H * 0.76f, p)
        p.shader = null
        soft(w * 0.004f)
        p.color = alpha(c(0xFFF7DF), 190)
        for (i in 0 until 7) {
            val y = lt + H * 0.008f + i * H * 0.011f
            val half = w * (0.12f - i * 0.014f)
            c.drawOval(sx - half, y, sx + half, y + H * 0.004f, p)
        }
        // Near hills: the main slope on the right, a low one on the left.
        path.reset()
        path.moveTo(w * 0.30f, H * 0.86f)
        for (i in 0..60) {
            val t = i / 60f
            path.lineTo(w * (0.30f + 0.70f * t), H * (0.76f - 0.15f * sin(t * PI.toFloat() * 0.55f) - 0.008f * sin(t * 23f)))
        }
        path.lineTo(w, H * 0.58f); path.lineTo(w, H); path.lineTo(w * 0.3f, H); path.close()
        soft(w * 0.002f)
        p.shader = LinearGradient(0f, H * 0.6f, 0f, H * 0.9f, alpha(l.hill, 252), alpha(l.hill2, 252), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        path.reset()
        path.moveTo(0f, H * 0.71f)
        for (i in 0..40) {
            val t = i / 40f
            path.lineTo(w * 0.5f * t, H * (0.715f + 0.055f * t.pow(1.6f) - 0.01f * sin(i * 0.6f)))
        }
        path.lineTo(w * 0.5f, H); path.lineTo(0f, H); path.close()
        p.shader = LinearGradient(0f, H * 0.7f, 0f, H * 0.9f, alpha(l.hill, 240), alpha(l.hill2, 240), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        // Conifers on the slopes.
        val r2 = Rnd(7)
        soft(w * 0.0018f)
        for (i in 0 until 22) {
            val t = 0.50f + 0.50f * r2.next()
            val u = (t - 0.30f) / 0.70f
            val y = H * (0.76f - 0.15f * sin(u * PI.toFloat() * 0.55f)) + H * 0.03f * r2.next()
            val size = H * (0.05f + 0.08f * r2.next())
            p.color = alpha(if (r2.next() > 0.4f) l.tree else l.tree2, 245)
            tree(c, w * t, y + H * 0.012f, size)
        }
        p.color = alpha(l.tree2, 230)
        for (i in 0 until 7) {
            val t = 0.01f + 0.2f * r2.next()
            tree(c, w * t, H * 0.727f, H * (0.04f + 0.05f * r2.next()))
        }
        if (l.road) road(c, w, H)
        flowers(c, w, H * 0.74f, H, 180, 3)
        p.maskFilter = null
    }

    private data class Ridge(val base: Float, val amp: Float, val seed: Float, val color: Int, val alpha: Int)

    private fun noise(t: Float, seed: Float, rough: Float): Float =
        0.5f * sin(t * 5.1f + seed) + 0.28f * sin(t * 11.7f + seed * 1.9f) +
            rough * (0.12f * sin(t * 23.3f + seed * 2.7f) + 0.07f * abs(sin(t * 41f + seed)) + 0.03f * sin(t * 83f + seed * 3.1f))

    private fun ridgePath(w: Float, H: Float, base: Float, amp: Float, seed: Float, rough: Float) {
        path.reset()
        path.moveTo(0f, H)
        for (i in 0..160) {
            val t = i / 160f
            path.lineTo(w * t, H * (base - amp * noise(t, seed, rough)))
        }
        path.lineTo(w, H)
        path.close()
    }

    private fun tree(c: Canvas, x: Float, ground: Float, height: Float) {
        val hw = height * 0.19f
        path.reset()
        path.moveTo(x, ground - height)
        path.lineTo(x + hw * 0.5f, ground - height * 0.66f)
        path.lineTo(x + hw * 0.32f, ground - height * 0.64f)
        path.lineTo(x + hw * 0.8f, ground - height * 0.34f)
        path.lineTo(x + hw * 0.5f, ground - height * 0.33f)
        path.lineTo(x + hw, ground - height * 0.04f)
        path.lineTo(x - hw, ground - height * 0.04f)
        path.lineTo(x - hw * 0.5f, ground - height * 0.33f)
        path.lineTo(x - hw * 0.8f, ground - height * 0.34f)
        path.lineTo(x - hw * 0.32f, ground - height * 0.64f)
        path.lineTo(x - hw * 0.5f, ground - height * 0.66f)
        path.close()
        c.drawPath(path, p)
    }

    /** A sandy road winding up the near hill (recovery: "the path goes on"). */
    private fun road(c: Canvas, w: Float, H: Float) {
        val n = 40
        val left = ArrayList<Pair<Float, Float>>()
        val right = ArrayList<Pair<Float, Float>>()
        for (i in 0..n) {
            val t = i / n.toFloat()
            val x = w * (0.93f - 0.33f * t + 0.07f * sin(t * 4.5f))
            val y = H * (1.0f - 0.36f * t)
            val half = w * (0.075f * (1 - t).pow(1.2f) + 0.006f)
            left += (x - half) to y
            right += (x + half) to y
        }
        path.reset()
        path.moveTo(left[0].first, left[0].second)
        left.drop(1).forEach { path.lineTo(it.first, it.second) }
        right.reversed().forEach { path.lineTo(it.first, it.second) }
        path.close()
        soft(w * 0.002f)
        p.shader = LinearGradient(0f, H * 0.64f, 0f, H, c(0xEEDFC0), c(0xE6D3AE), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
    }

    /** White daisies with warm centres, denser near the bottom. */
    private fun flowers(c: Canvas, w: Float, top: Float, bottom: Float, count: Int, seed: Int) {
        val r = Rnd(seed)
        soft(w * 0.0015f)
        for (i in 0 until count) {
            val x = w * r.next()
            val v = r.next()
            val y = top + (bottom - top) * v
            val rr = w * (0.0035f + 0.006f * v * r.next() + 0.002f)
            if (x < w * 0.32f && y < top + (bottom - top) * 0.25f) continue
            p.color = alpha(c(0xFFFDF6), 235)
            c.drawOval(x - rr, y - rr * 0.8f, x + rr, y + rr * 0.8f, p)
            p.color = alpha(c(0xEDC25E), 235)
            c.drawOval(x - rr * 0.32f, y - rr * 0.3f, x + rr * 0.32f, y + rr * 0.3f, p)
        }
        p.maskFilter = null
    }

    // ---------------- foliage frame ----------------

    /** Leafy branches entering from the top-right corner; [lowY] places the small lower twig. */
    private fun leaves(c: Canvas, w: Float, lowY: Float) {
        branch(c, w, w * 1.06f, w * 0.05f, 205f, w * 0.30f, 5, 23, w * 0.075f, w * 0.012f, 190)
        branch(c, w, w * 1.04f, -w * 0.04f, 162f, w * 0.42f, 7, 11, w * 0.085f, w * 0.002f, 250)
        branch(c, w, w * 1.03f, lowY, 195f, w * 0.16f, 3, 31, w * 0.065f, w * 0.002f, 240)
        p.maskFilter = null
    }

    private val leafColors = intArrayOf(0xCDD46F, 0xB9C85C, 0xA9BC52, 0xDEDC7E, 0x98AE48, 0xC4CF63)

    private fun branch(c: Canvas, w: Float, ox: Float, oy: Float, angleDeg: Float, length: Float, n: Int, seed: Int, size: Float, blur: Float, a: Int) {
        val r = Rnd(seed)
        val ang = Math.toRadians(angleDeg.toDouble()).toFloat()
        val stem = (0..n).map { k ->
            val t = k / n.toFloat()
            (ox + cos(ang) * length * t) to (oy + sin(ang) * length * t + length * 0.22f * t * t)
        }
        soft(blur)
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, w * 0.005f)
        p.color = alpha(c(0x7E8F3C), a)
        path.reset()
        path.moveTo(stem[0].first, stem[0].second)
        stem.drop(1).forEach { path.lineTo(it.first, it.second) }
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        for (k in 1..n) {
            val (px, py) = stem[k]
            for (side in intArrayOf(-1, 1)) {
                if (k == n && side == 1) continue
                val la = ang + side * Math.toRadians(40.0 + 20 * r.next()).toFloat() + Math.toRadians(18.0).toFloat()
                val ll = size * (0.75f + 0.5f * r.next()) * (1f - 0.25f * k / n)
                val col = c(leafColors[(r.next() * leafColors.size).toInt() % leafColors.size])
                leaf(c, px + cos(la) * ll * 0.5f, py + sin(la) * ll * 0.5f, la, ll, alpha(col, a))
            }
        }
        val (tx, ty) = stem.last()
        leaf(c, tx + cos(ang) * size * 0.45f, ty + sin(ang) * size * 0.45f, ang + 0.17f, size * 0.9f, alpha(c(0xC7D068), a))
    }

    private fun leaf(c: Canvas, cx: Float, cy: Float, ang: Float, len: Float, color: Int) {
        path.reset()
        for (j in 0 until 24) {
            val th = 2 * PI.toFloat() * j / 24
            val ex = len * 0.5f * cos(th)
            val ey = len * 0.23f * sin(th) * (1 - 0.35f * cos(th))
            val x = cx + ex * cos(ang) - ey * sin(ang)
            val y = cy + ex * sin(ang) + ey * cos(ang)
            if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        p.color = color
        c.drawPath(path, p)
    }

    /** Out-of-focus foliage and blossoms along the bottom edge. */
    private fun bokeh(c: Canvas, w: Float, h: Float) {
        val r = Rnd(5)
        val greens = intArrayOf(0xBCCB8E, 0xA7BB7C, 0xDCE2AE, 0xC9D69F, 0xE9E7C9)
        soft(w * 0.025f)
        for (i in 0 until 30) {
            val x = if (i % 2 == 0) w * 0.32f * r.next() else w * (0.68f + 0.32f * r.next())
            val y = h * (0.88f + 0.14f * r.next())
            val rr = w * (0.035f + 0.05f * r.next())
            p.color = alpha(c(greens[(r.next() * greens.size).toInt() % greens.size]), 170)
            c.drawOval(x - rr, y - rr * 0.65f, x + rr, y + rr * 0.65f, p)
        }
        soft(w * 0.007f)
        p.color = alpha(c(0xFFFFFA), 215)
        for (i in 0 until 12) {
            val x = w * (0.03f + 0.94f * r.next())
            val y = h * (0.91f + 0.09f * r.next())
            val rr = w * (0.012f + 0.014f * r.next())
            if (x / w > 0.3f && x / w < 0.7f && r.next() > 0.3f) continue
            c.drawCircle(x, y, rr, p)
        }
        p.maskFilter = null
    }

    // ---------------- helpers ----------------

    /** Radial glow fading as (1 − d)^power. */
    private fun glow(c: Canvas, x: Float, y: Float, radius: Float, color: Int, power: Float) {
        val a = Color.alpha(color)
        val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val colors = IntArray(stops.size) { i -> alpha(color, (a * (1 - stops[i]).pow(power)).roundToInt()) }
        p.maskFilter = null
        p.shader = RadialGradient(x, y, radius, colors, stops, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, radius, p)
        p.shader = null
    }

    /** A soft horizontal band (mist). */
    private fun band(c: Canvas, w: Float, top: Float, bottom: Float, color: Int, blur: Float) {
        soft(blur)
        p.color = color
        c.drawRect(-blur, top, w + blur, bottom, p)
        p.maskFilter = null
    }

    private fun gradientRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, from: Int, to: Int, mid: Int? = null, midAt: Float = 0.5f) {
        p.maskFilter = null
        p.shader = if (mid != null) {
            LinearGradient(0f, t, 0f, b, intArrayOf(from, mid, to), floatArrayOf(0f, midAt, 1f), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, t, 0f, b, from, to, Shader.TileMode.CLAMP)
        }
        c.drawRect(l, t, r, b, p)
        p.shader = null
    }

    private fun fadeDown(c: Canvas, l: Float, t: Float, r: Float, b: Float, base: Int) =
        gradientRect(c, l, t, r, b, alpha(base, 0), base, mid = alpha(base, 180), midAt = 0.55f)

    /** The left part of the sky is veiled, so the title on it reads well. */
    private fun titleVeil(c: Canvas, w: Float, H: Float, base: Int) {
        val bottom = H * 0.8f
        val layer = c.saveLayer(0f, 0f, w, bottom, null)
        p.maskFilter = null
        p.shader = LinearGradient(0f, 0f, w * 0.66f, 0f, intArrayOf(alpha(base, 175), alpha(base, 70), alpha(base, 0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w * 0.66f, bottom, p)
        p.shader = LinearGradient(0f, 0f, 0f, bottom, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawRect(0f, 0f, w, bottom, p)
        p.xfermode = null
        p.shader = null
        c.restoreToCount(layer)
    }

    /** A production image in the landscape box: width-filling, top-aligned (centre-cropped if taller boxes need it). */
    private fun drawPhoto(c: Canvas, bitmap: Bitmap, left: Float, top: Float, w: Float, H: Float) {
        val scale = max(w / bitmap.width, H / bitmap.height)
        val sw = min(bitmap.width.toFloat(), w / scale)
        val sh = min(bitmap.height.toFloat(), H / scale)
        val sl = ((bitmap.width - sw) / 2f).roundToInt()
        val src = Rect(sl, 0, (sl + sw).roundToInt(), sh.roundToInt())
        p.maskFilter = null
        p.isFilterBitmap = true
        c.drawBitmap(bitmap, src, RectF(left, top, left + w, top + H), p)
    }

    /** The same pseudo-random sequence on every device, so a scene never changes between draws. */
    private class Rnd(seed: Int) {
        private var r = seed.toLong()
        fun next(): Float {
            r = (r * 1103515245L + 12345L) and 0x7fffffffL
            return ((r shr 8) and 0xffffL).toFloat() / 65535f
        }
    }
}
