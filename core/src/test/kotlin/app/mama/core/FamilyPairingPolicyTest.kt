package app.mama.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class FamilyPairingPolicyTest {
    private val t0 = Instant.parse("2026-11-04T12:00:00Z")
    private val request = FamilyPairingPolicy.Request(
        id = "pair-1", parentAccountId = "parent-1", childDeviceId = "tablet-1",
        createdAt = t0, expiresAt = t0.plusSeconds(300),
    )
    private val parent = FamilyPairingPolicy.Actor.Parent("parent-1")
    private val child = FamilyPairingPolicy.Actor.ChildDevice("tablet-1")

    private fun accepted(value: FamilyPairingPolicy.Decision) =
        assertIs<FamilyPairingPolicy.Decision.Accepted>(value).request

    @Test fun `requires both independent approvals`() {
        val first = accepted(FamilyPairingPolicy.approve(request, parent, t0.plusSeconds(5)))
        assertEquals(FamilyPairingPolicy.State.PENDING, first.state)
        assertFalse(FamilyPairingPolicy.mayManageChild(first, "parent-1"))
        val linked = accepted(FamilyPairingPolicy.approve(first, child, t0.plusSeconds(6)))
        assertEquals(FamilyPairingPolicy.State.LINKED, linked.state)
        assertTrue(FamilyPairingPolicy.mayManageChild(linked, "parent-1"))
        assertFalse(FamilyPairingPolicy.mayManageChild(linked, "other-parent"))
    }

    @Test fun `FastAPI child initiated invite needs parent approval only once`() {
        // FastAPI required a fresh signed child-device challenge before
        // returning the invitation. The local projection is not proof.
        val scanned = FamilyPairingPolicy.afterServerVerifiedChildInvitation(
            id = request.id,
            authenticatedParentAccountId = "parent-1",
            verifiedChildDeviceId = "tablet-1",
            createdAt = t0,
            expiresAt = t0.plusSeconds(300),
        )
        assertEquals(t0, scanned.childApprovedAt)
        assertEquals(FamilyPairingPolicy.State.PENDING, scanned.state)
        assertFalse(FamilyPairingPolicy.mayManageChild(scanned, "parent-1"))
        assertEquals(
            FamilyPairingPolicy.Decision.Forbidden,
            FamilyPairingPolicy.approve(
                scanned, FamilyPairingPolicy.Actor.Parent("intruder"), t0.plusSeconds(1),
            ),
        )
        val approved = accepted(FamilyPairingPolicy.approve(scanned, parent, t0.plusSeconds(2)))
        assertEquals(FamilyPairingPolicy.State.LINKED, approved.state)
        assertTrue(FamilyPairingPolicy.mayManageChild(approved, "parent-1"))
    }

    @Test fun `expired FastAPI invitation never links a parent`() {
        val scanned = FamilyPairingPolicy.afterServerVerifiedChildInvitation(
            id = "expired", authenticatedParentAccountId = "parent-1",
            verifiedChildDeviceId = "tablet-1",
            createdAt = t0, expiresAt = t0.plusSeconds(300),
        )
        assertEquals(
            FamilyPairingPolicy.Decision.NoLongerAvailable,
            FamilyPairingPolicy.approve(scanned, parent, t0.plusSeconds(300)),
        )
    }

    @Test fun `reverse approval order also works`() {
        val first = accepted(FamilyPairingPolicy.approve(request, child, t0.plusSeconds(1)))
        val linked = accepted(FamilyPairingPolicy.approve(first, parent, t0.plusSeconds(2)))
        assertEquals(FamilyPairingPolicy.State.LINKED, linked.state)
    }

    @Test fun `foreign parent and foreign device are forbidden`() {
        assertEquals(FamilyPairingPolicy.Decision.Forbidden,
            FamilyPairingPolicy.approve(request, FamilyPairingPolicy.Actor.Parent("intruder"), t0))
        assertEquals(FamilyPairingPolicy.Decision.Forbidden,
            FamilyPairingPolicy.approve(request, FamilyPairingPolicy.Actor.ChildDevice("other"), t0))
    }

    @Test fun `code expires at exact deadline and cannot be resurrected`() {
        assertEquals(FamilyPairingPolicy.State.EXPIRED,
            FamilyPairingPolicy.expire(request, request.expiresAt).state)
        assertEquals(FamilyPairingPolicy.Decision.NoLongerAvailable,
            FamilyPairingPolicy.approve(request, parent, request.expiresAt))
        assertEquals(FamilyPairingPolicy.Decision.NoLongerAvailable,
            FamilyPairingPolicy.approve(request.copy(state = FamilyPairingPolicy.State.EXPIRED), child, t0))
    }

    @Test fun `approval replay preserves first approval timestamp`() {
        val once = accepted(FamilyPairingPolicy.approve(request, parent, t0.plusSeconds(1)))
        val twice = accepted(FamilyPairingPolicy.approve(once, parent, t0.plusSeconds(30)))
        assertEquals(once, twice)
    }

    @Test fun `linked session cannot be paired again`() {
        val first = accepted(FamilyPairingPolicy.approve(request, parent, t0))
        val linked = accepted(FamilyPairingPolicy.approve(first, child, t0.plusSeconds(1)))
        assertEquals(FamilyPairingPolicy.Decision.NoLongerAvailable,
            FamilyPairingPolicy.approve(linked, parent, t0.plusSeconds(2)))
    }

    @Test fun `either verified party can revoke but strangers cannot`() {
        val first = accepted(FamilyPairingPolicy.approve(request, parent, t0))
        val linked = accepted(FamilyPairingPolicy.approve(first, child, t0.plusSeconds(1)))
        assertEquals(FamilyPairingPolicy.Decision.Forbidden,
            FamilyPairingPolicy.revoke(linked, FamilyPairingPolicy.Actor.Parent("intruder"), t0.plusSeconds(2)))
        val revoked = accepted(FamilyPairingPolicy.revoke(linked, child, t0.plusSeconds(2)))
        assertEquals(FamilyPairingPolicy.State.REVOKED, revoked.state)
        assertFalse(FamilyPairingPolicy.mayManageChild(revoked, "parent-1"))
        assertEquals(FamilyPairingPolicy.Decision.NoLongerAvailable,
            FamilyPairingPolicy.revoke(revoked, parent, t0.plusSeconds(3)))
    }

    @Test fun `rejects invalid pairing identifiers and TTL`() {
        assertFailsWith<IllegalArgumentException> { request.copy(id = "") }
        assertFailsWith<IllegalArgumentException> { request.copy(expiresAt = t0) }
    }
}
