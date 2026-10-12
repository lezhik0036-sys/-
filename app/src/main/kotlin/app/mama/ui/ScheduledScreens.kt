package app.mama.ui

import android.view.View
import android.widget.Toast
import app.mama.billing.FeatureFlags
import app.mama.core.CommitmentState
import app.mama.core.Series
import app.mama.core.SeriesStatus
import app.mama.platform.Mama
import app.mama.platform.Promise
import app.mama.ui.MainActivity.Choice
import app.mama.ui.MainActivity.Step

// A SCHEDULED series (confirmed, first lock not started): "Изменить условия"
// and "Отменить запуск". Both are free and have no consequences.

/** Shown before "Начать серию": what is fixed right away and what until the first lock. */
internal const val SCHEDULED_NOTE =
    "Время и длина серии фиксируются сразу после запуска. До первой блокировки можно изменить доверенный контакт, " +
        "личную причину и Цену слова или отменить запуск — без последствий."

/** Opens the editable pre-start fields of [series] as a draft (contact, reason, "Цена слова"). */
internal fun MainActivity.beginEditing(series: Series) {
    editing = true
    editSaved = false
    // For the "Цена слова" screen (the Restart price hint); the length itself is fixed.
    choice = Choice.entries.first { it.series == series.kind }
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

/**
 * "Изменить условия": length and time are shown as fixed; trusted contact,
 * "Цена слова" and the personal reason each open their own screen.
 */
internal fun MainActivity.editScreen(series: Series? = Mama.series(this)): Pair<View, Boolean> {
    val b = body()
    b.addView(kit.sectionHeader("Изменить условия", "Серия ещё не началась, поэтому изменения ничего не стоят и ни на что не влияют."))
    val card = kit.card(18).apply { setPadding(kit.dp(18), kit.dp(6), kit.dp(18), kit.dp(6)) }
    fun line(icon: Icon, label: String, value: String, note: String?, target: Step?) {
        if (card.childCount > 0) card.addView(kit.divider())
        card.addView(kit.summaryRow(icon, label, value, note, target?.let { { go(it) } }))
    }
    if (series != null) {
        line(Icon.CALENDAR, "Серия", Texts.seriesName(series.kind), if (series.isRestart) "Restart · зафиксирована" else "зафиксирована", null)
        line(Icon.CLOCK, "Время", "${Texts.hhmm(series.window.start)} → ${Texts.hhmm(series.window.end)}", "каждый день · зафиксировано", null)
    }
    line(Icon.PERSON, "Доверенный контакт", contactName.text.toString().trim(), Texts.phone(contactPhone.text.toString().trim()), Step.CONTACT)
    if (FeatureFlags.WALLET_ENABLED) {
        val c = commitmentKop
        line(Icon.SHIELD, "Цена слова", if (c != null) Texts.money(c) else "Без цены слова", if (c != null) "резерв с баланса MAMA" else null, Step.COMMITMENT)
    }
    line(Icon.HEART, "Личная причина", "«${promiseField.text.toString().trim().ifEmpty { Promise.get(this) }}»", null, Step.PROMISE)
    b.addView(card, kit.gap(20))

    b.addView(kit.infoCard(
        "Время и длина серии не меняются. Если время выбрано по ошибке, отмени запуск и создай новую серию — это бесплатно и без последствий.",
        Icon.LOCK, Tone.NEUTRAL, "Время зафиксировано",
    ), kit.gap(16))
    b.addView(kit.caption("Когда блокировка начнётся, зафиксируется и всё остальное."), kit.gap(10))

    val footer = kit.column()
    footer.addView(kit.primaryButton("Сохранить изменения") { saveEdits() }, kit.fill())
    footer.addView(kit.textButton("Отмена") { stopEditing(); render() }, kit.gap(4))
    return lightFrame(kit.stepHeader(null, 0) { back() }, b, footer)
}

internal fun MainActivity.saveEdits() {
    val contact = readyContact() ?: return
    when (Mama.editScheduledSeries(this, contact, commitmentKop)) {
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
        is Mama.SeriesEdit.ProtectionMissing, Mama.SeriesEdit.Rejected -> return alert("Не получилось", "Сохранить изменения сейчас нельзя.")
    }
    render()
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
