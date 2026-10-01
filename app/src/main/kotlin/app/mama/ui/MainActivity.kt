package app.mama.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.mama.core.DailyWindow
import app.mama.core.LockPlan
import app.mama.core.FlexEngine
import app.mama.core.FlexPackage
import app.mama.core.GrantSource
import app.mama.core.LockState
import app.mama.core.Series
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.StandardRules
import app.mama.core.SessionMode
import app.mama.core.SessionStatus
import app.mama.core.TrustedContact
import app.mama.billing.Checkout
import app.mama.billing.Product
import app.mama.platform.Mama
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Setup screen: mode, start/end time and time zone, trusted contact,
 * permission checklist, start. While a session is planned or running it
 * shows the session instead of the form, so the contact cannot be changed.
 */
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("mama_setup", Context.MODE_PRIVATE) }

    private var mode = SessionMode.SLEEP
    private var seriesKind = SeriesKind.SEVEN
    private var start = LocalTime.of(23, 0)
    private var end = LocalTime.of(7, 0)
    private var zone: ZoneId = ZoneId.systemDefault()
    private lateinit var contactName: EditText
    private lateinit var contactPhone: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = runCatching { SessionMode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(SessionMode.SLEEP)
        seriesKind = runCatching { SeriesKind.valueOf(prefs.getString("series", null)!!) }
            .getOrDefault(SeriesKind.SEVEN)
        start = runCatching { LocalTime.parse(prefs.getString("start", null)) }.getOrDefault(start)
        end = runCatching { LocalTime.parse(prefs.getString("end", null)) }.getOrDefault(end)
        zone = runCatching { ZoneId.of(prefs.getString("zone", null)) }.getOrDefault(zone)
        contactName = input("Имя, например «Мама»", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            .apply { setText(prefs.getString("contactName", "")) }
        contactPhone = input("+7 900 123-45-67", InputType.TYPE_CLASS_PHONE)
            .apply { setText(prefs.getString("contactPhone", "")) }
    }

    override fun onResume() {
        super.onResume()
        render()
        if (setupAllActive) window.decorView.post { continueSetupAll() }
    }

    // --- "grant everything" wizard ---

    /** True while the one-button setup walks through the missing permissions. */
    private var setupAllActive = false

    /** Whether this screen was covered since the last runtime-permission request. */
    private var pausedSinceRequest = false

    override fun onPause() {
        super.onPause()
        pausedSinceRequest = true
    }

    /** Items already offered in this run, so a refusal does not loop forever. */
    private val setupAllOffered = mutableSetOf<Requirement>()

    private fun startSetupAll() {
        setupAllActive = true
        setupAllOffered.clear()
        continueSetupAll()
    }

    /**
     * Android does not let an app grant these itself: each needs a tap from the
     * user. The wizard asks all runtime permissions in one dialog, then opens
     * each settings screen in turn and comes back here after every one.
     */
    private fun continueSetupAll() {
        if (!setupAllActive) return
        val missing = Requirement.entries.filter { !it.isGranted(this) && it !in setupAllOffered }
        val runtime = missing.filter { it.runtimePermissions.isNotEmpty() }
        if (runtime.isNotEmpty()) {
            setupAllOffered += runtime
            pausedSinceRequest = false
            requestPermissions(runtime.flatMap { it.runtimePermissions.toList() }.toTypedArray(), SETUP_ALL)
            return
        }
        val next = missing.firstOrNull()
        if (next == null) {
            setupAllActive = false
            val left = Requirement.missingRequired(this)
            if (left.isEmpty()) {
                Toast.makeText(this, "Все разрешения выданы", Toast.LENGTH_SHORT).show()
            } else {
                alert("Остались разрешения", left.joinToString("\n") { "• ${it.title}" } +
                    "\n\nНажмите «Выдать все разрешения» ещё раз.")
            }
            render()
            return
        }
        setupAllOffered += next
        next.hint?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        next.request(this)
    }

    @Deprecated("Framework Activity API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
        // If a system dialog was shown, onResume continues the wizard. If Android
        // answered without a dialog (denied for good earlier), continue here.
        if (requestCode == SETUP_ALL && !pausedSinceRequest) continueSetupAll()
    }

    private fun saveForm() {
        prefs.edit()
            .putString("mode", mode.name)
            .putString("series", seriesKind.name)
            .putString("start", start.toString())
            .putString("end", end.toString())
            .putString("zone", zone.id)
            .putString("contactName", contactName.text.toString())
            .putString("contactPhone", contactPhone.text.toString())
            .apply()
    }

    private fun render() {
        val state = Mama.sync(this)
        // The contact fields are reused across renders to keep typed text.
        (contactName.parent as? ViewGroup)?.removeView(contactName)
        (contactPhone.parent as? ViewGroup)?.removeView(contactPhone)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }
        column.addView(label("MAMA  ${Texts.version(this)}", 30f, bold = true))
        column.addView(label("Добровольная блокировка телефона на выбранное время", 15f, MUTED))
        column.addView(space(16))
        stopTicker()
        val series = Mama.series(this)
        val flex = Mama.entitlements(this).flex
        when {
            series != null && series.status == SeriesStatus.BROKEN -> renderBroken(column, series)
            series != null && series.status == SeriesStatus.COMPLETED -> renderSuccess(column, series)
            series != null -> renderSeries(column, series, state)
            flex != null && flex.over -> renderFlexResult(column, flex)
            flex != null -> renderFlex(column, flex, state)
            state == LockState.Free -> renderForm(column)
            else -> renderSession(column, state)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Palette.PAPER)
            addView(column)
        })
    }

    // --- session in progress ---

    private fun renderSession(column: LinearLayout, state: LockState) {
        val session = Mama.session(this) ?: return
        val z = session.plan.zone
        val now = Mama.trustedNow(this)
        column.addView(label("Режим «${Texts.mode(session.plan.mode)}»", 20f, bold = true))
        column.addView(label("${Texts.dayTime(session.plan.start, z)} → ${Texts.dayTime(session.plan.end, z)}", 16f))
        column.addView(label(Texts.zone(z, now), 14f, MUTED))
        column.addView(label("Доверенный контакт: ${session.contact.name}, ${session.contact.phone}", 14f, MUTED))
        column.addView(space(16))
        when (state) {
            is LockState.Waiting -> {
                column.addView(label("Начнётся через ${Texts.duration(Duration.between(now, state.startsAt))}.", 16f))
                column.addView(label("До начала можно передумать. После — выйти можно только с кодом от доверенного контакта.", 14f, MUTED))
                column.addView(button("Отменить до начала") {
                    Mama.cancelBeforeStart(this)
                    render()
                })
            }
            is LockState.Locked -> column.addView(label("Идёт блокировка.", 16f))
            is LockState.EmergencyPass -> {
                column.addView(label("Экстренный доступ до ${Texts.time(state.until, z)}.", 16f))
                column.addView(label("После этого блокировка вернётся до ${Texts.time(state.endsAt, z)}.", 14f, MUTED))
            }
            LockState.Free -> Unit
        }
    }

    // --- setup form ---

    private fun renderForm(column: LinearLayout) {
        Mama.session(this)?.takeIf { it.status == SessionStatus.FINISHED }?.let {
            column.addView(label("Прошлая сессия: ${Texts.finishReason(it.finishReason)}.", 14f, MUTED))
            column.addView(space(12))
        }

        column.addView(section("Серия"))
        column.addView(label("Сколько дней подряд ты готов не менять своё решение?", 14f, MUTED))
        SeriesKind.entries.forEach { kind -> column.addView(seriesOption(kind), ui.gap(8)) }
        val ent = Mama.entitlements(this)
        val waitDays = StandardRules.daysUntilFreeStart(ent, Mama.trustedNow(this))
        if (waitDays > 0) {
            column.addView(ui.card().apply {
                addView(ui.text("Бесплатная новая серия будет доступна через ${Texts.days(waitDays.toInt())}", 15f, bold = true))
                addView(ui.small("Телефон при этом не заблокирован: ожидание касается только бесплатного старта новой серии."))
            }, ui.gap(10))
        }

        column.addView(section("MAMA FLEX"))
        column.addView(flexOffer(ent.freeFlexCredits), ui.gap(4))

        column.addView(section("Режим"))
        column.addView(button(Texts.mode(mode)) { pickMode() })

        column.addView(section("Время"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("С ${start}") { pickTime(start) { start = it } }, weighted())
        row.addView(button("До ${end}") { pickTime(end) { end = it } }, weighted())
        column.addView(row)
        column.addView(button("Часовой пояс: ${Texts.zone(zone)}") { pickZone() })
        column.addView(label(preview(), 14f, MUTED))

        column.addView(section("Доверенный контакт"))
        column.addView(label("Только этот человек сможет выпустить вас раньше: ему придёт SMS с кодом из 5 цифр, на ввод — 3 попытки.", 14f, MUTED))
        column.addView(contactName)
        column.addView(contactPhone)
        column.addView(button("Выбрать из контактов") { pickContact() })

        column.addView(section("Разрешения"))
        if (Requirement.entries.any { !it.isGranted(this) }) {
            column.addView(button("Выдать все разрешения", primary = true) { startSetupAll() })
            column.addView(label("MAMA по очереди откроет каждый экран: включите переключатель и вернитесь назад.", 13f, MUTED))
        }
        Requirement.entries.forEach { req ->
            val ok = req.isGranted(this)
            val mark = if (ok) "✓" else if (req.required) "✗" else "○"
            column.addView(button("$mark  ${req.title}", enabled = !ok) { req.request(this) })
            column.addView(label(req.why, 13f, MUTED))
        }

        column.addView(space(20))
        column.addView(ui.primary("Начать серию: ${Texts.seriesName(seriesKind)}") { confirmStart() }, ui.fill())
    }

    private val ui by lazy { Ui(this) }

    /** One selectable standard series card with its Restart price. */
    private fun seriesOption(kind: SeriesKind): View {
        val selected = kind == seriesKind
        val fg = if (selected) Color.WHITE else Palette.INK
        val sub = if (selected) Palette.CHIP else Palette.MUTED
        return ui.card(selected).apply {
            val line = ui.row()
            line.addView(ui.text(Texts.seriesName(kind), 20f, fg, bold = true), ui.weight())
            if (selected) line.addView(ui.text("✓", 18f, Color.WHITE, bold = true))
            addView(line)
            addView(ui.text("Restart при срыве — ${Texts.price(kind.restartPriceRub)}", 13f, sub))
            setOnClickListener {
                seriesKind = kind
                saveForm()
                render()
            }
        }
    }

    /** The FLEX offer: price, rules, and the free package if one was earned. */
    private fun flexOffer(freeCredits: Int): View = ui.card().apply {
        val line = ui.row()
        line.addView(ui.text("FLEX", 20f, bold = true), ui.weight())
        line.addView(
            ui.text(if (freeCredits > 0) "бесплатно" else Texts.price(FlexPackage.PRICE_RUB), 16f, Palette.FOREST, bold = true),
        )
        addView(line)
        addView(ui.small(
            "${FlexPackage.PERIODS} периодов блокировки в любые выбранные дни, срок ${FlexPackage.VALIDITY.toDays()} дней. " +
                "Разовый пакет, не подписка, без автопродления. Пройди 7 из 7 — следующий FLEX бесплатно.",
        ))
        if (freeCredits > 0) {
            addView(ui.primary("Активировать бесплатный FLEX") { activateFlex(GrantSource.REWARD) }, ui.gap(10))
        } else {
            addView(ui.secondary("Купить FLEX — ${Texts.price(FlexPackage.PRICE_RUB)}") {
                Checkout.obtain(this@MainActivity, Product.FlexPackage, "MAMA FLEX") { activateFlex(it) }
            }, ui.gap(10))
        }
    }

    private fun activateFlex(source: GrantSource) {
        saveForm()
        if (!Mama.activateFlex(this, source)) {
            alert("Не получилось", "FLEX можно активировать, когда нет активной серии или блокировки.")
        }
        render()
    }

    /** Checks permissions, time and contact from the form; null (with a message) if something is missing. */
    private fun readyContact(): TrustedContact? {
        saveForm()
        val missing = Requirement.missingRequired(this)
        if (missing.isNotEmpty()) {
            alert("Не хватает разрешений", missing.joinToString("\n") { "• ${it.title}" })
            return null
        }
        if (start == end) {
            alert("Проверьте время", "Начало и конец совпадают.")
            return null
        }
        val name = contactName.text.toString().trim()
        val phone = TrustedContact.normalizePhone(contactPhone.text.toString())
        if (name.isEmpty() || phone == null) {
            alert("Доверенный контакт", "Укажите имя и номер телефона в формате +7 900 123-45-67.")
            return null
        }
        return TrustedContact(name, phone)
    }

    private fun plan(): LockPlan =
        DailyWindow(start, end, zone).nextPlan(Mama.trustedNow(this), mode, Mama.policy.minDuration)

    private fun preview(): String {
        if (start == end) return "Начало и конец совпадают."
        val p = plan()
        val now = Mama.trustedNow(this)
        val startsNow = !p.start.isAfter(now)
        val from = if (startsNow) "сразу" else Texts.dayTime(p.start, zone)
        val length = Duration.between(maxOf(p.start, now), p.end)
        val local = ZoneId.systemDefault()
        val localHint = if (local.rules.getOffset(now) != zone.rules.getOffset(now)) {
            "\nПо времени телефона (${local.id}): ${Texts.dayTime(p.start, local)} → ${Texts.dayTime(p.end, local)}"
        } else {
            ""
        }
        return "Блокировка: $from → ${Texts.dayTime(p.end, zone)} (${Texts.duration(length)})$localHint"
    }

    private fun confirmStart() {
        val contact = readyContact() ?: return
        val kind = seriesKind
        val ent = Mama.entitlements(this)
        val waitDays = StandardRules.daysUntilFreeStart(ent, Mama.trustedNow(this))
        if (waitDays > 0) {
            alert(
                "Бесплатная серия пока недоступна",
                "Бесплатная новая серия будет доступна через ${Texts.days(waitDays.toInt())}. " +
                    "Телефон при этом не заблокирован.",
            )
            return
        }
        val restart = Texts.price(kind.restartPriceRub)
        val message = "Серия: ${Texts.seriesName(kind)}\n" +
            "Каждый день: $start → $end (${zone.id})\n" +
            "Доверенный контакт: ${contact.name}, ${contact.phone}\n\n" +
            "Если серия будет прервана:\nпрогресс обнулится.\nRestart серии — $restart.\n" +
            "Без Restart бесплатная новая серия будет доступна через " +
            "${StandardRules.FREE_START_AFTER.toDays()} дней.\n\n" +
            "Выйти по коду доверенного контакта можно всегда, это бесплатно. " +
            "Если серия завершена успешно — платить не нужно."
        val agree = CheckBox(this).apply {
            text = "Я понимаю условия и принимаю ответственность за своё решение"
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(agree)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Проверь условия")
            .setMessage(message)
            .setView(box)
            .setPositiveButton("Начать серию") { _, _ ->
                when (val r = Mama.startSeries(this, kind, DailyWindow(start, end, zone), contact, mode)) {
                    Mama.SeriesStart.Started -> Unit
                    Mama.SeriesStart.Busy ->
                        alert("Не получилось", "Сейчас уже идёт серия, FLEX-период или блокировка.")
                    is Mama.SeriesStart.Waiting ->
                        alert("Бесплатная серия пока недоступна", "Будет доступна через ${Texts.days(r.days.toInt())}.")
                }
                render()
            }
            .setNegativeButton("Назад", null)
            .show()
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positive.isEnabled = false
        agree.setOnCheckedChangeListener { _, checked -> positive.isEnabled = checked }
    }

    // --- series screens ---

    private val tickHandler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    private fun stopTicker() {
        ticker?.let(tickHandler::removeCallbacks)
        ticker = null
    }

    override fun onStop() {
        stopTicker()
        super.onStop()
    }

    private fun renderSeries(column: LinearLayout, series: Series, state: LockState) {
        val n = series.kind.periods
        val day = minOf(series.completedPeriods + 1, n)
        column.addView(ui.text("Твоя серия активна", 22f, bold = true, center = true), ui.fill())
        column.addView(ui.text("День $day из $n", 17f, Palette.MUTED, center = true), ui.gap(4))
        val dots = ui.row().apply { gravity = Gravity.CENTER }
        repeat(n) { i ->
            val done = i < series.completedPeriods
            dots.addView(
                ui.badge("", if (done) Palette.FOREST else Palette.LINE, sizeDp = 16),
                LinearLayout.LayoutParams(dp(16), dp(16)).apply { setMargins(dp(5), 0, dp(5), 0) },
            )
        }
        column.addView(dots, ui.gap(14))

        val status = ui.card()
        val caption = ui.small("", center = true)
        val big = ui.text("", 34f, bold = true, center = true)
        status.addView(caption, ui.fill())
        status.addView(big, ui.gap(4))
        column.addView(status, ui.gap(18))
        val z = series.window.zone
        val tick = object : Runnable {
            override fun run() {
                val now = Mama.trustedNow(this@MainActivity)
                when (val st = Mama.state(this@MainActivity)) {
                    is LockState.Waiting -> {
                        caption.text = "MAMA включится в ${Texts.time(st.startsAt, z)} через"
                        big.text = Texts.countdown(Duration.between(now, st.startsAt))
                    }
                    is LockState.Locked -> {
                        caption.text = "Блокировка идёт, осталось"
                        big.text = Texts.countdown(Duration.between(now, st.endsAt))
                    }
                    is LockState.EmergencyPass -> {
                        caption.text = "Экстренный доступ, блокировка вернётся через"
                        big.text = Texts.countdown(Duration.between(now, st.until))
                    }
                    LockState.Free -> {
                        caption.text = "Следующий период планируется…"
                        big.text = "—"
                    }
                }
                tickHandler.postDelayed(this, 1_000)
            }
        }
        ticker = tick
        tick.run()

        val info = ui.row()
        info.addView(ui.card().apply {
            addView(ui.small("Каждый день"))
            addView(ui.text("${series.window.start} → ${series.window.end}", 16f, bold = true))
        }, ui.weight().apply { rightMargin = dp(6) })
        info.addView(ui.card().apply {
            addView(ui.small("Серия"))
            addView(ui.text(Texts.seriesName(series.kind), 16f, bold = true))
        }, ui.weight().apply { leftMargin = dp(6) })
        column.addView(info, ui.gap(12))

        column.addView(ui.card().apply {
            addView(ui.small("Доверенный контакт"))
            addView(ui.text(series.contact.name, 17f, bold = true))
            addView(ui.small(series.contact.phone))
        }, ui.gap(12))

        column.addView(
            ui.small(
                "Если серия будет прервана: прогресс обнулится. Restart серии — " +
                    "${Texts.price(series.kind.restartPriceRub)}, или бесплатная новая серия через " +
                    "${StandardRules.FREE_START_AFTER.toDays()} дней.",
            ),
            ui.gap(16),
        )

        if (state is LockState.Waiting) {
            column.addView(ui.secondary("Прервать серию") { confirmBreak(series) }, ui.gap(20))
        }
    }

    private fun confirmBreak(series: Series) {
        val price = "\nRestart серии — ${Texts.price(series.kind.restartPriceRub)}, " +
            "или бесплатная новая серия через ${StandardRules.FREE_START_AFTER.toDays()} дней."
        AlertDialog.Builder(this)
            .setTitle("Прервать серию?")
            .setMessage("Пройдено ${series.completedPeriods} из ${series.kind.periods}. Прогресс обнулится.$price")
            .setPositiveButton("Прервать") { _, _ ->
                Mama.cancelBeforeStart(this)
                render()
            }
            .setNegativeButton("Продолжить серию", null)
            .show()
    }

    private fun renderBroken(column: LinearLayout, series: Series) {
        column.addView(ui.badge("!", Palette.ALERT, sizeDp = 64).apply {
            (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        })
        column.addView(ui.text("Серия остановлена", 24f, bold = true, center = true), ui.gap(16))
        column.addView(
            ui.body(
                "Вы прошли ${series.completedPeriods} из ${Texts.days(series.kind.periods)}. " +
                    "Прогресс серии обнулён. Телефон разблокирован.",
                center = true,
            ),
            ui.gap(8),
        )
        column.addView(ui.card().apply {
            addView(ui.small("Restart серии прямо сейчас", center = true), ui.fill())
            addView(ui.text(Texts.price(series.kind.restartPriceRub), 30f, bold = true, center = true), ui.gap(4))
            addView(ui.primary("Начать заново") {
                Checkout.obtain(this@MainActivity, Product.Restart(series.kind), "Restart серии") { source ->
                    if (!Mama.restartSeries(this@MainActivity, source)) {
                        alert("Не получилось", "Restart сейчас недоступен.")
                    }
                    render()
                }
            }, ui.gap(12))
        }, ui.gap(24))
        val days = StandardRules.daysUntilFreeStart(Mama.entitlements(this), Mama.trustedNow(this))
        if (days > 0) {
            column.addView(
                ui.small("Без Restart бесплатная новая серия будет доступна через ${Texts.days(days.toInt())}.", center = true),
                ui.gap(12),
            )
        }
        column.addView(ui.secondary("Вернуться без серии") {
            Mama.dismissSeries(this)
            render()
        }, ui.gap(16))
    }

    private fun renderSuccess(column: LinearLayout, series: Series) {
        column.addView(ui.badge("✓", Palette.FOREST, sizeDp = 64).apply {
            (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        })
        column.addView(ui.text("Серия пройдена!", 26f, bold = true, center = true), ui.gap(16))
        column.addView(
            ui.body(
                "${series.completedPeriods} из ${series.kind.periods}: решение выдержано до конца.",
                center = true,
            ),
            ui.gap(8),
        )
        column.addView(ui.primary("Новая серия") {
            Mama.dismissSeries(this)
            render()
        }, ui.gap(28))
    }

    // --- FLEX ---

    private fun renderFlex(column: LinearLayout, flex: FlexPackage, state: LockState) {
        val now = Mama.trustedNow(this)
        column.addView(ui.text("MAMA FLEX", 24f, bold = true, center = true), ui.fill())
        column.addView(
            ui.text("${flex.successful} из ${FlexPackage.PERIODS} выполнено", 17f, Palette.MUTED, center = true),
            ui.gap(4),
        )
        val dots = ui.row().apply { gravity = Gravity.CENTER }
        repeat(FlexPackage.PERIODS) { i ->
            val color = when {
                i < flex.successful -> Palette.FOREST
                i < flex.successful + flex.failed -> Palette.ALERT
                else -> Palette.LINE
            }
            dots.addView(
                ui.badge("", color, sizeDp = 16),
                LinearLayout.LayoutParams(dp(16), dp(16)).apply { setMargins(dp(5), 0, dp(5), 0) },
            )
        }
        column.addView(dots, ui.gap(14))
        column.addView(
            ui.body(
                if (flex.rewardStillPossible) {
                    "Пройди 7 из 7 — следующий FLEX бесплатно"
                } else {
                    "Следующий бесплатный FLEX доступен только при результате 7 из 7."
                },
                center = true,
            ),
            ui.gap(10),
        )
        flex.lastBurntAt?.takeIf { Duration.between(it, now) < Duration.ofHours(18) }?.let {
            column.addView(ui.card().apply {
                addView(ui.text("Сегодняшний FLEX-день завершён досрочно.", 15f, bold = true))
                addView(ui.small("Успешно: ${flex.successful} из ${FlexPackage.PERIODS}. Остальные дни FLEX остаются доступны."))
            }, ui.gap(12))
        }

        val stats = ui.row()
        val daysLeft = maxOf(0L, Duration.between(now, flex.expiresAt).toDays())
        listOf(
            "Осталось дней FLEX" to "${flex.remaining}",
            "Успешно" to "${flex.successful}",
            "Действует ещё" to Texts.days(daysLeft.toInt()),
        ).forEachIndexed { i, (k, v) ->
            stats.addView(ui.card(paddingDp = 12).apply {
                addView(ui.small(k))
                addView(ui.text(v, 17f, bold = true))
            }, ui.weight().apply { if (i > 0) leftMargin = dp(8) })
        }
        column.addView(stats, ui.gap(14))
        column.addView(
            ui.small("Пакет действует до ${Texts.dayTime(flex.expiresAt, zone)}. Неиспользованные дни сгорают после этого срока."),
            ui.gap(8),
        )

        if (flex.currentSessionId != null && state != LockState.Free) {
            val caption = ui.small("", center = true)
            val big = ui.text("", 32f, bold = true, center = true)
            column.addView(ui.card().apply {
                addView(caption, ui.fill())
                addView(big, ui.gap(4))
            }, ui.gap(16))
            val tick = object : Runnable {
                override fun run() {
                    val t = Mama.trustedNow(this@MainActivity)
                    when (val st = Mama.state(this@MainActivity)) {
                        is LockState.Waiting -> {
                            caption.text = "FLEX-период начнётся в ${Texts.time(st.startsAt, zone)} через"
                            big.text = Texts.countdown(Duration.between(t, st.startsAt))
                        }
                        is LockState.Locked -> {
                            caption.text = "FLEX-период идёт, осталось"
                            big.text = Texts.countdown(Duration.between(t, st.endsAt))
                        }
                        is LockState.EmergencyPass -> {
                            caption.text = "Экстренный доступ, блокировка вернётся через"
                            big.text = Texts.countdown(Duration.between(t, st.until))
                        }
                        LockState.Free -> {
                            caption.text = ""
                            big.text = "—"
                        }
                    }
                    tickHandler.postDelayed(this, 1_000)
                }
            }
            ticker = tick
            tick.run()
            if (state is LockState.Waiting) {
                column.addView(
                    ui.small("Отмена до начала не тратит FLEX-день.", center = true),
                    ui.gap(8),
                )
                column.addView(ui.secondary("Отменить этот FLEX-период") {
                    Mama.cancelBeforeStart(this)
                    render()
                }, ui.gap(8))
            }
        } else {
            renderFlexPlanner(column)
        }
    }

    // --- FLEX period planner: absolute local date + time, never in the past ---

    private var flexStartNow = true
    private var flexStart: LocalDateTime? = null
    private var flexEnd: LocalDateTime? = null

    private fun nowLocal(): LocalDateTime =
        LocalDateTime.ofInstant(Mama.trustedNow(this), zone).truncatedTo(ChronoUnit.MINUTES)

    private fun renderFlexPlanner(column: LinearLayout) {
        val now = nowLocal()
        val start = if (flexStartNow) now else (flexStart ?: now.plusHours(1))
        // Until the user picks an end, suggest the shortest valid one. For "start now" it is
        // 3 h after the real moment rounded up to the minute (17:12:37 → 20:13). Once picked,
        // the end is kept exactly as chosen: never moved, never shifted to another day.
        val end = flexEnd ?: if (flexStartNow) {
            LocalDateTime.ofInstant(FlexPackage.defaultEndForStartNow(Mama.trustedNow(this)), zone)
        } else {
            start.plus(FlexPackage.MIN_DURATION)
        }
        if (!flexStartNow && flexStart == null) flexStart = start

        column.addView(section("Новый FLEX-период"))
        val modes = ui.row()
        modes.addView(
            (if (flexStartNow) ui.primary("Начать сейчас") {} else ui.secondary("Начать сейчас") {
                flexStartNow = true
                render()
            }),
            ui.weight().apply { rightMargin = dp(6) },
        )
        modes.addView(
            (if (!flexStartNow) ui.primary("Запланировать") {} else ui.secondary("Запланировать") {
                flexStartNow = false
                render()
            }),
            ui.weight().apply { leftMargin = dp(6) },
        )
        column.addView(modes, ui.gap(4))

        val today = now.toLocalDate()
        if (flexStartNow) {
            column.addView(label("Начало: сейчас · ${Texts.relativeDayTime(now, today)}", 15f))
        } else {
            column.addView(button("Начало: ${Texts.relativeDayTime(start, today)}") {
                pickDateTime(start) { flexStart = it; render() }
            })
        }
        column.addView(button("Окончание: ${Texts.relativeDayTime(end, today)}") {
            pickDateTime(end) { flexEnd = it; render() }
        })
        val length = Duration.between(start, end)
        if (!length.isNegative && !length.isZero) {
            column.addView(label("Продолжительность: ${Texts.flexDuration(length)} · ${zone.id}", 13f, MUTED))
        }

        column.addView(ui.primary("Включить FLEX-период") { submitFlexPeriod() }, ui.gap(16))
        column.addView(
            ui.small(
                "FLEX-день расходуется в момент начала блокировки. От 3 до 24 часов, можно через полночь. " +
                    "Доверенный контакт: ${contactName.text}.",
                center = true,
            ),
            ui.gap(8),
        )
    }

    private fun submitFlexPeriod() {
        val contact = readyContact() ?: return
        val start = if (flexStartNow) null else flexStart
        val end = flexEnd ?: if (flexStartNow) {
            LocalDateTime.ofInstant(FlexPackage.defaultEndForStartNow(Mama.trustedNow(this)), zone)
        } else {
            (start ?: return).plus(FlexPackage.MIN_DURATION)
        }
        val result = Mama.startFlexPeriod(
            this,
            start?.atZone(zone)?.toInstant(),
            end.atZone(zone).toInstant(),
            zone, contact, mode,
        )
        when (result) {
            is FlexEngine.PlanResult.Planned -> {
                flexStart = null
                flexEnd = null
                flexStartNow = true
            }
            is FlexEngine.PlanResult.Rejected -> alert("FLEX-период", Texts.flexPlanError(result.error))
        }
        render()
    }

    /** Date, then time. Nothing is inferred: the chosen date and time are used as is. */
    private fun pickDateTime(initial: LocalDateTime, set: (LocalDateTime) -> Unit) {
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                set(LocalDateTime.of(y, m + 1, d, h, min))
            }, initial.hour, initial.minute, true).show()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }

    private fun renderFlexResult(column: LinearLayout, flex: FlexPackage) {
        val perfect = flex.earnedReward
        column.addView(ui.badge(if (perfect) "✓" else "•", if (perfect) Palette.FOREST else Palette.MOSS, sizeDp = 64).apply {
            (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        })
        column.addView(ui.text("FLEX завершён", 24f, bold = true, center = true), ui.gap(16))
        column.addView(
            ui.body("Успешно: ${flex.successful} из ${FlexPackage.PERIODS}.", center = true),
            ui.gap(8),
        )
        val credits = Mama.entitlements(this).freeFlexCredits
        if (credits > 0) {
            column.addView(ui.body("Следующий FLEX — бесплатно.", center = true), ui.gap(8))
            column.addView(ui.primary("Активировать бесплатный FLEX") {
                Mama.dismissFlex(this)
                activateFlex(GrantSource.REWARD)
            }, ui.gap(20))
        } else {
            column.addView(
                ui.small("Следующий бесплатный FLEX доступен только при результате 7 из 7.", center = true),
                ui.gap(8),
            )
            column.addView(ui.secondary("Новый FLEX — ${Texts.price(FlexPackage.PRICE_RUB)}") {
                Checkout.obtain(this@MainActivity, Product.FlexPackage, "MAMA FLEX") { source ->
                    Mama.dismissFlex(this@MainActivity)
                    activateFlex(source)
                }
            }, ui.gap(20))
        }
        column.addView(ui.secondary("На главную") {
            Mama.dismissFlex(this)
            render()
        }, ui.gap(12))
    }

    private fun pickMode() {
        val modes = SessionMode.entries
        AlertDialog.Builder(this)
            .setTitle("Режим")
            .setSingleChoiceItems(modes.map { Texts.mode(it) }.toTypedArray(), modes.indexOf(mode)) { d, i ->
                mode = modes[i]
                saveForm()
                d.dismiss()
                render()
            }
            .show()
    }

    private fun pickTime(initial: LocalTime, set: (LocalTime) -> Unit) {
        TimePickerDialog(this, { _, h, m ->
            set(LocalTime.of(h, m))
            saveForm()
            render()
        }, initial.hour, initial.minute, true).show()
    }

    private fun pickZone() {
        val now = Instant.now()
        val zones = ZoneId.getAvailableZoneIds()
            .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .map(ZoneId::of)
            .sortedWith(compareBy<ZoneId> { it.rules.getOffset(now).totalSeconds }.thenBy { it.id })
        val system = ZoneId.systemDefault()
        val items = listOf(system) + zones.filter { it != system }
        AlertDialog.Builder(this)
            .setTitle("Часовой пояс")
            .setItems(items.map { Texts.zone(it, now) }.toTypedArray()) { _, i ->
                zone = items[i]
                saveForm()
                render()
            }
            .show()
    }

    private fun pickContact() {
        saveForm()
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        runCatching { @Suppress("DEPRECATION") startActivityForResult(intent, PICK_CONTACT) }
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_CONTACT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                contactName.setText(c.getString(0).orEmpty())
                contactPhone.setText(c.getString(1).orEmpty())
                saveForm()
            }
        }
    }

    // --- small view helpers ---

    private fun alert(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Понятно", null).show()
    }

    private fun section(text: String) = label(text, 13f, ACCENT, bold = true).apply {
        setPadding(0, dp(20), 0, dp(6))
        isAllCaps = true
    }

    private fun label(text: String, sizeSp: Float, color: Int = FG, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun input(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setSingleLine()
    }

    private fun button(
        text: String,
        primary: Boolean = false,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) = Button(this).apply {
        this.text = text
        isAllCaps = false
        isEnabled = enabled
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(if (primary) Color.WHITE else FG)
        background = GradientDrawable().apply {
            setColor(if (primary) PRIMARY else SURFACE)
            cornerRadius = dp(12).toFloat()
        }
        if (primary) gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) }
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        .apply { setMargins(dp(2), dp(4), dp(2), dp(4)) }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val PICK_CONTACT = 10
        const val SETUP_ALL = 20
        val FG = Color.rgb(0x1A, 0x1D, 0x22)
        val MUTED = Color.rgb(0x6B, 0x72, 0x7C)
        val ACCENT = Color.rgb(0x2B, 0x5C, 0xB8)
        val PRIMARY = Color.rgb(0x2B, 0x5C, 0xB8)
        val SURFACE = Color.rgb(0xEE, 0xF1, 0xF5)
    }
}
