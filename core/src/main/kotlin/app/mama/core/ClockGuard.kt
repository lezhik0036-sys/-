package app.mama.core

import java.time.Duration
import java.time.Instant

/**
 * A point where wall-clock time was trusted, tied to the monotonic
 * since-boot clock (Android `SystemClock.elapsedRealtime()`).
 */
data class ClockAnchor(
    val wall: Instant,
    val elapsedMs: Long,
    /** Identifies the boot (Android `Settings.Global.BOOT_COUNT`). */
    val bootId: String,
)

/**
 * Protects the lock from "just move the clock forward".
 *
 * Within one boot the monotonic clock cannot be changed by the user, so time is
 * measured from the anchor and edits of the system clock are ignored.
 * After a reboot the monotonic clock restarts; then time may never go below
 * the last trusted moment. (A forward jump made while the phone was off cannot
 * be detected offline — see docs/CORE.md, "Known limitations".)
 */
object ClockGuard {

    fun trustedNow(
        anchor: ClockAnchor?,
        lastTrusted: Instant?,
        wallNow: Instant,
        elapsedNowMs: Long,
        bootId: String,
    ): Instant {
        if (anchor != null && anchor.bootId == bootId && elapsedNowMs >= anchor.elapsedMs) {
            return anchor.wall + Duration.ofMillis(elapsedNowMs - anchor.elapsedMs)
        }
        return if (lastTrusted != null) maxOf(wallNow, lastTrusted) else wallNow
    }

    /** A fresh anchor for the current boot, pinned to [trustedNow]. */
    fun reanchor(trustedNow: Instant, elapsedNowMs: Long, bootId: String) =
        ClockAnchor(trustedNow, elapsedNowMs, bootId)

    /**
     * Converts a trusted target moment into the wall-clock time an alarm must be
     * set for, given that the system clock may be skewed relative to trusted time.
     */
    fun wallTimeFor(target: Instant, trustedNow: Instant, wallNow: Instant): Instant =
        wallNow + Duration.between(trustedNow, target)
}
