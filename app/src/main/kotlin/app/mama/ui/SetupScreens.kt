package app.mama.ui

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.mama.billing.Checkout
import app.mama.billing.FeatureFlags
import app.mama.billing.Product
import app.mama.core.DailyWindow
import app.mama.core.FlexPackage
import app.mama.core.GrantSource
import app.mama.core.StandardRules
import app.mama.core.TestMode
import app.mama.core.TrustedContact
import app.mama.platform.Mama
import app.mama.platform.Promise
import app.mama.ui.MainActivity.Choice
import app.mama.ui.MainActivity.Step
import java.time.Duration
import java.time.LocalDateTime

// The setup wizard: Welcome → Permissions → Series → Time → Contact → Reason → Review.

/** Steps for the current choice (FLEX periods get their time later, per period). */
internal fun MainActivity.steps(): List<Step> =
    if (choice == Choice.FLEX) {
        listOf(Step.PERMISSIONS, Step.SERIES, Step.CONTACT, Step.PROMISE)
    } else {
        listOf(Step.PERMISSIONS, Step.SERIES, Step.TIME, Step.CONTACT, Step.PROMISE)
    }

internal fun MainActivity.nextStep(from: Step): Step = when (from) {
    Step.WELCOME -> Step.PERMISSIONS
    Step.REVIEW -> Step.REVIEW
    else -> steps().getOrNull(steps().indexOf(from) + 1) ?: Step.REVIEW
}

internal fun MainActivity.back() {
    step = when (step) {
        Step.WELCOME, Step.PERMISSIONS -> Step.WELCOME
        Step.REVIEW -> steps().last()
        else -> steps().getOrNull(steps().indexOf(step) - 1) ?: Step.WELCOME
    }
    render()
}

internal fun MainActivity.go(next: Step) {
    saveForm()
    step = next
    render()
}

internal fun MainActivity.wizardScreen(): Pair<View, Boolean> = when (step) {
    Step.WELCOME -> welcomeScreen()
    Step.PERMISSIONS -> permissionsScreen()
    Step.SERIES -> seriesScreen()
    Step.TIME -> timeScreen()
    Step.CONTACT -> contactScreen()
    Step.PROMISE -> promiseScreen()
    Step.REVIEW -> reviewScreen()
}

private fun MainActivity.header(s: Step): View {
    val list = steps()
    val index = list.indexOf(s)
    return kit.stepHeader(if (index >= 0) index + 1 else null, list.size) { back() }
}

// ---------------- 1. Welcome ----------------

internal fun MainActivity.welcomeScreen(): Pair<View, Boolean> {
    val k = nightKit
    val b = k.column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(k.dp(28), k.dp(72), k.dp(28), k.dp(24))
    }
    b.addView(k.text("MAMA", MamaType.WORDMARK_XL, MamaColors.OnNight, center = true).apply {
        setShadowLayer(18f, 0f, 2f, Color.argb(0x50, 0, 0, 0))
    }, k.centered())
    b.addView(k.text("Ты уже решил.\nТеперь просто\nне меняй решение.", MamaType.H2, MamaColors.OnNight, center = true).apply {
        textSize = 21f
        typeface = MamaFonts.ui(context, 500)
        setLineSpacing(0f, 1.3f)
        setShadowLayer(14f, 0f, 1f, Color.argb(0x60, 0, 0, 0))
    }, k.centered(22))
    b.addView(View(this).apply { setBackgroundColor(Color.argb(0x90, 0xF3, 0xF1, 0xEA)) }, LinearLayout.LayoutParams(k.dp(48), 2).apply {
        topMargin = k.dp(26); gravity = Gravity.CENTER_HORIZONTAL
    })
    val footer = k.column().apply {
        addView(k.primaryButton("Начать", Icon.ARROW_RIGHT) { go(Step.PERMISSIONS) }, k.fill())
        addView(k.text("Решение принято. Осталось его выдержать.", MamaType.CAPTION, MamaColors.OnNightMuted, center = true), k.gap(14))
    }
    return nightFrame(Scene.DAWN, b, footer)
}

// ---------------- 2. Permissions ----------------

internal fun MainActivity.permissionsScreen(): Pair<View, Boolean> {
    val b = body()
    b.addView(kit.sectionHeader(
        "Подготовим MAMA",
        "Чтобы блокировку нельзя было обойти, MAMA нужно несколько разрешений. Это займёт около минуты.",
    ))
    Requirement.entries.forEachIndexed { i, req ->
        val status = when {
            req.isGranted(this) -> PermissionStatus.GRANTED
            req.required -> PermissionStatus.REQUIRED
            else -> PermissionStatus.MISSING
        }
        b.addView(kit.permissionCard(req.icon, req.title, req.why, status) { req.request(this) }, kit.gap(if (i == 0) 20 else 10))
    }
    b.addView(kit.caption("Нажмите «Продолжить»: MAMA по очереди откроет нужные экраны — включите переключатель и вернитесь назад."), kit.gap(14))
    val cta = kit.primaryButton("Продолжить") {
        if (Requirement.missingRequired(this).isEmpty()) go(nextStep(Step.PERMISSIONS)) else startSetupAll()
    }
    return lightFrame(header(Step.PERMISSIONS), b, cta)
}

// ---------------- 3. Series ----------------

internal fun MainActivity.seriesScreen(): Pair<View, Boolean> {
    val b = body()
    b.addView(kit.sectionHeader("Выбери свою серию", "Сколько дней ты готов не менять своё решение?"))
    fun option(c: Choice, icon: Icon, title: String, subtitle: String, trailing: String? = null) =
        b.addView(kit.selectableCard(icon, title, subtitle, choice == c, trailing) { choice = c; saveForm(); render() }, kit.gap(10))
    b.addView(View(this), kit.gap(10))
    if (FeatureFlags.TEST_MODE_ENABLED) {
        option(Choice.TEST, Icon.FLASK, "Тестовый режим — 15 минут", "Только для тестирования. Ровно 15 минут. Бесплатно.")
    }
    option(Choice.THREE, Icon.LEAF, "3 дня", "Restart при срыве — ${Texts.price(SeriesPrices.THREE)}")
    option(Choice.FIVE, Icon.LEAF, "5 дней", "Restart при срыве — ${Texts.price(SeriesPrices.FIVE)}")
    option(Choice.SEVEN, Icon.MOUNTAIN, "7 дней", "Restart при срыве — ${Texts.price(SeriesPrices.SEVEN)}")
    val free = Mama.entitlements(this).freeFlexCredits > 0
    option(
        Choice.FLEX, Icon.INFINITY, "FLEX",
        "${FlexPackage.PERIODS} периодов за ${FlexPackage.VALIDITY.toDays()} дней. Пройди 7 из 7 — следующий FLEX бесплатно.",
        if (free) "Бесплатно" else Texts.price(FlexPackage.PRICE_RUB),
    )
    val wait = StandardRules.daysUntilFreeStart(Mama.entitlements(this), Mama.trustedNow(this))
    if (wait > 0 && choice.series != null) {
        b.addView(kit.infoCard(
            "Телефон при этом не заблокирован. Сразу — только Restart сорванной серии.",
            Icon.CALENDAR, Tone.WARNING, "Следующая бесплатная серия через ${Texts.days(wait.toInt())}",
        ), kit.gap(14))
    }
    val cta = kit.primaryButton("Далее", Icon.ARROW_RIGHT) { go(nextStep(Step.SERIES)) }
    return lightFrame(header(Step.SERIES), b, cta)
}

/** Restart prices shown before start (from the core's [app.mama.core.SeriesKind]). */
internal object SeriesPrices {
    val THREE = app.mama.core.SeriesKind.THREE.restartPriceRub
    val FIVE = app.mama.core.SeriesKind.FIVE.restartPriceRub
    val SEVEN = app.mama.core.SeriesKind.SEVEN.restartPriceRub
}

// ---------------- 4. Time ----------------

internal fun MainActivity.timeScreen(): Pair<View, Boolean> {
    val b = body()
    val now = Mama.trustedNow(this)
    val today = LocalDateTime.ofInstant(now, zone).toLocalDate()
    if (choice == Choice.TEST) {
        b.addView(kit.sectionHeader("Тестовый режим", "Блокировка начнётся сразу после запуска и продлится ровно 15 минут."))
        b.addView(kit.timeField("Начало", "Сегодня, ${Texts.date(today)}", Texts.time(now, zone), null, null, "сразу после запуска"), kit.gap(22))
        b.addView(kit.timeField("Окончание", "Сегодня, ${Texts.date(today)}", Texts.time(now + TestMode.DURATION, zone), null, null), kit.gap(12))
        b.addView(kit.hintRow(Icon.FLASK, "Только для тестирования: бесплатно, не входит в серии, не тратит FLEX-дни."), kit.gap(18))
    } else {
        b.addView(kit.sectionHeader("Выбери время", "Одно окно, которое повторяется каждый день серии."))
        val plan = DailyWindow(start, end, zone).nextPlan(now, mode, Mama.policy.minDuration)
        val firstStart = LocalDateTime.ofInstant(plan.start, zone).toLocalDate()
        val firstEnd = LocalDateTime.ofInstant(plan.end, zone).toLocalDate()
        b.addView(kit.timeField(
            "Начало", "Каждый день", Texts.hhmm(start), null,
            { DigitalTimePicker.show(this, "Начало блокировки", start) { start = it; saveForm(); render() } },
            "Первая ночь: ${Texts.dayName(firstStart, today)}, ${Texts.date(firstStart)}",
        ), kit.gap(22))
        b.addView(kit.timeField(
            "Окончание", "Каждый день", Texts.hhmm(end), null,
            { DigitalTimePicker.show(this, "Окончание блокировки", end) { end = it; saveForm(); render() } },
            "${Texts.dayName(firstEnd, today)}, ${Texts.date(firstEnd)}",
        ), kit.gap(12))
        val length = Duration.between(plan.start, plan.end)
        b.addView(kit.hintRow(Icon.CLOCK, "${Texts.hhmm(start)} → ${Texts.hhmm(end)} · каждый день · ${Texts.flexDuration(length)}"), kit.gap(18))
        b.addView(kit.row().apply {
            addView(kit.hintRow(Icon.GLOBE, "Часовой пояс: ${Texts.zone(zone)}"), kit.weight())
            addView(kit.text("Изменить", MamaType.CAPTION, MamaColors.Emerald).apply {
                typeface = MamaFonts.ui(context, 700)
                setPadding(kit.dp(10), kit.dp(6), 0, kit.dp(6))
                isClickable = true
                setOnClickListener { pickZone() }
            })
        }, kit.gap(10))
        if (start == end) b.addView(kit.infoCard("Начало и окончание совпадают.", Icon.ALERT, Tone.WARNING), kit.gap(12))
    }
    val cta = kit.primaryButton("Далее", Icon.ARROW_RIGHT) {
        if (choice != Choice.TEST && start == end) alert("Проверьте время", "Начало и окончание совпадают.") else go(nextStep(Step.TIME))
    }
    return lightFrame(header(Step.TIME), b, cta)
}

internal fun MainActivity.pickZone() {
    val now = java.time.Instant.now()
    val zones = java.time.ZoneId.getAvailableZoneIds()
        .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
        .map(java.time.ZoneId::of)
        .sortedWith(compareBy<java.time.ZoneId> { it.rules.getOffset(now).totalSeconds }.thenBy { it.id })
    val system = java.time.ZoneId.systemDefault()
    val items = listOf(system) + zones.filter { it != system }
    BrandDialog.list(this, "Часовой пояс", items.map { z ->
        z.id to "UTC" + z.rules.getOffset(now).id.let { if (it == "Z") "+00:00" else it }
    }, items.indexOf(zone)) { i ->
        zone = items[i]
        saveForm()
        render()
    }
}

// ---------------- 5. Trusted contact ----------------

internal fun MainActivity.contactScreen(): Pair<View, Boolean> {
    val b = body()
    b.addView(kit.sectionHeader("Доверенный контакт", "Только этот человек сможет подтвердить досрочный выход из активной серии."))
    val name = contactName.text.toString().trim()
    val phone = contactPhone.text.toString().trim()
    if (name.isNotEmpty() && phone.isNotEmpty() && !manualContact) {
        b.addView(kit.contactCard(name, Texts.phone(phone), null) { pickContact() }, kit.gap(22))
    } else if (!manualContact) {
        b.addView(kit.contactCard("Контакт не выбран", "Нажмите, чтобы выбрать из контактов", null) { pickContact() }, kit.gap(22))
    }
    if (manualContact) {
        b.addView(kit.text("Имя", MamaType.CAPTION, MamaColors.TextSecondary), kit.gap(22))
        b.addView(contactName, kit.gap(6))
        b.addView(kit.text("Телефон", MamaType.CAPTION, MamaColors.TextSecondary), kit.gap(14))
        b.addView(contactPhone, kit.gap(6))
    }
    b.addView(kit.infoCard(
        "Ему будет отправлен SMS с кодом из ${Mama.policy.codeLength} цифр. У вас будет максимум ${Mama.policy.maxAttempts} попытки ввода.",
    ), kit.gap(16))
    b.addView(kit.secondaryButton("Выбрать из контактов") { pickContact() }, kit.gap(22))
    b.addView(kit.textButton(if (manualContact) "Готово" else "Ввести вручную") {
        saveForm()
        manualContact = !manualContact
        render()
    }, kit.gap(4))
    val cta = kit.primaryButton("Далее", Icon.ARROW_RIGHT) {
        if (readyContact() != null) go(nextStep(Step.CONTACT))
    }
    return lightFrame(header(Step.CONTACT), b, cta)
}

/** The contact from the form, or null with a message. */
internal fun MainActivity.readyContact(): TrustedContact? {
    saveForm()
    val name = contactName.text.toString().trim()
    val phone = TrustedContact.normalizePhone(contactPhone.text.toString())
    if (name.isEmpty() || phone == null) {
        alert("Доверенный контакт", "Выберите контакт или укажите имя и номер в формате +7 900 123-45-67.")
        return null
    }
    return TrustedContact(name, phone)
}

// ---------------- 6. Personal reason ----------------

internal fun MainActivity.promiseScreen(): Pair<View, Boolean> {
    val b = body()
    b.addView(kit.sectionHeader("Зачем ты это делаешь?", "Напиши одну фразу, которую MAMA покажет тебе, когда захочется сдаться."))
    b.addView(promiseField, kit.gap(22).apply { height = kit.dp(160) })
    val counter = kit.text("", MamaType.SMALL, MamaColors.TextSecondary).apply { gravity = Gravity.END }
    fun count() { counter.text = "${promiseField.text.length} / ${Promise.MAX_LENGTH}" }
    count()
    // One watcher at a time: the field is reused across redraws.
    (promiseField.tag as? TextWatcher)?.let(promiseField::removeTextChangedListener)
    val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = count()
        override fun afterTextChanged(s: Editable?) = Unit
    }
    promiseField.addTextChangedListener(watcher)
    promiseField.tag = watcher
    b.addView(counter, kit.gap(6))
    b.addView(kit.infoCard("Эту фразу увидишь только ты: на экране досрочного выхода.", Icon.HEART, Tone.BRAND), kit.gap(14))
    val cta = kit.primaryButton("Продолжить") {
        val text = promiseField.text.toString().trim()
        if (text.length < 3) {
            alert("Одна фраза", "Напиши, зачем тебе эта пауза — хотя бы несколько слов.")
        } else {
            Promise.set(this, text)
            go(Step.REVIEW)
        }
    }
    return lightFrame(header(Step.PROMISE), b, cta)
}

// ---------------- 7. Review ----------------

internal fun MainActivity.reviewScreen(): Pair<View, Boolean> {
    val b = body()
    val contactLine = contactName.text.toString().trim()
    val phoneLine = Texts.phone(contactPhone.text.toString().trim())
    val reason = promiseField.text.toString().trim().ifEmpty { Promise.get(this) }
    val card = kit.card(18).apply { setPadding(kit.dp(18), kit.dp(6), kit.dp(18), kit.dp(6)) }
    fun line(icon: Icon, label: String, value: String, note: String? = null) {
        if (card.childCount > 0) card.addView(kit.divider())
        card.addView(kit.summaryRow(icon, label, value, note))
    }
    val ctaLabel: String
    val needsAgreement: Boolean
    when (choice) {
        Choice.TEST -> {
            b.addView(kit.sectionHeader("Проверь условия", "Если всё верно — запускаем тест."))
            line(Icon.FLASK, "Режим", "Тестовый режим", "15 минут · только для тестирования")
            line(Icon.CLOCK, "Время", "Сразу после запуска", "ровно 15 минут")
            line(Icon.PERSON, "Доверенный контакт", contactLine, phoneLine)
            line(Icon.HEART, "Моя причина", "«$reason»")
            line(Icon.INFO, "Стоимость", "Бесплатно", "Без Restart, не тратит FLEX-дни")
            ctaLabel = "Начать тест 15 минут"
            needsAgreement = false
        }
        Choice.FLEX -> {
            b.addView(kit.sectionHeader("Проверь условия", "FLEX — разовый пакет, не подписка."))
            line(Icon.INFINITY, "Пакет", "MAMA FLEX", "${FlexPackage.PERIODS} периодов за ${FlexPackage.VALIDITY.toDays()} дней")
            line(Icon.CLOCK, "Периоды", "От 3 до 24 часов", "Дата и время — для каждого периода отдельно")
            line(Icon.PERSON, "Доверенный контакт", contactLine, phoneLine)
            line(Icon.HEART, "Моя причина", "«$reason»")
            val free = Mama.entitlements(this).freeFlexCredits > 0
            line(Icon.INFO, "Стоимость", if (free) "Бесплатно" else Texts.price(FlexPackage.PRICE_RUB), "Пройди 7 из 7 — следующий FLEX бесплатно")
            ctaLabel = "Активировать FLEX"
            needsAgreement = true
        }
        else -> {
            val kind = choice.series!!
            b.addView(kit.sectionHeader("Проверь свои условия", "Если всё верно — запускаем серию."))
            line(Icon.CALENDAR, "Серия", Texts.seriesName(kind))
            line(Icon.CLOCK, "Время", "${Texts.hhmm(start)} → ${Texts.hhmm(end)}", "каждый день · ${zone.id}")
            line(Icon.PERSON, "Доверенный контакт", contactLine, phoneLine)
            line(Icon.HEART, "Моя причина", "«$reason»")
            line(Icon.REFRESH, "Restart при срыве", Texts.price(kind.restartPriceRub), "Старт серии бесплатный")
            ctaLabel = "Начать ${Texts.seriesName(kind)}"
            needsAgreement = true
        }
    }
    b.addView(card, kit.gap(20))
    if (choice.series != null) {
        b.addView(kit.infoCard(
            "Прогресс обнулится. Сразу начать заново — Restart за ${Texts.price(choice.series!!.restartPriceRub)}, " +
                "бесплатная новая серия — через ${StandardRules.FREE_START_AFTER.toDays()} дней. Выход по коду доверенного контакта всегда бесплатный.",
            Icon.ALERT, Tone.WARNING, "Если серия будет прервана",
        ), kit.gap(14))
    }
    val cta = kit.primaryButton(ctaLabel) { startChosen() }
    if (needsAgreement) {
        b.addView(kit.checkRow("Я понимаю условия и принимаю ответственность за своё решение.", agreed) { agreed = it; render() }, kit.gap(18))
        cta.isEnabled = agreed
    }
    return lightFrame(kit.stepHeader(null, 0) { back() }, b, cta)
}

private fun MainActivity.startChosen() {
    val contact = readyContact() ?: return
    when (choice) {
        Choice.TEST -> when (val r = Mama.startTest(this, contact, zone, mode)) {
            is Mama.TestStart.Started -> prefs.edit().putString("testSession", r.sessionId).apply()
            Mama.TestStart.Busy -> alert("Не получилось", "Сейчас уже идёт серия, FLEX-период или блокировка.")
            is Mama.TestStart.Rejected -> alert("Не получилось", Texts.createError(r.reason))
        }
        Choice.FLEX -> {
            if (Mama.entitlements(this).freeFlexCredits > 0) {
                activateFlex(GrantSource.REWARD)
            } else {
                Checkout.obtain(this, Product.FlexPackage, "MAMA FLEX") { activateFlex(it) }
            }
            return
        }
        else -> when (val r = Mama.startSeries(this, choice.series!!, DailyWindow(start, end, zone), contact, mode)) {
            Mama.SeriesStart.Started -> Unit
            Mama.SeriesStart.Busy -> alert("Не получилось", "Сейчас уже идёт серия, FLEX-период или блокировка.")
            is Mama.SeriesStart.Waiting -> alert(
                "Бесплатная серия пока недоступна",
                "Следующая бесплатная серия через ${Texts.days(r.days.toInt())}. Телефон при этом не заблокирован.",
            )
        }
    }
    agreed = false
    step = Step.WELCOME
    tab = 0
    render()
}

internal fun MainActivity.activateFlex(source: GrantSource) {
    saveForm()
    if (!Mama.activateFlex(this, source)) {
        alert("Не получилось", "FLEX можно активировать, когда нет активной серии или блокировки.")
    } else {
        agreed = false
        step = Step.WELCOME
        tab = 0
    }
    render()
}
