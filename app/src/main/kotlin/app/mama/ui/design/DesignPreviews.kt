package app.mama.ui.design

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.mama.motivation.MotivationCatalog
import app.mama.motivation.Placement
import app.mama.ui.Icon

/**
 * Debug builds only: the UI v1 foundation on sample screens, for the
 * screenshot tour (`--es mama.preview ds_<name>`). Examples of the
 * components, not product screens: nothing here is wired to MAMA's logic.
 */
object DesignPreviews {
    val names = listOf(
        "ds_palette", "ds_type", "ds_buttons", "ds_cards", "ds_notes", "ds_light", "ds_scenic", "ds_recovery",
        "ds_lock_shell", "ds_sheet", "ds_test", "ds_scenes",
    )

    /** (view, light status bar). */
    fun render(a: Activity, name: String): Pair<View, Boolean> {
        val k = DesignKit(a)
        val view = when (name) {
            "ds_palette" -> palette(k)
            "ds_type" -> typography(k)
            "ds_buttons" -> buttons(k)
            "ds_cards" -> cards(k)
            "ds_notes" -> notes(k)
            "ds_light" -> lightScreen(k)
            "ds_scenic" -> scenicScreen(k)
            "ds_recovery" -> recovery(k)
            "ds_lock_shell" -> lockShell(k)
            "ds_sheet" -> sheet(a, k)
            "ds_test" -> testTools(k)
            "ds_scenes" -> scenes(k)
            else -> palette(k)
        }
        return view to true
    }

    private fun page(k: DesignKit, label: String, scenery: Scenery? = null, content: LinearLayout.() -> Unit): View {
        val body = k.body(14).apply(content)
        return k.screen(scenery, SceneLayout.HEADER, k.topBar(null, label), body)
    }

    private fun section(k: DesignKit, label: String) = k.text(label, Typo.TAG, Palette.InkSoft)

    // ---------------- palette ----------------

    private fun palette(k: DesignKit) = page(k, "Палитра") {
        addView(k.titleBlock("Палитра", "Светлая тема: айвори, изумруд, тёплый песок. Без красного."))
        val swatches = listOf(
            "bg" to Palette.Bg, "card" to Palette.Card, "cardQuiet" to Palette.CardQuiet, "line" to Palette.Line,
            "emeraldDeep" to Palette.EmeraldDeep, "emeraldLight" to Palette.EmeraldLight, "emeraldInk" to Palette.EmeraldInk, "emeraldBorder" to Palette.EmeraldBorder,
            "mintBadge" to Palette.MintBadge, "mintSelected" to Palette.MintSelected, "mintTag" to Palette.MintTag, "progressOff" to Palette.ProgressOff,
            "ringArc" to Palette.RingArc, "ringTrack" to Palette.RingTrack, "sand" to Palette.Sand, "sandSoft" to Palette.SandSoft,
            "olive" to Palette.Olive, "ink" to Palette.Ink, "inkMuted" to Palette.InkMuted, "inkSoft" to Palette.InkSoft,
            "testInk" to Palette.TestInk, "testFill" to Palette.TestFill, "testLine" to Palette.TestLine, "onEmerald" to Palette.OnEmerald,
        )
        swatches.chunked(4).forEachIndexed { r, line ->
            val row = k.row().apply { gravity = Gravity.TOP }
            line.forEachIndexed { i, (n, c) ->
                row.addView(k.column().apply {
                    addView(View(context).apply { background = k.shape(c, 12, Palette.Line) }, LinearLayout.LayoutParams(-1, k.dp(44)))
                    addView(k.text(n, Typo.CAPTION, Palette.Ink).apply { textSize = 11f }, k.gap(5))
                    addView(k.text("#%06X".format(c and 0xFFFFFF), Typo.CAPTION, Palette.InkMuted).apply { textSize = 10.5f }, k.gap(1))
                }, k.weight().apply { if (i > 0) leftMargin = k.dp(10) })
            }
            addView(row, k.gap(if (r == 0) 20 else 12))
        }
        addView(section(k, "Главная кнопка · прогресс"), k.gap(20))
        addView(View(context).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.EmeraldLight, Palette.EmeraldDeep),
            ).apply { cornerRadius = k.dpf(20f) }
        }, LinearLayout.LayoutParams(-1, k.dp(26)).apply { topMargin = k.dp(10) })
        addView(k.segments(4, 7), k.gap(12))
    }

    // ---------------- typography ----------------

    private fun typography(k: DesignKit) = page(k, "Типографика") {
        val samples = listOf(
            Typo.DISPLAY to "Всё готово",
            Typo.HEADLINE to "Начать заново",
            Typo.TITLE to "7 дней",
            Typo.VALUE to "21:00 – 07:00",
            Typo.SUBTITLE to "Ты выбрал путь, который помогает",
            Typo.BODY to "Доверенный выход всегда доступен.",
            Typo.LABEL to "Доверенный контакт",
            Typo.CAPTION to "Только внутри приложения",
            Typo.BUTTON to "Продолжить",
            Typo.WORDMARK to "MAMA",
            Typo.STEP to "6 из 6",
            Typo.TAG to "Рекомендуем",
            Typo.AMOUNT to "500 ₽",
            Typo.NUMBER to "6 ч 12 мин",
            Typo.QUOTE to "Каждая минута вне телефона",
            Typo.QUOTE_ITALIC to "Ты не ограничиваешь себя",
            Typo.QUOTE_SANS to "Уверенность растёт из обещаний",
        )
        samples.forEachIndexed { i, (style, sample) ->
            val row = k.row().apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(k.column().apply {
                addView(k.text(style.name.replace('_', ' '), Typo.TAG, Palette.InkSoft).apply { textSize = 9.5f })
                addView(k.text("${style.sizeSp.toString().removeSuffix(".0")} sp", Typo.CAPTION, Palette.InkSoft).apply { textSize = 10.5f }, k.gap(2))
            }, LinearLayout.LayoutParams(k.dp(84), -2))
            row.addView(k.text(sample, style, if (style == Typo.BUTTON) Palette.EmeraldInk else Palette.Ink).apply { maxLines = 1 }, k.weight())
            addView(row, k.gap(if (i == 0) 6 else 11))
        }
        addView(k.text("Manrope — интерфейс · Cormorant Garamond — цитаты", Typo.CAPTION, Palette.InkMuted), k.gap(16))
    }

    // ---------------- buttons, chips, tags ----------------

    private fun buttons(k: DesignKit) = page(k, "Кнопки") {
        addView(section(k, "Главная · неактивная · вторичная · текстовая"))
        addView(k.primaryButton("Начать 7 дней") {}, k.gap(10))
        addView(k.primaryButton("Продолжить") {}.apply { isEnabled = false }, k.gap(12))
        addView(k.secondaryButton("Изменить условия", Icon.CALENDAR) {}, k.gap(12))
        addView(k.linkButton("Отменить запуск") {}, k.gap(4))
        addView(section(k, "Чипы «Цены слова»"), k.gap(16))
        addView(k.chipGrid(listOf("500 ₽", "1 000 ₽", "3 000 ₽", "5 000 ₽", "Своя сумма"), 1) {}, k.gap(10))
        addView(section(k, "Плашки · фраза-плашка (не кнопка)"), k.gap(18))
        addView(k.row().apply {
            addView(k.tag("Рекомендуем"))
            addView(k.tag("Вечером"), k.wrap().apply { leftMargin = k.dp(8) })
            addView(k.tag("Утром", TagTone.SAND), k.wrap().apply { leftMargin = k.dp(8) })
            addView(k.testBadge("Тест"), k.wrap().apply { leftMargin = k.dp(8) })
        }, k.gap(10))
        addView(k.badge("Я держу курс", Icon.SPROUT), k.wrap().apply { topMargin = k.dp(10) })
        addView(section(k, "Согласие · прогресс шагов и дней"), k.gap(18))
        addView(k.checkRow("Я понимаю условия и принимаю своё решение.", true) {}, k.gap(4))
        addView(k.segments(2, 6), k.gap(8))
        addView(k.segments(5, 7), k.gap(10))
    }

    // ---------------- cards ----------------

    private fun cards(k: DesignKit) = page(k, "Карточки", Scenery.DAY) {
        addView(section(k, "Выбор (с пейзажем)"))
        val grid = k.row().apply { gravity = Gravity.TOP }
        grid.addView(k.selectableCard(Icon.SPROUT, "3 дня", "Мягкое начало. Restart — 99 ₽.", false) {}, k.weight())
        grid.addView(k.selectableCard(Icon.STAR, "7 дней", "Restart — 299 ₽.", true, tag = "Рекомендуем") {}, k.weight().apply { leftMargin = k.dp(12) })
        addView(grid, k.gap(10))
        addView(section(k, "Сводка «Твой план»"), k.gap(18))
        addView(k.summaryCard(null, listOf(
            SummaryItem(Icon.CALENDAR, "Длительность", "7 дней") {},
            SummaryItem(Icon.PERSON, "Доверенный контакт", "Анна") {},
            SummaryItem(Icon.COINS, "Цена слова", "500 ₽") {},
        )), k.gap(10))
        addView(section(k, "Действие-строка"), k.gap(18))
        addView(k.actionRow(Icon.HOME, "Вернуться на главную", "Продолжить, когда почувствуешь готовность.") {}, k.gap(10))
    }

    private fun notes(k: DesignKit) = page(k, "Карточки", Scenery.DAY) {
        addView(section(k, "Значение (только реальные данные)"))
        addView(k.valueCard(Icon.CLOCK, "До начала блокировки", k.hoursMinutes(5, 40), k.tag("Сегодня в 21:00")), k.gap(10))
        addView(section(k, "Заметки: спокойная · бережная (вместо красного)"), k.gap(18))
        addView(k.noteCard("Пройдёшь серию — сумма снова будет доступна на балансе MAMA.", "Цена слова: 500 ₽"), k.gap(10))
        addView(k.noteCard("Включи доступ MAMA до начала блокировки — без него серия не начнётся.", "Защита не готова", NoteTone.CARE), k.gap(10))
        addView(section(k, "Мотивация: цитаты"), k.gap(18))
        addView(k.quoteCard(MotivationCatalog.pick(Placement.REVIEW, "preview").text, italic = true, scenery = Scenery.DAWN), k.gap(10))
        addView(k.quoteCard(MotivationCatalog.pick(Placement.ACTIVE, "preview").text, accent = QuoteAccent.SAND, serif = false, markInCircle = true), k.gap(12))
    }

    // ---------------- example screens ----------------

    /** Light screen: the "Всё готово" composition (reference 09-review), sample data. */
    private fun lightScreen(k: DesignKit): View {
        val body = k.body(22)
        body.addView(k.titleBlock("Всё готово", "Ты выбрал путь, который помогает вернуть себя.", narrow = true))
        body.addView(k.summaryCard("Твой план", listOf(
            SummaryItem(Icon.CALENDAR, "Длительность", "7 дней") {},
            SummaryItem(Icon.CLOCK, "Время блокировки", "21:00 – 07:00") {},
            SummaryItem(Icon.PERSON, "Доверенный контакт", "Анна") {},
            SummaryItem(Icon.HEART, "Моя причина", "Сон и спокойствие") {},
            SummaryItem(Icon.COINS, "Цена слова", "500 ₽") {},
        )), k.gap(Spacing.SECTION))
        body.addView(k.quoteCard("Ты не ограничиваешь себя — ты выбираешь себя.", italic = true, scenery = Scenery.DAWN), k.gap(Spacing.GUTTER))
        body.addView(k.checkRow("Я понимаю условия и принимаю своё решение.", true) {}, k.gap(10))
        return k.screen(Scenery.DAWN, SceneLayout.HEADER, k.stepHeader(6, 6) {}, body, k.bottomCta(k.primaryButton("Начать 7 дней") {}))
    }

    /** Scenic screen: home of a running series (reference 13-dashboard_active), real-data cards only. */
    private fun scenicScreen(k: DesignKit): View {
        val header = k.column().apply {
            addView(k.topBar(null, "2 из 7"))
            addView(k.segments(2, 7), k.gap(4).apply { leftMargin = k.dp(Spacing.SCREEN); rightMargin = k.dp(Spacing.SCREEN) })
        }
        val body = k.body(26)
        body.addView(k.titleBlock("День 2 из 7", "Ты держишь курс.", narrow = true))
        body.addView(View(k.context), LinearLayout.LayoutParams(1, k.dp(200)))
        body.addView(k.valueCard(Icon.MOON, "До начала блокировки", k.hoursMinutes(5, 40), k.tag("Сегодня в 21:00")), k.fill())
        body.addView(k.valueCard(Icon.COINS, "Цена слова", "500 ₽", k.tag("В резерве до конца серии", TagTone.SAND)), k.gap(Spacing.GUTTER))
        body.addView(k.quoteCard(MotivationCatalog.pick(Placement.ACTIVE, "series:2").text, accent = QuoteAccent.SAND, serif = false, markInCircle = true), k.gap(Spacing.GUTTER))
        return k.screen(Scenery.MORNING, SceneLayout.HERO, header, body, nav = k.bottomNav(0) {})
    }

    /**
     * Recovery example (reference 20-failure): light, dawn, a road. The hero
     * line supports; the fact is small and neutral; Restart is a chance.
     */
    private fun recovery(k: DesignKit): View {
        val body = k.body(18)
        body.addView(k.titleBlock("Этот путь продолжается", narrow = true, caption = "Серия завершилась раньше · 2 из 7"))
        body.addView(k.offerCard(
            Icon.REFRESH, "Попробовать снова", "Restart · 299 ₽",
            "Ты можешь начать снова. Время выберешь заново.",
            k.primaryButton("Начать заново") {}, Scenery.RECOVERY,
        ), k.gap(Spacing.SECTION))
        body.addView(k.actionRow(Icon.HOME, "Вернуться на главную", "Продолжить, когда почувствуешь готовность.") {}, k.gap(Spacing.GUTTER))
        val phrase = MotivationCatalog.byId("recovery-02")?.text ?: MotivationCatalog.pick(Placement.RECOVERY, "preview").text
        body.addView(k.quoteCard(phrase, accent = QuoteAccent.SAND, signature = "MAMA", markInCircle = true, italic = true, scenery = Scenery.RECOVERY), k.gap(Spacing.GUTTER))
        body.addView(k.noteCard("Один сложный день не стирает твой прогресс.", "Твоя цель всё ещё с тобой.", icon = Icon.HEART), k.gap(Spacing.GUTTER))
        return k.screen(Scenery.RECOVERY, SceneLayout.HEADER, k.topBar({}), body)
    }

    /** Active lock shell (reference 16-lock): only two actions; "Я держу курс" is a badge, not a button. */
    private fun lockShell(k: DesignKit): View {
        val header = k.column().apply {
            addView(k.topBar(null, "2 из 7"))
            addView(k.segments(2, 7), k.gap(4).apply { leftMargin = k.dp(Spacing.SCREEN); rightMargin = k.dp(Spacing.SCREEN) })
        }
        val body = k.body(20).apply { gravity = Gravity.CENTER_HORIZONTAL }
        body.addView(k.titleBlock("Время для себя", "Сейчас ты в фокусе.\nТелефон под защитой до 07:00.", center = true), k.fill())
        val ring = k.timerRing()
        ring.set(6, 12, 0.32f, "День 2 из 7")
        body.addView(ring.view, (ring.view.layoutParams as LinearLayout.LayoutParams).apply { topMargin = k.dp(22) })
        body.addView(k.badge("Я держу курс", Icon.SPROUT), k.centered(16))
        val actions = k.row()
        actions.addView(k.actionTile(Icon.PHONE, "Экстренный звонок") {}, k.weight())
        actions.addView(k.actionTile(Icon.PEOPLE, "Мне действительно нужно выйти") {}, k.weight().apply { leftMargin = k.dp(12) })
        body.addView(actions, k.gap(22))
        body.addView(k.quoteCard(MotivationCatalog.pick(Placement.LOCK_NIGHT, "preview").text, scenery = Scenery.DAWN), k.gap(Spacing.GUTTER))
        return k.screen(Scenery.DAWN, SceneLayout.FULL, header, body)
    }

    // ---------------- dialogs ----------------

    private fun sheet(a: Activity, k: DesignKit): View {
        val view = page(k, "Диалоги", Scenery.DAY) {
            addView(k.titleBlock("Диалоги", "Снизу — шторка, по центру — диалог. Один стиль, без системных окон."))
            addView(MamaSheet.panel(
                k.context, "Своя сумма", "Сумма в рублях.", style = MamaSheet.Style.CENTER,
                content = k.textField("Например, 2000"),
                primary = { it.primaryButton("Готово", arrow = false) {} },
                secondary = { it.linkButton("Отмена") {} },
            ).apply { k.elevate(this, 6f) }, k.gap(Spacing.SECTION))
        }
        a.window.decorView.post {
            MamaSheet.show(
                a, "Отменить запуск?",
                "До первой блокировки — без последствий: ничего не списывается.",
                icon = Icon.CALENDAR, primary = "Оставить серию", secondary = "Отменить запуск",
            )
        }
        return view
    }

    // ---------------- test tools ----------------

    private fun testTools(k: DesignKit): View {
        val body = k.body(14)
        body.addView(k.titleBlock("Тестирование", "Только в тестовой сборке. На основных экранах этих кнопок нет."))
        body.addView(k.testBadge(), k.wrap().apply { topMargin = k.dp(12) })
        body.addView(k.testToolsCard(
            "Тестовая блокировка",
            "Ровно 15 минут. Бесплатно, не входит в серии и FLEX.",
            listOf(k.testButton("Тестовая блокировка · 15 минут") {}),
        ), k.gap(Spacing.SECTION))
        body.addView(k.testToolsCard(
            "Тестовые кредиты",
            "Пополняют тот же баланс MAMA и по-настоящему тратятся на Restart, FLEX и Цену слова.",
            listOf(k.testButton("+1 000 ₽ · тест") {}, k.testButton("+5 000 ₽ · тест") {}),
        ), k.gap(Spacing.GUTTER))
        return k.screen(Scenery.DAY, SceneLayout.HEADER, k.topBar({}), body)
    }

    // ---------------- sceneries ----------------

    private fun scenes(k: DesignKit) = page(k, "Фоны") {
        addView(k.text("Production-фоны MAMA (assets/scenes/v1). Если файла нет, фон рисуется в коде — запасной вариант.", Typo.LABEL, Palette.InkMuted))
        Scenery.entries.chunked(2).forEachIndexed { r, line ->
            val row = k.row().apply { gravity = Gravity.TOP }
            line.forEachIndexed { i, s ->
                row.addView(k.column().apply {
                    addView(FrameLayout(context).apply {
                        background = ScenicBackground(context, s, SceneLayout.FULL, Palette.Bg, k.dpf(14f))
                        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                        clipToOutline = true
                    }, LinearLayout.LayoutParams(-1, k.dp(176)))
                    addView(k.text(s.name, Typo.TAG, Palette.InkMuted), k.gap(6))
                }, k.weight().apply { if (i > 0) leftMargin = k.dp(12) })
            }
            if (line.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = k.dp(12) })
            addView(row, k.gap(if (r == 0) 14 else 12))
        }
        addView(View(context).apply { setBackgroundColor(Color.TRANSPARENT) }, LinearLayout.LayoutParams(1, k.dp(8)))
    }
}
