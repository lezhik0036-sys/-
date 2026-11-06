package app.mama.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A hardware-backed (TEE/StrongBox where available) AES-256-GCM key kept in the
 * AndroidKeyStore under [alias]. The key is non-exportable; it only ever wraps and
 * unwraps the device private key and the session bearer on-device. Android-only,
 * so it is not exercised by host unit tests (the wrap/unwrap logic is tested
 * separately with an ordinary AES key); the emulator CI covers it once wired.
 */
object AndroidKeystoreAesKey {
    private const val PROVIDER = "AndroidKeyStore"

    fun getOrCreate(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
