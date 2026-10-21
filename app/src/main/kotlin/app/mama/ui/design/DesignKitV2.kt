package app.mama.ui.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mama.ui.BrandButtonView
import app.mama.ui.Icon

/**
 * The palette of MAMA Design System v2 (docs/MAMA_DESIGN_SYSTEM_V2.md), the
 * source of truth of the whole UI: nature, earth and MAMA as quiet support.
 * Milk, warm wash and sand; sage and olive; forest ink instead of black; clay
 * (MAMA Coral) only for the one main action. [Palette] names the roles.
 */
object V2 {
    val Milk = Color.rgb(0xFF, 0xF9, 0xF1)
    val WarmBg = Color.rgb(0xF8, 0xF0, 0xE6)
    val Coral = Color.rgb(0xC9, 0x6F, 0x62)
    val CoralLight = Color.rgb(0xE6, 0xA0, 0x97)
    val OliveDeep = Color.rgb(0x47, 0x53, 0x42)
    val Olive = Color.rgb(0x69, 0x74, 0x5E)
    val OliveLight = Color.rgb(0xDC, 0xE2, 0xD4)
    val Ink = Color.rgb(0x17, 0x30, 0x2E)
    val InkMuted = Color.rgb(0x53, 0x61, 0x5D)
    val Sand = Color.rgb(0xEB, 0xDC, 0xC9)

    /** Sage: the light end of the olive scale (arcs, marks, leaves). */
    val Sage = Color.rgb(0x8E, 0x9A, 0x80)

    /** Mist: a chosen card, the palest green. */
    val Mist = Color.rgb(0xEE, 0xF1, 0xE8)

    /** Pale sand: steps still ahead, quiet circles. */
    val SandLight = Color.rgb(0xF2, 0xE8, 0xDA)

    /** Warm earth: quote marks; its ink for text on sand. */
    val Earth = Color.rgb(0xA3, 0x8B, 0x6D)
    val EarthInk = Color.rgb(0x6E, 0x5B, 0x44)

    /** Pale clay and clay ink: something still needed ("Нужно"), never an alarm. */
    val ClaySoft = Color.rgb(0xF7, 0xE5, 0xDF)
    val ClayInk = Color.rgb(0xA9, 0x58, 0x4B)

    /** Hints, chevrons, inactive navigation. */
    val InkSoft = Color.rgb(0x8A, 0x95, 0x8F)

    /** Dividers and outlines. */
    val Line = Color.rgb(0xEF, 0xE5, 0xD7)

    /** Card surface on the milk page. */
    val Card = Color.rgb(0xFF, 0xFD, 0xF9)

    const val CARD_LARGE = 28
    const val CARD = 22
    const val BUTTON = 26
}

/**
 * What only the hero screens need on top of [DesignKit] (Welcome, the lock,
 * Recovery): the accent in a title, MAMA as a portrait, the [EarthPage]; the
 * rest are the shared components under the names those screens use.
 */
class DesignKitV2(val context: Context) {
    /** The shared components. */
    val k = DesignKit(context)

    fun dp(v: Int) = k.dp(v)

    fun text(value: CharSequence, style: Typo = Typo.BODY, color: Int = V2.Ink, center: Boolean = false): TextView = k.text(value, style, color, center)

    /** [text] with [accent] in MAMA Coral ("Добро пожаловать в MAMA"). */
    fun accented(text: String, accent: String): CharSequence = SpannableString(text).apply {
        val at = text.lastIndexOf(accent)
        if (at >= 0) setSpan(ForegroundColorSpan(V2.Coral), at, at + accent.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun shape(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Float = 1f) = k.shape(fill, radiusDp, stroke, strokeDp)

    /** The one main action of a screen: the clay pill, white label, "→". */
    fun primary(label: String, arrow: Boolean = true, onClick: () -> Unit): BrandButtonView = k.primaryButton(label, arrow, large = true, onClick = onClick)

    /** A second action that does not compete: a soft outline pill. */
    fun secondary(label: String, icon: Icon? = null, onClick: () -> Unit): BrandButtonView = k.secondaryButton(label, icon, onClick)

    /** A quiet text action ("Мой путь"). */
    fun link(label: String, onClick: () -> Unit): BrandButtonView = k.linkButton(label, onClick = onClick)

    /** A secondary action that stays at hand but never leads (the lock). */
    fun quietAction(icon: Icon, label: String, onClick: () -> Unit): BrandButtonView = k.quietAction(icon, label, onClick)

    /** A status pill, fully rounded: light olive, deep olive text ("Пройдено ночей: 4 из 7"). */
    fun pill(label: String, icon: Icon? = null): View = k.badge(label, icon)

    /** A soft card: [radiusDp] 20–28, a faint sand rim and a warm shadow. */
    fun card(paddingDp: Int = 20, radiusDp: Int = V2.CARD, fill: Int = V2.Card): LinearLayout = k.card(paddingDp, fill, radiusDp)

    /** An icon in a soft round bubble. */
    fun bubble(icon: Icon, tint: Int = V2.OliveDeep, fill: Int = V2.OliveLight, sizeDp: Int = 44): View = FrameLayout(context).apply {
        background = k.oval(fill)
        addView(k.icon(icon, tint, (sizeDp * 0.46f).toInt()), FrameLayout.LayoutParams(dp((sizeDp * 0.46f).toInt()), dp((sizeDp * 0.46f).toInt()), Gravity.CENTER))
        layoutParams = k.size(sizeDp)
    }

    /** A note of facts (what "Цена слова" came to, a free series later); [warm] for "Цена слова". */
    fun note(title: String?, message: CharSequence, icon: Icon, warm: Boolean = false): View =
        k.noteCard(message, title, if (warm) NoteTone.CARE else NoteTone.INFO, icon)

    /** The lock timer (see [DesignKit.timerRing]). */
    fun timerRing(sizeDp: Int): TimerRing = k.timerRing(sizeDp)

    /** MAMA as a round portrait (a crop of the approved art), with a soft coral halo. */
    fun portrait(asset: String, sizeDp: Int): View = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        private val matrix = Matrix()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f
            halo.shader = RadialGradient(cx, cy, r, intArrayOf(Color.argb(0x66, 0xE6, 0xA0, 0x97), Color.argb(0, 0xE6, 0xA0, 0x97)), floatArrayOf(0.72f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, halo)
            val inner = r * 0.84f
            canvas.drawCircle(cx, cy, inner + dp(3), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = V2.Card })
            val bitmap = art(context, asset) ?: return
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val s = 2 * inner / minOf(bitmap.width, bitmap.height)
            matrix.setScale(s, s)
            matrix.postTranslate(cx - bitmap.width * s / 2, cy - bitmap.height * s / 2)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            canvas.drawCircle(cx, cy, inner, paint)
        }
    }.apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    /** The page with the ground and the sprigs ([EarthPage]). */
    fun page(ground: Boolean = true): Drawable = EarthPage(context.resources.displayMetrics.density, ground)

    private companion object {
        private val cache = object : LruCache<String, Bitmap>(4 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }

        fun art(context: Context, asset: String): Bitmap? = cache.get(asset) ?: runCatching {
            context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.also { cache.put(asset, it) }
    }
}
