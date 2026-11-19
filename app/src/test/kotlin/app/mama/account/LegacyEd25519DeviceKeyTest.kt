package app.mama.account

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LegacyEd25519DeviceKeyTest {
    @Test
    fun legacyKeySurvivesUpgradeToPlatformEd25519() {
        val (legacy, material) = LegacyEd25519DeviceKey.generate()
        val restored = LegacyEd25519DeviceKey.restore(material.privatePkcs8, material.rawPublic)
        val platform = SoftwareEd25519DeviceKey.restore(material.privatePkcs8, material.rawPublic)
        val message = "MAMA/device-key/interoperability".toByteArray()
        assertArrayEquals(legacy.sign(message), restored.sign(message))
        assertArrayEquals(platform.sign(message), restored.sign(message))
    }
}
