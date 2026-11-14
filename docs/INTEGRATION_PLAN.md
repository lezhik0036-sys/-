# MAMA — Integration plan for physical-device testing

Status: planning only. **No production change, no real SMS, no RuStore.** This
document defines the single consistent change-set, the verifiable merge order,
the Android↔FastAPI compatibility check, the reproducible real-HTTPS APK build,
and the remaining blockers for a working MAMA build to test on real Android
phones.

Scope at time of writing (2026-11): Android app in `lezhik0036-sys/-`, FastAPI in
`lezhik0036-sys/mama-fastapi`, Ktor SMS adapter in `backend/` of the dash repo.

---

## 1. PR dependency graph and the single consistent change-set

### Android trunk
- **`claude/android-pilot-integrated`** (PR #89) — the single integrated Android
  pre-release (real-server Trusted Exit wired into BRICK start). Base of the
  family work. **Already contains the content of the Trusted-Exit security
  fixes #81 (merged) and #82** — verified: `HttpTrustedExitSetup.readiness()`
  on this branch uses the exact-period endpoint with `period_matches_requested`
  + `series_id`/`period_id` equality (that is #82), and `realBackendRequested`
  vs `isConfigured` is distinguished (that is #81).

### Family (the one that ships)
- **PR #97** `claude/android-family-flow` → `claude/android-pilot-integrated`.
  The **single** in-app «Семья» screen (`app.mama.ui.FamilyScreen`) over the
  **single** server-authoritative client `app.mama.family.FamilyClient`
  (+ `MamaFamily` composition root). CI green. **This is canonical** (owner
  decision).

### Superseded / excluded
- **PR #94** `codex/mama-family-pairing` → `codex/mama-rustore-release` — a
  **second, duplicate** family implementation: `app.mama.account.FamilyClient`
  + a separate `FamilyActivity` (raw views, not the design system). **Excluded**
  (duplicate of #97). Its one keepable asset — the real Android→FastAPI
  `FamilyLiveHttpTest` — has already been **ported to #97** against
  `app.mama.family.FamilyClient`.
- **PR #95** `codex/mama-release-artifact-guard` → `codex/mama-family-pairing` —
  **does NOT touch family code.** It (a) moves the fake Trusted-Exit backend
  (`FakeTrustedExitBackend`, `DeviceChallengeVault`, `TestCodeSink`,
  `DebugCodeSmsService`, `SimulatedSmsService`) out of `main` into
  `src/debug/kotlin` with a `src/userFacing/kotlin` release/pilot stub
  (`ReleaseTrustedExitDisabled`), so fakes are **physically absent** from
  release/pilot; (b) adds `.github/scripts/check-release-artifacts.py`, which
  audits the real release APK DEX and the Ktor jar for test markers; (c) forces
  `minSdk=33` for non-debug variants; (d) adds `BrickClockLifecycleTest`. It is
  coupled to #94 **only by branch ancestry, not by content** → its changes are
  **wanted** and must be **re-based onto the trunk+#97**, not carried in with #94.
- **PR #93** `codex/mama-rustore-release` → `claude/android-pilot-integrated` —
  "Integrate Ktor SMS dispatch and **prepare signed RuStore release**." The
  RuStore release parts are **excluded** (owner: no RuStore now). The Ktor SMS
  dispatch is already delivered by the #90/#91/#92 stack.

### Server (mama-fastapi)
- `api/app/family_api.py` — authenticated `/v1/family/*` routes (already present
  on the FastAPI deployment branch; run against real Postgres in `fastapi-ci.yml`).
- `api/app/family_consent.py` — `GuardianConsentProvider` seam. **Production
  default `DisabledGuardianConsent` → `/links` returns 503 (fail-closed).** The
  real verified provider does not exist yet (see Blockers).

### The single consistent change-set
```
claude/android-pilot-integrated      (trunk; has #81 + #82)
  └── + PR #97                        (the ONE «Семья» screen + FamilyClient)
        └── + content of PR #95       (fakes → src/debug; release-APK auditor; minSdk 33; clock test)
  EXCLUDE: PR #94 (duplicate family), PR #93 RuStore parts
Server: mama-fastapi family routes as-is; guardian consent stays DISABLED until a real provider.
```
Result: exactly one «Семья» screen, one `FamilyClient`, no test code in the
user build, no RuStore.

---

## 2. Verifiable merge order (verify after every step)

1. **Trunk baseline.** Confirm `claude/android-pilot-integrated` `build` job is
   green. (It carries #81+#82.)
2. **Merge #97 → trunk.** Family screen + client.
   - Verify: dash **`build`** job green (unit + Robolectric incl.
     `FamilyClientTest`, `FamilyScreenTest`). Already green on #97 head
     (run 444/448).
3. **Apply #95's content onto trunk+#97** (cherry-pick its non-family commits;
   do **not** merge #94). Resolve the known overlaps:
   - `app/src/main/kotlin/app/mama/MamaApp.kt` — keep **both** #97's
     `MamaFamily.configure(this, BuildConfig.MAMA_BACKEND_BASE_URL)` **and**
     #95's `TrustedExit.backend = … TrustedExitVariant.localBackend(this)`
     selection (the family line is a separate statement from the backend `when`).
   - Verify: `build` job green **and** the new
     `check-release-artifacts.py` step passes on a real `assembleRelease`
     (no `FakeTrustedExitBackend`/`DebugCodeSmsService`/… markers in the DEX;
     `https://api.mama-app.ru` present; Ktor jar has no `MockSmsProvider`).
4. **Drop #94 and #93-RuStore.** If #94 was already merged anywhere downstream,
   remove `app/src/main/kotlin/app/mama/account/FamilyClient.kt`,
   `app/src/main/kotlin/app/mama/ui/FamilyActivity.kt`, its `AndroidManifest`
   `<activity …FamilyActivity>` entry, `MamaAccount.familyClient()`, and its raw
   `Button` «Семья» entry in `settingsScreen()`; keep #97's
   `AccountHttpTransport.deleteJson` (both PRs add it — keep one).
   - Verify: `build` job green; `grep -r FamilyActivity app/src/main` empty;
     exactly one `class FamilyClient` under `app/src/main`.
5. **Server:** merge the FastAPI family routes branch via `fastapi-ci.yml`
   (family tests vs real Postgres green). Consent provider stays disabled.

### Known merge conflicts (and resolutions)
| File | #97 | #94/#95 | Resolution |
|---|---|---|---|
| `AccountHttpTransport.kt` | adds `deleteJson` | #94 also adds `deleteJson` | keep one (identical intent) |
| `HomeScreens.kt` `settingsScreen()` | DesignKit `actionRow` «Семья» | #94 raw `Button` «Семья» | keep #97 |
| `MamaApp.kt` | `MamaFamily.configure` | #95 rewrites `TrustedExit.backend` | keep both |
| `app/src/main/.../account/FamilyClient.kt`, `ui/FamilyActivity.kt` | n/a | #94 adds | drop (#97 supersedes) |

---

## 3. Android ↔ FastAPI family compatibility (verified)

`app.mama.family.FamilyClient` (#97) vs `api/app/family_api.py` — route-by-route:

| Client call | Server route | Match |
|---|---|---|
| `createInvitation` → POST `/v1/family/device-key-challenges {device_id}` | `issue_device_key_challenge` → `{challenge_id,nonce,expires_at}` | ✓ |
| … then POST `/v1/family/device-invitations {device_id,challenge_id,nonce,signature_b64}` | `create_invitation` → 403 on bad proof / existing invite, else `{invitation_token,expires_at}` | ✓ (client maps non-200 → `Failed`) |
| `consumeInvitation` → POST `/v1/family/links {invitation_token}` | `consume_invitation` → **503** (consent disabled), 404 (bad token), else `{status:linked, child_device_id}` | ✓ (`ConsentUnavailable`/`Failed`/`Linked`) |
| `listDevices` → GET `/v1/family/devices` | `list_devices` → `{devices:[{child_device_id,linked_at}]}` | ✓ |
| `revokeChildDevice` → DELETE `/v1/family/devices/{id}` | `revoke_parent_link` → `{status:revoked}` | ✓ (200 → true) |
| `revokeParentLinkAsChild` → DELETE `/v1/family/my-devices/{id}/parent` | `revoke_parent_link_as_child` → **401 if not RECENT auth**, else `{status:revoked}` | ✓ (401 → `RecentAuthRequired`; one fresh-login retry satisfies step-up) |

**Device-key proof is byte-identical**: client `proofMessage` =
`"MAMA/family/device-key-proof/v1\n" + join(user_id, challenge_id, device_id, nonce)`
(US-ASCII) == server `family_device_key_proof.challenge_message`. Proven in
`FamilyClientTest` (a fake server verifies the real Ed25519 signature exactly as
FastAPI does). **No integration fix is required** — the only "block" is the
intentional 503 from the disabled consent provider, which the client surfaces
honestly.

---

## 4. Reproducible single real-HTTPS APK (no fakes)

Build the installable **pilot** variant (internal distribution, debug-signed but
`isDebuggable=false`, `TEST_MODE=false`, mandatory validated HTTPS backend):

```bash
# From the dash repo root, on the assembled trunk+#97+#95 branch.
export MAMA_RELEASE_KEYSTORE=/abs/path/pilot.jks         # a real keystore you control
export MAMA_RELEASE_STORE_PASSWORD=…                      # never commit these
export MAMA_RELEASE_KEY_PASSWORD=…
export MAMA_RELEASE_KEY_ALIAS=…
./gradlew :app:assemblePilot -PmamaBackendBaseUrl=https://api.mama-app.ru
# APK: app/build/outputs/apk/pilot/app-pilot.apk
```

Guarantees (enforced, not asserted by hand):
- `-PmamaBackendBaseUrl` must be a valid `https://…` host with no
  credentials/path/query/fragment, enforced at configure time for pilot/release.
- With #95's source-set split, `FakeTrustedExitBackend` / `DebugCodeSmsService`
  / `DeviceChallengeVault` / `SimulatedSmsService` are **not compiled** into
  pilot/release — the real-HTTPS `TrustedExitVariant` stub returns
  `ReleaseTrustedExitDisabled`.
- `.github/scripts/check-release-artifacts.py` fails the build if any test
  marker appears in the release DEX or the Ktor jar, and requires
  `https://api.mama-app.ru` to be present.
- `MamaFamily`/`MamaTrustedExit` are fail-closed: no configured HTTPS backend or
  API < 33 → «Семья»/Trusted Exit show an explicit "unavailable" error; no fake
  pairing and no simulated BRICK exit.

CI should run `assemblePilot`/`assembleRelease` + the DEX audit on the final
branch before producing the test APK.

---

## 5. Remaining blockers (precise)

1. **Guardian-consent provider (server) — the #1 family blocker.**
   `family_consent.DisabledGuardianConsent` always returns `False`, so `/links`
   returns 503 and no parent↔child link is created on a live server. A real
   **verified** consent provider must be implemented server-side (what counts as
   guardian consent is a product/legal decision, never a client flag). Until
   then the app correctly shows the honest 503 state.
2. **Live Android `FamilyLiveHttpTest` run.** The full family lifecycle is
   already proven **server-side against real Postgres** (`test_family_api_http`,
   `test_family_invite_http`, `test_family_pairing_db` in `fastapi-ci.yml`) and
   the Android client's wire bytes are proven in `FamilyClientTest`. The one
   piece not yet automated is the **Android client over a live TLS socket**:
   `FamilyLiveHttpTest` is gated by `MAMA_FAMILY_E2E_DIR` and needs a
   cross-toolchain CI job (Android SDK + Python + Postgres + a TLS uvicorn + an
   approvable consent provider + a request/result fixture driver for
   approve/expire). Design in §6.
3. **Real SMS** (Trusted Exit contact verification; the family flow itself sends
   no SMS). SMS.RU provider is built (#90/#91/#92) but gated and untested against
   the live gateway: needs the `MAMA_SMSRU_API_ID` secret, a verified sender, and
   owner authorization. No real SMS from this work.
4. **Selectel production deploy** — gated; needs the migration + backup +
   rollback runbook (§7) and owner authorization. Not executed here.
5. **On-device verification** — Keystore-wrapped Ed25519 device key on real
   API 33/34 hardware, and no-SIM Wi-Fi tablet pairing, are not verified by unit
   tests (which use a software key behind the `DeviceKey` seam).

---

## 6. Live Android↔FastAPI family E2E — design (next step)

To make `FamilyLiveHttpTest` actually run (not skip), add one CI job with both
toolchains (extend the dash repo's `build.yml`, which already has the Android
SDK):

1. `services: postgres:17` (as `fastapi-ci.yml`).
2. Check out `mama-fastapi`; `pip install -r api/requirements.txt`;
   `alembic upgrade head` against the service DB.
3. Start uvicorn with a self-signed TLS cert on `127.0.0.1:18443`; write the
   cert to `$MAMA_FAMILY_E2E_DIR/tls.crt`. Install an **approvable** consent
   provider whose `verify()` returns true for tokens approved via the fixture.
4. Start a tiny **fixture driver** that watches `$MAMA_FAMILY_E2E_DIR` for
   `request` files and applies `op:"approve"` (grant consent for the token /
   parent) and `op:"expire"` (set an invitation's `expires_at` in the past),
   writing `result` = `ok`. (Protocol already encoded in `FamilyLiveHttpTest`.)
5. `./gradlew :app:testDebugUnitTest --tests "*.FamilyLiveHttpTest"` with
   `MAMA_FAMILY_E2E_DIR` set → the Robolectric test drives the real client over
   TLS against the live FastAPI.

The server-side half of this (an approvable provider + a `/links`-over-HTTP E2E
that exercises 503→approve→linked→list→revoke→recovery) can run **today** inside
`fastapi-ci.yml` against real Postgres and is the recommended first increment.

---

## 7. Real SMS + Selectel rollout (gated plan — do not execute without authorization)

**Real SMS (SMS.RU):** provide `MAMA_SMSRU_API_ID` as a server secret (never in
the repo/APK); register/verify the sender name; enable the provider for the
contact-verification route only; keep per-recipient rate limits and the
exact-recipient ACK check (#90). Validate against one consented test number
before any pilot. No bulk/marketing sending.

**Selectel deploy (blue-green / staged):**
1. **Backup first:** `pg_dump` the production DB to off-box storage; record the
   current image tag/commit.
2. **Migrate on a copy:** run `alembic upgrade head` against a restored copy;
   confirm `alembic current` == head and smoke-test `/v1/family/*` + Trusted
   Exit routes.
3. **Deploy new image** alongside old (blue-green); shift traffic after health
   checks; keep the old image hot.
4. **Rollback:** shift traffic back to the old image; if a migration must be
   undone, `alembic downgrade` to the recorded revision **or** restore the
   `pg_dump` (document which migrations are reversible; prefer expand/contract so
   a rollback never drops columns in use).
5. **Gate:** production DB credentials, the SMS.RU key, and the deploy are
   applied only after explicit owner authorization. This document plans; it does
   not deploy.
