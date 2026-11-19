# MAMA — stable pilot signing key (mass field testing)

The pilot APK (`:app:assemblePilot`, real server, no fakes) is signed with a **stable
pilot keystore** so testers can **update-install** across pilot builds. It is separate
from the RuStore release key and is the **owner's secret** — created once, kept safe,
never committed.

**The pilot APK can never be debug-signed.** Any `:app:assemblePilot` build is **refused
at configuration** unless all four `MAMA_PILOT_*` are set — there is no flag, CI, or
local-dev path that produces a debug-signed pilot APK. The `real-server-pilot` workflow
additionally fails up front if the four `MAMA_PILOT_*` Actions secrets are missing, and
CI proves the refusal (a pilot build with no keystore is expected to fail).

Build wiring (already in `app/build.gradle.kts`): a `pilotRelease` signing config is
created **only** when all four `MAMA_PILOT_*` are present. Without it the `pilot` build
type has **no signing config at all** (`signingConfig = null`) — there is no `else
"debug"` fallback — so even a task path that bypasses the explicit `assemblePilot`
refusal (the aggregate `build`/`assemble`, whose requested task name has no "Pilot")
produces an **unsigned, non-installable** APK, never a debug-signed one. CI proves both:
`assemblePilot` is refused, and `:app:signingReport` shows the `pilot` variant never maps
to the debug key.

## 1. Create the keystore once (owner, offline)

Fastest path — the helper script does the keytool call, generates a strong password,
writes a protected local backup (`mama-pilot.secrets.txt`), prints the cert SHA-256, and
can set the four Actions secrets for you:

```
scripts/make-pilot-keystore.sh                # create-if-absent OR reuse existing; local backup
scripts/make-pilot-keystore.sh --set-secrets  # also set the 4 Actions secrets via gh
scripts/make-pilot-keystore.sh --print-fingerprint   # print the cert SHA-256 of the existing key
```

The script is **idempotent and never overwrites an existing keystore**: if
`mama-pilot.jks` already exists it **reuses the owner's key** (supply its password via
`MAMA_PILOT_STORE_PASSWORD` or the prompt), re-derives the signing-cert SHA-256, and can
re-set the Actions secrets from that existing key — so the fleet identity created once is
preserved across runs. The printed `SIGNING_CERT_SHA256_NOCOLON` is the value to pass as
`EXPECTED_CERT_SHA256` to `deploy/release-kit/apk-check.sh` when verifying a built APK.

Or do it by hand:

```
keytool -genkeypair -v \
  -keystore mama-pilot.jks -storetype PKCS12 \
  -alias mama-pilot -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=MAMA Pilot, O=MAMA, C=RU"
# choose a strong store password; reuse it as the key password (PKCS12 uses one).
```

**Back it up.** Losing `mama-pilot.jks` or its password means the fleet can no longer
receive updates (a new key = a new app identity testers must uninstall/reinstall).
Keep an encrypted copy off the build machine.

## 2. Use it locally

```
export MAMA_PILOT_KEYSTORE=/secure/path/mama-pilot.jks
export MAMA_PILOT_STORE_PASSWORD=...        # the store password
export MAMA_PILOT_KEY_ALIAS=mama-pilot
export MAMA_PILOT_KEY_PASSWORD=...          # the key password (same as store for PKCS12)
./gradlew :app:assemblePilot -PmamaBackendBaseUrl=https://api.mama-app.ru
# -> app/build/outputs/apk/pilot/app-pilot.apk, signed with the stable pilot key.
```

## 3. Use it in CI (the `real-server-pilot` workflow)

Add these **GitHub Actions repository secrets** (Settings → Secrets and variables →
Actions), or let `scripts/make-pilot-keystore.sh --set-secrets` set them. The workflow
decodes the keystore to a temp file and signs with it; **with any of them unset the
workflow fails** (it will not emit a debug-signed user APK).

| Secret | Value |
|---|---|
| `MAMA_PILOT_KEYSTORE_B64` | `base64 -w0 mama-pilot.jks` (the keystore, base64, one line) |
| `MAMA_PILOT_STORE_PASSWORD` | the store password |
| `MAMA_PILOT_KEY_ALIAS` | `mama-pilot` |
| `MAMA_PILOT_KEY_PASSWORD` | the key password |

```
base64 -w0 mama-pilot.jks            # paste the output as MAMA_PILOT_KEYSTORE_B64
# (macOS: base64 -i mama-pilot.jks | tr -d '\n')
```

Then run the pilot build: **Actions → real-server-pilot → Run workflow** with
`backend_url=https://api.mama-app.ru` (needs the live API). The run uploads the signed
`MAMA-…-real-server-pilot.apk` + its SHA-256 and prints the signing-cert SHA-256 so you
can confirm every pilot build shares one identity.

## Security
- Never commit the keystore or passwords; they live only in the owner's vault and in
  encrypted Actions secrets.
- The pilot key signs internal-distribution APKs only; the RuStore release key stays
  separate and out of scope for now.
