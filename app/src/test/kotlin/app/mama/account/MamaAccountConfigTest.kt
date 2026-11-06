package app.mama.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The account gate: configured (and so able to attempt a session) ONLY with a
 * backend URL AND a platform that can hold the Ed25519 device key (API 33+). A
 * blank URL or an older device is fail-closed. These assertions exercise only the
 * gate, never the AndroidKeyStore (covered by the crypto unit tests + emulator).
 */
@RunWith(RobolectricTestRunner::class)
class MamaAccountConfigTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test @Config(sdk = [33])
    fun `blank backend url is not configured and yields no session`() {
        MamaAccount.configure(context, "   ")
        assertFalse(MamaAccount.isConfigured)
        assertEquals(MamaAccountClient.Result.Unavailable, MamaAccount.ensureSession())
    }

    @Test @Config(sdk = [33])
    fun `a backend url on api 33 is configured`() {
        MamaAccount.configure(context, "https://staging.mama.example")
        assertTrue(MamaAccount.isConfigured)
    }

    @Test @Config(sdk = [30])
    fun `below api 33 is not configured even with a url`() {
        MamaAccount.configure(context, "https://staging.mama.example")
        assertFalse(MamaAccount.isConfigured)
        assertEquals(MamaAccountClient.Result.Unavailable, MamaAccount.ensureSession())
    }
}
