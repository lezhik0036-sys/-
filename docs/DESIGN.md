# MAMA — дизайн-система

Визуальный эталон — макет из 10 экранов (светлый мастер настройки, тёмные
ночные экраны с пейзажем). Бизнес-правила и цены — из docs/MONETIZATION.md,
не из текста на макете.

## Палитра (`ui/Theme.kt` → `MamaColors`)

Warm Ivory `#F7F4EC` · Pure Card `#FCFAF5` · Warm Card `#EEE9DF` ·
MAMA Emerald `#28594A` · Emerald Active `#3E735F` · Emerald Soft `#A8BFAF` ·
Olive `#83896A` · Olive Light `#D9DCCB` · Graphite `#262A27` ·
Secondary Text `#70756E` · Night `#091714` · Night Surface `#102720` ·
Night Soft `#1D3A30` · Progress Glow `#C6D39D` · Warning `#C87561` ·
Danger Soft `#F1DDD6` · Success `#5D8269`. Примерно 80 % нейтральных,
15 % изумруда, 5 % оливы и предупреждений. Без ярко-синего.

## Шрифты

В приложение встроены (assets/fonts, лицензия SIL OFL), поэтому системный
шрифт телефона (например, рукописный стиль Samsung) интерфейс не меняет:
- Manrope — весь интерфейс;
- Cormorant Garamond — словесный знак MAMA.

## Компоненты (`ui/Components.kt`, `ui/Dialogs.kt`)

| Спецификация | В коде |
|---|---|
| MamaPrimaryButton / MamaSecondaryButton | `Kit.primaryButton` / `Kit.secondaryButton` |
| MamaCard | `Kit.card` |
| MamaSelectableCard | `Kit.selectableCard` |
| MamaSectionHeader | `Kit.sectionHeader` |
| MamaProgressIndicator | `Kit.progressIndicator`, `Kit.stepHeader` |
| MamaTimeField | `Kit.timeField` |
| MamaContactCard | `Kit.contactCard` |
| MamaPermissionCard | `Kit.permissionCard` |
| MamaStatusBadge | `Kit.statusBadge` |
| MamaInfoCard | `Kit.infoCard` |
| MamaDigitalTimePicker | `DigitalTimePicker` |

Интерфейс сделан на Android View (без Jetpack Compose): весь проект на
View, а Compose нельзя собрать и проверить в среде разработки.
`Kit(context, night = true)` — вариант для тёмных экранов.

## Пейзажи

`tools/scenery/generate.py` рисует фоны (рассвет, ночь, вечер) локально:
слои гор из фрактального шума, дымка, хвойный лес, озеро с отражением,
зерно. Файлы лежат в `app/src/main/assets/scenes/*.jpg`; их можно заменить
финальными фотографиями с теми же именами — код менять не нужно.

## Экраны

Мастер: Старт → Разрешения → Серия → Время → Доверенный контакт → Личная
причина → Подтверждение. Затем: главный экран (ночь, таймер, нижнее меню:
Главная / Статистика (пока заглушка) / Настройки), экран блокировки,
Доверенный выход, Срыв / Restart, Успех, FLEX (панель, планировщик
периода, итог).

## Время — только цифровое

Нигде нет круглых часов. `DigitalTimePicker`: `[ 23 ] : [ 00 ]`, 24 часа.
Стандартные серии — одно окно на каждый день; даты — только у FLEX.

## Экран блокировки

Тёмный ночной пейзаж, фраза (меняется каждые 10 минут), кольцо прогресса
с таймером, «День N из M». Только две кнопки: «Экстренный звонок» и
«Доверенный выход». Временного экстренного доступа в интерфейсе нет.

## Скриншоты

Каждый push в ветку `claude/*` снимает все экраны на эмуляторе Android 14
(Pixel 6) в режиме превью с примерными данными и публикует их в
pre-release `ui-screenshots` репозитория.
