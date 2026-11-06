package app.mama.account

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM wrap/unwrap used to protect the device private key and the server
 * session bearer at rest. The [SecretKey] is a hardware-backed AndroidKeyStore
 * key (see [AndroidKeystoreAesKey]); this helper is pure `javax.crypto`, so it is
 * unit-testable with an ordinary AES key. The output is `iv || ciphertext||tag`;
 * a fresh random IV per call means identical plaintext never wraps the same way,
 * and GCM authentication makes any tampering a decrypt failure (fail-closed).
 */
object AesGcmWrap {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun wrap(key: SecretKey, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        return iv + cipher.doFinal(plaintext)
    }

    /** Inverse of [wrap]; throws (GCM auth / parse) if the blob is tampered or short. */
    fun unwrap(key: SecretKey, blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "wrapped blob too short" }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }
}
