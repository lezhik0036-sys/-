package app.mama.core

import java.time.Instant

/**
 * A standard series: 3, 5 or 7 lock periods in a row with the same daily
 * window and trusted contact. Starting is free; leaving a lock with the
 * contact's code is always free and ends (fails) the series. After a failure
 * see [StandardRules] for when the next series can start.
 */
enum class SeriesKind(
    /** Lock periods needed to complete the series. */
    val periods: Int,
    /** Price of an immediate Restart after this series failed. */
    val restartPriceRub: Int,
) {
    THREE(3, 99),
    FIVE(5, 199),
    SEVEN(7, 299),
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
    val active: Boolean get() = status == SeriesStatus.ACTIVE
}

/**
 * Pure series rules on top of [LockEngine]. Every call returns the new series
 * and, when a lock period should be planned, the session to store.
 */
class SeriesEngine(private val engine: LockEngine, private val policy: CorePolicy = CorePolicy()) {

    data class Update(val series: Series, val session: Session?)

    /** Starts a series and plans its first night right away. */
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
        return Update(series, planNext(series, sessionId, now))
    }

    /**
     * Folds a finished session into the series: a completed period counts and
     * the next night is planned; an early exit (Trusted Exit) or a cancelled
     * night fails the series.
     */
    fun afterSession(series: Series, session: Session?, nextSessionId: String, now: Instant): Update {
        if (!series.active) return Update(series, session)
        if (session == null || session.status != SessionStatus.FINISHED || session.id == series.lastSessionId) {
            return Update(series, session)
        }
        val counted = series.copy(lastSessionId = session.id)
        return when (session.finishReason) {
            FinishReason.COMPLETED -> {
                val done = counted.completedPeriods + 1
                if (done >= series.kind.periods) {
                    Update(counted.copy(completedPeriods = done, status = SeriesStatus.COMPLETED, endedAt = now), session)
                } else {
                    val next = counted.copy(completedPeriods = done)
                    Update(next, planNext(next, nextSessionId, now) ?: session)
                }
            }
            FinishReason.EXITED_WITH_CONTACT, FinishReason.CANCELLED_BEFORE_START, null ->
                Update(counted.copy(status = SeriesStatus.BROKEN, endedAt = now), session)
        }
    }

    private fun planNext(series: Series, sessionId: String, now: Instant): Session? {
        val plan = series.window.nextPlan(now, series.mode, policy.minDuration)
        return (engine.create(sessionId, plan, series.contact, now) as? LockEngine.CreateResult.Created)?.session
    }
}
