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
- **Strict JWT parsing** (mirrors the FastAPI verifier): header key set is EXACTLY
  `{alg,typ,kid}` (no `jku`/`x5u`/`crit`); all three segments must be canonical
  base64url (no padding, no `+`/`/`); **duplicate JSON keys are rejected** in both
  header and payload (a lenient parser would silently keep the last); timestamps
  are **integer** NumericDate only (a fractional or string token is rejected);
  `nbf ≥ iat`. See `GrantVerifierTest` for the negative cases.
- **Public-key format contract (no private keys exchanged).** Ktor's
  `MAMA_GRANT_PUBLIC_KEY` is the base64 of the Ed25519 **X.509 SubjectPublicKeyInfo**
  (SPKI) DER — what `KeyFactory("Ed25519")` + `X509EncodedKeySpec` consume. FastAPI's
  verifier currently takes the **32 raw** Ed25519 public-key bytes. Conversion
  (public material only): SPKI DER = the 12-byte prefix
  `302a300506032b6570032100` ++ the 32 raw bytes; base64 that for Ktor. Reverse:
  the last 32 bytes of the SPKI DER are the raw key. No signing/private key exists
  or should be added on the Ktor side.
- **Identity and bindings come only from the grant.** There is no client
  `sessionId`/`contactPhone` in the request body; the SMS destination is resolved
  server-side (§5). The verify endpoint checks the challenge is owned by the
  grant's `sub/series_id/period_id/device_id/contact_id` (else 403).

## 2. Durable storage (PostgreSQL, Ktor-owned schema)

`db/Database.kt` migrates schema `trusted_exit` (never FastAPI's tables); a
dedicated DB role is expected in production. Tables:

- `challenge(id PK, jti UNIQUE, user_id, series_id, period_id, device_id,
  contact_id, salt, code_hash, attempts_left, status, issued_at, expires_at,
  cooldown_until, sms_ref, send_attempts, receipt)` — no plaintext OTP; only a
  peppered+salted **HMAC-SHA256** verifier (`OtpHasher`, pepper from env
  `MAMA_OTP_PEPPER`). `receipt` holds the one verified-OTP proof minted on
  consume (§3b), so a retry returns the same one.
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
- **Deliverable-status gate (fail-closed):** a challenge is verifiable ONLY while
  its status is `SENT`. `PENDING_SEND`, `SENDING` and `SEND_FAILED` can never be
  turned into a `VALID` even if the code is somehow known — so a provider timeout
  (left `SENDING`, reported `UNKNOWN`) or an unsent/failed challenge is unusable.
  A verify against a non-deliverable status returns a non-committal `Unknown` and
  does **not** consume an attempt (attempts preserved; the code is never even
  compared). Tested for every forbidden status and for the provider-timeout case.
- **One-time JTI:** `jti_ledger` `INSERT … ON CONFLICT DO NOTHING`, checked before
  create.

## 3a. `VALID` is a match result, NOT an unlock authorization

A `VALID` from `/verify` means only that the entered OTP matched a delivered
challenge. On its own it MUST NOT end the phone lock. When receipt signing is
configured it carries a signed **verified-OTP receipt** (§3b) for Android to
forward to FastAPI; the server-authoritative series/period transition is owned by
FastAPI, which authenticates the internal caller, persists the receipt once and
performs the transition. Without a signing key, `VALID` carries no receipt and is
informational only — nothing can unlock (fail-closed).

This also resolves a lifetime mismatch: the request grant lives ≤ 60s while the OTP
lives 15 min, so the request grant is normally expired by the time the user types
the code. Verify therefore authenticates on the **signed bindings** of a *freshly
issued* grant (new `jti`, same `sub/series_id/period_id/device_id/contact_id`),
never the one-time request `jti`; the challenge is still single-use. The clean
long-term answer is a dedicated `purpose=trusted-exit:verify` grant or a redemption
token minted at verify time — an open item for FastAPI (§7.3).

## 3b. Verified-OTP receipt (test-only, issue #8 / FastAPI PR #13)

On a successful verification Ktor mints a **verified-OTP receipt**: a Ktor-signed
Ed25519 compact JWT that attests the OTP matched, for FastAPI to redeem. It matches
the FastAPI `trusted_exit_ktor_receipt_format.py` verifier (proposal v1) exactly:

- header `{alg:EdDSA, typ:JWT, kid:mama-ktor-exit-v1}`; issuer
  `mama-ktor-trusted-exit`, audience `mama-fastapi`, purpose
  `trusted-exit:verified-otp`;
- exact claims `{iss,aud,purpose,sub,series_id,period_id,device_id,contact_id,
  challenge_id,jti,iat,nbf,exp,verified_at}`; canonical lower-case UUIDs;
  `challenge_id = tec-<uuid>`; integer NumericDate; `nbf≥iat`, `exp>nbf`,
  `exp-iat≤60`, `verified_at∈[iat-60, iat]`; no phone and no OTP.
- **Bindings come from the server-owned challenge row, never the client.**

**Minted once, inside the consume transaction, and returned only to the consuming
caller.** The receipt is produced in the same `verifyAndConsume` transaction that
flips the challenge to `CONSUMED` and is persisted in `challenge.receipt`, so a
receipt exists **iff** the challenge is consumed — it is impossible to issue two
for one OTP. It is returned **only** to the caller whose `/verify` performed that
one-time consumption; a parallel burst of correct-code verifies yields exactly one
authorized caller with the receipt and the rest `Unknown`
(`parallel verification yields exactly one authorization and one minted receipt`).

**A consumed challenge never re-exposes its receipt (fail closed).** Once `CONSUMED`,
`verifyAndConsume` returns `Unknown` unconditionally — the stored receipt is never
handed back, not even for the correct code or the same grant. Re-handing a stored
signed proof to any binding-compatible caller would be an oracle (a fresh grant +
challenge id + an *arbitrary* code could harvest it without proving the OTP was
ever known), and a 45–60s receipt could be re-exposed after its own expiry. So
network-loss recovery (returning the same proof to a client that lost the HTTP
response) is **deferred**: it requires a dedicated, independently authenticated,
one-time recovery credential proving the original successful verification — a
cross-service item to design with FastAPI (§7), not a replay of grant/challenge/code.
With no signer configured, a successful verify is a bare `VALID` and a consumed
challenge is likewise `Unknown`.

**Keys (no private key in Git).** Ktor signs with an Ed25519 private key from
`MAMA_RECEIPT_SIGNING_KEY` (PKCS#8 DER, base64), **off by default** — tests use an
ephemeral in-process keypair. FastAPI pins the **raw 32-byte** public key; the JVM
emits X.509 SPKI, so ops strip the 12-byte prefix `302a300506032b6570032100`
(`ReceiptSigner.rawPublicFromSpki`) to export the raw key and distribute the
private key only through a secret store. Compatibility is proven two ways offline:
`KtorReceiptFormatTest` is a faithful Kotlin port of the FastAPI verifier (positive
+ every tamper/expiry/binding negative), and the minted signature also verifies
under an independent implementation (OpenSSL / `Ed25519PublicKey.from_public_bytes`).

## 4. Anti-abuse / rate limits

`RateLimiter` via `rate_counter`, reserved in ONE transaction **before** any send;
if any key is over budget the whole reservation rolls back (no partial charge)
and the request is denied with no SMS. Keys: `user(sub)`, `device`, `contact`,
`ip`, and a `global` budget, each a windowed counter. Defaults in `RateLimits`.
Retention via `OtpRepository.cleanup` (run periodically; documented, not a cron yet).

**The `ip` key is not attacker-forgeable.** `X-Forwarded-For` is honoured only when
the actual socket peer is an explicitly configured trusted proxy
(`MAMA_TRUSTED_PROXIES`); otherwise the socket address is used and the header is
ignored. With no proxies configured (the test-only default) XFF is always ignored,
so a client cannot vary a header to dodge or inflate the per-IP quota. When the peer
is trusted, the client is the right-most forwarded hop that is not itself a trusted
proxy (`resolveClientIp`, with a forged-XFF test). The other quota keys come from the
signed grant, not from any client input.

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
never VALID — so the Android client maps them to retry, never to a bypass. `VALID`
itself is a match result, not an unlock authorization (§3a): the lock ends only
after the FastAPI single-use redemption and series transition (§7.3).

## 7. Deliverable B — authenticated integration contract (design)

1. **Issuance:** FastAPI (which owns identity, the registered trusted contact, the
   device key proof and the live-lock policy) issues the grant **only** when its
   own gates pass. Grant issuance is currently **disabled**; no permissive test
   bypass is added to production.
2. **Destination:** Ktor resolves the phone via a pinned, internally-authenticated
   FastAPI call/proxy keyed by `contact_id` — never the client. (Stub = null now.)
3. **Redemption → series transition:** a `VALID` is only an OTP match (§3a), never
   the unlock. Ktor now mints the signed **verified-OTP receipt** (§3b) as that
   proof; it must be redeemed **once** in FastAPI to authorize the server-side
   series/period transition (FastAPI owns the state machine). The receipt format is
   agreed (FastAPI PR #13 verifier ↔ Ktor `ReceiptSigner`, cross-checked offline).
   **Still on the FastAPI side (cross-service blockers for issue #8):**
   (a) a service-authenticated internal endpoint that *accepts* the receipt
   (ingestion is DISABLED in PR #12); (b) durable one-time redemption keyed by the
   receipt `jti`/`challenge_id` with a live re-check of series/device/period/contact;
   (c) the authenticated internal transport (mTLS / internal token) between Ktor and
   FastAPI; (d) the inbound verify credential — a dedicated `purpose=trusted-exit:verify`
   grant (or redemption token) carrying `challenge_id`, since verify today
   authenticates on the binding-checked request grant (a freshly issued one; the
   ≤60s request grant has expired by verify time, §3a); (e) a **network-loss recovery
   credential** — an independently authenticated, one-time secret proving the original
   successful verification, so a client that lost the first response can re-obtain the
   proof without the current fail-closed `CONSUMED → Unknown` becoming a receipt oracle
   (§3b). Android ends the lock only on FastAPI's authoritative result, never on
   `VALID`/the receipt alone.
4. **No bypass:** client-chosen `sessionId/contactPhone` are not accepted anywhere;
   bindings are the signed claims / the server-owned challenge row only.

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
