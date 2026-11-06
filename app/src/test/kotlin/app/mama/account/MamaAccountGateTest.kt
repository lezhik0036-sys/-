package app.mama.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account gate, as pure logic (no Robolectric, Context, or SDK download):
 * a session may be attempted ONLY with a backend URL AND a platform that can hold
 * the Ed25519 device key (API 33+). A blank URL or an older device is fail-closed.
 */
class MamaAccountGateTest {

    @Test fun `platform gate requires api 33 or newer`() {
        assertFalse(KeystoreDeviceKeyProvider.isSupported(26))
        assertFalse(KeystoreDeviceKeyProvider.isSupported(32))
        assertTrue(KeystoreDeviceKeyProvider.isSupported(33))
        assertTrue(KeystoreDeviceKeyProvider.isSupported(34))
    }

    @Test fun `configured needs both a url and a supported platform`() {
        assertFalse("blank url", MamaAccount.configured("", 34))
        assertFalse("whitespace url", MamaAccount.configured("   ", 34))
        assertFalse("url but api below 33", MamaAccount.configured("https://staging.mama.example", 30))
        assertTrue("url on api 33", MamaAccount.configured("https://staging.mama.example", 33))
    }
}
