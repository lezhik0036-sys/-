package app.mama.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stale-response guard: a Trusted Exit result captured before the surface was
 * hidden/reopened, or before the session changed, must not be applied to the new
 * state. Pure logic, so these races are tested without an actual overlay.
 */
class StaleResultGuardTest {

    @Test
    fun `a fresh snapshot with the surface present and the same session is current`() {
        val g = StaleResultGuard()
        val snap = g.snapshot("s1")
        assertTrue(g.isCurrent(snap, currentSessionId = "s1", surfacePresent = true))
    }

    @Test
    fun `a closed surface drops the result`() {
        val g = StaleResultGuard()
        val snap = g.snapshot("s1")
        assertFalse(g.isCurrent(snap, currentSessionId = "s1", surfacePresent = false))
    }

    @Test
    fun `hide then reopen (epoch advanced) drops the result even with the surface present`() {
        val g = StaleResultGuard()
        val snap = g.snapshot("s1")
        g.bump() // hide() / destroy() advanced the epoch; a reopened surface is a new generation
        assertFalse(g.isCurrent(snap, currentSessionId = "s1", surfacePresent = true))
    }

    @Test
    fun `a changed session drops the result`() {
        val g = StaleResultGuard()
        val snap = g.snapshot("s1")
        // A new session also advances the epoch in the overlay; even without that the id differs.
        assertFalse(g.isCurrent(snap, currentSessionId = "s2", surfacePresent = true))
        g.bump()
        assertFalse(g.isCurrent(snap, currentSessionId = "s2", surfacePresent = true))
    }

    @Test
    fun `a null session never matches`() {
        val g = StaleResultGuard()
        val snap = g.snapshot(null)
        assertFalse(g.isCurrent(snap, currentSessionId = null, surfacePresent = true))
    }

    @Test
    fun `a request dispatched after a bump is current again for its own session`() {
        val g = StaleResultGuard()
        g.bump() // previous generation invalidated
        val snap = g.snapshot("s2")
        assertTrue(g.isCurrent(snap, currentSessionId = "s2", surfacePresent = true))
    }
}
