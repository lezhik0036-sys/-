package app.mama.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.mama.trustedexit.TrustedExitReadinessGate

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

    @Test fun `dropping session revokes cached BRICK readiness even without a client`() {
        // The device-key session might be revoked/expired while a cached
        // server-readiness observation is still within its two-minute TTL.
        TrustedExitReadinessGate.clear()
        TrustedExitReadinessGate.record(true, 10_000L)
        assertTrue(TrustedExitReadinessGate.ready(10_000L))
        try {
            MamaAccount.forgetSession()
            assertFalse(TrustedExitReadinessGate.ready(10_000L))
        } finally {
            TrustedExitReadinessGate.clear()
        }
    }

    @Test fun `requested real backend never becomes debug fake on old Android`() {
        // Android 8-12 can be unsupported for real Ed25519 account keys,
        // but explicit backend selection must still mean fail CLOSED.
        assertFalse(MamaAccount.realBackendRequested(""))
        assertFalse(MamaAccount.realBackendRequested("   "))
        assertTrue(MamaAccount.realBackendRequested("https://staging.mama.example"))
        for (sdk in 26..32) {
            assertFalse(MamaAccount.configured("https://staging.mama.example", sdk))
            assertTrue(MamaAccount.realBackendRequested("https://staging.mama.example"))
        }
    }

    @Test fun `configured needs both a url and a supported platform`() {
        assertFalse("blank url", MamaAccount.configured("", 34))
        assertFalse("whitespace url", MamaAccount.configured("   ", 34))
        assertFalse("url but api below 33", MamaAccount.configured("https://staging.mama.example", 30))
        assertTrue("url on api 33", MamaAccount.configured("https://staging.mama.example", 33))
    }
}
