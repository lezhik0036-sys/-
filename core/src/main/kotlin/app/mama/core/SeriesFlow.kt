package app.mama.core

import java.time.Instant

/**
 * Every series transition on the whole persisted state. The series, its
 * lock, "Цена слова", a held Restart, the 30-day wait and the failed series
 * a pending Restart replaced all change together, in one [Snapshot].
 *
 * The platform runs one transition at a time on freshly loaded state and
 * saves the result in a single write. So two transitions never both apply:
 * "Отменить запуск" racing with the commitment point ends in exactly one of
 * CANCELLED_BEFORE_START or ACTIVE, because each step refuses a series that
 * the other one has already moved on.
 */
class SeriesFlow(lockEngine: LockEngine, policy: CorePolicy = CorePolicy()) {
    private val series = SeriesEngine(lockEngine, policy)

    sealed interface Result {
        data class Done(val snapshot: Snapshot) : Result
        data class NotEnoughBalance(val availableKop: Long) : Result

        /** The series is not in a state that allows this (for example it is already ACTIVE). */
        data object NotAllowed : Result
        data object Rejected : Result
    }

    /** "Начать серию" for a free series. Busy and 30-day checks are the caller's. */
    fun schedule(
        s: Snapshot,
        seriesId: String,
        sessionId: String,
        kind: SeriesKind,
        window: DailyWindow,
        contact: TrustedContact,
        mode: SessionMode,
        commitmentKop: Long?,
        now: Instant,
    ): Result {
        val u = series.start(seriesId, sessionId, kind, window, contact, mode, now)
        val wallet = when (val r = reserveCommitment(s.wallet, seriesId, commitmentKop, now)) {
            is WalletEngine.Result.Ok -> r.wallet
            is WalletEngine.Result.Insufficient -> return Result.NotEnoughBalance(r.availableKop)
            is WalletEngine.Result.Invalid -> return Result.Rejected
        }
        return Result.Done(s.copy(series = u.series, session = u.session, wallet = wallet))
    }

    /**
     * Restart of the failed series in [s], once granted (paid, a test grant,
     * or [GrantSource.INTERNAL_BALANCE]: the Restart price is held from the
     * balance here). The new series is SCHEDULED like any other: the Restart
     * is used, and the 30-day wait of the failure lifted, only when it
     * becomes ACTIVE. Until then the failed series is kept aside, so a cancel
     * puts everything back as it was before the Restart.
     *
     * TODO(payments): a store-paid Restart cancelled before ACTIVE needs a
     * store refund or credit; decide with the payment integration.
     */
    fun restart(s: Snapshot, source: GrantSource, seriesId: String, sessionId: String, commitmentKop: Long?, now: Instant): Result {
        val failed = s.series?.takeIf { it.status == SeriesStatus.BROKEN } ?: return Result.NotAllowed
        var wallet = s.wallet
        if (source == GrantSource.INTERNAL_BALANCE) {
            wallet = when (val r = WalletEngine.holdRestart(wallet, seriesId, failed.kind.restartPriceRub * 100L, now)) {
                is WalletEngine.Result.Ok -> r.wallet
                is WalletEngine.Result.Insufficient -> return Result.NotEnoughBalance(r.availableKop)
                is WalletEngine.Result.Invalid -> return Result.Rejected
            }
        }
        val u = series.start(seriesId, sessionId, failed.kind, failed.window, failed.contact, failed.mode, now, restartOf = failed.id)
        wallet = when (val r = reserveCommitment(wallet, seriesId, commitmentKop, now)) {
            is WalletEngine.Result.Ok -> r.wallet
            is WalletEngine.Result.Insufficient -> return Result.NotEnoughBalance(r.availableKop)
            is WalletEngine.Result.Invalid -> return Result.Rejected
        }
        return Result.Done(s.copy(series = u.series, session = u.session, wallet = wallet, restartedFrom = failed))
    }

    /** Brings the series up to date with its lock and settles what follows from it (reconcile). */
    fun fold(s: Snapshot, nextSessionId: String, now: Instant): Snapshot {
        val current = s.series ?: return s
        if (!current.inProgress) return settle(s, now)
        val u = series.afterSession(current, s.session, nextSessionId, now)
        return settle(s.copy(series = u.series, session = u.session), now)
    }

    /**
     * The commitment point: strictProtectionReady holds on the device. The
     * series becomes ACTIVE and, in the same snapshot, "Цена слова" is fixed,
     * a held Restart is used and the failure it restarts stops blocking.
     */
    fun confirm(s: Snapshot, now: Instant): Snapshot? {
        val current = s.series ?: return null
        val active = series.confirmActive(current, s.session, now) ?: return null
        return settle(s.copy(series = active), now)
    }

    /** Strict protection could not be switched on: NEEDS_ATTENTION, the unconfirmed lock ends. */
    fun protectionFailed(s: Snapshot, now: Instant): Snapshot? {
        val current = s.series ?: return null
        val u = series.protectionFailed(current, s.session, now) ?: return null
        return settle(s.copy(series = u.series, session = u.session), now)
    }

    /** Phone not unlocked since a reboot: the activation timeout waits (see [SeriesEngine.awaitUnlock]). */
    fun awaitUnlock(s: Snapshot, now: Instant): Snapshot {
        val current = s.series ?: return s
        return s.copy(series = series.awaitUnlock(current, now))
    }

    data class Cancelled(val snapshot: Snapshot, val cancelled: Series)

    /**
     * "Отменить запуск" before ACTIVE: CANCELLED_BEFORE_START, the scheduled
     * "Цена слова" and a held Restart go back to the available balance in
     * full. A cancelled Restart is undone: the failed series comes back with
     * its failure date and 30-day wait, which were never lifted.
     */
    fun cancel(s: Snapshot, now: Instant): Cancelled? {
        val current = s.series ?: return null
        val u = series.cancelScheduled(current, s.session, now) ?: return null
        val wallet = WalletEngine.settle(s.wallet, u.series, now)
        return Cancelled(s.copy(series = s.restartedFrom, restartedFrom = null, session = u.session, wallet = wallet), u.series)
    }

    /** "Изменить условия" before ACTIVE ([commitmentKop] null = without "Цена слова"). */
    fun edit(
        s: Snapshot,
        kind: SeriesKind,
        window: DailyWindow,
        contact: TrustedContact,
        mode: SessionMode,
        commitmentKop: Long?,
        sessionId: String,
        now: Instant,
    ): Result {
        val current = s.series ?: return Result.NotAllowed
        val u = when (val e = series.edit(current, s.session, kind, window, contact, mode, sessionId, now)) {
            is SeriesEngine.EditResult.Edited -> e.update
            SeriesEngine.EditResult.NotAllowed -> return Result.NotAllowed
            is SeriesEngine.EditResult.Rejected -> return Result.Rejected
        }
        val wallet = when (val r = WalletEngine.changeScheduled(s.wallet, current.id, commitmentKop?.takeIf { it > 0 }, now)) {
            is WalletEngine.Result.Ok -> r.wallet
            is WalletEngine.Result.Insufficient -> return Result.NotEnoughBalance(r.availableKop)
            is WalletEngine.Result.Invalid -> return Result.NotAllowed
        }
        return Result.Done(s.copy(series = u.series, session = u.session, wallet = wallet))
    }

    /** After NEEDS_ATTENTION: the user's explicit new launch with the same conditions. */
    fun reschedule(s: Snapshot, sessionId: String, now: Instant): Result {
        val current = s.series ?: return Result.NotAllowed
        if (current.status != SeriesStatus.NEEDS_ATTENTION) return Result.NotAllowed
        val u = series.reschedule(current, sessionId, now) ?: return Result.Rejected
        return Result.Done(s.copy(series = u.series, session = u.session))
    }

    /** What follows from the series status: the 30-day wait, a pending Restart, the wallet. */
    private fun settle(s: Snapshot, now: Instant): Snapshot {
        val current = s.series ?: return s
        var ent = s.entitlements
        var replaced = s.restartedFrom
        val begun = current.status == SeriesStatus.ACTIVE || current.status == SeriesStatus.COMPLETED || current.status == SeriesStatus.BROKEN
        if (replaced != null && begun) {
            // The Restart series began: the wait caused by the failure it restarts is over.
            ent = ent.copy(standardFailedAt = null)
            replaced = null
        }
        ent = StandardRules.afterSeries(ent, current, now)
        return s.copy(entitlements = ent, restartedFrom = replaced, wallet = WalletEngine.settle(s.wallet, current, now))
    }

    private fun reserveCommitment(w: Wallet, seriesId: String, commitmentKop: Long?, now: Instant): WalletEngine.Result =
        if (commitmentKop == null || commitmentKop <= 0) WalletEngine.Result.Ok(w) else WalletEngine.reserve(w, seriesId, commitmentKop, now)
}
