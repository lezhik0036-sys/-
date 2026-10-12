package app.mama.platform

import android.content.Context
import android.util.Log
import app.mama.core.ClockGuard
import app.mama.core.CorePolicy
import app.mama.core.DailyWindow
import app.mama.core.Entitlements
import app.mama.core.FlexPackage
import app.mama.core.LedgerType
import app.mama.core.Wallet
import app.mama.core.WalletEngine
import app.mama.core.ExitKind
import app.mama.core.FlexEngine
import app.mama.core.GrantSource
import app.mama.core.LockEngine
import app.mama.core.LockPlan
import app.mama.core.LockState
import app.mama.core.Reconciliation
import app.mama.core.Recovery
import app.mama.core.OutgoingCallRules
import app.mama.core.Series
import app.mama.core.SeriesFlow
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.Session
import app.mama.core.SessionMode
import app.mama.core.StandardRules
import app.mama.core.Snapshot
import app.mama.core.TestMode
import app.mama.core.TrustedContact
import app.mama.billing.FeatureFlags
import app.mama.lock.LockService
import app.mama.ui.Requirement
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
    private val flow = SeriesFlow(engine, policy)
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
        r = foldSeries(app, r)
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
        // "Цена слова": credit due partial returns once.
        val before = r.snapshot.wallet
        val wallet = WalletEngine.processDue(before, r.trustedNow)
        if (wallet != r.snapshot.wallet) r = r.copy(snapshot = r.snapshot.copy(wallet = wallet))
        store.save(r.snapshot)
        cached = r
        if (wallet != before) Notifications.balanceReturn(app, wallet.availableKop - before.availableKop)
        return r
    }

    /**
     * Standard series: a started first lock makes it ACTIVATING; ACTIVATING
     * gives up (NEEDS_ATTENTION) when a mandatory permission is off or the
     * activation timeout passed; once ACTIVE, a finished night counts, the
     * next one is planned, or the series fails. Everything that follows
     * (wallet, 30-day wait, a pending Restart) is settled in the same state.
     */
    private fun foldSeries(app: Context, r: Reconciliation): Reconciliation {
        val before = r.snapshot
        val now = r.trustedNow
        var snap = before
        val unlocked = Protection.userUnlocked(app)
        if (snap.series?.status == SeriesStatus.ACTIVATING && !unlocked) snap = flow.awaitUnlock(snap, now)
        snap = flow.fold(snap, UUID.randomUUID().toString(), now)
        if (snap.series?.status == SeriesStatus.ACTIVATING && unlocked) {
            val off = Protection.missingConfiguration(app)
            if (off.isNotEmpty()) {
                Log.w(TAG, "Strict protection cannot start, switched off: $off")
                snap = flow.protectionFailed(snap, now) ?: snap
            }
        }
        if (snap == before) return r
        val series = snap.series
        if (series != null && series != before.series) {
            if (series.status == SeriesStatus.COMPLETED || series.status == SeriesStatus.BROKEN) History.record(app, series)
            if (series.status == SeriesStatus.NEEDS_ATTENTION && before.series?.status != SeriesStatus.NEEDS_ATTENTION) {
                Log.w(TAG, "Series ${series.id} did not start: protection could not be switched on")
                Notifications.seriesNotStarted(app)
            }
        }
        return r.copy(snapshot = snap, state = engine.stateOf(snap.session, now))
    }

    /** Reconciles and makes the device match: alarm for the next change, lock service on/off. */
    @Synchronized
    fun sync(context: Context): LockState {
        val r = reconcile(context)
        Alarms.schedule(context, nextWake(r), r.trustedNow)
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
        Alarms.schedule(context, nextWake(r), r.trustedNow)
        return r
    }

    /** Next lock change, activation deadline or pending "Цена слова" return, whichever comes first. */
    private fun nextWake(r: Reconciliation) = listOfNotNull(
        r.state.nextChangeAt,
        r.snapshot.series?.activationDeadline(policy),
        r.snapshot.wallet.nextReturnDue,
    ).minOrNull()

    fun wallet(context: Context): Wallet = (cached ?: reconcile(context)).snapshot.wallet

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

    /** Nothing running: no scheduled or active series, no FLEX period planned, no session. */
    private fun idle(r: Reconciliation) =
        r.state == LockState.Free && r.snapshot.series?.inProgress != true &&
            r.snapshot.entitlements.flex?.currentSessionId == null

    sealed interface SeriesStart {
        data object Started : SeriesStart
        data object Busy : SeriesStart

        /** After a failure: free start only after the 30-day wait (or pay for Restart). */
        data class Waiting(val days: Long) : SeriesStart

        /** Not enough internal balance for the chosen "Цена слова" (or the Restart from balance). */
        data class NotEnoughBalance(val availableKop: Long) : SeriesStart
        data object Rejected : SeriesStart
    }

    /**
     * Schedules a free standard series ("Начать серию"). Tonight's window is
     * locked automatically; the series becomes binding only when that lock
     * has started and protection is confirmed ([confirmProtection]).
     * [commitmentKop] ("Цена слова", optional) is set aside from the internal
     * balance in the same save as the series, so they cannot get out of step.
     */
    @Synchronized
    fun startSeries(
        context: Context,
        kind: SeriesKind,
        window: DailyWindow,
        contact: TrustedContact,
        mode: SessionMode,
        commitmentKop: Long? = null,
    ): SeriesStart {
        val r = reconcile(context)
        if (!idle(r)) return SeriesStart.Busy
        val ent = r.snapshot.entitlements
        if (!StandardRules.canStartFree(ent, r.trustedNow)) {
            return SeriesStart.Waiting(StandardRules.daysUntilFreeStart(ent, r.trustedNow))
        }
        val result = flow.schedule(
            r.snapshot, UUID.randomUUID().toString(), UUID.randomUUID().toString(), kind, window, contact, mode, commitmentKop, r.trustedNow,
        )
        return saveStart(context, result)
    }

    /**
     * Restart of a failed series with the same settings. Call only after the
     * Restart was granted (paid, a test grant, or [GrantSource.INTERNAL_BALANCE],
     * in which case the Restart price is held from the balance here until the
     * new series becomes ACTIVE). The new series is SCHEDULED; cancelling it
     * before ACTIVE gives the held Restart back and restores the failure.
     * "Цена слова" is asked again and is never added to the Restart price.
     */
    @Synchronized
    fun restartSeries(context: Context, source: GrantSource, commitmentKop: Long? = null): SeriesStart {
        val r = reconcile(context)
        if (r.snapshot.series?.status != SeriesStatus.BROKEN || r.state != LockState.Free) return SeriesStart.Rejected
        Log.i(TAG, "Restart of ${r.snapshot.series?.kind} granted: $source")
        val result = flow.restart(r.snapshot, source, UUID.randomUUID().toString(), UUID.randomUUID().toString(), commitmentKop, r.trustedNow)
        return saveStart(context, result)
    }

    private fun saveStart(context: Context, result: SeriesFlow.Result): SeriesStart = when (result) {
        is SeriesFlow.Result.Done -> {
            SessionStore(context.applicationContext).save(result.snapshot)
            sync(context)
            SeriesStart.Started
        }
        is SeriesFlow.Result.NotEnoughBalance -> SeriesStart.NotEnoughBalance(result.availableKop)
        SeriesFlow.Result.NotAllowed, SeriesFlow.Result.Rejected -> SeriesStart.Rejected
    }

    /**
     * The commitment point, called by the lock service on every tick while a
     * lock runs. Only when strictProtectionReady holds does an ACTIVATING
     * series become ACTIVE, and in the same save its conditions, "Цена слова"
     * and a held Restart are fixed. Runs under the same lock as every other
     * transition, on freshly loaded state: racing with "Отменить запуск" it
     * ends in exactly one of ACTIVE or CANCELLED_BEFORE_START.
     */
    @Synchronized
    fun confirmProtection(context: Context, lockScreenShown: Boolean, lockServiceInForeground: Boolean): Boolean {
        if ((cached ?: reconcile(context)).snapshot.series?.status != SeriesStatus.ACTIVATING) return false
        if (!Protection.strictProtectionReady(context, lockScreenShown, lockServiceInForeground)) return false
        val r = reconcile(context)
        val next = flow.confirm(r.snapshot, r.trustedNow) ?: return false
        SessionStore(context.applicationContext).save(next)
        reconcile(context)
        Log.i(TAG, "Series ${next.series?.id} is ACTIVE: strict protection ready")
        return true
    }

    sealed interface SeriesEdit {
        data object Saved : SeriesEdit

        /** The series has begun (or is over): its conditions are fixed. */
        data object NotAllowed : SeriesEdit
        data class NotEnoughBalance(val availableKop: Long) : SeriesEdit

        /** A new launch after NEEDS_ATTENTION needs the strict protection fixed first. */
        data class ProtectionMissing(val missing: List<Requirement>) : SeriesEdit
        data object Rejected : SeriesEdit
    }

    /**
     * "Изменить условия" before ACTIVE, free: trusted contact and "Цена слова"
     * ([commitmentKop] null = without); the reason is stored by the UI. The
     * series length and its daily window are fixed once the series is
     * confirmed: another time means "Отменить запуск" and a new series. One
     * save; nothing is lost and no failure rule applies.
     */
    @Synchronized
    fun editScheduledSeries(context: Context, contact: TrustedContact, commitmentKop: Long?): SeriesEdit {
        val r = reconcile(context)
        return when (val result = flow.edit(r.snapshot, contact, commitmentKop, r.trustedNow)) {
            is SeriesFlow.Result.Done -> {
                SessionStore(context.applicationContext).save(result.snapshot)
                sync(context)
                SeriesEdit.Saved
            }
            is SeriesFlow.Result.NotEnoughBalance -> SeriesEdit.NotEnoughBalance(result.availableKop)
            SeriesFlow.Result.NotAllowed -> SeriesEdit.NotAllowed
            SeriesFlow.Result.Rejected -> SeriesEdit.Rejected
        }
    }

    /**
     * After NEEDS_ATTENTION, once the protection is fixed: the user's explicit
     * new launch with the same conditions (the next window is planned).
     */
    @Synchronized
    fun relaunchSeries(context: Context): SeriesEdit {
        val r = reconcile(context)
        Protection.missing(context).takeIf { it.isNotEmpty() }?.let { return SeriesEdit.ProtectionMissing(it) }
        return when (val result = flow.reschedule(r.snapshot, UUID.randomUUID().toString(), r.trustedNow)) {
            is SeriesFlow.Result.Done -> {
                SessionStore(context.applicationContext).save(result.snapshot)
                sync(context)
                SeriesEdit.Saved
            }
            SeriesFlow.Result.NotAllowed -> SeriesEdit.NotAllowed
            else -> SeriesEdit.Rejected
        }
    }

    /**
     * "Отменить запуск": cancels a series that has not become ACTIVE. No
     * failure, no Restart, no new waiting period; the scheduled "Цена слова"
     * and a held Restart go back to the balance in full. A cancelled Restart
     * brings back the failed series it replaced. One save.
     */
    @Synchronized
    fun cancelScheduledSeries(context: Context): Boolean {
        val r = reconcile(context)
        val cancelled = flow.cancel(r.snapshot, r.trustedNow) ?: return false
        SessionStore(context.applicationContext).save(cancelled.snapshot)
        sync(context)
        Log.i(TAG, "Series ${cancelled.cancelled.id} cancelled before start")
        return true
    }

    /** Pilot: adds test credits to the internal balance (not money). */
    @Synchronized
    fun topUpTest(context: Context, amountKop: Long): Boolean {
        if (!FeatureFlags.WALLET_TEST_TOPUP_ENABLED) return false
        val r = reconcile(context)
        val res = WalletEngine.topUpTest(r.snapshot.wallet, amountKop, "topup-" + UUID.randomUUID(), r.trustedNow)
        if (res !is WalletEngine.Result.Ok) return false
        SessionStore(context.applicationContext).save(r.snapshot.copy(wallet = res.wallet))
        reconcile(context)
        return true
    }

    /** The user has seen the "part of the amount came back" message. */
    @Synchronized
    fun markReturnsSeen(context: Context) {
        val r = reconcile(context)
        val w = WalletEngine.markReturnsSeen(r.snapshot.wallet)
        if (w == r.snapshot.wallet) return
        SessionStore(context.applicationContext).save(r.snapshot.copy(wallet = w))
        reconcile(context)
    }

    /** Forgets a finished (completed or failed) series; the failure date is kept. */
    @Synchronized
    fun dismissSeries(context: Context) {
        val r = reconcile(context)
        if (r.snapshot.series?.inProgress == true) return
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
        val id = UUID.randomUUID().toString()
        var wallet = r.snapshot.wallet
        if (source == GrantSource.INTERNAL_BALANCE) {
            val paid = WalletEngine.spend(wallet, FlexPackage.PRICE_RUB * 100L, LedgerType.FLEX_PURCHASE_TEST, "flex-$id", r.trustedNow, flexId = id)
            wallet = (paid as? WalletEngine.Result.Ok)?.wallet ?: return false
        }
        ent = ent.copy(flex = flexEngine.activate(id, source, r.trustedNow))
        SessionStore(context.applicationContext).save(r.snapshot.copy(entitlements = ent, wallet = wallet))
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

    sealed interface TestStart {
        data class Started(val sessionId: String) : TestStart
        data object Busy : TestStart
        data class Rejected(val reason: LockEngine.CreateError) : TestStart
    }

    /**
     * Free 15-minute test lock, starting right now. Only when nothing else is
     * running: no series, no FLEX period, no session.
     */
    @Synchronized
    fun startTest(context: Context, contact: TrustedContact, zone: ZoneId, mode: SessionMode): TestStart {
        if (!FeatureFlags.TEST_MODE_ENABLED) return TestStart.Busy
        val r = reconcile(context)
        if (!idle(r)) return TestStart.Busy
        val id = UUID.randomUUID().toString()
        return when (val created = engine.create(id, TestMode.plan(r.trustedNow, zone, mode), contact, r.trustedNow)) {
            is LockEngine.CreateResult.Rejected -> TestStart.Rejected(created.reason)
            is LockEngine.CreateResult.Created -> {
                SessionStore(context.applicationContext).save(r.snapshot.copy(session = created.session))
                sync(context)
                TestStart.Started(id)
            }
        }
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

    /** Test lock or FLEX period before it starts. A series uses [cancelScheduledSeries]. */
    @Synchronized
    fun cancelBeforeStart(context: Context): Boolean {
        val r = reconcile(context)
        if (r.snapshot.series?.inProgress == true) return false
        val session = r.snapshot.session ?: return false
        val result = engine.cancelBeforeStart(session, r.trustedNow)
        if (result !is LockEngine.CancelResult.Cancelled) return false
        SessionStore(context.applicationContext).save(r.snapshot.copy(session = result.session))
        sync(context)
        return true
    }

    /**
     * Call control: may this outgoing call go ahead? Only during the strict
     * lock are calls limited, to emergency numbers and the lock's trusted
     * contact ([OutgoingCallRules]).
     */
    @Synchronized
    fun outgoingCall(context: Context, number: String, isEmergency: Boolean?): OutgoingCallRules.Decision {
        val r = reconcile(context)
        return OutgoingCallRules.decide(r.state is LockState.Locked, number, isEmergency, r.snapshot.session?.contact)
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
