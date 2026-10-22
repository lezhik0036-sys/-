package app.mama.trustedexit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.mama.core.DailyWindow
import app.mama.core.ExitKind
import app.mama.core.LockPlan
import app.mama.core.LockState
import app.mama.core.SeriesKind
import app.mama.core.SessionMode
import app.mama.core.TrustedContact
import app.mama.platform.Mama
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * TEST/DEBUG safety net for the field test: the Trusted Exit TEST CODE (a
 * guaranteed way out without real SMS), the readiness check before a series
 * may lock, and the production gate that keeps a release build from ever
 * showing a code. The 15-minute lock cap itself is a core rule
 * ([app.mama.core.CorePolicy.maxLockDuration], covered by LockCapTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestSafetyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val contact = TrustedContact("Мама", "+79001234567")

    private fun clearState() {
        context.createDeviceProtectedStorageContext().getSharedPreferences("mama_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.createDeviceProtectedStorageContext().getSharedPreferences("mama_fake_exit_server_NOT_PRODUCTION_SECURE", Context.MODE_PRIVATE).edit().clear().commit()
        TrustedExitStore(context).clear()
        TestCodeSink.clear()
    }

    @Before
    fun setUp() {
        clearState()
        TrustedExit.online = { true }
        // The TEST build wiring: the simulated delivery stashes the code so the lock screen can show it.
        TrustedExit.backend = FakeTrustedExitBackend(vault = DeviceChallengeVault(context), sms = DebugCodeSmsService)
        Mama.reconcile(context)
    }

    @After
    fun tearDown() {
        TrustedExit.online = { Connectivity.online(it) }
        TrustedExit.backend = FakeTrustedExitBackend()
        TrustedExit.readiness = TrustedExit.defaultReadiness
        TestCodeSink.clear()
    }

    private fun lockNow() {
        val start = Mama.start(context, LockPlan(Instant.now().minus(Duration.ofMinutes(1)), Instant.now() + Duration.ofHours(1), zone), contact)
        assertTrue(start is Mama.StartResult.Started)
        assertTrue(Mama.state(context) is LockState.Locked)
    }

    // The guaranteed exit: a test challenge is issued, its code is shown (captured) and unlocks the device.
    @Test
    fun `the TEST CODE is shown and the correct code ends the lock`() {
        lockNow()
        assertTrue(Mama.requestCode(context, ExitKind.END_SESSION) is Mama.CodeRequest.Sent)
        val handle = TrustedExitStore(context).active(Mama.session(context)!!.id)
        assertNotNull(handle)
        val testCode = TestCodeSink.codeFor(handle!!.challengeId)
        assertNotNull("the TEST CODE is available without a real SMS", testCode)
        assertEquals(5, testCode!!.length)
        assertTrue(testCode.all { it.isDigit() })

        assertEquals(Mama.SubmitResult.Accepted, Mama.submitCode(context, testCode))
        assertEquals(LockState.Free, Mama.state(context))
    }

    // Readiness: the backend can create and verify a challenge.
    @Test
    fun `the backend round-trip readiness probe passes`() {
        assertTrue(TrustedExit.backendCanRoundTrip())
        assertTrue(TrustedExit.ready(context))
    }

    // Readiness gate: if Trusted Exit is not ready, a series must not start and the device is not locked.
    @Test
    fun `a series does not start when Trusted Exit is not ready`() {
        TrustedExit.readiness = { false }
        val window = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), zone)
        assertEquals(Mama.SeriesStart.TrustedExitUnavailable, Mama.startSeries(context, SeriesKind.THREE, window, contact, SessionMode.SLEEP))
        assertNull("no series was scheduled", Mama.series(context))
        assertEquals("the phone was not locked", LockState.Free, Mama.state(context))
    }

    // Production gate: with the TEST flag off, no recorded code is ever revealed — a release build shows no TEST CODE.
    @Test
    fun `a production build never reveals the TEST CODE`() {
        TestCodeSink.record("tec-1", "54321")
        assertNull("production (testMode=false) shows nothing", TestCodeSink.visibleCode(testMode = false, challengeId = "tec-1"))
        assertEquals("a test build shows it", "54321", TestCodeSink.visibleCode(testMode = true, challengeId = "tec-1"))
    }
}
