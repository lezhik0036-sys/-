package app.mama.trustedexit

import app.mama.account.AccountHttpTransport
import app.mama.account.HttpReply
import org.json.JSONObject

/**
 * Builds the server-owned Trusted Exit prerequisites over the authenticated
 * FastAPI setup API (the `/v1/setup` routes), then records the resulting series
 * and lock-period ids so [HttpTrustedExitBackend] knows which exit to drive.
 *
 * It assembles, all under the verified session (never client-supplied identity):
 *   1. a trusted contact          (POST /v1/setup/contacts)
 *   2. a draft series             (POST /v1/setup/series)
 *   3. the contact selection      (POST /v1/setup/series/{id}/contact)
 *   4. activation                 (POST /v1/setup/series/{id}/activate)
 *   5. a registered lock period   (POST /v1/setup/series/{id}/periods)
 * and persists the (series, period) pair via [context] on success.
 *
 * What it deliberately does NOT do: it never marks the contact VERIFIED (that is
 * the independent provider path, not an app write) and never sends an SMS. So a
 * freshly provisioned series is NOT yet a usable exit — [readiness] reports
 * `contactVerified`/`ready` false until a real provider verification exists. The
 * BRICK preflight must gate on that readiness, never on provisioning succeeding.
 *
 * Inputs are primitives so the client stays free of the core domain types and is
 * host-testable; the caller maps its DailyWindow / SeriesKind / TrustedContact to
 * these. Fail-closed: any non-200, transport failure, or malformed reply returns
 * a failure result and no context is stored. One 401 re-auth is attempted per call.
 * Call OFF the main thread (it does network + Keystore work through the session).
 */
class HttpTrustedExitSetup(
    private val transport: AccountHttpTransport,
    private val session: TrustedExitSessionSource,
    private val context: ExitContextWriter,
) {
    sealed interface Result {
        /** Provisioned; the series + period are stored and the readiness is included.
         *  [contactId] is the server-owned id to request verification for. */
        data class Provisioned(
            val contactId: String, val seriesId: String, val periodId: String,
            val readiness: ServerReadiness?,
        ) : Result

        /** Could not provision now (offline / transport / server / malformed). Fail-closed. */
        data class Failed(val reason: String) : Result
    }

    /** Outcome of asking the server to verify a contact (POST /contacts/{id}/verification).
     *  NEVER verifies the contact itself and NEVER sends a code the app can check:
     *  the trusted person answers the provider, and the server records 'verified'. */
    sealed interface VerificationDispatch {
        /** The server accepted the request (status e.g. "pending"); the contact is NOT
         *  yet verified — readiness stays false until the provider proves possession. */
        data class Requested(val status: String) : VerificationDispatch

        /** The verification provider is disabled/unprovisioned (503): no SMS was sent.
         *  Expected in the pilot until live delivery is authorized. */
        object Unavailable : VerificationDispatch

        /** Could not request now (not owned / transport / server / malformed). Fail-closed. */
        data class Failed(val reason: String) : VerificationDispatch
    }

    /** Create contact -> draft series -> select -> activate -> register period -> store context. */
    fun provision(spec: ExitSetupSpec): Result {
        if (session.ensureSession() == null) return Result.Failed("no-session")

        val contactId = postString(
            PATH_CONTACTS,
            JSONObject().put("name", spec.contactName).put("phone", spec.contactPhone).toString(),
            "contact_id",
        ) ?: return Result.Failed("contact")

        val seriesId = postString(
            PATH_SERIES,
            JSONObject()
                .put("duration_days", spec.durationDays)
                .put("start_time", spec.startTime)
                .put("end_time", spec.endTime)
                .put("timezone", spec.timezone)
                .put("brick_mode", spec.brickMode)
                .toString(),
            "series_id",
        ) ?: return Result.Failed("series")

        if (!postOk(seriesPath(seriesId, "/contact"), JSONObject().put("contact_id", contactId).toString())) {
            return Result.Failed("select")
        }
        if (!postOk(seriesPath(seriesId, "/activate"), "{}")) return Result.Failed("activate")

        val periodId = postString(
            seriesPath(seriesId, "/periods"),
            JSONObject()
                .put("period_key", spec.periodKey)
                .put("begins_at", spec.beginsAtIso)
                .put("ends_at", spec.endsAtIso)
                .toString(),
            "period_id",
        ) ?: return Result.Failed("period")

        // Persist BEFORE reading readiness so a dropped readiness poll still leaves
        // the app pointing at the right series/period for the exit flow.
        if (runCatching {
            context.set(ExitContext(seriesId = seriesId, periodId = periodId))
        }.isFailure) {
            // Provisioning may have succeeded server-side but there is no
            // durable device period binding. Never claim a usable setup.
            return Result.Failed("context-storage")
        }

        return Result.Provisioned(contactId, seriesId, periodId, readiness(seriesId, periodId))
    }

    /**
     * Ask the server to verify that the trusted person controls [contactId]'s number
     * (POST /v1/setup/contacts/{id}/verification). This NEVER marks the contact verified
     * and NEVER returns a code the app could check locally — the provider delivers the
     * challenge and the trusted person proves possession back to the server. A disabled
     * provider answers 503 (no SMS). One 401 re-auth per call; fail-closed otherwise.
     * Call OFF the main thread.
     */
    fun requestContactVerification(contactId: String): VerificationDispatch {
        val path = runCatching {
            PATH_CONTACTS + "/" + java.util.UUID.fromString(contactId) + "/verification"
        }.getOrNull() ?: return VerificationDispatch.Failed("bad-contact-id")
        val reply = authed(path, "{}") ?: return VerificationDispatch.Failed("transport")
        return when (reply.status) {
            200, 202 -> runCatching {
                val json = JSONObject(reply.body)
                val returnedContactId = json.getString("contact_id")
                val returnedStatus = json.getString("status")
                // The response must acknowledge exactly the contact we asked to verify.
                // Missing/foreign contact IDs or unknown server statuses are not success.
                if (returnedContactId != java.util.UUID.fromString(contactId).toString() ||
                    returnedStatus !in setOf("pending", "verified") ||
                    (reply.status == 202 && returnedStatus != "pending")
                ) {
                    VerificationDispatch.Failed("invalid-response")
                } else {
                    VerificationDispatch.Requested(returnedStatus)
                }
            }.getOrDefault(VerificationDispatch.Failed("invalid-response"))
            503 -> VerificationDispatch.Unavailable
            else -> VerificationDispatch.Failed("status-${reply.status}")
        }
    }

    /**
     * Readiness for the EXACT provisioned period, not merely for any active
     * period on the series. A server reporting another period ready must never
     * make this device's setup appear safe to lock.
     */
    fun readiness(seriesId: String, periodId: String): ServerReadiness? {
        val sess = session.ensureSession() ?: return null
        val path = runCatching {
            seriesPath(seriesId, "/periods/" + java.util.UUID.fromString(periodId) + "/readiness")
        }.getOrNull() ?: return null
        val reply = runCatching { transport.getJson(path, sess.token) }
            .getOrNull() ?: return null
        if (reply.status != 200) return null
        return runCatching {
            val j = JSONObject(reply.body)
            val exactMatch =
                j.getBoolean("period_matches_requested") &&
                j.getString("series_id") == seriesId &&
                j.getString("period_id") == periodId
            ServerReadiness(
                active = j.getBoolean("active"),
                contactSelected = j.getBoolean("contact_selected"),
                contactVerified = j.getBoolean("contact_verified"),
                hasRegisteredPeriod = j.getBoolean("has_registered_period"),
                ready = j.getBoolean("ready") && exactMatch,
            )
        }.getOrNull()
    }

    // --- internals -----------------------------------------------------------

    /** POST [body]; return string field [key] from a 200 reply, else null. */
    private fun postString(path: String, body: String, key: String): String? {
        val reply = authed(path, body) ?: return null
        if (reply.status != 200) return null
        return runCatching {
            val parsed = JSONObject(reply.body).getString(key)
            val canonical = java.util.UUID.fromString(parsed).toString()
            if (canonical != parsed) null else canonical
        }.getOrNull()
    }

    /** POST [body]; true iff the reply is 200. */
    private fun postOk(path: String, body: String): Boolean = authed(path, body)?.status == 200

    /** POST with the current bearer; on a 401 drop the session, re-auth once and retry. */
    private fun authed(path: String, body: String): HttpReply? {
        val sess = session.ensureSession() ?: return null
        var reply = runCatching { transport.postJson(path, body, sess.token) }.getOrNull() ?: return null
        if (reply.status == 401) {
            session.forgetSession()
            val fresh = session.ensureSession() ?: return null
            reply = runCatching { transport.postJson(path, body, fresh.token) }.getOrNull() ?: return null
        }
        return reply
    }

    private fun seriesPath(seriesId: String, suffix: String) =
        PATH_SERIES + "/" + java.util.UUID.fromString(seriesId) + suffix

    private companion object {
        const val PATH_CONTACTS = "/v1/setup/contacts"
        const val PATH_SERIES = "/v1/setup/series"
    }
}

/** All the values the app must supply to provision one Trusted Exit series. */
data class ExitSetupSpec(
    val contactName: String,
    val contactPhone: String,
    val durationDays: Int,
    /** "HH:mm:ss" local start of the daily BRICK window. */
    val startTime: String,
    /** "HH:mm:ss" local end of the daily BRICK window. */
    val endTime: String,
    val timezone: String,
    val brickMode: Boolean,
    /** Client-stable key for the current lock period (idempotent registration). */
    val periodKey: String,
    /** ISO-8601 instant the current lock period begins. */
    val beginsAtIso: String,
    /** ISO-8601 instant the current lock period ends. */
    val endsAtIso: String,
)

/** The minimal write capability [HttpTrustedExitSetup] needs to record the context. */
fun interface ExitContextWriter {
    fun set(ctx: ExitContext)
}
