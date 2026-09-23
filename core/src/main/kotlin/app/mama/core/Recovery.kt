package app.mama.core

import java.time.Instant

/** Result of bringing persisted state up to date. */
data class Reconciliation(
    /** Updated state to persist. */
    val snapshot: Snapshot,
    /** What the phone must look like now. */
    val state: LockState,
    /** The time all decisions were made with. */
    val trustedNow: Instant,
)

/**
 * Reboot Recovery and every other "wake up and figure out where we are" path
 * (alarm, boot, app update, clock/zone change, service restart) goes through
 * [reconcile]. It never loosens a lock: if a session was ACTIVE before a
 * reboot and its end has not been reached in trusted time, it is ACTIVE again.
 */
class Recovery(private val engine: LockEngine) {

    fun reconcile(snapshot: Snapshot, wallNow: Instant, elapsedNowMs: Long, bootId: String): Reconciliation {
        val running = snapshot.session?.status.let { it == SessionStatus.SCHEDULED || it == SessionStatus.ACTIVE }
        if (!running) {
            // Nothing to protect: the system clock is as good as any.
            val fresh = Snapshot(
                session = snapshot.session,
                anchor = ClockGuard.reanchor(wallNow, elapsedNowMs, bootId),
                lastTrusted = wallNow,
            )
            return Reconciliation(fresh, LockState.Free, wallNow)
        }
        val trusted = ClockGuard.trustedNow(snapshot.anchor, snapshot.lastTrusted, wallNow, elapsedNowMs, bootId)
        val sameBoot = snapshot.anchor?.let { it.bootId == bootId && elapsedNowMs >= it.elapsedMs } == true
        val anchor = if (sameBoot) snapshot.anchor else ClockGuard.reanchor(trusted, elapsedNowMs, bootId)
        val session = snapshot.session?.let { engine.advance(it, trusted) }
        val lastTrusted = snapshot.lastTrusted?.let { maxOf(it, trusted) } ?: trusted
        return Reconciliation(
            snapshot = Snapshot(session, anchor, lastTrusted),
            state = engine.stateOf(session, trusted),
            trustedNow = trusted,
        )
    }
}
