package app.mama.core

import java.time.Duration
import java.time.Instant

/**
 * MAMA Family pairing state machine. This is a pure policy model, NOT authentication:
 * only the FastAPI server may call it, after verifying the caller's account/device
 * credential and loading this record from server-owned durable storage.
 *
 * A pairing code/QR is only a locator; knowing it never authorizes a link.
 *
 * This model is aligned with `mama-fastapi` `docs/FAMILY_PAIRING_PROTOCOL_V0.md`
 * and the service in PR #35. See `docs/FAMILY_PAIRING_CROSS_PROJECT_REVIEW.md` for
 * the gap analysis (G1–G6) this implementation closes:
 *  - G1: the FastAPI single-approval invitation is a distinct [ChildApprovalModel]
 *        and can never be driven through the interactive two-party path by mistake.
 *  - G2: the child-OWNER account ([Request.childAccountId]) is modelled separately
 *        from the device, so the owner can revoke a link after losing the device.
 *  - G5: a server-revoked device ([Request.deviceRevoked]) can neither link nor be
 *        managed, even if a link row still exists.
 *  - G6: management authority ([mayManageChild]) requires proof of a fresh
 *        authenticated server read and is never an unlock / early-exit authority.
 *
 * Family authority is independent of the Trusted Exit / OTP path: nothing here
 * unlocks an active BRICK session, authorises an early exit, or changes a series.
 */
object FamilyPairingPolicy {
    enum class State { PENDING, LINKED, EXPIRED, REVOKED }

    /**
     * How the child side of a pairing was established. The two are deliberately
     * distinct so the FastAPI single-approval invitation can never be driven
     * through the interactive two-party path by mistake (review gap G1).
     */
    enum class ChildApprovalModel {
        /**
         * FastAPI issued the invitation only after an authenticated child account
         * plus a signed child-device challenge, so the child's approval is already
         * proven server-side (represented by `childApprovedAt = createdAt`). The
         * child is NEVER asked to approve again; only the parent approval remains.
         */
        SERVER_VERIFIED_INVITATION,

        /**
         * A deliberately interactive pairing where the child device approves in-app
         * as its own step. This is NOT the FastAPI flow; use it only if the product
         * adds a real second confirmation step on both platforms.
         */
        INTERACTIVE_TWO_PARTY,
    }

    data class Request(
        val id: String,
        val parentAccountId: String,
        /**
         * The child-OWNER account, distinct from the device (review gap G2). FastAPI
         * keys account-level recovery (unpair after device loss) on this, never on
         * device possession.
         */
        val childAccountId: String,
        val childDeviceId: String,
        val createdAt: Instant,
        val expiresAt: Instant,
        val childApprovalModel: ChildApprovalModel = ChildApprovalModel.INTERACTIVE_TWO_PARTY,
        val state: State = State.PENDING,
        val parentApprovedAt: Instant? = null,
        val childApprovedAt: Instant? = null,
        val linkedAt: Instant? = null,
        val revokedAt: Instant? = null,
        /**
         * Server-owned signal that the child device registration was revoked or
         * rotated (review gap G5). A revoked device can neither be linked nor
         * managed, even if the link row itself was not revoked in the same step.
         */
        val deviceRevoked: Boolean = false,
    ) {
        init {
            require(
                id.isNotBlank() && parentAccountId.isNotBlank() &&
                    childAccountId.isNotBlank() && childDeviceId.isNotBlank(),
            )
            require(expiresAt.isAfter(createdAt)) { "Pairing must have a positive TTL" }
        }
    }

    /**
     * Projection of a FastAPI invitation *after* the authenticated parent scans it.
     * FastAPI's child-owned device already proved key possession when it requested
     * the invitation, so the child approval is represented by createdAt and the
     * model is [ChildApprovalModel.SERVER_VERIFIED_INVITATION]. Only an
     * authenticated server reply may supply this projection; creating it locally
     * does NOT prove identity or link a device.
     */
    fun afterServerVerifiedChildInvitation(
        id: String,
        authenticatedParentAccountId: String,
        verifiedChildAccountId: String,
        verifiedChildDeviceId: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): Request = Request(
        id = id,
        parentAccountId = authenticatedParentAccountId,
        childAccountId = verifiedChildAccountId,
        childDeviceId = verifiedChildDeviceId,
        createdAt = createdAt,
        expiresAt = expiresAt,
        childApprovalModel = ChildApprovalModel.SERVER_VERIFIED_INVITATION,
        childApprovedAt = createdAt,
    )

    sealed interface Actor {
        data class Parent(val accountId: String) : Actor
        data class ChildDevice(val deviceId: String) : Actor

        /**
         * The child-OWNER account acting WITHOUT the device (account-level recovery,
         * review gap G2). Used to revoke a parent link after the tablet is lost or
         * its key was revoked. It can never *approve* a pairing — approval needs
         * device-possession proof or a server-verified invitation.
         */
        data class ChildAccount(val accountId: String) : Actor
    }

    sealed interface Decision {
        data class Accepted(val request: Request) : Decision
        data object Forbidden : Decision
        data object NoLongerAvailable : Decision
    }

    /** Expiry is inclusive: at exactly expiresAt, a pairing is no longer usable. */
    fun expire(request: Request, now: Instant): Request =
        if (request.state == State.PENDING && !now.isBefore(request.expiresAt)) {
            request.copy(state = State.EXPIRED)
        } else {
            request
        }

    /**
     * Parent approval alone is never enough. Child approval alone is never enough.
     * Replaying an identical approval is idempotent; a rejected approval never
     * changes state. Callers must persist Accepted atomically (compare-and-swap);
     * this model's idempotency mirrors state, it is NOT the single-use authority —
     * that is FastAPI's atomic token consume (review note A3).
     *
     * A server-revoked device can never be linked (G5). On a
     * [ChildApprovalModel.SERVER_VERIFIED_INVITATION] the child is never asked
     * again, so a child-device approval is refused rather than silently ignored
     * (G1). A child-account actor can never approve (G2).
     */
    fun approve(request: Request, actor: Actor, now: Instant): Decision {
        val current = expire(request, now)
        if (current.state != State.PENDING) return Decision.NoLongerAvailable
        if (current.deviceRevoked) return Decision.NoLongerAvailable
        if (now.isBefore(current.createdAt)) return Decision.NoLongerAvailable
        val updated = when (actor) {
            is Actor.Parent -> {
                if (actor.accountId != current.parentAccountId) return Decision.Forbidden
                current.copy(parentApprovedAt = current.parentApprovedAt ?: now)
            }
            is Actor.ChildDevice -> {
                if (current.childApprovalModel == ChildApprovalModel.SERVER_VERIFIED_INVITATION) {
                    // Child approval was already proven at invitation time; the FastAPI
                    // flow never asks the device again. Surfacing this as Forbidden keeps
                    // the two-party path from being wired to a server invitation.
                    return Decision.Forbidden
                }
                if (actor.deviceId != current.childDeviceId) return Decision.Forbidden
                current.copy(childApprovedAt = current.childApprovedAt ?: now)
            }
            is Actor.ChildAccount -> return Decision.Forbidden
        }
        val linked = if (updated.parentApprovedAt != null && updated.childApprovedAt != null) {
            updated.copy(state = State.LINKED, linkedAt = now)
        } else {
            updated
        }
        return Decision.Accepted(linked)
    }

    /**
     * Any verified party may revoke a link; the QR code cannot revoke it. The
     * child-OWNER account may revoke WITHOUT the device (account-level recovery,
     * G2): device possession is intentionally not required, and a server-revoked
     * device does not block the owner from unlinking it.
     */
    fun revoke(request: Request, actor: Actor, now: Instant): Decision {
        if (request.state != State.LINKED) return Decision.NoLongerAvailable
        val authorized = when (actor) {
            is Actor.Parent -> actor.accountId == request.parentAccountId
            is Actor.ChildDevice -> actor.deviceId == request.childDeviceId
            is Actor.ChildAccount -> actor.accountId == request.childAccountId
        }
        if (!authorized) return Decision.Forbidden
        if (request.linkedAt == null || now.isBefore(request.linkedAt)) return Decision.NoLongerAvailable
        return Decision.Accepted(request.copy(state = State.REVOKED, revokedAt = now))
    }

    /**
     * Display-only: the pairing *looks* linked for the UI (and not on a revoked
     * device). This is NEVER management authority — an offline/cached record can
     * reach this, so it must not gate any privileged action. See [mayManageChild].
     */
    fun appearsLinked(request: Request): Boolean =
        request.state == State.LINKED && !request.deviceRevoked

    /**
     * Parent management/read authority for the linked child device. This is NEVER
     * an unlock or early-BRICK-exit authority — Family authority is independent of
     * the Trusted Exit / OTP path and nothing here ends an active BRICK session.
     *
     * Authority requires proof of a FRESH authenticated server read (review gap
     * G6): [serverConfirmedAt] must be the timestamp the server returned with this
     * record, no earlier than [now] minus [maxAge] and not in the future. A
     * cached/offline record (no fresh server confirmation) can never satisfy this,
     * so offline state can never elevate to family-parent privilege. A
     * server-revoked device is never manageable (G5).
     */
    fun mayManageChild(
        request: Request,
        parentAccountId: String,
        serverConfirmedAt: Instant,
        now: Instant,
        maxAge: Duration,
    ): Boolean =
        request.state == State.LINKED &&
            !request.deviceRevoked &&
            request.parentAccountId == parentAccountId &&
            !now.isBefore(serverConfirmedAt) &&
            Duration.between(serverConfirmedAt, now) <= maxAge
}
