package app.mama.ui.design

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View

/**
 * Light lock-timer ring (reference 16-lock): a pale mint track and an
 * emerald arc that grows from the top as the lock goes on. The cream disc
 * behind it is the container's background ([DesignKit.timerRing]).
 */
class TimerRingView(context: Context) : View(context) {
    /** 0 at the start of the lock, 1 at its end. */
    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private val stroke = 9 * density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = Palette.RingTrack
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()
    private val rotate = Matrix()

    override fun onDraw(canvas: Canvas) {
        val inset = stroke / 2 + density
        box.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(box, track)
        if (progress > 0f) {
            val sweep = SweepGradient(width / 2f, height / 2f, intArrayOf(Palette.RingArcLight, Palette.RingArc, Palette.RingArc), floatArrayOf(0f, 0.6f, 1f))
            // Start the gradient at 12 o'clock, where the arc starts.
            rotate.setRotate(-90f, width / 2f, height / 2f)
            sweep.setLocalMatrix(rotate)
            arc.shader = sweep
            canvas.drawArc(box, -90f, 360f * progress, false, arc)
        }
    }
}
