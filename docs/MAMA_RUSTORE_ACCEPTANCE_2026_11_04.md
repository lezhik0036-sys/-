# MAMA / BRICK — product acceptance, device safety and RuStore launch gates

**Prepared:** 2026-11-04. **Status:** NO-GO for public release. Product/QA ownership document; not a claim that a real server, phone, SMS provider or store release was tested.

## Product boundaries (unchanged)

- MAMA (МАМА) is the brand. **BRICK Mode** is the strict scheduled lock mode, not a standalone product.
- Users choose a **3/5/7**-period series, trusted contact(s) for early exit and emergency support, and **any local daily start/end time**. The product is positioned around healthy sleep, but must not hard-code "night".
- No user-defined app allowlist. Preserve the app's existing emergency access, series success/failure and paid Restart rules.
- Parent master account should govern the child's separately installed app only after transparent consent/authority checks. A parent cannot silently bypass a BRICK commitment.
- Child's tablet may be **Wi-Fi-only without SIM**. An SMS verification code is delivered to a verified remote contact's phone, never dependent on local tablet telephony.

## RuStore hard gates / release decision

| Gate | Required evidence | Status 2026-11-04 |
| --- | --- | --- |
| Single buildable integration | Android ↔ authenticated FastAPI ↔ Ktor OTP ↔ authoritative state, E2E evidence | **BLOCKED** — stacked draft PRs not deployed as one product |
| Parent/child identity | Two independent sessions, child-device key proof, valid parent authority/consent, durable revocation | **BLOCKED** — internal foundations only |
| No-SIM experience | Physical tablet can pair over Wi-Fi and displays honest emergency guidance; no SMS/call permissions demanded when unavailable | **BLOCKED** — partial Android change in draft |
| SMS/Trusted Exit | Real delivery only to verified contact in approved staging; quotas, replay protection, release fail-closed, lost-ACK recovery | **BLOCKED** — internal backend work / stubs |
| BRICK safety | Cannot start binding strict mode without essential emergency and recovery guarantees; OS manufacturer behavior tested | **BLOCKED** — physical tests outstanding |
| User privacy | Clear consent notice, Russian privacy policy (in store card + app), account delete inside app, data-retention and incident practices, data residency/legal review | **BLOCKED** — needs implemented flows and legal review |
| Android package | Proper release signature, no debug cert/test OTP/real keys, permission declarations, APK manifest, version code/checksum | **BLOCKED** — no final signed release verified |
| Monetization | Actual purchase/restart flows or truthful removal from release UI, offline/reboot consistency, provider contract | **BLOCKED** — end-to-end not verified |
| Store listing | Only features actually present described, proper screenshots/age rating/support/test-account access | **BLOCKED** — release feature set not frozen |

**Decision rule:** any blocked item means NO-GO; a green CI badge does not replace two-device physical testing or legal/product approval. Official moderation reference: https://www.rustore.ru/help/developers/publishing-and-verifying-apps/requirement-apps

## Acceptance matrix — 26 independently observable cases

Use P = separate parent Android phone, C = supervised Android phone or Wi-Fi tablet, T = verified trusted contact phone. Record date, build SHA, OS/model, network, screenshot/video, backend request ID (redacted), outcome and defects.

| ID | Setup / action | Expected outcome | Severity |
| --- | --- | --- | --- |
| A01 | Fresh C Android phone with SIM, choose 3 periods and 20:00–06:00 | Series scheduled in local time; no lock until valid commitment point | P0 |
| A02 | Fresh C Wi-Fi tablet **without telephony** | Setup proceeds without SMS/SEND_SMS/call-role demand; truthful emergency availability | P0 |
| A03 | On C start 11:00–12:00 BRICK in different timezone | Correct local interval; not forced into night times | P0 |
| A04 | DST forward/back across scheduled period | No skipped/duplicated successful periods or unexpected early release | P0 |
| A05 | Restart C while ACTIVE, remain offline | Committed state persisted, OS-provided emergency access still available | P0 |
| A06 | C loses Wi-Fi while ACTIVE, asks to leave early | Network error is not VALID and does not fabricate wrong attempt | P0 |
| A07 | Contact T receives correct 5-digit SMS incl leading zero | Code delivered to T, never requires local SIM on C | P0 |
| A08 | T declines/doesn't respond to request | No auto-unlock; user sees non-misleading remaining options | P0 |
| A09 | Wrong code 3 times | Attempts/cooldown respected across app reboot and parallel requests | P0 |
| A10 | Server accepts OTP but response to C times out | Read-only authoritative recovery resolves state without second exit/charge | P0 |
| A11 | Reuse same OTP after successful verified exit | One-time receipt cannot unlock second period | P0 |
| A12 | SMS.RU provider 5xx / quota exhausted | No pending fake challenge represented as delivered; useful copy on C | P0 |
| A13 | Code requested for different child/contact/period | Server refuses foreign and stale scopes | P0 |
| A14 | Parent P authenticates separately, child C issues pairing code | One-time 5-min invite shown only after valid child device proof | P0 |
| A15 | P scans valid invite with verified guardian authority | Exactly one parent-to-device link; visible on both sides | P0 |
| A16 | P scans expired or replayed code | Rejected without child metadata leakage; child may request fresh invitation | P0 |
| A17 | Foreign parent scans child's invite / tries direct device ID | Cannot see or control C | P0 |
| A18 | P logs out / child device revoked | Session ends; unauthorized parent device reads denied | P0 |
| A19 | C lost or no longer has key, child recovers account | Child can revoke P link without access to lost hardware | P0 |
| A20 | P revokes C link | Parent device dashboard updates; no covert ongoing access | P0 |
| A21 | C has no SIM and no dialer; emergency scenario | Explicit warning to use another phone; no false promise of dialing 112 | P0 |
| A22 | C phone has telephony, emergency during BRICK | OS-approved emergency dial and configured trusted contacts remain reachable | P0 |
| A23 | P requests to change active child's BRICK schedule | No stealth parent unlock or unilateral bypass; explicit policy and audit | P0 |
| A24 | Completed 3/5/7 series transitions to result | One recorded completion, no screen jump/ticker rearming on Samsung | P1 |
| A25 | Parent/child account deletion from in-app settings | Access revocation and appropriate data deletion/retention correctly explained | P0 |
| A26 | Release APK inspection and store form | Production signature, clean manifest, test/fake OTP off, correct permissions/data disclosures | P0 |

No test passes solely by reading source code: a physical observation, unit/integration test with exact CI link, or both must be attached as applicable.

## Product copy — short, user-facing; to be localized in-app

**Before BRICK:** “Вы выбрали ограничение телефона с {start} до {end} по местному времени в течение {periods} периодов. Во время ограничения досрочный выход возможен только по проверенному коду доверенного контакта. Перед началом убедитесь, что у вас есть безопасный способ связи при чрезвычайной ситуации.”

**Wi-Fi tablet:** “На этом устройстве нет функции обычных мобильных звонков. При экстренной ситуации воспользуйтесь другим телефоном или доступным способом связи. Интернет нужен для запроса кода доверенному контакту.”

**No internet:** “Нет связи с сервером. Отправить или проверить код сейчас нельзя. Повторите попытку после восстановления интернета. Ошибка связи не считается неверным кодом.”

**SMS trouble:** “Код пока не отправлен. Попробуйте позже или проверьте подключение к сети. Досрочное завершение не подтверждено.”

**Parent link:** “Родитель сможет видеть привязанные устройства и управлять только теми функциями, на которые дано подтверждённое разрешение. Код привязки действует 5 минут и не даёт права разблокировать BRICK.”

**Child unpair:** “Отключить семейную привязку? Родитель потеряет доступ к данным и разрешённым действиям для этого устройства. Уже действующий период BRICK не будет досрочно отменён.”

**Account deletion:** “Удаление аккаунта завершает семейные привязки. Некоторые сведения могут сохраняться ограниченное время, если это необходимо по закону или для безопасности; подробности указаны в политике конфиденциальности.” (Needs legal/product validation.)

## Privacy/disclosure inventory to finalize before public release

Personal data by category and source: account identifier, device public key/ID, contact phone numbers, encrypted/hashed challenge/OTP references, parent-child link, verified consent evidence, local schedule and active-period state, purchase records, diagnostic errors. Each must have a clear purpose, storage location, retention duration, access controls, deletion path and cross-service transfer. Do not collect hidden location, device messages or contact list beyond user-selected trusted contacts.

RuStore reference: https://www.rustore.ru/help/developers/publishing-and-verifying-apps/requirement-apps — as published in November 2026, notably Russian policy available in-app and on store page; explain sensitive permissions; in-app account deletion for account-based apps. Full review required, including any child-focused obligations.

## Evidence bundle and sequencing

1. **Staging contract:** authenticate separately; check FastAPI/Ktor wire formats, grants, real contact resolution, one-time receipt, recovery. Only fake SMS in CI.
2. **Android preflight:** verify not just green tests but reproducible Robolectric and Android 14 emulator, no telephony permission dependency, accessible emergency UX.
3. **Two physical devices:** C Wi-Fi tablet + P Android + T trusted contact. Repeat with Samsung phone as C. Capture video of results and network-loss/reboot handling.
4. **Production readiness:** independent review of legal privacy, payment, signing, keys, logs, monitoring, rollback; canary test with approved staging SMS, then release decision.
5. **RuStore submission:** truthful card screenshots/description, test credentials or demo for moderation when appropriate, accurate permissions, signed artifact and support contact.

## Clear owner split

**Claude Code (when externally started through existing GitHub workflow):** implement Android/FastAPI/Ktor and the runnable test/staging chain, fix flaky CI, publish draft PR and exact evidence. GitHub issues: Android #39, FastAPI #38, Android QA CI #40.

**ChatGPT/product QA:** maintain this acceptance document, assess actual test evidence, flag inconsistent policy/UX and go/no-go readiness, prepare store content only for features that pass.

**Human owner:** approve credentials/guardian-provider and data processing decisions, authorize carefully staged paid SMS, review real device UX, authorize any merge/deploy/publish and signing key custody.


---

## 2026-11-05 addendum: UNIVERSAL PERSONAL MAMA is the primary release journey

**Owner clarification:** MAMA is for individuals of any age-appropriate user group — founders, professionals, teachers, students and people seeking better sleep or self-discipline. Family is explicitly **optional** and not a sign-in or setup prerequisite. See [issue #48](https://github.com/lezhik0036-sys/-/issues/48) and [copy/tests PR #49](https://github.com/lezhik0036-sys/-/pull/49).

### Personal-mode pilot acceptance (must pass BEFORE claiming any public release)

| ID | Real observed journey | Outcome required | Current status |
| --- | --- | --- | --- |
| P01 | Install on personal Android phone, never open Family | Complete onboarding, choose series/contact/time, start only with safe recovery; no parent/child role required | UI tests pass; real recovery BLOCKED |
| P02 | Personal daytime work session 09:00–11:00 | BRICK works as chosen in local timezone; no night-only or sleep-only limit | Domain supported; physical test NOT RUN |
| P03 | Teacher chooses 14:00–15:00 period | Same full scheduling, exit/emergency UX as nighttime | Physical test NOT RUN |
| P04 | User configures evening and next-day overnight window | Correct interval and no duplicated completed period around DST | Server/physical E2E NOT RUN |
| P05 | Self-managed Wi-Fi-only tablet with no SIM | No local cellular permissions; verified external trusted contact can receive server SMS; clear emergency warning | Partial UI in PR #26; live SMS BLOCKED |
| P06 | Personal Trusted Exit after valid code delivered to external contact | Backend-only code generation and one-time authoritative state transition; no device-side fake OTP in release | BLOCKED: release adapter disabled |
| P07 | No network, expired response, 3 wrong codes, retry | No accidental unlock or false failure; cooldown persists and lost-ACK recovers from server | Contract tests only; actual Android/HTTP E2E NOT RUN |
| P08 | User completes 3, then 5, then 7 period series (separate runs) | Correct success/history, Restarts, scheduled intervals and payments | Physical test NOT RUN |
| P09 | Independent user uses FLEX, statistics, motivation and contact changes | Features preserved, no regression caused by Family code | Not fully tested in functional pilot |
| P10 | One adult uses own BRICK and separately opts into Family for a different device | Separate data, no role collision, no bypass of either person's active series | BLOCKED: Family UI/API connection absent |
| P11 | Unlink Family child or revoke parent, then return to independent MAMA | Personal series, history and own credentials remain intact | API partial PR #40, no Android E2E |
| P12 | Adult deletes account, revokes device/link and erases tokens appropriately | Data/retention legally reviewed, safe audit and privacy notice | BLOCKED |
| P13 | User sees installation/setup prompts without telephone hardware | No fake emergency dial capability; show safe alternative communication instructions | Staging test and physical review NOT RUN |

### Current verified source/CI inventory (do not confuse green CI with production behavior)

- Android [PR #49](https://github.com/lezhik0036-sys/-/pull/49): universal copy and personal onboarding Robolectric tests, CI success on runs 41998525302 and 41998554279.
- Claude test [PR #46](https://github.com/lezhik0036-sys/-/pull/46): APK v0.8.20-test21 is debug-only; locally simulated OTP. Do not claim working SMS.
- FastAPI [PR #40](https://github.com/lezhik0036-sys/mama-fastapi/pull/40): actual ASGI authenticated family device list + revocation, CI success on 41999399629; no family create/approve public routes yet.
- FastAPI [issue #41](https://github.com/lezhik0036-sys/mama-fastapi/issues/41): 30-day bearer is NOT fresh account-recovery proof for sensitive child unpair. Add server-confirmed step-up identity before shipping that route.
- Functional release blocker tasks: [Android #47](https://github.com/lezhik0036-sys/-/issues/47), [FastAPI #39](https://github.com/lezhik0036-sys/mama-fastapi/issues/39).

**Acceptance rule:** no family role is needed for solo use; every solo user's Trusted Exit also has to use the actual authenticated SMS/contact server. A green CI for mock provider / in-process contract does not establish paid live SMS, remote Android pairing or Selectel deployment. Preserve working personal MAMA functionality across any future parent/child additions.
