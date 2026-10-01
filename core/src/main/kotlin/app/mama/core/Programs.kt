package app.mama.core

import java.time.Duration
import java.time.Instant

/** Where an entitlement came from. Nothing here is ever needed to unlock the phone. */
enum class GrantSource {
    /** Paid through a real store (not available in test builds). */
    PAID,

    /** Test build only: granted for testing, no payment was made. */
    TEST_NO_PAYMENT,

    /** Earned: a FLEX package completed 7 of 7. */
    REWARD,
}

/**
 * Standard series (3/5/7) after a failure. The phone is never kept locked:
 * this only decides when another series can start.
 * - Immediately: by paying the Restart price of the failed series.
 * - For free: [FREE_START_AFTER] after the failure date.
 */
object StandardRules {
    val FREE_START_AFTER: Duration = Duration.ofDays(30)

    fun freeStartAt(e: Entitlements): Instant? = e.standardFailedAt?.plus(FREE_START_AFTER)

    fun canStartFree(e: Entitlements, now: Instant): Boolean = freeStartAt(e)?.let { !now.isBefore(it) } ?: true

    /** Whole calendar days until a free start (rounded up), 0 if available now. */
    fun daysUntilFreeStart(e: Entitlements, now: Instant): Long {
        val at = freeStartAt(e) ?: return 0
        if (!now.isBefore(at)) return 0
        val ms = Duration.between(now, at).toMillis()
        return (ms + DAY_MS - 1) / DAY_MS
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}

/**
 * A MAMA FLEX package: [PERIODS] independently started lock periods on any
 * days within [VALIDITY] of activation. One-off, never auto-renewed.
 *
 * A period is consumed when it starts. Completing it counts as successful;
 * leaving it early with the contact's code burns only that period.
 */
data class FlexPackage(
    val id: String,
    val activatedAt: Instant,
    val source: GrantSource,
    /** Periods started (consumed), successful or not. */
    val used: Int = 0,
    val successful: Int = 0,
    /** Session planned for the current/next FLEX period, if any. */
    val currentSessionId: String? = null,
    /** Session already counted as consumed / settled, so nothing is counted twice. */
    val consumedSessionId: String? = null,
    val settledSessionId: String? = null,
    /** Set when the package is over (all periods used or expired). */
    val endedAt: Instant? = null,
    /** When the last period was left early, if ever. */
    val lastBurntAt: Instant? = null,
) {
    val expiresAt: Instant get() = activatedAt + VALIDITY
    val remaining: Int get() = PERIODS - used
    /** A period that started and has not finished yet. */
    val inProgress: Boolean get() = currentSessionId != null && consumedSessionId == currentSessionId

    /** Periods that started and ended early (burnt). */
    val failed: Int get() = used - successful - (if (inProgress) 1 else 0)

    /** Only a perfect 7 of 7 earns the next package for free. */
    val rewardStillPossible: Boolean get() = failed == 0
    val earnedReward: Boolean get() = successful >= PERIODS
    val over: Boolean get() = endedAt != null

    companion object {
        const val PERIODS = 7
        const val PRICE_RUB = 399
        val VALIDITY: Duration = Duration.ofDays(30)
    }
}

/** What the user owns or is waiting for, apart from the running series/session. */
data class Entitlements(
    /** Last failure of a standard series (starts the 30-day wait for a free start). */
    val standardFailedAt: Instant? = null,
    val flex: FlexPackage? = null,
    /** Earned free FLEX packages not yet activated. */
    val freeFlexCredits: Int = 0,
)

/** Pure FLEX rules on top of [LockEngine]. */
class FlexEngine(private val engine: LockEngine, private val policy: CorePolicy = CorePolicy()) {

    fun activate(id: String, source: GrantSource, now: Instant) = FlexPackage(id, now, source)

    fun canStartPeriod(p: FlexPackage, now: Instant): Boolean =
        !p.over && p.remaining > 0 && now.isBefore(p.expiresAt) && p.currentSessionId == null

    /** Plans one FLEX period: the next occurrence of [window] (or right now, if inside it). */
    fun planPeriod(
        p: FlexPackage,
        window: DailyWindow,
        contact: TrustedContact,
        mode: SessionMode,
        sessionId: String,
        now: Instant,
    ): Pair<FlexPackage, Session>? {
        if (!canStartPeriod(p, now)) return null
        val plan = window.nextPlan(now, mode, policy.minDuration)
        if (!maxOf(plan.start, now).isBefore(p.expiresAt)) return null
        val created = engine.create(sessionId, plan, contact, now) as? LockEngine.CreateResult.Created ?: return null
        return p.copy(currentSessionId = sessionId) to created.session
    }

    data class Update(val pkg: FlexPackage, val rewardEarnedNow: Boolean)

    /**
     * Applies the current session to the package: consumed when it starts,
     * successful when completed, burnt when left early, freed when cancelled
     * before start. Ends the package when all periods are used or it expired.
     */
    fun afterSession(p: FlexPackage, session: Session?, now: Instant): Update {
        if (p.over) return Update(p, false)
        var pkg = p
        var reward = false
        val mine = session?.takeIf { it.id == p.currentSessionId }
        if (mine != null) {
            val started = mine.status == SessionStatus.ACTIVE ||
                (mine.status == SessionStatus.FINISHED && mine.finishReason != FinishReason.CANCELLED_BEFORE_START)
            if (started && pkg.consumedSessionId != mine.id) {
                pkg = pkg.copy(used = pkg.used + 1, consumedSessionId = mine.id)
            }
            if (mine.status == SessionStatus.FINISHED && pkg.settledSessionId != mine.id) {
                pkg = when (mine.finishReason) {
                    FinishReason.COMPLETED -> {
                        val ok = pkg.successful + 1
                        reward = ok >= FlexPackage.PERIODS && !pkg.earnedReward
                        pkg.copy(successful = ok, settledSessionId = mine.id, currentSessionId = null)
                    }
                    FinishReason.CANCELLED_BEFORE_START ->
                        pkg.copy(settledSessionId = mine.id, currentSessionId = null)
                    else -> // left early: the period is burnt
                        pkg.copy(settledSessionId = mine.id, currentSessionId = null, lastBurntAt = now)
                }
            }
        }
        val running = pkg.currentSessionId != null
        if (!running && (pkg.remaining <= 0 || !now.isBefore(pkg.expiresAt))) {
            pkg = pkg.copy(endedAt = now)
        }
        return Update(pkg, reward)
    }
}
