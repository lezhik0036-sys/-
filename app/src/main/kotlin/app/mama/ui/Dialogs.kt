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
import java.time.LocalDate
import java.time.LocalTime

/** Handle of a shown [BrandDialog]. */
class BrandDialogHandle(private val dialog: Dialog, val confirm: BrandButtonView?) {
    fun dismiss() = dialog.dismiss()
    fun setConfirmEnabled(enabled: Boolean) {
        confirm?.isEnabled = enabled
    }
}

/** MAMA's own dialog: ivory surface, large radius, emerald actions. No system AlertDialogs. */
object BrandDialog {

    /** [onConfirm] returns true to close the dialog, false to keep it open (invalid input). */
    fun show(
        context: Context,
        title: String,
        message: CharSequence? = null,
        content: View? = null,
        confirm: String? = "Понятно",
        cancel: String? = null,
        onCancel: () -> Unit = {},
        onConfirm: () -> Boolean = { true },
    ): BrandDialogHandle {
        val kit = Kit(context)
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = kit.column().apply {
            background = kit.shape(MamaColors.WarmIvory, 26)
            setPadding(kit.dp(22), kit.dp(24), kit.dp(22), kit.dp(18))
        }
        sheet.addView(kit.text(title, MamaType.H2))
        message?.let { sheet.addView(kit.text(it, MamaType.BODY, MamaColors.TextSecondary), kit.gap(10)) }
        content?.let { sheet.addView(it, kit.gap(16)) }

        var confirmButton: BrandButtonView? = null
        if (confirm != null || cancel != null) {
            val actions = kit.row()
            cancel?.let {
                actions.addView(kit.secondaryButton(it) { dialog.dismiss(); onCancel() }, kit.weight())
            }
            confirm?.let {
                val b = kit.primaryButton(it) { if (onConfirm()) dialog.dismiss() }
                actions.addView(b, kit.weight().apply { if (cancel != null) leftMargin = kit.dp(10) })
                confirmButton = b
            }
            sheet.addView(actions, kit.gap(22))
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
            w.setDimAmount(0.45f)
            @Suppress("DEPRECATION")
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return BrandDialogHandle(dialog, confirmButton)
    }

    /** A list of choices; picking one closes the dialog. */
    fun list(context: Context, title: String, items: List<Pair<String, String?>>, selected: Int, onPick: (Int) -> Unit) {
        val kit = Kit(context)
        val rows = kit.column()
        lateinit var handle: BrandDialogHandle
        items.forEachIndexed { i, (main, side) ->
            val on = i == selected
            val r = kit.row().apply {
                minimumHeight = kit.dp(52)
                setPadding(kit.dp(16), kit.dp(10), kit.dp(16), kit.dp(10))
                background = if (on) {
                    kit.pressable(kit.gradient(MamaColors.EmeraldActive, MamaColors.Emerald, 16), 16, MamaColors.RippleOnDark)
                } else {
                    kit.pressable(kit.shape(MamaColors.PureCard, 16, MamaColors.Border), 16)
                }
                isClickable = true
                setOnClickListener { handle.dismiss(); onPick(i) }
            }
            r.addView(kit.text(main, MamaType.BODY, if (on) MamaColors.White else MamaColors.Graphite), kit.weight())
            side?.let { r.addView(kit.text(it, MamaType.BODY, if (on) MamaColors.OliveLight else MamaColors.TextSecondary)) }
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
 * [MamaDigitalTimePicker]: 24-hour `[ HH ] : [ mm ]`, typed or stepped with
 * arrows (hold to repeat). There is no analog clock face anywhere in MAMA.
 */
object DigitalTimePicker {

    fun show(context: Context, title: String = "Выберите время", initial: LocalTime, onPicked: (LocalTime) -> Unit) {
        val kit = Kit(context)
        val hours = digitField(kit, initial.hour)
        val minutes = digitField(kit, initial.minute)
        val error = kit.text("", MamaType.CAPTION, MamaColors.Warning, center = true).apply { visibility = View.GONE }

        val line = kit.row().apply { gravity = Gravity.CENTER }
        line.addView(unit(kit, "Часы", hours, 24))
        line.addView(kit.text(":", MamaType.DIGITS_L, MamaColors.Emerald).apply { setPadding(kit.dp(10), 0, kit.dp(10), kit.dp(26)) })
        line.addView(unit(kit, "Минуты", minutes, 60))

        val quick = kit.row().apply { gravity = Gravity.CENTER }
        listOf(0, 15, 30, 45).forEachIndexed { i, m ->
            quick.addView(kit.text(":%02d".format(m), MamaType.CAPTION, MamaColors.Emerald, center = true).apply {
                typeface = MamaFonts.ui(context, 700)
                gravity = Gravity.CENTER
                background = kit.pressable(kit.shape(MamaColors.EmeraldTint, 14), 14)
                setPadding(kit.dp(14), kit.dp(9), kit.dp(14), kit.dp(9))
                isClickable = true
                setOnClickListener { minutes.setText("%02d".format(m)) }
            }, kit.wrap().apply { if (i > 0) leftMargin = kit.dp(8) })
        }
        val content = kit.column().apply {
            addView(line, kit.fill())
            addView(quick, kit.gap(14))
            addView(error, kit.gap(12))
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
        setTextColor(MamaColors.Graphite)
        gravity = Gravity.CENTER
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(2))
        setSelectAllOnFocus(true)
        background = kit.fieldBackground()
        setPadding(0, kit.dp(8), 0, kit.dp(8))
    }

    private fun unit(kit: Kit, label: String, field: EditText, modulo: Int): View = kit.column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        fun step(delta: Int) {
            val current = field.text.toString().toIntOrNull()?.coerceIn(0, modulo - 1) ?: 0
            field.setText("%02d".format(Math.floorMod(current + delta, modulo)))
        }
        addView(arrow(kit, "▲") { step(+1) }, LinearLayout.LayoutParams(kit.dp(96), kit.dp(42)))
        addView(field, LinearLayout.LayoutParams(kit.dp(96), kit.dp(82)).apply { topMargin = kit.dp(6) })
        addView(arrow(kit, "▼") { step(-1) }, LinearLayout.LayoutParams(kit.dp(96), kit.dp(42)).apply { topMargin = kit.dp(6) })
        addView(kit.text(label, MamaType.SMALL, MamaColors.TextSecondary, center = true), kit.centered(8))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun arrow(kit: Kit, symbol: String, action: () -> Unit) =
        kit.text(symbol, MamaType.TITLE, MamaColors.Emerald, center = true).apply {
            gravity = Gravity.CENTER
            background = kit.pressable(kit.shape(MamaColors.EmeraldTint, 14), 14)
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
    fun show(context: Context, title: String, initial: LocalDate, from: LocalDate, to: LocalDate, onPicked: (LocalDate) -> Unit) {
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        if (days.isEmpty()) return
        BrandDialog.list(context, title, days.map { Texts.dayName(it, from) to Texts.date(it) }, days.indexOf(initial)) { onPicked(days[it]) }
    }
}
