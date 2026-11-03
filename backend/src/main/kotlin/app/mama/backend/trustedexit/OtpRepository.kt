package app.mama.backend.trustedexit

import app.mama.backend.db.Db
import java.sql.Connection

/** One durable challenge row (never carries the plaintext code). */
data class StoredChallenge(
    val id: String,
    val jti: String,
    val userId: String,
    val seriesId: String,
    val periodId: String,
    val deviceId: String,
    val contactId: String,
    val salt: String,
    val codeHash: String,
    val attemptsLeft: Int,
    val status: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val cooldownUntil: Long?,
    val smsRef: String?,
    /** The verified-OTP receipt, minted once when the challenge is consumed; null until then. */
    val receipt: String? = null,
)

/** A single rate-limit reservation: a durable counter window and its cap. */
data class RateScope(val scope: String, val windowStart: Long, val limit: Int)

sealed interface RateDecision {
    data object Allowed : RateDecision
    data class Denied(val scope: String) : RateDecision
}

/**
 * All Trusted Exit durable SQL — idempotent creation, send-claim lifecycle,
 * one-time transactional verification, the one-time JTI ledger, and durable
 * rate reservations. Atomic under concurrent requests and across restarts.
 */
class OtpRepository(private val db: Db, private val hasher: OtpHasher) {

    fun isJtiRedeemed(jti: String): Boolean = db.open().use { c ->
        c.prepareStatement("SELECT 1 FROM trusted_exit.jti_ledger WHERE jti = ?").use { ps ->
            ps.setString(1, jti); ps.executeQuery().use { it.next() }
        }
    }

    fun findByJti(jti: String): StoredChallenge? = db.open().use { c -> selectByJti(c, jti) }

    fun byId(id: String): StoredChallenge? = db.open().use { c -> selectById(c, id) }

    /** Atomic idempotent create keyed on the grant's jti. Returns true only if this call inserted it. */
    fun insertIfAbsent(ch: StoredChallenge): Boolean = db.open().use { c ->
        c.prepareStatement(
            """
            INSERT INTO trusted_exit.challenge
              (id, jti, user_id, series_id, period_id, device_id, contact_id, salt, code_hash,
               attempts_left, status, issued_at, expires_at, cooldown_until, sms_ref, send_attempts)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
            ON CONFLICT (jti) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, ch.id); ps.setString(2, ch.jti); ps.setString(3, ch.userId)
            ps.setString(4, ch.seriesId); ps.setString(5, ch.periodId); ps.setString(6, ch.deviceId)
            ps.setString(7, ch.contactId); ps.setString(8, ch.salt); ps.setString(9, ch.codeHash)
            ps.setInt(10, ch.attemptsLeft); ps.setString(11, ch.status)
            ps.setLong(12, ch.issuedAt); ps.setLong(13, ch.expiresAt)
            if (ch.cooldownUntil != null) ps.setLong(14, ch.cooldownUntil) else ps.setNull(14, java.sql.Types.BIGINT)
            ps.setString(15, ch.smsRef)
            ps.executeUpdate() == 1
        }
    }

    /** Claims a challenge for sending exactly once (PENDING_SEND → SENDING). Prevents duplicate provider calls. */
    fun claimForSend(id: String): Boolean = db.open().use { c ->
        c.prepareStatement(
            "UPDATE trusted_exit.challenge SET status='SENDING', send_attempts = send_attempts + 1 WHERE id = ? AND status = 'PENDING_SEND'",
        ).use { ps -> ps.setString(1, id); ps.executeUpdate() == 1 }
    }

    fun markSent(id: String, smsRef: String?) = update("UPDATE trusted_exit.challenge SET status='SENT', sms_ref=? WHERE id=?") { ps ->
        ps.setString(1, smsRef); ps.setString(2, id)
    }

    fun markSendFailed(id: String) = update("UPDATE trusted_exit.challenge SET status='SEND_FAILED' WHERE id=?") { ps -> ps.setString(1, id) }

    /** Rolls back a just-inserted-but-not-sent challenge (e.g. rate denied). Only if still PENDING_SEND. */
    fun abortIfUnsent(id: String) = update("DELETE FROM trusted_exit.challenge WHERE id=? AND status='PENDING_SEND'") { ps -> ps.setString(1, id) }

    /**
     * One-time, transactional verification. Row-locked (`FOR UPDATE`): a second
     * concurrent verify of the correct code blocks, then sees CONSUMED → Unknown,
     * so a used code can never pass twice. Wrong code decrements attempts; the
     * third wrong one locks with a cooldown. On success the challenge's own jti
     * is written to the ledger so the grant cannot be replayed to make a new one.
     *
     * A challenge is verifiable ONLY in an explicitly deliverable status
     * (SENT). PENDING_SEND / SENDING / SEND_FAILED are never
     * verifiable — a provider timeout (left SENDING, reported UNKNOWN) or an
     * unsent/failed challenge can never be turned into a VALID, and such an
     * attempt does NOT burn the user's attempts (fail-closed, attempts preserved).
     */
    fun verifyAndConsume(
        id: String,
        enteredCode: String,
        nowMs: Long,
        cooldownMs: Long,
        mintReceipt: (StoredChallenge, Long) -> String? = { _, _ -> null },
    ): VerifyOutcome = db.tx { c ->
        val ch = selectForUpdate(c, id) ?: return@tx VerifyOutcome.Unknown
        when {
            // Idempotent recovery: a consumed challenge returns its ONE stored receipt
            // (same jti) for a lost-response retry, never a new one. With no receipt
            // (signer off) a consumed challenge is a denied replay.
            ch.status == "CONSUMED" -> if (ch.receipt != null) VerifyOutcome.Valid(ch.receipt) else VerifyOutcome.Unknown
            ch.cooldownUntil != null && nowMs < ch.cooldownUntil -> VerifyOutcome.Locked(ch.cooldownUntil)
            nowMs >= ch.expiresAt -> { setStatus(c, id, "EXPIRED"); VerifyOutcome.Expired }
            ch.attemptsLeft <= 0 -> VerifyOutcome.Locked(ch.cooldownUntil ?: nowMs)
            // Fail-closed: only a delivered (SENT) challenge may be verified. No
            // attempt is consumed for a non-deliverable status, and the code is
            // never even compared, so a timed-out/unsent challenge stays unusable.
            ch.status != OtpStatus.SENT.name -> VerifyOutcome.Unknown
            Otp.looksValid(enteredCode) && hasher.matches(ch.salt, enteredCode, ch.codeHash) -> {
                // Mint the receipt and consume atomically: the receipt exists iff the
                // challenge is CONSUMED, so it is impossible to issue two for one OTP.
                val receipt = mintReceipt(ch, nowMs)
                c.prepareStatement("UPDATE trusted_exit.challenge SET status='CONSUMED', receipt=? WHERE id=?").use { ps ->
                    if (receipt != null) ps.setString(1, receipt) else ps.setNull(1, java.sql.Types.VARCHAR)
                    ps.setString(2, id); ps.executeUpdate()
                }
                redeemJti(c, ch.jti, nowMs)
                VerifyOutcome.Valid(receipt)
            }
            else -> {
                val left = ch.attemptsLeft - 1
                if (left <= 0) {
                    val until = nowMs + cooldownMs
                    c.prepareStatement("UPDATE trusted_exit.challenge SET attempts_left=0, status='LOCKED', cooldown_until=? WHERE id=?").use { ps ->
                        ps.setLong(1, until); ps.setString(2, id); ps.executeUpdate()
                    }
                    redeemJti(c, ch.jti, nowMs)
                    VerifyOutcome.Locked(until)
                } else {
                    c.prepareStatement("UPDATE trusted_exit.challenge SET attempts_left=? WHERE id=?").use { ps ->
                        ps.setInt(1, left); ps.setString(2, id); ps.executeUpdate()
                    }
                    VerifyOutcome.Invalid(left)
                }
            }
        }
    }

    /** Reserves all [keys] atomically: if any window is over its cap, nothing is charged (rollback) and Denied. */
    fun reserve(keys: List<RateScope>): RateDecision = db.tx { c ->
        for (k in keys) {
            val count = c.prepareStatement(
                """
                INSERT INTO trusted_exit.rate_counter (scope, window_start, count) VALUES (?,?,1)
                ON CONFLICT (scope, window_start) DO UPDATE SET count = trusted_exit.rate_counter.count + 1
                RETURNING count
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, k.scope); ps.setLong(2, k.windowStart)
                ps.executeQuery().use { if (it.next()) it.getInt(1) else Int.MAX_VALUE }
            }
            if (count > k.limit) {
                c.rollback() // undo every increment in this reservation
                return@tx RateDecision.Denied(k.scope)
            }
        }
        RateDecision.Allowed
    }

    /** Retention cleanup (call periodically): drop finished/expired challenges and stale counters/ledger. */
    fun cleanup(nowMs: Long, keepMs: Long) {
        db.open().use { c ->
            c.prepareStatement("DELETE FROM trusted_exit.challenge WHERE expires_at < ? AND status IN ('CONSUMED','EXPIRED','SEND_FAILED','LOCKED')").use { ps ->
                ps.setLong(1, nowMs - keepMs); ps.executeUpdate()
            }
            c.prepareStatement("DELETE FROM trusted_exit.rate_counter WHERE window_start < ?").use { ps ->
                ps.setLong(1, nowMs - keepMs); ps.executeUpdate()
            }
            c.prepareStatement("DELETE FROM trusted_exit.jti_ledger WHERE redeemed_at < ?").use { ps ->
                ps.setLong(1, nowMs - keepMs); ps.executeUpdate()
            }
        }
    }

    private fun redeemJti(c: Connection, jti: String, nowMs: Long) {
        c.prepareStatement("INSERT INTO trusted_exit.jti_ledger (jti, redeemed_at) VALUES (?,?) ON CONFLICT (jti) DO NOTHING").use { ps ->
            ps.setString(1, jti); ps.setLong(2, nowMs); ps.executeUpdate()
        }
    }

    private fun setStatus(c: Connection, id: String, status: String) {
        c.prepareStatement("UPDATE trusted_exit.challenge SET status=? WHERE id=?").use { ps ->
            ps.setString(1, status); ps.setString(2, id); ps.executeUpdate()
        }
    }

    private fun update(sql: String, bind: (java.sql.PreparedStatement) -> Unit) {
        db.open().use { c -> c.prepareStatement(sql).use { ps -> bind(ps); ps.executeUpdate() } }
    }

    private fun selectById(c: Connection, id: String): StoredChallenge? =
        c.prepareStatement("$SELECT_COLS WHERE id = ?").use { ps -> ps.setString(1, id); ps.executeQuery().use { if (it.next()) read(it) else null } }

    private fun selectByJti(c: Connection, jti: String): StoredChallenge? =
        c.prepareStatement("$SELECT_COLS WHERE jti = ?").use { ps -> ps.setString(1, jti); ps.executeQuery().use { if (it.next()) read(it) else null } }

    private fun selectForUpdate(c: Connection, id: String): StoredChallenge? =
        c.prepareStatement("$SELECT_COLS WHERE id = ? FOR UPDATE").use { ps -> ps.setString(1, id); ps.executeQuery().use { if (it.next()) read(it) else null } }

    private fun read(rs: java.sql.ResultSet): StoredChallenge {
        val cooldown = rs.getLong("cooldown_until").let { if (rs.wasNull()) null else it }
        return StoredChallenge(
            id = rs.getString("id"), jti = rs.getString("jti"), userId = rs.getString("user_id"),
            seriesId = rs.getString("series_id"), periodId = rs.getString("period_id"),
            deviceId = rs.getString("device_id"), contactId = rs.getString("contact_id"),
            salt = rs.getString("salt"), codeHash = rs.getString("code_hash"),
            attemptsLeft = rs.getInt("attempts_left"), status = rs.getString("status"),
            issuedAt = rs.getLong("issued_at"), expiresAt = rs.getLong("expires_at"),
            cooldownUntil = cooldown, smsRef = rs.getString("sms_ref"),
            receipt = rs.getString("receipt"),
        )
    }

    private companion object {
        const val SELECT_COLS =
            "SELECT id, jti, user_id, series_id, period_id, device_id, contact_id, salt, code_hash, " +
                "attempts_left, status, issued_at, expires_at, cooldown_until, sms_ref, send_attempts, receipt " +
                "FROM trusted_exit.challenge"
    }
}
