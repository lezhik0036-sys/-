package app.mama.core

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CodecAndContactTest {
    @Test
    fun `snapshot round trips`() {
        val e = engine()
        val now = t("2026-09-23T22:00:00Z")
        val plan = LockPlan(t("2026-09-23T20:00:00Z"), t("2026-09-24T04:00:00Z"), MSK, SessionMode.DETOX)
        val session = (e.requestCode(e.created(plan, now), ExitKind.EMERGENCY, now) as LockEngine.CodeRequestResult.Issued)
            .session.copy(emergencyPassesUsed = 2, codeCooldownUntil = now + Duration.ofMinutes(1))
        val snap = Snapshot(session, ClockAnchor(now, 42, "3"), now)
        assertEquals(snap, StateCodec.decode(StateCodec.encode(snap)))
        assertEquals(Snapshot(), StateCodec.decode(emptyMap()))
    }

    @Test
    fun `corrupt session fails loudly instead of unlocking`() {
        val map = StateCodec.encode(Snapshot(engine().created(
            LockPlan(t("2026-09-23T20:00:00Z"), t("2026-09-24T04:00:00Z"), MSK), t("2026-09-23T22:00:00Z"),
        ))).toMutableMap()
        map.remove("s.end")
        assertFailsWith<IllegalStateException> { StateCodec.decode(map) }
    }

    @Test
    fun `phones are normalized`() {
        assertEquals("+79001234567", TrustedContact.normalizePhone("8 (900) 123-45-67"))
        assertEquals("+79001234567", TrustedContact.normalizePhone("79001234567"))
        assertEquals("+491701234567", TrustedContact.normalizePhone("+49 170 1234567"))
        assertNull(TrustedContact.normalizePhone("12345"))
        assertNull(TrustedContact.normalizePhone("call mom"))
        assertNull(TrustedContact.normalizePhone(""))
    }
}
