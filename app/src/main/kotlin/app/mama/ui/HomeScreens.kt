package app.mama.ui

import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ScrollView
import app.mama.billing.Checkout
import app.mama.billing.FeatureFlags
import app.mama.billing.Product
import app.mama.core.FlexEngine
import app.mama.core.FlexPackage
import app.mama.core.GrantSource
import app.mama.core.HistoryRecord
import app.mama.core.HistoryResult
import app.mama.core.HistoryType
import app.mama.core.LockState
import app.mama.core.Commitment
import app.mama.core.CommitmentState
import app.mama.core.RestartHold
import app.mama.core.RestartHoldState
import app.mama.core.Series
import app.mama.core.SeriesStatus
import app.mama.core.Session
import app.mama.core.StandardRules
import app.mama.motivation.MotivationRotation
import app.mama.motivation.Placement
import app.mama.platform.History
import app.mama.platform.Mama
import app.mama.platform.Protection
import app.mama.ui.design.NoteTone
import app.mama.ui.design.Palette
import app.mama.ui.design.SceneLayout
import app.mama.ui.design.SceneMood
import app.mama.ui.design.Scenery
import app.mama.ui.design.Spacing
import app.mama.ui.design.SummaryItem
import app.mama.ui.design.Typo
import app.mama.ui.design.V2
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Dashboards, statistics/settings tabs, Recovery, success, FLEX (MAMA_DESIGN_SYSTEM_V2).

private fun MainActivity.nav() = design.bottomNav(tab) { tab = it; editSaved = false; render() }

/** Top of a dashboard: M A M A, an optional "N из M" and the days as segments. */
private fun MainActivity.homeHeader(counter: String?, done: Int, total: Int): View = design.column().apply {
    addView(design.topBar(null, counter))
    if (total > 0) addView(design.segments(done, total), design.gap(4).apply { leftMargin = design.dp(Spacing.SCREEN); rightMargin = design.dp(Spacing.SCREEN) })
}

/** Room under the title where the landscape shows (the hero window of Recovery and Success). */
private fun MainActivity.heroSpace(b: LinearLayout, heightDp: Int = 140) = b.addView(View(this), LinearLayout.LayoutParams(1, design.dp(heightDp)))

/**
 * The landscape of this moment under the title of a home screen, as a
 * rounded picture (reference 07): the words stay on the cream page, the
 * photo keeps its full colour.
 */
private fun MainActivity.homePicture(b: LinearLayout, scenery: Scenery, mood: SceneMood, heightDp: Int = 176) =
    b.addView(design.photoBand(scenery, mood), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, design.dp(heightDp)).apply { topMargin = design.dp(22) })

/**
 * Title of a dashboard: the main fact (the day of the series), its state as
 * a quiet badge and one short line (a phrase from [app.mama.motivation.MotivationCatalog]).
 */
private fun MainActivity.homeTitle(b: LinearLayout, title: String, state: String?, stateIcon: Icon, line: String?, onLine: (() -> Unit)? = null) {
    val d = design
    // The day of the series is the main fact of the screen: the largest words on it.
    b.addView(d.title(title).apply { textSize = 42f })
    state?.let { b.addView(d.badge(it, stateIcon), d.wrap().apply { topMargin = d.dp(12) }) }
    line?.let {
        b.addView(d.subtitle(it).apply {
            maxWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
            onLine?.let { open -> setOnClickListener { open() } }
        }, d.wrap().apply { topMargin = d.dp(12) })
    }
}

/** A short phrase for the home of a series: one line, the same for the whole day. */
private fun MainActivity.homePhrase(placement: Placement, key: String): String =
    MotivationRotation.of(this).phrase(placement, key, maxLength = 40).text

/** The third of a running series the current day is in: 0 the start, 1 the middle, 2 the end (3 days: one each). */
private fun stage(series: Series): Int {
    val n = series.kind.periods
    val day = minOf(series.completedPeriods + 1, n)
    return (day - 1) * 3 / n
}

/**
 * The home photo along a running series: its energy grows with the path —
 * a quiet misted morning at the start, a living day in the middle, a light
 * golden evening at the end (MAMA_DESIGN_SYSTEM_V2 «Свет»). Only a picture:
 * the series itself does not change.
 */
internal fun progressScenery(series: Series): Scenery = when (stage(series)) {
    0 -> Scenery.MORNING
    1 -> Scenery.DAY
    else -> Scenery.EVENING
}

/** The light of [progressScenery]: quiet, calm, bright. */
internal fun progressMood(series: Series): SceneMood = when (stage(series)) {
    0 -> SceneMood.QUIET
    1 -> SceneMood.CALM
    else -> SceneMood.BRIGHT
}

/** An evening or night window counts nights; a daytime one counts days. */
private fun countsNights(window: app.mama.core.DailyWindow) = window.start.hour >= 17 || window.start.hour < 5

/** "Пройдено ночей: 2 из 7" (real progress of the series). */
private fun doneLine(series: Series) =
    "Пройдено ${if (countsNights(series.window)) "ночей" else "дней"}: ${series.completedPeriods} из ${series.kind.periods}"

/** Russian plural: 1 ночь, 2 ночи, 5 ночей. */
private fun plural(n: Int, one: String, few: String, many: String) = when {
    n % 10 == 1 && n % 100 != 11 -> one
    n % 10 in 2..4 && n % 100 !in 12..14 -> few
    else -> many
}

/** The lock right now, in a few words ("Блокировка идёт"). */
private fun lockBadge(st: LockState, waiting: String): Pair<String, Icon>? = when (st) {
    is LockState.Locked -> "Блокировка идёт" to Icon.MOON
    is LockState.EmergencyPass -> "Экстренный доступ" to Icon.PHONE
    is LockState.Waiting -> waiting to Icon.SPROUT
    LockState.Free -> null
}

private fun countdownLabel(st: LockState, waiting: String) = when (st) {
    is LockState.Waiting -> waiting
    is LockState.Locked -> "До конца блокировки"
    is LockState.EmergencyPass -> "Блокировка вернётся через"
    LockState.Free -> "Следующий период планируется…"
}

/**
 * Time to the start or the end of the lock, in hours and minutes, with the
 * real moment as a note ("Сегодня в 23:00"). Re-reads [state] every second;
 * when the state kind changes (waiting → locked …) the whole screen is redrawn.
 */
private fun MainActivity.countdownRow(state: () -> LockState, zone: ZoneId, waiting: String = "До начала блокировки"): View {
    val d = design
    val now = Mama.trustedNow(this)
    val first = state()
    val note = when (first) {
        is LockState.Waiting -> Texts.firstLock(first.startsAt, zone, now)
        is LockState.Locked -> "До ${Texts.time(first.endsAt, zone)}"
        is LockState.EmergencyPass -> "До ${Texts.time(first.until, zone)}"
        LockState.Free -> null
    }
    // The largest figure of the screen.
    val row = d.valueRow(Icon.CLOCK, countdownLabel(first, waiting), "—", note?.let { d.badge(it) }, Typo.AMOUNT)
    var shown: Class<*>? = null
    startTicker {
        val st = state()
        if (shown != null && shown != st.javaClass) {
            render()
            return@startTicker
        }
        shown = st.javaClass
        val target = when (st) {
            is LockState.Waiting -> st.startsAt
            is LockState.Locked -> st.endsAt
            is LockState.EmergencyPass -> st.until
            LockState.Free -> null
        }
        row.label.text = countdownLabel(st, waiting)
        row.value.text = if (target == null) "—" else {
            val minutes = maxOf(0L, (Duration.between(Mama.trustedNow(this), target).toMillis() + 59_999) / 60_000)
            d.hoursMinutes(minutes / 60, minutes % 60)
        }
    }
    return row.view
}

/** The countdown alone in its card: the main thing on the home screen. */
private fun MainActivity.timerCard(state: () -> LockState, zone: ZoneId, waiting: String = "До начала блокировки"): View =
    design.rowsCard(listOf(countdownRow(state, zone, waiting)), paddingDp = 20)

// ---------------- 8. Series dashboard: scheduled / activating / active ----------------

internal fun MainActivity.seriesDashboard(
    series: Series,
    sampleCommitment: Commitment? = null,
    sampleRestart: RestartHold? = null,
    protectionMissing: List<Requirement> = Protection.missing(this),
    state: () -> LockState,
): Pair<View, Boolean> {
    if (series.status == SeriesStatus.NEEDS_ATTENTION) return needsAttentionScreen(series, sampleCommitment, sampleRestart, protectionMissing)
    val d = design
    val n = series.kind.periods
    val day = minOf(series.completedPeriods + 1, n)
    val b = d.body(22)
    // The day, the state and the timer first; one short phrase; the rest in one quiet card.
    when {
        series.committed -> {
            // The day, the nights already done and one short phrase (tap: the motivation screen).
            val (badge, icon) = lockBadge(state(), doneLine(series)) ?: (doneLine(series) to Icon.SPROUT)
            homeTitle(b, Texts.dayOf(day, n), badge, icon, homePhrase(Placement.ACTIVE, "${series.id}:day-$day")) {
                motivationOpen = true
                render()
            }
            // The days as a path: done, today, still ahead.
            b.addView(d.dayCircles(series.completedPeriods, n, day), d.gap(18))
        }
        series.status == SeriesStatus.ACTIVATING ->
            homeTitle(b, "MAMA включает защиту", "Проверяем защиту", Icon.SHIELD, "Это займёт несколько секунд.")
        else -> homeTitle(
            b, "Серия запланирована", (if (series.isRestart) "Restart · " else "") + Texts.seriesName(series.kind), Icon.CALENDAR,
            homePhrase(Placement.SCHEDULED, "${series.id}:scheduled"),
        )
    }
    // Before the first lock: the quiet morning of a path about to begin.
    val scenery = if (series.committed) progressScenery(series) else Scenery.MORNING
    val mood = if (series.committed) progressMood(series) else SceneMood.QUIET
    homePicture(b, scenery, mood)
    if (editSaved && series.status == SeriesStatus.SCHEDULED) {
        b.addView(d.noteCard("Время и длина серии остались прежними.", "Изменения сохранены", icon = Icon.CHECK), d.gap(Spacing.GUTTER))
    }
    if (series.status == SeriesStatus.SCHEDULED && protectionMissing.isNotEmpty()) {
        b.addView(d.noteCard(
            missingList(protectionMissing) + "\nБез этого серия не начнётся. Включи до начала блокировки.",
            "Защита не готова", NoteTone.CARE, Icon.SHIELD,
        ), d.gap(Spacing.GUTTER))
        b.addView(d.secondaryButton("Исправить защиту", Icon.SHIELD) { startSetupAll(Protection.strict) }, d.gap(10))
    }
    b.addView(timerCard(state, series.window.zone), d.gap(Spacing.GUTTER))
    b.addView(d.summaryCard(null, seriesDetails(series, series.window, sampleCommitment, sampleRestart)), d.gap(16))
    when (series.status) {
        SeriesStatus.SCHEDULED -> {
            b.addView(d.secondaryButton("Изменить условия", Icon.CALENDAR) { beginEditing(series) }, d.gap(22))
            b.addView(d.linkButton("Отменить запуск") { confirmCancelLaunch(series) }, d.gap(4))
        }
        SeriesStatus.ACTIVATING -> b.addView(d.linkButton("Отменить запуск") { confirmCancelLaunch(series) }, d.gap(14))
        else -> Unit
    }
    // The day counter once the series runs (the days themselves are the circles); before that only the wordmark.
    val header = if (series.committed) homeHeader("$day из $n", 0, 0) else homeHeader(null, 0, 0)
    return lightFrame(header, b, null, nav())
}

/** The window, trusted contact, "Цена слова" and a held Restart of a series: real values, short notes. */
private fun MainActivity.seriesDetails(
    series: Series,
    window: app.mama.core.DailyWindow,
    sampleCommitment: Commitment?,
    sampleRestart: RestartHold?,
): List<SummaryItem> {
    val items = mutableListOf(
        SummaryItem(Icon.CALENDAR, "Окно блокировки", "${Texts.hhmm(window.start)} → ${Texts.hhmm(window.end)}"),
        SummaryItem(Icon.PERSON, "Доверенный контакт", series.contact.name),
    )
    val wallet = Mama.wallet(this)
    val commitment = sampleCommitment ?: wallet.commitmentFor(series.id)
    when {
        commitment != null && commitment.state == CommitmentState.SCHEDULED ->
            items += SummaryItem(Icon.COINS, "Цена слова", Texts.money(commitment.amountKop), "можно изменить до первой блокировки")
        commitment != null && commitment.state == CommitmentState.RESERVED ->
            items += SummaryItem(Icon.COINS, "Цена слова", Texts.money(commitment.amountKop), "в резерве до конца серии")
        else -> if (FeatureFlags.WALLET_ENABLED && !series.committed) items += SummaryItem(Icon.COINS, "Цена слова", "Без цены слова")
    }
    val hold = sampleRestart ?: wallet.restartHoldFor(series.id)
    if (hold != null && hold.state == RestartHoldState.HELD) {
        items += SummaryItem(Icon.REFRESH, "Restart", Texts.money(hold.amountKop), "вернётся на баланс, если отменить запуск")
    }
    return items
}

private fun missingList(missing: List<Requirement>) = missing.joinToString("\n") { "• ${it.title}" }

/**
 * The window to show. An AUTO_NOW series waiting in NEEDS_ATTENTION is not
 * frozen: a new launch starts it from that moment, so it shows "from now".
 */
internal fun MainActivity.shownWindow(series: Series): app.mama.core.DailyWindow =
    if (series.autoNow && series.status == SeriesStatus.NEEDS_ATTENTION) {
        app.mama.core.SeriesDraft.resolve(
            app.mama.core.LaunchTime.AutoNow(series.window.length, series.window.zone), Mama.trustedNow(this),
        ).window
    } else {
        series.window
    }

// ---------------- NEEDS_ATTENTION: the start time came, protection could not be switched on ----------------

private fun MainActivity.needsAttentionScreen(
    series: Series,
    sampleCommitment: Commitment?,
    sampleRestart: RestartHold?,
    missing: List<Requirement>,
): Pair<View, Boolean> {
    val d = design
    val b = d.body(22)
    b.addView(d.titleBlock("Серия не началась", lead = "MAMA не смогла включить защиту.", narrow = true))
    homePicture(b, Scenery.MORNING, SceneMood.QUIET, 150)
    b.addView(d.noteCard(
        "Поэтому день не засчитан, ничего не списано и ничего не потеряно. Блокировка не включится сама — только после того, как ты подтвердишь новый запуск.",
        "Ничего не потеряно", icon = Icon.HEART,
    ), d.gap(Spacing.GUTTER))
    if (missing.isNotEmpty()) {
        b.addView(d.noteCard(missingList(missing), "Не работает защита", NoteTone.CARE, Icon.SHIELD), d.gap(Spacing.GUTTER))
        b.addView(d.primaryButton("Исправить защиту", arrow = false) { startSetupAll(Protection.strict) }, d.gap(14))
    } else {
        val text = if (series.autoNow) {
            "Подтверди новый запуск. Блокировка начнётся сразу после подтверждения и продлится " +
                "${Texts.flexDuration(series.window.length)}; время серии отсчитается от этого момента."
        } else {
            val now = Mama.trustedNow(this)
            val first = series.window.nextFullPlan(now, series.mode)
            "Подтверди новый запуск. Первая блокировка: ${Texts.firstLock(first.start, series.window.zone, now)}."
        }
        b.addView(d.noteCard(text, "Защита готова", icon = Icon.CHECK), d.gap(Spacing.GUTTER))
        b.addView(d.primaryButton("Подтвердить запуск", arrow = false) { relaunch() }, d.gap(14))
    }
    b.addView(d.secondaryButton("Изменить условия", Icon.CALENDAR) { beginEditing(series) }, d.gap(10))
    b.addView(d.linkButton("Отменить запуск") { confirmCancelLaunch(series) }, d.gap(4))
    b.addView(d.summaryCard(null, seriesDetails(series, shownWindow(series), sampleCommitment, sampleRestart)), d.gap(18))
    return lightFrame(homeHeader(null, 0, 0), b, null, nav())
}

private fun MainActivity.relaunch() {
    when (val r = Mama.relaunchSeries(this)) {
        Mama.SeriesEdit.Saved -> {
            val started = Mama.series(this)?.status != SeriesStatus.SCHEDULED
            android.widget.Toast.makeText(this, if (started) "Серия запускается" else "Серия запланирована", android.widget.Toast.LENGTH_SHORT).show()
        }
        is Mama.SeriesEdit.ProtectionMissing -> alert("Сначала исправь защиту", missingList(r.missing))
        else -> alert("Не получилось", "Запланировать серию сейчас нельзя.")
    }
    render()
}

// ---------------- test mode / single session ----------------

internal fun MainActivity.sessionDashboard(session: Session, state: () -> LockState): Pair<View, Boolean> {
    val d = design
    val b = d.body(22)
    val isTest = Duration.between(session.plan.start, session.plan.end) == app.mama.core.TestMode.DURATION
    val (badge, icon) = lockBadge(state(), "Скоро начнётся") ?: ("Скоро начнётся" to Icon.SPROUT)
    homeTitle(b, if (isTest) "Тестовый режим" else "Блокировка", badge, icon, if (isTest) "15 минут · только для тестирования" else Texts.mode(session.plan.mode))
    homePicture(b, Scenery.MORNING, SceneMood.QUIET)
    val z = session.plan.zone
    b.addView(timerCard(state, z), d.gap(Spacing.GUTTER))
    b.addView(d.summaryCard(null, listOf(
        SummaryItem(Icon.CALENDAR, "Время блокировки", "${Texts.time(session.plan.start, z)} → ${Texts.time(session.plan.end, z)}"),
        SummaryItem(Icon.PERSON, "Доверенный контакт", session.contact.name),
    )), d.gap(16))
    if (state() is LockState.Waiting) {
        b.addView(d.linkButton("Отменить до начала") { Mama.cancelBeforeStart(this); render() }, d.gap(10))
    }
    return lightFrame(homeHeader(null, 0, 0), b, null, nav())
}

// ---------------- tabs ----------------

/** Stable identifiers (view tags) of the statistics states and of each card of "Твой путь", for tests. */
internal const val STATISTICS_SCREEN = "statistics_screen"
internal const val STATISTICS_EMPTY = "statistics_empty"
internal const val PATH_ITEM = "path_item"

/** Tag of every quiet "Мой путь" entry (welcome, success, recovery). */
internal const val OPEN_PATH = "open_path"

/** Opens "Мой путь" from a screen without the tab bar; back returns there. */
internal fun MainActivity.openPath() { pathOpen = true; render() }

internal fun MainActivity.closePath() { pathOpen = false; render() }

/**
 * "Статистика" (UI v1, 3A): the decisions already made, from the history of
 * finished series and FLEX packages ([History]) and the series running now.
 * Only real numbers; no hours "without the phone", streaks, percentages,
 * ratings, comparisons or money. Before anything is done: an empty state,
 * never zeros. [standalone]: opened as "Мой путь" from a screen without the
 * tab bar, so a back arrow instead of the tabs. [showPath]: previews open the
 * screen at "Твой путь".
 */
internal fun MainActivity.statisticsScreen(
    records: List<HistoryRecord> = History.records(this),
    running: Series? = Mama.series(this),
    flex: FlexPackage? = Mama.entitlements(this).flex,
    showPath: Boolean = false,
    standalone: Boolean = false,
): Pair<View, Boolean> {
    val d = design
    val stats = Statistics.of(records, running, flex)
    val header = d.topBar(if (standalone) ({ closePath() }) else null)
    val tabs = if (standalone) null else nav()
    if (stats.isEmpty) return statisticsEmpty(header, tabs)
    val b = d.body(18)

    // A clean page (MAMA_DESIGN_SYSTEM_V2: statistics without a picture): the nights already chosen first.
    val (label, each) = when (stats.unit) {
        Statistics.PeriodUnit.NIGHTS -> "Ночей пройдено" to "Каждая — решение в пользу себя."
        Statistics.PeriodUnit.DAYS -> "Дней пройдено" to "Каждый — решение в пользу себя."
        Statistics.PeriodUnit.NIGHTS_AND_DAYS -> "Ночей и дней пройдено" to "Каждое — решение в пользу себя."
    }
    // The numbers as tiles: the nights already chosen first. Only what exists: nothing is shown as zero.
    val tiles = d.row().apply { gravity = Gravity.TOP }
    fun tile(value: String, caption: String) = tiles.addView(d.tile(value, caption), d.weight().apply { if (tiles.childCount > 0) leftMargin = d.dp(10) })
    tile(stats.nightsDone.toString(), label)
    if (stats.seriesCompleted > 0) tile(stats.seriesCompleted.toString(), "Серий завершено")
    stats.best?.let { tile("${it.done} из ${it.planned}", "Лучший результат") }
    b.addView(tiles, d.fill())
    b.addView(d.text(each, Typo.SUBTITLE, Palette.InkMuted, center = true), d.gap(14))

    val rows = buildList {
        if (stats.restartsStarted > 0) {
            add(SummaryItem(Icon.REFRESH, "Продолжил после срыва", "${stats.restartsStarted} ${plural(stats.restartsStarted, "раз", "раза", "раз")}"))
        }
        stats.flex?.let {
            add(SummaryItem(
                Icon.INFINITY, "FLEX-периодов пройдено", it.successful.toString(),
                "из ${it.used} использованных · ${it.packages} ${plural(it.packages, "пакет", "пакета", "пакетов")}",
            ))
        }
    }
    if (rows.isNotEmpty()) b.addView(d.summaryCard(null, rows), d.gap(20))

    val pathTitle = d.text("Твой путь", Typo.HEADLINE)
    b.addView(pathTitle, d.gap(30))
    val thisYear = LocalDate.now().year
    stats.path.forEachIndexed { i, item ->
        b.addView(pathCard(item, thisYear).apply { tag = PATH_ITEM }, d.gap(if (i == 0) 14 else Spacing.GUTTER))
    }
    if (showPath) {
        pathTitle.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                pathTitle.viewTreeObserver.removeOnGlobalLayoutListener(this)
                generateSequence(pathTitle.parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull()?.scrollTo(0, pathTitle.top - d.dp(16))
            }
        })
    }
    return (design.screen(null, header = header, body = b, nav = tabs) to true).also { it.first.tag = STATISTICS_SCREEN }
}

/** No history yet: the beginning of the path, a quiet morning photo, no numbers. */
private fun MainActivity.statisticsEmpty(header: View, tabs: View?): Pair<View, Boolean> {
    val d = design
    val b = d.body(26)
    b.addView(d.titleBlock(
        "Твой путь только начинается",
        narrow = true,
        lead = "Здесь появятся решения, которыми ты будешь гордиться.",
    ))
    return v1Frame(Scenery.MORNING, header, b, null, tabs, layout = SceneLayout.FULL, mood = SceneMood.QUIET).also { it.first.tag = STATISTICS_EMPTY }
}

/** One card of "Твой путь": calm facts, never a verdict. */
private fun MainActivity.pathCard(item: Statistics.PathItem, thisYear: Int): View {
    val d = design
    val card = d.card(paddingDp = 18)
    fun head(tags: List<String>) {
        if (tags.isEmpty()) return
        val row = d.row()
        tags.forEachIndexed { i, t -> row.addView(d.tag(t), d.wrap().apply { if (i > 0) leftMargin = d.dp(6) }) }
        card.addView(row, d.fill())
    }
    fun main(title: String, value: String?, caption: String) {
        val row = d.row().apply { gravity = Gravity.CENTER_VERTICAL }
        val texts = d.column()
        texts.addView(d.text(title, Typo.TITLE))
        texts.addView(d.text(caption, Typo.LABEL, Palette.InkMuted), d.gap(4))
        row.addView(texts, d.weight())
        value?.let { row.addView(d.text(it, Typo.HEADLINE, Palette.OliveInk), d.wrap().apply { leftMargin = d.dp(12) }) }
        card.addView(row, d.gap(if (card.childCount == 0) 0 else 10))
    }
    fun note(icon: Icon, text: String, color: Int) {
        val row = d.row().apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(d.icon(icon, color, 16), d.size(16))
        row.addView(d.text(text, Typo.LABEL, color), d.wrap().apply { leftMargin = d.dp(8) })
        card.addView(row, d.gap(12))
    }
    fun span(start: java.time.Instant?, end: java.time.Instant, zone: ZoneId) =
        Statistics.dateRange(start?.atZone(zone)?.toLocalDate(), end.atZone(zone).toLocalDate(), thisYear)
    fun nights(n: Int, window: app.mama.core.DailyWindow?) =
        if (window == null || Statistics.nightWindow(window)) "$n ${plural(n, "ночь", "ночи", "ночей")}" else "$n ${plural(n, "день", "дня", "дней")}"
    fun window(w: app.mama.core.DailyWindow) = "${Texts.hhmm(w.start)}–${Texts.hhmm(w.end)}"
    fun periods(n: Int) = "$n ${plural(n, "период", "периода", "периодов")}"

    when (item) {
        is Statistics.PathItem.RunningSeries -> {
            val s = item.series
            head(listOfNotNull("Сейчас", if (s.restartOf != null) "Restart" else null))
            val since = s.actualStartedAt ?: s.startedAt
            main(nights(s.kind.periods, s.window), "${s.completedPeriods} из ${s.kind.periods}", "с ${span(null, since, s.window.zone)} · ${window(s.window)}")
            card.addView(d.segments(s.completedPeriods, s.kind.periods), d.gap(14))
        }
        is Statistics.PathItem.RunningFlex -> {
            val p = item.pkg
            head(listOf("FLEX", "Сейчас"))
            main("Пройдено ${periods(p.successful)}", null, "Использовано ${p.used} из ${FlexPackage.PERIODS} · с ${span(null, p.activatedAt, ZoneId.systemDefault())}")
        }
        is Statistics.PathItem.Finished -> {
            val r = item.record
            val zone = r.window?.zone ?: ZoneId.systemDefault()
            val dates = span(r.startedAt, r.endedAt, zone)
            if (r.type == HistoryType.FLEX) {
                head(listOf("FLEX"))
                main("Пройдено ${periods(r.successfulNights)}", null, "Использовано ${r.consumedNights ?: 0} из ${r.plannedNights} · $dates")
                if (r.result == HistoryResult.COMPLETED) {
                    note(Icon.CHECK, "Все ${r.plannedNights} периодов использованы", Palette.OliveInk)
                } else {
                    note(Icon.CALENDAR, "Срок пакета закончился", Palette.InkMuted)
                }
            } else {
                head(if (r.restartOf != null) listOf("Restart") else emptyList())
                main(nights(r.plannedNights, r.window), "${r.successfulNights} из ${r.plannedNights}", listOfNotNull(dates, r.window?.let(::window)).joinToString(" · "))
                card.addView(d.segments(r.successfulNights, r.plannedNights), d.gap(14))
                when {
                    r.result == HistoryResult.COMPLETED -> note(Icon.SPROUT, "Серия пройдена", Palette.OliveInk)
                    item.continuedLater -> note(Icon.REFRESH, "Ты продолжил путь позже", Palette.InkMuted)
                    else -> note(Icon.SPROUT, "Этот путь продолжается", Palette.InkMuted)
                }
            }
        }
    }
    return card
}

/** Settings: a clean page of the earth, sections with quiet headings. */
internal fun MainActivity.settingsScreen(): Pair<View, Boolean> {
    val d = design
    val b = body(30)
    b.addView(d.title("Настройки"))
    if (FeatureFlags.WALLET_ENABLED) {
        val wallet = Mama.wallet(this)
        b.addView(balanceCard(wallet), d.gap(18))
        b.addView(d.text("История баланса", Typo.HEADLINE), d.gap(30))
        b.addView(ledgerList(wallet, zone), d.gap(10))
    }
    b.addView(d.text("Разрешения", Typo.HEADLINE), d.gap(34))
    b.addView(d.text("Без них блокировку можно обойти.", Typo.BODY, Palette.InkMuted), d.gap(4))
    Requirement.entries.forEachIndexed { i, req ->
        val status = when {
            req.isGranted(this) -> PermissionStatus.GRANTED
            req.required -> PermissionStatus.REQUIRED
            else -> PermissionStatus.MISSING
        }
        b.addView(kit.permissionCard(req.icon, req.title, req.why, status) { req.request(this) }, kit.gap(if (i == 0) 20 else 10))
    }
    if (FeatureFlags.TEST_MODE_ENABLED) {
        b.addView(d.noteCard("Тестовая версия: оплата не подключена и не проводится.", icon = Icon.FLASK), d.gap(18))
    }
    b.addView(d.text("MAMA ${Texts.version(this)}", Typo.CAPTION, Palette.InkSoft, center = true), d.gap(16))
    return lightFrame(null, b, null, nav())
}

// ---------------- 11. Failure / Restart ----------------

/**
 * Stable identifiers of the recovery screen and its Restart action (view
 * tags), so tests find the state without relying on the copy on it.
 */
internal const val RECOVERY_SCREEN = "recovery_screen"
internal const val RESTART_CTA = "restart_cta"

/**
 * A series ended early: a return to the path, never a verdict. The production
 * photo of the path to the lake at sunrise ([Scenery.RECOVERY]) under the
 * words "Этот путь продолжается" and the support; the nights done as a pill;
 * Restart as the natural way on (with a new time), the one main action. No
 * red, no warning. Nothing about a return of «Цена слова» before it has
 * really come.
 */
internal fun MainActivity.failureScreen(
    series: Series,
    freeInDays: Long = StandardRules.daysUntilFreeStart(Mama.entitlements(this), Mama.trustedNow(this)),
    sampleCommitment: app.mama.core.Commitment? = null,
): Pair<View, Boolean> {
    val v = v2
    val b = design.body(22).apply { gravity = Gravity.CENTER_HORIZONTAL }
    b.addView(design.titleBlock("Этот путь продолжается", center = true, lead = "Один сложный вечер не отменяет твоего решения."))
    b.addView(v.pill(doneLine(series), Icon.SPROUT), design.centered(14))
    // The path itself: light ahead, the way back in view.
    heroSpace(b, 176)

    // Restart: the way on, the one main action of the screen.
    val offer = v.card(paddingDp = 20, radiusDp = V2.CARD_LARGE).apply { clipToPadding = false }
    val head = v.k.row().apply { gravity = Gravity.TOP }
    head.addView(v.bubble(Icon.REFRESH, V2.Moss, V2.SandLight, 52))
    val texts = v.k.column()
    texts.addView(v.text("Попробовать снова", Typo.HEADLINE, V2.Ink))
    // The source comes from the one shared resolver: a копилка credit of this length wins over the paid Restart.
    val source = Mama.startSource(this, series.kind)
    val priceLine = when (source) {
        is app.mama.core.StartSource.PaidRestart -> "Restart · ${Texts.price(source.priceRub)}"
        app.mama.core.StartSource.FromCredit -> "Бесплатно — из копилки"
        app.mama.core.StartSource.Free -> "Бесплатно"
    }
    texts.addView(v.text(priceLine, Typo.VALUE, V2.Moss), v.k.gap(4))
    val detail = if (source == app.mama.core.StartSource.FromCredit) {
        "Та же серия — ${Texts.seriesName(series.kind)}, с первого дня, бесплатно из копилки. Время выберешь заново."
    } else {
        "Та же серия — ${Texts.seriesName(series.kind)}, с первого дня. Время выберешь заново."
    }
    texts.addView(v.text(detail, Typo.LABEL, V2.InkMuted), v.k.gap(6))
    head.addView(texts, v.k.weight().apply { leftMargin = v.dp(14) })
    offer.addView(head, v.k.fill())
    offer.addView(v.primary("Начать заново") {
        // A new launch: time first (a new draft from the current time), then "Цена слова", then payment.
        beginRestart(series.kind)
        render()
    }.apply { tag = RESTART_CTA }, v.k.gap(18))
    b.addView(offer, v.k.gap(Spacing.GUTTER))

    commitmentNote(series.id, sampleCommitment)?.let { b.addView(v.note(it.title, it.message, it.icon, warm = it.care), v.k.gap(Spacing.GUTTER)) }
    if (freeInDays > 0) {
        b.addView(v.note(
            "Следующая бесплатная серия через ${Texts.days(freeInDays.toInt())}",
            "Без Restart — бесплатно, телефон при этом не заблокирован.", Icon.CALENDAR,
        ), v.k.gap(Spacing.GUTTER))
    }
    b.addView(v.secondary("Вернуться на главную", Icon.HOME) {
        closeDraft()
        Mama.dismissSeries(this)
        render()
    }, v.k.gap(22))
    b.addView(v.link("Посмотреть мой путь") { openPath() }.apply { tag = OPEN_PATH }, v.k.centered(6))
    // The sun stays high in the window, so the path down to the lake is in view.
    return v1Frame(Scenery.RECOVERY, design.topBar(null), b, null, layout = SceneLayout.HERO, focusY = 0.1f).also { it.first.tag = RECOVERY_SCREEN }
}

// ---------------- 12. Success ----------------

/**
 * The series is done: the brightest screen of MAMA — open space, the sun over
 * the lake, the path walked and an inner victory. A grown-up reward, no
 * confetti: the result, «Цена слова» kept, and the way on.
 */
internal fun MainActivity.successScreen(series: Series, sampleCommitment: app.mama.core.Commitment? = null): Pair<View, Boolean> {
    val d = design
    val n = series.kind.periods
    val done = series.completedPeriods
    val unit = if (countsNights(series.window)) plural(done, "ночь", "ночи", "ночей") else plural(done, "день", "дня", "дней")
    val b = d.body(22)
    // The result in ink, one thought per line: it is the reward of the screen.
    b.addView(d.titleBlock("Ты сделал это.", center = true, lead = "$done $unit.\n$done ${plural(done, "решение", "решения", "решений")} в пользу себя.").apply {
        (getChildAt(0) as android.widget.TextView).textSize = 44f
    })
    // No badges: the title is the reward, the open sky and the sun carry the light.
    heroSpace(b, 212)
    // The result: every day of the series, done.
    val result = d.card(paddingDp = 20)
    result.addView(d.valueRow(Icon.SPROUT, "Твой результат · ${Texts.seriesName(series.kind)}", "$done из $n").view)
    result.addView(d.segments(done, n), d.gap(18))
    b.addView(result, d.gap(Spacing.GUTTER))
    commitmentOutcome(series.id, sampleCommitment)?.let { b.addView(it, d.gap(Spacing.GUTTER)) }
    val phrase = MotivationRotation.of(this).phrase(Placement.SUCCESS, "${series.id}:done").text
    b.addView(d.text(phrase, Typo.QUOTE_ITALIC, Palette.InkMuted, center = true), d.gap(26))
    // A completed paid Restart earns one free series of the same length (копилка).
    if (series.origin == app.mama.core.SeriesOrigin.PAID_RESTART) {
        b.addView(d.noteCard(
            "Ты оплатил этот рестарт и прошёл его целиком — следующая серия ${Texts.seriesName(series.kind)} уже в копилке, бесплатно.",
            "Бесплатная серия в копилке", icon = Icon.SPROUT,
        ), d.gap(Spacing.GUTTER))
    }
    b.addView(d.text("Платить не нужно: Restart бывает только после срыва.", Typo.CAPTION, Palette.InkMuted, center = true), d.gap(16))
    val next = d.primaryButton("Новая серия", large = true) { closeDraft(); Mama.dismissSeries(this@successScreen); render() }
    val path = d.linkButton("Посмотреть мой путь") { openPath() }.apply { tag = OPEN_PATH }
    return v1Frame(Scenery.EVENING, d.topBar(null), b, d.bottomCta(next, path), layout = SceneLayout.HERO, mood = SceneMood.BRIGHT)
}

// ---------------- motivation ----------------

/**
 * One quiet thought over the misted photo of this stage of the series:
 * "У меня получается. Я уже двигаюсь." — soft support, not a slogan. Shown
 * once after each night that is done, and when the phrase on the home
 * screen is tapped. Phrases from [app.mama.motivation.MotivationCatalog]: no
 * pressure, shame or comparison.
 */
internal fun MainActivity.motivationScreen(series: Series): Pair<View, Boolean> {
    val d = design
    val n = series.kind.periods
    val done = series.completedPeriods
    val b = d.body(18)
    val phrase = MotivationRotation.of(this).phrase(Placement.SUCCESS, "${series.id}:night-$done", maxLength = 70).text
    // The landscape of this stage as a card; the thought in a card resting on it.
    // At least 250 dp; on a taller screen the landscape takes the room left.
    b.addView(d.photoBand(progressScenery(series), progressMood(series)), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d.dp(250), 1f))
    val thought = d.card(paddingDp = 24, radiusDp = V2.CARD_LARGE)
    thought.addView(d.text(phrase, Typo.QUOTE, Palette.Ink).apply { textSize = 27f }, d.fill())
    thought.addView(d.text("— MAMA", Typo.TAG, Palette.InkSoft), d.wrap().apply { topMargin = d.dp(12) })
    b.addView(thought, d.fill().apply { topMargin = -d.dp(44); leftMargin = d.dp(14); rightMargin = d.dp(14) })
    b.addView(d.badge(doneLine(series), Icon.SPROUT), d.wrap().apply { topMargin = d.dp(26) })
    b.addView(d.dayCircles(done, n), d.gap(14))
    val next = d.primaryButton("Продолжить", large = true) { closeMotivation(series) }
    return lightFrame(d.topBar(null), b, d.bottomCta(next))
}

/** The motivation screen was seen for this night: back to the home screen. */
internal fun MainActivity.closeMotivation(series: Series) {
    prefs.edit().putString(MainActivity.MOTIVATION_SEEN, "${series.id}:${series.completedPeriods}").apply()
    motivationOpen = false
    render()
}

// ---------------- FLEX ----------------

internal fun MainActivity.flexDashboard(flex: FlexPackage, state: () -> LockState): Pair<View, Boolean> {
    val d = design
    val now = Mama.trustedNow(this)
    val b = d.body(22)
    b.addView(d.titleBlock("MAMA FLEX", "${flex.successful} из ${FlexPackage.PERIODS} выполнено", narrow = true))
    homePicture(b, Scenery.MORNING, SceneMood.CALM)
    b.addView(d.noteCard(
        if (flex.rewardStillPossible) "Каждый успешный период приближает к бесплатному пакету." else "Следующий бесплатный FLEX доступен только при результате 7 из 7.",
        if (flex.rewardStillPossible) "Пройди 7 из 7 — следующий FLEX бесплатно" else "Успешно: ${flex.successful} из ${FlexPackage.PERIODS}",
        icon = Icon.INFINITY,
    ), d.gap(Spacing.GUTTER))
    flex.lastBurntAt?.takeIf { Duration.between(it, now) < Duration.ofHours(18) }?.let {
        b.addView(d.noteCard(
            "Успешно: ${flex.successful} из ${FlexPackage.PERIODS}. Остальные дни FLEX остаются доступны.",
            "Сегодняшний FLEX-день завершён досрочно.", NoteTone.CARE, Icon.HEART,
        ), d.gap(Spacing.GUTTER))
    }
    if (flex.currentSessionId != null && state() != LockState.Free) {
        b.addView(d.rowsCard(listOf(countdownRow(state, zone, "До начала FLEX-периода")), paddingDp = 18), d.gap(Spacing.GUTTER))
        if (state() is LockState.Waiting) {
            b.addView(d.linkButton("Отменить период (день не тратится)") { Mama.cancelBeforeStart(this); render() }, d.gap(6))
        }
    }
    val daysLeft = maxOf(0L, Duration.between(now, flex.expiresAt).toDays())
    b.addView(d.summaryCard(null, listOf(
        SummaryItem(Icon.INFINITY, "Осталось", "${flex.remaining} из ${FlexPackage.PERIODS}"),
        SummaryItem(Icon.CHECK, "Успешно", "${flex.successful}"),
        SummaryItem(Icon.CALENDAR, "Действует", Texts.days(daysLeft.toInt()), "до ${Texts.dayTime(flex.expiresAt, zone)} · неиспользованные дни сгорают"),
    )), d.gap(Spacing.GUTTER))
    if (flex.currentSessionId == null || state() == LockState.Free) {
        b.addView(d.primaryButton("Запланировать FLEX-период") { flexPlanning = true; render() }, d.gap(18))
    }
    return lightFrame(homeHeader("${flex.successful} из ${FlexPackage.PERIODS}", flex.successful, FlexPackage.PERIODS), b, null, nav())
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
    return lightFrame(design.topBar({ flexPlanning = false; render() }), b, cta)
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

/**
 * A FLEX package is over: like the end of a series, a calm look at what was
 * done — the result, each period as a dot (done, ended early, unused) and
 * the way on.
 */
internal fun MainActivity.flexResultScreen(flex: FlexPackage): Pair<View, Boolean> {
    val d = design
    val b = d.body(22)
    b.addView(d.titleBlock("FLEX завершён", narrow = true))
    heroSpace(b, 230)
    // The same words as before, now in a result card like the end of a series.
    val result = d.card(paddingDp = 20)
    result.addView(d.row().apply {
        addView(d.iconCircle(if (flex.earnedReward) Icon.CHECK else Icon.INFINITY, 52))
        addView(d.text("Успешно: ${flex.successful} из ${FlexPackage.PERIODS}", Typo.TITLE), d.weight().apply { leftMargin = d.dp(16) })
    })
    result.addView(kit.dots(FlexPackage.PERIODS, flex.successful, flex.used - flex.successful), d.gap(18))
    b.addView(result, d.gap(Spacing.GUTTER))
    val credits = Mama.entitlements(this).freeFlexCredits
    val next = if (credits > 0) {
        b.addView(d.noteCard("Следующий FLEX — бесплатно.", icon = Icon.STAR), d.gap(Spacing.GUTTER))
        d.primaryButton("Активировать бесплатный FLEX", large = true) { Mama.dismissFlex(this); activateFlex(GrantSource.REWARD) }
    } else {
        b.addView(d.text("Следующий бесплатный FLEX доступен только при результате 7 из 7.", Typo.CAPTION, Palette.InkMuted, center = true), d.gap(20))
        d.primaryButton("Новый FLEX — ${Texts.price(FlexPackage.PRICE_RUB)}", large = true) {
            Checkout.obtain(this, Product.FlexPackage, "MAMA FLEX") { source -> Mama.dismissFlex(this); activateFlex(source) }
        }
    }
    val home = d.linkButton("На главную") { Mama.dismissFlex(this); render() }
    return v1Frame(Scenery.DAY, d.topBar(null), b, d.bottomCta(next, home), layout = SceneLayout.HERO)
}
