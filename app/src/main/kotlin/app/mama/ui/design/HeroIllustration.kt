package app.mama.ui.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An approved raster illustration on top of a screen (the welcome of MAMA),
 * drawn as it is, never redrawn: as wide as the screen, from its top edge,
 * and as much of its height as the room allows between [minShown] and
 * [maxShown] (rows of the image), the bottom fading into the [page]. On a
 * screen wider than [maxWidthDp] it is centred and its sides fade too, so
 * the people at its edges are never cut. Text and buttons stay real views
 * around it. In a vertical LinearLayout give it height 0 and weight 1: it
 * takes the room the text leaves, never less than [minShown].
 */
class HeroIllustration(
    context: Context,
    private val asset: String,
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val minShown: Int,
    private val maxShown: Int,
    private val page: Int,
    private val maxWidthDp: Int = 560,
) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = RectF()

    init {
        // Decorative: the text under it says what the screen is about.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Width the image is drawn at in a view [w] wide. */
    private fun imageWidthIn(w: Int) = min(w, (maxWidthDp * density).roundToInt())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val scale = imageWidthIn(w).toFloat() / imageWidth
        val min = (minShown * scale).roundToInt()
        val max = (maxShown * scale).roundToInt()
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec).coerceIn(min, max)
            else -> min
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = load(context, asset) ?: return
        val iw = imageWidthIn(width)
        val left = (width - iw) / 2f
        val scale = iw.toFloat() / imageWidth
        val shown = min(height / scale, imageHeight.toFloat())
        // The decoded bitmap may be smaller than the image (memory); map rows in its own pixels.
        val k = bitmap.width.toFloat() / imageWidth
        src.set(0, 0, bitmap.width, (shown * k).roundToInt().coerceAtMost(bitmap.height))
        dst.set(left, 0f, left + iw, shown * scale)
        canvas.drawBitmap(bitmap, src, dst, paint)

        val edge = 44 * density
        fade.shader = LinearGradient(0f, dst.bottom - edge, 0f, dst.bottom, transparent(page), page, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, dst.bottom - edge, width.toFloat(), dst.bottom, fade)
        if (left > 0f) {
            val side = 28 * density
            fade.shader = LinearGradient(left, 0f, left + side, 0f, page, transparent(page), Shader.TileMode.CLAMP)
            canvas.drawRect(left, 0f, left + side, dst.bottom, fade)
            fade.shader = LinearGradient(dst.right - side, 0f, dst.right, 0f, transparent(page), page, Shader.TileMode.CLAMP)
            canvas.drawRect(dst.right - side, 0f, dst.right, dst.bottom, fade)
        }
        fade.shader = null
    }

    private fun transparent(color: Int) = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }

        fun load(context: Context, asset: String): Bitmap? = cache.get(asset) ?: runCatching {
            context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.also { cache.put(asset, it) }
    }
}
