package app.mama.ui

import android.content.Context
import app.mama.core.CorePolicy
import app.mama.core.ExitKind
import app.mama.core.FinishReason
import app.mama.core.LockEngine
import app.mama.core.SessionMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** All user-facing copy in one place (RU for v0.1). */
object Texts {
    private val ru = Locale.forLanguageTag("ru")
    private val time = DateTimeFormatter.ofPattern("HH:mm", ru)
    private val dayTime = DateTimeFormatter.ofPattern("EEE, d MMM, HH:mm", ru)

    fun version(context: Context): String = runCatching {
        "v" + context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrDefault("")

    fun mode(mode: SessionMode) = when (mode) {
        SessionMode.SLEEP -> "Сон"
        SessionMode.WORK -> "Работа"
        SessionMode.STUDY -> "Учёба"
        SessionMode.KIDS -> "Дети"
        SessionMode.DETOX -> "Цифровой детокс"
    }

    fun time(at: Instant, zone: ZoneId): String = time.format(at.atZone(zone))

    fun dayTime(at: Instant, zone: ZoneId): String = dayTime.format(at.atZone(zone))

    fun zone(zone: ZoneId, at: Instant = Instant.now()): String {
        val offset = zone.rules.getOffset(at).id.let { if (it == "Z") "+00:00" else it }
        return "${zone.id} (UTC$offset)"
    }

    fun duration(d: Duration): String {
        val totalMin = maxOf(0, (d.toMillis() + 59_999) / 60_000)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "$m мин"
            m == 0L -> "$h ч"
            else -> "$h ч $m мин"
        }
    }

    fun countdown(d: Duration): String {
        val s = maxOf(0, d.seconds)
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    fun smsForContact(mode: SessionMode, kind: ExitKind, code: String, policy: CorePolicy): String {
        val what = when (kind) {
            ExitKind.END_SESSION -> "досрочно завершить режим «${mode(mode)}»"
            ExitKind.EMERGENCY -> "экстренный доступ к телефону на ${duration(policy.emergencyPass)}"
        }
        return "MAMA: вас указали доверенным контактом. Человек просит $what. " +
            "Код: $code (действует ${duration(policy.codeTtl)}). " +
            "Если не согласны — просто не сообщайте код."
    }

    fun createError(e: LockEngine.CreateError) = when (e) {
        LockEngine.CreateError.ALREADY_ENDED -> "Это время уже прошло."
        LockEngine.CreateError.TOO_SHORT -> "Слишком коротко: минимум ${duration(CorePolicy().minDuration)}."
        LockEngine.CreateError.TOO_LONG -> "Слишком долго: максимум ${duration(CorePolicy().maxDuration)}."
    }

    fun codeRequestError(e: LockEngine.CodeRequestError, retryAt: Instant?, zone: ZoneId) = when (e) {
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
        null -> "—"
    }
}
