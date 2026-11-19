package app.mama.account

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * A [DeviceKey] backed by a platform Ed25519 key (`java.security`, available on
 * Android API 33+ and the host test JVM). This is **software** Ed25519 — honest
 * about not being a hardware-non-exportable key: the private key material exists
 * as PKCS#8 bytes that are only ever persisted WRAPPED by a hardware-backed
 * AndroidKeyStore AES key (see [KeystoreDeviceKeyProvider]). This class itself
 * never persists anything, so it is fully unit-testable with an ephemeral key.
 */
class SoftwareEd25519DeviceKey internal constructor(
    private val privateKey: PrivateKey,
    private val rawPublic: ByteArray,
) : DeviceKey {

    override fun publicKeyB64(): String = Base64.getEncoder().encodeToString(rawPublic)

    override fun sign(message: ByteArray): ByteArray =
        Signature.getInstance(ALGORITHM).run {
            initSign(privateKey)
            update(message)
            sign()
        }

    /** The at-rest secret (PKCS#8 private key) and the public key to persist alongside it. */
    class Material(val privatePkcs8: ByteArray, val rawPublic: ByteArray)

    companion object {
        const val ALGORITHM = "Ed25519"

        /** Fresh keypair; [Material] is what the caller wraps + stores. The curve is
         * fixed by the "Ed25519" algorithm name, so no key-size initialization is
         * needed (and passing one can throw on some providers). */
        fun generate(): Pair<SoftwareEd25519DeviceKey, Material> {
            val pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
            val raw = rawPublicOf(pair.public.encoded)
            return SoftwareEd25519DeviceKey(pair.private, raw) to
                Material(pair.private.encoded, raw)
        }

        /** Rebuild from previously stored [Material] (unwrapped PKCS#8 + raw public). */
        fun restore(privatePkcs8: ByteArray, rawPublic: ByteArray): SoftwareEd25519DeviceKey {
            require(rawPublic.size == 32) { "Ed25519 public key must be 32 bytes" }
            val privateKey = KeyFactory.getInstance(ALGORITHM)
                .generatePrivate(PKCS8EncodedKeySpec(privatePkcs8))
            return SoftwareEd25519DeviceKey(privateKey, rawPublic.copyOf())
        }

        /** The 32 raw public-key bytes from an X.509 SubjectPublicKeyInfo (12-byte prefix). */
        private fun rawPublicOf(x509: ByteArray): ByteArray {
            require(x509.size >= 32) { "unexpected Ed25519 public encoding" }
            return x509.copyOfRange(x509.size - 32, x509.size)
        }
    }
}
