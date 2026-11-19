package app.mama.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceKeyPersistenceTest {
    @Test
    fun createsAndRestoresSameIdentityFromAndroidKeystore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val message = "MAMA/account/persistence".toByteArray()
        val first = checkNotNull(KeystoreDeviceKeyProvider.loadOrCreate(context))
        val second = checkNotNull(KeystoreDeviceKeyProvider.loadOrCreate(context))
        assertEquals(first.publicKeyB64(), second.publicKeyB64())
        assertArrayEquals(first.sign(message), second.sign(message))
    }
}
