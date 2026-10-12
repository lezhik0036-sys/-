package app.mama.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.mama.billing.Checkout
import app.mama.billing.FeatureFlags
import app.mama.billing.Product
import app.mama.core.FlexEngine
import app.mama.core.FlexPackage
import app.mama.core.GrantSource
import app.mama.core.LockState
import app.mama.core.Commitment
import app.mama.core.CommitmentState
import app.mama.core.RestartHold
import app.mama.core.RestartHoldState
import app.mama.core.Series
import app.mama.core.SeriesStatus
import app.mama.core.Session
import app.mama.core.StandardRules
import app.mama.platform.Mama
import app.mama.platform.Protection
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

// Dashboards (night), statistics/settings tabs, failure, success, FLEX.

/** Top bar of night dashboards: MAMA wordmark, settings gear (reference screen 7). */
private fun MainActivity.nightTop(): View = FrameLayout(this).apply {
    val k = nightKit
    addView(k.text("MAMA", MamaType.WORDMARK, MamaColors.OnNight), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    addView(FrameLayout(context).apply {
        background = k.pressable(k.oval(android.graphics.Color.TRANSPARENT), 22)
        addView(k.icon(Icon.GEAR, MamaColors.OnNight, 24), FrameLayout.LayoutParams(k.dp(24), k.dp(24), Gravity.CENTER))
        isClickable = true
        contentDescription = "Настройки"
        setOnClickListener { tab = 2; render() }
    }, FrameLayout.LayoutParams(k.dp(44), k.dp(44), Gravity.END or Gravity.CENTER_VERTICAL))
}

private fun MainActivity.nav() = kit.bottomNav(tab) { tab = it; editSaved = false; render() }

/**
 * Countdown card: caption, then HH : MM : SS with ЧАСОВ / МИНУТ / СЕКУНД
 * labels. Re-reads [state] every second; when the state kind changes
 * (waiting → locked …) the whole screen is redrawn.
 */
private fun MainActivity.countdownCard(b: LinearLayout, state: () -> LockState, waiting: String) =
    countdownCard(b, state) { waiting }

private fun MainActivity.countdownCard(b: LinearLayout, state: () -> LockState, waiting: (LockState.Waiting) -> String) {
    val k = nightKit
    val caption = k.text("", MamaType.CAPTION, MamaColors.OnNightMuted, center = true)
    val digits = listOf(
        k.text("00", MamaType.DIGITS_L, MamaColors.OnNight, center = true),
        k.text("00", MamaType.DIGITS_L, MamaColors.OnNight, center = true),
        k.text("00", MamaType.DIGITS_L, MamaColors.OnNight, center = true),
    )
    val card = k.card(18, 20)
    card.addView(caption, k.fill())
    val labels = k.row()
    val nums = k.row().apply { gravity = Gravity.CENTER }
    listOf("ЧАСОВ", "МИНУТ", "СЕКУНД").forEachIndexed { i, l ->
        labels.addView(k.text(l, MamaType.OVERLINE, MamaColors.OnNightMuted, center = true).apply { gravity = Gravity.CENTER }, k.weight())
        if (i > 0) nums.addView(k.text(":", MamaType.DIGITS_L, MamaColors.OnNightMuted).apply { gravity = Gravity.CENTER }, k.wrap())
        nums.addView(digits[i].apply { gravity = Gravity.CENTER }, k.weight())
    }
    card.addView(labels, k.gap(14))
    card.addView(nums, k.gap(6))
    b.addView(card, k.gap(22))
    var shown: Class<*>? = null
    startTicker {
        val now = Mama.trustedNow(this)
        val st = state()
        if (shown != null && shown != st.javaClass) {
            render()
            return@startTicker
        }
        shown = st.javaClass
        val (target, text) = when (st) {
            is LockState.Waiting -> st.startsAt to waiting(st)
            is LockState.Locked -> st.endsAt to "Блокировка идёт. До окончания"
            is LockState.EmergencyPass -> st.until to "Экстренный доступ. Блокировка вернётся через"
            LockState.Free -> null to "Следующий период планируется…"
        }
        caption.text = text
        val (h, m, s) = Texts.hms(if (target != null) Duration.between(now, target) else Duration.ZERO)
        digits[0].text = h; digits[1].text = m; digits[2].text = s
    }
}

private fun MainActivity.infoTiles(b: LinearLayout, vararg tiles: Triple<Icon, String, String>) {
    val k = nightKit
    val row = k.row().apply { background = k.shape(MamaColors.NightGlass, 18, MamaColors.NightLine) }
    tiles.forEachIndexed { i, (icon, label, value) ->
        if (i > 0) row.addView(View(this).apply { setBackgroundColor(MamaColors.NightLine) }, LinearLayout.LayoutParams(1, k.dp(36)))
        row.addView(k.miniTile(icon, label, value), k.weight())
    }
    b.addView(row, k.gap(12))
}

// ---------------- 8. Series dashboard: scheduled / activating / active ----------------

internal fun MainActivity.seriesDashboard(
    series: Series,
    sampleCommitment: Commitment? = null,
    sampleRestart: RestartHold? = null,
    protectionMissing: List<Requirement> = Protection.missing(this),
    state: () -> LockState,
): Pair<View, Boolean> {
    if (series.status == SeriesStatus.NEEDS_ATTENTION) return needsAttentionScreen(series, sampleCommitment, sampleRestart, protectionMissing)
    val k = nightKit
    val b = body(8)
    b.addView(nightTop(), LinearLayout.LayoutParams(-1, k.dp(52)))
    val n = series.kind.periods
    val title = when (series.status) {
        SeriesStatus.SCHEDULED -> "Серия запланирована"
        SeriesStatus.ACTIVATING -> "MAMA включает защиту"
        else -> "Твоя серия активна"
    }
    b.addView(k.text(title, MamaType.H1, MamaColors.OnNight, center = true), k.gap(18))
    val subtitle = when {
        series.committed -> Texts.dayOf(minOf(series.completedPeriods + 1, n), n)
        series.isRestart -> "Restart: серия начнётся с первой блокировкой"
        else -> "Серия начнётся с первой блокировкой"
    }
    b.addView(k.text(subtitle, MamaType.BODY, MamaColors.OnNightMuted, center = true), k.gap(6))
    b.addView(k.dots(n, series.completedPeriods), k.gap(14))
    if (editSaved && series.status == SeriesStatus.SCHEDULED) {
        b.addView(k.infoCard("Время и длина серии остались прежними.", Icon.CHECK, Tone.SUCCESS, "Изменения сохранены"), k.gap(14))
    }
    if (series.status == SeriesStatus.SCHEDULED && protectionMissing.isNotEmpty()) {
        b.addView(k.infoCard(
            missingList(protectionMissing) + "\nБез этого серия не начнётся. Включи до начала блокировки.",
            Icon.ALERT, Tone.WARNING, "Защита не готова",
        ), k.gap(14))
        b.addView(k.secondaryButton("Исправить защиту") { startSetupAll(Protection.strict) }, k.gap(10))
    }
    val zone = series.window.zone
    countdownCard(b, state) { w ->
        val now = Mama.trustedNow(this)
        when (w.startsAt.atZone(zone).toLocalDate()) {
            now.atZone(zone).toLocalDate() -> "Сегодня MAMA включится через"
            now.atZone(zone).toLocalDate().plusDays(1) -> "MAMA включится завтра в ${Texts.time(w.startsAt, zone)}. Осталось"
            else -> "MAMA включится через"
        }
    }
    seriesParameters(b, series, sampleCommitment, sampleRestart)
    when (series.status) {
        SeriesStatus.SCHEDULED -> {
            b.addView(k.primaryButton("Изменить условия") { beginEditing(series) }, k.gap(18))
            b.addView(k.textButton("Отменить запуск") { confirmCancelLaunch(series) }, k.gap(4))
        }
        SeriesStatus.ACTIVATING -> {
            b.addView(k.caption("Проверяем строгую защиту. Это займёт несколько секунд.", center = true), k.gap(14))
            b.addView(k.textButton("Отменить запуск") { confirmCancelLaunch(series) }, k.gap(4))
        }
        else -> b.addView(k.caption("Условия серии зафиксированы. Досрочный выход — только через доверенный контакт.", center = true), k.gap(14))
    }
    b.addView(k.caption("Окно ${Texts.hhmm(series.window.start)} → ${Texts.hhmm(series.window.end)} каждый день · ${series.window.zone.id}", center = true), k.gap(8))
    return nightFrame(Scene.NIGHT, b, null, nav())
}

/** Time, length, contact, "Цена слова" and a held Restart of a series. */
private fun MainActivity.seriesParameters(b: LinearLayout, series: Series, sampleCommitment: Commitment?, sampleRestart: RestartHold?) {
    val k = nightKit
    infoTiles(
        b,
        Triple(Icon.CALENDAR, "Начало", Texts.hhmm(series.window.start)),
        Triple(Icon.MOON, "Окончание", Texts.hhmm(series.window.end)),
        Triple(Icon.REFRESH, "Серия", Texts.seriesName(series.kind)),
    )
    b.addView(k.contactCard(series.contact.name, Texts.phone(series.contact.phone), "Доверенный контакт", null), k.gap(12))
    val wallet = Mama.wallet(this)
    val commitment = sampleCommitment ?: wallet.commitmentFor(series.id)
    when {
        commitment != null && commitment.state == CommitmentState.SCHEDULED -> b.addView(k.infoCard(
            "Зарезервировано до начала.\nМожно изменить до первой блокировки.",
            Icon.SHIELD, Tone.NEUTRAL, "Цена слова: ${Texts.money(commitment.amountKop)}",
        ), k.gap(12))
        commitment != null && commitment.state == CommitmentState.RESERVED -> b.addView(k.infoCard(
            "В резерве до конца серии. Пройдёшь серию — сумма снова будет доступна на балансе MAMA.",
            Icon.SHIELD, Tone.NEUTRAL, "Цена слова: ${Texts.money(commitment.amountKop)}",
        ), k.gap(12))
        else -> if (FeatureFlags.WALLET_ENABLED && !series.committed) {
            b.addView(k.infoCard("Без цены слова", Icon.SHIELD, Tone.NEUTRAL, "Цена слова"), k.gap(12))
        }
    }
    val hold = sampleRestart ?: wallet.restartHoldFor(series.id)
    if (hold != null && hold.state == RestartHoldState.HELD) {
        b.addView(k.infoCard(
            "Зарезервирован до начала серии. Если отменить запуск до первой блокировки, вернётся на баланс MAMA.",
            Icon.REFRESH, Tone.NEUTRAL, "Restart: ${Texts.money(hold.amountKop)}",
        ), k.gap(12))
    }
}

private fun missingList(missing: List<Requirement>) = missing.joinToString("\n") { "• ${it.title}" }

// ---------------- NEEDS_ATTENTION: the start time came, protection could not be switched on ----------------

private fun MainActivity.needsAttentionScreen(
    series: Series,
    sampleCommitment: Commitment?,
    sampleRestart: RestartHold?,
    missing: List<Requirement>,
): Pair<View, Boolean> {
    val k = nightKit
    val b = body(8)
    b.addView(nightTop(), LinearLayout.LayoutParams(-1, k.dp(52)))
    b.addView(k.text("Серия не началась", MamaType.H1, MamaColors.OnNight, center = true), k.gap(18))
    b.addView(k.text("MAMA не смогла включить защиту.", MamaType.BODY, MamaColors.OnNight, center = true), k.gap(8))
    b.addView(k.caption(
        "Поэтому день не засчитан, ничего не списано и ничего не потеряно. Блокировка не включится сама — только после того, как ты подтвердишь новый запуск.",
        center = true,
    ), k.gap(8))
    if (missing.isNotEmpty()) {
        b.addView(k.infoCard(missingList(missing), Icon.ALERT, Tone.WARNING, "Не работает защита"), k.gap(18))
        b.addView(k.primaryButton("Исправить защиту") { startSetupAll(Protection.strict) }, k.gap(14))
    } else {
        val now = Mama.trustedNow(this)
        val first = series.window.nextFullPlan(now, series.mode)
        b.addView(k.infoCard(
            "Подтверди новый запуск. Первая блокировка: ${Texts.firstLock(first.start, series.window.zone, now)}.",
            Icon.CHECK, Tone.SUCCESS, "Защита готова",
        ), k.gap(18))
        b.addView(k.primaryButton("Подтвердить запуск") { relaunch() }, k.gap(14))
    }
    b.addView(k.secondaryButton("Изменить условия") { beginEditing(series) }, k.gap(10))
    b.addView(k.textButton("Отменить запуск") { confirmCancelLaunch(series) }, k.gap(4))
    seriesParameters(b, series, sampleCommitment, sampleRestart)
    return nightFrame(Scene.NIGHT, b, null, nav())
}

private fun MainActivity.relaunch() {
    when (val r = Mama.relaunchSeries(this)) {
        Mama.SeriesEdit.Saved -> android.widget.Toast.makeText(this, "Серия запланирована", android.widget.Toast.LENGTH_SHORT).show()
        is Mama.SeriesEdit.ProtectionMissing -> alert("Сначала исправь защиту", missingList(r.missing))
        else -> alert("Не получилось", "Запланировать серию сейчас нельзя.")
    }
    render()
}

// ---------------- test mode / single session ----------------

internal fun MainActivity.sessionDashboard(session: Session, state: () -> LockState): Pair<View, Boolean> {
    val k = nightKit
    val b = body(8)
    b.addView(nightTop(), LinearLayout.LayoutParams(-1, k.dp(52)))
    val isTest = Duration.between(session.plan.start, session.plan.end) == app.mama.core.TestMode.DURATION
    b.addView(k.text(if (isTest) "Тестовый режим" else "Блокировка", MamaType.H1, MamaColors.OnNight, center = true), k.gap(18))
    b.addView(k.text(if (isTest) "15 минут · только для тестирования" else Texts.mode(session.plan.mode), MamaType.BODY, MamaColors.OnNightMuted, center = true), k.gap(6))
    countdownCard(b, state, "Блокировка начнётся через")
    val z = session.plan.zone
    infoTiles(
        b,
        Triple(Icon.CALENDAR, "Начало", Texts.time(session.plan.start, z)),
        Triple(Icon.MOON, "Окончание", Texts.time(session.plan.end, z)),
        Triple(Icon.FLASK, "Режим", if (isTest) "Тест" else "—"),
    )
    b.addView(k.contactCard(session.contact.name, Texts.phone(session.contact.phone), "Доверенный контакт", null), k.gap(12))
    if (state() is LockState.Waiting) {
        b.addView(k.textButton("Отменить до начала") { Mama.cancelBeforeStart(this); render() }, k.gap(10))
    }
    return nightFrame(Scene.NIGHT, b, null, nav())
}

// ---------------- tabs ----------------

internal fun MainActivity.statisticsScreen(): Pair<View, Boolean> {
    val b = body(30)
    b.addView(kit.sectionHeader("Статистика"))
    val empty = kit.column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(kit.dp(24), kit.dp(56), kit.dp(24), kit.dp(56))
        addView(kit.iconCircle(Icon.CHART, 72), kit.centered())
        addView(kit.text("Статистика появится здесь", MamaType.H2, MamaColors.Graphite, center = true), kit.centered(20))
        addView(kit.caption("Скоро здесь будут пройденные серии и FLEX-дни.", center = true), kit.centered(8))
    }
    b.addView(empty, kit.gap(30))
    return lightFrame(null, b, null, nav())
}

internal fun MainActivity.settingsScreen(): Pair<View, Boolean> {
    val b = body(30)
    b.addView(kit.sectionHeader("Настройки"))
    if (FeatureFlags.WALLET_ENABLED) {
        val wallet = Mama.wallet(this)
        b.addView(balanceCard(wallet), kit.gap(18))
        b.addView(kit.text("История баланса", MamaType.TITLE), kit.gap(18))
        b.addView(ledgerList(wallet, zone), kit.gap(10))
    }
    b.addView(kit.text("Разрешения", MamaType.TITLE), kit.gap(24))
    b.addView(kit.caption("Без них блокировку можно обойти."), kit.gap(4))
    Requirement.entries.forEachIndexed { i, req ->
        val status = when {
            req.isGranted(this) -> PermissionStatus.GRANTED
            req.required -> PermissionStatus.REQUIRED
            else -> PermissionStatus.MISSING
        }
        b.addView(kit.permissionCard(req.icon, req.title, req.why, status) { req.request(this) }, kit.gap(if (i == 0) 20 else 10))
    }
    if (FeatureFlags.TEST_MODE_ENABLED) {
        b.addView(kit.infoCard("Тестовая версия: оплата не подключена и не проводится.", Icon.FLASK), kit.gap(18))
    }
    b.addView(kit.caption("MAMA ${Texts.version(this)}", center = true), kit.gap(14))
    return lightFrame(null, b, null, nav())
}

// ---------------- 11. Failure / Restart ----------------

internal fun MainActivity.failureScreen(
    series: Series,
    freeInDays: Long = StandardRules.daysUntilFreeStart(Mama.entitlements(this), Mama.trustedNow(this)),
    sampleCommitment: app.mama.core.Commitment? = null,
): Pair<View, Boolean> {
    val k = nightKit
    val b = body(40).apply { gravity = Gravity.CENTER_HORIZONTAL }
    b.addView(k.emblem(Icon.ALERT, MamaColors.Warning))
    b.addView(k.text("Серия завершена досрочно", MamaType.H1, MamaColors.OnNight, center = true), k.gap(20))
    b.addView(k.text("Ты прошёл ${series.completedPeriods} из ${Texts.days(series.kind.periods)}.", MamaType.BODY, MamaColors.OnNight, center = true), k.gap(10))
    b.addView(k.text("Так бывает. Это не поражение —\nтелефон уже разблокирован.", MamaType.CAPTION, MamaColors.OnNightMuted, center = true), k.gap(6))

    val card = kit.card(18).apply { background = kit.shape(MamaColors.PureCard, 20) }
    val head = kit.row()
    head.addView(kit.iconCircle(Icon.REFRESH, 48, MamaColors.DangerSoft, MamaColors.Warning))
    val t = kit.column()
    t.addView(kit.text("Restart серии", MamaType.TITLE))
    t.addView(kit.text(Texts.price(series.kind.restartPriceRub), MamaType.H1), kit.gap(2))
    t.addView(kit.text("Та же серия, ${Texts.seriesName(series.kind)}, сначала. Время выберешь заново.", MamaType.SMALL, MamaColors.TextSecondary), kit.gap(2))
    head.addView(t, kit.weight().apply { leftMargin = kit.dp(14) })
    card.addView(head)
    card.addView(kit.primaryButton("Начать заново") {
        // A new launch: time first (a new draft from the current time), then "Цена слова", then payment.
        beginRestart(series.kind)
        render()
    }, kit.gap(16))
    b.addView(card, k.gap(26))
    commitmentOutcome(series.id, night = true, zone = series.window.zone, sample = sampleCommitment)?.let { b.addView(it, k.gap(12)) }

    val days = freeInDays
    if (days > 0) {
        b.addView(k.infoCard("Без Restart — бесплатно, телефон при этом не заблокирован.", Icon.CALENDAR, Tone.NEUTRAL, "Следующая бесплатная серия через ${Texts.days(days.toInt())}"), k.gap(12))
    }
    val footer = k.secondaryButton("Вернуться на главную") { closeDraft(); Mama.dismissSeries(this); render() }
    return nightFrame(Scene.DUSK, b, footer)
}

// ---------------- 12. Success ----------------

internal fun MainActivity.successScreen(series: Series, sampleCommitment: app.mama.core.Commitment? = null): Pair<View, Boolean> {
    val k = nightKit
    val b = body(64).apply { gravity = Gravity.CENTER_HORIZONTAL }
    b.addView(k.emblem(Icon.CHECK, MamaColors.Emerald, 88))
    b.addView(k.text("Серия пройдена!", MamaType.DISPLAY, MamaColors.Night, center = true), k.gap(22))
    b.addView(k.text("${series.completedPeriods} из ${series.kind.periods} — решение выдержано до конца.", MamaType.BODY, MamaColors.Graphite, center = true), k.gap(10))
    b.addView(kit.dots(series.kind.periods, series.completedPeriods), k.gap(18))
    commitmentOutcome(series.id, night = false, zone = series.window.zone, sample = sampleCommitment)?.let { b.addView(it, k.gap(18)) }
    b.addView(k.text("Платить не нужно: Restart бывает только после срыва.", MamaType.CAPTION, MamaColors.TextSecondary, center = true), k.gap(18))
    val footer = k.column().apply {
        addView(k.primaryButton("Новая серия", Icon.ARROW_RIGHT) { closeDraft(); Mama.dismissSeries(this@successScreen); render() }, k.fill())
    }
    return nightFrame(Scene.DAWN, b, footer)
}

// ---------------- FLEX ----------------

internal fun MainActivity.flexDashboard(flex: FlexPackage, state: () -> LockState): Pair<View, Boolean> {
    val k = nightKit
    val now = Mama.trustedNow(this)
    val b = body(8)
    b.addView(nightTop(), LinearLayout.LayoutParams(-1, k.dp(52)))
    b.addView(k.text("MAMA FLEX", MamaType.H1, MamaColors.OnNight, center = true), k.gap(18))
    b.addView(k.text("${flex.successful} из ${FlexPackage.PERIODS} выполнено", MamaType.BODY, MamaColors.OnNightMuted, center = true), k.gap(6))
    b.addView(k.dots(FlexPackage.PERIODS, flex.successful, flex.failed), k.gap(14))
    b.addView(k.infoCard(
        if (flex.rewardStillPossible) "Каждый успешный период приближает к бесплатному пакету." else "Следующий бесплатный FLEX доступен только при результате 7 из 7.",
        Icon.INFINITY, Tone.NEUTRAL,
        if (flex.rewardStillPossible) "Пройди 7 из 7 — следующий FLEX бесплатно" else "Успешно: ${flex.successful} из ${FlexPackage.PERIODS}",
    ), k.gap(18))
    flex.lastBurntAt?.takeIf { Duration.between(it, now) < Duration.ofHours(18) }?.let {
        b.addView(k.infoCard(
            "Успешно: ${flex.successful} из ${FlexPackage.PERIODS}. Остальные дни FLEX остаются доступны.",
            Icon.ALERT, Tone.NEUTRAL, "Сегодняшний FLEX-день завершён досрочно.",
        ), k.gap(10))
    }
    val daysLeft = maxOf(0L, Duration.between(now, flex.expiresAt).toDays())
    infoTiles(
        b,
        Triple(Icon.INFINITY, "Осталось", "${flex.remaining} из ${FlexPackage.PERIODS}"),
        Triple(Icon.CHECK, "Успешно", "${flex.successful}"),
        Triple(Icon.CALENDAR, "Действует", Texts.days(daysLeft.toInt())),
    )
    if (flex.currentSessionId != null && state() != LockState.Free) {
        countdownCard(b, state, "FLEX-период начнётся через")
        if (state() is LockState.Waiting) {
            b.addView(k.textButton("Отменить период (день не тратится)") { Mama.cancelBeforeStart(this); render() }, k.gap(8))
        }
    } else {
        b.addView(k.primaryButton("Запланировать FLEX-период", Icon.ARROW_RIGHT) { flexPlanning = true; render() }, k.gap(22))
    }
    b.addView(k.caption("Пакет действует до ${Texts.dayTime(flex.expiresAt, zone)}. Неиспользованные дни сгорают.", center = true), k.gap(12))
    return nightFrame(Scene.NIGHT, b, null, nav())
}

/** FLEX period planner: explicit dates and 24-hour times (reference screen 4). */
internal fun MainActivity.flexPlannerScreen(flex: FlexPackage): Pair<View, Boolean> {
    val now = LocalDateTime.ofInstant(Mama.trustedNow(this), zone).truncatedTo(ChronoUnit.MINUTES)
    val today = now.toLocalDate()
    val start = if (flexStartNow) now else (flexStart ?: now.plusHours(1))
    val end = flexEnd ?: defaultFlexEnd(start)
    if (!flexStartNow && flexStart == null) flexStart = start
    val lastStartDay = maxOf(today, LocalDateTime.ofInstant(flex.expiresAt, zone).toLocalDate())

    val b = body()
    b.addView(kit.sectionHeader("Выбери время", "Укажи точные дату и время начала и окончания FLEX-периода."))
    b.addView(kit.segmented(listOf("Начать сейчас", "Запланировать"), if (flexStartNow) 0 else 1) { i ->
        flexStartNow = i == 0
        render()
    }, kit.gap(20))
    b.addView(kit.timeField(
        "Начало", "${Texts.dayName(start.toLocalDate(), today)}, ${Texts.date(start.toLocalDate())}", Texts.hhmm(start.toLocalTime()),
        if (flexStartNow) null else ({
            DateList.show(this, "Дата начала", start.toLocalDate(), today, lastStartDay) { d -> flexStart = LocalDateTime.of(d, start.toLocalTime()); render() }
        }),
        if (flexStartNow) null else ({
            DigitalTimePicker.show(this, "Время начала", start.toLocalTime()) { t -> flexStart = LocalDateTime.of(start.toLocalDate(), t); render() }
        }),
        if (flexStartNow) "сейчас" else null,
    ), kit.gap(16))
    b.addView(kit.timeField(
        "Окончание", "${Texts.dayName(end.toLocalDate(), today)}, ${Texts.date(end.toLocalDate())}", Texts.hhmm(end.toLocalTime()),
        { DateList.show(this, "Дата окончания", end.toLocalDate(), today, lastStartDay.plusDays(1)) { d -> flexEnd = LocalDateTime.of(d, end.toLocalTime()); render() } },
        { DigitalTimePicker.show(this, "Время окончания", end.toLocalTime()) { t -> flexEnd = LocalDateTime.of(end.toLocalDate(), t); render() } },
    ), kit.gap(12))
    val length = Duration.between(start, end)
    b.addView(kit.hintRow(Icon.CLOCK, "Продолжительность: " + if (!length.isNegative && !length.isZero) Texts.flexDuration(length) else "—"), kit.gap(18))
    b.addView(kit.hintRow(Icon.INFO, "Минимальная длительность FLEX — 3 часа, максимальная — 24 часа."), kit.gap(10))
    b.addView(kit.hintRow(Icon.INFO, "Время начала не может быть в прошлом. FLEX-день расходуется в момент начала."), kit.gap(10))
    b.addView(kit.hintRow(Icon.GLOBE, "Часовой пояс: ${zone.id}"), kit.gap(10))
    val cta = kit.primaryButton(if (flexStartNow) "Начать FLEX-период" else "Запланировать") { submitFlexPeriod() }
    return lightFrame(kit.stepHeader(null, 0) { flexPlanning = false; render() }, b, cta)
}

private fun MainActivity.defaultFlexEnd(start: LocalDateTime): LocalDateTime = if (flexStartNow) {
    // 3 h after the real moment, rounded up to the minute (17:12:37 → 20:13).
    LocalDateTime.ofInstant(FlexPackage.defaultEndForStartNow(Mama.trustedNow(this)), zone)
} else {
    start.plus(FlexPackage.MIN_DURATION)
}

private fun MainActivity.submitFlexPeriod() {
    val contact = readyContact() ?: return
    val start = if (flexStartNow) null else flexStart
    val now = LocalDateTime.ofInstant(Mama.trustedNow(this), zone).truncatedTo(ChronoUnit.MINUTES)
    val end = flexEnd ?: defaultFlexEnd(start ?: now)
    when (val r = Mama.startFlexPeriod(this, start?.atZone(zone)?.toInstant(), end.atZone(zone).toInstant(), zone, contact, mode)) {
        is FlexEngine.PlanResult.Planned -> {
            flexStart = null
            flexEnd = null
            flexStartNow = true
            flexPlanning = false
        }
        is FlexEngine.PlanResult.Rejected -> alert("FLEX-период", Texts.flexPlanError(r.error))
    }
    render()
}

internal fun MainActivity.flexResultScreen(flex: FlexPackage): Pair<View, Boolean> {
    val k = nightKit
    val perfect = flex.earnedReward
    val b = body(56).apply { gravity = Gravity.CENTER_HORIZONTAL }
    b.addView(k.emblem(if (perfect) Icon.CHECK else Icon.INFINITY, MamaColors.Emerald, 84))
    b.addView(k.text("FLEX завершён", MamaType.DISPLAY, MamaColors.Night, center = true), k.gap(22))
    b.addView(k.text("Успешно: ${flex.successful} из ${FlexPackage.PERIODS}", MamaType.BODY, MamaColors.Graphite, center = true), k.gap(10))
    b.addView(kit.dots(FlexPackage.PERIODS, flex.successful, flex.used - flex.successful), k.gap(16))
    val credits = Mama.entitlements(this).freeFlexCredits
    val footer = k.column()
    if (credits > 0) {
        b.addView(k.text("Следующий FLEX — бесплатно.", MamaType.TITLE, MamaColors.Emerald, center = true), k.gap(18))
        footer.addView(k.primaryButton("Активировать бесплатный FLEX") { Mama.dismissFlex(this); activateFlex(GrantSource.REWARD) }, k.fill())
    } else {
        b.addView(k.text("Следующий бесплатный FLEX доступен только при результате 7 из 7.", MamaType.CAPTION, MamaColors.TextSecondary, center = true), k.gap(18))
        footer.addView(k.primaryButton("Новый FLEX — ${Texts.price(FlexPackage.PRICE_RUB)}") {
            Checkout.obtain(this, Product.FlexPackage, "MAMA FLEX") { source -> Mama.dismissFlex(this); activateFlex(source) }
        }, k.fill())
    }
    footer.addView(k.secondaryButton("На главную") { Mama.dismissFlex(this); render() }, k.gap(10))
    return nightFrame(Scene.DAWN, b, footer)
}
