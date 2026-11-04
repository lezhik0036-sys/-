# MAMA Android — Claude Code handoff

MAMA is an Android application with BRICK Mode: a user-configured interval in their local time zone. Series 3/5/7; trusted contact; no user-defined app allowlist. Sleep is the main presentation, not the only use case.

## Current engineering focus
- Real Trusted Exit via authenticated FastAPI + Ktor SMS, never local SMS-based authority.
- Parent master account and explicit child-device pairing over Wi-Fi (child device may have no SIM).
- Android implementation must not silently gain a family-parent privilege from a local role flag or QR code.
- Offline behavior must not unlock an active BRICK session.
- Preserve Samsung and Android emergency handling and make non-cellular tablet limitations explicit.

## Working PRs
- Android family policy: https://github.com/lezhik0036-sys/-/pull/37
- Wi-Fi-only permission onboarding: https://github.com/lezhik0036-sys/-/pull/26
- HTTP Trusted Exit prototype: https://github.com/lezhik0036-sys/-/pull/2
- Ktor readiness release safety: https://github.com/lezhik0036-sys/-/pull/34
- FastAPI family pairing work: https://github.com/lezhik0036-sys/mama-fastapi/pull/35

## Security and safety
- No production Selectel deployment, external paid SMS, real purchases or RuStore publishing from this branch.
- Never put signing private keys, SMS.RU API keys, auth secrets, a test unlock code, or an alternate BRICK bypass in release APK.
- Parent-child pairing must be verified by backend-issued sessions and separate child device / guardian consent. Local `FamilyPairingPolicy` is only a pure helper and is not an authorization system.
- Do not request telephony permissions on Wi-Fi-only tablets; do not imply emergency mobile calls work on hardware without a cellular radio.
- Never disable strict protection to make tests pass. Keep existing lock, emergency, cooldown and payment policies intact.

## Tests
- `./gradlew :core:test` — pure rules, including `FamilyPairingPolicyTest`.
- `./gradlew :app:testDebugUnitTest` — Android host-side tests.
- `./gradlew :app:assembleDebug` — test build only, not a production artifact.
- Verify GitHub Actions results; distinguish emulator infrastructure failures from unit/regression failures.

## Cross-project review first
Compare parent/child approval semantics with FastAPI invitation lifecycle. Explicitly document sequence, account identity, replay denial, expiry, revocation, and offline/losing-phone recovery. Propose changes in a draft PR only after tests, and report exact checks run.
