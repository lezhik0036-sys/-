package app.mama.trustedexit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.mama.core.LockPlan
import app.mama.core.LockState
import app.mama.core.SessionMode
import app.mama.core.TrustedContact
import app.mama.platform.Mama
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.time.ZoneId

/**
 * The full app flow of server-authoritative Trusted Exit through [Mama]: the
 * backend owns the code, the device holds only the handle, and none of this
 * depends on a SIM. Robolectric has no SIM, which is exactly the no-SIM case
 * the change is about. The backend here is the on-device fake, driven with a
 * known code and a controllable clock so the outcomes are deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MamaTrustedExitTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val contact = TrustedContact("Мама", "+79001234567")

    private var backendNow = 1_000_000L
    private val code = "54321"

    private fun deviceVault() = DeviceChallengeVault(context)
    private fun newBackend() = FakeTrustedExitBackend(
        vault = deviceVault(),
        clockMs = { backendNow },
        codeSource = { code },
    )

    @Before
    fun setUp() {
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("mama_state", Context.MODE_PRIVATE).edit().clear().commit()
        TrustedExitStore(context).clear()
        backendNow = 1_000_000L
        TrustedExit.online = { true }
        TrustedExit.backend = newBackend()
        Mama.reconcile(context)
        // A session already locked (start a minute ago), so Trusted Exit is available.
        // A start of exactly "now" can fall in the same millisecond as the millisecond-truncated
        // trusted clock and read as Waiting instead of Locked, so start clearly in the past.
        val start = Mama.start(context, LockPlan(Instant.now().minus(Duration.ofMinutes(1)), Instant.now() + Duration.ofHours(1), zone), contact)
        assertTrue(start is Mama.StartResult.Started)
        assertTrue(Mama.state(context) is LockState.Locked)
    }

    @After
    fun tearDown() {
        TrustedExit.online = { Connectivity.online(it) }
        TrustedExit.backend = FakeTrustedExitBackend()
    }

    private fun request() = Mama.requestCode(context, app.mama.core.ExitKind.END_SESSION)

    @Test
    fun `wifi without SIM - request succeeds and stores only a handle`() {
        val r = request()
        assertTrue(r is Mama.CodeRequest.Sent)
        val handle = TrustedExitStore(context).active(Mama.session(context)!!.id)
        assertNotNull(handle)
        assertEquals(3, handle!!.attemptsLeft)
    }

    @Test
    fun `correct code ends the series through the existing mechanism`() {
        request()
        assertEquals(Mama.SubmitResult.Accepted, Mama.submitCode(context, code))
        assertEquals(LockState.Free, Mama.state(context))
        assertNull(TrustedExitStore(context).active("any"))
    }

    @Test
    fun `one, two, three wrong attempts then LOCKED`() {
        request()
        assertEquals(Mama.SubmitResult.Wrong(2), Mama.submitCode(context, "00000"))
        assertEquals(Mama.SubmitResult.Wrong(1), Mama.submitCode(context, "00000"))
        assertTrue(Mama.submitCode(context, "00000") is Mama.SubmitResult.Locked)
        // The correct code no longer works while locked, and the lock still holds.
        assertTrue(Mama.submitCode(context, code) is Mama.SubmitResult.Locked)
        assertTrue(Mama.state(context) is LockState.Locked)
    }

    @Test
    fun `the challenge expires`() {
        request()
        backendNow += Duration.ofMinutes(15).toMillis() + 1
        assertEquals(Mama.SubmitResult.NoActiveCode, Mama.submitCode(context, code))
    }

    @Test
    fun `a second request reuses the challenge, no second code`() {
        request()
        val first = TrustedExitStore(context).active(Mama.session(context)!!.id)!!.challengeId
        request()
        val second = TrustedExitStore(context).active(Mama.session(context)!!.id)!!.challengeId
        assertEquals(first, second)
    }

    @Test
    fun `a used code cannot be submitted twice`() {
        request()
        assertEquals(Mama.SubmitResult.Accepted, Mama.submitCode(context, code))
        // Series is over; nothing to submit against.
        assertEquals(Mama.SubmitResult.NoActiveCode, Mama.submitCode(context, code))
    }

    @Test
    fun `app restart keeps the challenge and the attempt count`() {
        request()
        Mama.submitCode(context, "00000") // attemptsLeft now 2, persisted
        // "Restart": a fresh backend instance over the same device-backed vault, fresh stores.
        TrustedExit.backend = newBackend()
        val handle = TrustedExitStore(context).active(Mama.session(context)!!.id)
        assertNotNull("the handle survives a restart", handle)
        assertEquals(2, handle!!.attemptsLeft)
        // The correct code still verifies against the persisted server challenge.
        assertEquals(Mama.SubmitResult.Accepted, Mama.submitCode(context, code))
    }

    @Test
    fun `offline is fail-closed - no challenge is created`() {
        TrustedExit.online = { false }
        assertEquals(Mama.CodeRequest.Offline, request())
        assertNull(TrustedExitStore(context).active(Mama.session(context)!!.id))
    }

    @Test
    fun `offline submit does not verify`() {
        request()
        TrustedExit.online = { false }
        assertEquals(Mama.SubmitResult.Offline, Mama.submitCode(context, code))
        assertTrue("the lock is untouched", Mama.state(context) is LockState.Locked)
    }

    @Test
    fun `the code never appears in device storage`() {
        request()
        // The app's own handle store holds no code and nothing to check one with.
        // (challengeId is an opaque id, not derived from the code; excluded from the scan.)
        val handle = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("mama_trusted_exit", Context.MODE_PRIVATE).all
        assertFalse(handle.filterKeys { it != "challengeId" }.values.joinToString(" ").contains(code))
        assertFalse(handle.keys.contains("salt"))
        assertFalse(handle.keys.contains("hash"))
        // Even the simulated server keeps only a salted hash — never the plain code.
        val server = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("mama_fake_exit_server_NOT_PRODUCTION_SECURE", Context.MODE_PRIVATE).all
        assertFalse(server.filterKeys { it !in setOf("challengeId", "salt", "hash") }.values.joinToString(" ").contains(code))
        assertFalse((server["hash"] as String?) == code)
        assertFalse((server["salt"] as String?) == code)
    }
}
