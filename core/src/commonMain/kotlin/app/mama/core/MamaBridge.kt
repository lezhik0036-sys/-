package app.mama.core

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.days

/**
 * The shared rules as the iPhone app sees them: primitive types only (epoch
 * milliseconds, minutes of the day, zone ids, string maps), because Kotlin's
 * time and value types are awkward across the Objective-C bridge. The state
 * is the same [Snapshot] the Android app keeps; the iPhone app stores it as
 * the [StateCodec] string map.
 *
 * Protection itself is native on each platform. On the iPhone it is the
 * Screen Time shield; the app reports when it was switched on for the current
 * lock ([refresh]), and that is the commitment point (ACTIVATING → ACTIVE).
 * Screen Time access confirmed withdrawn during an ACTIVE series or FLEX
 * period is a failure ([observeAccess], [refresh]).
 */
object MamaBridge {
    private val lock = LockEngine()
    private val flow = SeriesFlow(lock)
    private val flex = FlexEngine(lock)

    /** Series lengths offered at the start: 3, 5 and 7 days. */
    val seriesDays: List<Int> get() = SeriesKind.entries.map { it.periods }

    fun restartPriceRub(days: Int): Int = kind(days)?.restartPriceRub ?: 0

    /** `+79001234567`, or null if it does not look like a phone number. */
    fun normalizePhone(raw: String): String? = TrustedContact.normalizePhone(raw)

    /** Default times of a new draft (AUTO_NOW): the current minute, and 8 hours later. */
    fun freshDraft(nowMs: Long, zoneId: String): DraftTime {
        val (start, end) = SeriesDraft.freshTimes(Instant.fromEpochMilliseconds(nowMs), TimeZone.of(zoneId))
        return DraftTime(start.toSecondOfDay() / 60, end.toSecondOfDay() / 60)
    }

    /**
     * What the final confirmation would start at [nowMs]. AUTO_NOW: from this
     * minute for the draft's length, first lock right away. MANUAL: the fixed
     * window, first lock at its next full occurrence. Null if start equals end.
     */
    fun resolve(autoNow: Boolean, startMinute: Int, endMinute: Int, zoneId: String, days: Int, nowMs: Long): Launch? {
        val time = launchTime(autoNow, startMinute, endMinute, zoneId) ?: return null
        val now = Instant.fromEpochMilliseconds(nowMs)
        val launch = SeriesDraft.resolve(time, now)
        val first = if (launch.startNow) LockPlan(now, now + launch.window.length, launch.window.zone) else launch.window.nextFullPlan(now)
        return Launch(
            startMinute = launch.window.start.toSecondOfDay() / 60,
            endMinute = launch.window.end.toSecondOfDay() / 60,
            startsNow = launch.startNow,
            firstLockStartMs = first.start.toEpochMilliseconds(),
            firstLockEndMs = first.end.toEpochMilliseconds(),
            // The last of [days] daily periods (nominal: a DST night is not counted).
            lastLockEndMs = (first.end + (days - 1).coerceAtLeast(0).days).toEpochMilliseconds(),
        )
    }

    /**
     * «Начать серию» for a free STANDARD series on [state]. Refused while a
     * series is in progress (BUSY) or during the 30 days after a failure
     * (WAITING); INVALID for a bad phone, length or time.
     */
    fun schedule(
        state: Map<String, String>,
        days: Int,
        autoNow: Boolean,
        startMinute: Int,
        endMinute: Int,
        zoneId: String,
        contactName: String,
        contactPhone: String,
        nowMs: Long,
        seriesId: String,
        sessionId: String,
    ): Outcome {
        val kind = kind(days) ?: return Outcome.error(INVALID)
        val phone = TrustedContact.normalizePhone(contactPhone) ?: return Outcome.error(INVALID)
        if (contactName.isBlank()) return Outcome.error(INVALID)
        val time = launchTime(autoNow, startMinute, endMinute, zoneId) ?: return Outcome.error(INVALID)
        val now = Instant.fromEpochMilliseconds(nowMs)
        val s = StateCodec.decode(state)
        if (s.series?.inProgress == true || s.session?.status.let { it == SessionStatus.SCHEDULED || it == SessionStatus.ACTIVE }) {
            return Outcome.error(BUSY)
        }
        if (!StandardRules.canStartFree(s.entitlements, now)) return Outcome.error(WAITING)
        val contact = TrustedContact(contactName.trim(), phone)
        return when (val r = flow.schedule(s, seriesId, sessionId, kind, time, contact, SessionMode.SLEEP, null, now)) {
            is SeriesFlow.Result.Done -> Outcome.of(r.snapshot)
            else -> Outcome.error(REJECTED)
        }
    }

    /** How long a "denied" reading must last before it counts ([AccessWatch.CONFIRM_AFTER]). */
    val accessConfirmAfterMs: Long get() = AccessWatch.CONFIRM_AFTER.inWholeMilliseconds

    /**
     * One more Screen Time reading ([AccessReading] name: APPROVED, DENIED,
     * NOT_DETERMINED, UNREADABLE) folded into the evidence so far
     * ([deniedSinceMs], kept by the app). The verdict is an [AccessVerdict] name.
     */
    fun observeAccess(reading: String, deniedSinceMs: Long?, nowMs: Long): AccessCheck {
        val parsed = AccessReading.entries.firstOrNull { it.name == reading } ?: AccessReading.UNREADABLE
        val step = AccessWatch.observe(parsed, deniedSinceMs?.let(Instant::fromEpochMilliseconds), Instant.fromEpochMilliseconds(nowMs))
        return AccessCheck(step.verdict.name, step.deniedSince?.toEpochMilliseconds())
    }

    /**
     * Brings [state] up to [nowMs]. [shieldOnTimesMs] are the moments the
     * iPhone shield was switched on (by the app at an AUTO_NOW launch, or by
     * the DeviceActivity monitor when a window began). [access] is the
     * [AccessVerdict] name from [observeAccess].
     *
     * - A first lock during which the shield came on, with access APPROVED,
     *   is the commitment point: the series becomes ACTIVE as of that moment.
     *   Without that the series does not begin (the activation timeout leads
     *   to NEEDS_ATTENTION, exactly as on Android).
     * - CONFIRMED_DENIED before ACTIVE: NEEDS_ATTENTION, no consequences.
     * - CONFIRMED_DENIED while ACTIVE: the series fails (once) and the
     *   ordinary failure rules follow; a running FLEX period is burnt.
     * - UNKNOWN never fails anything.
     */
    fun refresh(state: Map<String, String>, nowMs: Long, nextSessionId: String, shieldOnTimesMs: List<Long>, access: String): Outcome {
        val approved = access == AccessVerdict.APPROVED.name
        val denied = access == AccessVerdict.CONFIRMED_DENIED.name
        val now = Instant.fromEpochMilliseconds(nowMs)
        var s = StateCodec.decode(state)
        // A first lock that began while the app was not running began at its start time
        // (the activation timeout counts from then, not from when the app was opened).
        s.session?.let { session ->
            if (s.series?.status == SeriesStatus.SCHEDULED && session.plan.start <= now) {
                val began = session.plan.start
                s = flow.fold(s.copy(session = lock.advance(session, began)), nextSessionId, began)
            }
        }
        // The shield came on during this lock: the commitment point, as of that moment.
        val current = s.session
        val at = current?.let { lockNow ->
            shieldOnTimesMs.map(Instant::fromEpochMilliseconds)
                .filter { it >= lockNow.plan.start && it < lockNow.plan.end && it <= now }
                .minOrNull()
        }
        if (approved && at != null && s.series?.status == SeriesStatus.ACTIVATING) {
            flow.confirm(s, at)?.let { s = it }
        }
        s = flow.fold(s.copy(session = s.session?.let { lock.advance(it, now) }), nextSessionId, now)
        if (denied) {
            when (s.series?.status) {
                SeriesStatus.ACTIVATING -> s = flow.protectionFailed(s, now) ?: s
                SeriesStatus.ACTIVE -> s = flow.protectionRevoked(s, nextSessionId, now) ?: s
                else -> {}
            }
            s.entitlements.flex?.let { pkg ->
                flex.protectionRevoked(pkg, s.session, now)?.let { r ->
                    s = s.copy(session = r.session, entitlements = s.entitlements.copy(flex = r.pkg))
                }
            }
        }
        return Outcome.of(s)
    }

    /** Whole days until a free series may start (the 30 days after a failure), 0 if now. */
    fun daysUntilFreeStart(state: Map<String, String>, nowMs: Long): Int =
        StandardRules.daysUntilFreeStart(StateCodec.decode(state).entitlements, Instant.fromEpochMilliseconds(nowMs)).toInt()

    /** «Отменить запуск» before ACTIVE: no consequences. NOT_ALLOWED once ACTIVE. */
    fun cancel(state: Map<String, String>, nowMs: Long): Outcome {
        val c = flow.cancel(StateCodec.decode(state), Instant.fromEpochMilliseconds(nowMs)) ?: return Outcome.error(NOT_ALLOWED)
        return Outcome.of(c.snapshot)
    }

    /** «Подтвердить запуск» after NEEDS_ATTENTION (AUTO_NOW resolves its time again from [nowMs]). */
    fun relaunch(state: Map<String, String>, nowMs: Long, sessionId: String): Outcome =
        when (val r = flow.reschedule(StateCodec.decode(state), sessionId, Instant.fromEpochMilliseconds(nowMs))) {
            is SeriesFlow.Result.Done -> Outcome.of(r.snapshot)
            else -> Outcome.error(NOT_ALLOWED)
        }

    /** The stored series for display, or null when there is none. */
    fun view(state: Map<String, String>): SeriesView? {
        val s = StateCodec.decode(state)
        val series = s.series ?: return null
        val session = s.session?.takeIf { it.status != SessionStatus.FINISHED }
        return SeriesView(
            status = series.status.name,
            days = series.kind.periods,
            completed = series.completedPeriods,
            startMinute = series.window.start.toSecondOfDay() / 60,
            endMinute = series.window.end.toSecondOfDay() / 60,
            zoneId = series.window.zone.id,
            autoNow = series.autoNow,
            contactName = series.contact.name,
            contactPhone = series.contact.phone,
            lockStartMs = session?.plan?.start?.toEpochMilliseconds(),
            lockEndMs = session?.plan?.end?.toEpochMilliseconds(),
            endReason = s.session?.takeIf { series.status == SeriesStatus.BROKEN }?.finishReason?.name,
        )
    }

    const val BUSY = "BUSY"
    const val WAITING = "WAITING"
    const val INVALID = "INVALID"
    const val REJECTED = "REJECTED"
    const val NOT_ALLOWED = "NOT_ALLOWED"

    private fun kind(days: Int) = SeriesKind.entries.firstOrNull { it.periods == days }

    private fun launchTime(autoNow: Boolean, startMinute: Int, endMinute: Int, zoneId: String): LaunchTime? {
        if (startMinute !in 0 until MINUTES_PER_DAY || endMinute !in 0 until MINUTES_PER_DAY || startMinute == endMinute) return null
        val zone = TimeZone.of(zoneId)
        val window = DailyWindow(LocalTime.fromSecondOfDay(startMinute * 60), LocalTime.fromSecondOfDay(endMinute * 60), zone)
        return if (autoNow) LaunchTime.AutoNow(window.length, zone) else LaunchTime.Fixed(window)
    }

    private const val MINUTES_PER_DAY = 24 * 60
}

/** Draft times as minutes of the day (0–1439). */
data class DraftTime(val startMinute: Int, val endMinute: Int)

/** A resolved launch: the fixed daily window and the first and last lock (epoch ms). */
data class Launch(
    val startMinute: Int,
    val endMinute: Int,
    val startsNow: Boolean,
    val firstLockStartMs: Long,
    val firstLockEndMs: Long,
    val lastLockEndMs: Long,
)

/** The stored series for display. [status] is a [SeriesStatus] name. */
data class SeriesView(
    val status: String,
    val days: Int,
    val completed: Int,
    val startMinute: Int,
    val endMinute: Int,
    val zoneId: String,
    val autoNow: Boolean,
    val contactName: String,
    val contactPhone: String,
    /** The current or next lock, if one is planned. */
    val lockStartMs: Long?,
    val lockEndMs: Long?,
    /** For a failed series: how its last lock ended ([FinishReason] name), e.g. PROTECTION_REVOKED. */
    val endReason: String?,
)

/** An [AccessVerdict] name, and since when access has read "denied" (keep it for the next reading). */
data class AccessCheck(val verdict: String, val deniedSinceMs: Long?)

/** A new state to store, or an error code ([MamaBridge.BUSY] and so on). */
data class Outcome(val state: Map<String, String>?, val error: String?) {
    companion object {
        internal fun of(s: Snapshot) = Outcome(StateCodec.encode(s), null)
        internal fun error(code: String) = Outcome(null, code)
    }
}
