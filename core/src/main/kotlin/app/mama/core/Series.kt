package app.mama.core

import java.time.Duration
import java.time.Instant

/**
 * A series is several lock periods with the same daily window and trusted
 * contact. Leaving a lock with the contact's code is always free. Money is
 * only ever involved in buying something new:
 * - 3/5/7 days: starting is free; an immediate Restart after the series broke
 *   costs [restartPriceRub].
 * - FLEX: a one-off package ([packagePriceRub], no subscription, no
 *   auto-renewal) of 7 periods on any days within 30 calendar days.
 */
enum class SeriesKind(
    /** Lock periods needed to complete the series. */
    val periods: Int,
    /** FLEX: the periods may be any days within this many days; null = every day in a row. */
    val withinDays: Int?,
    /** Price of an immediate Restart after a break; null = no Restart for this kind. */
    val restartPriceRub: Int?,
    /** Price of buying the series itself; null = free to start. */
    val packagePriceRub: Int?,
) {
    THREE(3, null, restartPriceRub = 99, packagePriceRub = null),
    FIVE(5, null, restartPriceRub = 199, packagePriceRub = null),
    SEVEN(7, null, restartPriceRub = 299, packagePriceRub = null),
    FLEX(7, 30, restartPriceRub = null, packagePriceRub = 399),
    ;

    val flexible: Boolean get() = withinDays != null
}

enum class SeriesStatus { ACTIVE, COMPLETED, BROKEN }

data class Series(
    val id: String,
    val kind: SeriesKind,
    val window: DailyWindow,
    val contact: TrustedContact,
    val mode: SessionMode,
    val startedAt: Instant,
    val completedPeriods: Int = 0,
    val status: SeriesStatus = SeriesStatus.ACTIVE,
    val endedAt: Instant? = null,
    /** The session already counted, so a reconcile never counts it twice. */
    val lastSessionId: String? = null,
) {
    /** FLEX only: after this moment an unfinished series is broken. */
    val deadline: Instant? get() = kind.withinDays?.let { startedAt + Duration.ofDays(it.toLong()) }

    val active: Boolean get() = status == SeriesStatus.ACTIVE
}

/**
 * Pure series rules on top of [LockEngine]. Every call returns the new series
 * and, when a lock period should be planned, the session to store.
 */
class SeriesEngine(private val engine: LockEngine, private val policy: CorePolicy = CorePolicy()) {

    data class Update(val series: Series, val session: Session?)

    /**
     * Starts a series. A fixed series plans its first night right away; a
     * FLEX series waits until the user starts a period ([startFlexPeriod]).
     */
    fun start(
        seriesId: String,
        sessionId: String,
        kind: SeriesKind,
        window: DailyWindow,
        contact: TrustedContact,
        mode: SessionMode,
        now: Instant,
    ): Update {
        val series = Series(seriesId, kind, window, contact, mode, startedAt = now)
        if (kind.flexible) return Update(series, null)
        return Update(series, planNext(series, sessionId, now))
    }

    /** FLEX: lock the next occurrence of the window (tonight, or right now if inside it). */
    fun startFlexPeriod(series: Series, sessionId: String, now: Instant): Session? {
        if (!series.active || !series.kind.flexible) return null
        if (series.deadline?.let { !now.isBefore(it) } == true) return null
        return planNext(series, sessionId, now)
    }

    /**
     * Folds a finished session into the series: a completed period counts, an
     * early exit or a cancelled night breaks the series. A fixed series then
     * plans the next night. Also breaks a FLEX series whose deadline passed.
     */
    fun afterSession(series: Series, session: Session?, nextSessionId: String, now: Instant): Update {
        if (!series.active) return Update(series, session)

        if (session != null && session.status == SessionStatus.FINISHED && session.id != series.lastSessionId) {
            val counted = series.copy(lastSessionId = session.id)
            return when (session.finishReason) {
                FinishReason.COMPLETED -> {
                    val done = counted.completedPeriods + 1
                    if (done >= series.kind.periods) {
                        Update(counted.copy(completedPeriods = done, status = SeriesStatus.COMPLETED, endedAt = now), session)
                    } else {
                        val next = counted.copy(completedPeriods = done)
                        val planned = if (series.kind.flexible) null else planNext(next, nextSessionId, now)
                        Update(next, planned ?: session)
                    }
                }
                FinishReason.EXITED_WITH_CONTACT, FinishReason.CANCELLED_BEFORE_START, null ->
                    Update(counted.copy(status = SeriesStatus.BROKEN, endedAt = now), session)
            }
        }

        val deadline = series.deadline
        val running = session != null && session.status != SessionStatus.FINISHED
        if (deadline != null && !now.isBefore(deadline) && !running) {
            return Update(series.copy(status = SeriesStatus.BROKEN, endedAt = now), session)
        }
        return Update(series, session)
    }

    /** A new series with the same settings, from zero. */
    fun restart(series: Series, seriesId: String, sessionId: String, now: Instant): Update =
        start(seriesId, sessionId, series.kind, series.window, series.contact, series.mode, now)

    private fun planNext(series: Series, sessionId: String, now: Instant): Session? {
        val plan = series.window.nextPlan(now, series.mode, policy.minDuration)
        return (engine.create(sessionId, plan, series.contact, now) as? LockEngine.CreateResult.Created)?.session
    }
}
