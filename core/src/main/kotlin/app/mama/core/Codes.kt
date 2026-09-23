package app.mama.core

import java.security.MessageDigest
import java.security.SecureRandom

/** Source of randomness for codes; replaceable in tests. */
interface CodeSource {
    fun nextCode(length: Int): String
    fun nextSalt(): String
}

class SecureCodeSource(private val random: SecureRandom = SecureRandom()) : CodeSource {
    override fun nextCode(length: Int): String =
        buildString(length) { repeat(length) { append('0' + random.nextInt(10)) } }

    override fun nextSalt(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.toHex()
    }
}

internal object CodeHasher {
    fun hash(salt: String, code: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$salt:$code".toByteArray(Charsets.UTF_8))
            .toHex()

    /** Constant-time comparison, so timing does not leak how many chars matched. */
    fun matches(salt: String, expectedHash: String, candidate: String): Boolean =
        MessageDigest.isEqual(
            hash(salt, candidate).toByteArray(Charsets.US_ASCII),
            expectedHash.toByteArray(Charsets.US_ASCII),
        )
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
