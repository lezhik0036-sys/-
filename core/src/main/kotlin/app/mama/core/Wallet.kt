package app.mama.core

import java.time.Duration
import java.time.Instant

/*
 * MAMA internal balance and "Цена слова" (a voluntary personal commitment).
 *
 * The balance exists only inside MAMA: it cannot be withdrawn, cashed out or
 * transferred, and is spent only on approved MAMA products (Restart, FLEX).
 * Pilot: test credits only, no real money (see FeatureFlags in the app).
 *
 * "Цена слова" is separate from the Restart price and never added to it. It
 * is set aside from the available balance when a standard series is
 * scheduled (SCHEDULED: can still be changed or released in full, no loss),
 * fixed when the series becomes ACTIVE (RESERVED), released in full when the
 * series is completed, and forfeited when an ACTIVE series fails;
 * RETURN_PERCENT of it then comes back to the available balance after
 * RETURN_DELAY, exactly once. A series cancelled before it became ACTIVE
 * gets its whole scheduled reserve back.
 *
 * All amounts are in kopecks. Every balance change is also a ledger entry.
 *
 * RETURN_DELAY is real calendar time from the failure moment (returnDueAt =
 * failure + 24 h, an absolute timestamp), not phone uptime: time while the
 * phone is off counts. Once now >= returnDueAt the return is credited at the
 * first chance (alarm, app start, reboot, service tick), exactly once.
 *
 * Pilot: the timestamps and the ledger are local. Known limitation: moving
 * the clock forward while the phone is off cannot be detected offline.
 * TODO(production, real money): make it server-authoritative — the MAMA
 * backend sets failureTimestamp and returnDueAt and owns the ledger; the
 * client cannot speed up a return by changing its clock and syncs with the
 * backend when it is back online. Do not use phone uptime as a substitute
 * for server time.
 */

enum class LedgerType {
    BALANCE_TOPUP_TEST,
    COMMITMENT_RESERVED,

    /** A scheduled reserve given back in full: changed or cancelled before the series began. */
    COMMITMENT_RELEASED_BEFORE_START,
    COMMITMENT_RELEASED_SUCCESS,
    COMMITMENT_FORFEITED_FAILURE,
    COMMITMENT_PARTIAL_RETURN,
    RESTART_PURCHASE_TEST,
    FLEX_PURCHASE_TEST,
}

enum class LedgerStatus {
    DONE,

    /** A credit that will happen at [LedgerEntry.dueAt] (the 24-hour partial return). */
    SCHEDULED,
}

data class LedgerEntry(
    val id: String,
    val at: Instant,
    val amountKop: Long,
    val type: LedgerType,
    val seriesId: String? = null,
    val flexId: String? = null,
    val status: LedgerStatus = LedgerStatus.DONE,
    val dueAt: Instant? = null,
)

enum class CommitmentState {
    /** Set aside for a series that has not begun: can be changed or released in full. */
    SCHEDULED,

    /** Fixed: the series became ACTIVE. */
    RESERVED,
    RELEASED,
    FORFEITED,
}

/** "Цена слова" of one standard series. Never carried over to another series. */
data class Commitment(
    val seriesId: String,
    val amountKop: Long,
    val reservedAt: Instant,
    val state: CommitmentState = CommitmentState.SCHEDULED,
    /** Success or failure moment (the failure timestamp when FORFEITED). */
    val settledAt: Instant? = null,
    val returnKop: Long = 0,
    val returnDueAt: Instant? = null,
    /** The partial return was credited; guards against crediting twice. */
    val returnProcessed: Boolean = false,
    /** The user has seen the "part of the amount came back" message. */
    val returnSeen: Boolean = false,
    /** When the series became ACTIVE and the amount was fixed. */
    val fixedAt: Instant? = null,
)

data class Wallet(
    val availableKop: Long = 0,
    val reservedKop: Long = 0,
    val entries: List<LedgerEntry> = emptyList(),
    val commitments: List<Commitment> = emptyList(),
) {
    fun commitmentFor(seriesId: String?): Commitment? = commitments.firstOrNull { it.seriesId == seriesId }

    /** Part of [reservedKop] set aside for series that have not begun (can still be released in full). */
    val scheduledKop: Long
        get() = commitments.filter { it.state == CommitmentState.SCHEDULED }.sumOf { it.amountKop }

    /** Earliest pending partial return, for the wake-up alarm. */
    val nextReturnDue: Instant?
        get() = commitments.filter { it.state == CommitmentState.FORFEITED && !it.returnProcessed }.mapNotNull { it.returnDueAt }.minOrNull()

    /** Credited returns the user has not seen yet. */
    val unseenReturns: List<Commitment>
        get() = commitments.filter { it.returnProcessed && !it.returnSeen }
}

/**
 * "Цена слова" configuration: the only place these numbers live. UI and
 * logic read them from here.
 */
object CommitmentConfig {
    /**
     * Pilot technical safety limit, NOT a product rule. TODO(product/legal):
     * revisit after testing, payment integration and legal review.
     */
    const val MAX_COMMITMENT_PRICE_RUB = 100_000

    /** Smallest own amount. TODO(product): confirm. */
    const val MIN_COMMITMENT_PRICE_RUB = 100

    /** Quick choices on the "Цена слова" screen. */
    val QUICK_AMOUNTS_RUB = listOf(500, 1_000, 5_000)

    /** Share of a forfeited commitment credited back to the available balance. */
    const val RETURN_PERCENT = 50

    /** Delay of that credit after the failure. */
    val RETURN_DELAY: Duration = Duration.ofHours(24)

    const val MIN_KOP = MIN_COMMITMENT_PRICE_RUB * 100L
    const val MAX_KOP = MAX_COMMITMENT_PRICE_RUB * 100L
}

object WalletEngine {
    val RETURN_PERCENT get() = CommitmentConfig.RETURN_PERCENT
    val RETURN_DELAY: Duration get() = CommitmentConfig.RETURN_DELAY

    sealed interface Result {
        data class Ok(val wallet: Wallet) : Result
        data class Insufficient(val availableKop: Long) : Result
        data class Invalid(val reason: String) : Result
    }

    /** Test credits (pilot only). Not money. */
    fun topUpTest(w: Wallet, amountKop: Long, id: String, now: Instant): Result {
        if (amountKop <= 0) return Result.Invalid("amount")
        return Result.Ok(
            w.copy(
                availableKop = w.availableKop + amountKop,
                entries = w.entries + LedgerEntry(id, now, amountKop, LedgerType.BALANCE_TOPUP_TEST),
            ),
        )
    }

    /**
     * Moves "Цена слова" from available to reserved for one series that has
     * just been scheduled. Until the series becomes ACTIVE the reserve is
     * SCHEDULED: see [changeScheduled].
     */
    fun reserve(w: Wallet, seriesId: String, amountKop: Long, now: Instant): Result {
        if (amountKop < CommitmentConfig.MIN_KOP || amountKop > CommitmentConfig.MAX_KOP) return Result.Invalid("amount")
        if (w.commitmentFor(seriesId) != null) return Result.Invalid("already reserved")
        if (w.availableKop < amountKop) return Result.Insufficient(w.availableKop)
        return Result.Ok(
            w.copy(
                availableKop = w.availableKop - amountKop,
                reservedKop = w.reservedKop + amountKop,
                entries = w.entries + LedgerEntry(entryId(w, "reserve", seriesId), now, amountKop, LedgerType.COMMITMENT_RESERVED, seriesId = seriesId),
                commitments = w.commitments + Commitment(seriesId, amountKop, now),
            ),
        )
    }

    /**
     * Changes the "Цена слова" of a series that has not begun, with no loss:
     * the old scheduled amount goes back to available in full and the new
     * one (if any) is set aside. [newAmountKop] null or 0 removes it.
     * A fixed commitment (the series is ACTIVE) cannot be changed.
     */
    fun changeScheduled(w: Wallet, seriesId: String, newAmountKop: Long?, now: Instant): Result {
        val current = w.commitmentFor(seriesId)
        if (current != null && current.state != CommitmentState.SCHEDULED) return Result.Invalid("fixed")
        val old = current?.amountKop ?: 0
        val new = newAmountKop ?: 0
        if (new == old) return Result.Ok(w)
        if (new != 0L && (new < CommitmentConfig.MIN_KOP || new > CommitmentConfig.MAX_KOP)) return Result.Invalid("amount")
        if (w.availableKop + old < new) return Result.Insufficient(w.availableKop + old)
        val released = current?.let { releaseScheduled(w, it, now) } ?: w
        return if (new == 0L) Result.Ok(released) else reserve(released, seriesId, new, now)
    }

    /**
     * Spends the AVAILABLE balance on an approved MAMA product. The reserved
     * balance belongs to an active "Цена слова" and is never spent.
     */
    fun spend(w: Wallet, amountKop: Long, type: LedgerType, id: String, now: Instant, seriesId: String? = null, flexId: String? = null): Result {
        require(type == LedgerType.RESTART_PURCHASE_TEST || type == LedgerType.FLEX_PURCHASE_TEST) { "not a purchase: $type" }
        if (amountKop <= 0) return Result.Invalid("amount")
        if (w.availableKop < amountKop) return Result.Insufficient(w.availableKop)
        return Result.Ok(
            w.copy(
                availableKop = w.availableKop - amountKop,
                entries = w.entries + LedgerEntry(id, now, amountKop, type, seriesId = seriesId, flexId = flexId),
            ),
        )
    }

    /**
     * Brings the commitment in line with its series, once per step:
     * - scheduled / activating: nothing happens, the reserve stays changeable;
     * - cancelled before start: the scheduled reserve is released in full;
     * - ACTIVE: the amount is fixed (the commitment point);
     * - completed: released in full;
     * - failed (only possible after ACTIVE): forfeited, with the partial
     *   return scheduled for failure time + [RETURN_DELAY].
     * Already settled commitments are left as they are.
     */
    fun settle(w: Wallet, series: Series, now: Instant): Wallet {
        val c = w.commitmentFor(series.id) ?: return w
        return when (c.state) {
            CommitmentState.SCHEDULED -> when (series.status) {
                SeriesStatus.SCHEDULED, SeriesStatus.ACTIVATING -> w
                SeriesStatus.CANCELLED_BEFORE_START -> releaseScheduled(w, c, series.endedAt ?: now)
                SeriesStatus.ACTIVE, SeriesStatus.COMPLETED, SeriesStatus.BROKEN ->
                    settle(fix(w, c, series.actualStartedAt ?: now), series, now)
            }
            CommitmentState.RESERVED -> when (series.status) {
                SeriesStatus.COMPLETED -> release(w, c, series.endedAt ?: now)
                SeriesStatus.BROKEN -> forfeit(w, c, series.endedAt ?: now)
                // Never forfeit without a failure: a series that did not begin gets everything back.
                SeriesStatus.CANCELLED_BEFORE_START -> releaseScheduled(w, c, series.endedAt ?: now)
                SeriesStatus.SCHEDULED, SeriesStatus.ACTIVATING, SeriesStatus.ACTIVE -> w
            }
            CommitmentState.RELEASED, CommitmentState.FORFEITED -> w
        }
    }

    private fun fix(w: Wallet, c: Commitment, at: Instant) = w.copy(
        commitments = w.commitments.map { if (it.seriesId == c.seriesId) it.copy(state = CommitmentState.RESERVED, fixedAt = at) else it },
    )

    /** Gives a not-yet-binding reserve back in full and forgets it (no settlement, no loss). */
    private fun releaseScheduled(w: Wallet, c: Commitment, at: Instant) = w.copy(
        availableKop = w.availableKop + c.amountKop,
        reservedKop = w.reservedKop - c.amountKop,
        entries = w.entries + LedgerEntry(
            entryId(w, "unreserve", c.seriesId), at, c.amountKop, LedgerType.COMMITMENT_RELEASED_BEFORE_START, seriesId = c.seriesId,
        ),
        commitments = w.commitments.filter { it.seriesId != c.seriesId },
    )

    /** "<prefix>-<series>", made unique when a scheduled reserve was changed before. */
    private fun entryId(w: Wallet, prefix: String, seriesId: String): String {
        val base = "$prefix-$seriesId"
        if (w.entries.none { it.id == base }) return base
        return "$base-${w.entries.count { it.seriesId == seriesId }}"
    }

    private fun release(w: Wallet, c: Commitment, at: Instant) = w.copy(
        availableKop = w.availableKop + c.amountKop,
        reservedKop = w.reservedKop - c.amountKop,
        entries = w.entries + LedgerEntry("release-${c.seriesId}", at, c.amountKop, LedgerType.COMMITMENT_RELEASED_SUCCESS, seriesId = c.seriesId),
        commitments = w.commitments.map { if (it.seriesId == c.seriesId) it.copy(state = CommitmentState.RELEASED, settledAt = at) else it },
    )

    private fun forfeit(w: Wallet, c: Commitment, failedAt: Instant): Wallet {
        val back = c.amountKop * RETURN_PERCENT / 100
        val due = failedAt + RETURN_DELAY
        return w.copy(
            reservedKop = w.reservedKop - c.amountKop,
            entries = w.entries +
                LedgerEntry("forfeit-${c.seriesId}", failedAt, c.amountKop, LedgerType.COMMITMENT_FORFEITED_FAILURE, seriesId = c.seriesId) +
                LedgerEntry(
                    "return-${c.seriesId}", failedAt, back, LedgerType.COMMITMENT_PARTIAL_RETURN,
                    seriesId = c.seriesId, status = LedgerStatus.SCHEDULED, dueAt = due,
                ),
            commitments = w.commitments.map {
                if (it.seriesId == c.seriesId) it.copy(state = CommitmentState.FORFEITED, settledAt = failedAt, returnKop = back, returnDueAt = due) else it
            },
        )
    }

    /**
     * Credits every partial return that is due by [now], exactly once: the
     * commitment's returnProcessed flag and the ledger entry's status change
     * together, so running this again (or after a reboot) does nothing.
     */
    fun processDue(w: Wallet, now: Instant): Wallet {
        val due = w.commitments.filter {
            it.state == CommitmentState.FORFEITED && !it.returnProcessed && it.returnDueAt != null && !now.isBefore(it.returnDueAt)
        }
        if (due.isEmpty()) return w
        val ids = due.map { it.seriesId }.toSet()
        return w.copy(
            availableKop = w.availableKop + due.sumOf { it.returnKop },
            entries = w.entries.map {
                if (it.type == LedgerType.COMMITMENT_PARTIAL_RETURN && it.seriesId in ids && it.status == LedgerStatus.SCHEDULED) {
                    it.copy(status = LedgerStatus.DONE, at = it.dueAt ?: now)
                } else {
                    it
                }
            },
            commitments = w.commitments.map { if (it.seriesId in ids) it.copy(returnProcessed = true) else it },
        )
    }

    fun markReturnsSeen(w: Wallet): Wallet =
        w.copy(commitments = w.commitments.map { if (it.returnProcessed) it.copy(returnSeen = true) else it })
}
