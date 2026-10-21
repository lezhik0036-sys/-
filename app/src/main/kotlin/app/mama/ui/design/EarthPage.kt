package app.mama.ui.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The page of every screen (MAMA_DESIGN_SYSTEM_V2 «Страница»): cream that
 * warms towards the bottom, with a painted leaf sprig (cut from the approved
 * welcome art) growing from the bottom-left corner, as on the approved
 * screens; the top stays clear for the bar and the title. [quiet]: the
 * stillest page — cream only.
 */
class EarthPage(
    context: Context,
    private val leaves: Boolean = true,
    private val quiet: Boolean = false,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val sprig = EarthPainter.sprig(context)

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        EarthPainter.paint(canvas, b.width().toFloat(), b.height().toFloat(), density, sprig.takeIf { leaves && !quiet })
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** Paints the [EarthPage] (also under the photo of a scenic screen). */
internal object EarthPainter {
    private const val SPRIG = "art/leaf_sprig.webp"
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()
    private var sprigBitmap: Bitmap? = null

    /** The painted leaf sprig (a stem from its bottom-right corner, leaves up and to the left), loaded once. */
    @Synchronized
    fun sprig(context: Context): Bitmap? = sprigBitmap ?: runCatching {
        context.assets.open(SPRIG).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()?.also { sprigBitmap = it }

    @Synchronized
    fun paint(canvas: Canvas, w: Float, h: Float, density: Float, sprig: Bitmap?) {
        p.reset()
        p.isAntiAlias = true
        p.isFilterBitmap = true
        p.shader = LinearGradient(0f, 0f, 0f, h, V2.Milk, V2.WarmBg, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null
        if (sprig == null) return
        val sw = 150 * density
        val sh = sw * sprig.height / sprig.width
        // Bottom-left: mirrored, so its stem grows from the corner and its leaves reach up and in.
        canvas.save()
        canvas.translate(-18 * density, h - sh + 10 * density)
        canvas.scale(-1f, 1f, sw / 2, sh / 2)
        box.set(0f, 0f, sw, sh)
        p.alpha = 0xE6
        canvas.drawBitmap(sprig, null, box, p)
        canvas.restore()
        p.alpha = 0xFF
    }
}
