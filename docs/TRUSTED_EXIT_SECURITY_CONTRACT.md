# MAMA Trusted Exit: security and integration contract (proposal v0.1)

**Status: design only — NOT implemented or approved for production.** Related: issue #3 and Android PR #2. This document is a gate before exposing SMS.RU or deploying Ktor. FastAPI on Selectel and Android release artifacts are unchanged.

## 0. Existing reality and boundaries

- FastAPI service (separate Python repository on Selectel) owns users, auth identities/sessions, series state, cooldown and paid restart. Existing bearer sessions are server-side random opaque tokens stored hashed; `/v1/users/me` validates a bearer token. **There is no production OAuth/phone login or public token-issuing endpoint yet.** Current verified identity does **not** imply a persisted trusted contact or trusted device/session.
- This GitHub repository contains Android and a separate Kotlin/Ktor SMS Trusted Exit prototype. Its `POST /trusted-exit/request` currently accepts untrusted `sessionId`, `kind`, `idempotencyKey`, `contactPhone` as JSON; `POST /trusted-exit/verify` accepts `challengeId`, `code` without auth; Ktor stores challenges only in memory.
- Android PR #2 adds HTTPS client and production fail-closed behavior, but has **no established user credential/exchange**. It must not be connected to a public SMS-sending endpoint as-is.
- User never manually selects app allowlists. Trusted contacts and arbitrary daily local active intervals are product inputs. BRICK Mode can occur at any time of day and in any timezone. Trusted Exit must not change the core lock/cooldown/business rules.

## 1. System ownership

1. **FastAPI is the identity and policy authority:** authenticated user, device registration, persisted trusted-contact ownership and authorization, series/session ownership, current eligibility, lock/exit policy and cooldown. It issues short-lived purpose-scoped capabilities only after server-side validation.
2. **Ktor is the OTP/SMS service:** verifies capabilities, applies durable rate/budget limits, creates a server challenge, sends SMS, checks attempts and exposes only result/status. It does not mint user identities, validate arbitrary client-provided contact numbers as authority or directly write FastAPI business tables.
3. **PostgreSQL** persists Ktor challenges and abuse controls in a dedicated schema/database role with least privilege. A migration plan and DB backup/restore verification are required before production rollout. Prefer a separate DB/role or schema to protect existing FastAPI data.
4. **Android** requests a scoped capability from FastAPI using the authenticated app session, then talks to Ktor over HTTPS. The UI never receives the correct OTP, SMS.RU key or signing private key. No TEST fallback in production.

## 2. Preconditions (hard blockers)

- Complete real user registration/login and durable auth session issuance on FastAPI; identify device securely. Do not treat a client-declared ID as proof.
- Persist verified trusted-contact binding for the user's current series/session; establish explicit product flow for contact selection and validation. No SMS solely because JSON names a phone.
- Persist/verify a server-owned active series/session and its owner. Precisely define how it corresponds to Android's currently local session ID and what happens if device is offline, reinstalled, clock/timezone changes or session ends.
- Define policy for sending a code to the selected contact, consent/abuse limits and whether an authenticated phone owner can alter it during a lock.
- Decide whether Trusted Exit is an **authorization to release the lock** or only a proof of receipt: see section 6 (anti-replay).
- Until these exist, Ktor production SMS/verify endpoints MUST be inaccessible from the public internet. A `/ready` that only confirms SMS credentials is not proof of authorization readiness.

## 3. Proposed capabilities: FastAPI -> Ktor

Use asymmetric signed JWT (e.g. EdDSA Ed25519 or ES256), with explicitly pinned supported algorithm and a trusted public-key set in Ktor. Ktor never receives the signing private key.

- **Issuing endpoint (proposal):** `POST /v1/trusted-exit/authorize` on FastAPI; accepts an authenticated app session and *server-resolvable* active series/session reference plus requested exit kind, and an idempotency key. No arbitrary phone number is trusted.
- FastAPI verifies logged-in account, device/session association, current server series/lock, trusted-contact binding, policy and relevant cooldown. Response includes a short-lived capability plus Ktor endpoint URL. All checks are transactional where needed.
- **JWT claims (proposal):** `iss` (pinned FastAPI issuer), `aud="mama-trusted-exit"`, `sub` (stable server user ID), `sid` (server-owned active session ID), `series_id`, `device_id` (bound to registered device), `contact_id` (server contact reference, not raw untrusted phone), `scope="trusted-exit:request"`, `kind`, `jti` random unique ID, `iat`, `nbf`, `exp` (suggest 1–2 minutes). Include `kid` header for key rotation.
- Ktor validates signature, issuer/audience, strict allowed alg, timestamps with bounded clock skew, scope, kind, and **atomic single-use `jti`**. It resolves contact destination through a trusted server-to-server lookup or a bound encrypted/verified contact assertion from FastAPI. Do not let Android choose/overwrite the SMS destination.
- Keys rotate with overlapping verification window, access-controlled rotation and emergency revocation strategy. Never accept `none`/algorithm substitution or unsafe `jku` key lookup.
- **Verification:** use a separate scoped verification authorization bound to the challenge, user, session and device, or an authenticated Ktor session established during request; `challengeId` alone must never grant permission to test codes. Define revocation on logout/device change.
- Bearer tokens in Android remain confidential, should not appear in logs, links, crash reports or deep links, and are not sent to SMS.RU. TLS required.

**Open design decision:** FastAPI-issued short-lived capabilities versus a same-origin FastAPI proxy for Ktor. Prefer proxy for early integration if it avoids multiple mobile auth schemes; compare latency/operability before committing to JWT. This document does not implement either.

## 4. Proposed HTTP contract (versioned; migration required)

- `POST /v1/trusted-exit/authorize` (FastAPI; authenticated): requests authorization for current server session, returns scoped capability or a server-mediated request.
- `POST /trusted-exit/request` (Ktor; authorized): receives capability and a bounded idempotency key. Do **not** trust client `contactPhone`, `sessionId` or `kind` as authorization; derive/bind them from validated server claims. Result `CREATED/REJECTED/FAILED`, with `challengeId`, timestamps, remaining attempts and safe delivery status (never OTP).
- `POST /trusted-exit/verify` (Ktor; authorized): bound capability/session plus `challengeId` and `code`. Result `VALID/INVALID/EXPIRED/LOCKED/UNKNOWN`, never OTP.
- `GET /health` liveness only; `GET /ready` production readiness must include **auth signing key + database + SMS provider policy/config**; consider keeping detailed readiness internal.
- HTTP `401` invalid/missing auth, `403` forbidden binding/ownership, `429` budget limit, `503` unavailable. No SMS sent or attempt consumed for these outcomes. Preserve typed business statuses where appropriate. Android must fail closed and distinguish offline/unavailable from INVALID; no fake fallback.
- Bound and validate request sizes, field lengths, phone numbers, idempotency keys, JSON parsing and response payload sizes. No long-lived credentials in URL query strings.

**Migration:** current Ktor DTO accepts `contactPhone` and is unauthenticated; Android PR #2 uses that legacy wire contract. Coordinate a versioned change and compatibility test before either endpoint becomes reachable outside an isolated test network.

## 5. Ktor durable data model (proposal, names not final)

Use own schema/role and Alembic/Flyway/liquibase-equivalent migration discipline, with restrictive grants. Minimum entities:

- `trusted_exit_challenges`: challenge UUID, user/series/server_session/device/contact reference, salted keyed OTP verifier (e.g. HMAC using server secret plus per-challenge salt; never raw OTP), `created_at/expires_at`, `attempts_left`, `locked_until`, `status` (PENDING/SENT/VERIFIED/EXPIRED/LOCKED/FAILED), `consumed_at`, provider message reference, idempotency hash. Unique/partial constraints as appropriate.
- `trusted_exit_capability_jti`: consumed `jti`, owner and expiry, unique index for single use.
- `trusted_exit_rate_windows` / counters: per account, contact, device, IP and global spend caps with expiry; atomic increments and monitored limits. Network/IP information used only as additional defense, never sole identity.
- `trusted_exit_delivery_attempts`: immutable audit of delivery request/result, masked recipient, no OTP or credential, limited retention.
- `trusted_exit_exit_receipts`: **one-time result** binding verified challenge to server session, subject/device, expiry and consumption state; needed to prevent client replay/forged exit.

**Concurrency rules:** transactions, unique constraints, row-level locking or conditional updates; one-time successful code verification, 3-attempt limit, durable lockout, idempotency across concurrent requests and restarts. Provider HTTP send is external side effect: design an outbox or explicit SENDING/SENT/UNKNOWN reconciliation process, so a timeout/retry does not silently send duplicate paid SMS. Never hold long DB locks across outbound provider calls.

**OTP brute-force:** five digits = 100,000 candidates; three attempts per challenge alone are insufficient without cross-challenge, cross-user and contact/global throttles. Use CSPRNG and hash keyed with a server-only pepper to limit offline brute force after DB disclosure.

## 6. Authoritative unlock / anti-replay

A Ktor `VALID` JSON response alone is **not** a durable server authorization to end a series. On successful verification:
1. Ktor records an irrevocably consumed challenge and issues/binds an opaque one-time exit receipt (or FastAPI checks the server-side result through mutually authenticated internal API).
2. FastAPI validates that the receipt belongs to the same authenticated user, device, server session, exit kind and still-current policy. It consumes receipt **once** and records the server-side transition/cooldown atomically.
3. Android applies local UI unlock only according to the agreed secure server-confirmed transition or a carefully specified failure-recovery path. Never unlock solely from a locally fabricated `VALID` response.
4. Define reconciliation for loss of network after Ktor successfully verifies but before Android sees acknowledgement. Ensure idempotent retry cannot count a correct code as wrong or lose rightful exit.

**Security limit:** an ordinary Android overlay/accessibility implementation cannot promise hardware-grade tamper-proof device lockdown. Do not claim it prevents uninstall/recovery/system-level controls; document achievable guarantees.

## 7. Test matrix and deployment gates

- Tests: unauthenticated request/verify -> 401 and zero provider sends; forged session/contact/device/challenge -> 403; expired/wrong audience/token/jti replay -> reject; no secret leaks.
- Durable 3-attempt lock and rate limits survive restart; parallel requests cause no duplicate sends; parallel verify uses one receipt; same receipt cannot end another session.
- Provider failure / timeout / unknown delivery -> safe handling; Android offline/timeout/invalid JSON -> no wrong-attempt decrement or unsafe unlock.
- Key rotation and revoked device/session; corrupted/partial DB; cooldown; different timezones/day boundaries; 5-digit leading zeros; lost response after successful verification.
- Test against ephemeral Postgres with fake SMS provider, **no actual SMS.RU** in CI.
- Staged release: isolated staging environment, strict spend cap, first real SMS only with explicit approval, Android two-device test, restore test, rollback plan, production go/no-go separately.
- Never deploy to existing Selectel FastAPI or main DB from a pull request automatically. Keep rollback backups and migrations reviewed.

## 8. Work sequence

1. Resolve product/identity/contact/server-session questions (blocking).
2. Choose authorization topology: FastAPI proxy vs JWT capability; write threat model and protocol tests.
3. Implement FastAPI server-owned session/contact authorization and protected internal contract; separate codebase/PR.
4. Implement Ktor authenticated endpoints, persistent challenge/outbox/counters and tests; separate PR in this GitHub repo.
5. Update Android PR #2 or successor to new versioned contract, preserving TEST safety and real offline behavior.
6. CI integration with mocked dependencies, staging, manual SMS validation and then manual deployment approval.

This proposal deliberately includes work that is **not present in this repository** (FastAPI on Selectel). No agent should mark the design implemented merely because this document is merged.
