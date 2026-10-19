package app.mama.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/**
 * The MAMA icon set, drawn on a 24×24 grid: thin line icons, and for UI v1
 * a solid variant of the silhouette icons (person, heart, home, phone…), as
 * on the references.
 */
enum class Icon {
    BELL, PHONE, SHIELD, LAYERS, ACCESSIBILITY, ALARM, BATTERY, MESSAGE, LEAF, MOUNTAIN, INFINITY, FLASK,
    CALENDAR, CLOCK, INFO, PERSON, LOCK, MOON, GEAR, HOME, CHART, REFRESH, CHECK, CHEVRON_RIGHT, BACK,
    ALERT, HEART, ARROW_RIGHT, GLOBE, SUN,
    SPROUT, COINS, PEOPLE, STAR, SUNRISE,
}

/** Draws one [Icon] in [color], scaled to the drawable bounds; [solid] fills silhouette icons. */
class IconDrawable(
    private val icon: Icon,
    color: Int,
    private val strokeUnits: Float = 1.7f,
    private val solid: Boolean = false,
) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }

    /** Outline, or filled outline for [solid] silhouettes. */
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = if (solid) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val path = Path()
    private val oval = RectF()

    override fun draw(canvas: Canvas) {
        val s = minOf(bounds.width(), bounds.height()) / 24f
        canvas.save()
        canvas.translate(bounds.left + (bounds.width() - 24 * s) / 2, bounds.top + (bounds.height() - 24 * s) / 2)
        canvas.scale(s, s)
        stroke.strokeWidth = strokeUnits
        body.strokeWidth = strokeUnits
        drawIcon(canvas)
        canvas.restore()
    }

    private fun p(block: Path.() -> Unit, c: Canvas, paint: Paint = stroke) {
        path.reset()
        path.block()
        c.drawPath(path, paint)
    }

    private fun circle(c: Canvas, x: Float, y: Float, r: Float, paint: Paint = stroke) = c.drawCircle(x, y, r, paint)

    private fun rrect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float) {
        oval.set(l, t, r, b)
        c.drawRoundRect(oval, rad, rad, stroke)
    }

    private fun drawIcon(c: Canvas) = when (icon) {
        Icon.BELL -> {
            p({ moveTo(6f, 16.5f); lineTo(6f, 11f); cubicTo(6f, 7.5f, 8.6f, 5f, 12f, 5f); cubicTo(15.4f, 5f, 18f, 7.5f, 18f, 11f); lineTo(18f, 16.5f); lineTo(19.5f, 18f); lineTo(4.5f, 18f); close() }, c)
            p({ moveTo(10f, 20.5f); quadTo(12f, 22f, 14f, 20.5f) }, c)
            p({ moveTo(12f, 5f); lineTo(12f, 3.5f) }, c)
        }
        Icon.PHONE -> p(c = c, paint = body, block = {
            moveTo(6.5f, 3.5f); lineTo(9.2f, 3.5f); lineTo(10.6f, 7.6f); lineTo(8.6f, 9.4f)
            cubicTo(9.8f, 11.9f, 12.1f, 14.2f, 14.6f, 15.4f); lineTo(16.4f, 13.4f); lineTo(20.5f, 14.8f); lineTo(20.5f, 17.5f)
            cubicTo(20.5f, 19.2f, 19.2f, 20.5f, 17.5f, 20.5f); cubicTo(10.3f, 20.1f, 3.9f, 13.7f, 3.5f, 6.5f)
            cubicTo(3.5f, 4.8f, 4.8f, 3.5f, 6.5f, 3.5f); close()
        })
        Icon.SHIELD -> {
            p({ moveTo(12f, 3f); lineTo(19f, 5.8f); lineTo(19f, 11f); cubicTo(19f, 15.6f, 16f, 19.3f, 12f, 21f); cubicTo(8f, 19.3f, 5f, 15.6f, 5f, 11f); lineTo(5f, 5.8f); close() }, c)
            p({ moveTo(9f, 12f); lineTo(11.2f, 14.2f); lineTo(15.2f, 10f) }, c)
        }
        Icon.LAYERS -> {
            p({ moveTo(12f, 4f); lineTo(20.5f, 8.5f); lineTo(12f, 13f); lineTo(3.5f, 8.5f); close() }, c)
            p({ moveTo(3.5f, 12.5f); lineTo(12f, 17f); lineTo(20.5f, 12.5f) }, c)
            p({ moveTo(3.5f, 16.2f); lineTo(12f, 20.7f); lineTo(20.5f, 16.2f) }, c)
        }
        Icon.ACCESSIBILITY -> {
            circle(c, 12f, 12f, 9f)
            circle(c, 12f, 7.4f, 1.3f, fill)
            p({ moveTo(7.5f, 9.8f); lineTo(16.5f, 9.8f); moveTo(12f, 9.8f); lineTo(12f, 13.6f); lineTo(9.6f, 18f); moveTo(12f, 13.6f); lineTo(14.4f, 18f) }, c)
        }
        Icon.ALARM -> {
            circle(c, 12f, 13f, 7.5f)
            p({ moveTo(12f, 9f); lineTo(12f, 13f); lineTo(14.8f, 14.8f) }, c)
            p({ moveTo(3.8f, 6.5f); lineTo(6.8f, 3.8f); moveTo(20.2f, 6.5f); lineTo(17.2f, 3.8f) }, c)
        }
        Icon.BATTERY -> {
            rrect(c, 3.5f, 7.5f, 18.5f, 16.5f, 2.2f)
            p({ moveTo(21f, 10.5f); lineTo(21f, 13.5f) }, c)
            p({ moveTo(11.8f, 9.2f); lineTo(9.6f, 12.4f); lineTo(12.4f, 12.4f); lineTo(10.2f, 15f) }, c)
        }
        Icon.MESSAGE -> {
            p({ moveTo(5.5f, 4.5f); lineTo(18.5f, 4.5f); quadTo(20.5f, 4.5f, 20.5f, 6.5f); lineTo(20.5f, 14.5f); quadTo(20.5f, 16.5f, 18.5f, 16.5f); lineTo(10f, 16.5f); lineTo(5.5f, 20f); lineTo(5.5f, 16.5f); quadTo(3.5f, 16.5f, 3.5f, 14.5f); lineTo(3.5f, 6.5f); quadTo(3.5f, 4.5f, 5.5f, 4.5f); close() }, c)
            p({ moveTo(8f, 9f); lineTo(16f, 9f); moveTo(8f, 12.2f); lineTo(13.5f, 12.2f) }, c)
        }
        Icon.LEAF -> {
            p({ moveTo(5f, 19f); cubicTo(4.5f, 11f, 9.5f, 5f, 19.5f, 4.5f); cubicTo(19.5f, 14f, 14f, 19.5f, 5f, 19f); close() }, c)
            p({ moveTo(5f, 19f); cubicTo(9f, 14.5f, 12f, 11.5f, 15.5f, 8.5f) }, c)
        }
        Icon.MOUNTAIN -> {
            p({ moveTo(2.5f, 19f); lineTo(9f, 7f); lineTo(13f, 14f); lineTo(15.5f, 10.5f); lineTo(21.5f, 19f); close() }, c)
            p({ moveTo(7.2f, 10.3f); lineTo(9f, 11.6f); lineTo(10.6f, 9.8f) }, c)
        }
        Icon.INFINITY -> p({
            moveTo(12f, 12f); cubicTo(9.8f, 8.6f, 7.8f, 8f, 6.5f, 8f); cubicTo(4.2f, 8f, 2.8f, 9.8f, 2.8f, 12f); cubicTo(2.8f, 14.2f, 4.2f, 16f, 6.5f, 16f)
            cubicTo(7.8f, 16f, 9.8f, 15.4f, 12f, 12f); cubicTo(14.2f, 8.6f, 16.2f, 8f, 17.5f, 8f); cubicTo(19.8f, 8f, 21.2f, 9.8f, 21.2f, 12f)
            cubicTo(21.2f, 14.2f, 19.8f, 16f, 17.5f, 16f); cubicTo(16.2f, 16f, 14.2f, 15.4f, 12f, 12f); close()
        }, c)
        Icon.FLASK -> {
            p({ moveTo(9.5f, 3.5f); lineTo(14.5f, 3.5f); moveTo(10.2f, 3.5f); lineTo(10.2f, 9.2f); lineTo(4.8f, 18.2f); quadTo(4f, 20.5f, 6.5f, 20.5f); lineTo(17.5f, 20.5f); quadTo(20f, 20.5f, 19.2f, 18.2f); lineTo(13.8f, 9.2f); lineTo(13.8f, 3.5f) }, c)
            p({ moveTo(7.2f, 14.5f); lineTo(16.8f, 14.5f) }, c)
        }
        Icon.CALENDAR -> {
            rrect(c, 3.8f, 5.5f, 20.2f, 20.2f, 2.4f)
            p({ moveTo(3.8f, 10f); lineTo(20.2f, 10f); moveTo(8f, 3.5f); lineTo(8f, 7f); moveTo(16f, 3.5f); lineTo(16f, 7f) }, c)
            circle(c, 8.5f, 14f, 0.9f, fill); circle(c, 12f, 14f, 0.9f, fill); circle(c, 15.5f, 14f, 0.9f, fill)
        }
        Icon.CLOCK -> {
            circle(c, 12f, 12f, 8.6f)
            p({ moveTo(12f, 7.4f); lineTo(12f, 12f); lineTo(15.2f, 13.9f) }, c)
        }
        Icon.INFO -> {
            circle(c, 12f, 12f, 8.6f)
            p({ moveTo(12f, 11f); lineTo(12f, 16.4f) }, c)
            circle(c, 12f, 7.9f, 1.05f, fill)
        }
        Icon.PERSON -> {
            circle(c, 12f, 8.3f, 3.8f, body)
            p({ moveTo(4.6f, 20.2f); cubicTo(5.4f, 16.2f, 8.4f, 14.2f, 12f, 14.2f); cubicTo(15.6f, 14.2f, 18.6f, 16.2f, 19.4f, 20.2f); if (solid) close() }, c, body)
        }
        Icon.LOCK -> {
            rrect(c, 5f, 10.5f, 19f, 20.5f, 2.4f)
            p({ moveTo(8f, 10.5f); lineTo(8f, 7.8f); cubicTo(8f, 5.3f, 9.8f, 3.5f, 12f, 3.5f); cubicTo(14.2f, 3.5f, 16f, 5.3f, 16f, 7.8f); lineTo(16f, 10.5f) }, c)
            p({ moveTo(12f, 14.5f); lineTo(12f, 16.5f) }, c)
        }
        Icon.MOON -> p(c = c, paint = body, block = { moveTo(19.5f, 14.2f); cubicTo(18.2f, 18.2f, 13.6f, 20.6f, 9.4f, 19f); cubicTo(5.2f, 17.4f, 3.4f, 12.6f, 5f, 8.6f); cubicTo(5.8f, 6.6f, 7.4f, 5f, 9.4f, 4.4f); cubicTo(7.8f, 8.6f, 9.8f, 13.4f, 14f, 14.8f); cubicTo(15.8f, 15.4f, 17.8f, 15.2f, 19.5f, 14.2f); close() })
        Icon.GEAR -> {
            circle(c, 12f, 12f, 3f)
            p({
                val teeth = 8
                for (i in 0 until teeth * 2) {
                    val a = Math.PI * i / teeth - Math.PI / 2
                    val r = if (i % 2 == 0) 8.6f else 6.7f
                    val a1 = a - Math.PI / (teeth * 2.6)
                    val a2 = a + Math.PI / (teeth * 2.6)
                    val x1 = 12f + r * cos(a1).toFloat(); val y1 = 12f + r * sin(a1).toFloat()
                    val x2 = 12f + r * cos(a2).toFloat(); val y2 = 12f + r * sin(a2).toFloat()
                    if (i == 0) moveTo(x1, y1) else lineTo(x1, y1)
                    lineTo(x2, y2)
                }
                close()
            }, c)
        }
        Icon.HOME -> if (solid) {
            p({
                moveTo(12f, 3.6f); lineTo(20.4f, 10.8f); lineTo(18.6f, 10.8f); lineTo(18.6f, 20f); lineTo(14.4f, 20f); lineTo(14.4f, 14.6f)
                lineTo(9.6f, 14.6f); lineTo(9.6f, 20f); lineTo(5.4f, 20f); lineTo(5.4f, 10.8f); lineTo(3.6f, 10.8f); close()
            }, c, body)
        } else {
            p({ moveTo(3.8f, 11f); lineTo(12f, 4f); lineTo(20.2f, 11f) }, c)
            p({ moveTo(6f, 9.4f); lineTo(6f, 20f); lineTo(18f, 20f); lineTo(18f, 9.4f) }, c)
            p({ moveTo(10f, 20f); lineTo(10f, 14.5f); lineTo(14f, 14.5f); lineTo(14f, 20f) }, c)
        }
        Icon.CHART -> {
            p({ moveTo(4f, 20f); lineTo(20f, 20f) }, c)
            p({ moveTo(7f, 16.5f); lineTo(7f, 12.5f); moveTo(12f, 16.5f); lineTo(12f, 7f); moveTo(17f, 16.5f); lineTo(17f, 10f) }, c)
        }
        Icon.REFRESH -> {
            p({ arcTo(RectF(4.5f, 4.5f, 19.5f, 19.5f), -60f, 300f) }, c)
            p({ moveTo(16.2f, 3.4f); lineTo(16.3f, 6.9f); lineTo(12.8f, 7.2f) }, c)
        }
        Icon.CHECK -> p({ moveTo(5.5f, 12.5f); lineTo(10f, 17f); lineTo(18.5f, 7.5f) }, c)
        Icon.CHEVRON_RIGHT -> p({ moveTo(9.5f, 5.5f); lineTo(16f, 12f); lineTo(9.5f, 18.5f) }, c)
        Icon.BACK -> p({ moveTo(14.5f, 5.5f); lineTo(8f, 12f); lineTo(14.5f, 18.5f) }, c)
        Icon.ALERT -> {
            p({ moveTo(12f, 7f); lineTo(12f, 13.5f) }, c)
            circle(c, 12f, 17f, 1.1f, fill)
        }
        Icon.HEART -> p(c = c, paint = body, block = { moveTo(12f, 19.5f); cubicTo(5.5f, 15.2f, 3.5f, 12f, 3.5f, 8.9f); cubicTo(3.5f, 6.4f, 5.4f, 4.5f, 7.8f, 4.5f); cubicTo(9.6f, 4.5f, 11f, 5.5f, 12f, 7f); cubicTo(13f, 5.5f, 14.4f, 4.5f, 16.2f, 4.5f); cubicTo(18.6f, 4.5f, 20.5f, 6.4f, 20.5f, 8.9f); cubicTo(20.5f, 12f, 18.5f, 15.2f, 12f, 19.5f); close() })
        Icon.ARROW_RIGHT -> p({ moveTo(4.5f, 12f); lineTo(19f, 12f); moveTo(13.5f, 6.5f); lineTo(19f, 12f); lineTo(13.5f, 17.5f) }, c)
        Icon.GLOBE -> {
            circle(c, 12f, 12f, 8.6f)
            p({ moveTo(3.4f, 12f); lineTo(20.6f, 12f) }, c)
            oval.set(8.2f, 3.4f, 15.8f, 20.6f); c.drawOval(oval, stroke)
        }
        Icon.SUN -> {
            circle(c, 12f, 12f, 4f)
            p({
                for (i in 0 until 8) {
                    val a = Math.PI * i / 4
                    moveTo(12f + 6.6f * cos(a).toFloat(), 12f + 6.6f * sin(a).toFloat())
                    lineTo(12f + 8.8f * cos(a).toFloat(), 12f + 8.8f * sin(a).toFloat())
                }
            }, c)
        }
        Icon.SPROUT -> {
            p({ moveTo(12f, 21f); lineTo(12f, 11.5f) }, c)
            p({ moveTo(12f, 12.5f); cubicTo(11.6f, 8.2f, 8.6f, 5.6f, 3.8f, 5.8f); cubicTo(3.8f, 10.4f, 7.2f, 13.2f, 12f, 12.5f); close() }, c, body)
            p({ moveTo(12f, 11.2f); cubicTo(12.6f, 7.2f, 15.6f, 4.6f, 20.2f, 4.8f); cubicTo(20.2f, 9.2f, 16.8f, 11.8f, 12f, 11.2f); close() }, c, body)
        }
        Icon.COINS -> {
            // Three stacked coins, the bottom one first; solid coins are parted by a thin cleared ring.
            val layer = c.saveLayer(0f, 0f, 24f, 24f, null)
            for (i in 2 downTo 0) {
                val top = 4.5f + i * 4.8f
                oval.set(4.5f, top, 19.5f, top + 5.6f)
                if (solid) {
                    c.drawOval(oval, clear)
                    c.drawOval(oval, fill)
                } else {
                    c.drawOval(oval, stroke)
                }
            }
            c.restoreToCount(layer)
        }
        Icon.PEOPLE -> {
            circle(c, 9f, 8.2f, 3.2f, body)
            p({ moveTo(2.8f, 19.6f); cubicTo(3.4f, 15.9f, 5.9f, 13.9f, 9f, 13.9f); cubicTo(12.1f, 13.9f, 14.6f, 15.9f, 15.2f, 19.6f); if (solid) close() }, c, body)
            circle(c, 16.6f, 8.8f, 2.6f, body)
            p({ moveTo(16.4f, 13.6f); cubicTo(19f, 13.6f, 20.9f, 15.4f, 21.3f, 18.6f) }, c)
        }
        Icon.STAR -> p(c = c, paint = body, block = {
            for (i in 0 until 10) {
                val a = -Math.PI / 2 + Math.PI * i / 5
                val r = if (i % 2 == 0) 9f else 4.1f
                val x = 12f + r * cos(a).toFloat()
                val y = 12.6f + r * sin(a).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        })
        Icon.SUNRISE -> {
            p({ arcTo(RectF(6.5f, 9.5f, 17.5f, 20.5f), 180f, 180f); close() }, c, body)
            p({ moveTo(3f, 15.5f); lineTo(21f, 15.5f) }, c)
            p({
                for (i in 0 until 5) {
                    val a = Math.PI + Math.PI * (i + 1) / 6
                    moveTo(12f + 7.8f * cos(a).toFloat(), 15f + 7.8f * sin(a).toFloat())
                    lineTo(12f + 9.8f * cos(a).toFloat(), 15f + 9.8f * sin(a).toFloat())
                }
            }, c)
        }
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
        body.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
        body.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
