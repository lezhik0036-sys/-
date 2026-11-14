package app.mama.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class FamilyPairingPolicyTest {
    private val t0 = Instant.parse("2026-11-04T12:00:00Z")
    private val maxAge = Duration.ofMinutes(5)
    private val request = FamilyPairingPolicy.Request(
        id = "pair-1", parentAccountId = "parent-1",
        childAccountId = "child-acct-1", childDeviceId = "tablet-1",
        createdAt = t0, expiresAt = t0.plusSeconds(300),
    )
    private val parent = FamilyPairingPolicy.Actor.Parent("parent-1")
    private val child = FamilyPairingPolicy.Actor.ChildDevice("tablet-1")
    private val childOwner = FamilyPairingPolicy.Actor.ChildAccount("child-acct-1")

    private fun accepted(value: FamilyPairingPolicy.Decision) =
        assertIs<FamilyPairingPolicy.Decision.Accepted>(value).request

    private fun linkedRequest(deviceRevoked: Boolean = false) = request.copy(
        state = FamilyPairingPolicy.State.LINKED,
        parentApprovedAt = t0, childApprovedAt = t0, linkedAt = t0,
        deviceRevoked = deviceRevoked,
    )

    @Test fun `requires both independent approvals`() {
        val first = accepted(FamilyPairingPolicy.approve(request, parent, t0.plusSeconds(5)))
        assertEquals(FamilyPairingPolicy.State.PENDING, first.state)
        assertFalse(
            FamilyPairingPolicy.mayManageChild(
                first, "parent-1", serverConfirmedAt = t0.plusSeconds(5), now = t0.plusSeconds(5), maxAge = maxAge,
            ),
        )
        val linked = accepted(FamilyPairingPolicy.approve(first, child, t0.plusSeconds(6)))
        assertEquals(FamilyPairingPolicy.State.LINKED, linked.state)
        assertTrue(
            FamilyPairingPolicy.mayManageChild(
                linked, "parent-1", serverConfirmedAt = t0.plusSeconds(6), now = t0.plusSeconds(6), maxAge = maxAge,
            ),
        )
        assertFalse(
            FamilyPairingPolicy.mayManageChild(
                linked, "other-parent", serverConfirmedAt = t0.plusSeconds(6), now = t0.plusSeconds(6), maxAge = maxAge,
            ),
        )
    }

    @Test fun `FastAPI child initiated invite needs parent approval only once`() {
        // FastAPI required a fresh signed child-device challenge before
        // returning the invitation. The local projection is not proof.
        val scanned = FamilyPairingPolicy.afterServerVerifiedChildInvitation(
            id = request.id,
            authenticatedParentAccountId = "parent-1",
            verifiedChildAccountId = "child-acct-1",
            verifiedChildDeviceId = "tablet-1",
            createdAt = t0,
            expiresAt = t0.plusSeconds(300),
        )
        assertEquals(t0, scanned.childApprovedAt)
        assertEquals(FamilyPairingPolicy.ChildApprovalModel.SERVER_VERIFIED_INVITATION, scanned.childApprovalModel)
        assertEquals(FamilyPairingPolicy.State.PENDING, scanned.state)
        assertEquals(
            FamilyPairingPolicy.Decision.Forbidden,
            FamilyPairingPolicy.approve(
                scanned, FamilyPairingPolicy.Actor.Parent("intruder"), t0.plusSeconds(1),
            ),
        )
        val approved = accepted(FamilyPairingPolicy.approve(scanned, parent, t0.plusSeconds(2)))
        assertEquals(FamilyPairingPolicy.State.LINKED, approved.state)
        assertTrue(
            FamilyPairingPolicy.mayManageChild(
                approved, "parent-1", serverConfirmedAt = t0.plusSeconds(2), now = t0.plusSeconds(2), maxAge = maxAge,
            ),
        )
    }

    @Test fun `server verified invitation refuses a second child approval (G1)`() {
        val scanned = FamilyPairingPolicy.afterServerVerifiedChildInvitation(
            id = "inv", authenticatedParentAccountId = "parent-1",
            verifiedChildAccountId = "child-acct-1", verifiedChildDeviceId = "tablet-1",
            createdAt = t0, expiresAt = t0.plusSeconds(300),
        )
        // The child is never asked again on the FastAPI flow: an in-app child
        // approval is refused, so the two-party path cannot be wired to it.
        assertEquals(
            FamilyPairingPolicy.Decision.Forbidden,
            FamilyPairingPolicy.approve(scanned, child, t0.plusSeconds(1)),
        )
        // The parent alone still completes the link.
        val linked = accepted(FamilyPairingPolicy.approve(scanned, parent, t0.plusSeconds(2)))
        assertEquals(FamilyPairingPolicy.State.LINKED, linked.state)
    }

    @Test fun `expired FastAPI invitation never links a parent`() {
        val scanned = FamilyPairingPolicy.afterServerVerifiedChildInvitation(
            id = "expired", authenticatedParentAccountId = "parent-1",
            verifiedChildAccountId = "child-acct-1", verifiedChildDeviceId = "tablet-1",
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
        assertEquals(
            FamilyPairingPolicy.Decision.Forbidden,
            FamilyPairingPolicy.approve(request, FamilyPairingPolicy.Actor.Parent("intruder"), t0),
        )
        assertEquals(
            FamilyPairingPolicy.Decision.Forbidden,
            FamilyPairingPolicy.approve(request, FamilyPairingPolicy.Actor.ChildDevice("other"), t0),
        )
    }

    @Test fun `child account can never approve a pairing (G2)`() {
        assertEquals(
            FamilyPairingPolicy.Decision.Forbidden,
            FamilyPairingPolicy.approve(request, childOwner, t0.plusSeconds(1)),
        )
        // A stranger account is equally refused.
        assertEquals(
            FamilyPairingPolicy.Decision.Forbidden,
            FamilyPairingPolicy.approve(request, FamilyPairingPolicy.Actor.ChildAccount("stranger"), t0.plusSeconds(1)),
        )
    }

    @Test fun `code expires at exact deadline and cannot be resurrected`() {
        assertEquals(
            FamilyPairingPolicy.State.EXPIRED,
            FamilyPairingPolicy.expire(request, request.expiresAt).state,
        )
        assertEquals(
            FamilyPairingPolicy.Decision.NoLongerAvailable,
            FamilyPairingPolicy.approve(request, parent, request.expiresAt),
        )
        assertEquals(
            FamilyPairingPolicy.Decision.NoLongerAvailable,
            FamilyPairingPolicy.approve(request.copy(state = FamilyPairingPolicy.State.EXPIRED), child, t0),
        )
    }

    @Test fun `approval replay preserves first approval timestamp`() {
        val once = accepted(FamilyPairingPolicy.approve(request, parent, t0.plusSeconds(1)))
        val twice = accepted(FamilyPairingPolicy.approve(once, parent, t0.plusSeconds(30)))
        assertEquals(once, twice)
    }

    @Test fun `linked session cannot be paired again`() {
        val first = accepted(FamilyPairingPolicy.approve(request, parent, t0))
        val linked = accepted(FamilyPairingPolicy.approve(first, child, t0.plusSeconds(1)))
        assertEquals(
            FamilyPairingPolicy.Decision.NoLongerAvailable,
            FamilyPairingPolicy.approve(linked, parent, t0.plusSeconds(2)),
        )
    }

    @Test fun `either verified party can revoke but strangers cannot`() {
        val first = accepted(FamilyPairingPolicy.approve(request, parent, t0))
        val linked = accepted(FamilyPairingPolicy.approve(first, child, t0.plusSeconds(1)))
        assertEquals(
            FamilyPairingPolicy.Decision.Forbidden,
            FamilyPairingPolicy.revoke(linked, FamilyPairingPolicy.Actor.Parent("intruder"), t0.plusSeconds(2)),
        )
        val revoked = accepted(FamilyPairingPolicy.revoke(linked, child, t0.plusSeconds(2)))
        assertEquals(FamilyPairingPolicy.State.REVOKED, revoked.state)
        assertFalse(
            FamilyPairingPolicy.mayManageChild(
                revoked, "parent-1", serverConfirmedAt = t0.plusSeconds(2), now = t0.plusSeconds(2), maxAge = maxAge,
            ),
        )
        assertEquals(
            FamilyPairingPolicy.Decision.NoLongerAvailable,
            FamilyPairingPolicy.revoke(revoked, parent, t0.plusSeconds(3)),
        )
    }

    @Test fun `child owner revokes a linked parent after losing the device (G2)`() {
        // Device is revoked server-side AND the owner no longer holds it, yet the
        // child-account owner can still unlink the parent via account recovery.
        val linked = linkedRequest(deviceRevoked = true)
        assertEquals(
            FamilyPairingPolicy.Decision.Forbidden,
            FamilyPairingPolicy.revoke(linked, FamilyPairingPolicy.Actor.ChildAccount("stranger"), t0.plusSeconds(10)),
        )
        val revoked = accepted(FamilyPairingPolicy.revoke(linked, childOwner, t0.plusSeconds(10)))
        assertEquals(FamilyPairingPolicy.State.REVOKED, revoked.state)
        assertEquals(t0.plusSeconds(10), revoked.revokedAt)
    }

    @Test fun `server-revoked device cannot be linked (G5)`() {
        val revokedDevice = request.copy(deviceRevoked = true)
        assertEquals(
            FamilyPairingPolicy.Decision.NoLongerAvailable,
            FamilyPairingPolicy.approve(revokedDevice, parent, t0.plusSeconds(1)),
        )
    }

    @Test fun `revoked device is never manageable even when a link row remains (G5)`() {
        val linked = linkedRequest(deviceRevoked = true)
        assertFalse(FamilyPairingPolicy.appearsLinked(linked))
        assertFalse(
            FamilyPairingPolicy.mayManageChild(
                linked, "parent-1", serverConfirmedAt = t0, now = t0, maxAge = maxAge,
            ),
        )
    }

    @Test fun `management authority needs a fresh non-future server confirmation (G6)`() {
        val linked = linkedRequest()
        // Fresh: within maxAge -> authoritative.
        assertTrue(
            FamilyPairingPolicy.mayManageChild(
                linked, "parent-1", serverConfirmedAt = t0, now = t0.plusSeconds(60), maxAge = maxAge,
            ),
        )
        // Exactly at the freshness boundary is still fresh (inclusive).
        assertTrue(
            FamilyPairingPolicy.mayManageChild(
                linked, "parent-1", serverConfirmedAt = t0, now = t0.plus(maxAge), maxAge = maxAge,
            ),
        )
        // Stale: older than maxAge -> never authoritative (offline cannot elevate).
        assertFalse(
            FamilyPairingPolicy.mayManageChild(
                linked, "parent-1", serverConfirmedAt = t0, now = t0.plusSeconds(301), maxAge = maxAge,
            ),
        )
        // A confirmation stamped in the future (clock skew) fails closed.
        assertFalse(
            FamilyPairingPolicy.mayManageChild(
                linked, "parent-1", serverConfirmedAt = t0.plusSeconds(60), now = t0, maxAge = maxAge,
            ),
        )
        // Wrong parent, and a non-linked state, are never authoritative.
        assertFalse(
            FamilyPairingPolicy.mayManageChild(
                linked, "other-parent", serverConfirmedAt = t0, now = t0, maxAge = maxAge,
            ),
        )
        assertFalse(
            FamilyPairingPolicy.mayManageChild(
                request, "parent-1", serverConfirmedAt = t0, now = t0, maxAge = maxAge,
            ),
        )
    }

    @Test fun `appearsLinked is display state only`() {
        assertTrue(FamilyPairingPolicy.appearsLinked(linkedRequest()))
        assertFalse(FamilyPairingPolicy.appearsLinked(request))
        assertFalse(FamilyPairingPolicy.appearsLinked(linkedRequest(deviceRevoked = true)))
    }

    @Test fun `rejects invalid pairing identifiers and TTL`() {
        assertFailsWith<IllegalArgumentException> { request.copy(id = "") }
        assertFailsWith<IllegalArgumentException> { request.copy(childAccountId = "") }
        assertFailsWith<IllegalArgumentException> { request.copy(expiresAt = t0) }
    }
}
