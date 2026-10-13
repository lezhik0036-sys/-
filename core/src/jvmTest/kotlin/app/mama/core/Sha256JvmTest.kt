package app.mama.core

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared SHA-256 matches the JVM's, so codes hashed by older Android builds still verify. */
class Sha256JvmTest {
    private fun jvm(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    @Test
    fun `same digest as java security for every length around the block boundaries`() {
        val random = Random(42)
        for (size in 0..200) {
            val bytes = random.nextBytes(size)
            assertEquals(jvm(bytes), Sha256.digest(bytes).toHex(), "size $size")
        }
    }

    @Test
    fun `salted code hash is unchanged`() {
        assertEquals(jvm("salt:12345".encodeToByteArray()), CodeHasher.hash("salt", "12345"))
    }
}
