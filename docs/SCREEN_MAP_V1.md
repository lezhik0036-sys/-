# MAMA — карта экранов UI v1 (Android)

Перечень всех экранов текущего Android-приложения (v0.7.8) — основа
редизайна — и утверждённые референсы из `docs/design-reference/` (source of
truth для UI v1). Пока ничего в интерфейсе не меняется.

- Ключ экрана совпадает с именем экрана в режиме предпросмотра
  (`ui/Previews.kt`, `adb shell am start -n app.mama/.ui.MainActivity --es mama.preview <ключ>`).
- Номер — порядок в CI-туре скриншотов: текущий вид экрана — релиз
  `ui-screenshots`, файл `NN-<ключ>.jpg`. Референс кладётся как
  `docs/design-reference/NN-<ключ>.png`, чтобы их было удобно сравнивать.
- Дизайн-система — `docs/DESIGN_SYSTEM_V1.md`.

Статусы: **—** нет референса · **реф** референс утверждён и сохранён ·
**реф (чат)** утверждён, но файл ещё не в репозитории · **готово** экран
переделан и проверен. Экран без референса делается в общем стиле
референсов (`docs/DESIGN_SYSTEM_V1.md`).

## Настройка серии (светлые экраны)

| № | Ключ | Экран | Когда показывается | Код | Референс | Статус |
|---|---|---|---|---|---|---|
| 01 | `welcome` | Приветствие | Первый запуск, нет серии | `SetupScreens.welcomeScreen` | — | — |
| 02 | `permissions` | Разрешения (строгая защита) | Перед первой серией и «Исправить защиту» | `SetupScreens.permissionsScreen` | — | — |
| 03 | `series` | Выбор: тест 15 мин · 3 · 5 · 7 дней · FLEX | Мастер, шаг 1 | `SetupScreens.seriesScreen` | `03-series.webp` | реф |
| 04 | `time` | Выбери время (AUTO_NOW / MANUAL) | Мастер, шаг 2 | `SetupScreens.timeScreen` | `04-time.webp` | реф |
| 05 | `time_test` | Тестовый режим 15 минут | Мастер, если выбран тест | `SetupScreens.timeScreen` | — | — |
| 06 | `contact` | Доверенный контакт | Мастер | `SetupScreens.contactScreen` | `06-contact.webp` | реф |
| 07 | `promise` | Личная причина | Мастер | `SetupScreens.promiseScreen` | `06-contact.webp` (карточка «Моя причина») | реф |
| 08 | `commitment` | «Цена слова» + баланс | Мастер | `CommitmentScreens.commitmentScreen` | `08-commitment.webp` | реф |
| 09 | `review` | Проверь условия | Мастер, последний шаг | `SetupScreens.reviewScreen` | `09-review.webp` | реф (чат) |

## Серия до начала и во время (ночные экраны)

| № | Ключ | Экран | Когда показывается | Код | Референс | Статус |
|---|---|---|---|---|---|---|
| 10 | `dashboard` | Серия запланирована (SCHEDULED) | После «Начать серию» | `HomeScreens.seriesDashboard` | — | — |
| 11 | `edit` | Изменить условия | До ACTIVE: контакт, причина, «Цена слова» | `ScheduledScreens.editScreen` | — | — |
| 12 | `cancel_launch` | Отменить запуск (подтверждение) | До ACTIVE | `ScheduledScreens.confirmCancelLaunch` | — | — |
| 13 | `dashboard_active` | Серия идёт (ACTIVE) | После подтверждения защиты | `HomeScreens.seriesDashboard` | `13-dashboard_active.webp` | реф (чат) |
| 14 | `needs_attention` | Серия не началась (NEEDS_ATTENTION) | Защита не включилась | `HomeScreens.seriesDashboard` | — | — |
| 15 | `restart_scheduled` | Restart запланирован | После оплаты Restart | `HomeScreens.seriesDashboard` | — | — |

## Экран блокировки (поверх всего)

| № | Ключ | Экран | Когда показывается | Код | Референс | Статус |
|---|---|---|---|---|---|---|
| 16 | `lock` | Блокировка: таймер, фраза | Идёт блокировка | `lock/LockScreenView` | `16-lock.webp` | реф (чат) |
| 17 | `emergency` | Экстренные службы | «Экстренный звонок» | `lock/LockScreenView` | — | — |
| 18 | `exit` | Досрочный выход: запрос кода | «Попросить код» | `lock/LockScreenView` | — | — |
| 19 | `code` | Ввод кода доверенного контакта | После запроса кода | `lock/LockScreenView` | — | — |

## Итоги и Restart

| № | Ключ | Экран | Когда показывается | Код | Референс | Статус |
|---|---|---|---|---|---|---|
| 20 | `failure` | Серия завершена досрочно | Срыв ACTIVE-серии | `HomeScreens.failureScreen` | `20-failure.webp` | реф (чат) |
| 21 | `restart_time` | Restart: выбери время | «Начать заново» | `SetupScreens.timeScreen` | — | — |
| 22 | `restart_commitment` | Restart: «Цена слова» | «Начать заново», шаг 2 | `CommitmentScreens.commitmentScreen` | — | — |
| 23 | `success` | Серия пройдена | Все дни серии | `HomeScreens.successScreen` | — | — |

## FLEX

| № | Ключ | Экран | Когда показывается | Код | Референс | Статус |
|---|---|---|---|---|---|---|
| 24 | `flex` | FLEX: пакет и дни | Активный пакет FLEX | `HomeScreens.flexDashboard` | — | — |
| 25 | `flex_plan` | FLEX: даты и время периода | «Запланировать FLEX-период» | `HomeScreens.flexPlannerScreen` | — | — |
| — | `flex_result` | Итог FLEX | Пакет закончился | `HomeScreens.flexResultScreen` | — | — |

## Новые экраны с референсов (в приложении их нет)

Новая функциональность, а не перерисовка: делается только после
подтверждения владельцем продукта (см. «Открытые вопросы» в
`docs/DESIGN_SYSTEM_V1.md`).

| Ключ | Экран | Референс | Статус |
|---|---|---|---|
| `role` | «Кто ты сегодня?» — роль: Спортсмен · Родитель · Работник · Бизнесмен · Студент · Творец; «MAMA подстроит фото и слова поддержки» | `new-role.webp` | реф |
| `motivation` | «Поддержка для тебя» — мотивация по ролям, карточки с фото людей, «Выбрать мотивацию» | `new-motivation.webp` | реф (чат) |

## Вкладки

| № | Ключ | Экран | Когда показывается | Код | Референс | Статус |
|---|---|---|---|---|---|---|
| 26 | `stats` | Статистика | Вкладка «Статистика» | `HomeScreens.statisticsScreen` | — | — |
| 27 | `settings` | Настройки, баланс MAMA, история | Вкладка «Настройки» | `HomeScreens.settingsScreen`, `CommitmentScreens.balanceCard`, `ledgerList` | — | — |

## Без отдельного превью

| Ключ | Экран | Когда показывается | Код | Референс | Статус |
|---|---|---|---|---|---|
| `test_session` | Идёт тестовый режим 15 минут | Тест запущен | `HomeScreens.sessionDashboard` | — | — |
| `checkout` | Оплата Restart / FLEX: «Оплатить с баланса MAMA», «+1 000 ₽ · тест» | Restart, FLEX | `billing/Payments.kt` (`Checkout`) | — | — |
| `return_notice` | «MAMA вернула тебе …» (возврат 50 %) | Через 24 ч после срыва с «Ценой слова» | `CommitmentScreens.showReturnNoticeIfAny` | — | — |
| `time_picker` | Цифровой выбор времени | Поля времени | `DigitalTimePicker` | — | — |
| `date_list` | Выбор даты (FLEX) | Планирование FLEX | `DateList` | — | — |
| `zone_picker` | Часовой пояс | «Изменить» на шаге времени | `SetupScreens.pickZone` (`BrandDialog`) | — | — |

## Обязательно сохранить при редизайне

- Тестовый режим 15 минут (экраны `series`, `time_test`, `test_session`).
- Тестовое пополнение баланса +1 000 ₽ / +5 000 ₽ (карточка баланса MAMA
  на экранах `commitment` и `settings`) и «+1 000 ₽ · тест» при нехватке
  средств (`checkout`).
- Реальная оплата тестовыми кредитами Restart, FLEX и «Цены слова»
  (`checkout`, `commitment`, `restart_commitment`).
- Все состояния серии из `docs/MONETIZATION.md`; тексты условий — оттуда же.
