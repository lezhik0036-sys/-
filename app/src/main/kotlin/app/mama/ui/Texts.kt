package app.mama.ui

import android.content.Context
import app.mama.core.CorePolicy
import app.mama.core.ExitKind
import app.mama.core.FinishReason
import app.mama.core.LockEngine
import app.mama.core.SeriesKind
import app.mama.core.SessionMode
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration

/** All user-facing copy in one place (RU for v0.1). */
object Texts {
    private val ru = Locale.forLanguageTag("ru")
    private val dayTime = DateTimeFormatter.ofPattern("EEE, d MMM, HH:mm", ru)

    fun version(context: Context): String = runCatching {
        "v" + context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrDefault("")

    fun seriesName(kind: SeriesKind) = when (kind) {
        SeriesKind.THREE -> "3 дня"
        SeriesKind.FIVE -> "5 дней"
        SeriesKind.SEVEN -> "7 дней"
    }

    fun price(rub: Int) = money(rub * 100L)

    /** Kopecks as "5 000 ₽" or "2 500,50 ₽" (thin-space thousands). */
    fun money(kop: Long): String {
        val rub = kop / 100
        val rest = kop % 100
        val grouped = rub.toString().reversed().chunked(3).joinToString("\u00A0").reversed()
        return if (rest == 0L) "$grouped ₽" else "$grouped,%02d ₽".format(rest)
    }

    fun localDayTime(t: LocalDateTime): String = dayTime.format(t.toJavaLocalDateTime())

    fun flexPlanError(e: app.mama.core.FlexEngine.PlanError) = when (e) {
        app.mama.core.FlexEngine.PlanError.START_IN_PAST ->
            "Время начала уже прошло.\nВыберите текущее или будущее время."
        app.mama.core.FlexEngine.PlanError.TOO_SHORT -> "Минимальный период FLEX — 3 часа."
        app.mama.core.FlexEngine.PlanError.TOO_LONG -> "Максимальный период FLEX — 24 часа."
        app.mama.core.FlexEngine.PlanError.END_BEFORE_START -> "Время окончания должно быть позже времени начала."
        app.mama.core.FlexEngine.PlanError.PACKAGE_EXPIRED ->
            "Срок действия FLEX закончился.\nНачать новый период уже нельзя."
        app.mama.core.FlexEngine.PlanError.AFTER_EXPIRY -> "Начало периода выходит за срок действия FLEX."
        app.mama.core.FlexEngine.PlanError.NOT_AVAILABLE ->
            "Сейчас FLEX-период начать нельзя: нет доступных дней или уже идёт другая блокировка."
    }

    /** "Сегодня · 17:12", "Завтра · 07:00", otherwise the full date. */
    fun relativeDayTime(t: LocalDateTime, today: LocalDate): String = when (t.date) {
        today -> "Сегодня · " + hhmm(t.time)
        today.plus(1, DateTimeUnit.DAY) -> "Завтра · " + hhmm(t.time)
        else -> localDayTime(t)
    }

    private val months = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
    private val weekdays = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")

    /** "01 окт 2026". */
    fun date(d: LocalDate): String = "%02d %s %d".format(d.dayOfMonth, months[d.monthNumber - 1], d.year)

    /** First lock of a series: "Сегодня в 23:00", "Завтра в 23:00" or "Четверг, 15 окт 2026 в 23:00". */
    fun firstLock(start: Instant, zone: TimeZone, now: Instant): String {
        val d = start.toLocalDateTime(zone).date
        val today = now.toLocalDateTime(zone).date
        val at = time(start, zone)
        return if (d == today || d == today.plus(1, DateTimeUnit.DAY)) "${dayName(d, today)} в $at" else "${dayName(d, today)}, ${date(d)} в $at"
    }

    /** "Сегодня", "Завтра", or the weekday. */
    fun dayName(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "Сегодня"
        today.plus(1, DateTimeUnit.DAY) -> "Завтра"
        else -> weekdays[d.dayOfWeek.isoDayNumber - 1]
    }

    /** 24-hour "HH:mm", never AM/PM. */
    fun hhmm(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

    /** Short lines for the lock screen: calm, firm, never shaming. */
    val lockPhrases = listOf(
        "Не сдавайся.\nТы уже выбрал себя.",
        "Это временно.\nТы сильнее этого момента.",
        "Ты управляешь собой.\nНе телефон.",
        "Решение принято.\nПросто будь в нём.",
        "Выдержка сегодня —\nсвобода завтра.",
        "Ещё немного.\nТы справишься.",
        "Тишина тоже\nделает тебя сильнее.",
        "Каждая минута —\nмаленькая победа.",
        "Телефон подождёт.\nТы важнее.",
        "Ты держишь слово,\nданное себе.",
    )

    /** Second line under the phrase when no personal reason is set. */
    const val LOCK_SUBLINE = "Ты можешь больше, чем думаешь."

    /** A phrase for this session that changes every [everyMinutes] minutes. */
    fun lockPhrase(sessionId: String, elapsedMinutes: Long, everyMinutes: Long = 10): String =
        lockPhrases[Math.floorMod(sessionId.hashCode() + (elapsedMinutes / everyMinutes).toInt(), lockPhrases.size)]

    /** "Продолжительность" value: "3 ч 1 мин", "6 часов", "24 часа". */
    fun flexDuration(d: Duration): String {
        val totalMin = maxOf(0, d.inWholeMinutes)
        val h = totalMin / 60
        val m = totalMin % 60
        if (m != 0L) return if (h == 0L) "$m мин" else "$h ч $m мин"
        val word = when {
            h % 100 in 11..14 -> "часов"
            h % 10 == 1L -> "час"
            h % 10 in 2..4 -> "часа"
            else -> "часов"
        }
        return "$h $word"
    }

    /** "1 день", "2 дня", "5 дней". */
    fun days(n: Int): String {
        val word = when {
            n % 100 in 11..14 -> "дней"
            n % 10 == 1 -> "день"
            n % 10 in 2..4 -> "дня"
            else -> "дней"
        }
        return "$n $word"
    }

    fun mode(mode: SessionMode) = when (mode) {
        SessionMode.SLEEP -> "Сон"
        SessionMode.WORK -> "Работа"
        SessionMode.STUDY -> "Учёба"
        SessionMode.KIDS -> "Дети"
        SessionMode.DETOX -> "Цифровой детокс"
    }

    fun time(at: Instant, zone: TimeZone): String = hhmm(at.toLocalDateTime(zone).time)

    fun dayTime(at: Instant, zone: TimeZone): String = localDayTime(at.toLocalDateTime(zone))

    fun zone(zone: TimeZone, at: Instant = Clock.System.now()): String {
        val offset = zone.offsetAt(at).toString().let { if (it == "Z") "+00:00" else it }
        return "${zone.id} (UTC$offset)"
    }

    fun duration(d: Duration): String {
        val totalMin = maxOf(0, (d.inWholeMilliseconds + 59_999) / 60_000)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "$m мин"
            m == 0L -> "$h ч"
            else -> "$h ч $m мин"
        }
    }

    fun countdown(d: Duration): String {
        val s = maxOf(0, d.inWholeSeconds)
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    // TODO(product): the code lifetime and the pause after 3 wrong entries are
    // not approved product rules yet, so the SMS does not promise either.
    fun smsForContact(mode: SessionMode, kind: ExitKind, code: String, policy: CorePolicy): String {
        val what = when (kind) {
            ExitKind.END_SESSION -> "досрочно завершить блокировку в MAMA"
            ExitKind.EMERGENCY -> "экстренный доступ к телефону на ${duration(policy.emergencyPass)}"
        }
        return "MAMA: вас указали доверенным контактом. Человек просит $what. " +
            "Код: $code. Если не согласны — просто не сообщайте код."
    }

    /** "13 : 18 : 24" parts: hours, minutes, seconds (hours may exceed 24). */
    fun hms(d: Duration): Triple<String, String, String> {
        val s = maxOf(0, d.inWholeSeconds)
        return Triple("%02d".format(s / 3600), "%02d".format((s % 3600) / 60), "%02d".format(s % 60))
    }

    /** "+79856322062" → "+7 985 632-20-62" for display; other numbers as stored. */
    fun phone(raw: String): String {
        val n = app.mama.core.TrustedContact.normalizePhone(raw) ?: return raw
        if (!n.startsWith("+7") || n.length != 12) return n
        val d = n.substring(2)
        return "+7 ${d.substring(0, 3)} ${d.substring(3, 6)}-${d.substring(6, 8)}-${d.substring(8, 10)}"
    }

    /** "1 из 7"-style day count: "День 3 из 7". */
    fun dayOf(day: Int, total: Int) = "День $day из $total"

    fun createError(e: LockEngine.CreateError) = when (e) {
        LockEngine.CreateError.ALREADY_ENDED -> "Это время уже прошло."
        LockEngine.CreateError.TOO_SHORT -> "Слишком коротко: минимум ${duration(CorePolicy().minDuration)}."
        LockEngine.CreateError.TOO_LONG -> "Слишком долго: максимум ${duration(CorePolicy().maxDuration)}."
    }

    fun codeRequestError(e: LockEngine.CodeRequestError, retryAt: Instant?, zone: TimeZone) = when (e) {
        LockEngine.CodeRequestError.NOT_LOCKED -> "Блокировка сейчас не активна."
        LockEngine.CodeRequestError.ALREADY_PENDING -> "Код уже отправлен. Введите его или дождитесь " +
            (retryAt?.let { "${time(it, zone)}, когда он истечёт." } ?: "его истечения.")
        LockEngine.CodeRequestError.COOLDOWN -> "Все попытки израсходованы. Новый код можно запросить в " +
            (retryAt?.let { time(it, zone) } ?: "—") + "."
        LockEngine.CodeRequestError.EMERGENCY_LIMIT_REACHED ->
            "Лимит экстренных доступов на эту сессию исчерпан. Можно попросить код на досрочное завершение."
    }

    fun finishReason(r: FinishReason?) = when (r) {
        FinishReason.COMPLETED -> "завершена по плану"
        FinishReason.EXITED_WITH_CONTACT -> "завершена досрочно по коду доверенного контакта"
        FinishReason.CANCELLED_BEFORE_START -> "отменена до начала"
        FinishReason.PROTECTION_REVOKED -> "завершена досрочно: защита была отключена"
        null -> "—"
    }
}
