package app.mama.core

import app.mama.core.OutgoingCallRules.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutgoingCallRulesTest {
    private val mom = TrustedContact("Мама", "+79001234567")

    @Test
    fun `locked - the trusted contact may be called however the number is written`() {
        for (written in listOf("+79001234567", "+7 900 123-45-67", "8 (900) 123-45-67", "89001234567", "79001234567", "9001234567")) {
            assertEquals(Decision.ALLOW, OutgoingCallRules.decide(locked = true, written, isEmergency = false, mom), written)
        }
    }

    @Test
    fun `locked - any other number is cancelled - also the one typed after erasing 112 in the dialer`() {
        for (other in listOf("+79991112233", "89991112233", "9001234568", "1234", "*31#89001234567", "+19001234567")) {
            assertEquals(Decision.BLOCK, OutgoingCallRules.decide(locked = true, other, isEmergency = false, mom), other)
        }
        assertEquals(Decision.BLOCK, OutgoingCallRules.decide(locked = true, "+79991112233", isEmergency = null, mom), "unknown is not emergency")
        assertEquals(Decision.BLOCK, OutgoingCallRules.decide(locked = true, "+79991112233", isEmergency = false, null))
    }

    @Test
    fun `emergency numbers are never blocked`() {
        for (n in listOf("112", "101", "102", "103", "104", "911")) {
            assertEquals(Decision.ALLOW, OutgoingCallRules.decide(locked = true, n, isEmergency = true, mom), n)
        }
    }

    @Test
    fun `not locked - MAMA does not interfere with any call`() {
        assertEquals(Decision.ALLOW, OutgoingCallRules.decide(locked = false, "+79991112233", isEmergency = false, mom))
        assertEquals(Decision.ALLOW, OutgoingCallRules.decide(locked = false, "1234", isEmergency = null, null))
    }

    @Test
    fun `number comparison is exact about the number itself`() {
        assertTrue(OutgoingCallRules.samePhone("8-900-123-45-67", "+79001234567"))
        assertFalse(OutgoingCallRules.samePhone("8-900-123-45-6", "+79001234567"))
        assertFalse(OutgoingCallRules.samePhone("900123456", "+79001234567"))
        assertFalse(OutgoingCallRules.samePhone("", "+79001234567"))
    }
}
