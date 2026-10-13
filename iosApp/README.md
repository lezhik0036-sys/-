# MAMA для iPhone

Ветка `claude/mama-ios` — прототип MAMA для iOS. Android-версия продолжается в
ветке `claude/mama-phone-lock-app-vzccj7`; эта ветка создана от неё (v0.7.8).
Когда архитектура устоится, обе платформы должны жить в одном репозитории
(`/shared`, `/androidApp`, `/iosApp`), чтобы общая логика не расходилась.

## Архитектура

- `core/` — общая бизнес-логика на **Kotlin Multiplatform**: серии 3/5/7,
  FLEX, Restart, «Цена слова», кошелёк и история, 30 дней после срыва,
  правила времени (AUTO_NOW / MANUAL, полное окно), доверенный выход. Тот же
  код, что у Android (JVM), для iPhone собирается во фреймворк `MamaCore`.
  Общие тесты идут на JVM и в симуляторе iOS. Платформенной защиты в общем
  модуле нет.
- `MamaBridge` (в `core`) — вход для Swift: только простые типы (миллисекунды,
  минуты суток, id часового пояса, строковая карта состояния).
- `iosApp/` — нативное приложение на **SwiftUI** и защита через
  **FamilyControls**, **ManagedSettings**, **ManagedSettingsUI**,
  **DeviceActivity**:
  - `MAMA/` — приложение: Screen Time, мастер серии, главный экран;
  - `ActivityMonitor/` — расширение DeviceActivity: включает щит в начале
    окна серии и снимает в конце, даже если приложение закрыто;
  - `ShieldConfiguration/` — расширение ManagedSettingsUI: экран MAMA на
    закрытых приложениях;
  - `Shared/` — общее для приложения и расширений (App Group, щит, расписание).

## Правила миграции (регрессионный контракт)

iOS/KMP-миграция **не меняет продуктовую семантику Android MAMA**.

- В `core` (shared) — только платформенно-независимая бизнес-логика.
  Android-защита (Accessibility, оверлей, Device Admin, CallRedirection,
  восстановление после перезагрузки и другие Android API) остаётся в `app/`;
  iOS-защита (FamilyControls, ManagedSettings, DeviceActivity) и SwiftUI —
  в `iosApp/`. Android-защиту ради KMP не переписываем.
- Замена Java time API: существующие тесты — контракт. Они перенесены без
  изменения входных данных и ожиданий; отличаются только записью типов
  (`java.time` → `kotlinx-datetime` / `kotlin.time`). Это проверяет
  `tools/kmp_test_contract.py`: каждый core-тест Android-ветки должен быть в
  `core/src/commonTest` и отличаться только записью типов (CI, job `build`).
- После каждого изменения shared core должны проходить все наборы:

| Что | Наборы |
|---|---|
| Все Android core-тесты (143) | `core/src/commonTest` на JVM (`:core:jvmTest`) и в симуляторе iOS |
| AUTO_NOW / MANUAL, ACTIVATING → ACTIVE (точка фиксации) | `AutoNowLaunchTest`, `SeriesDraftTest`, `SeriesStartPointTest` |
| NEEDS_ATTENTION | `SeriesStartPointTest`, `AutoNowLaunchTest`, `SeriesTest` |
| Restart = свежий черновик времени | `SeriesFlowTest` |
| «Цена слова» / кошелёк | `WalletTest` |
| 30 дней (cooldown) | `ProgramsTest`, `SeriesFlowTest` |
| FLEX — независимые периоды | `ProgramsTest` |
| Часовые пояса, окна через полночь, фиксированное окно 3/5/7 | `DailyWindowTest`, `SeriesStartPointTest`, `SeriesTest` |
| Новые iOS/shared | `MamaBridgeTest`, `Sha256JvmTest` |
| Android-приложение на shared core | Robolectric (`:app:testDebugUnitTest`), запуск на эмуляторе |

- Стабильная Android-ветка не трогается; iOS-работа идёт только в `claude/mama-ios`.

## Решения владельца продукта

1. Защита — Screen Time. Пользователь **не выбирает приложения**: MAMA
   закрывает все категории приложений и сайтов, которые разрешает iOS
   (`.all()`), и запрещает удалять MAMA на время щита. «Телефон» и
   экстренные вызовы iOS не закрывает никогда. Сценарий тот же: серия →
   время → доверенный контакт → «Цена слова» → запуск.
2. Authorization FamilyControls — `.individual` (взрослый владелец своего
   iPhone). Сознательный отзыв доступа во время ACTIVE — нарушение защиты и
   срыв, но только после достоверной проверки, что доступ действительно
   denied/revoked, а не временный сбой. *(Следующий шаг.)*
3. Доверенный выход — серверный, одинаковый для Android и iOS: сервер
   генерирует одноразовый код из 5 цифр и сам шлёт SMS контакту, в MAMA
   вводится код, 3 попытки. *(Нужен сервер; следующий шаг.)*
4. Kotlin Multiplatform — только для общей бизнес-логики; UI и защита на
   каждой платформе свои.

## Что уже работает в прототипе

- Разрешение Screen Time (`AuthorizationCenter.requestAuthorization(for: .individual)`).
- Мастер: серия 3/5/7 → время (AUTO_NOW по умолчанию, MANUAL после ручной
  правки начала — правила из общего кода) → доверенный контакт → «Цена слова»
  (на iPhone пока без баланса, серия идёт без неё) → проверка → запуск.
- Запуск: общий `SeriesFlow` создаёт серию; окно становится расписанием
  DeviceActivity на каждый день; при AUTO_NOW щит включается сразу.
- Точка начала серии на iPhone: блокировка началась, щит включился, доступ
  Screen Time есть → ACTIVE (как `strictProtectionReady` на Android). Нет
  доступа или щит не включился — NEEDS_ATTENTION, «Подтвердить запуск»;
  «Отменить запуск» до ACTIVE.
- После последней ночи серии расширение снимает расписание.
- Debug: «Проверка Screen Time» — включить/снять тестовый щит и тестовое
  расписание «щит через 1 минуту на 15 минут».

## Сборка

На Mac с Xcode 15.4 (Kotlin 2.0.21 поддерживает Xcode до 16.0):

```bash
./gradlew :core:iosSimulatorArm64Test             # общие тесты в симуляторе
./gradlew :core:assembleMamaCoreDebugXCFramework  # фреймворк MamaCore
brew install xcodegen
cd iosApp && xcodegen generate && open MAMA.xcodeproj
```

CI (job `ios`, macOS) делает то же самое и собирает приложение для симулятора
без подписи.

## Перед установкой на iPhone и публикацией

- **Apple Developer Program** — для установки на устройство через TestFlight
  и для App Store.
- **Family Controls (Distribution)** — право `com.apple.developer.family-controls`
  для распространения выдаёт Apple по отдельной заявке. Для разработки на
  своём устройстве хватает development-версии права.
- App Group `group.app.mama.shared` и bundle id `app.mama.ios`,
  `app.mama.ios.monitor`, `app.mama.ios.shield` нужно зарегистрировать в
  аккаунте разработчика.

## Следующие шаги

- Отзыв доступа Screen Time в ACTIVE: проверка denied/revoked и срыв.
- Серверный доверенный выход (общий с Android).
- «Цена слова» и баланс MAMA на iPhone.
- Защита от перевода часов (аналог `ClockGuard` с монотонным временем iOS).
