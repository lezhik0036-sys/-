package app.mama.core

import java.time.Duration

/** Tunable rules of MAMA Core. Defaults are the v0.1 product decisions. */
data class CorePolicy(
    /** Length of the code the trusted contact receives. */
    val codeLength: Int = 5,
    /** Wrong entries allowed per code. */
    val maxAttempts: Int = 3,
    /** How long a sent code stays valid. */
    val codeTtl: Duration = Duration.ofMinutes(15),
    /** After all attempts are spent, how long until a new code may be requested. */
    val cooldownAfterFailures: Duration = Duration.ofMinutes(30),
    /** Length of one emergency unlock. */
    val emergencyPass: Duration = Duration.ofMinutes(15),
    /** Emergency unlocks allowed per session. */
    val maxEmergencyPasses: Int = 3,
    /** Shortest lock that can be scheduled. */
    val minDuration: Duration = Duration.ofMinutes(15),
    /** Longest lock that can be scheduled. */
    val maxDuration: Duration = Duration.ofHours(24),
) {
    init {
        require(codeLength in 4..9)
        require(maxAttempts >= 1)
        require(!minDuration.isNegative && minDuration < maxDuration)
    }
}
