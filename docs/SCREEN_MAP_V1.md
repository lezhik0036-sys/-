# MAMA — карта экранов UI v1 (Android)

Перечень всех экранов Android-приложения (основа — v0.7.8) и утверждённые
референсы из `docs/design-reference/` (source of truth для UI v1).
Решения владельца продукта — `docs/DESIGN_SYSTEM_V1.md`, §12.

- Ключ экрана совпадает с именем экрана в режиме предпросмотра
  (`ui/Previews.kt`, `adb shell am start -n app.mama/.ui.MainActivity --es mama.preview <ключ>`).
- Номер — порядок в CI-туре скриншотов: текущий вид экрана — релиз
  `ui-screenshots`, файл `NN-<ключ>.jpg`. Референс лежит как
  `docs/design-reference/NN-<ключ>.webp`, чтобы их было удобно сравнивать.
- Дизайн-система — `docs/DESIGN_SYSTEM_V1.md`.

Статусы: **—** нет референса (делается в общем стиле референсов) ·
**реф** референс утверждён и сохранён · **готово** экран переделан и
проверен · **FUTURE** не входит в пилот.

## Итоговое соответствие «референс → экран»

| Референс | Экран |
|---|---|
| `03-series.webp` | 03 `series` — шаг 1 |
| `04-time.webp` | 04 `time` — шаг 2; стиль 21 `restart_time`, 25 `flex_plan` |
| `06-contact.webp` | 06 `contact` — шаг 3 (карточка контакта); 07 `promise` — шаг 4 (карточка «Моя причина») |
| `08-commitment.webp` | 08 `commitment` — шаг 5; 22 `restart_commitment` (суммы — по §12.3) |
| `09-review.webp` | 09 `review` — шаг 6; стиль 11 `edit` |
| `13-dashboard_active.webp` | 13 `dashboard_active`; стиль 10 `dashboard`, 14 `needs_attention`, 15 `restart_scheduled`, 24 `flex`, `test_session` |
| `16-lock.webp` | 16 `lock`; стиль 17 `emergency`, 18 `exit`, 19 `code` |
| `20-failure.webp` | 20 `failure`; стиль 23 `success`, `flex_result` |
| `new-role.webp` | `role` — FUTURE |
| `new-motivation.webp` | `motivation` — FUTURE (стиль цитат — в карточках-цитатах экранов) |

## До мастера (без счётчика шагов)

| № | Ключ | Экран | Когда показывается | Код | Референс | Статус |
|---|---|---|---|---|---|---|
| 01 | `welcome` | Приветствие | Первый запуск, нет серии | `SetupScreens.welcomeScreen` | — | — |
| 02 | `permissions` | «Подготовим MAMA» — разрешения | Перед мастером и «Исправить защиту» | `SetupScreens.permissionsScreen` | — | — |

## Мастер STANDARD: 6 шагов

| № | Ключ | Шаг | Экран | Код | Референс | Статус |
|---|---|---|---|---|---|---|
| 03 | `series` | 1 из 6 | Выбор серии: 3 · 5 · 7 дней · FLEX | `SetupScreens.seriesScreen` | `03-series.webp` | реф |
| 04 | `time` | 2 из 6 | Время (AUTO_NOW / MANUAL) | `SetupScreens.timeScreen` | `04-time.webp` | реф |
| 06 | `contact` | 3 из 6 | Доверенный контакт | `SetupScreens.contactScreen` | `06-contact.webp` | реф |
| 07 | `promise` | 4 из 6 | Личная причина | `SetupScreens.promiseScreen` | `06-contact.webp` («Моя причина») | реф |
| 08 | `commitment` | 5 из 6 | «Цена слова»: 500 / 1 000 / 3 000 / 5 000 ₽, своя сумма, «Продолжить без цены слова» | `CommitmentScreens.commitmentScreen` | `08-commitment.webp` | реф |
| 09 | `review` | 6 из 6 | Проверка / «Всё готово» | `SetupScreens.reviewScreen` | `09-review.webp` | реф |

FLEX: 1 Выбор · 2 Контакт · 3 Причина · 4 Проверка (время — у каждого
периода). Тест 15 минут (из «Настройки → Тестирование»): 1 Время · 2
Контакт · 3 Причина · 4 Проверка. Без кошелька шага «Цена слова» нет.

## Серия до начала и во время

| № | Ключ | Экран | Когда показывается | Код | Референс | Статус |
|---|---|---|---|---|---|---|
| 10 | `dashboard` | Серия запланирована (SCHEDULED) | После «Начать» | `HomeScreens.seriesDashboard` | стиль `13-dashboard_active.webp` | — |
| 11 | `edit` | Изменить условия | До ACTIVE: контакт, причина, «Цена слова» | `ScheduledScreens.editScreen` | стиль `09-review.webp` | — |
| 12 | `cancel_launch` | Отменить запуск (подтверждение) | До ACTIVE | `ScheduledScreens.confirmCancelLaunch` | — | — |
| 13 | `dashboard_active` | Серия идёт (ACTIVE) | После подтверждения защиты | `HomeScreens.seriesDashboard` | `13-dashboard_active.webp` | реф |
| 14 | `needs_attention` | Серия не началась (NEEDS_ATTENTION) | Защита не включилась | `HomeScreens.seriesDashboard` | стиль `13-dashboard_active.webp` | — |
| 15 | `restart_scheduled` | Restart запланирован | После оплаты Restart | `HomeScreens.seriesDashboard` | стиль `13-dashboard_active.webp` | — |

`dashboard_active`: только реальные данные (§12.6) — «День N из M»,
прогресс по дням, время до конца / начала блокировки, окно, «Цена слова»,
доверенный контакт. Без «Сегодня без телефона», «+1 ч 20 мин», «N дней
подряд», «Так держать!» и без кнопки «Продолжать».

## Экран блокировки (поверх всего)

| № | Ключ | Экран | Когда показывается | Код | Референс | Статус |
|---|---|---|---|---|---|---|
| 16 | `lock` | «Время для себя»: кольцо «Осталось», день серии, фраза | Идёт блокировка | `lock/LockScreenView` | `16-lock.webp` | реф |
| 17 | `emergency` | Экстренные службы | «Экстренный звонок» | `lock/LockScreenView` | стиль `16-lock.webp` | — |
| 18 | `exit` | «Мне действительно нужно выйти»: запрос кода | «Мне действительно нужно выйти» | `lock/LockScreenView` | стиль `16-lock.webp` | — |
| 19 | `code` | Ввод кода доверенного контакта | После запроса кода | `lock/LockScreenView` | стиль `16-lock.webp` | — |

`lock`: только два действия — «Экстренный звонок» и «Мне действительно
нужно выйти» (доверенный выход). «Я держу курс» — только некликабельная
плашка, не кнопка и не действие; «‹» с референса на блокировке не
показывается — назад некуда.

## Итоги и Restart

| № | Ключ | Экран | Когда показывается | Код | Референс | Статус |
|---|---|---|---|---|---|---|
| 20 | `failure` | «Этот путь продолжается»; небольшим текстом «Серия завершилась раньше · N из M»; «Попробовать снова · Restart · 299 ₽», «Начать заново» | Срыв ACTIVE-серии | `HomeScreens.failureScreen` | `20-failure.webp` | реф |
| 21 | `restart_time` | Restart: время | «Начать заново», шаг 1 из 2 | `SetupScreens.timeScreen` | стиль `04-time.webp` | — |
| 22 | `restart_commitment` | Restart: «Цена слова» (не ниже цены Restart) | «Начать заново», шаг 2 из 2 | `CommitmentScreens.commitmentScreen` | стиль `08-commitment.webp` | — |
| 23 | `success` | Серия пройдена | Все дни серии | `HomeScreens.successScreen` | стиль `20-failure.webp` | — |

`failure`: светлая сцена `RECOVERY`, без красного, «!», «Неудача», «Провал»,
«Ты проиграл»; слово «Срыв» не главный заголовок; без упоминания будущего
возврата 50 %.

## FLEX

| № | Ключ | Экран | Когда показывается | Код | Референс | Статус |
|---|---|---|---|---|---|---|
| 24 | `flex` | FLEX: пакет и дни | Активный пакет FLEX | `HomeScreens.flexDashboard` | стиль `13-dashboard_active.webp` | — |
| 25 | `flex_plan` | FLEX: даты и время периода | «Запланировать FLEX-период» | `HomeScreens.flexPlannerScreen` | стиль `04-time.webp` | — |
| — | `flex_result` | Итог FLEX | Пакет закончился | `HomeScreens.flexResultScreen` | стиль `20-failure.webp` | — |

## Вкладки и настройки

| № | Ключ | Экран | Когда показывается | Код | Референс | Статус |
|---|---|---|---|---|---|---|
| 26 | `stats` | Статистика (пока без выдуманных цифр) | Вкладка «Статистика» | `HomeScreens.statisticsScreen` | — | — |
| 27 | `settings` | Настройки: баланс MAMA, история, разрешения, «Тестирование» | Вкладка «Настройки»; шестерёнка на приветствии | `HomeScreens.settingsScreen` | — | — |
| — | `settings_testing` | Настройки → Тестирование (PHASE 2) | Только debug/pilot-сборка | `HomeScreens.testingScreen` | стиль `ds_test` | — |
| 05 | `time_test` | Тестовая блокировка 15 минут: время (шаг 1 из 4) | «Тестовая блокировка · 15 минут» | `SetupScreens.timeScreen` | стиль `04-time.webp` | — |

**Настройки → Тестирование** (только при `PILOT_TEST_CREDITS` /
`TEST_MODE_ENABLED`):
- «Тестовая блокировка · 15 минут» — запускает мастер теста (время, контакт,
  причина, проверка); доступно, когда нет серии, FLEX или блокировки;
- «+1 000 ₽ · тест», «+5 000 ₽ · тест» — пополнение того же баланса MAMA;
- пояснение: тестовые кредиты по-настоящему тратятся на Restart, FLEX и
  «Цену слова».

## Витрина фундамента UI v1 (PHASE 1)

Только debug-сборка: базовые компоненты на примерах, не экраны продукта
(`ui/design/DesignPreviews.kt`). Продолжают CI-тур после экранов 01–27.

| № | Ключ | Что показано | Сравнение |
|---|---|---|---|
| 28 | `ds_palette` | Палитра, градиент кнопки, прогресс | — |
| 29 | `ds_type` | Типографика | — |
| 30 | `ds_buttons` | Кнопки, чипы «Цены слова», плашки, «Я держу курс» (не кнопка), согласие, прогресс | — |
| 31 | `ds_cards` | Карточки выбора с пейзажем, «Твой план», действие-строка | — |
| 32 | `ds_notes` | Значение (реальные данные), заметки, цитаты | — |
| 33 | `ds_light` | Пример светлого экрана | `vs-ds_light.jpg` ↔ `09-review.webp` |
| 34 | `ds_scenic` | Пример экрана с пейзажем | `vs-ds_scenic.jpg` ↔ `13-dashboard_active.webp` |
| 35 | `ds_recovery` | Пример карточек срыва / восстановления | `vs-ds_recovery.jpg` ↔ `20-failure.webp` |
| 36 | `ds_lock_shell` | Каркас экрана блокировки | `vs-ds_lock_shell.jpg` ↔ `16-lock.webp` |
| 37 | `ds_sheet` | Шторка и диалог | — |
| 38 | `ds_test` | Стиль тестовых инструментов | — |
| 39 | `ds_scenes` | Временные фоны `ScenicBackground` | — |

## Без отдельного превью

| Ключ | Экран | Когда показывается | Код | Референс | Статус |
|---|---|---|---|---|---|
| `test_session` | Идёт тестовая блокировка 15 минут | Тест запущен | `HomeScreens.sessionDashboard` | стиль `13-dashboard_active.webp` | — |
| `checkout` | Оплата Restart / FLEX: «Оплатить с баланса MAMA», «+1 000 ₽ · тест» при нехватке | Restart, FLEX | `billing/Payments.kt` (`Checkout`) | — | — |
| `return_notice` | «MAMA вернула тебе …» (возврат 50 %) | После фактического начисления | `CommitmentScreens.showReturnNoticeIfAny` | — | — |
| `time_picker` | Цифровой выбор времени | Поля времени | `DigitalTimePicker` | — | — |
| `date_list` | Выбор даты (FLEX) | Планирование FLEX | `DateList` | — | — |
| `zone_picker` | Часовой пояс | «Изменить» на шаге времени | `SetupScreens.pickZone` (`BrandDialog`) | — | — |

## FUTURE (в пилот не входят)

| Ключ | Экран | Референс | Статус |
|---|---|---|---|
| `role` | «Кто ты сегодня?» — роль: Спортсмен · Родитель · Работник · Бизнесмен · Студент · Творец; «MAMA подстроит фото и слова поддержки» | `new-role.webp` | FUTURE |
| `motivation` | «Поддержка для тебя» — мотивация по ролям, карточки с фото людей | `new-motivation.webp` | FUTURE |

Не шаги мастера. Подготовлено: `MotivationCatalog` с `profileType`
(`ATHLETE`, `ENTREPRENEUR`, `PARENT`, `PROFESSIONAL`, `STUDENT`, `CREATOR`,
`GENERAL`); в пилоте всегда `GENERAL`. Фразы поддержки показываются внутри
существующих экранов: запланированная серия, ACTIVE, блокировка, успех,
срыв / восстановление, «Всё готово», «Цена слова», FLEX.

## Обязательно сохранить при редизайне

- Тестовый режим 15 минут (`time_test`, `test_session`; вход — «Настройки →
  Тестирование»).
- Тестовое пополнение баланса +1 000 ₽ / +5 000 ₽ («Настройки →
  Тестирование») и «+1 000 ₽ · тест» при нехватке средств (`checkout`).
- Реальная оплата тестовыми кредитами Restart, FLEX и «Цены слова»
  (`checkout`, `commitment`, `restart_commitment`).
- Все состояния серии из `docs/MONETIZATION.md`; тексты условий — оттуда же.
