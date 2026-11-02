# MAMA Android — Recovery Guide

> Как восстановить Android-проект из GitHub, настроить Android Studio и запустить
> приложение. Backup-context. **⚠** — проверить под свою среду.

## 1. Получить проект из GitHub

- Репозиторий: `https://github.com/lezhik0036-sys/-` (приватный; имя — `-`).
- Рабочая ветка версии: `claude/mama-ui-v1`.

```bash
git clone https://github.com/lezhik0036-sys/-.git mama
cd mama
git checkout claude/mama-ui-v1
```
(Имя репозитория — одиночный `-`, поэтому клонируем в явную папку `mama`.)

## 2. Тулчейн

| Инструмент | Версия | Откуда |
|---|---|---|
| JDK | **17** | `compileOptions`/`jvmTarget` |
| Gradle | **8.14.3** | `gradle/wrapper/gradle-wrapper.properties` (wrapper скачает сам) |
| Android Gradle Plugin | **8.7.3** | корневой `build.gradle.kts` |
| Kotlin | **2.0.21** | корневой `build.gradle.kts` |
| compileSdk / targetSdk | **35** | `app/build.gradle.kts` |
| minSdk | **26** (Android 8.0) | `app/build.gradle.kts` |
| Android Studio | версия с поддержкой AGP 8.7.x (**⚠** Koala/Ladybug или новее) | — |

## 3. Android Studio

1. File → Open → выбрать папку проекта (`mama`). Дождаться Gradle sync.
2. Android SDK: установить platform API 35 и build-tools. Studio обычно создаёт
   `local.properties` с `sdk.dir` автоматически; иначе создать вручную:
   ```
   sdk.dir=/Users/<you>/Library/Android/sdk        # macOS
   # или установить переменную окружения ANDROID_HOME
   ```
   `local.properties` в Git не коммитится (он в `.gitignore`).
3. Если `:app` «не виден» при сборке из командной строки — значит не найден
   Android SDK: `settings.gradle.kts` намеренно пропускает `:app` без SDK
   (`:core`/`:backend` при этом собираются).

## 4. Сборка

```bash
./gradlew :core:test            # ядро, только JDK 17 (нужен интернет для зависимостей)
./gradlew :backend:test         # backend Trusted Exit
./gradlew :app:assembleDebug    # debug APK (нужен Android SDK)
```
Готовые debug-APK также публикуются CI как prerelease `test-v<version>`
(вкладка Releases репозитория) — можно скачать на телефон напрямую.

## 5. Запуск на телефоне (debug)

1. Установить debug-APK.
2. Android 13+: Настройки → Приложения → MAMA → ⋮ → «Разрешить доступ к
   ограниченным настройкам» (иначе не включится служба спец. возможностей).
3. Открыть MAMA, пройти чек-лист разрешений (кнопка «Выдать все разрешения»):
   наложение поверх окон (SYSTEM_ALERT_WINDOW), служба спец. возможностей
   (Guardian), Device Admin, точные будильники, уведомления, звонки.
4. Samsung/One UI — выполнить настройки телефона из `docs/STATUS.md` и
   `docs/FIELD_TEST_01_SAMSUNG.md`, иначе страж можно обойти системными кнопками.
5. Перед первой ночью — короткая тестовая сессия 15 минут.

**Поведение debug-сборки (TEST_MODE=true):** каждый лок капится 15 минутами, на
экране выхода показывается TEST CODE, Trusted Exit по умолчанию использует
`FakeTrustedExitBackend` (симуляцию на устройстве) — реальный backend/SMS для
проверки потока в debug не нужен. В release этого нет.

## 6. Backend Trusted Exit (если нужен)

Отдельный сервис в `backend/`. Полная инструкция по развёртыванию (Docker/
Compose/Caddy, переменные окружения, где взять и куда вставить `MAMA_SMSRU_API_ID`,
smoke-тест одной SMS) — **`backend/DEPLOY.md`**. Ключ SMS.RU — только в server-side
env, никогда в репозитории/APK.

## 7. Проверка после восстановления

```bash
git branch --show-current                 # claude/mama-ui-v1
grep -E 'versionName|versionCode' app/build.gradle.kts   # 0.8.20-test20 / 51
./gradlew :core:test :backend:test        # должны пройти
```
Открыть приложение в эмуляторе/на устройстве и пройти настройку до тестовой
15-минутной блокировки.
