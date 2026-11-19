package app.mama.trustedexit

/**
 * Idempotent, off-main-thread driver that connects the setup orchestrator to the
 * UI so BRICK can only start on a REAL server-ready exit (lezhik0036-sys/-#85).
 *
 * - Fresh attempt (no persisted [ExitContext]): run the full
 *   provision -> request-verification -> refresh flow exactly once. Provisioning
 *   persists the context, so this sends the verification request once per attempt.
 * - Retry / cold start (a context is already provisioned): do NOT re-provision and
 *   do NOT re-send — only a fresh exact-period readiness may authorize the lock, so
 *   this just refreshes readiness on the stored series/period. This avoids duplicate
 *   contact/series creation and a second paid SMS, and survives process death.
 *
 * Fail-closed throughout: a dropped readiness refresh is [State.Failed], never a
 * cached approval; the lock may start only on [State.Ready]. Call [prepare] OFF the
 * main thread (it does network + Keystore work).
 */
class TrustedExitPilotController(
    private val existingContext: () -> ExitContext?,
    private val runFullAttempt: (ExitSetupSpec) -> TrustedExitPilotOrchestrator.State,
    private val refreshReadiness: () -> ServerReadiness?,
) {
    fun prepare(spec: ExitSetupSpec): TrustedExitPilotOrchestrator.State {
        val ctx = existingContext()
            ?: return runFullAttempt(spec) // fresh attempt: provision + verify + refresh, once
        // A series/period is already provisioned on this device. Never re-provision
        // or re-send; only a fresh exact-period server readiness can authorize BRICK.
        val r = refreshReadiness()
            ?: return TrustedExitPilotOrchestrator.State.Failed("readiness-refresh-unavailable")
        return if (
            r.ready && r.active && r.contactSelected && r.contactVerified && r.hasRegisteredPeriod
        ) {
            TrustedExitPilotOrchestrator.State.Ready(ctx.seriesId, ctx.periodId)
        } else {
            TrustedExitPilotOrchestrator.State.AwaitingVerification(ctx.seriesId, ctx.periodId)
        }
    }

    companion object {
        fun create(
            context: FileExitContextStore,
            orchestrator: TrustedExitPilotOrchestrator,
            refreshReadiness: () -> ServerReadiness?,
        ) = TrustedExitPilotController(
            existingContext = context::current,
            runFullAttempt = orchestrator::run,
            refreshReadiness = refreshReadiness,
        )
    }
}
