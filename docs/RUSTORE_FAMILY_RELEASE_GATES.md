# MAMA / BRICK Mode — RuStore release-candidate gates

**State on 2026-11-04: not yet approved for RuStore upload, paid SMS or production service rollout.** This checklist keeps the currently working local Android debug build separate from genuine server-verified production Trusted Exit.

## Product modes, one brand

- **Personal MAMA:** the owner chooses a series, emergency/trusted contact and recurring local active interval (including daytime intervals and time zones). No user-defined allowed-app whitelist.
- **MAMA Family:** adult has an authenticated guardian **master account**, child has a separately linked device. Child can be on a Wi-Fi-only Android tablet/phone without a SIM; the SMS goes to the adult's separate phone. Pairing is a voluntary, time-limited, single-use child-device challenge approved from an authenticated adult session with guardian consent. No universal master password or covert admin access.
- **BRICK Mode:** strict scheduled restriction during the active interval, with safe OS emergency access and independently authenticated server-side early exit. Parents cannot silently impersonate child device proof.
- **SIM-less:** phone/SMS permissions and call-redirection role may only be mandatory if *telephony hardware* actually exists. A phone with a modem but no SIM must preserve supported call controls. Wi-Fi is needed for OTP request/verification; lack of Wi-Fi must not falsely signal success or trap the child beyond the documented scheduled period. Wi-Fi-only tablets do not magically have cellular emergency calls; present a safety notice.

## Blocking server E2E integration

1. Replace Android debug-only `FakeTrustedExitBackend`/test-code provider with a network client in a **separate staging build variant** (release uses live backend and never accepts local test code).
2. Android session identity must be independently validated by FastAPI, per-operation Ed25519 key-possession proof must be fresh and bound to account/series/device/period and Ktor challenge; distinguish cryptographic key ownership from live BRICK enforcement/device integrity.
3. FastAPI issues narrowly scoped signed SMS/verify grants **only after** authenticated account/device/period/verified contact, abuse limits and server-side Ktor challenge ownership check. Private signing keys never ship to Android or GitHub.
4. Ktor challenge/OTP life cycle: server-selected verified destination, fake provider first, real SMS provider only in approved cost-capped staging test, three failed attempts, expiry, throttle, one-use redemption and replay denial.
5. Ktor sends Ed25519 receipt over a server-authenticated, pinned private mTLS channel with separate server-only MAC; FastAPI stores JTI/challenge uniquely and atomically commits one `EARLY_EXIT` + cooldown. Lost response must reconcile using a separately authenticated read-only account/transport status, **not** retry acceptance or client JWT.
6. Android unlocks **only** after authoritative FastAPI completed state. A `VALID` response from Ktor alone must never end BRICK. With network loss, show unresolved status; protect emergency communication and natural scheduled end.
7. Parent links: one-time pairing token stored hashed, guardian consent, parent and child session auth, device-scoped revocation, audit, safe recovery if parent device/account is unavailable. Remote change/stop needs a separate authorized transition and user-visible audit.
8. Integrate provider receipts and support telephone country codes, local time/DST, delivery failures, recovery of phone without SIM, poor Wi-Fi, backend restart and concurrent requests. No collection of unnecessary child location, contacts or call recordings.

## Android physical-device acceptance

- [ ] Three controlled configurations: Samsung with SIM, Samsung with SIM removed (Wi-Fi), Wi-Fi-only tablet with no telephony hardware.
- [ ] Initial onboarding uses only permissions available to each hardware configuration; never requests `SEND_SMS`.
- [ ] Permission denial/withdrawal, device admin removal and safe uninstall/recovery tested explicitly on a spare device.
- [ ] Parent/child pairing, rejection of invalid code, wrong parent, revoked child device, reused/expired code.
- [ ] Fake-SMS staging: request on Wi-Fi-only child device, OTP delivered to synthetic trusted-contact provider, validate and authoritative completed state; no SMS.RU traffic.
- [ ] Emergency dialer on supported phones unaffected, and no false emergency-call promise on tablets without telephony.
- [ ] Natural window ends according to user-chosen local schedule across UTC offsets/DST.
- [ ] Airplane mode, Wi-Fi loss, delayed callback, backend restart, SMS provider error: no counterfeit unlock.
- [ ] Screen transition jitter and transient Samsung launcher flashes retested against separate recorded state/overlay timeline.
- [ ] Device-to-server sensitive data and app telemetry minimization; no OTP, signing secret, parent/child PII in logcat.
- [ ] Child safety: restricted functionality can't prevent access to supported OS emergency services; no indefinite lock if account/parent is unavailable.

## RuStore distribution and moderation

Sources: [APK/AAB publication](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication), [moderation requirements](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/requirement-apps), [APK signature](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/apk-signature).

- [ ] Stable production `applicationId`, increasing `versionCode`, unique package, minSdk >= 26, correct supporting screenshots.
- [ ] Owner creates and securely stores **production keystore offline**; do not generate or upload release keys to public repo; debug CI signing cannot update a RuStore release.
- [ ] Reproducible **release** APK/AAB: `TEST_MODE=false`, no pilot credits, no fake OTP, no development server URLs, no private keys, no publicly callable test routes; official apksigner verification.
- [ ] Russian privacy policy, processing/retention and deletion, child/guardian consent and separate sensitive permission explanations reachable from app and store card.
- [ ] Authenticated self-service account deletion inside app and appropriate data deletion/retention policy; remote pairing revocation and user support contact.
- [ ] Sensitive Accessibility/overlay/device-admin/call permissions documented and justified to moderation; clear user opt-in, no concealed capture/control.
- [ ] Correct age rating and child-focused safety/content handling, app screenshots and test/demo account for moderation as required.
- [ ] Legal/regulatory review for Russian personal-data processing/localization and any hosted SMS/identity services.
- [ ] No externally submitted draft to RuStore or paid provider testing without explicit release owner sign-off.

## Related engineering

Android: draft PRs #13/#17–#20 for signed Ktor evidence, APK signing, SEND_SMS removal, phone setup and ticker fix. Wi-Fi-only hardware onboarding: draft PR #26. Issues #22–#25 track backend E2E, parent account, SIM-less and RuStore.

FastAPI: Trusted Exit security stack draft PRs #1–#29. Internal-only family pairing foundation draft PR #30.

**Immediate next decision:** sign off a *staging-only* integration plan with isolated hostnames, synthetic accounts, fake SMS provider and no production services; then separately authorize a bounded live-SMS trial. Do not label a debug local-OTP APK as a RuStore release candidate.
