package app.mama.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The iPhone app's view of the shared rules: same behaviour as Android, primitive types. */
class MamaBridgeTest {
    private val bridge = MamaBridge
    private val msk = "Europe/Moscow"
    private val empty = emptyMap<String, String>()

    private fun ms(iso: String) = t(iso).toEpochMilliseconds()

    private fun scheduled(autoNow: Boolean, start: Int, end: Int, nowIso: String, days: Int = 7): Map<String, String> {
        val r = bridge.schedule(empty, days, autoNow, start, end, msk, "Мама", "8 900 123-45-67", ms(nowIso), "s1", "n1")
        assertNull(r.error)
        return r.state!!
    }

    @Test
    fun `series lengths and Restart prices are the shared ones`() {
        assertEquals(listOf(3, 5, 7), bridge.seriesDays)
        assertEquals(299, bridge.restartPriceRub(7))
        assertEquals("+79001234567", bridge.normalizePhone("8 (900) 123-45-67"))
    }

    @Test
    fun `a new draft starts at the current minute, 8 hours long`() {
        assertEquals(DraftTime(16 * 60 + 48, 48), bridge.freshDraft(ms("2026-10-12T13:48:30Z"), msk))
    }

    @Test
    fun `AUTO_NOW confirmed at 16_53 - starts now for 8 hours, the last of 7 nights six days later`() {
        val now = ms("2026-10-12T13:53:20Z")
        val launch = bridge.resolve(autoNow = true, startMinute = 16 * 60 + 48, endMinute = 48, zoneId = msk, days = 7, nowMs = now)!!
        assertEquals(16 * 60 + 53, launch.startMinute)
        assertEquals(53, launch.endMinute)
        assertTrue(launch.startsNow)
        assertEquals(now, launch.firstLockStartMs)
        assertEquals(now + 8.hours.inWholeMilliseconds, launch.firstLockEndMs)
        assertEquals(launch.firstLockEndMs + 6.days.inWholeMilliseconds, launch.lastLockEndMs)
    }

    @Test
    fun `MANUAL 18_00 confirmed at 16_53 - today 18_00, never moved`() {
        val launch = bridge.resolve(autoNow = false, startMinute = 18 * 60, endMinute = 2 * 60, zoneId = msk, days = 3, nowMs = ms("2026-10-12T13:53:00Z"))!!
        assertFalse(launch.startsNow)
        assertEquals(ms("2026-10-12T15:00:00Z"), launch.firstLockStartMs)
        assertNull(bridge.resolve(autoNow = false, startMinute = 600, endMinute = 600, zoneId = msk, days = 3, nowMs = 0))
    }

    @Test
    fun `AUTO_NOW launch with the shield on becomes ACTIVE at once`() {
        val now = "2026-10-12T13:53:20Z"
        val state = scheduled(autoNow = true, start = 16 * 60 + 53, end = 53, nowIso = now)
        assertEquals("ACTIVATING", bridge.view(state)!!.status)
        assertTrue(bridge.view(state)!!.autoNow)
        val active = bridge.refresh(state, ms(now) + 2_000, "n2", shieldOnTimesMs = listOf(ms(now) + 1_000), authorized = true).state!!
        val v = bridge.view(active)!!
        assertEquals("ACTIVE", v.status)
        assertFalse(v.autoNow, "frozen at ACTIVE")
        assertEquals(16 * 60 + 53, v.startMinute)
        assertEquals("Мама", v.contactName)
    }

    @Test
    fun `MANUAL 23_00 - the monitor switched the shield on at 23_00, the app opened later - ACTIVE as of 23_00`() {
        val state = scheduled(autoNow = false, start = 23 * 60, end = 7 * 60, nowIso = "2026-10-12T17:00:00Z")
        val v = bridge.view(state)!!
        assertEquals("SCHEDULED", v.status)
        assertEquals(ms("2026-10-12T20:00:00Z"), v.lockStartMs)
        // Opened at 01:00: the shield came on at 23:00:02.
        val r = bridge.refresh(state, ms("2026-10-12T22:00:00Z"), "n2", shieldOnTimesMs = listOf(ms("2026-10-12T20:00:02Z")), authorized = true)
        assertEquals("ACTIVE", bridge.view(r.state!!)!!.status)
    }

    @Test
    fun `lock started without the shield, or without Screen Time - the series did not begin`() {
        val state = scheduled(autoNow = false, start = 23 * 60, end = 7 * 60, nowIso = "2026-10-12T17:00:00Z")
        val late = bridge.refresh(state, (t("2026-10-12T20:00:00Z") + 5.minutes).toEpochMilliseconds(), "n2", shieldOnTimesMs = emptyList(), authorized = true)
        assertEquals("NEEDS_ATTENTION", bridge.view(late.state!!)!!.status)
        val denied = bridge.refresh(state, ms("2026-10-12T20:00:05Z"), "n2", shieldOnTimesMs = listOf(ms("2026-10-12T20:00:01Z")), authorized = false)
        assertEquals("NEEDS_ATTENTION", bridge.view(denied.state!!)!!.status)
        // A shield from an earlier lock does not count for this one; the earliest one during this lock does.
        val stale = bridge.refresh(state, ms("2026-10-12T20:00:05Z") + 3.minutes.inWholeMilliseconds, "n2", shieldOnTimesMs = listOf(ms("2026-10-11T20:00:01Z")), authorized = true)
        assertEquals("NEEDS_ATTENTION", bridge.view(stale.state!!)!!.status)
        val mixed = bridge.refresh(state, ms("2026-10-12T22:00:00Z"), "n2", shieldOnTimesMs = listOf(ms("2026-10-11T20:00:01Z"), ms("2026-10-12T20:00:03Z")), authorized = true)
        assertEquals("ACTIVE", bridge.view(mixed.state!!)!!.status)
    }

    @Test
    fun `AUTO_NOW needing attention is relaunched from the moment of the relaunch`() {
        val state = scheduled(autoNow = true, start = 18 * 60, end = 2 * 60, nowIso = "2026-10-12T15:00:00Z")
        val missed = bridge.refresh(state, ms("2026-10-12T15:00:05Z"), "n2", shieldOnTimesMs = emptyList(), authorized = false).state!!
        assertEquals("NEEDS_ATTENTION", bridge.view(missed)!!.status)
        val relaunched = bridge.relaunch(missed, ms("2026-10-12T15:20:00Z"), "n3").state!!
        val v = bridge.view(relaunched)!!
        assertEquals(18 * 60 + 20, v.startMinute)
        assertEquals(2 * 60 + 20, v.endMinute)
        assertEquals(ms("2026-10-12T15:20:00Z"), v.lockStartMs)
    }

    @Test
    fun `busy, waiting after a failure, invalid input, cancel before the start`() {
        val state = scheduled(autoNow = false, start = 23 * 60, end = 7 * 60, nowIso = "2026-10-12T17:00:00Z")
        assertEquals(MamaBridge.BUSY, bridge.schedule(state, 3, true, 0, 480, msk, "Мама", "+79001234567", ms("2026-10-12T17:01:00Z"), "s2", "n2").error)
        assertEquals(MamaBridge.INVALID, bridge.schedule(empty, 3, true, 0, 480, msk, "Мама", "12", 0, "s2", "n2").error)
        assertEquals(MamaBridge.INVALID, bridge.schedule(empty, 4, true, 0, 480, msk, "Мама", "+79001234567", 0, "s2", "n2").error)

        val cancelled = bridge.cancel(state, ms("2026-10-12T17:05:00Z")).state!!
        assertNotNull(bridge.schedule(cancelled, 3, true, 0, 480, msk, "Мама", "+79001234567", ms("2026-10-12T17:06:00Z"), "s2", "n2").state)

        val failedAt = t("2026-10-10T21:00:00Z")
        val afterFailure = StateCodec.encode(Snapshot(entitlements = Entitlements(standardFailedAt = failedAt)))
        assertEquals(MamaBridge.WAITING, bridge.schedule(afterFailure, 3, true, 0, 480, msk, "Мама", "+79001234567", (failedAt + 1.days).toEpochMilliseconds(), "s2", "n2").error)
        assertNotNull(bridge.schedule(afterFailure, 3, true, 0, 480, msk, "Мама", "+79001234567", (failedAt + 30.days + 1.seconds).toEpochMilliseconds(), "s2", "n2").state)
    }
}
