package app.mama.lock

/**
 * Guards against a stale Trusted Exit network response changing the wrong state.
 *
 * A background request captures a [Snapshot] (an epoch counter + the session id)
 * when it is dispatched. When its result comes back, it is applied only if
 * [isCurrent]: the lock surface is still attached, the epoch has not advanced
 * (the overlay was not hidden/destroyed in the meantime) and the session is the
 * same one. [bump] advances the epoch, invalidating everything in flight; the
 * overlay calls it when it is hidden, destroyed, or shown for a different session.
 *
 * Kept as a tiny pure class so these races are unit-tested without an overlay.
 */
class StaleResultGuard {
    var epoch: Int = 0
        private set

    fun bump() {
        epoch++
    }

    fun snapshot(sessionId: String?): Snapshot = Snapshot(epoch, sessionId)

    /**
     * True only when it is still safe to apply a result taken at [snapshot]:
     * the surface is present, the epoch is unchanged and the session matches.
     */
    fun isCurrent(snapshot: Snapshot, currentSessionId: String?, surfacePresent: Boolean): Boolean =
        surfacePresent &&
            snapshot.epoch == epoch &&
            snapshot.sessionId != null &&
            snapshot.sessionId == currentSessionId

    data class Snapshot(val epoch: Int, val sessionId: String?)
}
