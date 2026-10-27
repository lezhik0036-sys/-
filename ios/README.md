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

## Настройка (один раз)

1. **Family Controls.** developer.apple.com → Account → запросите
   *Family Controls (Distribution)* для приложения. Без одобрения Apple
   выгрузка в TestFlight остановится на подписи; сборка под симулятор
   работает и без него.
2. **Карточка приложения.** appstoreconnect.apple.com → Apps → «+» → New App,
   Bundle ID `app.mama` (если он занят, задайте свой в переменной
   `IOS_BUNDLE_ID`, см. ниже).
3. **API-ключ.** App Store Connect → Users and Access → Integrations →
   App Store Connect API → «+», роль **Admin**. Скачайте `.p8` (дают один
   раз) и запишите Key ID и Issuer ID.
4. **Секреты GitHub.** Settings → Secrets and variables → Actions:
   - `ASC_KEY_ID` — Key ID;
   - `ASC_ISSUER_ID` — Issuer ID;
   - `ASC_KEY_P8` — всё содержимое файла `.p8`;
   - `APPLE_TEAM_ID` — Team ID (developer.apple.com → Membership).

   Необязательная переменная (вкладка Variables): `IOS_BUNDLE_ID`.
5. Actions → ios → Run workflow. Через 10–20 минут после загрузки сборка
   появится в App Store Connect → TestFlight; установите её на iPhone через
   приложение TestFlight.

## Пилот

Кнопка «Остановить (пилот)» снимает блокировку сразу, чтобы тест не запер
телефон. Её выключает `Pilot.allowsStop` в `Shared/MamaStore.swift`, когда
появится досрочный выход по коду доверенного контакта.
