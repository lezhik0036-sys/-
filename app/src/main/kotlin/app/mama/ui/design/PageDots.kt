package app.mama.ui.design

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * Page dots of an intro (the welcome and the onboarding): the current page a
 * larger moss dot, the others small beige ones, spread evenly across the view.
 */
class PageDots(context: Context, private val count: Int, private val current: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        setMeasuredDimension(
            resolveSize((count * 18 * density).toInt(), widthMeasureSpec),
            resolveSize((12 * density).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val step = width / count.toFloat()
        val r = minOf(height / 2f, step * 0.24f)
        for (i in 0 until count) {
            val on = i == current
            paint.color = if (on) V2.MossDeep else V2.Sand
            canvas.drawCircle(step * (i + 0.5f), height / 2f, if (on) r else r * 0.86f, paint)
        }
    }
}
