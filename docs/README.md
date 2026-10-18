# Документация SectoriaDB

Документация описывает **текущее** состояние проекта. Хронология разработки (документы по этапам 01–14, дорожная карта) сохранена в [docs/history](history/ROADMAP.md);
список изменений по этапам — [CHANGELOG](../CHANGELOG.md).

> **Начните с [рекомендаций по использованию](guide/recommendations.md)** — чек-лист перед боевым запуском, nginx, резервные копии, обновления.

## Рекомендуемый порядок чтения

**Если вы пользователь или администратор**

1. [README проекта](../README.md) — что это, быстрый старт, ключи, `aws cli`.
2. [guide/recommendations.md](guide/recommendations.md) — когда подходит, чек-лист, nginx, копии, обновления.
3. [guide/s3-usage.md](guide/s3-usage.md) — какой S3 API поддерживается, отличия от AWS, публичный доступ, multipart, лимиты и ошибки.
4. [guide/operations.md](guide/operations.md) — Docker, тома, резервные копии, сборка мусора, сбои, полный диск, логи.
5. [guide/configuration.md](guide/configuration.md) — все свойства `sectoriadb.*` в одной таблице.
6. [guide/admin-shell.md](guide/admin-shell.md) — команды консоли (какие работают рядом с сервером).
7. [guide/monitoring.md](guide/monitoring.md) — метрики, PromQL, стенд Grafana, алерты.

**Если вы разработчик**

1. [architecture/overview.md](architecture/overview.md) — модули, путь запросов, инварианты, глоссарий.
2. [architecture/storage-engine.md](architecture/storage-engine.md) → [architecture/metastore.md](architecture/metastore.md) → [architecture/metadata-model.md](architecture/metadata-model.md).
3. [architecture/garbage-collection.md](architecture/garbage-collection.md), [architecture/integrity.md](architecture/integrity.md), [architecture/security.md](architecture/security.md), [architecture/observability.md](architecture/observability.md).
4. [guide/development.md](guide/development.md) — сборка, тесты, правила зависимостей и стиль кода.
5. [testing/benchmarking.md](testing/benchmarking.md), [testing/compatibility.md](testing/compatibility.md), [testing/fault-injection.md](testing/fault-injection.md), [reports/sandbox-results.md](reports/sandbox-results.md).

## Карта документов

| Каталог | Документы |
|---|---|
| `guide/` | `recommendations`, `s3-usage`, `admin-shell`, `operations`, `configuration`, `monitoring`, `development` |
| `architecture/` | `overview`, `storage-engine`, `metastore`, `metadata-model`, `garbage-collection`, `integrity`, `security`, `observability` |
| `testing/` | `benchmarking`, `compatibility`, `fault-injection` |
| `reports/` | `sandbox-results` (измерения с датами и оговоркой о железе) |
| `history/` | `01-…`–`14-…`, `ROADMAP` — исторические документы этапов, могут расходиться с кодом |

Консистентность проверяет `DocsConsistencyTest` (ссылки, метрики, свойства).

## Известные ограничения и планы

Собрано из списков «отложено» и «открытые вопросы» документов этапов.

**Безопасность и модель доступа**
- Нет пользователей, групп, ролей, STS/OIDC и владельца объектов; права ключа задаёт IAM-политика (ключ без политики имеет полный доступ), ACL действуют только на анонимных. Из `Condition` вычисляются `Bool` на `aws:SecureTransport` и `StringEquals`/`StringLike` на `s3:prefix`/`s3:delimiter`.
- TLS сервер не терминирует (его терминирует nginx из compose: [operations.md](guide/operations.md#tls-через-nginx)); шифрования на стороне сервера нет и не будет в коде — шифруется слой хранения (LUKS, шифрованный диск облака: [operations.md](guide/operations.md#шифрование-на-диске)); секреты ключей лежат в `credentials.json` открытым текстом (защищает только шифрование тома).
- Лимиты (скорость тела, одновременные запросы) задаются на ключ доступа; общего ограничения числа бакетов, ключей и открытых multipart-загрузок нет.

**Функции S3, которых нет**
- Версионирование, блокировки объектов и retention, CORS, lifecycle, public access block, ownership controls, `GetObjectAttributes`, POST-форма, S3 Select, уведомления, SigV2 и SigV4a.
- Теги объектов не хранятся (`PutObjectTagging` — `501`); `Expires` и `x-amz-storage-class` не сохраняются; условные записи — только `If-None-Match: *` и одиночный `If-Match`; `x-amz-copy-source-if-*` — `501`.
- Нет `Initiator`/`Owner` в `ListParts` и `ListMultipartUploads`; суммы части не возвращаются в ответах `GET`/`HEAD ?partNumber`; `ListBuckets` без постраничности.

**Данные и производительность**
- `UploadPartCopy` и `CopyObject` копируют данные (переиспользования чанков источника без чтения нет); тело `PUT` сначала целиком пишется во временный файл (нужен том под самую большую загрузку).
- Файлы `blob_*.raw` не уменьшаются; слоты карантина не освобождаются автоматически, ремонта блоба нет.
- `replaceBlob` (расширение блоба) и компакция `.sob` переписывают индекс/манифесты одной транзакцией — для очень больших блобов это длинная монопольная транзакция; выборка очереди сборщика читает её целиком; freelist метахранилища переписывается целиком на каждом коммите.
- Один писатель метахранилища: потолок записи — коммиты в секунду на вашем диске (групповой коммит снижает цену, но не снимает предел).
- Нет сжатия; малые объекты не дедуплицируются; вставка в кукушку вблизи ~98 % заполнения дорожает.
- Обратной совместимости формата на диске между версиями разработки нет, миграторов нет.

**Эксплуатация и тестирование**
- **Один узел, репликации нет.** Потеря диска или хоста — потеря данных, не покрытых копией; отказоустойчивость и репликацию нужно получать от хранилища под сервером (снимки с отправкой на другой узел — ZFS/Btrfs send, облачные снимки в другую зону, `rsync`/архивы на другую площадку) и от клиентской репликации между двумя экземплярами (`rclone sync`). Своего движка резервного копирования в сервере нет: копия — это атомарный снимок единственного тома ([operations.md](guide/operations.md#резервное-копирование)), скрипты `deploy/backup/` — холодная копия, восстановление и проверка.
- Шифрование на диске и снимки LVM/ZFS/Btrfs/облака описаны, но не прогонялись в песочнице (нет `cryptsetup`, device-mapper и файловых систем со снимками); проверены имитация снимка (`bench/faults/snapshot-backup.sh`) и монтирование отдельной файловой системы в том.
- Нет версионирования объектов, а значит случайное удаление или перезапись откатываются только из копии.
- Не проводились: загрузка 1 ТБ, холодный/тёплый кэш и заполнение 70–90 %, прогоны на дрейф на 8 ч и дольше (есть `bench/warp/soak.sh` и короткий прогон в песочнице — [benchmarking.md](testing/benchmarking.md#длительный-прогон-soak)), распределённый `warp`. Результаты в [reports/sandbox-results.md](reports/sandbox-results.md) получены на песочнице и непригодны для планирования ёмкости.
- Фаззинг ограничен свойствами-тестами с фиксированным зерном на парсеры запросов (Range, copy-source, aws-chunked, SigV4, XML); покрытие-ориентированного фаззера (Jazzer) и фаззинга форматов на диске нет.
- Открыто в совместимости: minio-js не завершает набор mint, aws-sdk-java-v2 не печатает результатов, `PresignedPut` aws-sdk-go-v2 ([testing/compatibility.md](testing/compatibility.md)).
