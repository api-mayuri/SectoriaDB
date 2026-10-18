# SectoriaDB

S3-совместимое хранилище объектов на Java 21 и Spring Boot. Работает с `aws cli`, SDK и веб-интерфейсами, которые умеют S3 API (path-style, подпись SigV4).
Данные лежат в собственных блоб-файлах: крупные объекты — чанками в кукушкиных хэш-таблицах с дедупликацией, мелкие (меньше 16 КиБ) — в append-only журналах;
метаданные — во встроенном транзакционном хранилище (copy-on-write B+-дерево в одном файле `sectoria.db`), без внешней базы данных.

> Проект учебный и экспериментальный. Перед использованием для важных данных прочитайте [известные ограничения](docs/README.md#известные-ограничения-и-планы).

> **Перед боевым использованием прочитайте [рекомендации](docs/guide/recommendations.md):** когда SectoriaDB подходит, TLS (nginx), резервные копии, память, мониторинг.

## Возможности

- **S3 API:** бакеты, `PutObject`/`GetObject` (`Range`, условные заголовки)/`HeadObject`/`DeleteObject`/`DeleteObjects`/`CopyObject`, `ListObjects` v1 и v2, ACL, политики бакета, presigned-ссылки,
  полный multipart (`Complete` без копирования данных, идемпотентный, `GET ?partNumber`). Что поддержано и чем отличается от AWS — [guide/s3-usage.md](docs/guide/s3-usage.md).
- **Целостность от клиента до диска:** SigV4 и подписи `aws-chunked`, `x-amz-checksum-*` (CRC32, CRC32C, CRC64NVME, SHA-1, SHA-256) и `Content-MD5`, CRC32C каждого чанка и всего объекта на каждом чтении, `verify-all` — [architecture/integrity.md](docs/architecture/integrity.md).
- **Надёжность:** атомарные коммиты метаданных, переживает `kill -9` и потерю питания; при сбое записи метахранилища чтение продолжается, запись восстанавливается сама.
- **Место:** дедупликация по бакету, сборщик мусора возвращает место удалённых объектов, рост бакета добавлением блобов, расширение блоба без остановки записи.
- **Эксплуатация:** Docker и compose, несколько ключей доступа с включением/выключением без перезапуска, консоль администратора, метрики Prometheus, стенд Grafana, готовые алерты.

## Быстрый старт в Docker

Нужны Docker и Docker Compose.

```bash
cp .env.example .env
# откройте .env и задайте ключ (секрет не короче 16 символов):
#   SECTORIADB_S3_ACCESS_KEY=ваш_access_key
#   SECTORIADB_S3_SECRET_KEY=ваш_секретный_ключ_не_короче_16_символов
deploy/gen-self-signed.sh      # сертификат для проверки (боевой — в deploy/certs/, см. guide/operations.md)
docker compose up -d --build
```

TLS терминирует nginx из compose; порт самого сервера (`8080`) на хост не публикуется.

| Адрес | Что это |
|---|---|
| `https://localhost` | S3 API (nginx, TLS); `http://localhost` отвечает `301` на HTTPS |
| `http://127.0.0.1:9464/actuator/prometheus` | метрики и `/actuator/health` (без подписи, только loopback) |

Разработка без TLS: `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build` — дополнительно `http://localhost:8080` (S3, только loopback) и `http://localhost:8081` (веб-интерфейс s3manager, сторонний, необязательный).

Данные лежат в Docker-томах `sectoriadb-meta` и `sectoriadb-data` и переживают пересборку. Подробности — [guide/operations.md](docs/guide/operations.md).

## Запуск из jar

Нужны JDK 21 и Maven.

```bash
mvn -B package -DskipTests
java -jar sectoriadb-server/target/SectoriaDB-1.0-SNAPSHOT.jar                       # сервер + интерактивная консоль (help)
java -Dspring.shell.interactive.enabled=false -jar sectoriadb-server/target/SectoriaDB-1.0-SNAPSHOT.jar
# разработка без авторизации:  -Dsectoriadb.s3.auth.enabled=false
```

Пока нет ни одного ключа, сервер отвечает `403` на все запросы. Каталоги по умолчанию — `./sectoriadb-meta` и `./sectoriadb-data`; все свойства — [guide/configuration.md](docs/guide/configuration.md).

## Ключи доступа

Команды работают рядом с запущенным сервером ([все команды консоли](docs/guide/admin-shell.md)):

```bash
X="docker compose exec -T sectoriadb java -jar /app/sectoriadb.jar"
$X mk-key --count 3 --desc "для бэкапов"    # создать ключи
$X keys --secret                            # список с секретами
$X disable-key --id ID                      # выключить; enable-key — включить
$X rm-key --id ID1,ID2 --yes                # удалить
```

Один ключ — одному приложению или человеку: любой можно отозвать, не ломая остальных. Ключи можно передать и через `SECTORIADB_S3_CREDENTIALS=ACCESS:SECRET:описание,…` ([.env.example](.env.example)).

## Работа через AWS CLI

```bash
aws configure set aws_access_key_id     <AccessKeyId>
aws configure set aws_secret_access_key <SecretKey>
aws configure set default.region        us-east-1
aws configure set default.ca_bundle     "$PWD/deploy/certs/fullchain.pem"   # только для самоподписанного сертификата

aws s3 --endpoint-url https://localhost mb s3://my-bucket
aws s3 --endpoint-url https://localhost cp photo.jpg s3://my-bucket/
aws s3 --endpoint-url https://localhost ls s3://my-bucket
aws s3 --endpoint-url https://localhost cp s3://my-bucket/photo.jpg ./copy.jpg
aws s3 --endpoint-url https://localhost presign s3://my-bucket/photo.jpg --expires-in 3600
```

Регион клиента должен совпадать с `sectoriadb.s3.region` (по умолчанию `us-east-1`). Объекты приватны; как открыть их всем — [guide/s3-usage.md](docs/guide/s3-usage.md).

## Документация

Индекс и порядок чтения — [docs/README.md](docs/README.md).

| Раздел | Документы |
|---|---|
| Руководства | [S3 API](docs/guide/s3-usage.md) · [консоль](docs/guide/admin-shell.md) · [эксплуатация](docs/guide/operations.md) · [конфигурация](docs/guide/configuration.md) · [мониторинг](docs/guide/monitoring.md) · [разработка](docs/guide/development.md) |
| Архитектура | [обзор](docs/architecture/overview.md) · [движок](docs/architecture/storage-engine.md) · [метахранилище](docs/architecture/metastore.md) · [модель метаданных](docs/architecture/metadata-model.md) · [сборка мусора](docs/architecture/garbage-collection.md) · [целостность](docs/architecture/integrity.md) · [безопасность](docs/architecture/security.md) |
| Тестирование | [нагрузка](docs/testing/benchmarking.md) · [совместимость](docs/testing/compatibility.md) · [отказы](docs/testing/fault-injection.md) · [результаты](docs/reports/sandbox-results.md) |
| История | [CHANGELOG](CHANGELOG.md) · [документы этапов](docs/history/ROADMAP.md) |

## Безопасность в двух словах

- Не публикуйте `.env` (там реальные ключи), `deploy/certs/` и порты `9464` (метрики без подписи), `8080` (без TLS) и `8081` (s3manager с полными правами) наружу.
- Не отключайте авторизацию (`sectoriadb.s3.auth.enabled=false`) и не включайте `allow-open-setup` на сервере, доступном из сети. Сервер не терминирует TLS: в compose это делает nginx, `sectoriadb.s3.require-secure-transport=true` отвергает запросы не по HTTPS ([guide/operations.md](docs/guide/operations.md#tls-через-nginx)).
- Права ключа задаёт **IAM-политика** (стандартный документ AWS, `key-policy`, примеры в [deploy/iam](deploy/iam/)); пользователей, ролей и STS нет. **Ключ без политики имеет полный доступ ко всему**: выдавайте приложениям и арендаторам отдельные ключи с политиками. `public-read` на бакете открывает только листинг; чтение объекта открывают ACL объекта или политика. Подробности — [architecture/security.md](docs/architecture/security.md).
- Перед важными операциями делайте согласованную копию обоих томов ([резервное копирование](docs/guide/operations.md#резервное-копирование)).

## Лицензия

См. [LICENSE](LICENSE).
