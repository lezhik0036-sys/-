package app.mama.platform

import android.content.Context
import android.util.Log
import app.mama.core.ClockGuard
import app.mama.core.CorePolicy
import app.mama.core.DailyWindow
import app.mama.core.Entitlements
import app.mama.core.ExitKind
import app.mama.core.FlexEngine
import app.mama.core.GrantSource
import app.mama.core.LockEngine
import app.mama.core.LockPlan
import app.mama.core.LockState
import app.mama.core.Reconciliation
import app.mama.core.Recovery
import app.mama.core.Series
import app.mama.core.SeriesEngine
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.Session
import app.mama.core.SessionMode
import app.mama.core.StandardRules
import app.mama.core.Snapshot
import app.mama.core.TrustedContact
import app.mama.lock.LockService
import app.mama.ui.Texts
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The single entry point from Android into MAMA Core. Every event (UI action,
 * alarm, boot, clock change, service tick) goes through here, so the device is
 * always brought to the state the core says it should be in.
 */
object Mama {
    private const val TAG = "MAMA"

    val policy = CorePolicy()
    private val engine = LockEngine(policy)
    private val recovery = Recovery(engine)
    private val seriesEngine = SeriesEngine(engine, policy)
    private val flexEngine = FlexEngine(engine, policy)

    @Volatile private var cached: Reconciliation? = null

    /** Loads state, applies elapsed time, saves. Does not touch services or alarms. */
    @Synchronized
    fun reconcile(context: Context): Reconciliation {
        val app = context.applicationContext
        val store = SessionStore(app)
        val snapshot = try {
            store.load()
        } catch (e: RuntimeException) {
            // Only reachable if app storage was tampered with (root) or a bug.
            Log.e(TAG, "Unreadable state, starting clean", e)
            Snapshot()
        }
        var r = recovery.reconcile(snapshot, DeviceClock.wallNow(), DeviceClock.elapsedMs(), DeviceClock.bootId(app))
        // Standard series: count a finished night, plan the next one, or mark it failed.
        val series = r.snapshot.series
        if (series != null && series.active) {
            val u = seriesEngine.afterSession(series, r.snapshot.session, UUID.randomUUID().toString(), r.trustedNow)
            if (u.series != series || u.session != r.snapshot.session) {
                var ent = r.snapshot.entitlements
                if (u.series.status == SeriesStatus.BROKEN) {
                    // The phone is already free; this only starts the 30-day wait for a free series.
                    ent = ent.copy(standardFailedAt = u.series.endedAt ?: r.trustedNow)
                }
                r = r.copy(
                    snapshot = r.snapshot.copy(series = u.series, session = u.session, entitlements = ent),
                    state = engine.stateOf(u.session, r.trustedNow),
                )
                if (!u.series.active) History.record(app, u.series)
            }
        }
        // FLEX: consume a started period, count success, burn an early exit, earn the reward.
        val flex = r.snapshot.entitlements.flex
        if (flex != null && !flex.over) {
            val u = flexEngine.afterSession(flex, r.snapshot.session, r.trustedNow)
            if (u.pkg != flex) {
                var ent = r.snapshot.entitlements.copy(flex = u.pkg)
                if (u.rewardEarnedNow) ent = ent.copy(freeFlexCredits = ent.freeFlexCredits + 1)
                r = r.copy(snapshot = r.snapshot.copy(entitlements = ent))
                if (u.pkg.over) History.recordFlex(app, u.pkg)
            }
        }
        store.save(r.snapshot)
        cached = r
        return r
    }

    /** Reconciles and makes the device match: alarm for the next change, lock service on/off. */
    @Synchronized
    fun sync(context: Context): LockState {
        val r = reconcile(context)
        Alarms.schedule(context, r.state.nextChangeAt, r.trustedNow)
        when (r.state) {
            is LockState.Locked, is LockState.EmergencyPass -> LockService.start(context)
            else -> LockService.stop(context)
        }
        return r.state
    }

    /** Same as [sync] but for the lock service itself, which manages its own lifecycle. */
    @Synchronized
    fun syncFromService(context: Context): Reconciliation {
        val r = reconcile(context)
        Alarms.schedule(context, r.state.nextChangeAt, r.trustedNow)
        return r
    }

    fun state(context: Context): LockState = (cached ?: reconcile(context)).state

    fun session(context: Context): Session? = (cached ?: reconcile(context)).snapshot.session

    /** Trusted time without touching storage; for countdowns. */
    fun trustedNow(context: Context): Instant {
        val snap = (cached ?: reconcile(context)).snapshot
        return ClockGuard.trustedNow(
            snap.anchor, snap.lastTrusted, DeviceClock.wallNow(), DeviceClock.elapsedMs(), DeviceClock.bootId(context),
        )
    }

    fun series(context: Context): Series? = (cached ?: reconcile(context)).snapshot.series

    fun entitlements(context: Context): Entitlements = (cached ?: reconcile(context)).snapshot.entitlements

    /** Nothing running: no active series, no FLEX period planned, no session. */
    private fun idle(r: Reconciliation) =
        r.state == LockState.Free && r.snapshot.series?.active != true &&
            r.snapshot.entitlements.flex?.currentSessionId == null

    sealed interface SeriesStart {
        data object Started : SeriesStart
        data object Busy : SeriesStart

        /** After a failure: free start only after the 30-day wait (or pay for Restart). */
        data class Waiting(val days: Long) : SeriesStart
    }

    /** Starts a free standard series. Tonight's window is locked automatically. */
    @Synchronized
    fun startSeries(
        context: Context,
        kind: SeriesKind,
        window: DailyWindow,
        contact: TrustedContact,
        mode: SessionMode,
    ): SeriesStart {
        val r = reconcile(context)
        if (!idle(r)) return SeriesStart.Busy
        val ent = r.snapshot.entitlements
        if (!StandardRules.canStartFree(ent, r.trustedNow)) {
            return SeriesStart.Waiting(StandardRules.daysUntilFreeStart(ent, r.trustedNow))
        }
        save(context, r, kind, window, contact, mode, ent)
        return SeriesStart.Started
    }

    /**
     * Immediate Restart of a failed series with the same settings. Call only
     * after the Restart was granted (paid, or a test grant in test builds).
     */
    @Synchronized
    fun restartSeries(context: Context, source: GrantSource): Boolean {
        val r = reconcile(context)
        val series = r.snapshot.series ?: return false
        if (series.status != SeriesStatus.BROKEN || r.state != LockState.Free) return false
        Log.i(TAG, "Restart of ${series.kind} granted: $source")
        // The Restart replaces the free-start wait caused by this failure.
        val ent = r.snapshot.entitlements.copy(standardFailedAt = null)
        save(context, r, series.kind, series.window, series.contact, series.mode, ent)
        return true
    }

    private fun save(
        context: Context,
        r: Reconciliation,
        kind: SeriesKind,
        window: DailyWindow,
        contact: TrustedContact,
        mode: SessionMode,
        ent: Entitlements,
    ) {
        val u = seriesEngine.start(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), kind, window, contact, mode, r.trustedNow,
        )
        SessionStore(context.applicationContext)
            .save(r.snapshot.copy(series = u.series, session = u.session, entitlements = ent))
        sync(context)
    }

    /** Forgets a finished (completed or failed) series; the failure date is kept. */
    @Synchronized
    fun dismissSeries(context: Context) {
        val r = reconcile(context)
        if (r.snapshot.series?.active == true) return
        SessionStore(context.applicationContext).save(r.snapshot.copy(series = null))
        reconcile(context)
    }

    /**
     * Activates a FLEX package. [GrantSource.REWARD] spends an earned free
     * package; other sources must come from a completed (or test) grant.
     */
    @Synchronized
    fun activateFlex(context: Context, source: GrantSource): Boolean {
        val r = reconcile(context)
        if (!idle(r)) return false
        var ent = r.snapshot.entitlements
        if (ent.flex?.over == false) return false
        if (source == GrantSource.REWARD) {
            if (ent.freeFlexCredits <= 0) return false
            ent = ent.copy(freeFlexCredits = ent.freeFlexCredits - 1)
        }
        ent = ent.copy(flex = flexEngine.activate(UUID.randomUUID().toString(), source, r.trustedNow))
        SessionStore(context.applicationContext).save(r.snapshot.copy(entitlements = ent))
        reconcile(context)
        return true
    }

    /**
     * Plans one FLEX period with absolute moments. [start] = null starts now.
     * A start in the past is rejected, never shortened.
     */
    @Synchronized
    fun startFlexPeriod(
        context: Context,
        start: Instant?,
        end: Instant,
        zone: ZoneId,
        contact: TrustedContact,
        mode: SessionMode,
    ): FlexEngine.PlanResult {
        val r = reconcile(context)
        val flex = r.snapshot.entitlements.flex
        if (!idle(r) || flex == null) return FlexEngine.PlanResult.Rejected(FlexEngine.PlanError.NOT_AVAILABLE)
        val result = flexEngine.planPeriod(flex, start, end, zone, contact, mode, UUID.randomUUID().toString(), r.trustedNow)
        if (result is FlexEngine.PlanResult.Planned) {
            SessionStore(context.applicationContext).save(
                r.snapshot.copy(session = result.session, entitlements = r.snapshot.entitlements.copy(flex = result.pkg)),
            )
            sync(context)
        }
        return result
    }

    /** Leaves the result screen of a finished FLEX package. */
    @Synchronized
    fun dismissFlex(context: Context) {
        val r = reconcile(context)
        if (r.snapshot.entitlements.flex?.over != true) return
        SessionStore(context.applicationContext)
            .save(r.snapshot.copy(entitlements = r.snapshot.entitlements.copy(flex = null)))
        reconcile(context)
    }

    sealed interface StartResult {
        data class Started(val state: LockState) : StartResult
        data class Rejected(val reason: LockEngine.CreateError) : StartResult
        data object AlreadyRunning : StartResult
    }

    @Synchronized
    fun start(context: Context, plan: LockPlan, contact: TrustedContact): StartResult {
        val r = reconcile(context)
        if (r.state != LockState.Free) return StartResult.AlreadyRunning
        return when (val created = engine.create(UUID.randomUUID().toString(), plan, contact, r.trustedNow)) {
            is LockEngine.CreateResult.Rejected -> StartResult.Rejected(created.reason)
            is LockEngine.CreateResult.Created -> {
                SessionStore(context.applicationContext).save(r.snapshot.copy(session = created.session))
                StartResult.Started(sync(context))
            }
        }
    }

    @Synchronized
    fun cancelBeforeStart(context: Context): Boolean {
        val r = reconcile(context)
        val session = r.snapshot.session ?: return false
        val result = engine.cancelBeforeStart(session, r.trustedNow)
        if (result !is LockEngine.CancelResult.Cancelled) return false
        SessionStore(context.applicationContext).save(r.snapshot.copy(session = result.session))
        sync(context)
        return true
    }

    sealed interface CodeRequest {
        data class Sent(val contact: TrustedContact, val expiresAt: Instant) : CodeRequest
        data class Rejected(val reason: LockEngine.CodeRequestError, val retryAt: Instant?) : CodeRequest
        data object SmsFailed : CodeRequest
    }

    /** Generates a code, sends it by SMS to the trusted contact, and forgets it. */
    @Synchronized
    fun requestCode(context: Context, kind: ExitKind): CodeRequest {
        val r = reconcile(context)
        val session = r.snapshot.session ?: return CodeRequest.Rejected(LockEngine.CodeRequestError.NOT_LOCKED, null)
        return when (val result = engine.requestCode(session, kind, r.trustedNow)) {
            is LockEngine.CodeRequestResult.Rejected -> CodeRequest.Rejected(result.reason, result.retryAt)
            is LockEngine.CodeRequestResult.Issued -> {
                val text = Texts.smsForContact(session.plan.mode, kind, result.plainCode, policy)
                // Only commit the challenge if the SMS actually left; otherwise the
                // user would be stuck waiting for a code nobody received.
                if (!SmsSender.send(context, session.contact.phone, text)) return CodeRequest.SmsFailed
                SessionStore(context.applicationContext).save(r.snapshot.copy(session = result.session))
                reconcile(context)
                CodeRequest.Sent(session.contact, result.session.challenge!!.expiresAt)
            }
        }
    }

    @Synchronized
    fun submitCode(context: Context, code: String): LockEngine.CodeResult? {
        val r = reconcile(context)
        val session = r.snapshot.session ?: return null
        val result = engine.submitCode(session, code, r.trustedNow)
        SessionStore(context.applicationContext).save(r.snapshot.copy(session = result.session))
        sync(context)
        return result
    }
}
