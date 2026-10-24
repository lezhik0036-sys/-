# MAMA Trusted Exit backend — деплой на VPS (Docker)

Отдельный Kotlin/Ktor-сервис, запускается **вне** Android APK. Владеет всем
жизненным циклом кода Trusted Exit; телефон хранит только handle. Разворачивается
в Docker, за HTTPS-прокси. Первый тест — одна реальная SMS через backend, **без
Android и без блокировки**. Android подключается позже через `TRUSTED_EXIT_BASE_URL`.

## 1. Переменные окружения

| Переменная | Обязательна | Назначение |
|---|---|---|
| `MAMA_SMSRU_API_ID` | да | `api_id` из SMS.RU. **Только** в server-side env/secret (файл `.env` на сервере). Никогда в Git, APK, Docker image, Gradle, BuildConfig, Android resources или логах. |
| `MAMA_SMS_SENDER` | нет | Согласованное имя отправителя SMS.RU (`from`). Пусто → отправитель по умолчанию. |
| `MAMA_DOMAIN` | для HTTPS | Домен для авто-TLS Caddy (профиль `tls`). |
| `PORT` | нет (8080) | Порт HTTP внутри контейнера. |

Fail-closed: без `MAMA_SMSRU_API_ID` процесс пишет ошибку конфигурации и выходит
(код 78) — Trusted Exit не стартует, **автоматического отката на mock нет**. Mock
только в тестах.

`.env.example` содержит **только пустые имена** переменных. Реальные значения
живут в `.env`, который создаётся на сервере и в Git не попадает (он в
`.gitignore` и в `.dockerignore`, поэтому в образ тоже не попадает).

## 2. Где взять `api_id` SMS.RU

1. Зарегистрируйся на https://sms.ru/, пополни баланс (SMS платные).
2. Личный кабинет → раздел **API** («Программный API») → скопируй **API ID**.
   Это и есть значение `MAMA_SMSRU_API_ID`.
3. (Опц.) Зарегистрируй имя отправителя → `MAMA_SMS_SENDER`.

`api_id` я у тебя не прошу и нигде не логирую. Ты вставляешь его сам на сервере
(шаг 3.4).

## 3. Пошагово на чистом Ubuntu VPS

### 3.1 Установить Docker
```
sudo apt-get update
sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo tee /etc/apt/keyrings/docker.asc >/dev/null
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

### 3.2 Получить код
```
git clone https://github.com/lezhik0036-sys/-.git mama
cd mama/backend
```

### 3.3 (Для HTTPS) направить домен на VPS
Создай A-запись (и AAAA при IPv6) домена на IP этого VPS. Открой порты 80 и 443.
Без домена можно сделать первый SMS-тест (шаг 4) — он не требует HTTPS.

### 3.4 Создать `.env` и вставить `api_id` — ТОЛЬКО здесь, на сервере
```
cp .env.example .env
chmod 600 .env
nano .env
```
В `.env` впиши:
```
MAMA_SMSRU_API_ID=<твой api_id из SMS.RU>
MAMA_SMS_SENDER=<опц. имя отправителя>
MAMA_DOMAIN=<твой домен для HTTPS, если используешь Caddy>
```
**Это единственное место, куда вставляется ключ.** Файл `.env` не коммить и
никому не пересылай.

### 3.5 Запустить
С HTTPS (Caddy сам получит сертификат Let's Encrypt для `MAMA_DOMAIN`):
```
docker compose --profile tls up -d --build
```
Без домена, только backend (внутренний; для первого SMS-теста этого достаточно):
```
docker compose up -d --build
```

### 3.6 Проверить
Через Caddy (HTTPS):
```
curl https://<MAMA_DOMAIN>/health   # {"status":"ok"}
curl https://<MAMA_DOMAIN>/ready    # {"status":"ready","smsProvider":"sms.ru"}
```
Без домена (с хоста VPS, если раскомментировал `ports` backend на 127.0.0.1):
```
curl http://127.0.0.1:8080/ready
```
`/ready` подтверждает, что SMS-провайдер настроен, и **никогда** не показывает
`api_id`. 503 и `"status":"not_ready"` — провайдер не настроен.

## 4. Первый тест — одна SMS через backend (без Android, без блокировки)

Выполняется тем же образом и тем же `.env`, что и сервер, поэтому проверяет
реальную конфигурацию SMS.RU. Ключ берётся из `.env`, **не** из командной строки:
```
docker compose run --rm --entrypoint java backend -cp "/app/lib/*" app.mama.backend.SmokeKt "+7XXXXXXXXXX"
```
Ожидаем `MAMA smoke: OK …` и приход SMS на указанный телефон. Текст:
`MAMA: код доверенного выхода 12345. Никому не пересылайте его, кроме человека, которому вы помогаете выйти из режима.`

Вывод намеренно минимальный — маскированный номер и `OK`/`FAILED`. `api_id`,
полный номер и 5-значный код не печатаются (код уходит только в сам SMS).
Коды возврата: `0` принято · `1` отклонено провайдером · `64` нет номера ·
`78` нет ключа (fail-closed, ничего не отправлено).

Логи сервиса (`docker compose logs backend`) показывают статусы и
**маскированный** номер, но не код и не `api_id`.

## 5. Только после успешной реальной SMS — Android

Когда шаг 4 даёт `OK` и SMS доходит, подключаем Android (отдельная фаза):
production-сборка MAMA получит `TRUSTED_EXIT_BASE_URL = https://<MAMA_DOMAIN>`,
будет создавать challenge и проверять код через этот backend. Код по-прежнему
генерится/хранится/проверяется только на сервере; телефон правильный код не
видит. Затем — первый A/B end-to-end на двух телефонах.

## 6. Безопасность ключа (сводка)

- `api_id` только в `.env` на сервере (`chmod 600`).
- `.env` — в `.gitignore` и `.dockerignore`: не в Git и не в Docker image.
- Не в APK, Android resources, Gradle, BuildConfig.
- Не печатается в логах backend, Caddy или smoke; `/ready` его не раскрывает.
- Образ собирается без значения ключа; ключ подаётся только в рантайме.

## 7. Обновление и обслуживание
```
git pull
docker compose --profile tls up -d --build   # пересобрать и перезапустить
docker compose logs -f backend                # логи
docker compose down                           # остановить
```
