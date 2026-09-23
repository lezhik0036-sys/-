package app.mama.core

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecoveryTest {
    private val start = t("2026-09-23T20:00:00Z")
    private val end = t("2026-09-24T04:00:00Z")
    private val plan = LockPlan(start, end, MSK)
    private val e = engine()
    private val recovery = Recovery(e)

    private fun lockedSnapshot(now: java.time.Instant, elapsed: Long, boot: String): Snapshot {
        val base = recovery.reconcile(Snapshot(), now, elapsed, boot).snapshot
        return base.copy(session = e.created(plan, now))
    }

    @Test
    fun `moving the clock forward within a boot does not unlock`() {
        val now = t("2026-09-23T22:00:00Z")
        val snap = lockedSnapshot(now, elapsed = 1_000, boot = "7")
        // One minute later, but the user set the clock to 10:00 next day.
        val r = recovery.reconcile(snap, t("2026-09-24T10:00:00Z"), 61_000, "7")
        assertEquals(now + Duration.ofMinutes(1), r.trustedNow)
        assertTrue(r.state.isLocked)
    }

    @Test
    fun `reboot during lock restores the lock`() {
        val now = t("2026-09-23T22:00:00Z")
        val snap = recovery.reconcile(lockedSnapshot(now, 1_000, "7"), now, 1_000, "7").snapshot
        // Serialize like the app does, reboot (new boot id, elapsed restarts).
        val restored = StateCodec.decode(StateCodec.encode(snap))
        val afterBoot = now + Duration.ofMinutes(2)
        val r = recovery.reconcile(restored, afterBoot, 30_000, "8")
        assertEquals(LockState.Locked(end), r.state)
        assertEquals("8", r.snapshot.anchor!!.bootId)
    }

    @Test
    fun `clock set back across reboot cannot go before last trusted time`() {
        val now = t("2026-09-23T22:00:00Z")
        val snap = recovery.reconcile(lockedSnapshot(now, 1_000, "7"), now, 1_000, "7").snapshot
        val r = recovery.reconcile(snap, t("2020-01-01T00:00:00Z"), 5_000, "8")
        assertEquals(now, r.trustedNow)
        assertTrue(r.state.isLocked)
    }

    @Test
    fun `phone off past the end finishes the session`() {
        val now = t("2026-09-23T22:00:00Z")
        val snap = lockedSnapshot(now, 1_000, "7")
        val r = recovery.reconcile(snap, end + Duration.ofHours(1), 5_000, "8")
        assertEquals(LockState.Free, r.state)
        assertEquals(FinishReason.COMPLETED, r.snapshot.session!!.finishReason)
    }

    @Test
    fun `scheduled session starts on the first reconcile after start`() {
        val before = t("2026-09-23T19:00:00Z")
        val snap = lockedSnapshot(before, 1_000, "7")
        val r = recovery.reconcile(snap, start, 1_000 + 3_600_000, "7")
        assertEquals(LockState.Locked(end), r.state)
    }
}
