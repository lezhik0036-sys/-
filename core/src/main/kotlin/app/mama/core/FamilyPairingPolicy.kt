package app.mama.core

import java.time.Instant

/**
 * MAMA Family pairing state machine. This is a pure policy model, NOT authentication:
 * only the FastAPI server may call it, after verifying the caller's account/device
 * credential and loading this record from server-owned durable storage.
 *
 * A pairing code/QR is only a locator; knowing it never authorizes a link.
 * Parent and child must independently approve the same request.
 */
object FamilyPairingPolicy {
    enum class State { PENDING, LINKED, EXPIRED, REVOKED }

    data class Request(
        val id: String,
        val parentAccountId: String,
        val childDeviceId: String,
        val createdAt: Instant,
        val expiresAt: Instant,
        val state: State = State.PENDING,
        val parentApprovedAt: Instant? = null,
        val childApprovedAt: Instant? = null,
        val linkedAt: Instant? = null,
        val revokedAt: Instant? = null,
    ) {
        init {
            require(id.isNotBlank() && parentAccountId.isNotBlank() && childDeviceId.isNotBlank())
            require(expiresAt.isAfter(createdAt)) { "Pairing must have a positive TTL" }
        }
    }

    sealed interface Actor {
        data class Parent(val accountId: String) : Actor
        data class ChildDevice(val deviceId: String) : Actor
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
        } else request

    /**
     * Parent approval alone is never enough. Child approval alone is never enough.
     * Replaying an identical approval is idempotent; a rejected approval never
     * changes state. Callers must persist Accepted atomically (compare-and-swap).
     */
    fun approve(request: Request, actor: Actor, now: Instant): Decision {
        val current = expire(request, now)
        if (current.state != State.PENDING) return Decision.NoLongerAvailable
        if (now.isBefore(current.createdAt)) return Decision.NoLongerAvailable
        val updated = when (actor) {
            is Actor.Parent -> {
                if (actor.accountId != current.parentAccountId) return Decision.Forbidden
                current.copy(parentApprovedAt = current.parentApprovedAt ?: now)
            }
            is Actor.ChildDevice -> {
                if (actor.deviceId != current.childDeviceId) return Decision.Forbidden
                current.copy(childApprovedAt = current.childApprovedAt ?: now)
            }
        }
        val linked = if (updated.parentApprovedAt != null && updated.childApprovedAt != null) {
            updated.copy(state = State.LINKED, linkedAt = now)
        } else updated
        return Decision.Accepted(linked)
    }

    /** Either verified party can revoke a link; the QR code cannot revoke it. */
    fun revoke(request: Request, actor: Actor, now: Instant): Decision {
        if (request.state != State.LINKED) return Decision.NoLongerAvailable
        if (actor !is Actor.Parent || actor.accountId != request.parentAccountId) {
            if (actor !is Actor.ChildDevice || actor.deviceId != request.childDeviceId) return Decision.Forbidden
        }
        if (now.isBefore(request.linkedAt)) return Decision.NoLongerAvailable
        return Decision.Accepted(request.copy(state = State.REVOKED, revokedAt = now))
    }

    /** Administrative access requires the linked parent, never just a pairing code. */
    fun mayManageChild(request: Request, parentAccountId: String): Boolean =
        request.state == State.LINKED && request.parentAccountId == parentAccountId
}
