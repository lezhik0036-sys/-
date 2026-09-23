package app.mama.core

import java.time.Instant
import java.time.ZoneId

/**
 * What the lock is for. The engine treats every mode the same way; the mode
 * only drives texts, statistics and (later) series/cycles.
 */
enum class SessionMode { SLEEP, WORK, STUDY, KIDS, DETOX }

/** A concrete, absolute lock interval. Instants are UTC; [zone] is kept for display. */
data class LockPlan(
    val start: Instant,
    val end: Instant,
    val zone: ZoneId,
    val mode: SessionMode = SessionMode.SLEEP,
) {
    init {
        require(end.isAfter(start)) { "end must be after start" }
    }
}

/**
 * The person who can let the user out early. Frozen into a session when it is
 * created, so it cannot be swapped for the user's own number mid-lock.
 */
data class TrustedContact(val name: String, val phone: String) {
    init {
        require(name.isNotBlank()) { "contact name is blank" }
        require(normalizePhone(phone) == phone) { "phone must be normalized: $phone" }
    }

    companion object {
        /**
         * Returns the phone in `+<digits>` form, or null if it does not look like
         * a real number. Russian `8XXXXXXXXXX` / `7XXXXXXXXXX` become `+7XXXXXXXXXX`.
         */
        fun normalizePhone(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.any { !it.isDigit() && it !in "+-() " }) return null
            val digits = trimmed.filter { it.isDigit() }
            val normalized = when {
                trimmed.startsWith("+") -> "+$digits"
                digits.length == 11 && (digits[0] == '8' || digits[0] == '7') -> "+7" + digits.substring(1)
                else -> return null
            }
            return if (normalized.length - 1 in 10..15) normalized else null
        }
    }
}

/** Why a code is being requested from the trusted contact. */
enum class ExitKind {
    /** End the whole session early. */
    END_SESSION,

    /** Unlock for a short time (see [CorePolicy.emergencyPass]), then lock again. */
    EMERGENCY,
}

/**
 * An outstanding code sent to the trusted contact. Only a salted hash is kept:
 * the plain code exists in memory just long enough to be sent by SMS.
 */
data class CodeChallenge(
    val kind: ExitKind,
    val salt: String,
    val hash: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val attemptsLeft: Int,
)

enum class SessionStatus { SCHEDULED, ACTIVE, FINISHED }

enum class FinishReason {
    /** The planned end time was reached. */
    COMPLETED,

    /** The trusted contact's code ended the session early. */
    EXITED_WITH_CONTACT,

    /** The user changed their mind before the lock started. */
    CANCELLED_BEFORE_START,
}

data class Session(
    val id: String,
    val plan: LockPlan,
    val contact: TrustedContact,
    val status: SessionStatus,
    val finishReason: FinishReason? = null,
    val finishedAt: Instant? = null,
    /** While set and in the future, the phone is temporarily unlocked. */
    val emergencyUntil: Instant? = null,
    val emergencyPassesUsed: Int = 0,
    val challenge: CodeChallenge? = null,
    /** After [CorePolicy.maxAttempts] wrong codes, no new code until this moment. */
    val codeCooldownUntil: Instant? = null,
)

/** What the phone should look like right now. */
sealed interface LockState {
    /** No session or it has finished: phone is free. */
    data object Free : LockState

    /** A session is planned; phone is free until [startsAt]. */
    data class Waiting(val startsAt: Instant, val endsAt: Instant) : LockState

    /** Strict lock until [endsAt]. */
    data class Locked(val endsAt: Instant) : LockState

    /** Temporary emergency unlock; the lock returns at [until] (if the session still runs). */
    data class EmergencyPass(val until: Instant, val endsAt: Instant) : LockState

    /** Next moment the state can change on its own (for alarms), or null. */
    val nextChangeAt: Instant?
        get() = when (this) {
            Free -> null
            is Waiting -> startsAt
            is Locked -> endsAt
            is EmergencyPass -> minOf(until, endsAt)
        }

    val isLocked: Boolean get() = this is Locked
}
