package app.mama.trustedexit

import app.mama.account.AccountHttpTransport
import app.mama.account.AccountSession
import app.mama.account.HttpReply
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-side proof that the pilot orchestrator gates BRICK on REAL server readiness
 * (provision -> request verification -> refresh readiness) and that the verification
 * request maps the server's answers correctly. Only [State.Ready] permits a lock;
 * a disabled provider, a pending (unverified) contact, a failure or an offline step
 * all keep the lock OUT. Robolectric only for a real org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrustedExitPilotOrchestratorTest {

    private val spec = ExitSetupSpec(
        contactName = "Мама", contactPhone = "+31611112222",
        durationDays = 3, startTime = "22:00:00", endTime = "06:00:00",
        timezone = "Europe/Amsterdam", brickMode = true,
        periodKey = "te-x",
        beginsAtIso = "2026-11-06T22:00:00+00:00", endsAtIso = "2026-11-07T06:00:00+00:00",
    )

    private fun readiness(ready: Boolean, verified: Boolean = ready) = ServerReadiness(
        active = true, contactSelected = true, contactVerified = verified,
        hasRegisteredPeriod = true, ready = ready,
    )

    private fun provisioned(readiness: ServerReadiness?) =
        HttpTrustedExitSetup.Result.Provisioned("c-1", "s-1", "p-1", readiness)

    private fun orchestrator(
        prov: HttpTrustedExitSetup.Result,
        disp: HttpTrustedExitSetup.VerificationDispatch,
        refreshed: ServerReadiness?,
    ) = TrustedExitPilotOrchestrator(
        provision = { prov },
        requestVerification = { disp },
        refreshReadiness = { refreshed },
    )

    // --- state machine (the lock gate) ---------------------------------------

    @Test fun provisionFailure_isFailed_lockStaysOut() {
        val s = orchestrator(
            HttpTrustedExitSetup.Result.Failed("offline"),
            HttpTrustedExitSetup.VerificationDispatch.Requested("pending"),
            readiness(ready = true),  // ignored: provisioning failed first
        ).run(spec)
        assertEquals(TrustedExitPilotOrchestrator.State.Failed("offline"), s)
    }

    @Test fun disabledProvider503_isVerificationUnavailable() {
        val s = orchestrator(
            provisioned(readiness(ready = false, verified = false)),
            HttpTrustedExitSetup.VerificationDispatch.Unavailable,
            readiness(ready = false, verified = false),
        ).run(spec)
        assertEquals(
            TrustedExitPilotOrchestrator.State.VerificationUnavailable("s-1", "p-1"), s,
        )
    }

    @Test fun requestedButNotVerified_isAwaitingVerification() {
        val s = orchestrator(
            provisioned(readiness(ready = false, verified = false)),
            HttpTrustedExitSetup.VerificationDispatch.Requested("pending"),
            readiness(ready = false, verified = false),
        ).run(spec)
        assertEquals(
            TrustedExitPilotOrchestrator.State.AwaitingVerification("s-1", "p-1"), s,
        )
    }

    @Test fun exactPeriodReady_isReady_evenWhenReverifyPending() {
        // A contact verified on a previous run: the fresh (idempotent) verification
        // request is still 'pending'-shaped, but a ready exact-period readiness wins.
        val s = orchestrator(
            provisioned(readiness(ready = false)),
            HttpTrustedExitSetup.VerificationDispatch.Requested("pending"),
            readiness(ready = true),
        ).run(spec)
        assertEquals(TrustedExitPilotOrchestrator.State.Ready("s-1", "p-1"), s)
    }

    @Test fun verifyRequestFailure_isFailed() {
        val s = orchestrator(
            provisioned(readiness(ready = false, verified = false)),
            HttpTrustedExitSetup.VerificationDispatch.Failed("status-404"),
            readiness(ready = false, verified = false),
        ).run(spec)
        assertEquals(TrustedExitPilotOrchestrator.State.Failed("verify-status-404"), s)
    }

    @Test fun droppedReadinessRefresh_cannotAuthorizeLockFromCachedSnapshot() {
        val s = orchestrator(
            provisioned(readiness(ready = true)),
            HttpTrustedExitSetup.VerificationDispatch.Requested("pending"),
            null,  // refresh poll dropped; cached approval may now be revoked
        ).run(spec)
        assertEquals(
            TrustedExitPilotOrchestrator.State.Failed("readiness-refresh-unavailable"), s,
        )
    }

    @Test fun inconsistentReadyFlag_withoutVerifiedContact_doesNotAuthorizeLock() {
        val s = orchestrator(
            provisioned(readiness(ready = false)),
            HttpTrustedExitSetup.VerificationDispatch.Requested("pending"),
            readiness(ready = true, verified = false),
        ).run(spec)
        assertEquals(
            TrustedExitPilotOrchestrator.State.AwaitingVerification("s-1", "p-1"), s,
        )
    }

    // --- requestContactVerification HTTP mapping -----------------------------

    private var token = "tok-1"
    private val session = object : TrustedExitSessionSource {
        override fun ensureSession(): AccountSession? =
            if (token.isEmpty()) null else AccountSession("u", UUID.randomUUID().toString(), token)
        override fun forgetSession() { token = "tok-2" }
    }
    private val cid = UUID.randomUUID().toString()

    private fun client(server: AccountHttpTransport) =
        HttpTrustedExitSetup(transport = server, session = session, context = { })

    private fun verifyServer(reply: (bearer: String?) -> HttpReply) = object : AccountHttpTransport {
        override fun postJson(path: String, jsonBody: String, bearer: String?): HttpReply {
            assertTrue(path.endsWith("/verification"))
            assertTrue(path.contains(cid))
            return reply(bearer)
        }
        override fun getJson(path: String, bearer: String?): HttpReply = HttpReply(404, "{}")
    }

    @Test fun verification200_pending_isRequested() {
        val d = client(verifyServer { HttpReply(200, JSONObject().put("status", "pending").toString()) })
            .requestContactVerification(cid)
        assertEquals(HttpTrustedExitSetup.VerificationDispatch.Requested("pending"), d)
    }

    @Test fun verification202_unknownDispatch_isRequested() {
        val d = client(verifyServer {
            HttpReply(202, JSONObject().put("status", "pending").put("dispatch", "unknown").toString())
        }).requestContactVerification(cid)
        assertTrue(d is HttpTrustedExitSetup.VerificationDispatch.Requested)
    }

    @Test fun verification503_isUnavailable() {
        val d = client(verifyServer { HttpReply(503, "{}") }).requestContactVerification(cid)
        assertEquals(HttpTrustedExitSetup.VerificationDispatch.Unavailable, d)
    }

    @Test fun verification404_isFailed() {
        val d = client(verifyServer { HttpReply(404, "{}") }).requestContactVerification(cid)
        assertEquals(HttpTrustedExitSetup.VerificationDispatch.Failed("status-404"), d)
    }

    @Test fun verification401_thenReauth_succeeds() {
        var first = true
        val d = client(verifyServer { _ ->
            if (first) { first = false; HttpReply(401, "{}") }
            else HttpReply(200, JSONObject().put("status", "pending").toString())
        }).requestContactVerification(cid)
        assertEquals(HttpTrustedExitSetup.VerificationDispatch.Requested("pending"), d)
    }

    @Test fun verification_badContactId_isFailed() {
        val d = client(verifyServer { HttpReply(200, "{}") }).requestContactVerification("not-a-uuid")
        assertEquals(HttpTrustedExitSetup.VerificationDispatch.Failed("bad-contact-id"), d)
    }

    @Test fun verification_noSession_isFailed() {
        token = ""
        val d = client(verifyServer { HttpReply(200, "{}") }).requestContactVerification(cid)
        assertEquals(HttpTrustedExitSetup.VerificationDispatch.Failed("transport"), d)
    }
}
