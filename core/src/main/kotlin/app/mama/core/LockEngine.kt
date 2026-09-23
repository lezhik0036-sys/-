package app.mama.core

import java.time.Duration
import java.time.Instant

/**
 * The MAMA Core state machine. Pure: every call takes the current session and
 * the (trusted) current time and returns a new session, so the same logic runs
 * after a reboot, an alarm, a clock change or a process restart.
 *
 * Lifecycle: SCHEDULED → ACTIVE → FINISHED.
 * - Before start the user may cancel freely.
 * - Once ACTIVE, the only ways out are the end time or a code from the
 *   trusted contact (end the session, or a short emergency pass).
 */
class LockEngine(
    private val policy: CorePolicy = CorePolicy(),
    private val codes: CodeSource = SecureCodeSource(),
) {

    sealed interface CreateResult {
        data class Created(val session: Session) : CreateResult
        data class Rejected(val reason: CreateError) : CreateResult
    }

    enum class CreateError { ALREADY_ENDED, TOO_SHORT, TOO_LONG }

    /** Creates a session. A plan whose start is already past starts locked right away. */
    fun create(id: String, plan: LockPlan, contact: TrustedContact, now: Instant): CreateResult {
        if (!plan.end.isAfter(now)) return CreateResult.Rejected(CreateError.ALREADY_ENDED)
        val effectiveStart = maxOf(plan.start, now)
        val length = Duration.between(effectiveStart, plan.end)
        if (length < policy.minDuration) return CreateResult.Rejected(CreateError.TOO_SHORT)
        if (Duration.between(plan.start, plan.end) > policy.maxDuration) {
            return CreateResult.Rejected(CreateError.TOO_LONG)
        }
        val session = Session(id = id, plan = plan, contact = contact, status = SessionStatus.SCHEDULED)
        return CreateResult.Created(advance(session, now))
    }

    /** Applies everything that should have happened by [now]: start, end, pass/code expiry. */
    fun advance(session: Session, now: Instant): Session {
        var s = session
        if (s.status == SessionStatus.SCHEDULED && !now.isBefore(s.plan.start)) {
            s = s.copy(status = SessionStatus.ACTIVE)
        }
        if (s.status == SessionStatus.ACTIVE && !now.isBefore(s.plan.end)) {
            s = finish(s, FinishReason.COMPLETED, s.plan.end)
        }
        if (s.emergencyUntil != null && !now.isBefore(s.emergencyUntil)) {
            s = s.copy(emergencyUntil = null)
        }
        if (s.challenge != null && !now.isBefore(s.challenge!!.expiresAt)) {
            s = s.copy(challenge = null)
        }
        if (s.codeCooldownUntil != null && !now.isBefore(s.codeCooldownUntil)) {
            s = s.copy(codeCooldownUntil = null)
        }
        return s
    }

    fun stateOf(session: Session?, now: Instant): LockState {
        if (session == null) return LockState.Free
        val s = advance(session, now)
        return when (s.status) {
            SessionStatus.FINISHED -> LockState.Free
            SessionStatus.SCHEDULED -> LockState.Waiting(s.plan.start, s.plan.end)
            SessionStatus.ACTIVE -> {
                val pass = s.emergencyUntil
                if (pass != null) LockState.EmergencyPass(pass, s.plan.end) else LockState.Locked(s.plan.end)
            }
        }
    }

    sealed interface CancelResult {
        data class Cancelled(val session: Session) : CancelResult

        /** The lock has already started; only the trusted contact can end it. */
        data object NotAllowed : CancelResult
    }

    fun cancelBeforeStart(session: Session, now: Instant): CancelResult {
        val s = advance(session, now)
        if (s.status != SessionStatus.SCHEDULED) return CancelResult.NotAllowed
        return CancelResult.Cancelled(finish(s, FinishReason.CANCELLED_BEFORE_START, now))
    }

    sealed interface CodeRequestResult {
        /**
         * A new code was generated. [plainCode] must be sent to [Session.contact]
         * and then forgotten: it is never stored.
         */
        data class Issued(val session: Session, val plainCode: String) : CodeRequestResult

        data class Rejected(val reason: CodeRequestError, val retryAt: Instant? = null) : CodeRequestResult
    }

    enum class CodeRequestError {
        NOT_LOCKED,
        ALREADY_PENDING,
        COOLDOWN,
        EMERGENCY_LIMIT_REACHED,
    }

    fun requestCode(session: Session, kind: ExitKind, now: Instant): CodeRequestResult {
        val s = advance(session, now)
        if (stateOf(s, now) !is LockState.Locked) {
            return CodeRequestResult.Rejected(CodeRequestError.NOT_LOCKED)
        }
        s.codeCooldownUntil?.let {
            return CodeRequestResult.Rejected(CodeRequestError.COOLDOWN, it)
        }
        s.challenge?.let {
            return CodeRequestResult.Rejected(CodeRequestError.ALREADY_PENDING, it.expiresAt)
        }
        if (kind == ExitKind.EMERGENCY && s.emergencyPassesUsed >= policy.maxEmergencyPasses) {
            return CodeRequestResult.Rejected(CodeRequestError.EMERGENCY_LIMIT_REACHED)
        }
        val code = codes.nextCode(policy.codeLength)
        val salt = codes.nextSalt()
        val challenge = CodeChallenge(
            kind = kind,
            salt = salt,
            hash = CodeHasher.hash(salt, code),
            issuedAt = now,
            expiresAt = now + policy.codeTtl,
            attemptsLeft = policy.maxAttempts,
        )
        return CodeRequestResult.Issued(s.copy(challenge = challenge), code)
    }

    sealed interface CodeResult {
        val session: Session

        /** Correct code. For [ExitKind.END_SESSION] the session is finished. */
        data class Accepted(override val session: Session, val kind: ExitKind) : CodeResult

        data class Wrong(override val session: Session, val attemptsLeft: Int) : CodeResult

        /** Last attempt failed: the code is burned and a cooldown starts. */
        data class Exhausted(override val session: Session, val retryAt: Instant) : CodeResult

        /** Nothing to check against (never requested, or expired). */
        data class NoActiveCode(override val session: Session) : CodeResult
    }

    fun submitCode(session: Session, candidate: String, now: Instant): CodeResult {
        val s = advance(session, now)
        val challenge = s.challenge
        if (challenge == null || stateOf(s, now) !is LockState.Locked) {
            return CodeResult.NoActiveCode(s)
        }
        val normalized = candidate.trim()
        val ok = normalized.length == policy.codeLength &&
            normalized.all { it.isDigit() } &&
            CodeHasher.matches(challenge.salt, challenge.hash, normalized)
        if (ok) {
            val cleared = s.copy(challenge = null)
            val next = when (challenge.kind) {
                ExitKind.END_SESSION -> finish(cleared, FinishReason.EXITED_WITH_CONTACT, now)
                ExitKind.EMERGENCY -> cleared.copy(
                    emergencyUntil = minOf(now + policy.emergencyPass, s.plan.end),
                    emergencyPassesUsed = s.emergencyPassesUsed + 1,
                )
            }
            return CodeResult.Accepted(next, challenge.kind)
        }
        val left = challenge.attemptsLeft - 1
        if (left <= 0) {
            val retryAt = now + policy.cooldownAfterFailures
            return CodeResult.Exhausted(s.copy(challenge = null, codeCooldownUntil = retryAt), retryAt)
        }
        return CodeResult.Wrong(s.copy(challenge = challenge.copy(attemptsLeft = left)), left)
    }

    private fun finish(s: Session, reason: FinishReason, at: Instant) = s.copy(
        status = SessionStatus.FINISHED,
        finishReason = reason,
        finishedAt = at,
        emergencyUntil = null,
        challenge = null,
        codeCooldownUntil = null,
    )
}
