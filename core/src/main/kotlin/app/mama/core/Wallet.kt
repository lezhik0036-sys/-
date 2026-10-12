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
 * is reserved from the available balance when a standard series starts,
 * released in full when the series is completed, and forfeited when the
 * series fails; RETURN_PERCENT of it then comes back to the available
 * balance after RETURN_DELAY, exactly once.
 *
 * All amounts are in kopecks. Every balance change is also a ledger entry.
 */

enum class LedgerType {
    BALANCE_TOPUP_TEST,
    COMMITMENT_RESERVED,
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

enum class CommitmentState { RESERVED, RELEASED, FORFEITED }

/** "Цена слова" of one standard series. Never carried over to another series. */
data class Commitment(
    val seriesId: String,
    val amountKop: Long,
    val reservedAt: Instant,
    val state: CommitmentState = CommitmentState.RESERVED,
    /** Success or failure moment (the failure timestamp when FORFEITED). */
    val settledAt: Instant? = null,
    val returnKop: Long = 0,
    val returnDueAt: Instant? = null,
    /** The partial return was credited; guards against crediting twice. */
    val returnProcessed: Boolean = false,
    /** The user has seen the "part of the amount came back" message. */
    val returnSeen: Boolean = false,
)

data class Wallet(
    val availableKop: Long = 0,
    val reservedKop: Long = 0,
    val entries: List<LedgerEntry> = emptyList(),
    val commitments: List<Commitment> = emptyList(),
) {
    fun commitmentFor(seriesId: String?): Commitment? = commitments.firstOrNull { it.seriesId == seriesId }

    /** Earliest pending partial return, for the wake-up alarm. */
    val nextReturnDue: Instant?
        get() = commitments.filter { it.state == CommitmentState.FORFEITED && !it.returnProcessed }.mapNotNull { it.returnDueAt }.minOrNull()

    /** Credited returns the user has not seen yet. */
    val unseenReturns: List<Commitment>
        get() = commitments.filter { it.returnProcessed && !it.returnSeen }
}

object WalletEngine {
    const val RETURN_PERCENT = 50
    val RETURN_DELAY: Duration = Duration.ofHours(24)

    /** Pilot limits for "Цена слова". TODO(product): confirm. */
    const val MIN_COMMITMENT_KOP = 100_00L
    const val MAX_COMMITMENT_KOP = 100_000_00L

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

    /** Moves "Цена слова" from available to reserved for one series. */
    fun reserve(w: Wallet, seriesId: String, amountKop: Long, now: Instant): Result {
        if (amountKop < MIN_COMMITMENT_KOP || amountKop > MAX_COMMITMENT_KOP) return Result.Invalid("amount")
        if (w.commitmentFor(seriesId) != null) return Result.Invalid("already reserved")
        if (w.availableKop < amountKop) return Result.Insufficient(w.availableKop)
        return Result.Ok(
            w.copy(
                availableKop = w.availableKop - amountKop,
                reservedKop = w.reservedKop + amountKop,
                entries = w.entries + LedgerEntry("reserve-$seriesId", now, amountKop, LedgerType.COMMITMENT_RESERVED, seriesId = seriesId),
                commitments = w.commitments + Commitment(seriesId, amountKop, now),
            ),
        )
    }

    /** Spends available balance on an approved MAMA product. */
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
     * Settles the commitment of a finished series once: completed → released
     * in full; failed → forfeited, with the partial return scheduled for
     * failure time + [RETURN_DELAY]. Active series and already settled
     * commitments are left as they are.
     */
    fun settle(w: Wallet, series: Series, now: Instant): Wallet {
        val c = w.commitmentFor(series.id) ?: return w
        if (c.state != CommitmentState.RESERVED) return w
        return when (series.status) {
            SeriesStatus.ACTIVE -> w
            SeriesStatus.COMPLETED -> release(w, c, series.endedAt ?: now)
            SeriesStatus.BROKEN -> forfeit(w, c, series.endedAt ?: now)
        }
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
