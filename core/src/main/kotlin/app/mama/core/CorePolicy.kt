package app.mama.core

import java.time.Duration

/** Tunable rules of MAMA Core. Defaults are the v0.1 product decisions. */
data class CorePolicy(
    /** Length of the code the trusted contact receives. */
    val codeLength: Int = 5,
    /** Wrong entries allowed per code. */
    val maxAttempts: Int = 3,
    /**
     * How long a sent code stays valid.
     * TODO(product): placeholder, not an approved product rule; configurable.
     */
    val codeTtl: Duration = Duration.ofMinutes(15),
    /**
     * After all attempts are spent, how long until a new code may be requested.
     * TODO(product): placeholder, not an approved product rule; configurable.
     */
    val cooldownAfterFailures: Duration = Duration.ofMinutes(30),
    /**
     * Length of one emergency unlock. Not offered in the UI: there is no
     * approved rule for a temporary unlock during an active lock.
     */
    val emergencyPass: Duration = Duration.ofMinutes(15),
    /** Emergency unlocks allowed per session. */
    val maxEmergencyPasses: Int = 3,
    /** Shortest lock that can be scheduled. */
    val minDuration: Duration = Duration.ofMinutes(15),
    /** Longest lock that can be scheduled. */
    val maxDuration: Duration = Duration.ofHours(24),
    /**
     * A hard cap on how long ANY single lock may actually last, applied when a
     * session is created: the lock's end is clamped to start + this. Null means
     * no cap (production). TEST/DEBUG builds set it (15 minutes) so a test
     * device can never be locked for the full chosen time — the safety net
     * behind the whole test build (see the app's TEST_MODE).
     */
    val maxLockDuration: Duration? = null,
    /**
     * ACTIVATING is a short technical state: if strict protection is not
     * confirmed within this time after the first lock started, the series
     * goes to NEEDS_ATTENTION. The clock waits while the phone has not been
     * unlocked since a reboot (the guard cannot run before that).
     * TODO(product): tune after tests on real phones; configurable.
     */
    val activationTimeout: Duration = Duration.ofMinutes(2),
) {
    init {
        require(codeLength in 4..9)
        require(maxAttempts >= 1)
        require(!minDuration.isNegative && minDuration < maxDuration)
    }
}
