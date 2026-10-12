package app.mama.ui

import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import app.mama.billing.Checkout
import app.mama.billing.FeatureFlags
import app.mama.billing.Product
import app.mama.core.CommitmentState
import app.mama.core.LedgerEntry
import app.mama.core.LedgerStatus
import app.mama.core.LedgerType
import app.mama.core.SeriesKind
import app.mama.core.Wallet
import app.mama.core.WalletEngine
import app.mama.platform.Mama
import app.mama.ui.MainActivity.Step
import java.time.ZoneId

// "Цена слова" (optional commitment before every standard series) and the
// internal MAMA balance. Pilot: test credits only, internal only.

/** Quick amounts on the "Цена слова" screen, in kopecks. */
private val QUICK = listOf(500_00L, 1_000_00L, 5_000_00L)

/**
 * "Цена слова". Shown before every standard series: the wizard (first or
 * later free series) and the paid Restart ([restart] = true). Optional:
 * "Пропустить" starts the series without it. Never added to the Restart price.
 */
internal fun MainActivity.commitmentScreen(restart: Boolean, kind: SeriesKind): Pair<View, Boolean> {
    val wallet = Mama.wallet(this)
    val b = body()
    b.addView(kit.sectionHeader("Цена слова", "Хочешь усилить своё обещание?"))
    b.addView(kit.text(
        "Это необязательно.\nТы сам выбираешь сумму, которую готов поставить на своё решение.",
        MamaType.BODY, MamaColors.Graphite,
    ), kit.gap(14))

    // Amount choice: 500 / 1 000 / 5 000 / custom.
    val custom = commitmentKop?.takeIf { it !in QUICK }
    val options = QUICK.map { Texts.money(it) to it } + ((custom?.let { "Своя: ${Texts.money(it)}" } ?: "Своя сумма") to -1L)
    options.chunked(2).forEachIndexed { r, pair ->
        val row = kit.row()
        pair.forEachIndexed { i, (label, value) ->
            val on = if (value == -1L) custom != null else commitmentKop == value
            row.addView(amountPill(label, on) {
                if (value == -1L) customAmount() else { commitmentKop = value; render() }
            }, kit.weight().apply { if (i > 0) leftMargin = kit.dp(10) })
        }
        b.addView(row, kit.gap(if (r == 0) 20 else 10))
    }

    // Internal balance.
    b.addView(balanceCard(wallet, compact = true), kit.gap(18))
    val chosen = commitmentKop
    if (chosen != null && chosen > wallet.availableKop) {
        b.addView(kit.infoCard(
            "Для этой суммы нужно ${Texts.money(chosen)} на балансе MAMA, сейчас доступно ${Texts.money(wallet.availableKop)}.",
            Icon.INFO, Tone.WARNING, "Не хватает на балансе",
        ), kit.gap(10))
    }

    b.addView(kit.infoCard(
        "Если серия пройдена — вся сумма снова доступна на твоём балансе MAMA.\n" +
            "Если выйдешь досрочно — сумма остаётся в MAMA, а через 24 часа ${WalletEngine.RETURN_PERCENT}% вернутся на баланс.\n" +
            "Баланс MAMA — только внутри приложения: его нельзя вывести или перевести.",
        Icon.SHIELD, Tone.NEUTRAL, "Как это работает",
    ), kit.gap(14))
    b.addView(kit.hintRow(
        Icon.REFRESH,
        "Restart при срыве — отдельно: ${Texts.price(kind.restartPriceRub)}. Цена слова к нему не прибавляется." +
            if (restart) "" else " Сама серия остаётся бесплатной.",
    ), kit.gap(14))

    val footer = kit.column()
    val go = kit.primaryButton(if (chosen != null) "Поставить ${Texts.money(chosen)}" else "Выбери сумму") { proceedCommitment(restart, kind) }
    go.isEnabled = chosen != null && chosen <= wallet.availableKop
    footer.addView(go, kit.fill())
    footer.addView(kit.textButton("Пропустить") { commitmentKop = null; proceedCommitment(restart, kind) }, kit.gap(4))

    val header = if (restart) kit.stepHeader(null, 0) { restartFlow = false; render() } else kit.stepHeader(steps().indexOf(Step.COMMITMENT) + 1, steps().size) { back() }
    return lightFrame(header, b, footer)
}

private fun MainActivity.amountPill(label: String, on: Boolean, onClick: () -> Unit): View =
    kit.text(label, MamaType.TITLE, if (on) MamaColors.White else MamaColors.Graphite, center = true).apply {
        gravity = Gravity.CENTER
        minHeight = kit.dp(54)
        background = if (on) {
            kit.pressable(kit.gradient(MamaColors.EmeraldActive, MamaColors.Emerald, 16), 16, MamaColors.RippleOnDark)
        } else {
            kit.pressable(kit.shape(MamaColors.PureCard, 16, MamaColors.Border), 16)
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

private fun MainActivity.customAmount() {
    val field = kit.textField("Например, 2000", InputType.TYPE_CLASS_NUMBER).apply {
        filters = arrayOf(InputFilter.LengthFilter(6))
        commitmentKop?.let { setText((it / 100).toString()) }
    }
    val min = WalletEngine.MIN_COMMITMENT_KOP
    val max = WalletEngine.MAX_COMMITMENT_KOP
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
            error.text = "Укажите сумму от ${Texts.money(min)} до ${Texts.money(max)}."
            error.visibility = View.VISIBLE
            false
        } else {
            commitmentKop = kop
            render()
            true
        }
    }
}

private fun MainActivity.proceedCommitment(restart: Boolean, kind: SeriesKind) {
    if (!restart) {
        go(Step.REVIEW)
        return
    }
    val commitment = commitmentKop
    Checkout.obtain(this, Product.Restart(kind), "Restart серии", reserveAheadKop = commitment ?: 0) { source ->
        when (val r = Mama.restartSeries(this, source, commitment)) {
            app.mama.platform.Mama.SeriesStart.Started -> {
                restartFlow = false
                commitmentKop = null
            }
            is app.mama.platform.Mama.SeriesStart.NotEnoughBalance ->
                alert("Не хватает на балансе", "Доступно ${Texts.money(r.availableKop)}. Выбери сумму меньше или пропусти «Цену слова».")
            else -> alert("Не получилось", "Restart сейчас недоступен.")
        }
        render()
    }
}

// ---------------- balance ----------------

/** "Баланс MAMA": available / reserved, internal-only note, test top-ups. */
internal fun MainActivity.balanceCard(wallet: Wallet, compact: Boolean = false): View = kit.card(16).apply {
    val head = kit.row()
    head.addView(kit.iconCircle(Icon.SHIELD, 40))
    val t = kit.column()
    t.addView(kit.text("Баланс MAMA", MamaType.TITLE))
    t.addView(kit.text("Только внутри приложения", MamaType.SMALL, MamaColors.TextSecondary), kit.gap(2))
    head.addView(t, kit.weight().apply { leftMargin = kit.dp(12) })
    addView(head)
    val nums = kit.row()
    nums.addView(kit.column().apply {
        addView(kit.text("Доступно", MamaType.SMALL, MamaColors.TextSecondary))
        addView(kit.text(Texts.money(wallet.availableKop), MamaType.H2), kit.gap(2))
    }, kit.weight())
    nums.addView(kit.column().apply {
        addView(kit.text("В резерве", MamaType.SMALL, MamaColors.TextSecondary))
        addView(kit.text(Texts.money(wallet.reservedKop), MamaType.H2), kit.gap(2))
    }, kit.weight())
    addView(nums, kit.gap(14))
    if (FeatureFlags.WALLET_TEST_TOPUP_ENABLED) {
        val row = kit.row()
        listOf(1_000_00L, 5_000_00L).forEachIndexed { i, amount ->
            row.addView(kit.secondaryButton("+${Texts.money(amount)}") {
                Mama.topUpTest(this@balanceCard, amount)
                render()
            }.apply { minHeight = kit.dp(44); textSize = 14f }, kit.weight().apply { if (i > 0) leftMargin = kit.dp(8) })
        }
        addView(row, kit.gap(14))
        addView(kit.text("Тестовые кредиты для пилота — не деньги. Вывести или перевести баланс нельзя.", MamaType.SMALL, MamaColors.TextSecondary), kit.gap(8))
    } else if (!compact) {
        addView(kit.text("Вывести или перевести баланс нельзя.", MamaType.SMALL, MamaColors.TextSecondary), kit.gap(8))
    }
}

/** Ledger history (newest first), for the settings screen. */
internal fun MainActivity.ledgerList(wallet: Wallet, zone: ZoneId): View = kit.card(paddingDp = 0).apply {
    val entries = wallet.entries.sortedByDescending { it.dueAt ?: it.at }.take(12)
    if (entries.isEmpty()) {
        addView(kit.caption("Операций пока нет.").apply { setPadding(kit.dp(16), kit.dp(14), kit.dp(16), kit.dp(14)) })
        return@apply
    }
    entries.forEachIndexed { i, e ->
        if (i > 0) addView(kit.divider())
        val row = kit.row().apply { setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12)) }
        val (title, sign) = ledgerLabel(e)
        val t = kit.column()
        t.addView(kit.text(title, MamaType.CAPTION, MamaColors.Graphite).apply { typeface = MamaFonts.ui(context, 600) })
        val whenText = if (e.status == LedgerStatus.SCHEDULED && e.dueAt != null) "вернётся ${Texts.dayTime(e.dueAt!!, zone)}" else Texts.dayTime(e.at, zone)
        t.addView(kit.text(whenText, MamaType.SMALL, MamaColors.TextSecondary), kit.gap(2))
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

private fun ledgerLabel(e: LedgerEntry): Pair<String, String> = when (e.type) {
    LedgerType.BALANCE_TOPUP_TEST -> "Тестовое пополнение" to "+"
    LedgerType.COMMITMENT_RESERVED -> "Цена слова: в резерве" to ""
    LedgerType.COMMITMENT_RELEASED_SUCCESS -> "Слово сдержано: сумма снова доступна" to "+"
    LedgerType.COMMITMENT_FORFEITED_FAILURE -> "Цена слова: серия прервана" to "−"
    LedgerType.COMMITMENT_PARTIAL_RETURN ->
        (if (e.status == LedgerStatus.SCHEDULED) "Возврат ${WalletEngine.RETURN_PERCENT}% (ожидается)" else "Возврат ${WalletEngine.RETURN_PERCENT}% на баланс") to
            (if (e.status == LedgerStatus.SCHEDULED) "" else "+")
    LedgerType.RESTART_PURCHASE_TEST -> "Restart серии (тест)" to "−"
    LedgerType.FLEX_PURCHASE_TEST -> "MAMA FLEX (тест)" to "−"
}

/** Shows "Часть суммы вернулась на баланс MAMA" once per credited return. */
internal fun MainActivity.showReturnNoticeIfAny() {
    if (!FeatureFlags.WALLET_ENABLED || returnNoticeOpen) return
    val unseen = Mama.wallet(this).unseenReturns
    if (unseen.isEmpty()) return
    returnNoticeOpen = true
    val amount = unseen.sumOf { it.returnKop }
    BrandDialog.show(
        this, "Часть суммы вернулась на баланс MAMA",
        message = "${Texts.money(amount)} доступны внутри приложения.",
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
internal fun MainActivity.commitmentOutcome(seriesId: String, night: Boolean, zone: ZoneId, sample: app.mama.core.Commitment? = null): View? {
    val c = sample ?: Mama.wallet(this).commitmentFor(seriesId) ?: return null
    val k = if (night) nightKit else kit
    return when (c.state) {
        CommitmentState.RELEASED -> k.infoCard(
            "${Texts.money(c.amountKop)} снова доступны на твоём балансе MAMA.", Icon.CHECK, Tone.SUCCESS, "Ты сдержал слово.",
        )
        CommitmentState.FORFEITED -> k.infoCard(
            if (c.returnProcessed) {
                "${Texts.money(c.returnKop)} уже вернулись на баланс MAMA и доступны внутри приложения."
            } else {
                "По условию серии сумма осталась в MAMA. Через 24 часа ${Texts.money(c.returnKop)} вернутся на баланс MAMA" +
                    (c.returnDueAt?.let { " — ${Texts.dayTime(it, zone)}" } ?: "") + "."
            },
            Icon.SHIELD, Tone.NEUTRAL, "Цена слова: ${Texts.money(c.amountKop)}",
        )
        CommitmentState.RESERVED -> null
    }
}
