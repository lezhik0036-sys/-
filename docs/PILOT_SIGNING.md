# MAMA — stable pilot signing key (mass field testing)

The pilot APK (`:app:assemblePilot`, real server, no fakes) is signed with a **stable
pilot keystore** so testers can **update-install** across pilot builds. It is separate
from the RuStore release key and is the **owner's secret** — created once, kept safe,
never committed. If all four env vars below are absent, the pilot build falls back to
the per-machine debug key (fine for a one-off first install, but not updatable).

Build wiring (already in `app/build.gradle.kts`): a `pilotRelease` signing config is
created **only** when all four `MAMA_PILOT_*` are present, and the `pilot` build type
uses it then; otherwise it uses the debug key.

## 1. Create the keystore once (owner, offline)

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
Actions). The workflow decodes the keystore to a temp file and signs with it; with the
secret unset it logs that the APK is debug-signed.

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
