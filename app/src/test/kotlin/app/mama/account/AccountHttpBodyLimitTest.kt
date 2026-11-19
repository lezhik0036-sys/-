package app.mama.account

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** No network: oversized / truncated / unusual server streams cannot forge auth. */
class AccountHttpBodyLimitTest {
    @Test fun `normal JSON body is accepted`() {
        val body = """{"challenge_id":"abc","nonce":"xyz"}"""
        assertEquals(body, readBoundedBody(ByteArrayInputStream(body.toByteArray())))
    }

    @Test fun `exact limit is accepted`() {
        val payload = "a".repeat(MAX_ACCOUNT_RESPONSE_BYTES)
        assertEquals(payload, readBoundedBody(ByteArrayInputStream(payload.toByteArray())))
    }

    @Test fun `one byte over limit is rejected`() {
        val payload = ByteArray(MAX_ACCOUNT_RESPONSE_BYTES + 1) { 'x'.code.toByte() }
        try {
            readBoundedBody(ByteArrayInputStream(payload))
            throw AssertionError("oversized response was accepted")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("size limit"))
        }
    }

    @Test fun `huge stream never reads more than limit plus one byte`() {
        var consumed = 0
        val stream = object : InputStream() {
            override fun read(): Int {
                consumed++
                return 'x'.code
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                consumed += len
                b.fill('x'.code.toByte(), off, off + len)
                return len
            }
        }
        try {
            readBoundedBody(stream)
            throw AssertionError("unbounded stream was accepted")
        } catch (_: IOException) {
            assertEquals(MAX_ACCOUNT_RESPONSE_BYTES + 1, consumed)
        }
    }
}
