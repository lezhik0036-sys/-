package app.mama.trustedexit

/**
 * The main-thread-safe BRICK lock-start preflight for the real server backend.
 *
 * The lock-start gate ([TrustedExit.ready], called synchronously from UI handlers
 * on the main thread) must not do network. So the authoritative server readiness
 * (`GET /v1/setup/series/{id}/readiness`, which requires an active series + a
 * provider-VERIFIED contact + a registered period) is fetched OFF the main thread
 * and its result recorded here; the gate then reads only this cached value.
 *
 * Fail-closed by construction:
 *   - the gate is `ready` ONLY on a recent `ready == true` observation;
 *   - an observation older than [MAX_AGE_MS] is treated as unknown (not ready),
 *     so a stale "ready" can never let a lock start minutes later;
 *   - a never-recorded or cleared gate is not ready;
 *   - a clock that went backwards (negative age) is not ready.
 *
 * This deliberately refuses to start a strict physical lock merely because
 * `/activate` returned active or a period reads registered — only a fresh,
 * server-confirmed readiness opens the gate (owner P0 requirement).
 */
object TrustedExitReadinessGate {
    /** A server readiness observation older than this is treated as unknown. */
    const val MAX_AGE_MS = 2 * 60_000L

    @Volatile private var lastReady: Boolean = false
    @Volatile private var lastObservedAtMs: Long = 0L
    @Volatile private var everRecorded: Boolean = false
    // Monotonic invalidation generation: an in-flight server response from a
    // previous series/period must never reinstate its old readiness.
    @Volatile private var generation: Long = 0L

    /**
     * Begin an asynchronous refresh and invalidate the previous observation.
     * Call before the HTTP request. A later context change calls [clear] and
     * invalidates this ticket, so the stale reply is silently discarded.
     */
    @Synchronized
    fun beginRefresh(): Long {
        generation++
        lastReady = false
        everRecorded = false
        return generation
    }

    /** Commit a server response only if it belongs to the current refresh. */
    @Synchronized
    fun recordIfCurrent(ticket: Long, ready: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (ticket != generation) return false
        record(ready, nowMs)
        return true
    }

    /** Record a fresh server readiness observation. Call OFF the main thread. */
    @Synchronized
    fun record(ready: Boolean, nowMs: Long = System.currentTimeMillis()) {
        lastReady = ready
        lastObservedAtMs = nowMs
        everRecorded = true
    }

    /** Forget any observation (e.g. on sign-out or when the series ends). */
    @Synchronized
    fun clear() {
        generation++
        lastReady = false
        lastObservedAtMs = 0L
        everRecorded = false
    }

    /**
     * Main-thread-safe: true ONLY when the last observation was `ready == true`
     * and is still fresh. Pure given [nowMs], so the policy is unit-testable.
     */
    fun ready(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!everRecorded || !lastReady) return false
        val age = nowMs - lastObservedAtMs
        return age in 0..MAX_AGE_MS
    }
}
