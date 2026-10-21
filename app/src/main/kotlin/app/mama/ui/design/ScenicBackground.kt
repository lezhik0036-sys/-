package app.mama.ui.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
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

/**
 * Which landscape a v1 screen or card shows (DESIGN_SYSTEM_V1 §7). Each is a
 * production photo in `assets/scenes/v1/<name>.webp`; without the file the
 * landscape is painted in code (fallback).
 */
enum class Scenery {
    /** Quiet alpine lake at sunrise: ACTIVE and the lock, calm states, the first days of a series. */
    DAWN,

    /** Lake and mountains in the morning: home, welcome, a positive start. */
    MORNING,

    /** Alpine valley at sunrise: the setup wizard (series, time, contact, «Цена слова», review). */
    DAY,

    /** Golden sunrise over the lake: a scheduled series, waiting for the start, warm evening states. */
    EVENING,

    /** Golden path to the lake: a series ended early, Restart, a second chance. */
    RECOVERY,
}

/**
 * How much light a nature window lets in (MAMA_DESIGN_SYSTEM_V2 «Свет»): the
 * energy grows along a series — [QUIET] at its start, before it and on the
 * neutral screens (Trusted Exit, Restart), [CALM] in the middle, [BRIGHT] at
 * its end and on Success.
 */
enum class SceneMood(
    /** Saturation of the photo. */
    val saturation: Float,
    /** Share of the page colour lifted into the photo. */
    val lift: Float,
    /** Opacity (0–255) of the mist at the ground. */
    val mist: Int,
) {
    QUIET(0.84f, 0.1f, 70),
    CALM(0.96f, 0.04f, 36),
    BRIGHT(1.06f, 0f, 14),
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

    /** The lower part of the screen, fading into the cream page above it (the lock). */
    GROUND,

    /** The whole box, nothing over it (a photo card). */
    PICTURE,
}

/**
 * A window into nature (MAMA_DESIGN_SYSTEM_V2 «Образы»). Draws the
 * production photo of the scenery (`assets/scenes/v1/<scenery>.webp`),
 * cropped around its sun for the layout, in the warm golden light of its
 * [SceneMood] with a light mist towards the ground. On a screen it dissolves
 * softly into the cream [EarthPage] (as on the approved screens); light
 * cream veils stay where text sits. The photo is never redrawn. Without the file, a light landscape
 * is painted in code instead (fallback), in the same place.
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
    /** [SceneLayout.CARD_RIGHT]: share of the card width the landscape takes. */
    private val vignette: Float = 0.42f,
    private val mood: SceneMood = SceneMood.CALM,
    /**
     * [SceneLayout.HERO]: where the sun of the photo sits in the window (0 top,
     * 1 bottom), the photo whole in width — low to show the sky, high to show
     * the ground. Null: the home framing.
     */
    private val focusY: Float? = null,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val box = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val bitmap = SceneCache.get(context, scenery, layout, base, b.width(), b.height(), vignette, mood, focusY) ?: run {
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
    private val cache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** Decoded production photos (≈ 6 MB each): the last few used. */
    private val photos = object : LruCache<Scenery, Bitmap>(20 * 1024) {
        override fun sizeOf(key: Scenery, value: Bitmap) = value.byteCount / 1024
    }
    private var bundled: Map<Scenery, String>? = null

    @Synchronized
    fun get(
        context: Context,
        scenery: Scenery,
        layout: SceneLayout,
        base: Int,
        w: Int,
        h: Int,
        vignette: Float = 0.42f,
        mood: SceneMood = SceneMood.CALM,
        focusY: Float? = null,
    ): Bitmap? {
        // A painted screen background is soft: half resolution is enough. Photos and cards: full.
        val photo = file(context, scenery) != null
        val q = if (photo || layout == SceneLayout.CARD_BOTTOM || layout == SceneLayout.CARD_RIGHT) 1f else 0.5f
        val bw = max(1, (w * q).roundToInt())
        val bh = max(1, (h * q).roundToInt())
        val key = "$scenery/$layout/$base/$vignette/$mood/$focusY/$bw x $bh"
        cache.get(key)?.let { return it }
        val density = context.resources.displayMetrics.density * q
        val bitmap = runCatching {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
                ScenePainter.paint(
                    Canvas(it), bw.toFloat(), bh.toFloat(), scenery, layout, base, photo(context, scenery), vignette, density, mood, focusY,
                    EarthPainter.sprig(context),
                )
            }
        }.getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /** The production photo of [scenery], if one is bundled. */
    private fun photo(context: Context, scenery: Scenery): Bitmap? {
        photos.get(scenery)?.let { return it }
        val path = file(context, scenery) ?: return null
        val bitmap = runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return null
        photos.put(scenery, bitmap)
        return bitmap
    }

    /** `scenes/v1/<scenery>.webp` (or `.jpg`, `.png`), looked up once. */
    private fun file(context: Context, scenery: Scenery): String? {
        val files = bundled ?: run {
            val names = runCatching { context.assets.list("scenes/v1")?.toSet() }.getOrNull().orEmpty()
            Scenery.entries.mapNotNull { s ->
                val name = s.name.lowercase()
                listOf("webp", "jpg", "png").map { "$name.$it" }.firstOrNull { it in names }?.let { s to "scenes/v1/$it" }
            }.toMap().also { bundled = it }
        }
        return files[scenery]
    }
}

/**
 * Paints a light sunrise landscape in code (DESIGN_SYSTEM_V1 §7): warm sky
 * with a sunrise glow and soft rays, layered hazy ranges with fog between
 * them, a golden lake with the sun's reflection, sunlit slopes with
 * irregular two-tone conifers, a meadow full of daisies and a light grain.
 * Brush-like soft dabs give the hills and meadow a painted texture.
 * Geometry is relative to the landscape box (width w, height H). Only
 * gradients, filled paths and soft (blurred) shapes, so it renders the same
 * everywhere.
 */
internal object ScenePainter {

    private class Look(
        /** Sky from the zenith to the horizon (5 stops). */
        val sky: IntArray,
        val sunX: Float,
        val sunY: Float,
        val bloom: Int,
        val core: Int,
        val far: Int,
        val far2: Int,
        val range3: Int,
        val fog: Int,
        val mid: Int,
        val midTree: Int,
        val lake: IntArray,
        val hill: Int,
        val hill2: Int,
        /** Warm sunlight on ridges, slopes and grass. */
        val lit: Int,
        val tree: Int,
        val treeLit: Int,
        val meadow: IntArray,
        val clouds: Int,
        val moon: Boolean = false,
        val road: Boolean = false,
        /** Sky and far ranges only (settings, statistics, long lists). */
        val quiet: Boolean = false,
    )

    private fun c(hex: Int) = Color.rgb(hex shr 16 and 0xFF, hex shr 8 and 0xFF, hex and 0xFF)
    private fun cs(vararg hex: Int) = IntArray(hex.size) { c(hex[it]) }

    private fun look(s: Scenery): Look = when (s) {
        Scenery.DAWN -> Look(
            sky = cs(0xEAF0EE, 0xF6F0E4, 0xFBE3C2, 0xF8D3A1, 0xF5C792), sunX = 0.80f, sunY = 0.34f, bloom = c(0xFFD99A), core = c(0xFFFBEA),
            far = c(0xD5BCCD), far2 = c(0xBFA9C6), range3 = c(0xA6A6BD), fog = c(0xF8E8DF), mid = c(0x8FA19A), midTree = c(0x6F8A7C),
            lake = cs(0xF9D9A6, 0xEEDCC2), hill = c(0x93B464), hill2 = c(0x567D45), lit = c(0xEDE29A), tree = c(0x3D6140), treeLit = c(0x5F8550),
            meadow = cs(0xA9C46A, 0x7EA453), clouds = c(0xF5D2C6),
        )
        Scenery.MORNING -> Look(
            sky = cs(0xE4EEF0, 0xF3F2EA, 0xFAE8C9, 0xF8DAAA, 0xF5CF9C), sunX = 0.78f, sunY = 0.40f, bloom = c(0xFFE2A6), core = c(0xFFFCEE),
            far = c(0xCFBFD3), far2 = c(0xB9AECB), range3 = c(0xA2A8BE), fog = c(0xF6ECE4), mid = c(0x8DA39D), midTree = c(0x6D8A7E),
            lake = cs(0xF7DEAE, 0xECE0CB), hill = c(0x93B667), hill2 = c(0x567F47), lit = c(0xEDE6A0), tree = c(0x3B6141), treeLit = c(0x5D8652),
            meadow = cs(0xA8C66C, 0x7DA655), clouds = c(0xF3D7CF),
        )
        Scenery.DAY -> Look(
            sky = cs(0xEEF2EF, 0xF7F3EA, 0xFAEBD3, 0xF8E2C2, 0xF6DAB4), sunX = 0.82f, sunY = 0.30f, bloom = c(0xFFE6B8), core = c(0xFFFCF0),
            far = c(0xDCCFDA), far2 = c(0xCFC5D6), range3 = c(0xC2C3CF), fog = c(0xF9F1EA), mid = c(0x8FA19A), midTree = c(0x6F8A7C),
            lake = cs(0xF9E6C6, 0xF2E7D6), hill = c(0x93B464), hill2 = c(0x567D45), lit = c(0xEDE29A), tree = c(0x3D6140), treeLit = c(0x5F8550),
            meadow = cs(0xA9C46A, 0x7EA453), clouds = c(0xF5DCD2), quiet = true,
        )
        Scenery.EVENING -> Look(
            sky = cs(0xE1E4EF, 0xECE4EC, 0xF5D9D2, 0xF4CDB9, 0xF1C3AB), sunX = 0.24f, sunY = 0.42f, bloom = c(0xF9CDAF), core = c(0xFFF7EE),
            far = c(0xBCAFCB), far2 = c(0xA79FC0), range3 = c(0x9599B4), fog = c(0xF0E2E5), mid = c(0x8B97A3), midTree = c(0x73828F),
            lake = cs(0xF3D2C2, 0xE5DAD7), hill = c(0x86A47C), hill2 = c(0x557259), lit = c(0xEBD7B4), tree = c(0x3E5949), treeLit = c(0x5B7562),
            meadow = cs(0x9DB47F, 0x78966A), clouds = c(0xEECFD3), moon = true,
        )
        Scenery.RECOVERY -> Look(
            sky = cs(0xF2F2EA, 0xF9F1DF, 0xFCE5B8, 0xF9D49C, 0xF6C88A), sunX = 0.80f, sunY = 0.42f, bloom = c(0xFFD893), core = c(0xFFFBE8),
            far = c(0xDAC2C8), far2 = c(0xC8B1BE), range3 = c(0xAFAAB8), fog = c(0xFAEADA), mid = c(0x9CA88F), midTree = c(0x7F8F74),
            lake = cs(0xFAD9A0, 0xF0DFC2), hill = c(0xA5BC6A), hill2 = c(0x6A8A4A), lit = c(0xF3E39A), tree = c(0x46613F), treeLit = c(0x6C8A4E),
            meadow = cs(0xB3C870, 0x8BA859), clouds = c(0xF7D9C2), road = true,
        )
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val bounds = RectF()

    private fun alpha(color: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun soft(radius: Float) {
        p.maskFilter = if (radius >= 0.5f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
    }

    private fun reset() {
        p.shader = null
        p.maskFilter = null
        p.xfermode = null
        p.colorFilter = null
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
        p.alpha = 255
    }

    fun paint(
        canvas: Canvas,
        w: Float,
        h: Float,
        scenery: Scenery,
        layout: SceneLayout,
        base: Int,
        photo: Bitmap?,
        vignette: Float = 0.42f,
        density: Float = 1f,
        mood: SceneMood = SceneMood.CALM,
        focusY: Float? = null,
        /** The leaf sprig of the page under the window. */
        sprig: Bitmap? = null,
    ) {
        reset()
        this.mood = mood
        canvas.drawColor(base)
        val l = look(scenery)
        when (layout) {
            SceneLayout.HEADER, SceneLayout.HERO -> {
                val hero = layout == SceneLayout.HERO
                val H = w * if (hero) 1.22f else 1.0f
                // The page under the window: the cream page with its leaves on a screen, the plain colour on a card.
                if (base == Palette.Bg) EarthPainter.paint(canvas, w, h, density, sprig)
                reset()
                // The window: the photo (its sun right of the subtitle on a wizard step, under it on
                // home, a little closer), a light mist towards the ground, dissolving softly into the page.
                val edge = H * if (hero) 0.94f else 0.9f
                val layer = canvas.saveLayer(0f, 0f, w, H, null)
                if (photo != null) {
                    val framed = hero && focusY != null
                    val ty = if (framed) focusY!! else if (hero) 0.62f else 0.42f
                    drawPhoto(canvas, photo, scenery, 0f, 0f, w, H, focus(scenery).first, ty, if (hero && !framed) 1.2f else 1f)
                } else {
                    landscape(canvas, w, H, l, H)
                }
                mist(canvas, w, H * 0.42f, edge, mood.mist, H)
                // The lower part of the window fades into the page: no edge, the page simply takes over.
                p.shader = LinearGradient(0f, edge - w * 0.24f, 0f, edge, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                canvas.drawRect(0f, 0f, w, H, p)
                reset()
                canvas.restoreToCount(layer)
                if (photo != null) {
                    // Home: title, state badge and a phrase sit a little lower than a wizard subtitle.
                    // A framed window carries a centred title (Recovery): its veil spans the whole width.
                    textVeil(canvas, w, w * if (hero) 0.62f else 0.55f, w * 0.12f, base, centered = hero && focusY != null)
                } else {
                    titleVeil(canvas, w, H * if (hero) 0.40f else 0.82f, base)
                    grain(canvas, w, h)
                    bokeh(canvas, w, h)
                }
            }
            SceneLayout.FULL -> {
                val H = h * 0.86f
                if (photo != null) {
                    val (fx, fy) = focus(scenery)
                    drawPhoto(canvas, photo, scenery, 0f, 0f, w, h, fx, fy)
                } else {
                    landscape(canvas, w, H, l, h)
                }
                mist(canvas, w, h * 0.45f, h, mood.mist)
                // Light veils: the title at the top and the cards below stay readable.
                gradientRect(canvas, 0f, 0f, w, H * 0.40f, alpha(base, 150), alpha(base, 0))
                gradientRect(canvas, 0f, H * 0.70f, w, h, alpha(base, 0), alpha(base, 200), mid = alpha(base, 120), midAt = 0.5f)
                if (photo == null) {
                    grain(canvas, w, h)
                    leaves(canvas, w, h * 0.22f)
                    bokeh(canvas, w, h)
                }
            }
            SceneLayout.GROUND -> {
                // Cream above, the landscape below; its upper part dissolves into the page.
                val top = h * 0.4f
                EarthPainter.paint(canvas, w, h, density, null)
                reset()
                val layer = canvas.saveLayer(0f, top, w, h, null)
                if (photo != null) {
                    drawPhoto(canvas, photo, scenery, 0f, top, w, h - top, focus(scenery).first, 0.3f)
                } else {
                    canvas.save()
                    canvas.translate(0f, top)
                    landscape(canvas, w, h - top, l, h - top)
                    canvas.restore()
                }
                p.shader = LinearGradient(0f, top, 0f, top + (h - top) * 0.38f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
                p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                canvas.drawRect(0f, top, w, h, p)
                reset()
                canvas.restoreToCount(layer)
            }
            SceneLayout.PICTURE -> {
                if (photo != null) {
                    val (fx, fy) = focus(scenery)
                    drawPhoto(canvas, photo, scenery, 0f, 0f, w, h, fx, fy)
                } else {
                    landscape(canvas, w, h, l, h)
                }
            }
            SceneLayout.CARD_BOTTOM -> {
                // A band of landscape along the bottom, its height set by the card width, so taller text never runs into it.
                val band = min(h * 0.6f, w * 0.50f)
                val H = band * 1.9f
                if (photo != null) {
                    drawPhoto(canvas, photo, scenery, 0f, h - band, w, band, focus(scenery).first, 0.45f)
                } else {
                    canvas.save()
                    canvas.translate(0f, h + band * 0.1f - H)
                    landscape(canvas, w, H, l, H)
                    canvas.restore()
                }
                p.color = base
                canvas.drawRect(0f, 0f, w, h - band, p)
                gradientRect(canvas, 0f, h - band - 1f, w, h - band * 0.55f, base, alpha(base, 0), mid = alpha(base, 150), midAt = 0.45f)
            }
            SceneLayout.CARD_RIGHT -> {
                // Landscape on the right [vignette] of the card; the text side stays the card colour.
                val bw = w * vignette
                val H = max(h * 1.5f, bw * 0.95f)
                if (photo != null) {
                    drawPhoto(canvas, photo, scenery, w - bw, 0f, bw, h, 0.6f, 0.42f)
                } else {
                    canvas.save()
                    canvas.translate(w - bw, h * 1.12f - H)
                    landscape(canvas, bw, H, l, H)
                    canvas.restore()
                }
                p.color = base
                canvas.drawRect(0f, 0f, w - bw, h, p)
                p.shader = LinearGradient(w - bw - 1f, 0f, w - bw * 0.5f, 0f, base, alpha(base, 0), Shader.TileMode.CLAMP)
                canvas.drawRect(w - bw - 1f, 0f, w, h, p)
                p.shader = null
                gradientRect(canvas, 0f, 0f, w, h * 0.45f, alpha(base, 210), alpha(base, 0))
            }
        }
        reset()
    }

    // ---------------- landscape ----------------

    private data class Range(val base: Float, val amp: Float, val seed: Float, val color: Int, val alpha: Int, val rough: Float)

    /** [ground]: where the meadow ends (the box bottom, or lower for full-screen scenes). */
    private fun landscape(c: Canvas, w: Float, H: Float, l: Look, ground: Float) {
        val horizon = H * 0.60f
        p.shader = LinearGradient(0f, 0f, 0f, horizon, l.sky, floatArrayOf(0f, 0.35f, 0.62f, 0.85f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, horizon + 2f, p)
        p.shader = null
        val sx = l.sunX * w
        val sy = l.sunY * H
        // Sunrise glow: a wide bloom, a warm halo and a bright core.
        glow(c, sx, sy, w * 1.0f, alpha(l.bloom, 170), 1.4f)
        glow(c, sx, sy, w * 0.40f, alpha(c(0xFFF2CC), 240), 1.3f)
        glow(c, sx, sy, w * 0.16f, alpha(c(0xFFFBEA), 245), 1.1f)
        if (!l.moon && !l.quiet) rays(c, w, sx, sy)
        listOf(
            floatArrayOf(0.20f, 0.14f, 0.30f, 0.022f, 130f), floatArrayOf(0.55f, 0.08f, 0.24f, 0.016f, 100f),
            floatArrayOf(0.92f, 0.20f, 0.22f, 0.02f, 110f), floatArrayOf(0.40f, 0.25f, 0.32f, 0.014f, 100f),
            floatArrayOf(0.66f, 0.29f, 0.20f, 0.012f, 90f),
        ).forEach { (cx, cy, rw, rh, a) ->
            soft(w * 0.018f); p.color = alpha(l.clouds, a.toInt())
            c.drawOval(w * (cx - rw), H * cy - w * rh, w * (cx + rw), H * cy + w * rh, p)
        }
        soft(w * 0.004f)
        p.color = alpha(l.core, 253)
        c.drawCircle(sx, sy, w * if (l.moon) 0.03f else 0.065f, p)
        // Ranges, far to near: lavender, violet-grey, blue-grey; sunlit towards the sun; fog at their feet.
        val ranges = listOf(
            Range(0.455f, 0.12f, 1.3f, l.far, 225, 1.7f),
            Range(0.495f, 0.10f, 4.2f, l.far2, 238, 1.5f),
            Range(0.530f, 0.075f, 6.1f, l.range3, 245, 1.3f),
        )
        ranges.forEachIndexed { i, r ->
            if (l.quiet && i == 2) return@forEachIndexed
            ridgePath(path, w, H, r.base, r.amp, r.seed, r.rough)
            soft(w * 0.0018f)
            p.shader = LinearGradient(0f, H * (r.base - r.amp), 0f, H * (r.base + 0.05f), alpha(r.color, r.alpha), alpha(l.fog, 215), Shader.TileMode.CLAMP)
            c.drawPath(path, p)
            p.shader = null
            sunlit(c, path, l.lit, sx, sy, w * 0.6f, 0.5f - 0.1f * i, 1.4f)
            band(c, w, H * (r.base + 0.004f), H * (r.base + 0.045f), alpha(l.fog, 175), H * 0.016f)
        }
        if (l.quiet) {
            p.maskFilter = null
            return
        }
        // Forested near range.
        val mBase = 0.565f
        val mAmp = 0.045f
        val mSeed = 7.9f
        ridgePath(path, w, H, mBase, mAmp, mSeed, 0.5f)
        soft(w * 0.0015f)
        p.shader = LinearGradient(0f, H * (mBase - mAmp), 0f, H * mBase, alpha(l.mid, 245), alpha(l.mid, 230), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        forest(c, w, H, { t -> H * (mBase - mAmp * noise(t, mSeed, 0.5f)) + H * 0.004f }, 0f, 1f, 150, 0.014f, 0.035f, l.midTree, null, 42, 230, w * 0.002f)
        band(c, w, H * 0.568f, H * 0.60f, alpha(l.fog, 160), H * 0.012f)
        // Golden lake with the sun's reflection.
        val lt = H * 0.592f
        p.maskFilter = null
        val deep = blend(l.lake[1], l.range3, 0.3f)
        p.shader = LinearGradient(0f, lt, 0f, H * 0.86f, intArrayOf(alpha(l.lake[0], 252), alpha(l.lake[1], 252), alpha(deep, 252)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, lt, w, H * 0.86f, p)
        p.shader = null
        farShore(c, w, H, l)
        ripples(c, w, H, lt)
        soft(w * 0.018f)
        p.color = alpha(c(0xFFF8E0), 225)
        c.drawOval(sx - w * 0.05f, lt - H * 0.01f, sx + w * 0.05f, lt + H * 0.17f, p)
        soft(w * 0.002f)
        p.color = alpha(c(0xFFFCEB), 210)
        val shimmer = Rnd(9)
        for (i in 0 until 18) {
            val y = lt + H * 0.005f + i * H * 0.0065f
            val half = w * (0.10f - i * 0.004f) * (0.5f + 0.7f * shimmer.next())
            c.drawRect(sx - half, y, sx + half, y + H * 0.002f, p)
        }
        // Left slope.
        val left = Path().apply {
            moveTo(0f, H * 0.60f)
            for (i in 0..40) {
                val t = i / 40f
                lineTo(w * 0.5f * t, H * (0.60f + 0.20f * t.pow(1.3f) - 0.01f * sin(i * 0.8f)))
            }
            lineTo(w * 0.5f, H); lineTo(0f, H); close()
        }
        slope(c, left, H * 0.58f, H * 0.92f, l)
        dabs(c, left, intArrayOf(l.hill, l.hill2, l.lit, l.treeLit), 200, w * 0.028f, 78, 95, w * 0.005f)
        forest(c, w, H, { t -> H * (0.60f + 0.20f * (t / 0.5f).pow(1.3f)) }, 0f, 0.32f, 26, 0.04f, 0.13f, l.tree, l.treeLit, 61, 240, w * 0.0015f)
        // Right slope with a sunlit crest.
        val right = Path().apply {
            for (i in 0..60) {
                val t = i / 60f
                val x = w * (0.42f + 0.58f * t)
                val y = H * (0.80f - 0.24f * smooth(t) - 0.006f * sin(t * 23f))
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            lineTo(w, H); lineTo(w * 0.42f, H); close()
        }
        slope(c, right, H * 0.55f, H * 0.95f, l)
        sunlit(c, right, l.lit, w * 0.74f, H * 0.60f, w * 0.42f, 0.55f, 1.7f)
        dabs(c, right, intArrayOf(l.hill, l.hill2, l.lit, l.treeLit), 260, w * 0.028f, 77, 95, w * 0.005f)
        forest(c, w, H, { t -> H * (0.80f - 0.24f * smooth((t - 0.42f) / 0.58f)) }, 0.52f, 1f, 60, 0.035f, 0.13f, l.tree, l.treeLit, 7, 240, w * 0.0015f)
        // Meadow in front, full width.
        val meadow = Path().apply {
            moveTo(0f, H * 0.86f)
            for (i in 0..40) {
                val t = i / 40f
                lineTo(w * t, H * (0.86f - 0.05f * sin(t * PI.toFloat() * 0.9f) - 0.006f * sin(i * 1.3f)))
            }
            lineTo(w, ground); lineTo(0f, ground); close()
        }
        soft(w * 0.003f)
        p.shader = LinearGradient(0f, H * 0.80f, 0f, ground, alpha(l.meadow[0], 245), alpha(l.meadow[1], 255), Shader.TileMode.CLAMP)
        c.drawPath(meadow, p)
        p.shader = null
        dabs(c, meadow, intArrayOf(l.meadow[0], l.meadow[1], l.lit, l.hill2), 320, w * 0.026f, 55, 95, w * 0.004f)
        if (l.road) road(c, w, H)
        grass(c, w, H, ground, l)
        flowers(c, w, H * 0.80f, ground, 320, 3)
        p.maskFilter = null
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt(),
    )

    /** A low wooded far shore across the lake, mirrored softly in the water. */
    private fun farShore(c: Canvas, w: Float, H: Float, l: Look) {
        val top = H * 0.632f
        val shore = Path().apply {
            moveTo(w * 0.08f, top + H * 0.012f)
            for (i in 0..40) {
                val t = i / 40f
                lineTo(w * (0.08f + 0.84f * t), top - H * 0.006f * sin(t * 9f) - H * 0.004f * sin(t * 23f))
            }
            lineTo(w * 0.92f, top + H * 0.012f)
            close()
        }
        soft(w * 0.0015f)
        p.color = alpha(l.mid, 235)
        c.drawPath(shore, p)
        forest(c, w, H, { t -> top + H * 0.004f - H * 0.006f * sin(((t - 0.08f) / 0.84f) * 9f) }, 0.10f, 0.90f, 70, 0.008f, 0.022f, l.midTree, null, 88, 225, w * 0.0018f)
        // Reflection: the same band, flipped and faint.
        c.save()
        c.scale(1f, -1f, 0f, top + H * 0.012f)
        soft(w * 0.006f)
        p.color = alpha(l.midTree, 90)
        c.drawPath(shore, p)
        c.restore()
        p.maskFilter = null
    }

    /** Light ripples across the water. */
    private fun ripples(c: Canvas, w: Float, H: Float, lt: Float) {
        val r = Rnd(19)
        soft(w * 0.0025f)
        for (i in 0 until 46) {
            val y = lt + H * 0.05f + (H * 0.25f) * r.next()
            val x = w * r.next()
            val half = w * (0.03f + 0.14f * r.next())
            p.color = alpha(Color.WHITE, (40 + 50 * r.next()).roundToInt())
            c.drawOval(x - half, y, x + half, y + H * 0.0022f, p)
        }
        p.maskFilter = null
    }

    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }

    private fun slope(c: Canvas, area: Path, top: Float, bottom: Float, l: Look) {
        soft(0f)
        p.shader = LinearGradient(0f, top, 0f, bottom, l.hill, l.hill2, Shader.TileMode.CLAMP)
        c.drawPath(area, p)
        p.shader = null
    }

    private fun noise(t: Float, seed: Float, rough: Float): Float =
        0.5f * sin(t * 5.1f + seed) + 0.28f * sin(t * 11.7f + seed * 1.9f) +
            rough * (0.12f * sin(t * 23.3f + seed * 2.7f) + 0.07f * abs(sin(t * 41f + seed)) + 0.03f * sin(t * 83f + seed * 3.1f))

    private fun ridgePath(into: Path, w: Float, H: Float, base: Float, amp: Float, seed: Float, rough: Float) {
        into.reset()
        into.moveTo(0f, H)
        for (i in 0..160) {
            val t = i / 160f
            into.lineTo(w * t, H * (base - amp * noise(t, seed, rough)))
        }
        into.lineTo(w, H)
        into.close()
    }

    /** Soft sunlight over [area]: a radial warm light around (cx, cy), flattened vertically by [squash]. */
    private fun sunlit(c: Canvas, area: Path, color: Int, cx: Float, cy: Float, radius: Float, strength: Float, squash: Float) {
        val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val colors = IntArray(stops.size) { alpha(color, (255 * strength * (1 - stops[it]).pow(1.4f)).roundToInt()) }
        val gradient = RadialGradient(cx, cy, radius, colors, stops, Shader.TileMode.CLAMP)
        gradient.setLocalMatrix(Matrix().apply { setScale(1f, 1f / squash, cx, cy) })
        p.maskFilter = null
        p.shader = gradient
        c.drawPath(area, p)
        p.shader = null
    }

    /** Soft light rays fanning out from the sun. */
    private fun rays(c: Canvas, w: Float, sx: Float, sy: Float) {
        val r = Rnd(17)
        path.reset()
        for (i in 0 until 14) {
            val a = Math.toRadians(-10.0 + 200.0 * i / 13 + 8 * r.next())
            val spread = Math.toRadians(1.2 + 1.8 * r.next())
            val len = w * (0.6f + 0.5f * r.next())
            path.moveTo(sx, sy)
            path.lineTo(sx + cos(a - spread).toFloat() * len, sy - sin(a - spread).toFloat() * len)
            path.lineTo(sx + cos(a + spread).toFloat() * len, sy - sin(a + spread).toFloat() * len)
            path.close()
        }
        val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val colors = IntArray(stops.size) { alpha(Color.WHITE, (150 * (1 - stops[it]).pow(1.3f)).roundToInt()) }
        soft(w * 0.004f)
        p.shader = RadialGradient(sx, sy, w * 0.55f, colors, stops, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        p.maskFilter = null
    }

    /**
     * A row of irregular spruces along y = [ground](t), t in [t0, t1]: far
     * ones small and light, near ones large; with [lit], the sun-side half
     * of each tree is lighter.
     */
    private fun forest(
        c: Canvas, w: Float, H: Float, ground: (Float) -> Float, t0: Float, t1: Float, n: Int,
        hMin: Float, hMax: Float, body: Int, lit: Int?, seed: Int, a: Int, blur: Float,
    ) {
        val r = Rnd(seed)
        val trees = (0 until n).map {
            val t = t0 + (t1 - t0) * r.next()
            val depth = r.next()
            val size = H * (hMin + (hMax - hMin) * depth * depth * (0.7f + 0.3f * r.next()))
            floatArrayOf(ground(t) + H * 0.035f * depth, w * t, size)
        }.sortedBy { it[0] }
        soft(blur)
        for ((y, x, size) in trees) {
            val (whole, half) = spruce(x, y, size, r)
            p.color = alpha(body, a)
            c.drawPath(whole, p)
            if (lit != null) {
                p.color = alpha(lit, (a * 0.85f).toInt())
                c.drawPath(half, p)
            }
        }
        p.maskFilter = null
    }

    /** A spruce silhouette with jittered branch tiers, and its sun-side half. */
    private fun spruce(x: Float, ground: Float, height: Float, r: Rnd): Pair<Path, Path> {
        val top = ground - height
        val right = ArrayList<Pair<Float, Float>>()
        val left = ArrayList<Pair<Float, Float>>()
        for (k in 1..6) {
            val f = k / 6f
            val y = top + height * (0.12f + 0.8f * f)
            val wr = height * (0.06f + 0.17f * f) * (0.8f + 0.4f * r.next())
            val wl = height * (0.06f + 0.17f * f) * (0.8f + 0.4f * r.next())
            right += (x + wr) to (y + height * 0.02f)
            right += (x + wr * 0.38f) to (y - height * 0.035f)
            left += (x - wl) to (y + height * 0.02f)
            left += (x - wl * 0.38f) to (y - height * 0.035f)
        }
        val whole = Path().apply {
            moveTo(x, top)
            right.forEach { lineTo(it.first, it.second) }
            lineTo(x + height * 0.03f, ground)
            lineTo(x - height * 0.03f, ground)
            left.asReversed().forEach { lineTo(it.first, it.second) }
            close()
        }
        val half = Path().apply {
            moveTo(x, top)
            right.forEach { lineTo(it.first, it.second) }
            lineTo(x + height * 0.03f, ground)
            lineTo(x, ground)
            close()
        }
        return whole to half
    }

    /** Brush texture: soft ovals of the given colours inside [area]. */
    private fun dabs(c: Canvas, area: Path, colors: IntArray, n: Int, size: Float, seed: Int, a: Int, blur: Float) {
        area.computeBounds(bounds, true)
        val r = Rnd(seed)
        c.save()
        c.clipPath(area)
        soft(blur)
        for (i in 0 until n) {
            val x = bounds.left + bounds.width() * r.next()
            val y = bounds.top + bounds.height() * r.next()
            val rw = size * (0.5f + r.next())
            val rh = rw * (0.35f + 0.3f * r.next())
            p.color = alpha(colors[(r.next() * colors.size).toInt() % colors.size], a)
            c.drawOval(x - rw, y - rh, x + rw, y + rh, p)
        }
        p.maskFilter = null
        c.restore()
    }

    /** A sandy road winding up the near hill (recovery: "the path goes on"). */
    private fun road(c: Canvas, w: Float, H: Float) {
        fun edge(width: Float, sign: Float) = (0..40).map { i ->
            val t = i / 40f
            val x = w * (0.90f - 0.30f * t + 0.06f * sin(t * 4.5f))
            val y = H * (1.02f - 0.42f * t)
            (x + sign * w * (width * (1 - t).pow(1.3f) + if (width > 0.05f) 0.005f else 0f)) to y
        }
        for ((width, colors) in listOf(0.085f to intArrayOf(c(0xE3CFA6), c(0xECDBB8)), 0.045f to intArrayOf(alpha(c(0xF4E9D1), 200), alpha(c(0xF6ECD6), 200)))) {
            val l = edge(width, -1f)
            val rr = edge(width, 1f)
            path.reset()
            path.moveTo(l[0].first, l[0].second)
            l.drop(1).forEach { path.lineTo(it.first, it.second) }
            rr.asReversed().forEach { path.lineTo(it.first, it.second) }
            path.close()
            soft(w * if (width > 0.05f) 0.0025f else 0.004f)
            p.shader = LinearGradient(0f, H * 0.6f, 0f, H, colors[0], colors[1], Shader.TileMode.CLAMP)
            c.drawPath(path, p)
            p.shader = null
        }
        p.maskFilter = null
    }

    /** Grass strokes over the meadow, longer in front. */
    private fun grass(c: Canvas, w: Float, H: Float, ground: Float, l: Look) {
        val r = Rnd(31)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.maskFilter = null
        val top = H * 0.82f
        for (i in 0 until 520) {
            val x = w * r.next()
            val v = r.next()
            val y = top + (ground - top) * v
            val len = H * (0.008f + 0.024f * v)
            p.color = alpha(if (r.next() > 0.45f) l.hill2 else l.lit, 160)
            p.strokeWidth = max(1f, w * 0.0025f * (0.5f + v))
            c.drawLine(x, y, x + w * 0.006f * (r.next() - 0.5f), y - len, p)
        }
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
    }

    /** White daisies with warm centres and a few buttercups, larger in front. */
    private fun flowers(c: Canvas, w: Float, top: Float, bottom: Float, count: Int, seed: Int) {
        val r = Rnd(seed)
        soft(w * 0.0012f)
        for (i in 0 until count) {
            val x = w * r.next()
            val v = r.next().pow(0.7f)
            val y = top + (bottom - top) * v
            val rr = w * (0.003f + 0.014f * v * v) * (0.7f + 0.6f * r.next())
            if (r.next() > 0.86f) {
                p.color = alpha(c(0xF2CF5E), 245)
                c.drawCircle(x, y, rr * 0.7f, p)
            } else {
                p.color = alpha(c(0xFFFDF7), 248)
                c.drawOval(x - rr, y - rr * 0.72f, x + rr, y + rr * 0.72f, p)
                p.color = alpha(c(0xE9B946), 252)
                c.drawOval(x - rr * 0.34f, y - rr * 0.3f, x + rr * 0.34f, y + rr * 0.3f, p)
            }
        }
        p.maskFilter = null
    }

    // ---------------- foliage frame, grain ----------------

    /** Leafy branches entering from the top-right corner; [lowY] places the small lower twig. */
    private fun leaves(c: Canvas, w: Float, lowY: Float) {
        branch(c, w, w * 1.06f, w * 0.06f, 200f, w * 0.22f, 4, 23, w * 0.07f, w * 0.012f, 190)
        branch(c, w, w * 1.04f, -w * 0.05f, 160f, w * 0.30f, 6, 11, w * 0.08f, w * 0.002f, 250)
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

    /** A fine, even grain over the whole scene: it takes the flat "vector" look off the gradients. */
    private fun grain(c: Canvas, w: Float, h: Float) {
        val tile = grainTile
        p.maskFilter = null
        p.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
    }

    private val grainTile: Bitmap by lazy {
        val size = 64
        val r = java.util.Random(7)
        val pixels = IntArray(size * size) {
            val n = (r.nextFloat() + r.nextFloat() + r.nextFloat()) / 3f - 0.5f
            val a = (kotlin.math.abs(n) * 2f * 16f).roundToInt().coerceIn(0, 255)
            if (n > 0) Color.argb(a, 255, 255, 255) else Color.argb(a, 0, 0, 0)
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
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

    /** A soft horizontal band (fog). */
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

    /** The left part of the sky is veiled down to [bottom], so the title on it reads well. */
    private fun titleVeil(c: Canvas, w: Float, bottom: Float, base: Int) {
        val layer = c.saveLayer(0f, 0f, w, bottom, null)
        p.maskFilter = null
        p.shader = LinearGradient(0f, 0f, w * 0.62f, 0f, intArrayOf(alpha(base, 205), alpha(base, 95), alpha(base, 0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w * 0.62f, bottom, p)
        p.shader = LinearGradient(0f, 0f, 0f, bottom, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawRect(0f, 0f, w, bottom, p)
        p.xfermode = null
        p.shader = null
        c.restoreToCount(layer)
    }

    /**
     * Light ivory veil behind the title and subtitle of a photo screen:
     * milky on the left where the text is, clear over the sun on the right,
     * fading out below [textBottom]; and a light band under the top bar.
     */
    private fun textVeil(c: Canvas, w: Float, textBottom: Float, fade: Float, base: Int, centered: Boolean = false) {
        val bottom = textBottom + fade
        val layer = c.saveLayer(0f, 0f, w, bottom, null)
        p.maskFilter = null
        p.shader = if (centered) {
            LinearGradient(
                0f, 0f, w, 0f, intArrayOf(alpha(base, 178), alpha(base, 222), alpha(base, 222), alpha(base, 178)),
                floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP,
            )
        } else {
            LinearGradient(
                0f, 0f, w, 0f, intArrayOf(alpha(base, 215), alpha(base, 175), alpha(base, 0), alpha(base, 0)),
                floatArrayOf(0f, 0.55f, 0.9f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w, bottom, p)
        p.shader = LinearGradient(0f, textBottom, 0f, bottom, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawRect(0f, 0f, w, bottom, p)
        p.xfermode = null
        p.shader = null
        c.restoreToCount(layer)
        gradientRect(c, 0f, 0f, w, w * 0.30f, alpha(base, 110), alpha(base, 0))
    }

    /**
     * A milky mist settling from [top] down to [bottom]: thin at first, then
     * [ground] (0–255) at the ground, which holds on down to [to].
     */
    private fun mist(c: Canvas, w: Float, top: Float, bottom: Float, ground: Int, to: Float = bottom) {
        p.maskFilter = null
        p.shader = LinearGradient(
            0f, top, 0f, bottom, intArrayOf(alpha(V2.Milk, 0), alpha(V2.Milk, ground * 31 / 100), alpha(V2.Milk, ground)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, top, w, maxOf(bottom, to), p)
        p.shader = null
    }

    /** The light of the scene being painted ([paint]). */
    private var mood = SceneMood.CALM

    /**
     * The grade of a photo in a [SceneMood]: calmer colours and a milky lift
     * of the page colour, so the sun no longer flares like a postcard and the
     * photo sits in the palette of the page (CALM: 78 % saturation, 14 % lift).
     */
    private val grades = SceneMood.entries.associateWith { m ->
        ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(m.saturation)
            val k = 1f - m.lift
            postConcat(ColorMatrix(floatArrayOf(
                k, 0f, 0f, 0f, m.lift * Color.red(V2.Milk),
                0f, k, 0f, 0f, m.lift * Color.green(V2.Milk),
                0f, 0f, k, 0f, m.lift * Color.blue(V2.Milk),
                0f, 0f, 0f, 1f, 0f,
            )))
        })
    }

    /** Where the sun is on the bundled production photo of [s] (fractions of its width and height). */
    private fun focus(s: Scenery): Pair<Float, Float> = when (s) {
        Scenery.DAWN -> 0.26f to 0.37f
        Scenery.MORNING -> 0.22f to 0.35f
        Scenery.DAY -> 0.76f to 0.365f
        Scenery.EVENING -> 0.78f to 0.33f
        Scenery.RECOVERY -> 0.16f to 0.29f
    }

    /**
     * A production photo covering the box ([left], [top], [bw] × [bh]),
     * [zoom] times closer, placed so that its sun lands at ([tx], [ty]) of
     * the box as far as the photo reaches, in the grade of its [SceneMood]. Scaled as a
     * whole, never stretched or redrawn.
     */
    private fun drawPhoto(c: Canvas, bitmap: Bitmap, scenery: Scenery, left: Float, top: Float, bw: Float, bh: Float, tx: Float, ty: Float, zoom: Float = 1f) {
        val (fx, fy) = focus(scenery)
        val scale = max(bw / bitmap.width, bh / bitmap.height) * zoom
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val x = min(left, max(left + bw - dw, left + tx * bw - fx * dw))
        val y = min(top, max(top + bh - dh, top + ty * bh - fy * dh))
        c.save()
        c.clipRect(left, top, left + bw, top + bh)
        p.maskFilter = null
        p.shader = null
        p.isFilterBitmap = true
        p.colorFilter = grades.getValue(mood)
        val source = reduced(bitmap, dw)
        c.drawBitmap(source, null, RectF(x, y, x + dw, y + dh), p)
        if (source !== bitmap) source.recycle()
        p.colorFilter = null
        c.restore()
    }

    /** Halves [bitmap] while it stays at least [width] wide: small cards get a clean downscale, not aliasing. */
    private fun reduced(bitmap: Bitmap, width: Float): Bitmap {
        var b = bitmap
        while (b.width / 2f >= width && b.width >= 4) {
            val half = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            if (b !== bitmap) b.recycle()
            b = half
        }
        return b
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
