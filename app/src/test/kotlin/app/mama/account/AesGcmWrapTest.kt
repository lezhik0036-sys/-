package app.mama.account

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side proof of the at-rest wrapping used for the device private key and the
 * session bearer, with an ordinary AES-256 key standing in for the hardware-backed
 * AndroidKeyStore key: round-trip, fresh IV per call, tamper detection, and an
 * end-to-end wrap of a real device key's PKCS#8 that restores to a working key.
 */
class AesGcmWrapTest {

    private fun aesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun `wrap then unwrap round-trips`() {
        val key = aesKey()
        val plaintext = "device-secret-material".toByteArray(Charsets.UTF_8)
        assertArrayEquals(plaintext, AesGcmWrap.unwrap(key, AesGcmWrap.wrap(key, plaintext)))
    }

    @Test fun `each wrap uses a fresh IV so output differs`() {
        val key = aesKey()
        val plaintext = "same-input".toByteArray(Charsets.UTF_8)
        val a = AesGcmWrap.wrap(key, plaintext)
        val b = AesGcmWrap.wrap(key, plaintext)
        assertFalse("ciphertexts must differ (random IV)", a.contentEquals(b))
    }

    @Test fun `a tampered blob fails authentication`() {
        val key = aesKey()
        val blob = AesGcmWrap.wrap(key, "secret".toByteArray(Charsets.UTF_8))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        var threw = false
        try {
            AesGcmWrap.unwrap(key, blob)
        } catch (e: AEADBadTagException) {
            threw = true
        } catch (e: javax.crypto.BadPaddingException) {
            threw = true
        }
        assertTrue("tampering must be rejected", threw)
    }

    @Test fun `a wrong key cannot unwrap`() {
        val blob = AesGcmWrap.wrap(aesKey(), "secret".toByteArray(Charsets.UTF_8))
        var threw = false
        try {
            AesGcmWrap.unwrap(aesKey(), blob)
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test fun `wrapping a device key's PKCS#8 restores a working key`() {
        val key = aesKey()
        val (original, material) = SoftwareEd25519DeviceKey.generate()
        val wrapped = AesGcmWrap.wrap(key, material.privatePkcs8)
        val restored = SoftwareEd25519DeviceKey.restore(AesGcmWrap.unwrap(key, wrapped), material.rawPublic)
        assertEquals(original.publicKeyB64(), restored.publicKeyB64())
    }
}
