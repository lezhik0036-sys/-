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

Mac не нужен. Подписанную сборку для TestFlight собирает Codemagic
(`codemagic.yaml` в корне, только ручной запуск). GitHub Actions
(`.github/workflows/ios.yml`) на каждый push в `ios/` проверяет сборку под
симулятор без подписи; его ручной запуск — запасной путь в TestFlight.
`scripts/verify-ipa.sh` проверяет в готовом IPA Bundle ID и подписанные
entitlements всех трёх частей.

## Идентификаторы

| Цель | Bundle ID | Capabilities |
|---|---|---|
| Приложение | `app.mama.sleep` | Family Controls, App Groups |
| Shield (экран блокировки) | `app.mama.sleep.shield` | Family Controls, App Groups |
| Monitor (расписание) | `app.mama.sleep.monitor` | Family Controls, App Groups |
| App Group | `group.app.mama.sleep` | — |

Они записаны в `project.yml` и `Shared/MamaStore.swift` буквально и не
меняются: на каждом одобрен Family Controls (Distribution).

## Настройка Codemagic (один раз)

1. App Store Connect → Пользователи и доступ → Интеграции → App Store Connect
   API → Ключи команды → «+». Доступ **Admin**. Скачайте `.p8` (дают один
   раз), запишите Issuer ID и Key ID.
2. codemagic.io → вход через GitHub → Add application → репозиторий
   `lezhik0036-sys/-` → тип **iOS App**, конфигурация **codemagic.yaml**.
3. Team settings → Integrations → **Developer Portal** → Connect: имя
   ровно `MAMA App Store Connect`, Issuer ID, Key ID, файл `.p8`.
4. Team settings → codemagic.yaml settings → **Code signing identities** →
   iOS certificates → Generate certificate, тип **Apple Distribution**, ключ
   `MAMA App Store Connect`. Сохраните пароль сертификата.
5. developer.apple.com → Profiles → «+» → **App Store Connect** — три
   профиля, по одному на `app.mama.sleep`, `app.mama.sleep.shield`,
   `app.mama.sleep.monitor`, с сертификатом из шага 4. Family Controls и
   App Group профили берут из App ID сами.
6. Codemagic → Code signing identities → iOS provisioning profiles →
   **Fetch profiles** → отметьте все три.
7. Start new build → ветка → workflow `MAMA iOS → TestFlight`.

Для запасного пути через GitHub Actions нужны секреты `ASC_KEY_ID`,
`ASC_ISSUER_ID`, `ASC_KEY_P8` и `APPLE_TEAM_ID` (Settings → Secrets and
variables → Actions).

## Пилот

Кнопка «Остановить (пилот)» снимает блокировку сразу, чтобы тест не запер
телефон. Её выключает `Pilot.allowsStop` в `Shared/MamaStore.swift`, когда
появится досрочный выход по коду доверенного контакта.
