package app.mama.backend.trustedexit

/**
 * INTERNAL dispatch-status reconciliation (Ktor side of mama-fastapi #47).
 *
 * After an ambiguous FastAPI→Ktor dispatch (the request may have reached Ktor
 * and an SMS may already be on its way, but the response was lost), FastAPI must
 * be able to recover the original `provider_challenge_id` **without triggering a
 * second SMS**. It does so by querying this read-only status by the stable
 * server-owned grant **jti** — the same jti Ktor stored when it created the
 * challenge ([OtpRepository.findByJti]).
 *
 * This lookup NEVER creates, sends, mutates or consumes anything: it is a pure
 * read, which is why the authenticated request that reaches it does not need a
 * one-time nonce ledger (a replay of a read is harmless). FastAPI authenticates
 * the call with the shared internal transport MAC and then re-checks that the
 * returned bindings match its own durable request row (cross-user/device/period
 * protection lives on the querying side, against itself).
 */
data class DispatchStatus(
    val challengeId: String,
    val status: String,
    val userId: String,
    val seriesId: String,
    val periodId: String,
    val deviceId: String,
    val contactId: String,
)

/** Resolves a dispatch status by the grant jti. Returns null when unknown. */
fun interface DispatchStatusLookup {
    fun byJti(jti: String): DispatchStatus?
}

/** Production lookup, backed by the durable challenge store. Read-only. */
class RepositoryDispatchStatusLookup(private val repo: OtpRepository) : DispatchStatusLookup {
    override fun byJti(jti: String): DispatchStatus? {
        val c = repo.findByJti(jti) ?: return null
        return DispatchStatus(
            challengeId = c.id, status = c.status, userId = c.userId,
            seriesId = c.seriesId, periodId = c.periodId,
            deviceId = c.deviceId, contactId = c.contactId,
        )
    }
}
