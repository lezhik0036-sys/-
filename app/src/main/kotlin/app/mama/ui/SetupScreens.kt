package app.mama.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import app.mama.billing.Checkout
import app.mama.billing.FeatureFlags
import app.mama.billing.Product
import app.mama.core.CommitmentConfig
import app.mama.core.DailyWindow
import app.mama.core.FlexPackage
import app.mama.core.GrantSource
import app.mama.core.StandardRules
import app.mama.core.TestMode
import app.mama.core.TrustedContact
import app.mama.motivation.MotivationCatalog
import app.mama.motivation.Placement
import app.mama.platform.Mama
import app.mama.platform.Promise
import app.mama.ui.MainActivity.Choice
import app.mama.ui.MainActivity.Step
import app.mama.ui.design.ArtLayout
import app.mama.ui.design.NoteTone
import app.mama.ui.design.PageDots
import app.mama.ui.design.Palette
import app.mama.ui.design.SceneLayout
import app.mama.ui.design.SceneMood
import app.mama.ui.design.Scenery
import app.mama.ui.design.Spacing
import app.mama.ui.design.SummaryItem
import app.mama.ui.design.Typo
import app.mama.ui.design.V2
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

// The setup wizard: Welcome → Permissions → Series → Time → Contact → Reason → Review.

/** Steps for the current choice (FLEX periods get their time later, per period). */
internal fun MainActivity.steps(): List<Step> =
    if (editing) {
        // "Изменить условия": only these open from the summary (length and time are fixed).
        listOfNotNull(Step.CONTACT, Step.PROMISE, Step.COMMITMENT.takeIf { FeatureFlags.WALLET_ENABLED })
    } else if (choice == Choice.FLEX) {
        listOf(Step.PERMISSIONS, Step.SERIES, Step.CONTACT, Step.PROMISE)
    } else {
        listOfNotNull(
            Step.PERMISSIONS, Step.SERIES, Step.TIME, Step.CONTACT, Step.PROMISE,
            // "Цена слова" is offered before every standard series (not the test mode).
            Step.COMMITMENT.takeIf { choice.series != null && FeatureFlags.WALLET_ENABLED },
        )
    }

internal fun MainActivity.nextStep(from: Step): Step = when {
    editing -> Step.REVIEW
    else -> wizardNext(from)
}

private fun MainActivity.wizardNext(from: Step): Step = when (from) {
    Step.WELCOME -> Step.PERMISSIONS
    Step.REVIEW -> Step.REVIEW
    else -> steps().getOrNull(steps().indexOf(from) + 1) ?: Step.REVIEW
}

internal fun MainActivity.back() {
    if (editing) {
        if (step == Step.REVIEW) stopEditing() else step = Step.REVIEW
        render()
        return
    }
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
    Step.COMMITMENT -> commitmentScreen(restart = false, kind = choice.series ?: app.mama.core.SeriesKind.SEVEN)
    Step.REVIEW -> reviewScreen()
}

/** "Далее" in the wizard; "Готово" when a setting was opened from "Изменить условия". */
internal fun MainActivity.nextLabel() = if (editing) "Готово" else "Далее"

/** Steps with a counter (DESIGN_SYSTEM_V1 §12.2): permissions come before the wizard, the review is its last step. */
private fun MainActivity.counted(): List<Step> = steps().filter { it != Step.PERMISSIONS } + Step.REVIEW

/** UI v1 step header: "‹ · M A M A · N из M" and segments; only "‹" while editing. */
internal fun MainActivity.stepBar(s: Step): View {
    val index = counted().indexOf(s)
    if (editing || index < 0) return design.topBar({ back() })
    return design.stepHeader(index + 1, counted().size) { back() }
}

/** Part of the day of a time, as a label only ("ВЕЧЕРОМ"); it never changes the window. */
private fun dayPart(t: LocalTime) = when (t.hour) {
    in 4..11 -> "Утром"
    in 12..16 -> "Днём"
    in 17..23 -> "Вечером"
    else -> "Ночью"
}

private fun partIcon(t: LocalTime) = when (t.hour) {
    in 4..11 -> Icon.SUNRISE
    in 12..16 -> Icon.SUN
    else -> Icon.MOON
}

/** The supportive line of a setup step: the reference's phrase from [MotivationCatalog]. */
internal fun stepPhrase(id: String, fallback: Placement): String = (MotivationCatalog.byId(id) ?: MotivationCatalog.pick(fallback, id)).text

/** "Сегодня в 23:00" → "сегодня в 23:00". */
private fun String.lowerFirst() = replaceFirstChar { it.lowercase() }

/** "8 ч", "8 ч 30 мин", "45 мин": the length of a window in one short line. */
private fun shortDuration(d: Duration): String {
    val total = maxOf(0L, d.toMinutes())
    val h = total / 60
    val m = total % 60
    return when {
        h == 0L -> "$m мин"
        m == 0L -> "$h ч"
        else -> "$h ч $m мин"
    }
}

// ---------------- 1. Welcome ----------------

/**
 * The approved welcome (docs/design-reference/nature_v3/01_welcome_full.webp),
 * kept as it is: its art — the MAMA mark and line, the woman embracing the
 * land, the golden valley, the card and the leaves around it — with the
 * mockup's iOS status bar and its baked texts and buttons removed, so the
 * real ones can stand in their places. 941 × 1672 px.
 */
internal const val WELCOME_ART = "art/welcome_nature.webp"

/** Tag of the welcome art (tests). */
internal const val WELCOME_HERO = "welcome_hero"

/** Where the welcome card's content sits on the art (its pixels). */
private object WelcomeArt {
    const val WIDTH = 941
    const val HEIGHT = 1672
    val ICON_LEFTS = intArrayOf(238, 420, 604)
    const val ICON_TOP = 1395
    const val ICON_SIZE = 98
}

/**
 * The first screen, exactly as approved: the art covers the screen and the
 * real title, text, "Начать путь", the three quiet notes under it and the
 * page dots stand where the art was drawn with them; a quiet "Мой путь" in
 * the sky (the way to everything done, 3A.1). Before the first use the button
 * leads through the onboarding into the same setup as before.
 */
internal fun MainActivity.welcomeScreen(): Pair<View, Boolean> {
    val d = design
    val screen = ArtLayout(this, WELCOME_ART, WelcomeArt.WIDTH, WelcomeArt.HEIGHT)
    screen.art.apply {
        tag = WELCOME_HERO
        contentDescription = "MAMA — твоя тихая опора на пути к более свободной жизни"
    }
    // "в MAMA" a little larger, as on the art; the non-breaking space keeps it on the second line.
    val title = SpannableString("Добро пожаловать в\u00A0MAMA").apply {
        setSpan(RelativeSizeSpan(1.14f), length - 4, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    screen.place(screen.text(d.text(title, Typo.DISPLAY, V2.Ink, center = true).apply {
        typeface = MamaFonts.serif(this@welcomeScreen, 500)
        setLineSpacing(0f, 0.9f)
    }, 54f), 190, 1016, 752, 1150)
    screen.place(screen.text(d.text(
        "Здесь ты не один. Природа напоминает:\nвсё можно изменить. Маленькие шаги\nведут к большой, свободной и осознанной жизни.",
        Typo.BODY, V2.InkMuted, center = true,
    ).apply {
        typeface = MamaFonts.ui(this@welcomeScreen, 400)
        setLineSpacing(0f, 1.08f)
    }, 25.5f), 150, 1146, 792, 1258)
    val start = d.primaryButton("Начать путь", large = true) {
        beginDraft()
        if (!prefs.getBoolean(MainActivity.ONBOARDING_SEEN, false)) onboardingPage = 1
        go(Step.PERMISSIONS)
    }
    screen.place(screen.text(start, 40f), 178, 1272, 768, 1372)
    // Three quiet notes, not buttons: what the path is about.
    listOf(Icon.SPROUT to "Забота\nо себе", Icon.MOUNTAIN to "Больше\nсвободы", Icon.SUN to "Осознанная\nжизнь").forEachIndexed { i, (icon, label) ->
        val x = WelcomeArt.ICON_LEFTS[i]
        screen.place(FrameLayout(this).apply {
            background = d.oval(V2.SandLight)
            d.elevate(this, 1f)
            val pad = d.dp(14)
            setPadding(pad, pad, pad, pad)
            addView(ImageView(this@welcomeScreen).apply { setImageDrawable(IconDrawable(icon, Color.rgb(0x62, 0x5E, 0x32), 1.5f)) })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, x, WelcomeArt.ICON_TOP, x + WelcomeArt.ICON_SIZE, WelcomeArt.ICON_TOP + WelcomeArt.ICON_SIZE)
        screen.place(screen.text(d.text(label, Typo.LABEL, Color.rgb(0x8A, 0x82, 0x70), center = true).apply {
            typeface = MamaFonts.ui(this@welcomeScreen, 400)
            setLineSpacing(0f, 1.0f)
        }, 24f), x - 52, 1500, x + WelcomeArt.ICON_SIZE + 52, 1562, center = false)
    }
    screen.place(PageDots(this, 3, 0), 410, 1578, 532, 1602)
    // A quiet way to everything already done, at any time between series (3A.1).
    val path = d.linkButton("Мой путь") { openPath() }.apply {
        tag = OPEN_PATH
        background = d.pressable(d.shape(Color.argb(0xB8, 0xFD, 0xF9, 0xF1), 22), 22)
    }
    screen.place(screen.text(path, 30f), 726, 92, 918, 150)
    return screen to true
}

// ---------------- Onboarding ----------------

/** One onboarding page: its title, the line under it and a quiet line in its card. */
internal class OnboardingPage(val title: String, val line: String, val note: String)

/** The three onboarding pages (docs/design-reference/nature_v3/02–04), in order. */
internal val ONBOARDING = listOf(
    OnboardingPage("Тебе не нужно становиться сильнее", "Тебе нужен ритм.", "Маленькие шаги ведут к большим переменам."),
    OnboardingPage("Днём ты принимаешь решение.", "Ночью MAMA помогает его сохранить.", "Ты выбираешь направление. MAMA помогает идти."),
    OnboardingPage("Начнём с нескольких ночей.", "Маленькая серия — большие изменения.", "Ты удивишься, насколько многое может измениться."),
)

/**
 * One onboarding page (1–3), as approved: the landscape over the whole
 * screen, its light growing page by page (quiet, calm, bright); the title and
 * its line in the sky; a cream card at the bottom with a quiet line, the page
 * dots and a round "→". After the last page: the setup, as before.
 */
internal fun MainActivity.onboardingScreen(page: Int): Pair<View, Boolean> {
    val d = design
    val content = ONBOARDING[page - 1]
    val narrow = (resources.displayMetrics.widthPixels * 0.84f).toInt()
    val b = d.body(40)
    b.addView(d.text(content.title, Typo.DISPLAY).apply { textSize = 35f; maxWidth = narrow }, d.wrap())
    b.addView(d.text(content.line, Typo.SUBTITLE, V2.InkMuted).apply { textSize = 21f; maxWidth = narrow }, d.wrap().apply { topMargin = d.dp(14) })
    val card = d.card(paddingDp = 22, radiusDp = V2.CARD_LARGE)
    card.addView(d.text(content.note, Typo.QUOTE, V2.InkMuted, center = true).apply { textSize = 20f }, d.fill().apply { topMargin = d.dp(6) })
    val row = d.row()
    row.addView(PageDots(this, ONBOARDING.size, page - 1), LinearLayout.LayoutParams(d.dp(66), d.dp(12)))
    row.addView(View(this), d.weight())
    row.addView(d.roundButton(Icon.ARROW_RIGHT, "Далее") {
        if (page < ONBOARDING.size) {
            onboardingPage = page + 1
        } else {
            prefs.edit().putBoolean(MainActivity.ONBOARDING_SEEN, true).apply()
            onboardingPage = 0
        }
        render()
    })
    card.addView(row, d.gap(22))
    val mood = listOf(SceneMood.QUIET, SceneMood.CALM, SceneMood.BRIGHT)[page - 1]
    return v1Frame(Scenery.DAWN, null, b, card, layout = SceneLayout.FULL, mood = mood)
}

/** Back from an onboarding page: the page before, or the welcome. */
internal fun MainActivity.onboardingBack() {
    onboardingPage--
    if (onboardingPage == 0) back() else render()
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
    b.addView(kit.caption("Нажми «Продолжить»: MAMA по очереди откроет нужные экраны — включи переключатель и вернись назад."), kit.gap(14))
    val cta = kit.primaryButton("Продолжить") {
        if (Requirement.missingRequired(this).isEmpty()) go(nextStep(Step.PERMISSIONS)) else startSetupAll()
    }
    return lightFrame(stepBar(Step.PERMISSIONS), b, cta)
}

// ---------------- 3. Series ----------------

internal fun MainActivity.seriesScreen(): Pair<View, Boolean> {
    val b = body()
    b.addView(kit.sectionHeader("Выбери свою серию", "Сколько дней ты готов не менять своё решение?"))
    var first = true
    fun option(c: Choice, icon: Icon, title: String, subtitle: String, trailing: String? = null) {
        b.addView(kit.selectableCard(icon, title, subtitle, choice == c, trailing) { choice = c; saveForm(); render() }, kit.gap(if (first) 22 else 10))
        first = false
    }
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
    return lightFrame(stepBar(Step.SERIES), b, cta)
}

/** Restart prices shown before start (from the core's [app.mama.core.SeriesKind]). */
internal object SeriesPrices {
    val THREE = app.mama.core.SeriesKind.THREE.restartPriceRub
    val FIVE = app.mama.core.SeriesKind.FIVE.restartPriceRub
    val SEVEN = app.mama.core.SeriesKind.SEVEN.restartPriceRub
}

// ---------------- 4. Time ----------------

internal fun MainActivity.timeScreen(): Pair<View, Boolean> {
    if (choice != Choice.TEST) {
        ensureDraftTime()
        refreshAutoNow()
    }
    val d = design
    val b = d.body(22)
    val now = Mama.trustedNow(this)
    if (choice == Choice.TEST) {
        b.addView(d.titleBlock("Тестовый режим", "Блокировка начнётся сразу после запуска и продлится ровно 15 минут.", narrow = true))
        val from = LocalDateTime.ofInstant(now, zone).toLocalTime()
        val to = LocalDateTime.ofInstant(now + TestMode.DURATION, zone).toLocalTime()
        b.addView(d.timeRange(
            d.timeTile("Начало", Texts.time(now, zone), dayPart(from), partIcon(from), "сразу после запуска", onClick = null),
            d.timeTile("Окончание", Texts.time(now + TestMode.DURATION, zone), dayPart(to), partIcon(to), "через 15 минут", onClick = null),
        ), d.gap(28))
        b.addView(d.noteCard("Только для тестирования: бесплатно, не входит в серии, не тратит FLEX-дни.", icon = Icon.FLASK), d.gap(18))
    } else {
        b.addView(d.titleBlock(
            // Restart: a new start of the same series, not a penalty.
            if (restartFlow) "Новый старт" else "Выбери время",
            if (restartFlow) {
                "Restart: ${Texts.seriesName(choice.series ?: app.mama.core.SeriesKind.SEVEN)}. Путь продолжается с первого дня — выбери спокойное окно."
            } else {
                "Одно спокойное окно, которое повторяется каждый день серии."
            },
            narrow = true,
        ))
        // AUTO_NOW (start untouched): the series starts at the confirmation, for the chosen length.
        // MANUAL: a fixed window, whose first lock is always a full one (never one already in progress).
        val auto = startMode == MainActivity.StartMode.AUTO_NOW && start != end
        val plan = if (auto || start == end) null else DailyWindow(start, end, zone).nextFullPlan(now, mode)
        val length = when {
            auto -> draftLength
            plan != null -> Duration.between(plan.start, plan.end)
            else -> Duration.ZERO // not a window yet: the note below says so
        }
        // Start and end side by side, read at a glance. MANUAL: the days of the first lock under them ("сегодня", "завтра").
        val today = LocalDateTime.ofInstant(now, zone).toLocalDate()
        fun day(at: java.time.Instant) = Texts.dayName(LocalDateTime.ofInstant(at, zone).toLocalDate(), today).lowerFirst()
        b.addView(d.timeRange(
            d.timeTile("Начало", Texts.hhmm(start), dayPart(start), partIcon(start), plan?.let { day(it.start) }) {
                DigitalTimePicker.show(this, "Начало блокировки", start) { pickStart(it); render() }
            },
            d.timeTile("Окончание", Texts.hhmm(end), dayPart(end), partIcon(end), plan?.let { day(it.end) }) {
                DigitalTimePicker.show(this, "Окончание блокировки", end) { pickEnd(it); render() }
            },
        ), d.gap(28))
        val rows = mutableListOf(d.infoRow(Icon.CLOCK, "Каждый день · ${shortDuration(length)}", strong = true))
        if (auto) rows += d.infoRow(Icon.CALENDAR, "Начать после подтверждения", note = "Начало уточнится в момент запуска.")
        val change = d.linkButton("Изменить") { pickZone() }.apply {
            textSize = 14f
            setPadding(d.dp(10), d.dp(8), d.dp(10), d.dp(8))
        }
        rows += d.infoRow(Icon.GLOBE, "Часовой пояс: ${Texts.zone(zone)}", trailing = change)
        b.addView(d.rowsCard(rows), d.gap(18))
        if (start == end) {
            b.addView(d.noteCard("Выбери другое время окончания.", "Начало и окончание совпадают", NoteTone.CARE, Icon.CLOCK), d.gap(18))
        }
        val night = start.hour >= 17 || start.hour < 5
        b.addView(d.phraseCard(if (night) stepPhrase(NIGHT_PHRASE, Placement.LOCK_NIGHT) else MotivationCatalog.pick(Placement.LOCK_DAY, "time").text), d.gap(18))
    }
    val cta = d.primaryButton("Далее") {
        when {
            choice != Choice.TEST && start == end -> alert("Проверь время", "Начало и окончание совпадают.")
            restartFlow -> {
                saveForm()
                if (FeatureFlags.WALLET_ENABLED) {
                    restartStep = Step.COMMITMENT
                    render()
                } else {
                    payRestart(choice.series ?: app.mama.core.SeriesKind.SEVEN)
                }
            }
            else -> go(nextStep(Step.TIME))
        }
    }
    val header = if (restartFlow) {
        d.stepHeader(1, if (FeatureFlags.WALLET_ENABLED) 2 else 1) { restartFlow = false; render() }
    } else {
        stepBar(Step.TIME)
    }
    // Restart: calm, respectful and neutral — the quiet morning, not the path of Recovery.
    return if (restartFlow) {
        v1Frame(Scenery.MORNING, header, b, d.bottomCta(cta), mood = SceneMood.QUIET)
    } else {
        v1Frame(Scenery.DAY, header, b, d.bottomCta(cta))
    }
}

/** Phrases of the setup references (04, 06, 08, 09) in [MotivationCatalog]. */
internal const val NIGHT_PHRASE = "sleep-13"
internal const val SUPPORT_PHRASE = "confidence-13"
internal const val WEIGHT_PHRASE = "discipline-13"
internal const val CHOICE_PHRASE = "long-term-10"

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
    val d = design
    val b = d.body(22)
    b.addView(d.titleBlock("Кто поддержит тебя?", "Опора рядом помогает пройти путь спокойнее.", narrow = true))
    val name = contactName.text.toString().trim()
    val phone = contactPhone.text.toString().trim()
    when {
        manualContact -> b.addView(d.card().apply {
            addView(d.text("Имя", Typo.LABEL, Palette.InkMuted))
            addView(contactName, d.gap(6))
            addView(d.text("Телефон", Typo.LABEL, Palette.InkMuted), d.gap(14))
            addView(contactPhone, d.gap(6))
        }, d.gap(28))
        name.isNotEmpty() && phone.isNotEmpty() -> b.addView(d.contactCard("Доверенный контакт", name, Texts.phone(phone)) { pickContact() }, d.gap(28))
        else -> b.addView(d.contactCard("Доверенный контакт", "Выбери человека", "Нажми, чтобы выбрать из контактов") { pickContact() }, d.gap(28))
    }
    val links = d.row().apply { gravity = Gravity.CENTER_HORIZONTAL }
    if (manualContact) links.addView(d.linkButton("Выбрать из контактов") { pickContact() })
    links.addView(d.linkButton(if (manualContact) "Готово" else "Ввести вручную") {
        saveForm()
        manualContact = !manualContact
        render()
    })
    b.addView(links, d.gap(6))
    b.addView(d.noteCard(
        "Если понадобится выйти раньше, этому человеку придёт SMS с кодом из ${Mama.policy.codeLength} цифр. " +
            "У тебя будет максимум ${Mama.policy.maxAttempts} попытки ввода.",
        "Досрочный выход — только через этот контакт", icon = Icon.SHIELD,
    ), d.gap(10))
    b.addView(d.phraseCard(stepPhrase(SUPPORT_PHRASE, Placement.SCHEDULED)), d.gap(18))
    val cta = d.primaryButton(nextLabel()) {
        if (readyContact() != null) go(nextStep(Step.CONTACT))
    }
    return v1Frame(Scenery.DAY, stepBar(Step.CONTACT), b, d.bottomCta(cta))
}

/** The contact from the form, or null with a message. */
internal fun MainActivity.readyContact(): TrustedContact? {
    saveForm()
    val name = contactName.text.toString().trim()
    val phone = TrustedContact.normalizePhone(contactPhone.text.toString())
    if (name.isEmpty() || phone == null) {
        alert("Доверенный контакт", "Выбери контакт или укажи имя и номер в формате +7 900 123-45-67.")
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
    val cta = kit.primaryButton(if (editing) "Готово" else "Продолжить") {
        val text = promiseField.text.toString().trim()
        if (text.length < 3) {
            alert("Одна фраза", "Напиши, зачем тебе эта пауза — хотя бы несколько слов.")
        } else {
            // While editing, the reason is saved together with the other changes.
            if (!editing) Promise.set(this, text)
            go(nextStep(Step.PROMISE))
        }
    }
    return lightFrame(stepBar(Step.PROMISE), b, cta)
}

// ---------------- 7. Review ----------------

internal fun MainActivity.reviewScreen(): Pair<View, Boolean> {
    if (editing) return editScreen()
    val d = design
    val b = d.body(22)
    val contactLine = contactName.text.toString().trim()
    val reason = promiseField.text.toString().trim().ifEmpty { Promise.get(this) }
    // Every row opens its step again (when that step is part of this wizard).
    fun open(s: Step): (() -> Unit)? = if (s in steps()) ({ go(s) }) else null
    // First level: the four things that matter, large. Second level: short facts, the full terms one tap away.
    val items = mutableListOf<SummaryItem>()
    val facts = mutableListOf<View>()
    fun fact(icon: Icon, text: String, onClick: (() -> Unit)? = null) {
        facts += d.infoRow(icon, text, trailing = onClick?.let { d.icon(Icon.CHEVRON_RIGHT, Palette.InkMuted, 20, solid = false) }).apply {
            onClick?.let { click ->
                background = d.pressable(ColorDrawable(Color.TRANSPARENT), 0)
                setOnClickListener { click() }
            }
        }
    }
    val reasonLine = "Моя причина: «$reason»"
    val subtitle: String
    val ctaLabel: String
    val needsAgreement: Boolean
    when (choice) {
        Choice.TEST -> {
            subtitle = "Проверь условия. Если всё верно — запускаем тест."
            items += SummaryItem(Icon.FLASK, "Режим", "Тестовый режим", null, open(Step.SERIES))
            items += SummaryItem(Icon.CLOCK, "Время", "Сразу после запуска · 15 минут")
            items += SummaryItem(Icon.PERSON, "Доверенный контакт", contactLine, null, open(Step.CONTACT))
            items += SummaryItem(Icon.COINS, "Стоимость", "Бесплатно")
            fact(Icon.HEART, reasonLine, open(Step.PROMISE))
            fact(Icon.FLASK, "Только для тестирования: без Restart, не тратит FLEX-дни.")
            ctaLabel = "Начать тест 15 минут"
            needsAgreement = false
        }
        Choice.FLEX -> {
            subtitle = "FLEX — разовый пакет, не подписка."
            val free = Mama.entitlements(this).freeFlexCredits > 0
            items += SummaryItem(Icon.INFINITY, "Пакет", "MAMA FLEX", null, open(Step.SERIES))
            items += SummaryItem(Icon.CLOCK, "Периоды", "${FlexPackage.PERIODS} за ${FlexPackage.VALIDITY.toDays()} дней")
            items += SummaryItem(Icon.PERSON, "Доверенный контакт", contactLine, null, open(Step.CONTACT))
            items += SummaryItem(Icon.COINS, "Стоимость", if (free) "Бесплатно" else Texts.price(FlexPackage.PRICE_RUB))
            fact(Icon.CLOCK, "Каждый период — от 3 до 24 часов, дата и время — отдельно.")
            fact(Icon.STAR, "Пройди 7 из 7 — следующий FLEX бесплатно.")
            fact(Icon.HEART, reasonLine, open(Step.PROMISE))
            ctaLabel = "Активировать FLEX"
            needsAgreement = true
        }
        else -> {
            val kind = choice.series!!
            subtitle = "Ты выбрал путь, который помогает вернуть себя."
            items += SummaryItem(Icon.CALENDAR, "Серия", Texts.seriesName(kind), null, open(Step.SERIES))
            val now = Mama.trustedNow(this)
            when (val time = launchTime()) {
                is app.mama.core.LaunchTime.AutoNow -> {
                    val w = app.mama.core.SeriesDraft.resolve(time, now).window
                    items += SummaryItem(Icon.CLOCK, "Время блокировки", "${Texts.hhmm(w.start)} – ${Texts.hhmm(w.end)}", null, open(Step.TIME))
                    fact(Icon.MOON, "Первая блокировка — сразу после подтверждения, ${Texts.flexDuration(time.length)}. Начало уточнится в момент запуска.")
                }
                is app.mama.core.LaunchTime.Fixed -> {
                    items += SummaryItem(Icon.CLOCK, "Время блокировки", "${Texts.hhmm(start)} – ${Texts.hhmm(end)}", null, open(Step.TIME))
                    fact(Icon.MOON, "Первая блокировка — ${Texts.firstLock(time.window.nextFullPlan(now, mode).start, zone, now).lowerFirst()} · ${zone.id}")
                }
            }
            items += SummaryItem(Icon.PERSON, "Доверенный контакт", contactLine, null, open(Step.CONTACT))
            if (FeatureFlags.WALLET_ENABLED) {
                val c = commitmentKop
                items += SummaryItem(Icon.COINS, "Цена слова", if (c != null) Texts.money(c) else "Без цены слова", null, open(Step.COMMITMENT))
            }
            fact(Icon.REFRESH, "Restart при срыве — ${Texts.price(kind.restartPriceRub)}. Старт серии бесплатный.")
            fact(Icon.HEART, reasonLine, open(Step.PROMISE))
            ctaLabel = "Начать ${Texts.seriesName(kind)}"
            needsAgreement = true
        }
    }
    b.addView(d.titleBlock("Всё готово", subtitle, narrow = true))
    b.addView(d.summaryCard("Твой план", items, large = true), d.gap(24))
    b.addView(d.text(stepPhrase(CHOICE_PHRASE, Placement.REVIEW), Typo.QUOTE_ITALIC, Palette.InkMuted, center = true), d.gap(22))
    b.addView(d.rowsCard(facts).apply {
        background = d.surface(Palette.CardQuiet)
        d.elevate(this, 1f)
    }, d.gap(22))
    choice.series?.let { kind ->
        // The full terms: one tap away, never hidden from the decision.
        b.addView(d.linkButton(if (termsOpen) "Скрыть условия" else "Все условия серии") { termsOpen = !termsOpen; render() }, d.gap(4))
        if (termsOpen) {
            b.addView(d.noteCard(
                "Прогресс обнулится. Сразу начать заново — Restart за ${Texts.price(kind.restartPriceRub)}, " +
                    "бесплатная новая серия — через ${StandardRules.FREE_START_AFTER.toDays()} дней.\n\n" +
                    "Доверенный выход всегда доступен и не требует отдельной оплаты.\n\n" +
                    "Если серия будет завершена досрочно, она считается сорванной. Если установлена «Цена слова», она применяется.",
                "Если серия будет прервана", icon = Icon.SHIELD,
            ), d.gap(8))
            b.addView(d.noteCard(
                SCHEDULED_NOTE + (commitmentKop?.let { " Цена слова: ${Texts.money(it)} — резерв с баланса MAMA, к Restart не прибавляется." } ?: ""),
                icon = Icon.LOCK,
            ), d.gap(Spacing.GUTTER))
        }
    }
    val cta = d.primaryButton(ctaLabel, large = true) { startChosen() }
    val footer = d.column()
    if (needsAgreement) {
        footer.addView(d.checkRow("Я понимаю условия и принимаю ответственность за своё решение.", agreed) { agreed = it; render() })
        cta.isEnabled = agreed
    }
    footer.addView(cta, d.gap(if (needsAgreement) 8 else 0))
    return v1Frame(Scenery.DAY, stepBar(Step.REVIEW), b, footer)
}

internal fun MainActivity.startChosen() {
    val contact = readyContact() ?: return
    when (choice) {
        Choice.TEST -> when (val r = Mama.startTest(this, contact, zone, mode)) {
            is Mama.TestStart.Started -> {
                prefs.edit().putString("testSession", r.sessionId).apply()
                closeDraft()
            }
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
        else -> when (val r = Mama.startSeries(this, choice.series!!, launchTime(), contact, mode, commitmentKop)) {
            Mama.SeriesStart.Started -> {
                commitmentKop = null
                closeDraft()
            }
            Mama.SeriesStart.Busy -> return alert("Не получилось", "Сейчас уже идёт серия, FLEX-период или блокировка.")
            is Mama.SeriesStart.Waiting -> return alert(
                "Бесплатная серия пока недоступна",
                "Следующая бесплатная серия через ${Texts.days(r.days.toInt())}. Телефон при этом не заблокирован.",
            )
            is Mama.SeriesStart.NotEnoughBalance -> return notEnoughBalance { commitmentKop = null; startChosen() }
            Mama.SeriesStart.Rejected -> return alert("Не получилось", "Серию сейчас нельзя начать.")
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
        closeDraft()
        agreed = false
        step = Step.WELCOME
        tab = 0
    }
    render()
}
