# Консоль администратора

Консоль — это Spring Shell внутри самого приложения. Команды запускаются тремя способами; от способа зависит, можно ли
работать рядом с запущенным сервером. Причина ограничений одна: файл метаданных `sectoria.db` открывает **один процесс**
(блокировка `FileLock`), а ключи доступа лежат отдельно в `credentials.json`.

| Пометка | Что значит |
|---|---|
| **ONLINE** | разовый запуск `java -jar sectoriadb.jar <команда>` работает рядом с запущенным сервером: метахранилище не открывается |
| **OFFLINE-ONLY** | разовый запуск работает, **только если сервер остановлен** (иначе сразу `Error: ... needs exclusive access to the metadata store` и код 2) |
| **INTERACTIVE** | команда есть только в интерактивной консоли самого сервера; как разовая она не запускается (приложение стартовало бы как веб-сервер) |

Интерактивная консоль работает в процессе сервера, поэтому в ней доступны **все** команды, в том числе OFFLINE-ONLY, пока сервер работает.

## Как запустить

```bash
# ONLINE-команды в Docker: в работающем контейнере
X="docker compose exec -T sectoriadb java -jar /app/sectoriadb.jar"
$X mk-key --count 3 --desc "для бэкапов"

# OFFLINE-ONLY в Docker: сервер остановлен, команда запускается в новом контейнере на тех же томах
# (ENTRYPOINT образа запускает сервер, поэтому его переопределяют)
docker compose stop sectoriadb
docker compose run --rm --no-deps --entrypoint java sectoriadb -jar /app/sectoriadb.jar verify-all
docker compose start sectoriadb

# интерактивная консоль сервера: compose включает stdin_open и tty, SPRING_SHELL_INTERACTIVE_ENABLED=true
docker attach sectoriadb          # отсоединиться без остановки: Ctrl+P, Ctrl+Q (Ctrl+C остановит сервер)

# без Docker
java -jar sectoriadb-server/target/SectoriaDB-1.0-SNAPSHOT.jar            # сервер + интерактивная консоль, help
java -Dspring.shell.interactive.enabled=false -jar ...                    # сервер без консоли
java -jar ... verify-all --sectoriadb.meta-dir=/data/meta                 # разовая команда (OFFLINE-ONLY)
```

В образе `Dockerfile` по умолчанию `SPRING_SHELL_INTERACTIVE_ENABLED=false`; `docker-compose.yml` включает консоль. В режиме разовой
команды отключены веб-сервер, фоновые задания (GC, рост пулов) и очистка временных файлов, метахранилище открывается лениво,
уровень логов `WARN`. Ошибка команды печатается как `Error: ...` с кодом выхода 1.

## Ключи доступа (ONLINE)

| Команда | Что делает |
|---|---|
| `mk-key [--count N] [--desc ТЕКСТ] [--access-key AK --secret-key SK]` | создать 1–100 ключей; свои значения — только при `--count 1`; секрет не короче 16 символов |
| `keys [--secret]` | список ключей (секреты только с `--secret`) |
| `rm-key --id ID1,ID2 [--yes]` | удалить ключи (без `--yes` только подсказка) |
| `enable-key --id ID` / `disable-key --id ID` | включить / временно выключить без перезапуска |
| `key-policy --id ID --file policy.json` | подключить IAM-политику ключа (заменяет прежнюю) |
| `key-policy --id ID --show` / `key-policy --id ID --remove` | показать политику / снять её (ключ снова с полным доступом) |

`key-policy --file` проверяет документ до записи: корень — объект, `Version` `2012-10-17`, `Statement` — объект или непустой массив; `Effect` `Allow`/`Deny`; `Action` — строки `s3:…` (или `*`); `Resource` — ARN `arn:aws:s3:::…` (или `*`);
`NotAction`/`NotResource` только в `Deny`; нет `Principal`, переменных `${…}` и неподдерживаемых условий (разрешены `Bool aws:SecureTransport`, `StringEquals`/`StringLike` на `s3:prefix`, `s3:delimiter`). Ошибки перечисляются `Error: Invalid policy: …`
и ничего не записывают; неизвестные ключи верхнего уровня и отсутствие `Version` — предупреждения (`Warning: …`), политика сохраняется. Список `keys` показывает колонку `Policy` (`yes`/`none`). Примеры — [deploy/iam](../../deploy/iam/), правила — [s3-usage.md](s3-usage.md#iam-политики-ключей).

Работают рядом с сервером: сервер подхватывает изменения `credentials.json` по времени изменения и размеру файла (политика действует со следующего запроса, перезапуск не нужен).
Ключи из окружения (`SECTORIADB_S3_CREDENTIALS`) заводятся при старте сервера и считаются источником истины для перечисленных там ключей.

## Доступ (OFFLINE-ONLY)

| Команда | Что делает |
|---|---|
| `bucket-acl --bucket B [--acl private\|public-read\|public-read-write]` | показать или поставить ACL бакета |
| `object-acl --bucket B --key K [--acl ...]` | показать или поставить ACL текущей версии объекта |
| `public-list [--base-url http://host:8080]` | всё, что публично читается, со ссылками (обходит все объекты) |

То же через S3 API работает рядом с сервером: `aws s3api put-bucket-acl`, `put-object-acl`.

## Проверка целостности (OFFLINE-ONLY)

| Команда | Что делает |
|---|---|
| `verify --id FILE_ID` | перечитывает объект: CRC32C чанков/записей, CRC32C всего объекта, сумма клиента (`FULL_OBJECT`), MD5/ETag (не multipart); `OK`/`CORRUPT`/`MISSING` |
| `verify-all [--pool NAME]` | то же для всех живых объектов; итог `Verified N object(s): ok=… corrupt=… missing=…` и последняя строка `RESULT: OK` или `RESULT: FAILED (...)` |

`FILE_ID` — идентификатор манифеста из `ls` (не имя ключа). Скрытые части незавершённых multipart-загрузок не проверяются.

## Остальные команды (INTERACTIVE)

**Пулы (бакеты)**

| Команда | Что делает |
|---|---|
| `mkpool --name N --path DIR` | создать пул в каталоге (бакеты через S3 создаются в `<data-dir>/<имя>`; общий или вложенный каталог отвергается) |
| `pools` | список пулов |
| `pool --name N` | детали: блобы, заполнение, доля размещения HRW, сводка индекса чанков |
| `rmpool --name N [--yes]` | удалить пул без блобов (если блобы есть — отказ: сначала `rmblob`; бакет с объектами целиком удаляет только S3 `DeleteBucket`) |

**Блобы**

| Команда | Что делает |
|---|---|
| `mkblob --pool N [--buckets N] [--chunk N] \| --small` | создать кукушкин блоб или файл малых объектов вручную (обычно создаются сами; размер чанка в непустом пуле менять нельзя) |
| `blobs --pool N` | таблица блобов пула (вид, заполнение, чанки, HRW %) |
| `blob --id ID` | подробности и статистика заполнения / живые и мёртвые записи |
| `resize --id ID --buckets N` | расширить один кукушкин блоб на месте без остановки записи (после переноса заполнение должно быть ≤ 70 %); для файла малых объектов не поддерживается |
| `scrub --id ID` | проверить CRC32C всех слотов/записей; `RESULT: clean` или `RESULT: DAMAGED` |
| `rmblob --id ID [--yes]` | удалить блоб (отказ, пока в нём есть чанки со ссылками) |

**Файлы (манифесты)**

| Команда | Что делает |
|---|---|
| `store --file PATH --pool N` | положить локальный файл в пул (вне S3, без ключа) |
| `ls [--pool N]` | список объектов с идентификаторами |
| `info --id ID` | вид хранения, части, чанки, суммы |
| `get --id ID --out PATH [--from N --len N]` | восстановить объект (или диапазон) в файл |
| `rm --id ID [--yes]` | удалить объект (место вернёт сборщик мусора) |

**Сборка мусора** (подробности в [garbage-collection.md](../architecture/garbage-collection.md))

| Команда | Что делает |
|---|---|
| `gc status` | длины очередей, загрузки в полёте, итоги последних проходов |
| `gc run [--pool N] [--grace 15m\|0s] [--max N]` | один проход; `--grace 0s` не ждёт ничего (читатель старой версии может получить ошибку) |
| `gc sweep --pool N` | обход блобов в поиске копий оборванных загрузок |
| `gc compact --pool N [--force]` | компакция файлов малых объектов; `--force` игнорирует порог (но не текущий файл для дозаписи) |

**Служебные**

| Команда | Что делает |
|---|---|
| `status` | все пулы, блобы, заполнение, предупреждение о пороге роста |
| `config` | основные настройки (часть; полный список — [configuration.md](configuration.md)) |
| `logs [--n N]` | последние строки `<meta-dir>/sectoriadb.log` |
| `audit [--op ТИП] [--id ID] [--since ISO-8601] [--limit N]` | журнал операций (`oplogs.jsonl`): `STORE`, `DELETE`, `RESTORE`, `RESTORE_RANGE`, `RESIZE`, `BLOB_CREATE`, `BLOB_DELETE`, `POOL_CREATE`, `POOL_DELETE` |

В интерактивной консоли есть и встроенные команды Spring Shell (`help`, `exit`).
