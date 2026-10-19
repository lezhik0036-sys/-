package app.mama.ui.design

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import app.mama.ui.BrandButtonView
import app.mama.ui.Icon

/** Handle of a shown [MamaSheet]. */
class SheetHandle(private val dialog: Dialog, val primary: BrandButtonView?) {
    fun dismiss() = dialog.dismiss()
    fun setPrimaryEnabled(enabled: Boolean) {
        primary?.isEnabled = enabled
    }
}

/**
 * UI v1 dialogs: a bottom sheet (default) or a centred dialog. Ivory
 * surface, large radius, optional icon circle, title, message, custom
 * content, the main action as the emerald pill and a quiet second action
 * under it. No system AlertDialogs.
 */
object MamaSheet {
    enum class Style { BOTTOM, CENTER }

    /** [onPrimary] returns true to close, false to keep the sheet open (invalid input). */
    fun show(
        context: Context,
        title: String,
        message: CharSequence? = null,
        content: View? = null,
        icon: Icon? = null,
        iconStyle: CircleStyle = CircleStyle.MINT,
        primary: String? = "Понятно",
        secondary: String? = null,
        style: Style = Style.BOTTOM,
        onSecondary: () -> Unit = {},
        onPrimary: () -> Boolean = { true },
    ): SheetHandle {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var primaryButton: BrandButtonView? = null
        val panel = panel(context, title, message, content, icon, iconStyle, style,
            primary?.let { label -> { kit: DesignKit -> kit.primaryButton(label, arrow = false) { if (onPrimary()) dialog.dismiss() }.also { primaryButton = it } } },
            secondary?.let { label -> { kit: DesignKit -> kit.linkButton(label) { dialog.dismiss(); onSecondary() } } },
        )
        val kit = DesignKit(context)
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(panel)
        }
        val frame = FrameLayout(context).apply {
            if (style == Style.CENTER) setPadding(kit.dp(20), kit.dp(24), kit.dp(20), kit.dp(24))
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (style == Style.BOTTOM) Gravity.BOTTOM else Gravity.CENTER))
        }
        dialog.setContentView(frame)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setDimAmount(0.42f)
            @Suppress("DEPRECATION")
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (style == Style.BOTTOM) {
                w.setGravity(Gravity.BOTTOM)
                w.setWindowAnimations(android.R.style.Animation_InputMethod)
            }
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return SheetHandle(dialog, primaryButton)
    }

    /** The sheet surface itself (also used inline by the design showcase). */
    fun panel(
        context: Context,
        title: String,
        message: CharSequence? = null,
        content: View? = null,
        icon: Icon? = null,
        iconStyle: CircleStyle = CircleStyle.MINT,
        style: Style = Style.BOTTOM,
        primary: ((DesignKit) -> View)? = null,
        secondary: ((DesignKit) -> View)? = null,
    ): LinearLayout {
        val kit = DesignKit(context)
        val r = kit.dpf(Radii.SHEET.toFloat())
        return kit.column().apply {
            background = GradientDrawable().apply {
                setColor(Palette.Bg)
                cornerRadii = if (style == Style.BOTTOM) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else FloatArray(8) { r }
            }
            setPadding(kit.dp(22), kit.dp(if (style == Style.BOTTOM) 12 else 24), kit.dp(22), kit.dp(if (style == Style.BOTTOM) 22 else 18))
            if (style == Style.BOTTOM) {
                addView(View(context).apply { background = kit.shape(Palette.Line, 3) }, LinearLayout.LayoutParams(kit.dp(40), kit.dp(5)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = kit.dp(16)
                })
            }
            icon?.let { addView(kit.iconCircle(it, 52, iconStyle), kit.size(52).apply { bottomMargin = kit.dp(14) }) }
            addView(kit.text(title, Typo.HEADLINE), kit.fill())
            message?.let { addView(kit.text(it, Typo.BODY, Palette.InkMuted), kit.gap(10)) }
            content?.let { addView(it, kit.gap(18)) }
            primary?.let { addView(it(kit), kit.gap(24)) }
            secondary?.let { addView(it(kit), kit.gap(if (primary != null) 6 else 20)) }
        }
    }
}
