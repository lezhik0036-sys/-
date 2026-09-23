package app.mama.platform

import android.content.Context
import android.util.Log
import app.mama.core.ClockGuard
import app.mama.core.CorePolicy
import app.mama.core.ExitKind
import app.mama.core.LockEngine
import app.mama.core.LockPlan
import app.mama.core.LockState
import app.mama.core.Reconciliation
import app.mama.core.Recovery
import app.mama.core.Session
import app.mama.core.Snapshot
import app.mama.core.TrustedContact
import app.mama.lock.LockService
import app.mama.ui.Texts
import java.time.Instant
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
        val r = recovery.reconcile(snapshot, DeviceClock.wallNow(), DeviceClock.elapsedMs(), DeviceClock.bootId(app))
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
