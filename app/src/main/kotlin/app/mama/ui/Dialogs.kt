package app.mama.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalTime

/** Handle of a shown [BrandDialog]. */
class BrandDialogHandle(private val dialog: Dialog, val confirm: BrandButtonView?) {
    fun dismiss() = dialog.dismiss()
    fun setConfirmEnabled(enabled: Boolean) {
        confirm?.isEnabled = enabled
    }
}

/**
 * MAMA's own dialog: warm ivory surface, large rounded corners, emerald
 * actions. Replaces system AlertDialogs so nothing looks like default Android.
 */
object BrandDialog {

    /**
     * Shows a dialog. [onConfirm] returns true to close the dialog, false to
     * keep it open (for example when the input is invalid).
     */
    fun show(
        context: Context,
        title: String,
        message: CharSequence? = null,
        content: View? = null,
        confirm: String? = "Понятно",
        cancel: String? = null,
        confirmKind: ButtonKind = ButtonKind.PRIMARY,
        cancelable: Boolean = true,
        onCancel: () -> Unit = {},
        onConfirm: () -> Boolean = { true },
    ): BrandDialogHandle {
        val kit = Kit(context)
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(cancelable)

        val sheet = kit.column().apply {
            background = kit.shape(MamaColors.SurfaceCard, 30)
            setPadding(kit.dp(24), kit.dp(24), kit.dp(24), kit.dp(18))
        }
        sheet.addView(kit.text(title, MamaType.H2))
        message?.let { sheet.addView(kit.body(it), kit.gap(10)) }
        content?.let { sheet.addView(it, kit.gap(16)) }

        var confirmButton: BrandButtonView? = null
        if (confirm != null || cancel != null) {
            val actions = kit.row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            cancel?.let {
                actions.addView(
                    kit.brandButton(it, ButtonKind.GHOST) { dialog.dismiss(); onCancel() },
                    if (confirm != null) kit.weight() else kit.wrap(),
                )
            }
            confirm?.let {
                val b = kit.brandButton(it, confirmKind) { if (onConfirm()) dialog.dismiss() }
                actions.addView(b, kit.weight().apply { if (cancel != null) leftMargin = kit.dp(10) })
                confirmButton = b
            }
            sheet.addView(actions, kit.gap(20))
        }

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(sheet)
        }
        val frame = FrameLayout(context).apply {
            setPadding(kit.dp(18), kit.dp(24), kit.dp(18), kit.dp(24))
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        dialog.setContentView(frame)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setDimAmount(0.35f)
            @Suppress("DEPRECATION")
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return BrandDialogHandle(dialog, confirmButton)
    }

    /** A list of choices in a branded dialog; picking one closes it. */
    fun list(
        context: Context,
        title: String,
        items: List<Pair<String, String?>>,
        selected: Int,
        onPick: (Int) -> Unit,
    ) {
        val kit = Kit(context)
        val rows = kit.column()
        lateinit var handle: BrandDialogHandle
        items.forEachIndexed { i, (main, side) ->
            val on = i == selected
            val r = kit.row().apply {
                minimumHeight = kit.dp(52)
                setPadding(kit.dp(16), kit.dp(10), kit.dp(16), kit.dp(10))
                background = kit.pressable(
                    kit.shape(if (on) MamaColors.EmeraldTint else MamaColors.SurfaceCard, 18, if (on) MamaColors.EmeraldPrimary else MamaColors.BorderSoft),
                    18,
                )
                isClickable = true
                setOnClickListener { handle.dismiss(); onPick(i) }
            }
            r.addView(kit.text(main, MamaType.BODY, MamaColors.TextPrimary), kit.weight())
            side?.let { r.addView(kit.text(it, MamaType.BODY, if (on) MamaColors.EmeraldPrimary else MamaColors.TextSecondary)) }
            rows.addView(r, kit.gap(if (i == 0) 0 else 8))
        }
        val content: View = if (items.size > 6) {
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(rows)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, kit.dp(380))
            }
        } else {
            rows
        }
        handle = show(context, title, content = content, confirm = null, cancel = "Отмена")
        if (items.size > 6 && selected in items.indices) {
            (content as ScrollView).post { content.scrollTo(0, rows.getChildAt(selected).top - kit.dp(120)) }
        }
    }
}

/**
 * Digital 24-hour time picker: [ HH ] : [ mm ], typed or stepped with
 * arrows. There is no analog clock face anywhere in MAMA.
 */
object DigitalTimePicker {

    fun show(context: Context, title: String = "Выберите время", initial: LocalTime, onPicked: (LocalTime) -> Unit) {
        val kit = Kit(context)
        val hours = digitField(kit, initial.hour)
        val minutes = digitField(kit, initial.minute)
        val error = kit.text("", MamaType.CAPTION, MamaColors.DangerInk, center = true).apply { visibility = View.GONE }

        val line = kit.row().apply { gravity = Gravity.CENTER }
        line.addView(unit(kit, "Часы", hours, 24))
        line.addView(
            kit.text(":", MamaType.DIGITS_L, MamaColors.EmeraldSecondary).apply { setPadding(kit.dp(10), 0, kit.dp(10), 0) },
        )
        line.addView(unit(kit, "Минуты", minutes, 60))

        val quick = kit.chips(listOf(":00", ":15", ":30", ":45"), selected = -1) { i ->
            minutes.setText("%02d".format(i * 15))
        }
        val content = kit.column().apply {
            addView(line, kit.fill())
            addView(quick, kit.wrap().apply { topMargin = kit.dp(16); gravity = Gravity.CENTER_HORIZONTAL })
            addView(error, kit.gap(12))
            // Keeps the keyboard closed until a number is tapped.
            isFocusableInTouchMode = true
        }
        BrandDialog.show(context, title, content = content, confirm = "Готово", cancel = "Отмена") {
            val h = hours.text.toString().toIntOrNull()
            val m = minutes.text.toString().toIntOrNull()
            val problem = when {
                h == null || h !in 0..23 -> "Часы — от 00 до 23."
                m == null || m !in 0..59 -> "Минуты — от 00 до 59."
                else -> null
            }
            if (problem != null) {
                error.text = problem
                error.visibility = View.VISIBLE
                false
            } else {
                onPicked(LocalTime.of(h!!, m!!))
                true
            }
        }
        content.requestFocus()
    }

    private fun digitField(kit: Kit, value: Int): EditText = EditText(kit.context).apply {
        setText("%02d".format(value))
        MamaType.DIGITS_L.applyTo(this)
        setTextColor(MamaColors.TextPrimary)
        gravity = Gravity.CENTER
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(2))
        setSelectAllOnFocus(true)
        background = kit.fieldBackground()
        setPadding(0, kit.dp(10), 0, kit.dp(10))
    }

    /** Arrow, digits, arrow, caption. Holding an arrow keeps stepping. */
    private fun unit(kit: Kit, label: String, field: EditText, modulo: Int): View = kit.column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        fun step(delta: Int) {
            val current = field.text.toString().toIntOrNull()?.coerceIn(0, modulo - 1) ?: 0
            field.setText("%02d".format(Math.floorMod(current + delta, modulo)))
        }
        addView(arrow(kit, "▲") { step(+1) }, LinearLayout.LayoutParams(kit.dp(96), kit.dp(44)))
        addView(field, LinearLayout.LayoutParams(kit.dp(96), kit.dp(84)).apply { topMargin = kit.dp(6) })
        addView(arrow(kit, "▼") { step(-1) }, LinearLayout.LayoutParams(kit.dp(96), kit.dp(44)).apply { topMargin = kit.dp(6) })
        addView(kit.text(label, MamaType.OVERLINE, MamaColors.TextSecondary, center = true), kit.wrap().apply {
            topMargin = kit.dp(8)
            gravity = Gravity.CENTER_HORIZONTAL
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun arrow(kit: Kit, symbol: String, action: () -> Unit): TextView =
        kit.text(symbol, MamaType.TITLE, MamaColors.EmeraldPrimary, center = true).apply {
            gravity = Gravity.CENTER
            background = kit.pressable(kit.shape(MamaColors.EmeraldTint, 16), 16)
            isClickable = true
            contentDescription = if (symbol == "▲") "Больше" else "Меньше"
            val handler = Handler(Looper.getMainLooper())
            val repeat = object : Runnable {
                override fun run() {
                    action()
                    handler.postDelayed(this, 90)
                }
            }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        action()
                        handler.postDelayed(repeat, 450)
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        handler.removeCallbacks(repeat)
                        v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        handler.removeCallbacks(repeat)
                    }
                }
                true
            }
        }
}

/** Date choice as a plain list of days: no calendar widget. */
object DateList {
    /** Offers [from] .. [to] inclusive; [initial] is highlighted. */
    fun show(context: Context, title: String, initial: LocalDate, from: LocalDate, to: LocalDate, onPicked: (LocalDate) -> Unit) {
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        if (days.isEmpty()) return
        BrandDialog.list(
            context,
            title,
            days.map { Texts.dayName(it, from) to Texts.date(it) },
            days.indexOf(initial),
        ) { onPicked(days[it]) }
    }
}
