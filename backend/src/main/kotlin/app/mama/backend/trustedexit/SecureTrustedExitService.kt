package app.mama.backend.trustedexit

import app.mama.backend.auth.Grant
import app.mama.backend.auth.GrantVerifier
import java.util.UUID

/**
 * The server-authoritative Trusted Exit flow, fail-closed and grant-gated.
 *
 * Identity and bindings come ONLY from the verified [GrantVerifier] grant, never
 * from client JSON. Durable state (challenge, attempts, cooldown, one-time JTI,
 * rate counters) lives in PostgreSQL via [OtpRepository]. The plaintext OTP is
 * generated once, sent once, and never stored or logged; only a peppered/salted
 * HMAC is persisted. The SMS destination is resolved from a trusted server
 * source ([ContactDestinationResolver]); if it cannot be, the flow fails closed.
 */
class SecureTrustedExitService(
    private val verifier: GrantVerifier,
    private val repo: OtpRepository,
    private val hasher: OtpHasher,
    private val sms: app.mama.backend.trustedexit.SmsProvider,
    private val destinations: ContactDestinationResolver,
    private val limits: RateLimits = RateLimits(),
    private val ttlMs: Long = 15 * 60_000L,
    private val cooldownMs: Long = 30 * 60_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    fun request(bearer: String?, clientIp: String): RequestOutcome {
        val grant = (verifier.verify(bearer) as? GrantVerifier.Result.Valid)?.grant
            ?: return RequestOutcome.Unauthorized

        if (repo.isJtiRedeemed(grant.jti)) return RequestOutcome.Rejected("replay")

        repo.findByJti(grant.jti)?.let { return reuseOrReject(it) }

        val code = Otp.generate()
        val salt = Otp.salt()
        val now = nowMs()
        val challenge = StoredChallenge(
            id = "tec-" + UUID.randomUUID(),
            jti = grant.jti,
            userId = grant.sub, seriesId = grant.seriesId, periodId = grant.periodId,
            deviceId = grant.deviceId, contactId = grant.contactId,
            salt = salt, codeHash = hasher.hash(salt, code),
            attemptsLeft = 3, status = OtpStatus.PENDING_SEND.name,
            issuedAt = now, expiresAt = now + ttlMs, cooldownUntil = null, smsRef = null,
        )

        if (!repo.insertIfAbsent(challenge)) {
            // Lost the create race for this jti: reuse the winner's challenge, never a second SMS.
            return repo.findByJti(grant.jti)?.let { reuseOrReject(it) } ?: RequestOutcome.Failed("race")
        }

        val rate = repo.reserve(rateKeys(grant, clientIp, now))
        if (rate is RateDecision.Denied) {
            repo.abortIfUnsent(challenge.id)
            return RequestOutcome.RateLimited
        }

        val destination = destinations.resolve(grant)
        if (destination.isNullOrBlank()) {
            repo.abortIfUnsent(challenge.id)
            return RequestOutcome.DestinationUnavailable
        }

        if (!repo.claimForSend(challenge.id)) {
            // Another worker is already sending for this challenge; reuse, do not double-send.
            return repo.byId(challenge.id)?.let { reuseOrReject(it) } ?: RequestOutcome.Failed("claim")
        }

        return when (val r = runCatching { sms.send(destination, code) }.getOrNull()) {
            is SmsResult.Accepted -> {
                repo.markSent(challenge.id, r.smsId)
                created(challenge.id, now, now + ttlMs, 3, "SMS_ACCEPTED")
            }
            is SmsResult.Failed -> {
                repo.markSendFailed(challenge.id)
                RequestOutcome.Failed("delivery failed")
            }
            null -> // provider threw/timed out: stays SENDING, no immediate second SMS, status unknown
                created(challenge.id, now, now + ttlMs, 3, "UNKNOWN")
        }
    }

    fun verify(bearer: String?, challengeId: String, enteredCode: String): VerifyOutcome {
        val grant = (verifier.verify(bearer) as? GrantVerifier.Result.Valid)?.grant
            ?: return VerifyOutcome.Unauthorized
        val ch = repo.byId(challengeId) ?: return VerifyOutcome.Unknown
        if (!ownedBy(ch, grant)) return VerifyOutcome.Forbidden
        return repo.verifyAndConsume(challengeId, enteredCode, nowMs(), cooldownMs)
    }

    private fun reuseOrReject(existing: StoredChallenge): RequestOutcome {
        val alive = existing.status in ALIVE && nowMs() < existing.expiresAt
        return if (alive) created(existing.id, existing.issuedAt, existing.expiresAt, existing.attemptsLeft, deliveryLabel(existing.status))
        else RequestOutcome.Rejected("spent")
    }

    private fun created(id: String, issued: Long, expires: Long, attempts: Int, delivery: String) =
        RequestOutcome.Created(id, issued, expires, attempts, delivery)

    private fun deliveryLabel(status: String): String = when (status) {
        OtpStatus.SENT.name -> "SMS_ACCEPTED"
        OtpStatus.SEND_FAILED.name -> "SMS_FAILED"
        else -> "UNKNOWN"
    }

    private fun ownedBy(ch: StoredChallenge, g: Grant): Boolean =
        ch.userId == g.sub && ch.seriesId == g.seriesId && ch.periodId == g.periodId &&
            ch.deviceId == g.deviceId && ch.contactId == g.contactId

    private fun rateKeys(g: Grant, ip: String, now: Long): List<RateScope> {
        val hour = 3_600_000L
        val day = 86_400_000L
        fun win(w: Long) = now - (now % w)
        return listOf(
            RateScope("user:${g.sub}:h", win(hour), limits.userPerHour),
            RateScope("device:${g.deviceId}:h", win(hour), limits.devicePerHour),
            RateScope("contact:${g.contactId}:d", win(day), limits.contactPerDay),
            RateScope("ip:$ip:h", win(hour), limits.ipPerHour),
            RateScope("global:d", win(day), limits.globalPerDay),
        )
    }

    private companion object {
        val ALIVE = setOf(OtpStatus.PENDING_SEND.name, OtpStatus.SENDING.name, OtpStatus.SENT.name)
    }
}
