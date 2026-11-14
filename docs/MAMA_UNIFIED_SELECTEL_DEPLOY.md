# MAMA — unified Selectel deployment bundle (FastAPI + Ktor + Android)

One runbook that ties the three MAMA services together for the **existing
Selectel host**, plus the installable pilot APK that talks to them. It is a
**preparation** document: it changes nothing in production by itself, sends no
SMS, and prepares no RuStore release. The gated steps are marked **GATED** and
need the owner's explicit go-ahead.

Per-service detail lives in:
- FastAPI: `mama-fastapi` → `docs/SELECTEL_STAGING_DEPLOY.md`, `deploy/staging/`
- Ktor worker: this repo → `backend/DEPLOY.md`, `backend/docker-compose.yml`
- Family gate 2 (operator authority): `mama-fastapi` →
  `docs/FAMILY_GUARDIAN_AUTHORITY_OPERATOR.md`

## Topology on the single host

```
          Internet ──TLS──> api.mama-app.ru  (Caddy/nginx)
                               │  reverse-proxy
                               ▼
   FastAPI (Uvicorn) 127.0.0.1:8000  ──HTTPS(dispatch)──>  Ktor worker (private)
        │                                                      │
        ▼                                                      ▼
   PostgreSQL (prod DB)                                    SMS.RU  (GATED)
```

- FastAPI is the account/Trusted-Exit/Family authority tier; it is the **only**
  tier the Android app talks to.
- The Ktor worker is private (no public port); FastAPI reaches it over HTTPS with
  a shared dispatch secret. Only the Ktor worker ever talks to SMS.RU.
- Keep ports clear of collisions: prod Uvicorn `127.0.0.1:8000`, the owner's
  provisional nginx `127.0.0.1:8081`, staging `127.0.0.1:18081` (see the staging
  env template). Staging uses its own DB name + volume, never the prod DB.

## Secret matrix — what goes where (never in the APK, never in git)

| Secret | FastAPI | Ktor | Android |
|---|---|---|---|
| `POSTGRES_*` | ✓ | — | — |
| `MAMA_INGEST_SHARED_SECRET` (early-exit) | ✓ | ✓ (must match) | — |
| `MAMA_CONTACT_VERIFICATION_KTOR_DISPATCH_SECRET` | ✓ | ✓ (must match) | — |
| `MAMA_GRANT_SIGNING_KEY` / `MAMA_PINNED_KTOR_PUBLIC_KEY` | ✓ | paired | — |
| `MAMA_SMSRU_API_ID` | — | ✓ only | — |
| `MAMA_FAMILY_OPERATOR_KEY` (gate 2) | ✓ only | — | — |
| backend base URL | — | — | build-time `-PmamaBackendBaseUrl` |

Every `MAMA_*` secret left blank keeps that capability **fail-closed** (disabled,
503), so the stack is safe to bring up before the owner provisions secrets.

## Bring-up order (staging first; production is GATED)

1. **Postgres** up with its own DB/volume.
2. **FastAPI**: `python -m alembic -c alembic.ini upgrade head` (chain now ends at
   `bb2c3d4e5f60`, the guardian-authority table), then start Uvicorn. Confirm the
   non-secret readiness summary; everything unprovisioned reads `false`.
3. **Ktor worker**: start with its dispatch + SMS secrets (SMS itself **GATED**).
4. **Verify wiring** without sending SMS: FastAPI readiness shows the Ktor
   channels enabled only when both URL + secret are set.
5. **Enable family gate 2** when wanted: set `MAMA_FAMILY_OPERATOR_KEY`, restart,
   confirm `"family_operator_enabled": true`, then grant per-pair authority via
   the operator runbook. No automatic approvals.
6. **Android** points at the HTTPS host at build time (next section).

Rollback: each service doc above has its own rollback; the family gate-2 table
has a reversible migration (`alembic downgrade -1`), and clearing
`MAMA_FAMILY_OPERATOR_KEY` disables new grants immediately.

## Installable pilot APK (real server, no fakes)

The `pilot` build type is the real-server, installable APK: `TEST_MODE=false`,
no local OTP, no test code, no simulated credits; the backend URL is mandatory
and validated (HTTPS, no credentials/path/query). It is debug-signed for
**internal distribution only** (not RuStore).

```
./gradlew :app:assemblePilot -PmamaBackendBaseUrl=https://api.mama-app.ru
# -> app/build/outputs/apk/pilot/app-pilot.apk
```

Sideload this APK to the pilot phones. A Wi-Fi-only tablet (no SIM) works for the
whole family flow — the credential is the Ed25519 device key, not a phone number;
the app does not request telephony permissions it cannot honor and states the
non-cellular limitation honestly.

Family pairing on the pilot build (end to end):
1. Child device → «Семья» → create a code; read it to the parent.
2. Child enters the parent's id (shown on the parent's «Семья» screen) and
   confirms that parent (gate 1 / `POST /v1/family/consents`).
3. Operator records guardian authority for the pair (gate 2) — see the runbook.
4. Parent enters the code → the link forms. Until both gates hold the app shows
   an honest "awaiting child consent" / "awaiting guardian authority" message.

## GATED — needs the owner's explicit go-ahead (not done here)

- **Real SMS**: provisioning the SMS.RU key on the Ktor worker and enabling live
  dispatch.
- **Production migration**: running the bring-up against the production DB/host
  (take a backup first; staging must pass first).
- **RuStore release**: `assembleRelease` requires the owner's release keystore
  (`MAMA_RELEASE_KEYSTORE` + passwords/alias) and `-PmamaBackendBaseUrl=https://api.mama-app.ru`.
  Signing keys are the owner's and never enter this repo or any session.

## Dependency PRs (land before deploying the family flow)

- `mama-fastapi#88` — two-gate family consent + guardian authority on
  `claude/mama-prerelease-integration` (gate 1 child consent + gate 2 operator
  authority + this bundle's `MAMA_FAMILY_OPERATOR_KEY`). Consolidates the earlier
  main-based `#86`/`#87`.
- This repo: the Android gate-1 consent client PR (`/v1/family/consents` +
  parent naming + both 503 states) — completes the app side of the two-gate flow
  on `claude/android-pilot-integrated`.
