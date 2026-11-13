package app.mama.account

import android.content.Context
import android.os.Build
import java.io.File
import java.util.Base64

/**
 * Supplies the production [DeviceKey]: a software Ed25519 key whose private PKCS#8
 * is wrapped by a hardware-backed AndroidKeyStore AES key ([AndroidKeystoreAesKey])
 * and stored in app-private files. The private key is never written in the clear
 * and never leaves the device.
 *
 * API 33+ uses the platform Ed25519 implementation. Older devices use a
 * bundled Ed25519 implementation without registering a JCA provider.
 * Keystore, decoding or storage errors leave account operations unavailable.
 */
object KeystoreDeviceKeyProvider {
    const val MIN_SDK = 26
    private const val AES_ALIAS = "mama.account.devicekey.aeswrap.v1"
    private const val KEY_FILE = "mama_device_key.v1"

    fun isSupported(): Boolean = isSupported(Build.VERSION.SDK_INT)

    /** Pure, testable form of the platform gate. */
    internal fun isSupported(sdkInt: Int): Boolean = sdkInt >= MIN_SDK

    @Synchronized
    fun loadOrCreate(context: Context): DeviceKey? {
        if (!isSupported()) return null
        return runCatching {
            val aesKey = AndroidKeystoreAesKey.getOrCreate(AES_ALIAS)
            val file = File(context.filesDir, KEY_FILE)
            if (file.exists()) {
                val lines = file.readText().trim().split("\n")
                val wrapped = Base64.getDecoder().decode(lines[0])
                val rawPublic = Base64.getDecoder().decode(lines[1])
                val privateBytes = AesGcmWrap.unwrap(aesKey, wrapped)
                try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        SoftwareEd25519DeviceKey.restore(privateBytes, rawPublic)
                    } else {
                        LegacyEd25519DeviceKey.restore(privateBytes, rawPublic)
                    }
                } finally {
                    privateBytes.fill(0)
                }
            } else {
                val (key, material) = if (Build.VERSION.SDK_INT >= 33) {
                    SoftwareEd25519DeviceKey.generate()
                } else {
                    LegacyEd25519DeviceKey.generate()
                }
                val wrapped = try {
                    AesGcmWrap.wrap(aesKey, material.privatePkcs8)
                } finally {
                    material.privatePkcs8.fill(0)
                }
                val body = Base64.getEncoder().encodeToString(wrapped) +
                    "\n" + Base64.getEncoder().encodeToString(material.rawPublic)
                writeAtomically(context, file, body)
                key
            }
        }.onFailure { error ->
            android.util.Log.w("MamaDeviceKey", "Device identity unavailable: ${error.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun writeAtomically(context: Context, target: File, body: String) {
        val tmp = File(context.filesDir, "$KEY_FILE.tmp")
        tmp.writeText(body)
        try {
            check(tmp.renameTo(target)) { "Cannot persist device key atomically" }
        } finally {
            tmp.delete()
        }
    }
}
