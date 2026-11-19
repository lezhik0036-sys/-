package app.mama.account

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side proof of the production software Ed25519 device key: a freshly
 * generated key signs verifiably, survives a wrap-free round-trip through its
 * stored Material (PKCS#8 + raw public), and produces a signature the server
 * would accept over the exact account canonical message. No Android, no Keystore
 * — the AndroidKeyStore wrapping is covered by AesGcmWrapTest and the emulator.
 */
class SoftwareEd25519DeviceKeyTest {

    private val unb64 = Base64.getDecoder()

    @Test fun `public key is 32 raw bytes and signatures verify`() {
        val (key, _) = SoftwareEd25519DeviceKey.generate()
        val raw = unb64.decode(key.publicKeyB64())
        assertEquals(32, raw.size)
        val message = "hello-mama".toByteArray(Charsets.UTF_8)
        assertTrue(verify(key.publicKeyB64(), key.sign(message), message))
    }

    @Test fun `restore from stored material reproduces the same key`() {
        val (key, material) = SoftwareEd25519DeviceKey.generate()
        val restored = SoftwareEd25519DeviceKey.restore(material.privatePkcs8, material.rawPublic)
        assertEquals(key.publicKeyB64(), restored.publicKeyB64())
        val message = "persisted-across-restart".toByteArray(Charsets.UTF_8)
        // A signature from the restored key verifies against the original public key.
        assertTrue(verify(key.publicKeyB64(), restored.sign(message), message))
    }

    @Test fun `signs the account canonical message so the server would accept it`() {
        val (key, _) = SoftwareEd25519DeviceKey.generate()
        val pub = key.publicKeyB64()
        val message = MamaAccountClient.authMessage(
            MamaAccountClient.PURPOSE_REGISTER, "cid-42", "nonce-xyz-abcdefghij", pub,
        )
        assertTrue(verify(pub, key.sign(message), message))
    }

    @Test fun `two generated keys differ`() {
        val a = SoftwareEd25519DeviceKey.generate().first.publicKeyB64()
        val b = SoftwareEd25519DeviceKey.generate().first.publicKeyB64()
        assertNotEquals(a, b)
    }

    private fun verify(pubB64: String, signature: ByteArray, message: ByteArray): Boolean {
        val spki = SPKI_PREFIX + unb64.decode(pubB64)
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        return Signature.getInstance("Ed25519").run { initVerify(key); update(message); verify(signature) }
    }

    private companion object {
        val SPKI_PREFIX: ByteArray = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
    }
}
