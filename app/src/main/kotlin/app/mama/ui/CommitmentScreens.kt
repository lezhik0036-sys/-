package app.mama.ui

import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import app.mama.billing.Checkout
import app.mama.billing.FeatureFlags
import app.mama.billing.Product
import app.mama.core.CommitmentConfig
import app.mama.core.CommitmentState
import app.mama.core.LedgerEntry
import app.mama.core.LedgerStatus
import app.mama.core.LedgerType
import app.mama.core.SeriesKind
import app.mama.core.StartSource
import app.mama.core.Wallet
import app.mama.core.WalletEngine
import app.mama.motivation.Placement
import app.mama.platform.Mama
import app.mama.ui.MainActivity.Step
import app.mama.ui.design.NoteTone
import app.mama.ui.design.Palette
import app.mama.ui.design.SceneMood
import app.mama.ui.design.Scenery
import app.mama.ui.design.Spacing
import app.mama.ui.design.Typo
import java.time.ZoneId

// "Цена слова" (optional commitment before every standard series) and the
// internal MAMA balance. Pilot: test credits only, internal only.

/**
 * Quick amounts on the "Цена слова" screen, in kopecks: 500 · 1 000 · 3 000 ·
 * 5 000 ₽ (DESIGN_SYSTEM_V1 §12.3). The core's limits ([CommitmentConfig])
 * apply to every amount.
 */
private val QUICK = listOf(500L, 1_000L, 3_000L, 5_000L).map { it * 100 }

/**
 * Smallest own amount: the core minimum, and at a Restart never below the
 * Restart price (§12.3, checked here, in the interface).
 */
private fun minAmountKop(restart: Boolean, kind: SeriesKind): Long =
    if (restart) maxOf(CommitmentConfig.MIN_KOP, kind.restartPriceRub * 100L) else CommitmentConfig.MIN_KOP

/**
 * "Цена слова" (reference 08). Shown before every standard series: the
 * wizard (first or later free series) and the paid Restart ([restart] =
 * true). Optional: "Продолжить без цены слова" starts the series without it.
 * Never added to the Restart price.
 */
internal fun MainActivity.commitmentScreen(restart: Boolean, kind: SeriesKind, wallet: Wallet = Mama.wallet(this)): Pair<View, Boolean> {
    val d = design
    // While editing a scheduled series its own reserve can be reused: it comes back in full first.
    val ownReserve = if (editing) scheduledReserveOfCurrentSeries(wallet) else 0L
    val usable = wallet.availableKop + ownReserve
    val chosen = commitmentKop
    val b = d.body(22)
    b.addView(d.titleBlock(
        "Цена слова",
        (if (restart) "Restart: ${Texts.seriesName(kind)}. " else "") + "Это не наказание, а вес твоего решения.",
        narrow = true,
    ))

    // The amount: what is chosen, the quick amounts and "Своя сумма", what it is for.
    val card = d.card(paddingDp = 18)
    val head = d.row()
    head.addView(d.iconCircle(Icon.COINS, 72))
    val texts = d.column()
    texts.addView(d.text(if (chosen != null) "Выбранная сумма" else "Сумма не выбрана", Typo.LABEL, Palette.InkMuted))
    texts.addView(d.text(if (chosen != null) Texts.money(chosen) else "—", Typo.AMOUNT).apply { maxLines = 1 }, d.gap(2))
    texts.addView(d.text("На балансе MAMA: ${Texts.money(usable)}", Typo.CAPTION, Palette.InkMuted), d.gap(4))
    head.addView(texts, d.weight().apply { leftMargin = d.dp(18) })
    card.addView(head)
    val custom = chosen?.takeIf { it !in QUICK }
    val labels = QUICK.map { Texts.money(it) } + (custom?.let { "Своя: ${Texts.money(it)}" } ?: "Своя сумма")
    val selected = if (custom != null) QUICK.size else QUICK.indexOf(chosen)
    card.addView(d.chipGrid(labels, selected) { i ->
        if (i == QUICK.size) customAmount(minAmountKop(restart, kind)) else { commitmentKop = QUICK[i]; render() }
    }, d.gap(18))
    card.addView(d.divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d.dp(1)).apply { topMargin = d.dp(18) })
    val why = d.row()
    why.addView(d.iconCircle(Icon.SHIELD, 42))
    why.addView(d.text("Сумма помогает удержать слово и возвращает внимание к цели.", Typo.LABEL, Palette.InkMuted).apply { textSize = 14.5f }, d.weight().apply { leftMargin = d.dp(14) })
    card.addView(why, d.gap(14))
    b.addView(card, d.gap(26))

    if (ownReserve > 0) {
        b.addView(d.noteCard("Новая сумма заменит её без потерь.", "Сейчас в резерве для этой серии: ${Texts.money(ownReserve)}"), d.gap(Spacing.GUTTER))
    }
    if (chosen != null && chosen > usable) {
        // Not enough on the balance: a calm choice, never a warning.
        val note = d.column()
        note.addView(d.noteCard("Выбрано ${Texts.money(chosen)}, доступно ${Texts.money(usable)}.", NOT_ENOUGH, NoteTone.CARE, Icon.COINS))
        val choices = d.row().apply { gravity = Gravity.CENTER_HORIZONTAL }
        choices.addView(d.linkButton("Изменить сумму") { commitmentKop = null; render() })
        choices.addView(d.linkButton("Без Цены слова") { commitmentKop = null; proceedCommitment(restart, kind) })
        note.addView(choices, d.gap(4))
        if (FeatureFlags.WALLET_TEST_TOPUP_ENABLED) {
            // Test build only, as at a payment with too little on the balance.
            val top = d.row()
            listOf(1_000_00L, 5_000_00L).forEachIndexed { i, amount ->
                top.addView(d.testButton("+${Texts.money(amount)} · тест") {
                    Mama.topUpTest(this, amount)
                    render()
                }, d.weight().apply { if (i > 0) leftMargin = d.dp(10) })
            }
            note.addView(top, d.gap(4))
        }
        b.addView(note, d.gap(Spacing.GUTTER))
    }

    b.addView(d.quoteCard(stepPhrase(WEIGHT_PHRASE, Placement.COMMITMENT)), d.gap(18))
    // The rules, quietly and one per line (never a word about a return: it is not announced).
    b.addView(d.text("Как это работает", Typo.VALUE), d.gap(26))
    val rules = listOf(
        d.infoRow(Icon.CHECK, "Серия пройдена — вся сумма снова доступна на твоём балансе MAMA."),
        d.infoRow(Icon.SHIELD, "Серия завершена досрочно — она считается сорванной, и Цена слова применяется."),
        d.infoRow(
            Icon.REFRESH,
            "Restart при срыве — отдельно: ${Texts.price(kind.restartPriceRub)}. Цена слова к нему не прибавляется." +
                if (restart) "" else " Сама серия остаётся бесплатной.",
        ),
        d.infoRow(Icon.LOCK, "Баланс MAMA — только внутри приложения: его нельзя вывести или перевести."),
    )
    b.addView(d.rowsCard(rules).apply {
        background = d.surface(Palette.CardQuiet)
        d.elevate(this, 1f)
    }, d.gap(10))
    if (restart) {
        b.addView(d.noteCard(
            "После Restart серия будет запланирована: длина та же, время — выбранное. До первой блокировки можно изменить " +
                "доверенный контакт, личную причину и Цену слова. Restart с баланса MAMA засчитывается, только когда серия начнётся: " +
                "если отменить запуск, он вернётся на баланс.",
            icon = Icon.CALENDAR,
        ), d.gap(Spacing.GUTTER))
    }

    val go = d.primaryButton("Продолжить") { proceedCommitment(restart, kind) }
    go.isEnabled = chosen != null && chosen <= usable
    val skip = d.linkButton(if (editing) "Без цены слова" else "Продолжить без цены слова") { commitmentKop = null; proceedCommitment(restart, kind) }
    val header = when {
        restart -> d.stepHeader(2, 2) { restartStep = Step.TIME; render() }
        else -> stepBar(Step.COMMITMENT)
    }
    // Restart is a second chance: its own light scene.
    // Restart: the quiet morning (neutral), not the path of Recovery.
    return if (restart) {
        v1Frame(Scenery.MORNING, header, b, d.bottomCta(go, skip), mood = SceneMood.QUIET)
    } else {
        v1Frame(Scenery.DAY, header, b, d.bottomCta(go, skip))
    }
}

private fun MainActivity.customAmount(min: Long) {
    val field = kit.textField("Например, 2000", InputType.TYPE_CLASS_NUMBER).apply {
        filters = arrayOf(InputFilter.LengthFilter(6))
        commitmentKop?.let { setText((it / 100).toString()) }
    }
    val max = CommitmentConfig.MAX_KOP
    val error = kit.text("", MamaType.CAPTION, MamaColors.Warning).apply { visibility = View.GONE }
    val content = kit.column().apply {
        addView(field, kit.fill())
        addView(kit.caption("Сумма в рублях: от ${Texts.money(min)} до ${Texts.money(max)}."), kit.gap(8))
        addView(error, kit.gap(6))
    }
    BrandDialog.show(this, "Своя сумма", content = content, confirm = "Готово", cancel = "Отмена") {
        val rub = field.text.toString().toLongOrNull()
        val kop = rub?.times(100)
        if (kop == null || kop < min || kop > max) {
            error.text = "Укажи сумму от ${Texts.money(min)} до ${Texts.money(max)}."
            error.visibility = View.VISIBLE
            false
        } else {
            commitmentKop = kop
            render()
            true
        }
    }
}

/** "Цена слова" already set aside for the series being edited (released in full if changed). */
private fun MainActivity.scheduledReserveOfCurrentSeries(wallet: Wallet): Long {
    val series = Mama.series(this) ?: return 0
    return wallet.commitmentFor(series.id)?.takeIf { it.state == CommitmentState.SCHEDULED }?.amountKop ?: 0
}

private fun MainActivity.proceedCommitment(restart: Boolean, kind: SeriesKind) {
    if (!restart) {
        go(Step.REVIEW)
        return
    }
    finishRestart(kind)
}

/**
 * Final step of "Начать заново". The source comes from the one shared
 * resolver ([Mama.startSource]): a free start when the 30-day wait is over,
 * otherwise a paid Restart from the MAMA balance. The chosen time and "Цена
 * слова" carry over either way.
 */
internal fun MainActivity.finishRestart(kind: SeriesKind) {
    val time = launchTime()
    val commitment = commitmentKop
    when (Mama.startSource(this, kind)) {
        StartSource.Free -> startRestartFree(kind, time, commitment)
        is StartSource.PaidRestart, is StartSource.PaidRestartInsufficientFunds -> payRestartFromBalance(kind, time, commitment)
    }
}

/** Paid Restart (store or balance) for the chosen time and "Цена слова"; the series is then SCHEDULED. */
private fun MainActivity.payRestartFromBalance(kind: SeriesKind, time: app.mama.core.LaunchTime, commitment: Long?) {
    val schedule = when (time) {
        is app.mama.core.LaunchTime.AutoNow -> "старт сразу после подтверждения, ${Texts.flexDuration(time.length)} каждый день"
        is app.mama.core.LaunchTime.Fixed -> "${Texts.hhmm(start)} → ${Texts.hhmm(end)} каждый день"
    }
    val note = "${Texts.seriesName(kind)}, $schedule. " +
        "Если отменить запуск до первой блокировки, Restart вернётся на баланс." +
        (commitment?.let { " Цена слова: ${Texts.money(it)} (резерв с баланса MAMA)." } ?: " Без Цены слова.")
    Checkout.obtain(this, Product.Restart(kind), "Новый старт · Restart", reserveAheadKop = commitment ?: 0, note = note) { source ->
        when (Mama.restartSeries(this, source, time, commitment)) {
            Mama.SeriesStart.Started -> {
                restartFlow = false
                commitmentKop = null
                closeDraft()
            }
            is Mama.SeriesStart.NotEnoughBalance -> notEnoughBalance { commitmentKop = null; finishRestart(kind) }
            Mama.SeriesStart.TrustedExitUnavailable -> alert("Trusted Exit недоступен", "Серия не запущена.")
            else -> alert("Не получилось", "Restart сейчас недоступен.")
        }
        render()
    }
}

/** A free start of the same length — a копилка credit (spent), or a free start when the wait is over — reusing the broken series' contact. */
private fun MainActivity.startRestartFree(kind: SeriesKind, time: app.mama.core.LaunchTime, commitment: Long?) {
    val broken = Mama.series(this)
    val contact = broken?.contact
    if (contact == null) {
        alert("Не получилось", "Серию сейчас нельзя начать.")
        return
    }
    when (Mama.startSeries(this, kind, time, contact, broken.mode, commitment)) {
        Mama.SeriesStart.Started -> {
            restartFlow = false
            commitmentKop = null
            closeDraft()
        }
        is Mama.SeriesStart.NotEnoughBalance -> notEnoughBalance { commitmentKop = null; finishRestart(kind) }
        Mama.SeriesStart.TrustedExitUnavailable -> alert("Trusted Exit недоступен", "Серия не запущена.")
        else -> alert("Не получилось", "Серию сейчас нельзя начать.")
    }
    render()
}

internal const val NOT_ENOUGH = "На балансе MAMA недостаточно средств для выбранной Цены слова."

/** Calm choice when the balance does not cover the chosen "Цена слова". */
internal fun MainActivity.notEnoughBalance(withoutCommitment: () -> Unit) {
    BrandDialog.show(
        this, "Цена слова", message = NOT_ENOUGH,
        confirm = "Без Цены слова", cancel = "Изменить сумму",
        onCancel = { if (onWizard) go(MainActivity.Step.COMMITMENT) else render() },
    ) {
        withoutCommitment()
        true
    }
}

// ---------------- balance ----------------

/** "Баланс MAMA": available / reserved, internal-only note, test top-ups. */
internal fun MainActivity.balanceCard(wallet: Wallet, compact: Boolean = false): View = kit.card(16).apply {
    val head = kit.row()
    head.addView(kit.iconCircle(Icon.SHIELD, 40))
    val t = kit.column()
    t.addView(design.text("Баланс MAMA", Typo.VALUE))
    t.addView(design.text("Только внутри приложения", Typo.LABEL, Palette.InkMuted), kit.gap(2))
    head.addView(t, kit.weight().apply { leftMargin = kit.dp(12) })
    addView(head)
    // The two sums in the serif figures of the whole app, large enough to read at a glance.
    val nums = kit.row()
    nums.addView(kit.column().apply {
        addView(design.text("Доступно", Typo.LABEL, Palette.InkMuted))
        addView(design.text(Texts.money(wallet.availableKop), Typo.NUMBER).apply { textSize = 32f }, kit.gap(2))
    }, kit.weight())
    nums.addView(kit.column().apply {
        addView(design.text("В резерве", Typo.LABEL, Palette.InkMuted))
        addView(design.text(Texts.money(wallet.reservedKop + wallet.restartReservedKop), Typo.NUMBER).apply { textSize = 32f }, kit.gap(2))
    }, kit.weight())
    addView(nums, kit.gap(14))
    if (wallet.restartReservedKop > 0) {
        addView(kit.text(
            "Из них Restart ${Texts.money(wallet.restartReservedKop)} — до начала серии. Если отменить запуск, вернётся.",
            MamaType.SMALL, MamaColors.TextSecondary,
        ), kit.gap(6))
    }
    if (FeatureFlags.WALLET_TEST_TOPUP_ENABLED) {
        val row = kit.row()
        listOf(1_000_00L, 5_000_00L).forEachIndexed { i, amount ->
            row.addView(kit.secondaryButton("+${Texts.money(amount)} · тест") {
                Mama.topUpTest(this@balanceCard, amount)
                render()
            }.apply { minHeight = kit.dp(44); textSize = 17f }, kit.weight().apply { if (i > 0) leftMargin = kit.dp(8) })
        }
        addView(row, kit.gap(14))
        addView(kit.text("Тестовая сборка: кнопки добавляют тестовые кредиты, не деньги. Пополнение картой не подключено. Вывести или перевести баланс нельзя.", MamaType.SMALL, MamaColors.TextSecondary), kit.gap(8))
    } else if (!compact) {
        addView(kit.text("Вывести или перевести баланс нельзя.", MamaType.SMALL, MamaColors.TextSecondary), kit.gap(8))
    }
}

/** Ledger history (newest first), for the settings screen. */
internal fun MainActivity.ledgerList(wallet: Wallet, zone: ZoneId): View = kit.card(paddingDp = 0).apply {
    // A partial return is not announced: it appears only once it has really been credited.
    val entries = wallet.entries
        .filterNot { it.type == LedgerType.COMMITMENT_PARTIAL_RETURN && it.status == LedgerStatus.SCHEDULED }
        .sortedByDescending { it.at }
        .take(12)
    if (entries.isEmpty()) {
        addView(kit.caption("Операций пока нет.").apply { setPadding(kit.dp(16), kit.dp(14), kit.dp(16), kit.dp(14)) })
        return@apply
    }
    entries.forEachIndexed { i, e ->
        if (i > 0) addView(kit.divider())
        val row = kit.row().apply { setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12)) }
        val (title, sign) = ledgerLabel(e, wallet)
        val t = kit.column()
        t.addView(kit.text(title, MamaType.CAPTION, MamaColors.Graphite).apply { typeface = MamaFonts.ui(context, 600) })
        t.addView(kit.text(Texts.dayTime(e.at, zone), MamaType.SMALL, MamaColors.TextSecondary), kit.gap(2))
        row.addView(t, kit.weight())
        val color = when (sign) {
            "+" -> MamaColors.Success
            "−" -> MamaColors.Warning
            else -> MamaColors.TextSecondary
        }
        row.addView(kit.text("$sign${Texts.money(e.amountKop)}", MamaType.CAPTION, color).apply { typeface = MamaFonts.ui(context, 700) })
        addView(row)
    }
}

private fun ledgerLabel(e: LedgerEntry, wallet: Wallet): Pair<String, String> = when (e.type) {
    LedgerType.BALANCE_TOPUP_TEST -> "Тестовое пополнение" to "+"
    LedgerType.COMMITMENT_RESERVED -> "Цена слова: в резерве" to ""
    LedgerType.COMMITMENT_RELEASED_BEFORE_START -> "Цена слова: резерв снят до начала серии" to "+"
    LedgerType.COMMITMENT_RELEASED_SUCCESS -> "Слово сдержано: сумма снова доступна" to "+"
    LedgerType.COMMITMENT_FORFEITED_FAILURE -> "Цена слова применена" to "−"
    // Only credited returns are listed (see ledgerList).
    LedgerType.COMMITMENT_PARTIAL_RETURN -> "Возврат от MAMA" to "+"
    // A held Restart left the available balance when it was held; using it moves nothing.
    LedgerType.RESTART_PURCHASE_TEST ->
        if (wallet.entries.any { it.type == LedgerType.RESTART_RESERVED_TEST && it.seriesId == e.seriesId }) "Restart использован: серия началась" to ""
        else "Restart серии (тест)" to "−"
    LedgerType.RESTART_RESERVED_TEST -> "Restart (тест): в резерве до начала серии" to "−"
    LedgerType.RESTART_RELEASED_BEFORE_START -> "Restart вернулся: серия не началась" to "+"
    LedgerType.FLEX_PURCHASE_TEST -> "MAMA FLEX (тест)" to "−"
}

/**
 * The partial return of "Цена слова" is a surprise: never announced before,
 * shown once it has really been credited.
 */
internal fun MainActivity.showReturnNoticeIfAny() {
    if (!FeatureFlags.WALLET_ENABLED || returnNoticeOpen) return
    val unseen = Mama.wallet(this).unseenReturns
    if (unseen.isEmpty()) return
    returnNoticeOpen = true
    val amount = unseen.sumOf { it.returnKop }
    BrandDialog.show(
        this, returnTitle(amount),
        message = "$RETURN_TEXT\nПопробуй ещё раз.",
        confirm = "Понятно",
        cancel = null,
        onConfirm = {
            Mama.markReturnsSeen(this)
            returnNoticeOpen = false
            true
        },
    )
}

/** "Цена слова" outcome on the failure / success screens. */
/** What "Цена слова" came to for a finished series, the same facts whatever card shows them. */
internal data class CommitmentNote(val title: String, val message: String, val icon: Icon, val care: Boolean)

internal fun MainActivity.commitmentNote(seriesId: String, sample: app.mama.core.Commitment? = null): CommitmentNote? {
    val c = sample ?: Mama.wallet(this).commitmentFor(seriesId) ?: return null
    return when (c.state) {
        CommitmentState.RELEASED -> CommitmentNote("Ты сдержал слово.", "${Texts.money(c.amountKop)} снова доступны на твоём балансе MAMA.", Icon.CHECK, care = false)
        // Before the return is credited: only the fact, calmly, no promise of a return.
        CommitmentState.FORFEITED -> if (c.returnProcessed) {
            CommitmentNote(returnTitle(c.returnKop), "$RETURN_TEXT Попробуй ещё раз.", Icon.HEART, care = false)
        } else {
            CommitmentNote("Цена слова: ${Texts.money(c.amountKop)}", "Цена слова применена.", Icon.COINS, care = true)
        }
        CommitmentState.SCHEDULED, CommitmentState.RESERVED -> null
    }
}

internal fun MainActivity.commitmentOutcome(seriesId: String, sample: app.mama.core.Commitment? = null): View? =
    commitmentNote(seriesId, sample)?.let { design.noteCard(it.message, it.title, if (it.care) NoteTone.CARE else NoteTone.INFO, it.icon) }

/** "MAMA вернула тебе 250 ₽": the partial return, once credited. */
internal fun returnTitle(amountKop: Long) = "MAMA вернула тебе ${Texts.money(amountKop)}"

internal const val RETURN_TEXT = "Сумма уже на твоём балансе MAMA."
