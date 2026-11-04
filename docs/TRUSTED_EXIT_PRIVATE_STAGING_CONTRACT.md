# MAMA Trusted Exit — private service delivery contract (staging proposal v1)

**Status: DRAFT / DESIGN ONLY, NO LIVE HTTP TRANSPORT OR ENDPOINT ENABLED.**

This document joins the *currently independently tested* Ktor and FastAPI code.
It is not an instruction to deploy production Selectel, enable SMS.RU or release
a signed APK. Existing code is only a protocol encoder/verifier with fake
transports and isolated PostgreSQL tests.

## Scope and identities

1. Android on Wi-Fi or mobile network authenticates to FastAPI; no SIM or
   number is required on the locked device. Parent and child accounts must
   be separately authenticated, with approved device pairing and guardian
   consent. Never treat a QR token or client-provided account ID as authority.
2. FastAPI independently validates user ownership, currently registered
   nonrevoked device and *fresh per-operation Ed25519 key-possession proof*,
   active series and scheduled period, contact's current independently verified
   phone, and live BRICK integrity evidence. For an OTP verification grant,
   FastAPI additionally checks the Ktor challenge's own exact bindings
   through a **separately authenticated** private Ktor lookup.
3. FastAPI creates purpose-separated, 45s Ed25519 grants:
   - SMS request: kid `mama-ktor-sms-v1`, purpose `trusted-exit:sms-request`.
   - OTP verification: kid `mama-ktor-verify-v1`, purpose
     `trusted-exit:verify` and exact canonical `tec-UUID` challenge_id.
   No phone or OTP in the grant. Separate grants have separate JTIs and cannot
   substitute for each other.
4. Ktor alone creates/sends/verifies OTP, bound to its own durable challenge,
   provider delivery state, retry budget, TTL and replay ledger.
5. After correct OTP, Ktor mints exactly one Ed25519 signed receipt. Ktor
   transports it to FastAPI over its **private authenticated service channel**
   with TWO independent shared-key proofs (transport and evidence-ingest).
6. FastAPI independently verifies the receipt, exact current user/device/
   contact/period, provider SMS verification evidence, and server-only proofs,
   persists one JTI/challenge, and performs one separately authorized EARLY_EXIT
   plus cooldown. Android may unlock **only after** FastAPI's authoritative
   completed-series status, never on a Ktor `VALID` or transport 2xx.

## Proposed service HTTP request (not implemented)

Method: `POST`
Path: `/internal/trusted-exit/evidence`

Body raw UTF-8 JSON bytes, exact two-field shape (never phone, OTP or Android session):

```json
{"token":"<signed-Ktor-compact-JWS>","macB64":"<base64-HMAC-over-receipt>"}
```

Evidence-specific ingest proof already implemented in both repos:

```text
HMAC-SHA256(evidence_ingest_key,
  ASCII("MAMA/ktor-verified-otp-ingest/v1\n") + ASCII(token))
```

**Separately** authenticate the private HTTP request using Ktor's new
`KtorInternalRequestSigner` / FastAPI
`verify_and_reserve_ktor_internal_request`:

```text
request_mac_input =
  ASCII("MAMA/ktor-fastapi-request/v1\n")
  + ASCII("POST\n")
  + ASCII(private_path + "\n")
  + ASCII(unix_timestamp_seconds + "\n")
  + ASCII(canonical_lowercase_UUIDv4_nonce + "\n")
  + ASCII(sha256_hex(raw_body_bytes))
request_mac_hex = hex(HMAC-SHA256(transport_key, request_mac_input))
```

Proposed headers (final names need a joint contract review):
`X-MAMA-Timestamp`, `X-MAMA-Nonce`, `X-MAMA-Request-MAC`.
Never log values from these headers, JWT or phone. Require raw body ≤4096
bytes; timestamp within ±30s; nonce unique in FastAPI PostgreSQL across all
private paths. A request with a duplicate nonce is **not** idempotently
accepted. FastAPI's nonce table is a single-use replay gate. Keep nonces at
least beyond the timestamp validity window; plan retention and rotation.

The **transport** service key must differ from the **evidence ingest** key;
neither is an Ed25519 signing key. Keep keys only in a protected server secret
manager, no Android, Git, logs or URLs. Use proper pinned peer certificates and
mutual TLS in addition to HMAC; network address/IP allowlist alone is insufficient.

### Expected HTTP outcomes (proposal, NOT implemented)

- Successful initial receipt admission: 202, **evidence recorded only**; do not
  tell Android that BRICK has ended.
- Bad mTLS identity, MAC, stale timestamp, nonce replay, wrong receipt signature,
  wrong contact/device/series, duplicate JTI or expired receipt: fail closed,
  with generic non-sensitive error. Never differentiate secrets to Android.
- Lost response/timeout: Ktor state is **Unknown**; blind POST retry may return
  replay denial, which must NOT be interpreted as proof of successful or failed
  first admission.
- Reconcile via a **distinct authenticated status query**. Short-lived receipt
  JWT can expire before Android recovers, so its long-term recovery must use
  FastAPI's durable account-owned EARLY_EXIT + cooldown transition with current
  independently authenticated account/device, not a refreshed OTP token.
- No local Android fake OTP acceptance in release. Release readiness defaults
  false until the complete service path passes staging tests.

## Staging go/no-go

- Isolated Ktor and FastAPI test hosts, private non-production PostgreSQL,
  ephemeral signing/service keys, fake SMS provider, synthetic contacts.
- Add a real mTLS transport implementation with certificate pinning and strict
  redirects disabled; separate private ingress/egress network boundaries.
- Start with invalid peer cert, body tamper, stale/noncanonical nonce, rotated
  key, replay, expired JWT, concurrent delivery and dropped ACK tests.
- Connect Android **staging/debug only** to authenticated FastAPI, test on
  SIM-equipped phone and Wi-Fi-only tablet. No personal phone number needed on
  tablet, OTP goes to a different trusted contact's handset only after consent.
- A test BRICK window must be bounded and safely recoverable on a spare
  device, never trap a user because SMS or Wi-Fi is unavailable; OS emergency
  access must remain intact where supported by hardware.
- Confirm one and only one EARLY_EXIT/cooldown, device lockdown until
  authoritative server status, no PII/OTP in logs; include restart/rollback.
- Explicit new owner authorization before provisioning staging infrastructure,
  sending any paid real SMS, deploying to Selectel or uploading to RuStore.

## Implemented as of this draft

- Ktor PR #31 private challenge ownership logic; #32 HMAC signer;
  #33 disabled authenticated evidence delivery adapter;
  #34 fail-closed production readiness.
- Private FastAPI PR #15 signed evidence admission; #16/#17 reconciliation;
  #18 contact recheck; #21 three-step server-side transition test;
  #22 device operation key-possession challenges; #30 parent link foundation;
  #31 grant wire encoder; #33 request HMAC and nonce DB ledger;
  #34 three-proof server-side integration simulation.

These are **stacked drafts**, not merged or deployed. The next milestone is
a reviewed, private mTLS implementation on isolated test hosts, not RuStore
publication or real SMS by default.
