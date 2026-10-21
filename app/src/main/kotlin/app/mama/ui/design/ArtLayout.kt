package app.mama.ui.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A screen drawn over one approved piece of art, exactly as it was designed
 * (the welcome): the art covers the screen — as high as the screen and
 * centred across it, or, on a screen wider than the art, as wide as the screen
 * and anchored to its bottom — and the real views sit where the art leaves
 * room for them. Each child is placed in a rectangle given in the art's own
 * pixels, and text set with [text] keeps its size relative to the art. If the
 * lowest child would fall under the navigation bar, everything moves up.
 */
class ArtLayout(
    context: Context,
    asset: String,
    private val artWidth: Int,
    private val artHeight: Int,
) : ViewGroup(context) {
    private class Place(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val center: Boolean)

    private val places = HashMap<View, Place>()
    private val textSizes = HashMap<TextView, Float>()
    private var scale = 1f
    private var artLeft = 0f
    private var artTop = 0f

    /** The art itself, the first child, covering the whole layout. */
    val art: View = ArtView(context, asset).also { addView(it) }

    /** Places [view] in the art's rectangle ([x0], [y0])–([x1], [y1]); [center]: vertically centred in it, else at its top. */
    fun place(view: View, x0: Int, y0: Int, x1: Int, y1: Int, center: Boolean = true): ArtLayout = apply {
        places[view] = Place(x0, y0, x1, y1, center)
        addView(view)
    }

    /** [view]'s text is [artPx] pixels of the art high, whatever the screen. */
    fun text(view: TextView, artPx: Float): TextView = view.also { textSizes[it] = artPx }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        if (w == 0 || h == 0) return
        scale = max(w / artWidth.toFloat(), h / artHeight.toFloat())
        artLeft = (w - artWidth * scale) / 2f
        artTop = if (w / artWidth.toFloat() > h / artHeight.toFloat()) h - artHeight * scale else 0f
        // Keep the lowest view above the navigation bar.
        val lowest = places.values.maxOfOrNull { it.y1 } ?: 0
        val over = artTop + lowest * scale - (h - paddingBottom)
        if (over > 0) artTop -= over
        for ((view, px) in textSizes) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px * scale)
        art.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        (art as ArtView).frame(artLeft, artTop, artWidth * scale, artHeight * scale)
        for ((view, p) in places) {
            val pw = ((p.x1 - p.x0) * scale).roundToInt()
            val ph = ((p.y1 - p.y0) * scale).roundToInt()
            view.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ph, MeasureSpec.AT_MOST))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        art.layout(0, 0, r - l, b - t)
        for ((view, p) in places) {
            val x = (artLeft + p.x0 * scale).roundToInt()
            val boxTop = artTop + p.y0 * scale
            val boxHeight = (p.y1 - p.y0) * scale
            val y = (if (p.center) boxTop + (boxHeight - view.measuredHeight) / 2f else boxTop).roundToInt()
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
        }
    }

    /** Draws the art into the frame the layout gives it. */
    private class ArtView(context: Context, private val asset: String) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val dst = RectF()

        fun frame(left: Float, top: Float, width: Float, height: Float) {
            dst.set(left, top, left + width, top + height)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val bitmap = load(context, asset) ?: return
            canvas.drawBitmap(bitmap, null, dst, paint)
        }
    }

    private companion object {
        private val cache = object : LruCache<String, Bitmap>(12 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }

        fun load(context: Context, asset: String): Bitmap? = cache.get(asset) ?: runCatching {
            context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.also { cache.put(asset, it) }
    }
}
