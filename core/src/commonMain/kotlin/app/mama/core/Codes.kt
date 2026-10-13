package app.mama.core

/** Source of randomness for codes; replaceable in tests. */
interface CodeSource {
    fun nextCode(length: Int): String
    fun nextSalt(): String
}

/** Codes and salts from the platform's cryptographically secure generator. */
class SecureCodeSource : CodeSource {
    override fun nextCode(length: Int): String =
        buildString(length) { repeat(length) { append('0' + uniformDigit()) } }

    override fun nextSalt(): String = secureRandomBytes(16).toHex()

    /** 0–9 with equal chances: bytes 250–255 are drawn again instead of skewing the result. */
    private fun uniformDigit(): Int {
        while (true) {
            val b = secureRandomBytes(1)[0].toInt() and 0xff
            if (b < 250) return b % 10
        }
    }
}

/** Cryptographically secure random bytes (SecureRandom on Android, SecRandomCopyBytes on iOS). */
internal expect fun secureRandomBytes(size: Int): ByteArray

internal object CodeHasher {
    fun hash(salt: String, code: String): String = Sha256.digest("$salt:$code".encodeToByteArray()).toHex()

    /** Constant-time comparison, so timing does not leak how many chars matched. */
    fun matches(salt: String, expectedHash: String, candidate: String): Boolean {
        val actual = hash(salt, candidate)
        if (actual.length != expectedHash.length) return false
        var diff = 0
        for (i in actual.indices) diff = diff or (actual[i].code xor expectedHash[i].code)
        return diff == 0
    }
}

private const val HEX = "0123456789abcdef"

internal fun ByteArray.toHex(): String = buildString(size * 2) {
    for (b in this@toHex) {
        val v = b.toInt() and 0xff
        append(HEX[v ushr 4])
        append(HEX[v and 0x0f])
    }
}

/** SHA-256 (FIPS 180-4) in plain Kotlin, the same on every platform. */
internal object Sha256 {
    private val K = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ).map { it.toInt() }.toIntArray()

    fun digest(message: ByteArray): ByteArray {
        val h = longArrayOf(
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
        ).map { it.toInt() }.toIntArray()
        // Padding: 0x80, zeros, then the message length in bits (big-endian), to a multiple of 64 bytes.
        val paddedLength = (message.size + 9 + 63) / 64 * 64
        val data = message.copyOf(paddedLength)
        data[message.size] = 0x80.toByte()
        val bits = message.size.toLong() * 8
        for (i in 0 until 8) data[paddedLength - 1 - i] = (bits ushr (8 * i)).toByte()

        val w = IntArray(64)
        for (chunk in 0 until paddedLength step 64) {
            for (t in 0 until 16) {
                val j = chunk + t * 4
                w[t] = ((data[j].toInt() and 0xff) shl 24) or ((data[j + 1].toInt() and 0xff) shl 16) or
                    ((data[j + 2].toInt() and 0xff) shl 8) or (data[j + 3].toInt() and 0xff)
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]
            for (t in 0 until 64) {
                val sum1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val choice = (e and f) xor (e.inv() and g)
                val temp1 = hh + sum1 + choice + K[t] + w[t]
                val sum0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val majority = (a and b) xor (a and c) xor (b and c)
                val temp2 = sum0 + majority
                hh = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }
            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh
        }
        return ByteArray(32) { i -> (h[i / 4] ushr (24 - 8 * (i % 4))).toByte() }
    }
}
