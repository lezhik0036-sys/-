# MAMA Android — AI Handoff

> Инструкция для другого AI-разработчика (или человека), чтобы продолжить работу
> с того же места. Backup-context. **⚠** — проверить перед действием.

## 0. Первым делом

- Репозиторий: `https://github.com/lezhik0036-sys/-` (имя — `-`).
- Рабочая ветка: **`claude/mama-ui-v1`** — единственная рабочая ветка текущей
  версии. **Не** переносить работу в `claude/mama-phone-lock-app-vzccj7` и **не**
  создавать новые ветки для той же версии без просьбы.
- Прочитать, в этом порядке: `README.md`, `docs/CORE.md`, `docs/MONETIZATION.md`,
  затем `docs/backup-context/ARCHITECTURE.md` и `CURRENT_STATUS.md`.
- Язык общения с владельцем — русский, на «ты» (предпочтение из сессии).

## 1. Инварианты бизнес-логики — НЕ МЕНЯТЬ без явного запроса

Trusted Exit (бесплатный, server-authoritative; телефон не видит правильный код;
3 попытки; код генерит/хранит/проверяет backend) · Series 3/5/7 · Restart
(99/179/299 ₽) · FLEX (349 ₽) · «Цена слова» (внутренний баланс, 100–100 000 ₽) ·
30-дневный cooldown · Guardian/строгая блокировка · статус-машина серии · цены
(источник правды — `core/`) · тестовый safety-net (15-мин кап и TEST CODE только
в debug). UI — **Views, не Compose**. Разблокировка телефона и экстренные вызовы
никогда не зависят от оплаты.

## 2. Где что (карта входных точек)

- **Фасад Android→ядро:** `app/.../platform/Mama.kt` (startSeries, restartSeries,
  confirmProtection, requestCode, submitCode, FLEX, test lock).
- **Ядро:** `core/.../LockEngine.kt`, `Model.kt`, `SeriesFlow.kt`, `Programs.kt`
  (FlexEngine, StandardRules), `Wallet.kt`, `Recovery.kt`, `ClockGuard.kt`,
  `CorePolicy.kt`.
- **Блокировка:** `app/.../lock/LockService.kt`, `LockOverlay.kt`, `LockScreenView.kt`,
  `GuardService.kt`.
- **Trusted Exit (устройство):** `app/.../trustedexit/TrustedExitBackend.kt`
  (интерфейс + `FakeTrustedExitBackend`), `TrustedExitStore.kt`, `TrustedExitDelivery.kt`.
- **Backend:** `backend/.../Server.kt`, `api/Routes.kt`, `trustedexit/Challenge.kt`,
  `SmsRuProvider.kt`, `Config.kt`; деплой — `backend/DEPLOY.md`.
- **UI:** `app/.../ui/` (+ `ui/design/` дизайн-система).

## 3. Сборка и тесты

```bash
# Ядро и backend — на голом JDK 17 (нужен интернет для зависимостей):
./gradlew :core:test
./gradlew :backend:test
# Android — нужен Android SDK (ANDROID_HOME или local.properties):
./gradlew :app:testDebugUnitTest      # Robolectric
./gradlew :app:assembleDebug
```
Без Android SDK `settings.gradle.kts` автоматически пропускает `:app` (чтобы
`:core`/`:backend` собирались где угодно).

## 4. CI (`.github/workflows/build.yml`)

- job **build**: `:core:test` → `:backend:test` → `:app:testDebugUnitTest` →
  debug APK → проверка «release без тест-кредитов» → публикация prerelease
  `test-v<version>` (APK + .sha256) на push в `claude/*`.
- job **launch**: эмулятор Android 14 (Pixel 6), `connectedDebugAndroidTest` +
  скриншоты → prerelease `ui-screenshots`.
- Падение unit-тестов блокирует публикацию APK (release gate в одном job).

## 5. Конвенции коммитов

- Небольшие изолированные изменения, зелёный CI перед push.
- Коммиты в проекте оканчиваются трейлером `Co-Authored-By:` (и ссылкой на
  сессию). **⚠** Ссылка на сессию специфична для конкретной сессии Claude —
  новый автор ставит свой трейлер/атрибуцию, а не копирует чужую ссылку.
- Не создавать PR без явной просьбы владельца.

## 6. Следующий шаг работы

Фаза 3 (см. `ROADMAP.md`): реализовать `HttpTrustedExitBackend` на устройстве,
production-readiness через `GET /health`, жёсткий TEST/PROD split в `MamaApp.kt`,
`BuildConfig.TRUSTED_EXIT_BASE_URL`, затем безопасный APK для реального
A/B-теста. Предусловие — задеплоенный backend и успешная реальная SMS
(`backend/DEPLOY.md`).

## 7. Секреты

- Ключ SMS.RU — **только** в server-side env `MAMA_SMSRU_API_ID`. Никогда в Git,
  APK, Gradle, BuildConfig, Android resources, логах. В репозитории секретов нет
  (проверено; в CI используется встроенный `${{ github.token }}`).
- `TRUSTED_EXIT_BASE_URL` — это конфиг (адрес), не секрет; его можно хранить в
  BuildConfig/коде.

## 8. Как быстро понять текущее состояние

`git log --oneline -20`, `git branch --show-current`, `grep versionName app/build.gradle.kts`,
прочитать `docs/backup-context/CURRENT_STATUS.md`. Запустить `:core:test` и
`:backend:test`. **⚠** Если `MamaApp.kt` всё ещё ставит `FakeTrustedExitBackend` —
значит Фаза 3 ещё не сделана.
