# Конфигурация

Параметры задаются в `application.properties` (внутри jar; свой файл `./config/application.properties` или `--spring.config.location`
переопределяют его), системными свойствами `-Dsectoriadb.…`, аргументами `--sectoriadb.…=…` или переменными окружения.
Правило переменных окружения (relaxed binding Spring Boot): точки и дефисы заменяются на `_`, буквы заглавные —
`sectoriadb.cache.max-resident-tables` → `SECTORIADB_CACHE_MAX_RESIDENT_TABLES`. Все изменения применяются после перезапуска.

Значения времени — `Duration` (`500ms`, `30s`, `15m`, `6h`, `7d`, `PT15M`), размеры — `DataSize` (`5MB` = 5 МиБ). Колонка «По умолчанию»
показывает значение из поставляемого `application.properties`; без него действуют значения из кода (отличается только `sectoriadb.gc.enabled`:
в коде `false`, в поставке `true`).

Таблица ниже — полный список свойств `sectoriadb.*`; её соответствие коду проверяет `DocsConsistencyTest` (свойство есть в коде тогда и только тогда,
когда оно есть здесь).

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `sectoriadb.meta-dir` | `./sectoriadb-meta` | каталог метаданных: `sectoria.db`, `credentials.json`, `oplogs.jsonl`, `sectoriadb.log`. В Docker `/data/meta` |
| `sectoriadb.data-dir` | `./sectoriadb-data` | каталог блобов: `<data-dir>/<бакет>/blob_*.raw`, `small_*.sob`. В Docker `/data/storage` |
| `sectoriadb.upload-tmp-dir` | пусто (`<data-dir>/.tmp`) | куда тело загрузки спулится до записи в блобы; оставшееся после сбоя удаляется при старте. Рассчитайте на самые большие параллельные загрузки (до 5 ГиБ каждая) |
| `sectoriadb.default-chunk-size` | `16384` | размер чанка, байт; он же порог «малого» объекта (меньше — запись в `.sob`). Один размер на пул |
| `sectoriadb.default-num-buckets` | `65536` | корзин в каждой из двух таблиц нового кукушкина блоба (по умолчанию 8 ГиБ данных, ≈9 МиБ памяти на таблицу) |
| `sectoriadb.max-evictions` | `32` | длина пути вытеснений при вставке; нет пути — `TableFullException` |
| `sectoriadb.fsync` | `true` | `fsync` данных и метаданных; отключать только в бенчмарках и тестах |
| `sectoriadb.reconcile-on-start` | `true` | при старте удалить неучтённые `blob_*.raw` (остатки сбоя расширения); в разовом CLI-режиме не выполняется |
| `sectoriadb.metastore.lazy` | `false` | открывать метахранилище при первом обращении; выставляется разовым CLI-режимом сам, вручную не нужно |
| `sectoriadb.metastore.recovery-backoff` | `1s` | пауза между попытками восстановить запись после сбоя коммита (пока диск не работает, запись отвечает `503 SlowDown`/`507`, чтение идёт) |
| `sectoriadb.metastore.page-cache-mb` | `64` | память кучи под проверенные страницы метаданных, МиБ; попадание не читает файл и не пересчитывает CRC32C (`0` — без кэша). Входит в `-Xmx` |
| `sectoriadb.metastore.group.max-batch-size` | `256` | тел записи в одном групповом коммите |
| `sectoriadb.metastore.group.max-batch-pages` | `1024` | страниц, записываемых одним групповым коммитом |
| `sectoriadb.metastore.group.max-wait-micros` | `0` | сколько лидер пачки ждёт новых тел (0 — не ждать, пачка = всё, что накопилось за предыдущий коммит) |
| `sectoriadb.metastore.group.queue-capacity` | `4096` | размер очереди группового коммита; при заполнении запись блокируется |
| `sectoriadb.cache.max-resident-tables` | `64` | таблиц кукушкиных блобов в памяти (≈9 МиБ каждая); остальные вытесняются по LRU, используемая таблица не закрывается (предел мягкий) |
| `sectoriadb.cache.max-resident-table-bytes` | `0` | дополнительный бюджет памяти резидентных таблиц, байт (0 — только счётчик) |
| `sectoriadb.cache.max-open-small-blobs` | `256` | одновременно открытых файлов малых объектов |
| `sectoriadb.small-object.max-file-bytes` | `1073741824` | размер `small_*.sob`, после которого пул открывает новый файл |
| `sectoriadb.small-object.checkpoint-interval-bytes` | `16777216` | через сколько дозаписанных байт сдвигается контрольная точка восстановления в заголовке `.sob` |
| `sectoriadb.pool.initial-blobs` | `1` | кукушкиных блобов, создаваемых при первом чанковом объекте пула (больше — параллельнее вставки, больше памяти) |
| `sectoriadb.pool.max-blobs` | `16` | предел блобов в пуле (замороженный на `resize` блоб не считается) |
| `sectoriadb.pool.grow-threshold-percent` | `75` | при таком суммарном заполнении блобов пула (и когда ни один блоб не принял чанк) добавляется блоб |
| `sectoriadb.pool.location-cache-entries` | `65536` | LRU подсказок «чанк → блоб» (≈100 байт на запись); `0` выключает |
| `sectoriadb.auto-resize.enabled` | `true` | фоновая проверка роста пулов (добавляет блоб по порогу) |
| `sectoriadb.auto-resize.check-interval-ms` | `60000` | период этой проверки, мс |
| `sectoriadb.gc.enabled` | `true` | фоновый сборщик мусора; команды `gc …` работают всегда. В разовом CLI-режиме отключён |
| `sectoriadb.gc.grace` | `15m` | период ожидания G: мусор моложе не трогается |
| `sectoriadb.gc.interval` | `1m` | пауза между проходами (очередь чанков, сироты, надгробия) |
| `sectoriadb.gc.max-chunks-per-run` | `10000` | не более стольких слотов освобождается за проход; если предел достигнут, а чанков к освобождению больше, планировщик сразу запускает следующий проход (до 20 подряд), так что предел — это размер прохода, а не потолок скорости. Просроченные надгробия предела не имеют |
| `sectoriadb.gc.batch-size` | `16` | чанков на одну эксклюзивную транзакцию метахранилища (`1` — «одна транзакция на чанк») |
| `sectoriadb.gc.sweep-interval` | `6h` | пауза между обходами блобов в поиске копий оборванных загрузок |
| `sectoriadb.gc.sweep-gate-wait` | `2s` | сколько обход ждёт, пока загрузки пула дойдут до коммита |
| `sectoriadb.gc.upload-ticket-ttl` | `30m` | возраст удержания загрузки, после которого обход может его отозвать (поздний коммит получит `503`) |
| `sectoriadb.gc.compact-interval` | `1m` | пауза между проверками файлов малых объектов на компакцию (проверка читает счётчики файлов в памяти; цель дозаписи, которая сама в основном мертва и заполнена хотя бы на четверть, заменяется новым файлом и уплотняется) |
| `sectoriadb.gc.small-compact-dead-percent` | `50` | компакция, когда столько процентов байт записей мертво… |
| `sectoriadb.gc.small-compact-min-dead-bytes` | `1048576` | …и мёртвых байт не меньше этого |
| `sectoriadb.s3.region` | `us-east-1` | регион сервера (подпись, `GetBucketLocation`) |
| `sectoriadb.s3.access-key` | не задан | устаревшая пара ключа из окружения (`SECTORIADB_S3_ACCESS_KEY`), применяется, если не задан `credentials` |
| `sectoriadb.s3.secret-key` | не задан | секрет к `access-key` (не короче 16 символов) |
| `sectoriadb.secrets.key` | не задан | мастер-ключ секретов в `credentials.json`: Base64 от 32 случайных байт (`openssl rand -base64 32`), переменная `SECTORIADB_SECRETS_KEY`. Задан — секреты хранятся зашифрованными AES-256-GCM, открытые секреты прежнего файла шифруются при первом старте. Файл с зашифрованными секретами без ключа или с другим ключом — сервер не стартует. Хранить отдельно от тома и его копий |
| `sectoriadb.s3.credentials` | не задан | ключи через запятую `ACCESS:SECRET[|описание]`. Секрет не должен содержать `:`, `,` и `|`; прежняя форма `ACCESS:SECRET:описание` не принимается (запись отклоняется с сообщением, секрет не усекается и не удлиняется молча). Для перечисленных ключей окружение — источник истины (сменили секрет — применится при старте). Пара `access-key`/`secret-key` проверяется теми же правилами (ключ — 16–128 букв и цифр, секрет — от 16 символов). Описание на уровне INFO не пишется |
| `sectoriadb.s3.auth.enabled` | `true` | проверка SigV4; `false` — только для разработки |
| `sectoriadb.s3.auth.allow-open-setup` | `false` | при включённой авторизации и отсутствии ключей пропускать все запросы (иначе `403`) |
| `sectoriadb.s3.auth.lockout.max-failures` | `20` | неудачных подписей (неверный секрет, неизвестный или выключенный ключ) с одного адреса за `window`, после которых адрес блокируется: подписанные запросы получают `503 SlowDown` с `Retry-After`, не проверяясь (`0` — выключено). Адрес — клиент за доверенным прокси: без `server.tomcat.remoteip.internal-proxies` все клиенты видны адресом nginx и блокируются вместе |
| `sectoriadb.s3.auth.lockout.window` | `1m` | окно подсчёта неудачных подписей |
| `sectoriadb.s3.auth.lockout.duration` | `5m` | сколько длится блокировка адреса |
| `sectoriadb.s3.auth.allow-unsigned-payload` | `true` | принимать `UNSIGNED-PAYLOAD` и `STREAMING-UNSIGNED-PAYLOAD-TRAILER`; `false` — требовать подписанное тело |
| `sectoriadb.s3.auth.enforce-region` | `true` | регион в `Credential` должен совпадать с `sectoriadb.s3.region` |
| `sectoriadb.s3.auth.require-signed-headers` | `true` | `host` и все `x-amz-*` обязаны входить в `SignedHeaders` |
| `sectoriadb.s3.require-secure-transport` | `false` | `true` — запрос не по HTTPS получает `403 InsecureTransport` ещё до проверки подписи. Схема берётся из `X-Forwarded-Proto` TLS-прокси; ставьте `true` за прокси ([operations.md](operations.md#tls-через-nginx)). Это же значение видит условие `aws:SecureTransport` политик бакета |
| `sectoriadb.s3.limits.min-body-bytes-per-second` | `1024` | минимальная скорость тела запроса в скользящем окне `min-rate-window` (не средняя с начала: быстрый всплеск не покупает время простоя); медленнее — `400 RequestTimeout`; `0` выключает |
| `sectoriadb.s3.limits.min-body-rate-grace` | `30s` | время разгона до начала проверки скорости |
| `sectoriadb.s3.limits.min-response-bytes-per-second` | `1024` | минимальная скорость, с которой клиент принимает тело ответа `GET` (окно `min-rate-window`); медленнее — соединение закрывается, поток освобождается; `0` выключает |
| `sectoriadb.s3.limits.min-response-rate-grace` | `30s` | время разгона для проверки скорости ответа |
| `sectoriadb.s3.limits.min-rate-window` | `30s` | окно, по которому считаются обе минимальные скорости |
| `sectoriadb.s3.limits.max-inflight-writes-per-key` | `32` | одновременных `PUT`/`POST` на ключ доступа (сверх — `503 SlowDown`); `0` выключает |
| `sectoriadb.s3.limits.max-inflight-reads-per-key` | `64` | одновременных `GET`/`HEAD`/`DELETE` на ключ доступа. Один ключ занимает не больше 96 из 200 потоков Tomcat (`server.tomcat.threads.max`); сумму по всем ключам держите ниже пула |
| `sectoriadb.s3.limits.max-inflight-anonymous` | `16` | одновременных анонимных запросов (публичные объекты и бакеты) на весь сервер, общий бюджет; `0` выключает |
| `sectoriadb.s3.limits.upload-free-space-reserve-bytes` | `67108864` | запас свободного места на томе спула; рабочий порог — большее из него и `sectoriadb.storage.min-free-bytes`. Заявленные размеры идущих загрузок и копий суммируются |
| `sectoriadb.storage.min-free-bytes` | `1073741824` | порог свободного места (1 ГиБ): запись, после которой на томе спула, метаданных или данных осталось бы меньше (с учётом уже принятых загрузок), получает `507 InsufficientStorage` (`503 SlowDown`, если место занято другими загрузками). Защищает metastore: при полном томе не проходят даже удаления. Чтение и `DELETE` порогом не ограничиваются |
| `sectoriadb.s3.limits.upload-heap-fraction` | `0.25` | доля максимальной кучи JVM для промежуточных структур идущих загрузок (оценка 256 байт на чанк: около 80 МБ на 5 ГиБ уникальных данных при чанке 16 КиБ). Больше — новая загрузка получает `503 SlowDown` (первая принимается всегда); `0` выключает. Защита от `OutOfMemoryError` при множестве параллельных больших загрузок |
| `sectoriadb.multipart.stale-after` | `7d` | брошенная загрузка без новых частей отменяется |
| `sectoriadb.multipart.completed-retention` | `24h` | сколько хранится запись о завершённой/отменённой загрузке (повтор `Complete` получает прежний ответ) |
| `sectoriadb.multipart.min-part-size` | `5MB` | каждая часть, кроме последней, не меньше (`400 EntityTooSmall` при `Complete`); `0` выключает |
| `sectoriadb.multipart.cleanup-interval-ms` | `3600000` | период очистки загрузок, мс |
| `sectoriadb.observability.slow-request-ms` | `2000` | запросы не короче пишутся в лог на WARN и в `sectoriadb_s3_requests_slow_total`; `0` выключает |
| `sectoriadb.observability.gauge-cache-ms` | `15000` | как долго кэшируются gauge состояния между скрейпами |
| `sectoriadb.observability.tree-pages-interval-ms` | `600000` | как часто пересчитываются страницы деревьев метаданных (`sectoriadb_metastore_tree_pages`); обход читает весь `sectoria.db` и держит одну читающую транзакцию, `0` — выключено |
| `sectoriadb.observability.tree-pages-initial-delay-ms` | `60000` | задержка первого пересчёта страниц деревьев после старта |

## Свойства Spring, которые задаёт поставка

Это не `sectoriadb.*`, но они влияют на поведение (значения из `application.properties`).

| Свойство | Значение | Смысл |
|---|---|---|
| `server.port` | `8080` | порт S3 API (`SERVER_PORT`) |
| `management.server.port` | `9464` | отдельный порт `/actuator/health` и `/actuator/prometheus`, без подписи (`MANAGEMENT_SERVER_PORT`) |
| `management.server.address` | не задан | привязать порт метрик к интерфейсу (`127.0.0.1`) |
| `management.metrics.tags.application` | `SectoriaDB` | общая метка метрик |
| `management.metrics.distribution.slo.jvm.gc.pause` | `1ms…5s` | границы гистограммы пауз GC |
| `server.forward-headers-strategy` | `native` | схему и адрес клиента берёт Tomcat (`RemoteIpValve`) из `X-Forwarded-Proto`/`X-Forwarded-For`, **только** от пира из `server.tomcat.remoteip.internal-proxies` (по умолчанию loopback и частные сети), от остальных заголовки игнорируются |
| `server.tomcat.remoteip.internal-proxies` | не задан (частные сети) | регулярное выражение адресов, которым доверяются `X-Forwarded-*`; сузьте до адреса прокси, если порт `8080` достижим кому-то ещё из частной сети |
| `server.tomcat.connection-timeout` | `60s` | таймаут чтения/записи сокета |
| `server.tomcat.keep-alive-timeout` | `30s` | простой keep-alive |
| `server.tomcat.max-keep-alive-requests` | `1000` | запросов на соединение |
| `server.tomcat.threads.max` | `200` | рабочих потоков |
| `server.tomcat.max-connections` | `2048` | предел соединений |
| `server.tomcat.accept-count` | `200` | очередь ожидающих соединений |
| `server.tomcat.max-swallow-size` | `32MB` | сколько тела дочитывается и отбрасывается у запроса, отклонённого до чтения тела (слишком большой XML, отказ в загрузке), чтобы клиент получил ошибку S3, а не обрыв соединения; Tomcat 11 при большем остатке сразу закрывает соединение (у Tomcat 10 хватало 1 МБ). Тело больше обрывается |
| `server.shutdown`, `spring.lifecycle.timeout-per-shutdown-phase` | `graceful`, `30s` | дать закончиться текущим запросам при остановке |
| `spring.task.scheduling.pool.size` | `4` | потоки планировщика (GC, рост пулов, очистка multipart не ждут друг друга) |
| `spring.shell.interactive.enabled` | `true` (в образе `false`) | интерактивная консоль |
| `spring.profiles.active` | не задан | `debug` включает подробные логи с бакетами и ключами |
| `logging.level.org.example.sectoriadb` | `INFO` | уровень логов приложения |

## Переменные окружения Docker

`docker-compose.yml` пробрасывает (значения по умолчанию в скобках): `HTTPS_PORT` (443) и `HTTP_PORT` (80) — порты nginx на хосте; `HOST_PORT` (8080, только в `docker-compose.dev.yml`, на loopback), `SERVER_PORT`, `METRICS_HOST_PORT` (9464, только на loopback),
`SECTORIADB_S3_REQUIRE_SECURE_TRANSPORT` (`true` в compose, `false` в dev-файле),
`MANAGEMENT_SERVER_PORT`, `SECTORIADB_S3_REGION`, `SECTORIADB_S3_AUTH_ENABLED`, `SECTORIADB_S3_AUTH_ALLOW_OPEN_SETUP`,
`SECTORIADB_S3_AUTH_ALLOW_UNSIGNED_PAYLOAD`, `SECTORIADB_S3_ACCESS_KEY`, `SECTORIADB_S3_SECRET_KEY`, `SECTORIADB_S3_CREDENTIALS`,
`SECTORIADB_CACHE_MAX_RESIDENT_TABLES`, `JAVA_OPTS` (`-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC`),
`SPRING_SHELL_INTERACTIVE_ENABLED`. Любое другое свойство можно передать тем же правилом через `environment:` в compose.
Образ задаёт `SECTORIADB_META_DIR=/data/meta` и `SECTORIADB_DATA_DIR=/data/storage`. Шаблон — [.env.example](../../.env.example).

## Память

Каждая резидентная таблица кукушкина блоба занимает ≈9 МиБ кучи (при геометрии по умолчанию), поэтому `max-resident-tables=64` — около 576 МиБ.
Дайте контейнеру не меньше 1,5 ГиБ или уменьшите этот предел. Куча = 70 % лимита контейнера (`MaxRAMPercentage`); `OutOfMemoryError` завершает
процесс, чтобы политика перезапуска подняла его чистым.
