# MAMA Family — cross-project pairing review (Android/core ↔ FastAPI)

**Status:** review of record, design only. No code change, no HTTP route, no
flag, no SMS, no deployment. This document reconciles the two **draft**
implementations and names the exact alignment gaps to settle **before** any UI
integration or public endpoint.

Mandated by `CLAUDE.md` ("Cross-project review first… document sequence, account
identity, replay denial, expiry, revocation, and offline/losing-phone
recovery") and by FastAPI issue
[#38](https://github.com/lezhik0036-sys/mama-fastapi/issues/38) deliverable 1.

## Sources compared (exact revisions)

| Side | Artifact | Revision reviewed |
| --- | --- | --- |
| Android/core | `core/.../FamilyPairingPolicy.kt` + `FamilyPairingPolicyTest.kt` | this repo, PR [#37](https://github.com/lezhik0036-sys/-/pull/37) tip `7b54d76` |
| FastAPI | `api/app/family_pairing_service.py`, `test_family_pairing_db.py`, `docs/FAMILY_PAIRING_PROTOCOL_V0.md` | `mama-fastapi` PR [#35](https://github.com/lezhik0036-sys/mama-fastapi/pull/35) head `eae8136` |
| Contract | `docs/FAMILY_PAIRING_PROTOCOL_V0.md` (the authoritative server-owned contract) | same |

Neither implementation alone proves end-to-end readiness; this review treats the
FastAPI server state as **authoritative** and the Android/core policy as a
**pure, display-oriented mirror that only the server may drive**. That matches
the `FamilyPairingPolicy` KDoc ("only the FastAPI server may call it, after
verifying the caller's account/device credential") and the protocol doc's
"Authority and identity" section.

---

## 1. Sequence / state model

**FastAPI (authoritative):**
```
UNLINKED
  child owner authenticates + device key signs fresh challenge
    -> INVITED            (256-bit locator, 5-min TTL, hashed at rest)
  parent scans locator, signs in, guardian consent verified
    -> LINKED             (token consumed atomically, single-use)
  authenticated parent OR child owner revokes
    -> REVOKED
  invitation TTL elapses
    -> EXPIRED            (replaceable by a new verified request)
```
Child "approval" is represented **by the act of issuing the invitation**
(`begin_child_device_pairing`, gated on an authenticated child account + signed
device challenge). There is **no second child confirmation step**.

**Android/core (`FamilyPairingPolicy`):**
```
PENDING -> LINKED -> REVOKED / EXPIRED
```
with two approval slots (`parentApprovedAt`, `childApprovedAt`). `approve`
requires *both* before `LINKED`. The FastAPI-aligned entry point
`afterServerVerifiedChildInvitation(...)` pre-fills `childApprovedAt = createdAt`
so only the parent approval remains outstanding.

**Alignment:** `INVITED` ≙ `PENDING`; `LINKED/REVOKED/EXPIRED` match 1:1. The
FastAPI-aligned path (`afterServerVerifiedChildInvitation` → parent `approve`)
reproduces the server sequence exactly, and `FamilyPairingPolicyTest`
("FastAPI child initiated invite needs parent approval only once") locks that in.

### Gap G1 — the generic two-party `approve` path is a superset FastAPI never uses
`approve(request, Actor.ChildDevice, …)` and the tests `requires both
independent approvals` / `reverse approval order also works` model a **second,
interactive child approval** that the FastAPI flow does **not** have. The
protocol doc is explicit: *"never require the child to confirm again after a
successful child-device-key signed invitation unless the product deliberately
adds a second step on both platforms."*
- **Risk:** if Android UI ever drives the FastAPI invitation through the generic
  two-party path (expecting a child tap) it diverges from the server, which has
  already consumed the child's approval at invite time → a stuck/838 "waiting for
  child" state the server cannot satisfy.
- **Resolution (deferred to code phase):** Android must bind the FastAPI flow
  **only** to `afterServerVerifiedChildInvitation` + parent `approve`. Either keep
  the generic two-party path for a *future, deliberately two-step* product and
  document it as not-the-FastAPI-flow, or restrict it behind a distinct type so it
  cannot be wired to a server invitation by mistake.

---

## 2. Account identity

**FastAPI:** authenticates the **parent account** and the **child-owner account**
separately; the **child device** has its own registered signing key. Guardian
authority/consent is independent server/provider evidence.
`verified_parent_session`, `verified_child_session`,
`verified_device_key_possession`, `guardian_consent_checked` are
**trusted-server-only** values that default false and are **never** read from
Android JSON (FastAPI `CLAUDE.md`, hard boundary). The child-owner unpair path
keys on `authenticated_child_user_id` (the **account**), explicitly **not** on
device possession.

**Android/core:** identities are opaque strings: `parentAccountId` and
`childDeviceId`. The child is modelled **only as a device id**; there is no
child-*account* identity in the record. `Actor.ChildDevice(deviceId)` is the only
child actor.

### Gap G2 — core models the child as a device, FastAPI separates child **account** from device **(highest priority)**
Because the only child actor is `Actor.ChildDevice(deviceId)` and `revoke`
requires `actor.deviceId == request.childDeviceId`, the core model **cannot
represent "the child-account owner revokes after losing/rotating the device."**
That is a first-class FastAPI capability (`revoke_child_device_parent_link`,
device possession intentionally not required) and an explicit product
requirement ("offline/losing-phone recovery"; CLAUDE.md "losing-phone recovery").
- **Consequence:** a UI built on the current core model would gate child-side
  revocation on possessing the very tablet the user has lost — the opposite of
  the recovery guarantee.
- **Resolution (deferred to code phase):** add a child **account** identity to
  `Request` (e.g. `childAccountId`) distinct from `childDeviceId`, and a
  `Actor.ChildAccount(accountId)` that can revoke without the device, mirroring
  FastAPI. Keep `childDeviceId` for the device-possession proof path. This is a
  model change and is intentionally **not** made in this review-only PR.

---

## 3. Replay denial

**FastAPI:** single-use locator, **consumed atomically**; a second unexpired
invitation for the same device is denied on the **locked child-owner row**
(`begin_child_device_pairing` new `pending` check); exactly one active link per
device (partial unique index); token hashed at rest, plaintext returned once.

**Android/core:** `approve` is idempotent (replaying a party's approval preserves
the first timestamp — test `approval replay preserves first approval timestamp`);
a `LINKED` request cannot be paired again (`linked session cannot be paired
again`); an `EXPIRED` request cannot be resurrected. Core has **no token** — the
locator/single-use authority lives entirely in FastAPI.

**Alignment A3 (consistent, by design):** both sides are replay-safe, but the
**authority** for single-use is FastAPI's atomic token consume. Core's
idempotency is a UX/state mirror and must **never** be treated as the
single-use authority. Document this so Android does not "consume" a locator
locally.

---

## 4. Expiry

**FastAPI:** 5-minute TTL; the duplicate-invite check uses
`FamilyPairingInvitation.expires_at > now`, so an invitation **at exactly**
`expires_at` is already non-pending (expired). Server clock is authoritative.

**Android/core:** TTL is arbitrary (`expiresAt`); `expire` marks `EXPIRED` when
`now >= expiresAt` — i.e. **inclusive at the deadline** (`code expires at exact
deadline and cannot be resurrected`). The test pins `expiresAt = createdAt +
300s`, matching the 5-minute locator.

**Alignment A4 (boundary consistent):** core's inclusive `now >= expiresAt` and
FastAPI's `expires_at > now` agree at the boundary (exactly-at-TTL ⇒ expired on
both). Two things to settle in the code phase, both small:
- **Clock authority:** core expiry uses the *caller's* `now`. Android must feed
  **server time** (or treat the result as a display hint only); never let a
  client clock resurrect or prematurely expire a server invitation. The protocol
  test matrix requires DST / clock-drift / "response lost after server commit"
  handling — all server-owned.
- **Consume-path boundary:** confirm `approve_parent_device_pairing` (not in the
  PR #35 diff) uses the same strict `>` at the TTL edge as the duplicate check,
  so the atomically-consumed path and the dedupe path agree.

---

## 5. Revocation

**FastAPI:** linked parent revokes their **own** link
(`revoke_parent_device_link`); child owner revokes at account level
(`revoke_child_device_parent_link`, `verified_child_session` required, device
possession not required). Crucially, `parent_may_read_child_device` now **joins
the device's original owner and excludes revoked devices**, so a
revoked/rotated device drops out of parent read paths **even if the link row was
not revoked in the same transaction**. The DB test asserts the device
disappears from both the read check and `list_authenticated_parent_devices`.

**Android/core:** `revoke` moves `LINKED → REVOKED` for either verified party;
`mayManageChild` returns true for any `LINKED` request whose `parentAccountId`
matches. Core has a **single** `REVOKED` state — it does not distinguish **device
revocation** from **link revocation**.

### Gap G5 — core collapses device-revocation and link-revocation into one state
FastAPI separates `TrustedExitDevice.revoked_at` (device) from
`FamilyParentDeviceLink.revoked_at` (link); a revoked **device** must be invisible
to the parent regardless of the link row. Core's `mayManageChild` would still
return true for a `LINKED` request after the underlying device was revoked
server-side, because core has no device-revocation input.
- **Resolution (deferred):** Android must **never** evaluate read-authorization
  locally from a cached `Request`; `mayManageChild` is a convenience predicate for
  a *server-confirmed* record only. If core is to model this at all, it needs a
  device-revoked signal distinct from link state. Until then, document
  `mayManageChild` as "server-confirmed records only; not an offline authority."

---

## 6. Offline / losing-phone recovery

**FastAPI / contract:** *No offline authority elevation.* The client may cache
**display** state, but network errors cannot create a link, authorize early
BRICK exit, or erase a server-side revocation. Child-owner account recovery
unpairs a lost device without possession. Family authority must **not** be
conflated with Trusted Exit/OTP authority.

**Android/core:** pure functions with no network concept — which is the point of
the risk. `mayManageChild` / an `Accepted` decision are plain values an Android
caller could compute **offline** from a stale cached `Request` and mistake for
live authority.

### Gap G6 — core results must be display-only, server-confirmed, and never OTP authority
- Core carries **no staleness / server-confirmation marker**. A cached
  `Decision.Accepted` or `mayManageChild == true` must never, offline, grant
  parent authority, change a BRICK schedule, or influence early exit (CLAUDE.md:
  "Offline behavior must not unlock an active BRICK session"; "Android must not
  silently gain a family-parent privilege from a local role flag or QR code").
- **Do not conflate** Family authority with Trusted Exit receipt/OTP authority
  (protocol doc, test matrix: verify the signed Trusted Exit receipt and the
  server-owned BRICK period **separately** from pairing).
- **Resolution (deferred):** gate every Android consumer of
  `FamilyPairingPolicy` behind a fresh authenticated server read; treat all core
  outputs as display hints. Optionally thread a "confirmed at / server epoch"
  marker so the UI can show *stale* state without acting on it.

---

## Protocol test-matrix → coverage map

| Protocol matrix row | Covered where | Status |
| --- | --- | --- |
| No-SIM child tablet, device key, parent phone, single-use locator | FastAPI DB `begin`/`approve`; core `afterServerVerifiedChildInvitation` + parent approve | ✅ both sides |
| Parent links foreign/previously-linked tablet → reject, no leak | FastAPI `deny` foreign user; core `foreign parent and foreign device are forbidden` | ✅ both sides |
| Device revoked **between** QR and parent approval → reject | FastAPI `deny(approve)` after `device.revoked_at = now` | ✅ FastAPI (core has no device-revoked input — **G5**) |
| Device revoked **after** linking → drops from parent list/read | FastAPI read-path join + `list_… == ()` | ✅ FastAPI (**G5** on core) |
| Child loses tablet, unpairs via account recovery | FastAPI `revoke_child_device_parent_link` (no device) | ✅ FastAPI; ❌ core cannot model (**G2**) |
| Two concurrent invitations/approvals → no second token/double link | FastAPI locked-row dedupe + single-use | ✅ FastAPI; core `linked session cannot be paired again` (state-level) |
| Wrong locale / DST / clock drift / lost response after commit | server-owned | ⬜ server-only (**A4** clock authority) |
| Trusted Exit receipt + BRICK period verified **separately** from pairing | server-owned; Ktor PR [#34](https://github.com/lezhik0036-sys/-/pull/34) | ⬜ keep separate (**G6**) |
| Expiry exact deadline / replay idempotent / invalid id+TTL | core unit tests | ✅ core |

Legend: ✅ covered · ❌ cannot be expressed in the current model · ⬜ server-only / out of core scope.

---

## Findings summary (for the follow-up code PR)

| # | Severity | Gap | Proposed change (deferred — not in this PR) |
| --- | --- | --- | --- |
| **G2** | High | Core models child as **device only**; cannot express child-**account** recovery unpair after device loss | Add `childAccountId` to `Request` + `Actor.ChildAccount`; allow account revoke without device, mirroring FastAPI |
| **G5** | Medium | Core collapses device-revocation into link-revocation | Feed a server device-revoked signal; document `mayManageChild` as server-confirmed only |
| **G1** | Medium | Generic two-party `approve` is a superset FastAPI never uses | Bind the FastAPI flow only to `afterServerVerifiedChildInvitation` + parent approve; isolate the two-step model |
| **G6** | Medium | Core outputs are usable offline and could be mistaken for live/OTP authority | Require fresh server read at every consumer; add staleness marker; keep Family ≠ Trusted Exit authority |
| **A3** | Low (align) | Single-use authority is FastAPI's atomic consume | Document: core idempotency is a mirror, never the single-use authority |
| **A4** | Low (align) | Expiry boundary consistent; clock authority must be the server | Feed server time; confirm consume-path TTL operator matches the dedupe `>` |

**No "never" is relaxed by this review.** The remedies preserve: server-owned
authority, no client-JSON trust of verification booleans, QR/locator as a mere
locator, no offline authority, no Family↔OTP conflation, child privacy and
revocation/recovery.

## Checks run for this review

Review-only (documentation); the branch runs full CI
(`:core:test` including `FamilyPairingPolicyTest`, `:backend:test`,
`:app:testDebugUnitTest`, APK build + emulator launch). No FastAPI code was
changed; FastAPI assertions above are read from PR #35 head `eae8136`
(`family_pairing_service.py`, `test_family_pairing_db.py`,
`docs/FAMILY_PAIRING_PROTOCOL_V0.md`). FastAPI's own isolated-PostgreSQL tests
run in its repository, not here.

## Next step

With sign-off on the findings table, the code-phase PR implements **G2** and
**G5** on the core model with new `FamilyPairingPolicyTest` cases (account-level
recovery revoke without device; server-device-revoked hides management), keeps
the FastAPI flow bound to the single-approval path (**G1**), and adds the
display-only/staleness guard (**G6**) — each with tests, no "never" weakened, as
a stacked draft PR.
