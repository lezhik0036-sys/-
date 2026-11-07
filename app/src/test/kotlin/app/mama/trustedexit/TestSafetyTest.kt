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
import app.mama.account.MamaAccount
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

    // The same gate protects the field-test lock: startTest is a real strict lock,
    // so it must not start without a ready exit (no 15-minute trap without a way out).
    @Test
    fun `a test lock does not start when Trusted Exit is not ready`() {
        TrustedExit.readiness = { false }
        assertEquals(Mama.TestStart.TrustedExitUnavailable, Mama.startTest(context, contact, zone, SessionMode.SLEEP))
        assertNull("no session was created", Mama.session(context))
        assertEquals("the phone was not locked", LockState.Free, Mama.state(context))
    }

    @Test
    fun `direct legacy start refuses real-server build without Trusted Exit readiness`() {
        // A legacy entry point must not bypass the series/test preflight even
        // when callers provide their own LockPlan.
        MamaAccount.configure(context, "https://staging.mama.example")
        TrustedExit.readiness = { false }
        try {
            val plan = LockPlan(
                Instant.now().minus(Duration.ofMinutes(1)),
                Instant.now().plus(Duration.ofMinutes(15)), zone,
            )
            assertEquals(
                Mama.StartResult.TrustedExitUnavailable,
                Mama.start(context, plan, contact),
            )
            assertNull("no legacy lock was created", Mama.session(context))
            assertEquals(LockState.Free, Mama.state(context))
        } finally {
            MamaAccount.configure(context, "")
        }
    }

    @Test
    @Config(sdk = [30])
    fun `Android 11 configured server never allows legacy strict lock through fake exit`() {
        // API 30 is unsupported for the real device key, but that must be
        // treated as a closed gate, not as permission to use a debug OTP.
        MamaAccount.configure(context, "https://staging.mama.example")
        TrustedExit.readiness = { false }
        try {
            assertTrue(MamaAccount.realBackendRequested)
            assertEquals(
                Mama.StartResult.TrustedExitUnavailable,
                Mama.start(
                    context,
                    LockPlan(
                        Instant.now().minus(Duration.ofMinutes(1)),
                        Instant.now().plus(Duration.ofMinutes(15)),
                        zone,
                    ),
                    contact,
                ),
            )
            assertNull(Mama.session(context))
        } finally {
            MamaAccount.configure(context, "")
        }
    }

    @Test
    fun `debug local OTP is hidden once real HTTPS backend is requested`() {
        TestCodeSink.record("fake-existing", "54321")
        MamaAccount.configure(context, "https://staging.mama.example")
        try {
            // Even if a previous debug fake request was cached, switching
            // to a configured real backend must never show the test code.
            assertNull(TestCodeSink.codeFor("fake-existing"))
            TestCodeSink.record("fake-new", "12345")
            assertNull(TestCodeSink.codeFor("fake-new"))
        } finally {
            MamaAccount.configure(context, "")
            TestCodeSink.clear()
        }
    }

    // Production gate: with the TEST flag off, no recorded code is ever revealed — a release build shows no TEST CODE.
    @Test
    fun `a production build never reveals the TEST CODE`() {
        TestCodeSink.record("tec-1", "54321")
        assertNull("production (testMode=false) shows nothing", TestCodeSink.visibleCode(testMode = false, challengeId = "tec-1"))
        assertEquals("a test build shows it", "54321", TestCodeSink.visibleCode(testMode = true, challengeId = "tec-1"))
    }
}
