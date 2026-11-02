# MAMA Android — Current Status

> Backup-context. Состояние на ветке `claude/mama-ui-v1`.
> Дата составления — ноябрь 2026. **⚠ Требует проверки** — там, где факт не
> подтверждён кодом/CI на момент чтения.

## Версия и ветка

- `versionName = "0.8.20-test20"`, `versionCode = 51` (`app/build.gradle.kts`).
- Рабочая ветка версии: **`claude/mama-ui-v1`** (единственная рабочая ветка
  текущей версии MAMA; `claude/mama-phone-lock-app-vzccj7` — не использовать).
- Репозиторий: `https://github.com/lezhik0036-sys/-` (имя репозитория — `-`).

## Готово

- **Ядро (`:core`)**: расписание, часовые пояса, окна через полночь, `ClockGuard`,
  `Recovery`, серии 3/5/7, `SeriesFlow`, `FlexEngine`, кошелёк/«Цена слова»,
  30-дневный cooldown, коды. Покрыто тестами (десятки тестов в `core/src/test`).
- **Android-слой (`:app`)**: оверлей, `LockService`, `GuardService`, Device Admin,
  будильники, приёмники boot/time, контроль исходящих звонков, звонок контакту,
  экстренные вызовы, экран блокировки, экраны настройки/серий/статистики,
  дизайн-система, канонический UI V1. Robolectric-тесты (`app/src/test`).
- **Trusted Exit (контракт на устройстве)**: server-authoritative; телефон хранит
  только handle; `endByTrustedExit` завершает серию бесплатно; offline — честная
  ошибка без списания попытки.
- **Backend (`:backend`, Ktor)**: движок challenge (5 цифр, 3 попытки, expiry 15м,
  cooldown 30м), API `/trusted-exit/request|verify`, `/health`, `/ready`, реальный
  `SmsRuProvider` (SMS.RU) + `MockSmsProvider`, жёсткий TEST/PROD split (fail-closed
  без ключа), smoke-отправка одной SMS, Docker/Compose/Caddy, `backend/DEPLOY.md`.
  Тесты зелёные на CI.
- **CI** (`.github/workflows/build.yml`): job `build` (core-тесты → backend-тесты →
  app Robolectric → debug APK → проверка release без тест-кредитов → публикация
  `test-v<version>` prerelease) и job `launch` (эмулятор Android 14 Pixel 6:
  `connectedDebugAndroidTest` + скриншоты → prerelease `ui-screenshots`). На момент
  последних коммитов — зелёный.

## Не завершено / в работе

- **⚠ Android HTTP-клиент к реальному backend НЕ реализован.** `MamaApp.kt` по
  умолчанию использует `FakeTrustedExitBackend` (симуляция «сервера» на устройстве,
  помечена `NOT PRODUCTION SECURE`). Класса `HttpTrustedExitBackend` в
  `app/.../trustedexit/` нет. Это Фаза 3 (см. `ROADMAP.md`).
- **⚠ Backend ещё не задеплоен** на реальный VPS, реальная SMS.RU-цепочка из
  приложения не проверена end-to-end на телефоне. Готовы артефакты деплоя и
  инструкция (`backend/DEPLOY.md`), но сам деплой выполняет пользователь.
- **`TRUSTED_EXIT_BASE_URL`** (адрес backend для прод-сборки) ещё не задан в
  BuildConfig — появится в Фазе 3.
- **Платежи выключены**: `FeatureFlags.PAYMENTS_ENABLED=false`, `Billing.payments=NoPayments`.
  В пилоте доступны тест-гранты (`TEST_GRANTS_ENABLED`) и оплата из внутреннего
  баланса (`WALLET_SPEND_ENABLED`); реального стора (Google Play/RuStore Billing) нет.

## Известные ограничения

- **Samsung/One UI**: кнопка спец. возможностей в навигации может выключить
  стража; камера и быстрые настройки на системном экране блокировки. Обходится
  **настройками телефона** (список — `docs/STATUS.md` и `docs/FIELD_TEST_01_SAMSUNG.md`),
  в приложении не блокируется.
- **Тестовая/debug-сборка**: каждый лок капится 15 минутами и на экране выхода
  показывается TEST CODE (гейт `FeatureFlags.TEST_MODE = BuildConfig.TEST_MODE`,
  true только в debug). В release — ни капа, ни кода.
- Текстовую модель угроз и остальные ограничения см. `docs/CORE.md`.

## Ошибки / баг-трекинг

- Отдельного файла баг-листа под текущую версию в репозитории нет. `docs/STATUS.md`
  **устарел** (v0.1.7, 23.09.2026) и описывает ранние открытые вопросы. **⚠**
  Актуальные дефекты смотреть по последним запускам CI (`build`/`launch`) и по
  полевому тесту; формального актуального отчёта в репозитории нет.

## Быстрые факты для проверки состояния

```bash
grep -E 'versionName|versionCode' app/build.gradle.kts
git branch --show-current          # ожидается claude/mama-ui-v1
./gradlew :core:test :backend:test # локально (нужен интернет для зависимостей)
```
