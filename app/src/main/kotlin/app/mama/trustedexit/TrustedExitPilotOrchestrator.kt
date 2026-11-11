package app.mama.trustedexit

/**
 * Drives the server-owned Trusted Exit setup so the UI can gate BRICK on REAL
 * readiness instead of starting a lock with no server context (the gap behind
 * lezhik0036-sys/-#85: screens that call startSeries/startTest without
 * provisioning the server series/contact/period or refreshing server readiness).
 *
 * It provisions the series + contact + exact period, asks the server to verify
 * the contact (POST /v1/setup/contacts/{id}/verification — no app-side code, no
 * self-verify), refreshes the authoritative exact-period readiness (recorded into
 * [TrustedExitReadinessGate] off the main thread), and returns the single state on
 * which the UI may enable a lock.
 *
 * Fail-closed by construction: only [State.Ready] — the exact provisioned period is
 * server-ready, which REQUIRES a provider-VERIFIED contact — permits a lock. Until
 * the trusted person proves possession of the number there is no Ready, so a no-SIM
 * Wi-Fi device can walk the whole flow and the strict BRICK stays unavailable. All
 * three injected steps do network/Keystore work; call [run] OFF the main thread.
 */
class TrustedExitPilotOrchestrator(
    private val provision: (ExitSetupSpec) -> HttpTrustedExitSetup.Result,
    private val requestVerification: (contactId: String) -> HttpTrustedExitSetup.VerificationDispatch,
    private val refreshReadiness: () -> ServerReadiness?,
) {
    sealed interface State {
        /** Provisioning/transport failed, or the backend is not configured. Lock stays out. */
        data class Failed(val reason: String) : State

        /** Server context exists but the verification provider is disabled/unprovisioned
         *  (503): no SMS was sent and the contact cannot be verified yet. Lock stays out. */
        data class VerificationUnavailable(val seriesId: String, val periodId: String) : State

        /** Verification requested; the contact is NOT yet provider-verified, so readiness
         *  is false. Lock stays out until the trusted person proves the number. */
        data class AwaitingVerification(val seriesId: String, val periodId: String) : State

        /** The EXACT provisioned period is server-ready (contact verified). The ONLY state
         *  on which the UI may enable a lock. */
        data class Ready(val seriesId: String, val periodId: String) : State
    }

    /**
     * Provision -> request verification -> refresh readiness, returning the gate state.
     * The UI must enable BRICK only on [State.Ready]; every other state keeps it out.
     * A contact already verified on a previous run reports [State.Ready] even though a
     * fresh verification request is (idempotently) made, so recovery/cold start work.
     */
    fun run(spec: ExitSetupSpec): State {
        val provisioned = when (val r = provision(spec)) {
            is HttpTrustedExitSetup.Result.Failed -> return State.Failed(r.reason)
            is HttpTrustedExitSetup.Result.Provisioned -> r
        }
        val dispatch = requestVerification(provisioned.contactId)
        // Authoritative, exact-period readiness recorded off the main thread; fall back
        // to the provisioning readiness only if the refresh poll dropped.
        val readiness = refreshReadiness() ?: provisioned.readiness
        if (readiness != null && readiness.ready) {
            return State.Ready(provisioned.seriesId, provisioned.periodId)
        }
        return when (dispatch) {
            is HttpTrustedExitSetup.VerificationDispatch.Unavailable ->
                State.VerificationUnavailable(provisioned.seriesId, provisioned.periodId)
            is HttpTrustedExitSetup.VerificationDispatch.Failed ->
                State.Failed("verify-${dispatch.reason}")
            is HttpTrustedExitSetup.VerificationDispatch.Requested ->
                State.AwaitingVerification(provisioned.seriesId, provisioned.periodId)
        }
    }

    companion object {
        /**
         * Production wiring: provision + verification via [setup]; readiness via
         * [refreshReadiness] (e.g. `MamaTrustedExit::refreshReadiness`, which records the
         * result into [TrustedExitReadinessGate] so the main-thread lock-start gate agrees).
         */
        fun create(
            setup: HttpTrustedExitSetup,
            refreshReadiness: () -> ServerReadiness?,
        ): TrustedExitPilotOrchestrator = TrustedExitPilotOrchestrator(
            provision = setup::provision,
            requestVerification = setup::requestContactVerification,
            refreshReadiness = refreshReadiness,
        )
    }
}
