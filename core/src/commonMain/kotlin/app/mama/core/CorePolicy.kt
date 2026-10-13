package app.mama.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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
    val codeTtl: Duration = 15.minutes,
    /**
     * After all attempts are spent, how long until a new code may be requested.
     * TODO(product): placeholder, not an approved product rule; configurable.
     */
    val cooldownAfterFailures: Duration = 30.minutes,
    /**
     * Length of one emergency unlock. Not offered in the UI: there is no
     * approved rule for a temporary unlock during an active lock.
     */
    val emergencyPass: Duration = 15.minutes,
    /** Emergency unlocks allowed per session. */
    val maxEmergencyPasses: Int = 3,
    /** Shortest lock that can be scheduled. */
    val minDuration: Duration = 15.minutes,
    /** Longest lock that can be scheduled. */
    val maxDuration: Duration = 24.hours,
    /**
     * ACTIVATING is a short technical state: if strict protection is not
     * confirmed within this time after the first lock started, the series
     * goes to NEEDS_ATTENTION. The clock waits while the phone has not been
     * unlocked since a reboot (the guard cannot run before that).
     * TODO(product): tune after tests on real phones; configurable.
     */
    val activationTimeout: Duration = 2.minutes,
) {
    init {
        require(codeLength in 4..9)
        require(maxAttempts >= 1)
        require(!minDuration.isNegative() && minDuration < maxDuration)
    }
}
