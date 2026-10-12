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

/**
 * Where a series is. Pressing "Начать серию" only schedules it: the series
 * becomes binding (ACTIVE) when its first lock has started AND strict
 * protection is confirmed on the device. Only an ACTIVE series can fail.
 *
 * Persisted by name: COMPLETED is the "success" state and BROKEN the
 * "failed" one (kept under their original names for stored state).
 */
enum class SeriesStatus {
    /** Confirmed by the user, first lock not started yet: free to change or cancel. */
    SCHEDULED,

    /** The first lock's start time came; MAMA is switching strict protection on and checking it. */
    ACTIVATING,

    /** Strict protection confirmed: conditions are fixed and the failure rules apply. */
    ACTIVE,

    /** Success: every period completed. */
    COMPLETED,

    /** Failed: an ACTIVE series ended early (Trusted Exit). */
    BROKEN,

    /** Cancelled before it became ACTIVE: no consequences of any kind. */
    CANCELLED_BEFORE_START,
}

data class Series(
    val id: String,
    val kind: SeriesKind,
    val window: DailyWindow,
    val contact: TrustedContact,
    val mode: SessionMode,
    /** When the user confirmed the series ("Начать серию"); not when it began. */
    val startedAt: Instant,
    val completedPeriods: Int = 0,
    val status: SeriesStatus = SeriesStatus.SCHEDULED,
    val endedAt: Instant? = null,
    /** The session already counted, so a reconcile never counts it twice. */
    val lastSessionId: String? = null,
    /** The commitment point: strict protection confirmed and the series became ACTIVE. */
    val actualStartedAt: Instant? = null,
    /**
     * A planned first lock passed without strict protection being confirmed
     * (for example, a permission was missing). The series did not begin and
     * waits for the next night.
     */
    val protectionMissedAt: Instant? = null,
) {
    /** Not over yet: scheduled, activating or active. */
    val inProgress: Boolean
        get() = status == SeriesStatus.SCHEDULED || status == SeriesStatus.ACTIVATING || status == SeriesStatus.ACTIVE

    /** Binding: the conditions are fixed and leaving early is a failure. */
    val committed: Boolean get() = status == SeriesStatus.ACTIVE

    /** Before the commitment point: changing or cancelling has no consequences. */
    val beforeCommitment: Boolean get() = status == SeriesStatus.SCHEDULED || status == SeriesStatus.ACTIVATING
}

/**
 * Pure series rules on top of [LockEngine]. Every call returns the new series
 * and, when a lock period should be planned, the session to store.
 */
class SeriesEngine(private val engine: LockEngine, private val policy: CorePolicy = CorePolicy()) {

    data class Update(val series: Series, val session: Session?)

    /**
     * The user pressed "Начать серию": the series is SCHEDULED and its first
     * night is planned. Nothing is binding yet. A window that has already
     * begun starts locking right away (ACTIVATING).
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
        val session = planNext(series, sessionId, now)
        return Update(series.copy(status = scheduledOrActivating(session)), session)
    }

    sealed interface EditResult {
        data class Edited(val update: Update) : EditResult

        /** Only a series that has not begun can be changed; once ACTIVE everything is fixed. */
        data object NotAllowed : EditResult

        data class Rejected(val reason: LockEngine.CreateError) : EditResult
    }

    /**
     * Changes the conditions of a SCHEDULED series, free of charge: series
     * length, daily window, trusted contact, mode. The planned first lock is
     * replaced, so the countdown follows the new time.
     */
    fun edit(
        series: Series,
        session: Session?,
        kind: SeriesKind,
        window: DailyWindow,
        contact: TrustedContact,
        mode: SessionMode,
        newSessionId: String,
        now: Instant,
    ): EditResult {
        if (series.status != SeriesStatus.SCHEDULED) return EditResult.NotAllowed
        // A lock that is already running is never replaced.
        if (session != null && engine.advance(session, now).status == SessionStatus.ACTIVE) return EditResult.NotAllowed
        val plan = window.nextPlan(now, mode, policy.minDuration)
        return when (val created = engine.create(newSessionId, plan, contact, now)) {
            is LockEngine.CreateResult.Rejected -> EditResult.Rejected(created.reason)
            is LockEngine.CreateResult.Created -> {
                val edited = series.copy(
                    kind = kind, window = window, contact = contact, mode = mode,
                    status = scheduledOrActivating(created.session),
                )
                EditResult.Edited(Update(edited, created.session))
            }
        }
    }

    /**
     * "Отменить запуск": ends a series that has not become ACTIVE, with no
     * consequences (no failure, no Restart, no waiting period). Returns null
     * for an ACTIVE or finished series: those cannot be cancelled.
     */
    fun cancelScheduled(series: Series, session: Session?, now: Instant): Update? {
        if (!series.beforeCommitment) return null
        val s = session?.let { engine.advance(it, now) }
        val ended = when (s?.status) {
            SessionStatus.SCHEDULED -> (engine.cancelBeforeStart(s, now) as LockEngine.CancelResult.Cancelled).session
            // Started on time but protection was never confirmed (ACTIVATING).
            SessionStatus.ACTIVE -> engine.cancelUnconfirmed(s, now)
            SessionStatus.FINISHED, null -> s
        }
        val cancelled = series.copy(
            status = SeriesStatus.CANCELLED_BEFORE_START,
            endedAt = now,
            lastSessionId = ended?.id ?: series.lastSessionId,
        )
        return Update(cancelled, ended)
    }

    /**
     * The commitment point. Called by the device once the lock screen is
     * up and strict protection is on: an ACTIVATING series whose lock is
     * really running becomes ACTIVE, day 1 begins and the conditions are
     * fixed. Returns null when there is nothing to confirm.
     */
    fun confirmActive(series: Series, session: Session?, now: Instant): Series? {
        if (series.status != SeriesStatus.ACTIVATING || session == null) return null
        if (engine.stateOf(session, now) !is LockState.Locked) return null
        return series.copy(status = SeriesStatus.ACTIVE, actualStartedAt = now, protectionMissedAt = null)
    }

    /**
     * Folds the current session into the series.
     * - Before the commitment point: a started lock makes the series
     *   ACTIVATING; a lock that ended without confirmed protection does not
     *   count and does not fail anything (the next night is planned); a
     *   cancelled one cancels the series without consequences.
     * - ACTIVE: a completed period counts and the next night is planned; an
     *   early exit (Trusted Exit) or a cancelled night fails the series.
     */
    fun afterSession(series: Series, session: Session?, nextSessionId: String, now: Instant): Update = when (series.status) {
        SeriesStatus.SCHEDULED, SeriesStatus.ACTIVATING -> beforeCommitment(series, session, nextSessionId, now)
        SeriesStatus.ACTIVE -> committed(series, session, nextSessionId, now)
        SeriesStatus.COMPLETED, SeriesStatus.BROKEN, SeriesStatus.CANCELLED_BEFORE_START -> Update(series, session)
    }

    private fun beforeCommitment(series: Series, session: Session?, nextSessionId: String, now: Instant): Update {
        if (session == null || session.id == series.lastSessionId) return Update(series, session)
        return when (session.status) {
            SessionStatus.SCHEDULED -> Update(series, session)
            SessionStatus.ACTIVE -> Update(series.copy(status = SeriesStatus.ACTIVATING), session)
            SessionStatus.FINISHED -> {
                val counted = series.copy(lastSessionId = session.id)
                val endedAt = session.finishedAt ?: now
                if (session.finishReason == FinishReason.CANCELLED_BEFORE_START) {
                    Update(counted.copy(status = SeriesStatus.CANCELLED_BEFORE_START, endedAt = endedAt), session)
                } else {
                    // The lock never became binding: nothing began, nothing failed.
                    val again = counted.copy(status = SeriesStatus.SCHEDULED, protectionMissedAt = endedAt)
                    val next = planNext(again, nextSessionId, now)
                    Update(again.copy(status = scheduledOrActivating(next)), next ?: session)
                }
            }
        }
    }

    private fun committed(series: Series, session: Session?, nextSessionId: String, now: Instant): Update {
        if (session == null || session.status != SessionStatus.FINISHED || session.id == series.lastSessionId) {
            return Update(series, session)
        }
        val counted = series.copy(lastSessionId = session.id)
        // The series ends when its session ended (Trusted Exit, cancel, or the
        // planned end), not when this reconcile happened to run.
        val endedAt = session.finishedAt ?: now
        return when (session.finishReason) {
            FinishReason.COMPLETED -> {
                val done = counted.completedPeriods + 1
                if (done >= series.kind.periods) {
                    Update(counted.copy(completedPeriods = done, status = SeriesStatus.COMPLETED, endedAt = endedAt), session)
                } else {
                    val next = counted.copy(completedPeriods = done)
                    Update(next, planNext(next, nextSessionId, now) ?: session)
                }
            }
            FinishReason.EXITED_WITH_CONTACT, FinishReason.CANCELLED_BEFORE_START, null ->
                Update(counted.copy(status = SeriesStatus.BROKEN, endedAt = endedAt), session)
        }
    }

    private fun scheduledOrActivating(session: Session?) =
        if (session?.status == SessionStatus.ACTIVE) SeriesStatus.ACTIVATING else SeriesStatus.SCHEDULED

    private fun planNext(series: Series, sessionId: String, now: Instant): Session? {
        val plan = series.window.nextPlan(now, series.mode, policy.minDuration)
        return (engine.create(sessionId, plan, series.contact, now) as? LockEngine.CreateResult.Created)?.session
    }
}
