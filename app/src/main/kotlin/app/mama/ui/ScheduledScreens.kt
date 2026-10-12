package app.mama.ui

import android.view.View
import android.widget.Toast
import app.mama.billing.FeatureFlags
import app.mama.core.CommitmentState
import app.mama.core.DailyWindow
import app.mama.core.Series
import app.mama.core.SeriesStatus
import app.mama.platform.Mama
import app.mama.platform.Promise
import app.mama.ui.MainActivity.Choice
import app.mama.ui.MainActivity.Step

// A SCHEDULED series (confirmed, first lock not started): "Изменить условия"
// and "Отменить запуск". Both are free and have no consequences.

/** Shown before "Начать серию": when the conditions become fixed. */
internal const val SCHEDULED_NOTE =
    "До первой блокировки условия можно изменить или отменить запуск — без последствий. Когда блокировка начнётся, условия зафиксируются."

/** Opens the current conditions of [series] as an editable draft. */
internal fun MainActivity.beginEditing(series: Series) {
    editing = true
    editSaved = false
    choice = Choice.entries.first { it.series == series.kind }
    mode = series.mode
    start = series.window.start
    end = series.window.end
    zone = series.window.zone
    contactName.setText(series.contact.name)
    contactPhone.setText(series.contact.phone)
    manualContact = false
    promiseField.setText(Promise.get(this))
    commitmentKop = Mama.wallet(this).commitmentFor(series.id)?.takeIf { it.state == CommitmentState.SCHEDULED }?.amountKop
    step = Step.REVIEW
    render()
}

/** Leaves "Изменить условия" without saving. */
internal fun MainActivity.stopEditing() {
    editing = false
    commitmentKop = null
    promiseField.setText(Promise.get(this))
    step = Step.WELCOME
}

/** "Изменить условия": the current parameters, each opens its own screen. */
internal fun MainActivity.editScreen(): Pair<View, Boolean> {
    val b = body()
    b.addView(kit.sectionHeader("Изменить условия", "Серия ещё не началась, поэтому изменения ничего не стоят и ни на что не влияют."))
    val kind = choice.series ?: app.mama.core.SeriesKind.SEVEN
    val current = Mama.series(this)
    val card = kit.card(18).apply { setPadding(kit.dp(18), kit.dp(6), kit.dp(18), kit.dp(6)) }
    fun line(icon: Icon, label: String, value: String, note: String?, target: Step?) {
        if (card.childCount > 0) card.addView(kit.divider())
        card.addView(kit.summaryRow(icon, label, value, note, target?.let { { go(it) } }))
    }
    if (current?.isRestart == true) {
        // A Restart repeats the failed series: its length (and Restart price) stays.
        line(Icon.CALENDAR, "Серия", Texts.seriesName(kind), "Restart той же серии", null)
    } else {
        line(Icon.CALENDAR, "Серия", Texts.seriesName(kind), "Restart при срыве — ${Texts.price(kind.restartPriceRub)}", Step.SERIES)
    }
    line(Icon.CLOCK, "Время", "${Texts.hhmm(start)} → ${Texts.hhmm(end)}", "каждый день · ${zone.id}", Step.TIME)
    line(Icon.PERSON, "Доверенный контакт", contactName.text.toString().trim(), Texts.phone(contactPhone.text.toString().trim()), Step.CONTACT)
    if (FeatureFlags.WALLET_ENABLED) {
        val c = commitmentKop
        line(Icon.SHIELD, "Цена слова", if (c != null) Texts.money(c) else "Без цены слова", if (c != null) "резерв с баланса MAMA" else null, Step.COMMITMENT)
    }
    line(Icon.HEART, "Личная причина", "«${promiseField.text.toString().trim().ifEmpty { Promise.get(this) }}»", null, Step.PROMISE)
    b.addView(card, kit.gap(20))

    val now = Mama.trustedNow(this)
    val first = DailyWindow(start, end, zone).nextFullPlan(now, mode)
    b.addView(kit.hintRow(Icon.MOON, "Первая блокировка: ${Texts.firstLock(first.start, zone, now)} — полное окно"), kit.gap(16))
    val relaunch = current?.status == SeriesStatus.NEEDS_ATTENTION
    b.addView(kit.infoCard(
        if (relaunch) "После сохранения серия будет запланирована заново. Когда блокировка начнётся, условия зафиксируются."
        else "Когда блокировка начнётся, условия зафиксируются.",
        Icon.LOCK, Tone.NEUTRAL,
    ), kit.gap(12))

    val footer = kit.column()
    footer.addView(kit.primaryButton(if (relaunch) "Сохранить и запланировать" else "Сохранить изменения") { saveEdits() }, kit.fill())
    footer.addView(kit.textButton("Отмена") { stopEditing(); render() }, kit.gap(4))
    return lightFrame(kit.stepHeader(null, 0) { back() }, b, footer)
}

internal fun MainActivity.saveEdits() {
    val contact = readyContact() ?: return
    val kind = choice.series ?: return
    when (val r = Mama.editScheduledSeries(this, kind, DailyWindow(start, end, zone), contact, mode, commitmentKop)) {
        Mama.SeriesEdit.Saved -> {
            promiseField.text.toString().trim().takeIf { it.length >= 3 }?.let { Promise.set(this, it) }
            editing = false
            editSaved = true
            commitmentKop = null
            step = Step.WELCOME
            tab = 0
        }
        Mama.SeriesEdit.NotAllowed -> {
            editing = false
            commitmentKop = null
            alert("Серия уже началась", "Блокировка началась, поэтому условия зафиксированы.")
        }
        is Mama.SeriesEdit.NotEnoughBalance -> return notEnoughBalance { commitmentKop = null; saveEdits() }
        is Mama.SeriesEdit.ProtectionMissing -> return fixProtectionFirst(r.missing)
        Mama.SeriesEdit.Rejected -> return alert("Не получилось", "Такое время блокировки нельзя запланировать. Проверьте начало и окончание.")
    }
    render()
}

/** A new launch after NEEDS_ATTENTION needs the strict protection working first. */
private fun MainActivity.fixProtectionFirst(missing: List<app.mama.ui.Requirement>) {
    BrandDialog.show(
        this, "Сначала исправь защиту",
        message = missing.joinToString("\n") { "• ${it.title}" } + "\n\nБез этого серия не начнётся. Изменения пока не сохранены.",
        confirm = "Исправить защиту", cancel = "Позже",
    ) {
        startSetupAll(app.mama.platform.Protection.strict)
        true
    }
}

/** "Отменить запуск": confirmation, then the series is gone with no consequences. */
internal fun MainActivity.confirmCancelLaunch(series: Series) {
    BrandDialog.show(
        this, "Отменить запланированную серию?",
        message = when (series.status) {
            SeriesStatus.ACTIVATING -> "Защита ещё не включилась, поэтому никаких последствий не будет."
            SeriesStatus.NEEDS_ATTENTION -> "Серия ещё не началась, поэтому никаких последствий не будет."
            else -> "Блокировка ещё не началась, поэтому никаких последствий не будет."
        },
        confirm = "Отменить запуск", cancel = "Оставить",
    ) {
        if (Mama.cancelScheduledSeries(this)) {
            Toast.makeText(this, "Запуск отменён", Toast.LENGTH_SHORT).show()
            editSaved = false
            step = Step.WELCOME
            tab = 0
        } else {
            alert("Серия уже началась", "Блокировка началась, поэтому отменить запуск нельзя.")
        }
        render()
        true
    }
}
