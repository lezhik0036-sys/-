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
    FIVE(5, 179),
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

    /** The first lock's start time came; MAMA is switching strict protection on (short, see [CorePolicy.activationTimeout]). */
    ACTIVATING,

    /**
     * The start time came but strict protection could not be switched on.
     * The series did not begin: nothing counted, nothing charged, no failure.
     * Nothing locks again until the user explicitly confirms a new launch.
     */
    NEEDS_ATTENTION,

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
    /** When protection could not be switched on (the series went to NEEDS_ATTENTION). */
    val protectionMissedAt: Instant? = null,
    /** Start of the current activation attempt (ACTIVATING). */
    val activatingSince: Instant? = null,
    /**
     * Set for a Restart: the id of the failed series it restarts (same length,
     * whose Restart price was paid; the time is a new choice).
     */
    val restartOf: String? = null,
    /**
     * An AUTO_NOW launch not frozen yet: [window] was resolved from the moment
     * of the launch, and until the series becomes ACTIVE a new launch after
     * NEEDS_ATTENTION resolves it again from that moment, with the same
     * length. Cleared at ACTIVE: from then on the window is fixed like any
     * other. A MANUAL window never moves.
     */
    val autoNow: Boolean = false,
) {
    /** Not over yet. */
    val inProgress: Boolean get() = beforeCommitment || status == SeriesStatus.ACTIVE

    /** Binding: the conditions are fixed and leaving early is a failure. */
    val committed: Boolean get() = status == SeriesStatus.ACTIVE

    /** Before the commitment point: changing or cancelling has no consequences. */
    val beforeCommitment: Boolean
        get() = status == SeriesStatus.SCHEDULED || status == SeriesStatus.ACTIVATING || status == SeriesStatus.NEEDS_ATTENTION

    val isRestart: Boolean get() = restartOf != null

    /** When an unconfirmed activation gives up (NEEDS_ATTENTION), for the wake-up alarm. */
    fun activationDeadline(policy: CorePolicy): Instant? =
        if (status == SeriesStatus.ACTIVATING) activatingSince?.plus(policy.activationTimeout) else null
}

/**
 * Pure series rules on top of [LockEngine]. Every call returns the new series
 * and, when a lock period should be planned, the session to store.
 */
class SeriesEngine(private val engine: LockEngine, private val policy: CorePolicy = CorePolicy()) {

    data class Update(val series: Series, val session: Session?)

    /**
     * The user pressed "Начать серию" (or a Restart was granted): the series
     * is SCHEDULED and its first lock is planned for the next FULL window
     * ([DailyWindow.nextFullPlan]), never a shortened one already in
     * progress. Nothing is binding yet. With [startNow] (an AUTO_NOW launch,
     * see [LaunchTime.AutoNow]) the first lock starts at [now] instead, for
     * the window's whole length, and the series goes straight to ACTIVATING.
     */
    fun start(
        seriesId: String,
        sessionId: String,
        kind: SeriesKind,
        window: DailyWindow,
        contact: TrustedContact,
        mode: SessionMode,
        now: Instant,
        restartOf: String? = null,
        startNow: Boolean = false,
    ): Update {
        val series = Series(seriesId, kind, window, contact, mode, startedAt = now, restartOf = restartOf, autoNow = startNow)
        val session = if (startNow) planNow(series, sessionId, now) else planFirst(series, sessionId, now)
        return Update(enter(series, session, now), session)
    }

    sealed interface EditResult {
        data class Edited(val update: Update) : EditResult

        /** Only a series that has not begun can be changed; once ACTIVE everything is fixed. */
        data object NotAllowed : EditResult
    }

    /**
     * Changes the trusted contact of a series that has not begun, free of
     * charge. The schedule is NOT editable: series length and the daily
     * window are fixed when the series is confirmed (to use another time the
     * user cancels the launch and creates a new series). The planned first
     * lock stays exactly as it was; only its contact changes. The state does
     * not change either: after NEEDS_ATTENTION a new launch is a separate,
     * explicit [reschedule].
     */
    fun edit(series: Series, session: Session?, contact: TrustedContact, now: Instant): EditResult {
        if (series.status != SeriesStatus.SCHEDULED && series.status != SeriesStatus.NEEDS_ATTENTION) return EditResult.NotAllowed
        val s = session?.let { engine.advance(it, now) }
        // A lock that is already running is never touched.
        if (s?.status == SessionStatus.ACTIVE) return EditResult.NotAllowed
        val planned = if (s?.status == SessionStatus.SCHEDULED) s.copy(contact = contact) else session
        return EditResult.Edited(Update(series.copy(contact = contact), planned))
    }

    /**
     * After NEEDS_ATTENTION: the user explicitly confirms a new launch with
     * the same conditions. MANUAL: the next full window of the same fixed
     * time is planned (SCHEDULED). AUTO_NOW ([Series.autoNow]): the window is
     * resolved again from this moment, same length, and the lock starts now.
     */
    fun reschedule(series: Series, newSessionId: String, now: Instant): Update? {
        if (series.status != SeriesStatus.NEEDS_ATTENTION) return null
        if (series.autoNow) {
            // Not ACTIVE yet, so not frozen: "now" is the moment of this launch (18:00 missed, relaunched at 18:20 -> 18:20).
            val window = SeriesDraft.resolve(LaunchTime.AutoNow(series.window.length, series.window.zone), now).window
            val relaunched = series.copy(window = window, protectionMissedAt = null)
            val session = planNow(relaunched, newSessionId, now) ?: return null
            return Update(enter(relaunched, session, now), session)
        }
        val session = planFirst(series, newSessionId, now) ?: return null
        return Update(enter(series.copy(protectionMissedAt = null), session, now), session)
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
            activatingSince = null,
            lastSessionId = ended?.id ?: series.lastSessionId,
        )
        return Update(cancelled, ended)
    }

    /**
     * The commitment point. Called once strictProtectionReady holds on the
     * device: an ACTIVATING series whose lock is really running becomes
     * ACTIVE, day 1 begins and the conditions are fixed. Returns null when
     * there is nothing to confirm (also after a cancel or NEEDS_ATTENTION).
     */
    fun confirmActive(series: Series, session: Session?, now: Instant): Series? {
        if (series.status != SeriesStatus.ACTIVATING || session == null) return null
        if (engine.stateOf(session, now) !is LockState.Locked) return null
        // The freeze point: an AUTO_NOW window is final from here on.
        return series.copy(status = SeriesStatus.ACTIVE, actualStartedAt = now, activatingSince = null, protectionMissedAt = null, autoNow = false)
    }

    /**
     * Strict protection could not be switched on (a mandatory permission is
     * missing, or it did not come up within [CorePolicy.activationTimeout]):
     * the unconfirmed lock ends right away and the series waits for the user
     * (NEEDS_ATTENTION). Not ACTIVE, not failed, nothing counted or charged.
     */
    fun protectionFailed(series: Series, session: Session?, now: Instant): Update? {
        if (series.status != SeriesStatus.ACTIVATING) return null
        val ended = session?.let { engine.cancelUnconfirmed(it, now) }
        return Update(needsAttention(series, ended, now), ended)
    }

    /**
     * Before the first unlock after a reboot the guard cannot run yet; the
     * phone is held by the system lock screen meanwhile. The activation
     * timeout starts counting once the phone has been unlocked.
     */
    fun awaitUnlock(series: Series, now: Instant): Series =
        if (series.status == SeriesStatus.ACTIVATING) series.copy(activatingSince = now) else series

    /**
     * Folds the current session into the series.
     * - Before the commitment point: a started lock makes the series
     *   ACTIVATING; an activation that is not confirmed in time, or a lock
     *   that ended without confirmed protection, puts it in NEEDS_ATTENTION
     *   (never silently moved to the next night); a cancelled one cancels it
     *   without consequences.
     * - ACTIVE: a completed period counts and the next night is planned; an
     *   early exit (Trusted Exit) or a cancelled night fails the series.
     */
    fun afterSession(series: Series, session: Session?, nextSessionId: String, now: Instant): Update = when (series.status) {
        SeriesStatus.SCHEDULED, SeriesStatus.ACTIVATING -> beforeCommitment(series, session, now)
        SeriesStatus.ACTIVE -> committed(series, session, nextSessionId, now)
        SeriesStatus.NEEDS_ATTENTION, SeriesStatus.COMPLETED, SeriesStatus.BROKEN, SeriesStatus.CANCELLED_BEFORE_START ->
            Update(series, session)
    }

    private fun beforeCommitment(series: Series, session: Session?, now: Instant): Update {
        if (session == null || session.id == series.lastSessionId) return Update(series, session)
        return when (session.status) {
            SessionStatus.SCHEDULED -> Update(series, session)
            SessionStatus.ACTIVE -> {
                val deadline = series.activationDeadline(policy)
                when {
                    series.status == SeriesStatus.SCHEDULED || deadline == null ->
                        Update(series.copy(status = SeriesStatus.ACTIVATING, activatingSince = series.activatingSince ?: now), session)
                    !now.isBefore(deadline) -> protectionFailed(series, session, now)!!
                    else -> Update(series, session)
                }
            }
            SessionStatus.FINISHED -> {
                val endedAt = session.finishedAt ?: now
                if (session.finishReason == FinishReason.CANCELLED_BEFORE_START) {
                    Update(series.copy(lastSessionId = session.id, status = SeriesStatus.CANCELLED_BEFORE_START, endedAt = endedAt, activatingSince = null), session)
                } else {
                    // The lock never became binding: nothing began, nothing failed,
                    // and nothing is planned until the user confirms again.
                    Update(needsAttention(series, session, endedAt), session)
                }
            }
        }
    }

    private fun needsAttention(series: Series, ended: Session?, at: Instant) = series.copy(
        status = SeriesStatus.NEEDS_ATTENTION,
        protectionMissedAt = at,
        activatingSince = null,
        lastSessionId = ended?.id ?: series.lastSessionId,
    )

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

    /** SCHEDULED, or ACTIVATING when the planned lock is already running (a first lock only for AUTO_NOW, see [planNow]). */
    private fun enter(series: Series, session: Session?, now: Instant) =
        if (session?.status == SessionStatus.ACTIVE) {
            series.copy(status = SeriesStatus.ACTIVATING, activatingSince = now)
        } else {
            series.copy(status = SeriesStatus.SCHEDULED, activatingSince = null)
        }

    /** First lock: the next full window, starting in the future. */
    private fun planFirst(series: Series, sessionId: String, now: Instant): Session? {
        val plan = series.window.nextFullPlan(now, series.mode)
        return (engine.create(sessionId, plan, series.contact, now) as? LockEngine.CreateResult.Created)?.session
    }

    /** First lock of an AUTO_NOW launch: from the confirmation itself, for the window's whole length. */
    private fun planNow(series: Series, sessionId: String, now: Instant): Session? {
        val plan = LockPlan(start = now, end = now.plus(series.window.length), zone = series.window.zone, mode = series.mode)
        return (engine.create(sessionId, plan, series.contact, now) as? LockEngine.CreateResult.Created)?.session
    }

    /** The next night of an ACTIVE series (unchanged rule: the window in progress or the next one). */
    private fun planNext(series: Series, sessionId: String, now: Instant): Session? {
        val plan = series.window.nextPlan(now, series.mode, policy.minDuration)
        return (engine.create(sessionId, plan, series.contact, now) as? LockEngine.CreateResult.Created)?.session
    }
}
