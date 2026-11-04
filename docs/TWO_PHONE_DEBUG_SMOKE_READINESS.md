# MAMA debug-only physical-phone smoke test — controlled, no real SMS

**Status:** candidate artifact cryptographically audited; NOT approved for primary-phone installation, live BRICK enforcement, SMS.RU or Selectel deployment. This is a field-test checklist for a spare test handset, not a production release guide.

## Audited CI artifact

- Source: GitHub Actions workflow run **41788069540**, branch `trusted-exit-ktor-evidence-transport`, commit `1f15e728b80e15ed4eb10418ea5750ace0fd463a`. Both `build` and `launch` succeeded.
- Archived artifact ID `13302341253`: `MAMA-v0.8.20-test20`, contains `MAMA-v0.8.20-test20.apk` + `.sha256`, expires **2027-02-02**.
- APK SHA-256 (verified against sibling checksum): `4cd049332980acd73fca24e565a5b1f1a000e2b7fd64d9743bab61a5774be0a8`.
- ZIP SHA-256 recorded by GitHub: `da40f3ed0ad6f0e8ae0d9bccf71765f22594b528207afb870681436241857075`.
- Independently parsed APK Signing Block: **APK Signature Scheme v2**, signer cert subject `C=US,O=Android,CN=Android Debug`, cert SHA-256 `ebf52cabf542f65fe0ff4a49d299244fabae53a33fba565a7fdb78571fc58c5e`. RSA PKCS#1 SHA-256 signature over signed data **verified**, and APK content digest (scheme v2 1MB chunk algorithm) **verified**. Confirm again on CI using Android's `apksigner verify --verbose --print-certs` before field install; this independent cryptographic analysis is not a substitute for official Android signature tooling.
- `app/build.gradle.kts`: Android `minSdk=26` (Android 8.0), `targetSdk=35`, `versionName=0.8.20-test20`. Debug `TEST_MODE=true`; release `TEST_MODE=false`. Source comments describe a **15-minute cap on debug lock** and test-code display, but device behavior must be proven on the test phone.
- `MamaApp.kt` instantiates `FakeTrustedExitBackend` and `DebugCodeSmsService` in test mode. This is **local fake OTP**; it does not exercise authenticated Ktor→FastAPI receipt transport, actual SMS.RU delivery, or real server-authorized exit.

## Safety review before ANY phone installation

1. Use a spare handset with no irreplaceable data, no primary authenticator, and a tested full device reset path. Do not try the app on a primary daily-driver phone. Take backups and record exact model, Android version, security patch and OEM skin privately.
2. Record current version/hash of the APK; refuse to install if checksum or v2 signature differs. Verify `apksigner verify --verbose --print-certs MAMA-v0.8.20-test20.apk` and confirm the certificate is **Android Debug**, not any production signing credential.
3. Carefully review requested permissions on the target phone. Manifest includes **SYSTEM_ALERT_WINDOW, Accessibility service, Device Administrator, Call Redirection, foreground service, exact alarms, SEND_SMS, CALL_PHONE, ANSWER_PHONE_CALLS, READ_PHONE_STATE and boot receivers**. These are intrusive; do not grant them all until the code paths and recovery have been explicitly tested.
4. Prior to locking anything: confirm permissions can be revoked; document a physical safe reset/uninstall procedure outside BRICK Mode. Emergency service calls must remain available. Android Device Administrator may prevent easy uninstall until deactivated. **If safe recovery cannot be demonstrated, stop test.**
5. Use an isolated debug account and synthetic contact details only; do not use real trusted contacts or phone numbers in any submitted screenshots/logs. No network traffic to SMS.RU or production Selectel is authorized.
6. Run with a supervised, short, **nonblocking** initial smoke test. Do not trigger actual lock or grant Accessibility/Device Admin on the first pass. Observe onboarding, series selection, local time interval, contact selection and navigation; no user-selectable app whitelist should appear.
7. After a separate safety go/no-go: use test-mode lock under supervision, keep an alternate device available, exercise on-device test OTP only, attempt escape/call flows in a controlled setting, confirm restart/boot and timeout recovery. Do not manufacture/trigger public emergency calls for testing.
8. Capture only sanitized pass/fail results; never publish phone numbers, OTPs, device identifiers, app signing private keys, MACs, JWTs or personal account data.

## Test matrix before staging server integration

| Test | Expected result |
|---|---|
| Open/debug launch, screen rotation and app restart | No crash; persistent setup state |
| Series kind, any manual local start/end, timezone | Saved plan matches user's choice, including daytime intervals |
| No permissions, then individually grant/revoke | No crash or unsafe silent bypass |
| Contact selection | No SMS, no number-dependent authorization |
| Fake test OTP wrong three times | Failed verification and durable attempt budget; no unlock on a wrong code |
| Correct fake OTP; then retry | At most one accepted local test outcome; never proves FastAPI completion |
| Airplane mode and lost response (staging only) | UI shows unresolved until authoritative server confirmation; never unlocks just on Ktor `VALID` |
| Boot, app forced-stop, timezone/manual clock change | Document recovery; Android restrictions can vary by OEM |
| End of debug interval and emergency dialer | Restore access safely; emergency services never obstructed |
| Logs and recordings | No phone, OTP, JWT, key or HMAC disclosures |

## Production readiness remains blocked

This debug APK is **not integrated** with live, independently authenticated FastAPI/Ktor channels, server grant issuers, actual provider-verified contacts, hardware-backed device request proof/live BRICK state or real SMS. All new FastAPI PRs are unmerged drafts. A real SMS trial requires a separate security/recovery review, an isolated staging environment, user authorization, and a clearly bounded paid-SMS test plan. No Selectel deployment, production APK release or real SMS is authorized by this document.
