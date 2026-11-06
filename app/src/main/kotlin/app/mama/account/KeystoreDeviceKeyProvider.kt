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
 * Gated to API 33+, where the platform provides Ed25519. On older devices the
 * platform has no Ed25519, so this returns null and the caller keeps Trusted Exit
 * disabled / fail-closed — a known, escalated compatibility limit, never an
 * insecure local-OTP bypass. Any KeyStore/IO error also yields null (fail-closed).
 */
object KeystoreDeviceKeyProvider {
    /** Build.VERSION_CODES.TIRAMISU — first API with platform Ed25519. */
    const val MIN_SDK = 33
    private const val AES_ALIAS = "mama.account.devicekey.aeswrap.v1"
    private const val KEY_FILE = "mama_device_key.v1"

    fun isSupported(): Boolean = isSupported(Build.VERSION.SDK_INT)

    /** Pure, testable form of the platform gate. */
    internal fun isSupported(sdkInt: Int): Boolean = sdkInt >= MIN_SDK

    fun loadOrCreate(context: Context): DeviceKey? {
        if (!isSupported()) return null
        return runCatching {
            val aesKey = AndroidKeystoreAesKey.getOrCreate(AES_ALIAS)
            val file = File(context.filesDir, KEY_FILE)
            if (file.exists()) {
                val lines = file.readText().trim().split("\n")
                val wrapped = Base64.getDecoder().decode(lines[0])
                val rawPublic = Base64.getDecoder().decode(lines[1])
                SoftwareEd25519DeviceKey.restore(AesGcmWrap.unwrap(aesKey, wrapped), rawPublic)
            } else {
                val (key, material) = SoftwareEd25519DeviceKey.generate()
                val body = Base64.getEncoder().encodeToString(AesGcmWrap.wrap(aesKey, material.privatePkcs8)) +
                    "\n" + Base64.getEncoder().encodeToString(material.rawPublic)
                writeAtomically(context, file, body)
                key
            }
        }.getOrNull()
    }

    private fun writeAtomically(context: Context, target: File, body: String) {
        val tmp = File(context.filesDir, "$KEY_FILE.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(target)) {
            target.writeText(body)
            tmp.delete()
        }
    }
}
