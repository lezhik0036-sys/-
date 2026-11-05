# MAMA — private pilot go/no-go: real Trusted Exit, cellular-only fallback, Pay SDK (2026-11-05)

**Status:** PRE-RELEASE BLOCKED; these are tests/acceptance requirements, not implemented or executed functionality. Primary product = independent personal MAMA for everyone; Family is optional.

## Capability and connectivity truth table

| Child/solo MAMA device | Contact's SMS-capable phone | Can the MAMA app obtain an authoritative early-exit result? |
| --- | --- | --- |
| Wi-Fi, no SIM | contact has SMS | YES only after authenticated HTTPS request + typed code verified and one-time server receipt consumed |
| SIM + mobile internet | contact has SMS | YES under same normal HTTPS protocol |
| SIM, cellular network, mobile data/Wi-Fi absent | contact has SMS | **NOT CURRENTLY**: the MAMA app cannot submit code over HTTPS. A separately secured 2-way SMS gateway or IVR must be implemented, carrier tested, and bound to a server-owned series/period/device; no spoofable caller-number-based unlock |
| SIM, cellular network, mobile data/Wi-Fi absent | no separate contact available | **NOT CURRENTLY**: receiving an SMS locally does not authenticate a fresh Trusted Exit on the server |
| No SIM, no IP connection | any contact | **NO** remote interaction possible; never promise code send/verify; keep available OS emergency and truthful explanation |
| No SIM, Wi-Fi | contact has no phone/SMS | **NO** verified SMS route; support provider-approved alternative only when actually implemented and proven |

**Safety:** server must never return or log OTP; fail closed on lost network, but do not trap users in an untested long strict period. Provide emergency handling for phones and hardware-aware messaging for Wi-Fi tablets. A 5-digit OTP must not authenticate a bearer solely on SMS origin/number. Trusted Exit is always free. All testing should use spare devices until safety proves robust.

## Functional exit acceptance

- E01 — account/device session + signed nonce established; user chooses verified external trusted contact; local device SIM optional
- E02 — active arbitrary 09:00–11:00 and 23:00–07:00 BRICK periods registered server-side
- E03 — real approved staged SMS provider sends exactly one code with leading-zero support, retry and delivery status; no SMS abuse
- E04 — HTTPS input, 3 wrong attempts, cooldown, expiration, replay, wrong period/user/device and provider errors enforced across servers
- E05 — FastAPI consumes signed receipt and transitions the BRICK state exactly once, with lost-response recovery; Android acts only on authoritative server state
- E06 — offline cellular-only fallback (if claimed): sign/encrypt or cryptographically authenticate device/session proof, account for SMS/voice carrier limits and network routing; phone-origin spoof must fail; OS can receive signed result without IP; observe behavior on real Android with data disabled
- E07 — no-SIM + Wi-Fi independent personal flow and optional Family flow, with no direct SEND_SMS permission
- E08 — no connectivity: no false success, no local fake code, no activation of unreasonably unsafe long strict lock
- E09 — all emergency calls allowed by device hardware and OS; no pretend 112 capability on tablets
- E10 — capture backend redacted request IDs, real-device video, CI logs, APK version/signature and checksum

## Finance: MAMA balance and card-paid sandbox

**Source as of PR #46:** `app/.../billing/Payments.kt`: `PAYMENTS_ENABLED=false`, `Billing.payments=NoPayments`, `TEST_GRANTS_ENABLED=true`, `WALLET_TEST_TOPUP_ENABLED=BuildConfig.PILOT_TEST_CREDITS`; `core/.../Wallet.kt` local ledger. These are debug/pilot mechanisms, **not financially safe for live money**.

- F01 — integrate RuStore **Pay SDK** (not obsolete BillingClient). Provider collects PAN/CVV and returns server-verifiable transaction receipt; MAMA never collects raw card details.
- F02 — account-owned, immutable, server-authoritative integer-kopek ledger: `AVAILABLE`, `RESERVED`, `SPENT`, `REFUNDED`, `REVERSED`, `FAILED`; include purchase IDs, verified provider signatures, timestamps and idempotency.
- F03 — separate TEST and REAL currency realms; no test credits appear in a real-money balance; no test grants in real-paid builds.
- F04 — test-card payment `PENDING` → provider approval → server confirmation → **one** credit even if callback repeated; local fake success cannot credit.
- F05 — cancelled/declined/pending/failed/refunded/chargeback/duplicate callback/timeout/device reinstall/account swap validated against API; never negative balance or cross-user funds.
- F06 — test money spends on FLEX/Restart and user-selected `Цена слова` with exact reserve/release/forfeit/partial-return semantics only after product/legal review; no money withdrawal or transfers.
- F07 — visible balance refresh and transaction history match authoritative backend; offline status is stale/not spendable until verified or securely reserved.
- F08 — verify refund support, fiscal receipt/consumer obligations, retention, legal restrictions of stored prepaid value and forfeiture model before real money.
- F09 — RuStore sandbox only after first app moderation (publication not required); store testers explicitly opted in; test card data from RuStore only and visually verify `Тестовая покупка` badge. **No real bank card charges** for sandbox.
- F10 — release/pilot build cannot bypass payment confirmation with `NoPayments`, `GrantSource.TEST_NO_PAYMENT`, or local-only test credits.

## Official provider sources (checked 2026-11-05)

- Pay SDK: https://www.rustore.ru/help/sdk/pay
- Payment instructions: https://www.rustore.ru/help/en/guides/payments
- Test cards and moderation requirement: https://www.rustore.ru/help/developers/monetization/sandbox
- Enable test mode: https://www.rustore.ru/help/developers/monetization/sandbox/enable-test-mode
- Test Pay SDK flow and sandbox badge: https://www.rustore.ru/help/developers/monetization/sandbox/testing-sdk-pay

RuStore says test purchases require the first app version to pass moderation, but the app need not be publicly published. Pay SDK sandbox testing is supported **only within RuStore**, and if the test-purchase badge is absent, the purchase may be real. Pay SDK 9.0.0+ supports testing; device RuStore version must be 1.29+. BillingClient purchases stopped processing August 2026.

## Owners and evidence

- Claude Android: #50 exit fallback and #51 Pay SDK + wallet UI; stage branches and run full CI
- Claude FastAPI: #43 provider/SMS gateway and #44 financial authoritative ledger; real HTTP tests
- ChatGPT product QA: this gate and source/regulation review; protect universal personal flow and optional Family
- Human owner: approve *staging* SMS budget, provider test accounts and any real financial collection; authorize production Selectel deployment and store upload only after passing tests

**DO NOT label an APK final until E01–E10 and F01–F10 are either actually passed or explicitly not offered in its released feature set.**
