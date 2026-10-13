package app.mama.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.mama.core.CallObservation
import app.mama.core.Commitment
import app.mama.core.CommitmentState
import app.mama.core.DailyWindow
import app.mama.core.EmergencyNumberCatalog
import app.mama.core.FrontWindow
import app.mama.core.LockPlan
import app.mama.core.LockState
import app.mama.core.OutgoingCallRules
import app.mama.core.Series
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.Session
import app.mama.core.SessionMode
import app.mama.core.SessionStatus
import app.mama.core.Snapshot
import app.mama.core.StateCodec
import app.mama.core.TrustedContact
import app.mama.core.Wallet
import app.mama.core.minuteOfDay
import app.mama.core.plusOnClock
import app.mama.platform.EmergencyCalls
import app.mama.platform.Mama
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Emergency calls through the real platform code: list, dialer, session — and nothing else changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmergencyCallsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: TimeZone = TimeZone.currentSystemDefault()
    private val mom = TrustedContact("Мама", "+79001234567")

    private fun telephony(on: Boolean) {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, on)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING, on)
    }

    /** An ACTIVE 7-day series in its first night, with «Цена слова» 5 000 fixed. */
    private fun activeSeries() {
        val now = Clock.System.now()
        val start = now.minus(1.hours)
        val window = DailyWindow(start.minuteOfDay(zone), start.minuteOfDay(zone).plusOnClock(8.hours), zone)
        val snapshot = Snapshot(
            session = Session("night1", LockPlan(start, now.plus(7.hours), zone), mom, SessionStatus.ACTIVE),
            lastTrusted = now,
            series = Series("s1", SeriesKind.SEVEN, window, mom, SessionMode.SLEEP, startedAt = now.minus(1.days), status = SeriesStatus.ACTIVE, actualStartedAt = start, lastSessionId = null),
            wallet = Wallet(
                availableKop = 5_000_00, reservedKop = 5_000_00,
                commitments = listOf(Commitment("s1", 5_000_00, start, CommitmentState.RESERVED, fixedAt = start)),
            ),
        )
        val store = context.createDeviceProtectedStorageContext().getSharedPreferences("mama_state", Context.MODE_PRIVATE).edit().clear()
        StateCodec.encode(snapshot).forEach { (k, v) -> store.putString(k, v) }
        store.commit()
    }

    @Before
    fun clean() {
        telephony(true)
        EmergencyCalls.clear(context)
    }

    @Test
    fun `emergency services come from the phone, with the Russian pilot list as fallback, 112 first`() {
        val list = EmergencyCalls.services(context) as EmergencyCalls.Availability.Numbers
        assertTrue(list.services.isNotEmpty())
        assertEquals("112", list.services.first().number)
        val russian = EmergencyNumberCatalog.RUSSIA_FALLBACK.map { it.number }
        assertTrue(list.services.all { it.number in russian })
        assertFalse(list.services.any { it.number.length < 3 })
    }

    @Test
    fun `no telephony - no call buttons, a clear message instead`() {
        telephony(false)
        assertEquals(EmergencyCalls.Availability.NoTelephony, EmergencyCalls.services(context))
        assertEquals(EmergencyCalls.Start.NoTelephony, EmergencyCalls.start(context, "112"))
        assertNull(EmergencyCalls.session(context))
    }

    @Test
    fun `112 opens the phone's own dialer with the number entered, and the lock screen steps aside`() {
        assertEquals(EmergencyCalls.Start.Opened, EmergencyCalls.start(context, "112"))
        val intent = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("tel:112", intent.data.toString())
        assertNotNull(EmergencyCalls.session(context))
        assertTrue(EmergencyCalls.overlayMayStepAside(context))
        // Keyboard or a system dialog changes nothing; MAMA's own app is "somewhere else".
        assertEquals(FrontWindow.NEUTRAL, EmergencyCalls.classify(context, "android", null))
        assertEquals(FrontWindow.CALL_FLOW, EmergencyCalls.classify(context, "com.android.server.telecom", null))
        assertEquals(FrontWindow.CALL_FLOW, EmergencyCalls.classify(context, "com.samsung.android.incallui", null))
        assertEquals(FrontWindow.OTHER, EmergencyCalls.classify(context, "com.android.settings", null))
        assertEquals(FrontWindow.OTHER, EmergencyCalls.classify(context, context.packageName, "app.mama.ui.MainActivity"))
    }

    @Test
    fun `a whole emergency call changes nothing about the series, its lock or the commitment`() {
        activeSeries()
        val before = Mama.reconcile(context)
        assertEquals(SeriesStatus.ACTIVE, before.snapshot.series!!.status)
        assertTrue(before.state is LockState.Locked)

        assertEquals(EmergencyCalls.Start.Opened, EmergencyCalls.start(context, "112"))
        assertFalse("call starting", EmergencyCalls.tick(context, CallObservation.ACTIVE, true))
        assertTrue(EmergencyCalls.session(context)!!.callStarted)
        // Going home during the call: the lock comes back, the call goes on.
        EmergencyCalls.onFront(context, "com.sec.android.app.launcher", null)
        assertNotNull(EmergencyCalls.session(context))
        assertFalse(EmergencyCalls.overlayMayStepAside(context))
        assertTrue("call over: lock screen back", EmergencyCalls.tick(context, CallObservation.NONE, false))
        assertNull(EmergencyCalls.session(context))

        val after = Mama.reconcile(context)
        assertEquals(SeriesStatus.ACTIVE, after.snapshot.series!!.status)
        assertTrue("still locked", after.state is LockState.Locked)
        assertEquals(SessionStatus.ACTIVE, after.snapshot.session!!.status)
        assertNull("no failure, no cooldown", after.snapshot.entitlements.standardFailedAt)
        assertEquals(before.snapshot.wallet, after.snapshot.wallet)
        assertEquals(CommitmentState.RESERVED, after.snapshot.wallet.commitmentFor("s1")!!.state)
    }

    // ---------------- call control (Android call redirection) ----------------

    @Test
    fun `during the lock only emergency numbers and the trusted contact may be called`() {
        activeSeries()
        assertTrue(Mama.reconcile(context).state is LockState.Locked)
        assertEquals(OutgoingCallRules.Decision.BLOCK, Mama.outgoingCall(context, "+79991112233", false))
        assertEquals(OutgoingCallRules.Decision.BLOCK, Mama.outgoingCall(context, "89991112233", null))
        assertEquals("trusted contact", OutgoingCallRules.Decision.ALLOW, Mama.outgoingCall(context, "8 (900) 123-45-67", false))
        assertEquals("emergency", OutgoingCallRules.Decision.ALLOW, Mama.outgoingCall(context, "112", true))
    }

    @Test
    fun `without a running lock MAMA does not interfere with calls`() {
        assertEquals(OutgoingCallRules.Decision.ALLOW, Mama.outgoingCall(context, "+79991112233", false))
    }

    @Test
    fun `112 erased in the dialer and another number called - refused, session over, lock screen back, nothing failed`() {
        activeSeries()
        val before = Mama.reconcile(context)
        assertEquals(EmergencyCalls.Start.Opened, EmergencyCalls.start(context, "112"))
        assertTrue(EmergencyCalls.overlayMayStepAside(context))
        // The dialer now asks Android to call an ordinary number: the call control refuses it.
        assertEquals(OutgoingCallRules.Decision.BLOCK, Mama.outgoingCall(context, "+79991112233", false))
        EmergencyCalls.onCallBlocked(context)
        assertNull(EmergencyCalls.session(context))
        assertFalse(EmergencyCalls.overlayMayStepAside(context))
        val after = Mama.reconcile(context)
        assertEquals(SeriesStatus.ACTIVE, after.snapshot.series!!.status)
        assertTrue(after.state is LockState.Locked)
        assertNull(after.snapshot.entitlements.standardFailedAt)
        assertEquals(before.snapshot.wallet, after.snapshot.wallet)
    }
}
