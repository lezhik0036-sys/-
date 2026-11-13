package app.mama.family

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The family composition root is fail-closed: it exposes a real client ONLY with a
 * backend URL AND a platform that can hold the Ed25519 device key. There is no local
 * pairing fallback, so an unconfigured or unsupported build yields no client at all.
 */
@RunWith(RobolectricTestRunner::class)
class MamaFamilyTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @After fun tearDown() = MamaFamily.configure(ctx, "")

    @Test
    @Config(sdk = [34])
    fun `no backend url is fail-closed and yields no client`() {
        MamaFamily.configure(ctx, "")
        assertFalse(MamaFamily.isConfigured)
        assertNull("no local pairing path without a configured server", MamaFamily.client())
    }

    @Test
    @Config(sdk = [30])
    fun `a configured url on an unsupported platform is still fail-closed`() {
        MamaFamily.configure(ctx, "https://api.mama-app.ru")
        assertFalse("API < 33 cannot hold the device key", MamaFamily.isConfigured)
        assertNull("API < 33 must not open a local pairing path", MamaFamily.client())
    }
}
