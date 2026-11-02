# MAMA — Roadmap

> Backup-context. Планы на основе состояния кода и договорённостей сессии.
> Приоритеты и сроки — ориентир, не обязательство. **⚠** — предполагаемое.

## Ближайшее: Фаза 3 — подключить Android к реальному backend

1. `HttpTrustedExitBackend` в `app/.../trustedexit/` — реализация
   `TrustedExitBackend` поверх HTTPS (`HttpURLConnection` + `org.json`, без новых
   зависимостей), общается с backend по контракту `/trusted-exit/request|verify`.
2. Production-readiness: вместо «всегда готов» — реальная проверка `GET /health`
   (и/или `/ready`).
3. Жёсткий TEST/PROD split в `MamaApp.kt`: TEST → `FakeTrustedExitBackend` + TEST
   CODE на экране (как сейчас); PROD → `HttpTrustedExitBackend`, кода на экране нет.
4. `BuildConfig.TRUSTED_EXIT_BASE_URL` (это конфиг, не секрет) для release-сборки.
5. Отдельная безопасная сборка для реального A/B-теста на двух телефонах.

## Деплой и первый реальный SMS

- Развернуть backend на VPS (Docker/Compose/Caddy, `backend/DEPLOY.md`), задать
  `MAMA_SMSRU_API_ID` в server-side env/secret.
- Smoke: отправка одной реальной SMS через backend без Android.
- Затем A/B end-to-end: запрос кода из приложения → SMS контакту → ввод → выход.

## Монетизация

- Подключить реальный платёжный провайдер (Google Play Billing / RuStore) в
  `Billing`/`Payments`, включить `PAYMENTS_ENABLED`. Сейчас — пауза, только
  тест-гранты и внутренний баланс. **⚠** провайдер и сроки не зафиксированы.

## Продукт / UX (из `docs/STATUS.md`, п. «Следующие шаги», и сессии)

- Повторные полевые тесты после настроек телефона (Samsung).
- Статистика, продолжение полировки дизайна в рамках канона V1.
- **⚠** Конкретный бэклог фич за пределами перечисленного в репозитории не
  формализован — уточнять у владельца продукта.

## Принципы при развитии

- Бизнес-логику (Trusted Exit, Series/Restart/FLEX, «Цена слова», cooldown,
  Guardian, блокировку, цены, тестовый safety-net) не менять без явного запроса.
- UI — Views (не Compose).
- Изменения — минимальные изолированные, с зелёным CI; секреты только в env.
