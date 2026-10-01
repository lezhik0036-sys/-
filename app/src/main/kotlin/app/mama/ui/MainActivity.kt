package app.mama.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import app.mama.billing.Checkout
import app.mama.billing.FeatureFlags
import app.mama.billing.Product
import app.mama.core.DailyWindow
import app.mama.core.FlexEngine
import app.mama.core.FlexPackage
import app.mama.core.GrantSource
import app.mama.core.LockPlan
import app.mama.core.LockState
import app.mama.core.Series
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.SessionMode
import app.mama.core.SessionStatus
import app.mama.core.StandardRules
import app.mama.core.TestMode
import app.mama.core.TrustedContact
import app.mama.platform.Mama
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Setup screen: mode, time, trusted contact, permissions, start. While a
 * series, FLEX package or session exists it shows that instead of the form,
 * so the contact cannot be changed mid-commitment.
 */
class MainActivity : Activity() {

    /** What the user is about to start. */
    private enum class Choice(val series: SeriesKind?) {
        TEST(null), THREE(SeriesKind.THREE), FIVE(SeriesKind.FIVE), SEVEN(SeriesKind.SEVEN), FLEX(null)
    }

    private val prefs by lazy { getSharedPreferences("mama_setup", Context.MODE_PRIVATE) }
    private val kit by lazy { Kit(this) }

    private var mode = SessionMode.SLEEP
    private var choice = Choice.SEVEN
    private var start = LocalTime.of(23, 0)
    private var end = LocalTime.of(7, 0)
    private var zone: ZoneId = ZoneId.systemDefault()
    private lateinit var contactName: EditText
    private lateinit var contactPhone: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        styleSystemBars()
        mode = runCatching { SessionMode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(SessionMode.SLEEP)
        choice = runCatching { Choice.valueOf(prefs.getString("choice", null)!!) }.getOrElse {
            // Older versions stored only the series length.
            runCatching { Choice.valueOf(prefs.getString("series", null)!!) }.getOrDefault(Choice.SEVEN)
        }
        if (choice == Choice.TEST && !FeatureFlags.TEST_MODE_ENABLED) choice = Choice.SEVEN
        start = runCatching { LocalTime.parse(prefs.getString("start", null)) }.getOrDefault(start)
        end = runCatching { LocalTime.parse(prefs.getString("end", null)) }.getOrDefault(end)
        zone = runCatching { ZoneId.of(prefs.getString("zone", null)) }.getOrDefault(zone)
        contactName = kit.textField("Например, «Мама»", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            .apply { setText(prefs.getString("contactName", "")) }
        contactPhone = kit.textField("+7 900 123-45-67", InputType.TYPE_CLASS_PHONE)
            .apply { setText(prefs.getString("contactPhone", "")) }
    }

    /**
     * Cream bars with dark icons. The page is laid out edge-to-edge on every
     * Android version (Android 15 enforces it anyway) and pads itself for the
     * bars in [padForBars], so spacing is the same on all phones.
     */
    @Suppress("DEPRECATION")
    private fun styleSystemBars() {
        window.statusBarColor = MamaColors.BackgroundPrimary
        window.navigationBarColor = MamaColors.BackgroundPrimary
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            // Not window.insetsController: in onCreate, before setContentView, the decor view does not
            // exist yet and PhoneWindow.getInsetsController() throws a NullPointerException (v0.4.0 crash).
            // window.decorView creates the decor; its controller applies the appearance once attached.
            window.decorView.windowInsetsController?.setSystemBarsAppearance(light, light)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
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
            .putString("choice", choice.name)
            .putString("start", start.toString())
            .putString("end", end.toString())
            .putString("zone", zone.id)
            .putString("contactName", contactName.text.toString())
            .putString("contactPhone", contactPhone.text.toString())
            .apply()
    }

    // --- rendering ---

    private var scroll: ScrollView? = null
    private var screen: String? = null

    private fun render() {
        val state = Mama.sync(this)
        // The contact fields are reused across renders to keep typed text.
        (contactName.parent as? ViewGroup)?.removeView(contactName)
        (contactPhone.parent as? ViewGroup)?.removeView(contactPhone)
        stopTicker()
        val column = kit.column().apply { setPadding(dp(20), dp(20), dp(20), dp(40)) }
        val series = Mama.series(this)
        val flex = Mama.entitlements(this).flex
        val key = when {
            series != null && series.status == SeriesStatus.BROKEN -> "broken"
            series != null && series.status == SeriesStatus.COMPLETED -> "success"
            series != null -> "series"
            flex != null && flex.over -> "flexResult"
            flex != null -> "flex"
            state == LockState.Free -> "form"
            else -> "session"
        }
        header(column, hero = key == "form")
        when (key) {
            "broken" -> renderBroken(column, series!!)
            "success" -> renderSuccess(column, series!!)
            "series" -> renderSeries(column, series!!, state)
            "flexResult" -> renderFlexResult(column, flex!!)
            "flex" -> renderFlex(column, flex!!, state)
            "form" -> renderForm(column)
            else -> renderSession(column, state)
        }
        // Stay where the user was when the same screen is redrawn (e.g. after picking a mode).
        val keepY = if (key == screen) scroll?.scrollY ?: 0 else 0
        val newScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(column)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(MamaColors.BackgroundPrimary)
            addView(newScroll)
            setOnApplyWindowInsetsListener { v, insets -> padForBars(v, insets) }
        }
        setContentView(root)
        if (keepY > 0) newScroll.post { newScroll.scrollTo(0, keepY) }
        scroll = newScroll
        screen = key
    }

    /** Keeps content clear of the status bar, navigation bar and keyboard (edge-to-edge on Android 15). */
    @Suppress("DEPRECATION")
    private fun padForBars(v: View, insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            v.setPadding(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
            )
        }
        return insets
    }

    private fun header(column: LinearLayout, hero: Boolean) {
        val top = kit.row()
        top.addView(kit.text("MAMA", MamaType.WORDMARK, MamaColors.EmeraldPrimary), kit.weight())
        top.addView(kit.statusBadge(Texts.version(this), Tone.NEUTRAL))
        column.addView(top)
        if (hero) {
            column.addView(
                kit.body("Добровольная пауза от телефона на выбранное время. Спокойно, честно и без лазеек."),
                kit.gap(6),
            )
            column.addView(kit.hero(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132)).apply {
                topMargin = dp(18)
            })
        }
    }

    // --- setup form ---

    private fun renderForm(column: LinearLayout) {
        Mama.session(this)?.takeIf { it.status == SessionStatus.FINISHED }?.let {
            val wasTest = it.id == prefs.getString("testSession", null)
            val what = if (wasTest) "Тестовая блокировка" else "Прошлая блокировка"
            column.addView(kit.infoBlock("$what: ${Texts.finishReason(it.finishReason)}.", Tone.NEUTRAL), kit.gap(18))
        }
        val ent = Mama.entitlements(this)

        // 1. Mode
        column.addView(kit.sectionTitle("Выберите режим", "На какой срок ты берёшь обязательство?"))
        val cards = kit.column()
        fun add(view: View) = cards.addView(view, kit.gap(if (cards.childCount == 0) 0 else 10))
        if (FeatureFlags.TEST_MODE_ENABLED) {
            add(kit.modeCard(
                "Тестовый режим", "Бесплатно",
                "Короткий пробный запуск на 15 минут для проверки блокировки и механики приложения.",
                choice == Choice.TEST, tag = "15 минут · только для тестирования",
            ) { select(Choice.TEST) })
        }
        listOf(Choice.THREE, Choice.FIVE, Choice.SEVEN).forEach { c ->
            val kind = c.series!!
            val price = Texts.price(kind.restartPriceRub)
            add(kit.modeCard(
                Texts.seriesName(kind), price,
                "${Texts.seriesName(kind)} подряд в одно и то же время. Старт бесплатный, $price — только за Restart после срыва.",
                choice == c,
            ) { select(c) })
        }
        val freeFlex = ent.freeFlexCredits > 0
        add(kit.modeCard(
            "FLEX", if (freeFlex) "Бесплатно" else Texts.price(FlexPackage.PRICE_RUB),
            "${FlexPackage.PERIODS} периодов от 3 до 24 часов в любые дни за ${FlexPackage.VALIDITY.toDays()} дней. " +
                "Разовый пакет, не подписка. Пройди 7 из 7 — следующий FLEX бесплатно.",
            choice == Choice.FLEX, tag = if (freeFlex) "Заработан за 7 из 7" else null,
        ) { select(Choice.FLEX) })
        column.addView(cards, kit.fill())
        val waitDays = StandardRules.daysUntilFreeStart(ent, Mama.trustedNow(this))
        if (choice.series != null && waitDays > 0) {
            column.addView(kit.infoBlock(
                "Телефон при этом не заблокирован: ожидание касается только бесплатного старта новой серии.",
                Tone.WARNING,
                "Бесплатная новая серия будет доступна через ${Texts.days(waitDays.toInt())}",
            ), kit.gap(12))
        }

        // Purpose
        column.addView(kit.sectionTitle("Цель", "Для чего эта пауза"))
        val modes = SessionMode.entries
        column.addView(kit.chips(modes.map { Texts.mode(it) }, modes.indexOf(mode)) { i ->
            mode = modes[i]
            saveForm()
            render()
        }, kit.fill())

        // 2. Time
        renderTimeBlock(column)

        // 3. Trusted contact
        column.addView(kit.sectionTitle("Доверенный контакт", "Только этот человек сможет помочь выйти раньше — через код"))
        column.addView(kit.contactCard(contactName, contactPhone) { pickContact() }, kit.fill())
        column.addView(kit.infoBlock(
            "Код из ${Mama.policy.codeLength} цифр придёт ему по SMS, на ввод — ${Mama.policy.maxAttempts} попытки. " +
                "Выход по коду всегда бесплатный.",
        ), kit.gap(10))

        // 4. Permissions
        renderPermissions(column)

        // 5. Start
        column.addView(kit.brandButton(ctaLabel()) { startChosen() }, kit.gap(32))
        column.addView(kit.caption(ctaNote(), center = true), kit.gap(10))
    }

    private fun select(c: Choice) {
        choice = c
        saveForm()
        render()
    }

    private fun renderTimeBlock(column: LinearLayout) {
        when (choice) {
            Choice.TEST -> {
                column.addView(kit.sectionTitle("Время", "Тест всегда длится ровно 15 минут"))
                val now = Mama.trustedNow(this)
                val row = kit.row()
                row.addView(
                    kit.timeCard("Начало", Texts.time(now, zone), "сразу", enabled = false),
                    kit.weight().apply { rightMargin = dp(6) },
                )
                row.addView(
                    kit.timeCard("Окончание", Texts.time(now + TestMode.DURATION, zone), "через 15 минут", enabled = false),
                    kit.weight().apply { leftMargin = dp(6) },
                )
                column.addView(row, kit.fill())
                column.addView(kit.infoBlock(
                    "Блокировка начнётся сразу после запуска. Тест бесплатный, не входит в серии и не тратит FLEX-дни.",
                    Tone.BRAND,
                ), kit.gap(10))
            }
            Choice.FLEX -> {
                column.addView(kit.sectionTitle("Время", "Для каждого FLEX-периода — отдельно"))
                column.addView(kit.infoBlock(
                    "После активации пакета для каждого периода выбираются дата и время начала и окончания: " +
                        "сейчас или позже, от 3 до 24 часов, можно через полночь.",
                    Tone.BRAND,
                ), kit.fill())
                column.addView(kit.settingRow("Часовой пояс", Texts.zone(zone)) { pickZone() }, kit.gap(10))
            }
            else -> {
                column.addView(kit.sectionTitle("Время", "Каждый день серии — в это время"))
                val row = kit.row()
                row.addView(kit.timeCard("Начало", Texts.hhmm(start)) {
                    DigitalTimePicker.show(this, "Начало блокировки", start) { start = it; saveForm(); render() }
                }, kit.weight().apply { rightMargin = dp(6) })
                row.addView(kit.timeCard("Окончание", Texts.hhmm(end)) {
                    DigitalTimePicker.show(this, "Окончание блокировки", end) { end = it; saveForm(); render() }
                }, kit.weight().apply { leftMargin = dp(6) })
                column.addView(row, kit.fill())
                column.addView(kit.settingRow("Часовой пояс", Texts.zone(zone)) { pickZone() }, kit.gap(10))
                column.addView(kit.infoBlock(preview()), kit.gap(10))
            }
        }
    }

    private fun renderPermissions(column: LinearLayout) {
        column.addView(kit.sectionTitle("Разрешения", "Нужны, чтобы блокировку нельзя было обойти"))
        if (Requirement.entries.any { !it.isGranted(this) }) {
            column.addView(kit.brandButton("Выдать все разрешения") { startSetupAll() }, kit.fill())
            column.addView(
                kit.caption("MAMA по очереди откроет каждый экран настроек: включите переключатель и вернитесь назад.", center = true),
                kit.gap(8),
            )
        } else {
            column.addView(kit.infoBlock("Всё готово к строгой блокировке.", Tone.SUCCESS, "Все разрешения выданы"), kit.fill())
        }
        Requirement.entries.forEach { req ->
            val status = when {
                req.isGranted(this) -> PermissionStatus.GRANTED
                req.required -> PermissionStatus.REQUIRED
                else -> PermissionStatus.MISSING
            }
            column.addView(kit.permissionCard(req.title, req.why, status) { req.request(this) }, kit.gap(10))
        }
    }

    private fun ctaLabel() = when (choice) {
        Choice.TEST -> "Начать тест 15 минут"
        Choice.FLEX -> "Начать FLEX"
        else -> "Начать серию ${Texts.seriesName(choice.series!!)}"
    }

    private fun ctaNote() = when (choice) {
        Choice.TEST -> "Бесплатно. Выйти раньше можно по коду доверенного контакта."
        Choice.FLEX -> if (Mama.entitlements(this).freeFlexCredits > 0) {
            "Бесплатный FLEX, заработанный за 7 из 7."
        } else {
            "${Texts.price(FlexPackage.PRICE_RUB)} за пакет. Разовая покупка, без автопродления."
        }
        else -> "Старт бесплатный. Restart после срыва — ${Texts.price(choice.series!!.restartPriceRub)}."
    }

    private fun startChosen() = when (choice) {
        Choice.TEST -> confirmTest()
        Choice.FLEX -> startFlex()
        else -> confirmStart(choice.series!!)
    }

    private fun startFlex() {
        saveForm()
        if (Mama.entitlements(this).freeFlexCredits > 0) {
            activateFlex(GrantSource.REWARD)
        } else {
            Checkout.obtain(this, Product.FlexPackage, "MAMA FLEX") { activateFlex(it) }
        }
    }

    private fun activateFlex(source: GrantSource) {
        saveForm()
        if (!Mama.activateFlex(this, source)) {
            alert("Не получилось", "FLEX можно активировать, когда нет активной серии или блокировки.")
        }
        render()
    }

    /** Checks permissions and contact (and the daily window for series); null, with a message, if something is missing. */
    private fun readyContact(checkWindow: Boolean = false): TrustedContact? {
        saveForm()
        val missing = Requirement.missingRequired(this)
        if (missing.isNotEmpty()) {
            alert("Не хватает разрешений", missing.joinToString("\n") { "• ${it.title}" })
            return null
        }
        if (checkWindow && start == end) {
            alert("Проверьте время", "Начало и окончание совпадают.")
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
        if (start == end) return "Начало и окончание совпадают."
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
        return "Первая блокировка: $from → ${Texts.dayTime(p.end, zone)} (${Texts.duration(length)})$localHint"
    }

    private fun confirmTest() {
        val contact = readyContact() ?: return
        val endsAt = Mama.trustedNow(this) + TestMode.DURATION
        BrandDialog.show(
            this,
            "Начать тест на 15 минут?",
            message = "Телефон заблокируется сразу, примерно до ${Texts.time(endsAt, zone)}.\n\n" +
                "Выйти раньше можно только по коду, который получит ${contact.name}. " +
                "Тест бесплатный и ни на что не влияет: это не серия и не FLEX.",
            confirm = "Начать тест",
            cancel = "Назад",
        ) {
            when (val r = Mama.startTest(this, contact, zone, mode)) {
                is Mama.TestStart.Started -> prefs.edit().putString("testSession", r.sessionId).apply()
                Mama.TestStart.Busy -> alert("Не получилось", "Сейчас уже идёт серия, FLEX-период или блокировка.")
                is Mama.TestStart.Rejected -> alert("Не получилось", Texts.createError(r.reason))
            }
            render()
            true
        }
    }

    private fun confirmStart(kind: SeriesKind) {
        val contact = readyContact(checkWindow = true) ?: return
        val ent = Mama.entitlements(this)
        val waitDays = StandardRules.daysUntilFreeStart(ent, Mama.trustedNow(this))
        if (waitDays > 0) {
            alert(
                "Бесплатная серия пока недоступна",
                "Бесплатная новая серия будет доступна через ${Texts.days(waitDays.toInt())}. Телефон при этом не заблокирован.",
            )
            return
        }
        val restart = Texts.price(kind.restartPriceRub)
        val content = kit.column()
        listOf(
            "Серия" to Texts.seriesName(kind),
            "Каждый день" to "${Texts.hhmm(start)} → ${Texts.hhmm(end)}",
            "Часовой пояс" to zone.id,
            "Доверенный контакт" to "${contact.name}, ${contact.phone}",
        ).forEachIndexed { i, (k, v) ->
            val line = kit.row()
            line.addView(kit.caption(k), kit.weight())
            line.addView(kit.text(v, MamaType.BODY))
            content.addView(line, kit.gap(if (i == 0) 0 else 6))
        }
        content.addView(kit.infoBlock(
            "Прогресс обнулится. Restart серии — $restart. Без Restart бесплатная новая серия будет доступна через " +
                "${StandardRules.FREE_START_AFTER.toDays()} дней.",
            Tone.WARNING,
            "Если серия будет прервана",
        ), kit.gap(16))
        content.addView(kit.caption(
            "Выйти по коду доверенного контакта можно всегда, это бесплатно. Если серия завершена успешно — платить не нужно.",
        ), kit.gap(12))
        val agree = CheckBox(this).apply {
            text = "Я понимаю условия и принимаю ответственность за своё решение"
            MamaType.CAPTION.applyTo(this)
            setTextColor(MamaColors.TextPrimary)
            buttonTintList = ColorStateList.valueOf(MamaColors.EmeraldPrimary)
            setPadding(dp(6), dp(6), 0, dp(6))
        }
        content.addView(agree, kit.gap(12))
        val handle = BrandDialog.show(this, "Проверь условия", content = content, confirm = "Начать серию", cancel = "Назад") {
            when (val r = Mama.startSeries(this, kind, DailyWindow(start, end, zone), contact, mode)) {
                Mama.SeriesStart.Started -> Unit
                Mama.SeriesStart.Busy -> alert("Не получилось", "Сейчас уже идёт серия, FLEX-период или блокировка.")
                is Mama.SeriesStart.Waiting ->
                    alert("Бесплатная серия пока недоступна", "Будет доступна через ${Texts.days(r.days.toInt())}.")
            }
            render()
            true
        }
        handle.setConfirmEnabled(false)
        agree.setOnCheckedChangeListener { _, checked -> handle.setConfirmEnabled(checked) }
    }

    // --- countdown ---

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

    /** A live countdown card: until the lock starts, until it ends, or until an emergency pass ends. */
    private fun countdownCard(column: LinearLayout, z: ZoneId, upcoming: String, idle: String = "") {
        val caption = kit.text("", MamaType.OVERLINE, MamaColors.TextSecondary, center = true)
        val big = kit.text("", MamaType.DIGITS_L, MamaColors.TextPrimary, center = true)
        val sub = kit.caption("", center = true)
        column.addView(kit.card(22).apply {
            addView(caption, kit.fill())
            addView(big, kit.gap(12))
            addView(sub, kit.gap(10))
        }, kit.gap(20))
        val tick = object : Runnable {
            override fun run() {
                val now = Mama.trustedNow(this@MainActivity)
                when (val st = Mama.state(this@MainActivity)) {
                    is LockState.Waiting -> {
                        caption.text = "До начала"
                        big.text = Texts.countdown(Duration.between(now, st.startsAt))
                        sub.text = "$upcoming в ${Texts.time(st.startsAt, z)}"
                    }
                    is LockState.Locked -> {
                        caption.text = "Осталось"
                        big.text = Texts.countdown(Duration.between(now, st.endsAt))
                        sub.text = "Блокировка до ${Texts.time(st.endsAt, z)}"
                    }
                    is LockState.EmergencyPass -> {
                        caption.text = "Экстренный доступ"
                        big.text = Texts.countdown(Duration.between(now, st.until))
                        sub.text = "Блокировка вернётся в ${Texts.time(st.until, z)}"
                    }
                    LockState.Free -> {
                        caption.text = ""
                        big.text = "—"
                        sub.text = idle
                    }
                }
                tickHandler.postDelayed(this, 1_000)
            }
        }
        ticker = tick
        tick.run()
    }

    // --- single session (test mode or a plain lock) ---

    private fun renderSession(column: LinearLayout, state: LockState) {
        val session = Mama.session(this) ?: return
        val z = session.plan.zone
        val isTest = session.id == prefs.getString("testSession", null)
        column.addView(
            kit.text(if (isTest) "Тестовый режим" else "Режим «${Texts.mode(session.plan.mode)}»", MamaType.H1, center = true),
            kit.gap(28),
        )
        column.addView(
            kit.caption("${Texts.dayTime(session.plan.start, z)} → ${Texts.dayTime(session.plan.end, z)}", center = true),
            kit.gap(6),
        )
        countdownCard(column, z, "Блокировка начнётся")
        column.addView(kit.contactSummary(session.contact.name, session.contact.phone), kit.gap(12))
        if (isTest) {
            column.addView(
                kit.infoBlock("Это тест: он не входит в серии и не тратит FLEX-дни.", Tone.BRAND),
                kit.gap(12),
            )
        }
        if (state is LockState.Waiting) {
            column.addView(kit.caption(
                "До начала можно передумать. После — выйти можно только с кодом от доверенного контакта.",
                center = true,
            ), kit.gap(16))
            column.addView(kit.brandButton("Отменить до начала", ButtonKind.SECONDARY) {
                Mama.cancelBeforeStart(this)
                render()
            }, kit.gap(10))
        }
    }

    // --- series screens ---

    private fun renderSeries(column: LinearLayout, series: Series, state: LockState) {
        val n = series.kind.periods
        val day = minOf(series.completedPeriods + 1, n)
        column.addView(kit.text("Твоя серия активна", MamaType.H1, center = true), kit.gap(28))
        column.addView(kit.text("День $day из $n", MamaType.TITLE, MamaColors.TextSecondary, center = true), kit.gap(6))
        column.addView(kit.dots(n, series.completedPeriods), kit.gap(14))

        countdownCard(column, series.window.zone, "MAMA включится", idle = "Следующий период планируется…")

        val info = kit.row()
        info.addView(
            kit.stat("Каждый день", "${Texts.hhmm(series.window.start)} → ${Texts.hhmm(series.window.end)}"),
            kit.weight().apply { rightMargin = dp(6) },
        )
        info.addView(kit.stat("Серия", Texts.seriesName(series.kind)), kit.weight().apply { leftMargin = dp(6) })
        column.addView(info, kit.gap(12))
        column.addView(kit.contactSummary(series.contact.name, series.contact.phone), kit.gap(12))
        column.addView(kit.infoBlock(
            "Прогресс обнулится. Restart серии — ${Texts.price(series.kind.restartPriceRub)}, " +
                "или бесплатная новая серия через ${StandardRules.FREE_START_AFTER.toDays()} дней.",
            Tone.NEUTRAL,
            "Если серия будет прервана",
        ), kit.gap(12))

        if (state is LockState.Waiting) {
            column.addView(kit.brandButton("Прервать серию", ButtonKind.SECONDARY) { confirmBreak(series) }, kit.gap(20))
        }
    }

    private fun confirmBreak(series: Series) {
        BrandDialog.show(
            this,
            "Прервать серию?",
            message = "Пройдено ${series.completedPeriods} из ${series.kind.periods}. Прогресс обнулится.\n" +
                "Restart серии — ${Texts.price(series.kind.restartPriceRub)}, или бесплатная новая серия через " +
                "${StandardRules.FREE_START_AFTER.toDays()} дней.",
            confirm = "Прервать",
            confirmKind = ButtonKind.DANGER,
            cancel = "Продолжить",
        ) {
            Mama.cancelBeforeStart(this)
            render()
            true
        }
    }

    private fun renderBroken(column: LinearLayout, series: Series) {
        column.addView(kit.emblem("!", Tone.WARNING), kit.wrap().apply {
            topMargin = dp(28)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        column.addView(kit.text("Серия остановлена", MamaType.H1, center = true), kit.gap(16))
        column.addView(kit.body(
            "Пройдено ${series.completedPeriods} из ${Texts.days(series.kind.periods)}. " +
                "Прогресс серии обнулён. Телефон разблокирован.",
            center = true,
        ), kit.gap(8))
        column.addView(kit.card(22).apply {
            addView(kit.text("Restart серии прямо сейчас", MamaType.OVERLINE, MamaColors.TextSecondary, center = true), kit.fill())
            addView(kit.text(Texts.price(series.kind.restartPriceRub), MamaType.DIGITS, center = true), kit.gap(8))
            addView(kit.brandButton("Начать заново") {
                Checkout.obtain(this@MainActivity, Product.Restart(series.kind), "Restart серии") { source ->
                    if (!Mama.restartSeries(this@MainActivity, source)) {
                        alert("Не получилось", "Restart сейчас недоступен.")
                    }
                    render()
                }
            }, kit.gap(16))
        }, kit.gap(24))
        val days = StandardRules.daysUntilFreeStart(Mama.entitlements(this), Mama.trustedNow(this))
        if (days > 0) {
            column.addView(kit.infoBlock(
                "Без Restart бесплатная новая серия будет доступна через ${Texts.days(days.toInt())}. Телефон при этом не заблокирован.",
            ), kit.gap(12))
        }
        column.addView(kit.brandButton("Вернуться без серии", ButtonKind.SECONDARY) {
            Mama.dismissSeries(this)
            render()
        }, kit.gap(16))
    }

    private fun renderSuccess(column: LinearLayout, series: Series) {
        column.addView(kit.emblem("✓", Tone.SUCCESS), kit.wrap().apply {
            topMargin = dp(28)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        column.addView(kit.text("Серия пройдена!", MamaType.H1, center = true), kit.gap(16))
        column.addView(kit.body(
            "${series.completedPeriods} из ${series.kind.periods}: решение выдержано до конца.",
            center = true,
        ), kit.gap(8))
        column.addView(kit.brandButton("Новая серия") {
            Mama.dismissSeries(this)
            render()
        }, kit.gap(28))
    }

    // --- FLEX ---

    private fun renderFlex(column: LinearLayout, flex: FlexPackage, state: LockState) {
        val now = Mama.trustedNow(this)
        column.addView(kit.text("MAMA FLEX", MamaType.H1, center = true), kit.gap(28))
        column.addView(
            kit.text("${flex.successful} из ${FlexPackage.PERIODS} выполнено", MamaType.TITLE, MamaColors.TextSecondary, center = true),
            kit.gap(6),
        )
        column.addView(kit.dots(FlexPackage.PERIODS, flex.successful, flex.failed), kit.gap(14))
        column.addView(kit.body(
            if (flex.rewardStillPossible) {
                "Пройди 7 из 7 — следующий FLEX бесплатно"
            } else {
                "Следующий бесплатный FLEX доступен только при результате 7 из 7."
            },
            center = true,
        ), kit.gap(10))
        flex.lastBurntAt?.takeIf { Duration.between(it, now) < Duration.ofHours(18) }?.let {
            column.addView(kit.infoBlock(
                "Успешно: ${flex.successful} из ${FlexPackage.PERIODS}. Остальные дни FLEX остаются доступны.",
                Tone.WARNING,
                "Сегодняшний FLEX-день завершён досрочно.",
            ), kit.gap(12))
        }

        val stats = kit.row()
        val daysLeft = maxOf(0L, Duration.between(now, flex.expiresAt).toDays())
        listOf(
            "Осталось" to "${flex.remaining}",
            "Успешно" to "${flex.successful}",
            "Действует" to Texts.days(daysLeft.toInt()),
        ).forEachIndexed { i, (k, v) ->
            stats.addView(kit.stat(k, v), kit.weight().apply { if (i > 0) leftMargin = dp(8) })
        }
        column.addView(stats, kit.gap(16))
        column.addView(
            kit.caption("Пакет действует до ${Texts.dayTime(flex.expiresAt, zone)}. Неиспользованные дни сгорают после этого срока."),
            kit.gap(8),
        )

        if (flex.currentSessionId != null && state != LockState.Free) {
            countdownCard(column, zone, "FLEX-период начнётся")
            if (state is LockState.Waiting) {
                column.addView(kit.caption("Отмена до начала не тратит FLEX-день.", center = true), kit.gap(12))
                column.addView(kit.brandButton("Отменить этот FLEX-период", ButtonKind.SECONDARY) {
                    Mama.cancelBeforeStart(this)
                    render()
                }, kit.gap(8))
            }
        } else {
            renderFlexPlanner(column, flex)
        }
    }

    // --- FLEX period planner: explicit date + 24-hour time, never in the past ---

    private var flexStartNow = true
    private var flexStart: LocalDateTime? = null
    private var flexEnd: LocalDateTime? = null

    private fun nowLocal(): LocalDateTime =
        LocalDateTime.ofInstant(Mama.trustedNow(this), zone).truncatedTo(ChronoUnit.MINUTES)

    /** Suggested end until the user picks one; a picked end is kept exactly as chosen. */
    private fun defaultFlexEnd(start: LocalDateTime): LocalDateTime = if (flexStartNow) {
        // 3 h after the real moment, rounded up to the minute (17:12:37 → 20:13).
        LocalDateTime.ofInstant(FlexPackage.defaultEndForStartNow(Mama.trustedNow(this)), zone)
    } else {
        start.plus(FlexPackage.MIN_DURATION)
    }

    private fun renderFlexPlanner(column: LinearLayout, flex: FlexPackage) {
        val now = nowLocal()
        val today = now.toLocalDate()
        val start = if (flexStartNow) now else (flexStart ?: now.plusHours(1))
        val end = flexEnd ?: defaultFlexEnd(start)
        if (!flexStartNow && flexStart == null) flexStart = start
        val lastStartDay = maxOf(today, LocalDateTime.ofInstant(flex.expiresAt, zone).toLocalDate())

        column.addView(kit.sectionTitle("Новый FLEX-период", "От 3 до 24 часов. Можно через полночь."))
        column.addView(kit.segmented(listOf("Начать сейчас", "Запланировать"), if (flexStartNow) 0 else 1) { i ->
            flexStartNow = i == 0
            render()
        }, kit.fill())

        column.addView(kit.text("Начало", MamaType.TITLE), kit.gap(20))
        val startRow = kit.row()
        startRow.addView(kit.timeCard(
            "Дата начала", Texts.date(start.toLocalDate()), Texts.dayName(start.toLocalDate(), today),
            enabled = !flexStartNow, digits = false,
        ) {
            DateList.show(this, "Дата начала", start.toLocalDate(), today, lastStartDay) { d ->
                flexStart = LocalDateTime.of(d, start.toLocalTime())
                render()
            }
        }, kit.weight().apply { rightMargin = dp(6) })
        startRow.addView(kit.timeCard(
            "Время начала", Texts.hhmm(start.toLocalTime()), if (flexStartNow) "сейчас" else null,
            enabled = !flexStartNow,
        ) {
            DigitalTimePicker.show(this, "Время начала", start.toLocalTime()) { t ->
                flexStart = LocalDateTime.of(start.toLocalDate(), t)
                render()
            }
        }, kit.weight().apply { leftMargin = dp(6) })
        column.addView(startRow, kit.gap(8))

        column.addView(kit.text("Окончание", MamaType.TITLE), kit.gap(16))
        val endRow = kit.row()
        endRow.addView(kit.timeCard(
            "Дата окончания", Texts.date(end.toLocalDate()), Texts.dayName(end.toLocalDate(), today), digits = false,
        ) {
            DateList.show(this, "Дата окончания", end.toLocalDate(), today, lastStartDay.plusDays(1)) { d ->
                flexEnd = LocalDateTime.of(d, end.toLocalTime())
                render()
            }
        }, kit.weight().apply { rightMargin = dp(6) })
        endRow.addView(kit.timeCard("Время окончания", Texts.hhmm(end.toLocalTime())) {
            DigitalTimePicker.show(this, "Время окончания", end.toLocalTime()) { t ->
                flexEnd = LocalDateTime.of(end.toLocalDate(), t)
                render()
            }
        }, kit.weight().apply { leftMargin = dp(6) })
        column.addView(endRow, kit.gap(8))

        val length = Duration.between(start, end)
        val lengthText = if (!length.isNegative && !length.isZero) Texts.flexDuration(length) else "—"
        column.addView(kit.infoBlock("Часовой пояс: ${zone.id}", Tone.BRAND, "Продолжительность: $lengthText"), kit.gap(12))

        column.addView(kit.brandButton("Включить FLEX-период") { submitFlexPeriod() }, kit.gap(20))
        column.addView(kit.caption(
            "FLEX-день расходуется в момент начала блокировки. Доверенный контакт: ${contactName.text}.",
            center = true,
        ), kit.gap(10))
    }

    private fun submitFlexPeriod() {
        val contact = readyContact() ?: return
        val start = if (flexStartNow) null else flexStart
        val end = flexEnd ?: defaultFlexEnd(start ?: nowLocal())
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

    private fun renderFlexResult(column: LinearLayout, flex: FlexPackage) {
        val perfect = flex.earnedReward
        column.addView(kit.emblem(if (perfect) "✓" else "•", if (perfect) Tone.SUCCESS else Tone.BRAND), kit.wrap().apply {
            topMargin = dp(28)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        column.addView(kit.text("FLEX завершён", MamaType.H1, center = true), kit.gap(16))
        column.addView(kit.body("Успешно: ${flex.successful} из ${FlexPackage.PERIODS}.", center = true), kit.gap(8))
        val credits = Mama.entitlements(this).freeFlexCredits
        if (credits > 0) {
            column.addView(kit.body("Следующий FLEX — бесплатно.", center = true), kit.gap(8))
            column.addView(kit.brandButton("Активировать бесплатный FLEX") {
                Mama.dismissFlex(this)
                activateFlex(GrantSource.REWARD)
            }, kit.gap(20))
        } else {
            column.addView(
                kit.caption("Следующий бесплатный FLEX доступен только при результате 7 из 7.", center = true),
                kit.gap(8),
            )
            column.addView(kit.brandButton("Новый FLEX — ${Texts.price(FlexPackage.PRICE_RUB)}") {
                Checkout.obtain(this@MainActivity, Product.FlexPackage, "MAMA FLEX") { source ->
                    Mama.dismissFlex(this@MainActivity)
                    activateFlex(source)
                }
            }, kit.gap(20))
        }
        column.addView(kit.brandButton("На главную", ButtonKind.SECONDARY) {
            Mama.dismissFlex(this)
            render()
        }, kit.gap(12))
    }

    // --- pickers ---

    private fun pickZone() {
        val now = Instant.now()
        val zones = ZoneId.getAvailableZoneIds()
            .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .map(ZoneId::of)
            .sortedWith(compareBy<ZoneId> { it.rules.getOffset(now).totalSeconds }.thenBy { it.id })
        val system = ZoneId.systemDefault()
        val items = listOf(system) + zones.filter { it != system }
        BrandDialog.list(
            this,
            "Часовой пояс",
            items.map { z ->
                val offset = z.rules.getOffset(now).id.let { if (it == "Z") "+00:00" else it }
                z.id to "UTC$offset"
            },
            items.indexOf(zone),
        ) { i ->
            zone = items[i]
            saveForm()
            render()
        }
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

    // --- helpers ---

    private fun alert(title: String, message: String) {
        BrandDialog.show(this, title, message)
    }

    private fun dp(v: Int) = kit.dp(v)

    private companion object {
        const val PICK_CONTACT = 10
        const val SETUP_ALL = 20
    }
}
