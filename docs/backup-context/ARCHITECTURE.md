# MAMA Android — Architecture

> Backup-context. Пути и классы указаны по состоянию репозитория (ветка
> `claude/mama-ui-v1`). **⚠ Требует проверки** — там, где факт не подтверждён кодом.

## Технологии

- **Kotlin** 2.0.21, **Gradle** 8.14.3 (wrapper), **AGP** 8.7.3.
- **JDK 17** (sourceCompatibility/target = 17, jvmTarget 17).
- Android: `compileSdk 35`, `minSdk 26`, `targetSdk 35`, `applicationId app.mama`.
- UI — **Views, собираются программно** (НЕ Jetpack Compose). Собственная
  дизайн-система (`app/ui/design/`).
- Тесты: core — JUnit 5; app — Robolectric 4.14.1 + JUnit 4; backend — JUnit 5 + Ktor test host.

## Модули (Gradle)

```
settings.gradle.kts → :core, :backend, (:app только если есть Android SDK)
```

- **`:core`** — чистый Kotlin (JVM, без Android). Вся бизнес-логика и
  детерминированные решения. Собирается и тестируется на голом JDK.
- **`:app`** — Android-слой. Исполняет решения ядра: оверлей, службы, страж,
  будильники, приёмники, UI. Включается в сборку только при наличии Android SDK
  (`ANDROID_HOME`/`local.properties`), иначе `settings.gradle.kts` его пропускает.
- **`:backend`** — отдельный Kotlin/**Ktor** сервис Trusted Exit. **Не зависимость
  `:app`, в APK не попадает.** Деплой — `backend/DEPLOY.md`.

`:app` зависит от `:core`. `:backend` не зависит ни от `:core`, ни от `:app`
(контракт между телефоном и backend — только HTTP/JSON).

## Структура `:core` (`core/src/main/kotlin/app/mama/core/`)

| Файл | Ответственность |
|---|---|
| `Model.kt` | Данные состояния: `Snapshot`, `Session`, `LockPlan`, `LockState`, `Series`, `Entitlements`, `Wallet`, `TrustedContact` и т.д. |
| `LockEngine.kt` | Создание/завершение сессии, переходы, проверка кода, `endByTrustedExit`. |
| `CorePolicy.kt` | Параметры: `codeLength=5`, `maxAttempts=3`, `codeTtl=15м`, `cooldownAfterFailures=30м`, min/max длительность. |
| `Recovery.kt` | Reconciliation: применяет прошедшее время, восстановление после перезагрузки. |
| `ClockGuard.kt` | Доверенное время: защита от перевода часов (anchor + elapsed + bootId). |
| `Series.kt` | `SeriesKind` THREE(3,99)/FIVE(5,179)/SEVEN(7,299), статусы серии. |
| `SeriesFlow.kt` | Жизненный цикл серии: schedule/confirm/fold/restart/cancel/edit. |
| `SeriesHistory.kt` | История завершённых/сорванных серий. |
| `Programs.kt` | `FlexEngine` (строка ~160), `FlexPackage` (`PRICE_RUB=349`), `StandardRules` (30-дневный cooldown, источник старта). |
| `Wallet.kt` | Внутренний баланс, «Цена слова» (`WalletEngine`, 100–100 000 ₽), частичные возвраты. |
| `DailyWindow.kt` | Окно «с/до», переход через полночь, часовые пояса. |
| `Codes.kt` | Генерация/проверка кодов (на стороне ядра; в Trusted Exit код теперь серверный). |
| `Emergency.kt` | Правила экстренного доступа. |
| `StateCodec.kt` | Сериализация `Snapshot` в строку (персист). |
| `TestMode.kt` | Тестовый режим: `DURATION=15 мин`, план тестовой блокировки. |

## Структура `:app` (`app/src/main/kotlin/app/mama/`)

- **`platform/Mama.kt`** — **единая точка входа из Android в ядро** (фасад). Любое
  событие (UI, будильник, загрузка, тик службы) проходит через него: reconcile →
  привести устройство к состоянию ядра (служба вкл/выкл, будильник). Содержит
  `startSeries`, `restartSeries`, `confirmProtection`, `requestCode`, `submitCode`,
  FLEX, test lock и т.д.
- **`lock/`**
  - `LockService.kt` — foreground-служба (`foregroundServiceType=specialUse`,
    `START_STICKY`, `directBootAware`). Держит оверлей, раз в секунду проверяет
    звонки, раз в ~15 с сверяет время.
  - `LockOverlay.kt` — контроллер оверлея (окно `TYPE_APPLICATION_OVERLAY`),
    реализует `LockActions`, владеет вводом кода и вызовами в ядро.
  - `LockScreenView.kt` — визуал экрана блокировки (Views), включая страницу
    выхода и TEST CODE-баннер (только в TEST_MODE).
  - `GuardService.kt` — `AccessibilityService`-страж: во время блокировки
    закрывает шторку/меню питания, любое другое приложение отправляет на «Домой».
    Смотрит только тип/размер/пакет окон, текст экрана не читает.
- **`platform/`** — `SessionStore` (device-protected storage), `Alarms`+`AlarmReceiver`
  (точные будильники), `SystemEventsReceiver` (boot/time/timezone/package-replaced),
  `DeviceClock`, `Protection` (проверка строгой защиты), `Notifications`, `Calls`/
  `CallControl`/`EmergencyCalls`, `MamaDeviceAdmin` (Device Admin),
  `MamaCallRedirectionService` (контроль исходящих во время лока), `History`, `Promise`.
- **`trustedexit/`**
  - `TrustedExitBackend.kt` — интерфейс `TrustedExitBackend` (createChallenge/verify)
    + `FakeTrustedExitBackend` (симуляция «сервера» на устройстве, `NOT PRODUCTION SECURE`).
  - `TrustedExitStore.kt` — на телефоне хранится только `StoredHandle`
    (challengeId, expiry, attemptsLeft, delivery, lockedUntil) — **ни кода, ни хэша**.
  - `TrustedExitDelivery.kt` — `TrustedExit` (держит активный backend),
    `TestCodeSink` (показ TEST CODE только в `FeatureFlags.TEST_MODE`),
    `DeliveryStatus`, `Connectivity`, SMS-сервисы-заглушки.
- **`ui/`** — `MainActivity`, экраны (`HomeScreens`, `SetupScreens`,
  `ScheduledScreens`, `CommitmentScreens`, `Statistics`), `design/` (`DesignKit`,
  `DesignKitV2`, `ScenicBackground`, `Tokens`, `TimerRingView` и др.), `Texts`,
  `Theme`, `Permissions`, `Previews`, `Components`, `Dialogs`, `Icons`.
- **`billing/Payments.kt`** — `FeatureFlags`, `Product` (Restart/FLEX), `Checkout`,
  `NoPayments` (реальные платежи выключены: `PAYMENTS_ENABLED=false`).
- **`motivation/`** — каталог и ротация мотивационных фраз.

## Механизм строгой блокировки (слои, Android без root)

Из `docs/CORE.md` §3:

| Слой | Что делает |
|---|---|
| Оверлей `TYPE_APPLICATION_OVERLAY` | Полноэкранное окно поверх всех приложений/лаунчера/недавних; «Домой»/«Назад» его не убирают. |
| Foreground-служба `LockService` | Держит оверлей, перезапускается при убийстве системой (`START_STICKY`). |
| Accessibility-страж `GuardService` | Закрывает шторку/меню питания, другое приложение → «Домой». Не даёт дойти до Настроек (нельзя остановить/снять разрешения/удалить). |
| Device Admin | Пока MAMA — админ устройства, удалить её одним нажатием нельзя. |
| `ClockGuard` | Перевод часов не укорачивает блокировку. |

Всегда доступно во время блокировки: входящие звонки, звонок контакту,
экстренные вызовы.

## Server-authoritative Trusted Exit

- Код (5 цифр) **генерит, хранит и проверяет только backend**. Телефон правильный
  код никогда не получает и не хранит.
- Телефон: `createChallenge` → получает `challengeId/expiresAt/attemptsLeft/delivery`;
  `verify(challengeId, enteredCode)` → `VALID/INVALID/EXPIRED/LOCKED`.
- Доставка не зависит от SIM телефона: SMS отправляет backend через SMS.RU.
- Backend (`:backend`, Ktor): `POST /trusted-exit/request`, `POST /trusted-exit/verify`,
  `GET /health`, `GET /ready`. SMS-провайдер за интерфейсом `SmsProvider`
  (`MockSmsProvider` для тестов, `SmsRuProvider` для прод). Ключ SMS.RU — только из
  env `MAMA_SMSRU_API_ID`, нигде в репозитории. См. `backend/DEPLOY.md`.
- **⚠ Текущее состояние клиента:** `MamaApp.kt` по умолчанию ставит
  `FakeTrustedExitBackend` (симуляция на устройстве). **HTTP-клиент
  (`HttpTrustedExitBackend`) к реальному backend ещё НЕ реализован** (это Фаза 3,
  см. `ROADMAP.md`). Поэтому реальная цепочка через SMS.RU пока проверялась только
  на самом backend, не из приложения.

## Ограничения Android (из CORE.md и полевого теста)

- Кнопка спец. возможностей в панели навигации может выключить стража — решается
  настройками телефона (см. `docs/STATUS.md` / `docs/FIELD_TEST_01_SAMSUNG.md`),
  в приложении не обходится.
- Камера и быстрые настройки на системном экране блокировки Samsung — тоже
  настройками телефона.
- `FOREGROUND_SERVICE_SPECIAL_USE` требует описания назначения (есть в манифесте).
- `SCHEDULE_EXACT_ALARM` — для точного старта/конца.
- **⚠** Манифест всё ещё объявляет `SEND_SMS` (историческое: раньше код слал сам
  телефон). При server-authoritative Trusted Exit отправку делает backend —
  возможно, `SEND_SMS` больше не нужен. **Не удалять без проверки** реальных
  путей отправки в коде.

## Разрешения (AndroidManifest.xml)

INTERNET, ACCESS_NETWORK_STATE, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE(+SPECIAL_USE),
POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, SCHEDULE_EXACT_ALARM, SEND_SMS (⚠ см.
выше), READ_PHONE_STATE, CALL_PHONE, ANSWER_PHONE_CALLS,
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, WAKE_LOCK. Компоненты: `MainActivity`,
`LockService`, `GuardService` (accessibility), `MamaCallRedirectionService`,
`SystemEventsReceiver`, `AlarmReceiver`, `MamaDeviceAdmin`.

## Персист состояния

`SessionStore` пишет `Snapshot` (через `StateCodec`) в
device-protected storage, чтобы блокировка и счётчик попыток переживали
перезагрузку. `TrustedExitStore` хранит активный handle там же.
