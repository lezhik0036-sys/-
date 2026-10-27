# MAMA для iOS

Облегчённая версия на Screen Time API: выбранные приложения закрываются
экраном MAMA с «с» до «до». Блокировку держит сама система: она переживает
перезагрузку и работает, даже если приложение закрыто. Строгость уровня
Android недоступна: в режиме «для себя» доступ к Экранному времени можно
отозвать в Настройках (см. docs/CORE.md).

```
MAMA/      приложение (SwiftUI): доступ, выбор приложений, время, запуск
Shield/    расширение: экран поверх заблокированного приложения
Monitor/   расширение: включает и снимает блокировку по расписанию
Shared/    общее состояние через App Group
project.yml  описание проекта для XcodeGen (.xcodeproj не хранится в git)
```

Mac не нужен: всё собирает GitHub Actions (`.github/workflows/ios.yml`).
Каждый push в `ios/` проверяет сборку под симулятор. TestFlight запускается
вручную: Actions → ios → Run workflow.

## Идентификаторы

| Цель | Bundle ID | Capabilities |
|---|---|---|
| Приложение | `app.mama.sleep` | Family Controls, App Groups |
| Shield (экран блокировки) | `app.mama.sleep.shield` | Family Controls, App Groups |
| Monitor (расписание) | `app.mama.sleep.monitor` | Family Controls, App Groups |
| App Group | `group.app.mama.sleep` | — |

Все три берутся из `MAMA_BUNDLE_ID` в `project.yml`; основной менять нельзя:
на нём одобрен Family Controls (Distribution).

## Настройка (один раз)

1. developer.apple.com → Certificates, Identifiers & Profiles:
   App Group `group.app.mama.sleep`, App ID для `.shield` и `.monitor`,
   App Groups у всех трёх App ID, Family Controls (Distribution) для
   каждого из трёх ID.
2. App Store Connect → Users and Access → Integrations → App Store Connect
   API → «+», роль **Admin**. Скачайте `.p8` (дают один раз) и запишите
   Key ID и Issuer ID.
3. GitHub → Settings → Secrets and variables → Actions:
   - `ASC_KEY_ID` — Key ID;
   - `ASC_ISSUER_ID` — Issuer ID;
   - `ASC_KEY_P8` — всё содержимое файла `.p8`;
   - `APPLE_TEAM_ID` — Team ID (developer.apple.com → Membership).
4. Actions → ios → Run workflow. Через 10–20 минут после загрузки сборка
   появится в App Store Connect → TestFlight; установите её на iPhone через
   приложение TestFlight.

## Пилот

Кнопка «Остановить (пилот)» снимает блокировку сразу, чтобы тест не запер
телефон. Её выключает `Pilot.allowsStop` в `Shared/MamaStore.swift`, когда
появится досрочный выход по коду доверенного контакта.
