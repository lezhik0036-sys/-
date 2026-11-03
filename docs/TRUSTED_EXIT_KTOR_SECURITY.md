# Trusted Exit — Ktor security (Issue #6, draft, test-only)

Server-authoritative Trusted Exit hardening on the Ktor side. **Not production-ready**,
not deployed, no real SMS, no APK. Pairs with Issue #3 and the FastAPI design
contract (draft PR #4 `docs/TRUSTED_EXIT_SECURITY_CONTRACT.md`).

## 1. Trust model

- Ktor runs **no user auth** and never reads FastAPI sessions/DB. The only trust
  anchor is a FastAPI-signed **capability grant** (protocol v0), verified by
  `auth/GrantVerifier.kt` against a **pinned Ed25519 public key** (env
  `MAMA_GRANT_PUBLIC_KEY`). No private key exists in this repo.
- Grant policy (hard, no agility): `alg=EdDSA` only (reject `none`/any other),
  `typ=JWT`, `kid=mama-ktor-sms-v1`; `iss=mama-fastapi`, `aud=mama-ktor-trusted-exit`,
  `purpose=trusted-exit:sms-request`; **exact** claim set
  `{iss,aud,purpose,sub,series_id,period_id,device_id,contact_id,jti,iat,nbf,exp}`;
  UUIDs canonical lower-case; lifetime ≤ 60s; ≤ 5s future skew; single pinned key
  (no `jku`, no URL fetch, no client-supplied destination).
- **Identity and bindings come only from the grant.** There is no client
  `sessionId`/`contactPhone` in the request body; the SMS destination is resolved
  server-side (§5). The verify endpoint checks the challenge is owned by the
  grant's `sub/series_id/period_id/device_id/contact_id` (else 403).

## 2. Durable storage (PostgreSQL, Ktor-owned schema)

`db/Database.kt` migrates schema `trusted_exit` (never FastAPI's tables); a
dedicated DB role is expected in production. Tables:

- `challenge(id PK, jti UNIQUE, user_id, series_id, period_id, device_id,
  contact_id, salt, code_hash, attempts_left, status, issued_at, expires_at,
  cooldown_until, sms_ref, send_attempts)` — no plaintext OTP; only a
  peppered+salted **HMAC-SHA256** verifier (`OtpHasher`, pepper from env
  `MAMA_OTP_PEPPER`).
- `jti_ledger(jti PK, redeemed_at)` — one-time spend of a grant.
- `rate_counter(scope, window_start, count, PK(scope,window_start))`.

No in-memory maps are used for any auth decision.

## 3. Atomicity (concurrency + restart safe)

- **Idempotent create / no duplicate SMS:** `INSERT … ON CONFLICT (jti) DO NOTHING`.
  The inserting caller sends; a concurrent/retried caller with the same grant
  reuses the row (no second SMS). Proven by `concurrent requests … at most one SMS`.
- **Send lifecycle (no duplicate provider calls):** explicit statuses
  `PENDING_SEND → SENDING → SENT/SEND_FAILED`. Exactly one worker claims via
  `UPDATE … SET status='SENDING' WHERE id=? AND status='PENDING_SEND'`. A
  provider timeout leaves the row `SENDING` (reported as `UNKNOWN`); a retry does
  not re-claim, so there is no immediate second SMS.
- **One-time verification:** `SELECT … FOR UPDATE` then conditional update in one
  transaction. A used code is `CONSUMED`; a second concurrent verify blocks then
  sees `CONSUMED → Unknown`. Wrong codes decrement `attempts_left`; the third
  locks with `cooldown_until`. On success/lock the challenge's `jti` is written to
  `jti_ledger`, so a replayed grant cannot mint a new challenge. All state is in
  PostgreSQL, so it survives a process restart (proven by the restart test).
- **One-time JTI:** `jti_ledger` `INSERT … ON CONFLICT DO NOTHING`, checked before
  create.

## 4. Anti-abuse / rate limits

`RateLimiter` via `rate_counter`, reserved in ONE transaction **before** any send;
if any key is over budget the whole reservation rolls back (no partial charge)
and the request is denied with no SMS. Keys: `user(sub)`, `device`, `contact`,
`ip`, and a `global` budget, each a windowed counter. Defaults in `RateLimits`.
Retention via `OtpRepository.cleanup` (run periodically; documented, not a cron yet).

**Authoritative side:** Ktor is authoritative for OTP attempts/cooldown and for
the per-request SMS budget tied to a grant; FastAPI is authoritative for *issuing*
grants (who/when a grant may exist at all). Because a grant is required before Ktor
does anything and the `jti` is one-time, FastAPI's issuance throttle and Ktor's
send budget compose without double-send: one grant ⇒ at most one SMS.

## 5. Destination resolution (never from the client)

`ContactDestinationResolver` resolves the phone for `contact_id` from a trusted
server source. The production resolver is **a stub returning null (fail-closed)**
until the FastAPI pinned, internally-authenticated resolution/proxy is implemented
(Deliverable B). So this increment cannot send even when otherwise configured —
by design.

## 6. Routes / fail-closed

`/trusted-exit/request` and `/verify` require a valid grant (401 missing/invalid,
403 binding mismatch, 429 rate-limited, 503 not-configured/destination). `/health`
is liveness; `/ready` is strict (200 only when grant key + DB + SMS are all present,
503 otherwise) and leaks no secrets. Server errors never consume a verify attempt
(the attempt counter moves only on an explicit server INVALID), and a transient is
never VALID — so the Android client maps them to retry, never to a bypass.

## 7. Deliverable B — authenticated integration contract (design)

1. **Issuance:** FastAPI (which owns identity, the registered trusted contact, the
   device key proof and the live-lock policy) issues the grant **only** when its
   own gates pass. Grant issuance is currently **disabled**; no permissive test
   bypass is added to production.
2. **Destination:** Ktor resolves the phone via a pinned, internally-authenticated
   FastAPI call/proxy keyed by `contact_id` — never the client. (Stub = null now.)
3. **Redemption → series transition:** a successful OTP verify must be redeemed
   **once** in FastAPI to authorize the server-side series/period transition
   (FastAPI owns the state machine). Proposed: Ktor calls a pinned FastAPI
   "redeem" endpoint with the verified `(jti, challenge_id, bindings)`; FastAPI
   enforces its own one-time redemption. **Open item:** verify currently reuses the
   protocol-v0 request grant (binding-checked); a dedicated `purpose=trusted-exit:verify`
   grant or a redemption token should be defined with FastAPI.
4. **No bypass:** client-chosen `sessionId/contactPhone` are not accepted anywhere;
   bindings are the signed claims only.

## 8. Answers to the issue's questions

- **Can Ktor be tested fully without signing/private keys or real FastAPI routes?**
  Yes for everything on the Ktor side. Tests generate an **ephemeral** Ed25519
  keypair, sign grants with it and configure the verifier with its public key;
  durability/concurrency run against **ephemeral PostgreSQL** with a **fake SMS**
  provider. No production key and no live FastAPI route are needed. What cannot be
  tested here is the real FastAPI issuance/destination/redemption (they live in
  `mama-fastapi` and are disabled).
- **What is missing for the first safe two-phone test?**
  (a) FastAPI grant issuance enabled behind its gates (device key proof, trusted-contact
  verification provider, live-lock policy); (b) the pinned FastAPI destination
  resolution so Ktor can get the contact phone; (c) the FastAPI one-time redemption
  endpoint for the series transition; (d) a real pinned `MAMA_GRANT_PUBLIC_KEY`,
  `MAMA_OTP_PEPPER`, DB and SMS.RU key in a **staging** deploy; (e) the Android
  client obtaining the grant from FastAPI and sending it as a Bearer (a follow-up to
  PR #2, not merged here).
- **Smallest handoff for ChatGPT and the user:**
  1) Confirm the verify-side grant shape (reuse request grant vs a dedicated verify
  purpose/redemption token). 2) Implement/enable on FastAPI: destination resolution,
  grant issuance behind gates, one-time redemption. 3) Provide a **staging**
  `MAMA_GRANT_PUBLIC_KEY` (public only), `MAMA_OTP_PEPPER`, Ktor DB, SMS.RU key —
  none in Git. 4) Then wire the Android grant fetch + Bearer. Only after staging is
  green does a single real two-phone SMS test make sense. Nothing here is
  production-ready and nothing is merged.
