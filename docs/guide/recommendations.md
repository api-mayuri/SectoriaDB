# Рекомендации по использованию

Практическое руководство: подходит ли SectoriaDB под вашу задачу и что сделать перед тем, как положить в неё данные, которые жалко потерять.
Все утверждения сверены с кодом и свойствами; ссылки ведут к подробностям.

## Когда SectoriaDB подходит и когда нет

**Подходит**
- Один узел: домашний сервер, небольшая команда, стенд, резервные копии, медиа-архив, хранилище для приложений, которым нужен S3 API (path-style, SigV4).
- Много мелких объектов (меньше 16 КиБ хранятся целиком в журнале без накладных расходов слота) и повторяющиеся данные (дедупликация по бакету).
- Нужна проверяемая целостность: CRC32C на каждом чтении, `verify-all`, `x-amz-checksum-*`.

**Не подходит (пока)**
- Нужна отказоустойчивость узла: **репликации нет**, потеря диска — потеря данных; защита — только резервные копии.
- Нужны пользователи и права: **IAM нет**, любой действующий ключ видит и меняет всё; политики бакета и ACL действуют только на анонимных ([security.md](../architecture/security.md)).
- Нужны версионирование, блокировки объектов, CORS, lifecycle, шифрование на стороне сервера, POST-форма, S3 Select — не реализованы ([s3-usage.md](s3-usage.md)).
- Нужна масштабируемая запись: один писатель метаданных, потолок — коммиты в секунду вашего диска ([sandbox-results.md](../reports/sandbox-results.md), на песочнице порядка тысячи мелких `PUT`/с).
- Хостинг для недоверенных арендаторов: ключи не изолированы друг от друга.

## Чек-лист перед боевым использованием

1. **TLS-прокси впереди.** Сервер не терминирует TLS; всегда ставьте перед ним обратный прокси с HTTPS (пример ниже). Порт `8080` наружу не публикуйте — только прокси.
2. **Порт управления `9464` никогда не публиковать наружу** (метрики и health без аутентификации). В compose он уже на `127.0.0.1`; на голом jar привяжите `management.server.address=127.0.0.1`.
3. **Ключи** — через `mk-key` или `SECTORIADB_S3_CREDENTIALS`; **не запускайте с `sectoriadb.s3.auth.allow-open-setup=true`** и не отключайте `sectoriadb.s3.auth.enabled`. Один ключ — одному приложению. s3manager из compose (`8081`) работает с полными правами: не публикуйте или не запускайте на проде.
4. **`sectoriadb.fsync=true`** (по умолчанию). Отключение даёт скорость ценой потери подтверждённых записей при сбое питания — только для тестов.
5. **`meta-dir` и `data-dir` на одной файловой системе (одном томе)** и этот том зашифрован (LUKS, шифрованный диск облака): иначе нельзя сделать атомарный снимок, согласованный по обеим частям, а шифрования на уровне приложения нет.
   Сейчас `docker-compose.yml` использует два именованных тома — для боевого развёртывания замените их двумя подкаталогами одного монтирования, например:
   `- /srv/sectoriadb/meta:/data/meta` и `- /srv/sectoriadb/storage:/data/storage` (оба внутри одной ФС `/srv`), либо задайте `SECTORIADB_META_DIR`/`SECTORIADB_DATA_DIR` внутри одного тома.
6. **Резервные копии** — атомарный снимок этого тома (LVM, ZFS, Btrfs, снимок облачного диска) плюс проверка восстановления (см. ниже).
7. **Память**: куча под `cache.max-resident-tables` (см. ниже); `restart: unless-stopped` и `ExitOnOutOfMemoryError` уже в образе.
8. **Запас диска** (см. ниже) и алерты (см. ниже).
9. Часы сервера и клиентов синхронизированы (NTP): допуск подписи ±15 минут.

## Обратный прокси (nginx)

Требования к прокси, вытекающие из устройства сервера:
- **`Host` передавать как пришёл** (`$http_host`): SigV4 подписывает `host`, любое изменение — `SignatureDoesNotMatch`.
- **Путь не переписывать и не нормализовать**: ключ `a//b` или `../x` — обычные ключи, подписанный канонический URI должен дойти без изменений. Поэтому `proxy_pass` без URI-части и `merge_slashes off`.
- Размер тела — до 5 ГиБ на запрос (`PutObject`/`UploadPart`): `client_max_body_size 5g`.
- Не буферизовать запрос (`proxy_request_buffering off`: иначе тело ляжет на диск прокси, а сервер увидит его только после полной загрузки) и ответ (`proxy_buffering off`: потоковая отдача больших `GET`).
- Длинные таймауты: загрузки и скачивания идут минутами. Сервер сам отсекает медленные тела (меньше 1 КиБ/с после 30 с — `400 RequestTimeout`) и держит `connection-timeout=60s`.
- HTTP/1.1 к бэкенду, без заголовка `Connection: close`.

```nginx
server {
    listen 443 ssl http2;
    server_name s3.example.com;

    ssl_certificate     /etc/ssl/s3.example.com/fullchain.pem;
    ssl_certificate_key /etc/ssl/s3.example.com/privkey.pem;

    client_max_body_size 5g;
    merge_slashes off;                 # ключи объектов могут содержать "//"

    location / {
        proxy_pass http://127.0.0.1:8080;      # без URI-части: путь уходит как есть
        proxy_http_version 1.1;
        proxy_set_header Host $http_host;       # подпись SigV4 покрывает Host
        proxy_set_header Connection "";
        proxy_request_buffering off;            # тело сразу к серверу
        proxy_buffering off;                    # потоковая отдача GET
        proxy_connect_timeout 10s;
        proxy_send_timeout    3600s;
        proxy_read_timeout    3600s;
        send_timeout          3600s;
    }
}
```

Адрес бэкенда `127.0.0.1:8080` — для прокси на том же хосте (в compose тогда опубликуйте порт только на loopback: `ports: ["127.0.0.1:8080:8080"]`). Этот блок станет готовым сервисом compose на следующем этапе;
пока он приведён как образец и конфигурацией проекта не проверялся — проверьте `aws s3 cp` большого файла, `ls` ключа с `//` и presigned-ссылку через ваш прокси.

## Резервные копии и проверка

- Хранилище **консистентно при аварийной остановке по построению** (данные до метаданных, атомарный коммит одной мета-страницы; проверено `kill -9` во время `PUT` и multipart и тестами потери несброшенных записей — [fault-injection.md](../testing/fault-injection.md)).
  Поэтому атомарный снимок тома эквивалентен аварийному выключению питания и откатывается сам при старте. Копирование каталогов работающего сервера «по очереди» снимком не является.
- Регулярно: снимок тома → поднять копию на отдельном экземпляре → остановить → `verify-all` (OFFLINE, [admin-shell.md](admin-shell.md)); успех — последняя строка `RESULT: OK`. Копия, не прошедшая проверку, не копия.
- `credentials.json` (секреты открытым текстом) лежит в том же томе — храните снимки так же защищённо, как ключи.
- Журнал и `.tmp` восстанавливать не нужно.

## Память и JVM

Каждая резидентная таблица кукушкиного блоба — около 9 МиБ кучи при настройках по умолчанию; `sectoriadb.cache.max-resident-tables` (64) — около 576 МиБ, предел мягкий (используемые таблицы не закрываются).
Куча в образе — 70 % лимита контейнера. Правило: лимит контейнера ≥ 1,5 ГиБ при значении по умолчанию; для малого контейнера уменьшайте `SECTORIADB_CACHE_MAX_RESIDENT_TABLES` (каждый бакет с чанковыми объектами — минимум одна таблица, а пул растёт блобами до `pool.max-blobs`=16).
Следите за `sectoriadb_cuckoo_tables_loaded / sectoriadb_cuckoo_tables_max` и паузами GC; частые вытеснения (`sectoriadb_cuckoo_tables_evicted_total`) значат, что активных блобов больше лимита и чтение «холодных» страдает от загрузки таблицы.

## Запас места на диске

- Тома: данные (`blob_*.raw` — разрежённые, растут по мере записи; новые блобы добавляются при заполнении 75 %), метаданные (`sectoria.db` растёт и не усекается), спул загрузок (`<data-dir>/.tmp`: нужно место под самые большие параллельные загрузки, до 5 ГиБ каждая).
- Загрузка принимается только если помещается в спул вместе с запасом 64 МиБ (`upload-free-space-reserve-bytes`), иначе `503 SlowDown`.
- Заполнился диск — запись отвечает `507 InsufficientStorage`, чтение работает, после освобождения места запись возобновляется без перезапуска ([operations.md](operations.md)). Не доводите до этого: держите **свободными не менее 15–20 %** (алерт `SectoriaDiskFreeLow` срабатывает на 15 %).
- Удаление возвращает место не сразу и не уменьшает `blob_*.raw` ([garbage-collection.md](../architecture/garbage-collection.md)): планируйте ёмкость по пику.

## Сборка мусора и долгие GET

Период ожидания `sectoriadb.gc.grace` (15 минут): читатель, держащий старую версию объекта дольше этого срока (скачивание очень большого файла медленным клиентом, пока объект перезаписывают или удаляют), может получить обрыв.
Если у вас такие сценарии — увеличьте `grace` (место вернётся позже). Загрузка, не уложившаяся в `grace` после обнаружения повторяющегося чанка, получает `503` с `Retry-After` — клиент должен повторять запрос (все SDK и `aws cli` повторяют `503`).
Не держите `gc.enabled=false` на постоянной основе: место не вернётся.

## Multipart

Каждая часть, кроме последней, не меньше 5 МиБ (`sectoriadb.multipart.min-part-size`), иначе `Complete` отвечает `400 EntityTooSmall`; размер проверяется при `Complete`, а не при `UploadPart`. Частей не больше 10000, один запрос — не более 5 ГиБ.
`aws cli`/SDK/rclone по умолчанию режут по 5–8 МиБ и больше — совместимо. Брошенные загрузки занимают место до `stale-after` (7 суток); смотрите `aws s3api list-multipart-uploads`.

## Мониторинг: какие алерты включить

Правила — `bench/observability/prometheus/alerts.yml` ([monitoring.md](monitoring.md)); для боя нужны минимум эти (подберите пороги по своему железу):

| Алерт | Что делать при срабатывании |
|---|---|
| `SectoriaIntegrityFailure`, `SectoriaQuarantinedSlots` (critical) | остановить запись в затронутый бакет, `verify-all` и `scrub --id` на остановленном сервере, восстановить затронутые объекты из копии |
| `SectoriaTableFull`, `SectoriaFillHigh`, `SectoriaFillCritical` | поднять `sectoriadb.pool.max-blobs` (память!) или проверить рост пула; освободить данные |
| `SectoriaDown`, `SectoriaServerErrors` | логи (`requestId` из ответа), здоровье диска, `docker compose ps`, `/actuator/health` |
| `SectoriaDiskFreeLow`, `SectoriaDiskFullSoon` | расширить том или удалить данные; помнить про период ожидания GC |
| `SectoriaMetastoreRecoveryFailed` | диск метаданных не отвечает или полон; чтение работает, запись вернётся сама после исправления |
| `SectoriaGcErrors`, `SectoriaGcStale`, `SectoriaResizeFailed` | `gc status`, логи; убедиться, что `gc.enabled=true` |
| `SectoriaFsyncSlow`, `SectoriaPutLatencyHigh` | деградация диска; сравнить с базой измерений на вашем железе |
| `SectoriaAuthFailureBurst`, `SectoriaClockSkew` | подбор ключей или сбитые часы клиентов |

Alertmanager в стенде нет — подключите свой.

## Обновления

Формат данных на диске между этапами разработки **несовместим**, миграторов нет ([CHANGELOG](../../CHANGELOG.md)). Политика: поднимите **новый экземпляр** на новой версии с пустыми томами и скопируйте данные через S3:
`aws s3 sync s3://bucket s3://bucket --endpoint-url …` между двумя endpoint'ами (через промежуточный каталог) или `rclone sync old:bucket new:bucket`; затем проверка `verify-all` на новом экземпляре и переключение клиентов.
ACL, политики бакетов и ключи доступа не копируются синхронизацией данных — воспроизведите их (ключи через `mk-key`/окружение). Незавершённые multipart-загрузки не переносятся. Перед переключением оставьте старый экземпляр (и его снимок) нетронутым.

## Настройки клиентов

Всегда **path-style** (`http(s)://host/bucket/key`; virtual-hosted не поддерживается) и регион, равный `sectoriadb.s3.region` (`us-east-1` по умолчанию; иначе `AuthorizationHeaderMalformed`).
Проверялись набором совместимости ([compatibility.md](../testing/compatibility.md)): `aws cli`, boto3 (ceph/s3-tests), `s3cmd`, `mc`, AWS SDK Go/PHP/Ruby и minio-SDK (часть наборов mint завершается с известными отказами). `rclone` в наборы не входит — настройка ниже стандартная, проверьте её на вашем экземпляре.

`aws cli` (`~/.aws/config`):

```ini
[profile sectoria]
region = us-east-1
s3 =
    addressing_style = path
    multipart_threshold = 64MB
    multipart_chunksize = 16MB
endpoint_url = https://s3.example.com
```
```ini
# ~/.aws/credentials
[sectoria]
aws_access_key_id = ВАШ_КЛЮЧ
aws_secret_access_key = ВАШ_СЕКРЕТ
```
```bash
aws --profile sectoria s3 cp big.iso s3://bucket/ --checksum-algorithm SHA256       # сервер сверит сумму
aws --profile sectoria s3api head-object --bucket bucket --key big.iso --checksum-mode ENABLED
```
Директива `endpoint_url` в профиле требует AWS CLI v2 (v1: ключ `--endpoint-url`). Если клиент по умолчанию отправляет неподдерживаемую сумму/режим — задайте
`request_checksum_calculation = when_required` (новые версии CLI/SDK) — сервер принимает CRC32/CRC32C/CRC64NVME/SHA1/SHA256.

`rclone` (`rclone.conf`):

```ini
[sectoria]
type = s3
provider = Other
access_key_id = ВАШ_КЛЮЧ
secret_access_key = ВАШ_СЕКРЕТ
endpoint = https://s3.example.com
region = us-east-1
force_path_style = true
chunk_size = 16M
```

`s3cmd` (`~/.s3cfg`):

```ini
access_key = ВАШ_КЛЮЧ
secret_key = ВАШ_СЕКРЕТ
host_base = s3.example.com
host_bucket = s3.example.com
use_https = True
bucket_location = us-east-1
signature_v2 = False
```

Для проверки целостности на стороне клиента включайте `--checksum-algorithm` (CLI/SDK) и читайте с `x-amz-checksum-mode: ENABLED`; клиент, не сверяющий длину ответа, может принять обрыв повреждённого `GET` за конец файла.
