package app.mama.account

import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** API 26–32 implementation of the same Ed25519 wire and storage formats. */
internal class LegacyEd25519DeviceKey private constructor(
    private val key: Ed25519PrivateKeyParameters,
) : DeviceKey {
    override fun publicKeyB64(): String =
        Base64.getEncoder().encodeToString(key.generatePublicKey().encoded)

    override fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, key)
        update(message, 0, message.size)
        generateSignature()
    }

    companion object {
        // RFC 8410 PrivateKeyInfo with a nested 32-byte Ed25519 seed.
        private val prefix = byteArrayOf(0x30, 0x2e, 0x02, 0x01, 0, 0x30, 0x05,
            0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20)

        fun generate(): Pair<DeviceKey, SoftwareEd25519DeviceKey.Material> {
            val key = Ed25519PrivateKeyParameters(SecureRandom())
            val seed = key.encoded
            return try {
                LegacyEd25519DeviceKey(key) to SoftwareEd25519DeviceKey.Material(
                    prefix + seed, key.generatePublicKey().encoded,
                )
            } finally {
                seed.fill(0)
            }
        }

        fun restore(encoded: ByteArray, publicKey: ByteArray): DeviceKey {
            require(encoded.size == prefix.size + 32 && prefix.indices.all { encoded[it] == prefix[it] })
            val key = Ed25519PrivateKeyParameters(encoded, prefix.size)
            require(key.generatePublicKey().encoded.contentEquals(publicKey))
            return LegacyEd25519DeviceKey(key)
        }
    }
}
