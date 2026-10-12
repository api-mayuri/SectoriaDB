# Этап 12. Модули и пакеты

Этап чисто структурный: поведение и форматы файлов на диске не менялись. Цель — чтобы зависимости между частями
читались из структуры проекта и проверялись сборкой, а не договорённостью. Документы этапов 01–11 написаны для прежних
имён; таблица «старое → новое» ниже переводит их.

## 1. Модули

```
sectoriadb-server ──> sectoriadb-core ──> sectoriadb-engine
                                      └─> sectoriadb-metastore
```

| Модуль | Зависимости | Что внутри |
|---|---|---|
| `sectoriadb-metastore` | JDK, slf4j-api | транзакционное copy-on-write B+-дерево: страницы, снимки, групповой коммит, восстановление |
| `sectoriadb-engine` | JDK, slf4j-api | кукушкина таблица, журнал малых объектов, форматы файлов, ввод-вывод, хэши, размещение |
| `sectoriadb-core` | engine, metastore, Spring Boot (только `config`), Jackson | каталог, слой хранения, сопровождение (GC, расширение), ключи доступа, журнал операций, контрольные суммы |
| `sectoriadb-server` | core | S3 API, SigV4, консоль, планировщики, метрики Micrometer |

Родительский `pom.xml` перечисляет модули в порядке сборки и держит версии всех четырёх в `dependencyManagement`.
Тестовые помощники нижних модулей отдаются наверх через test-jar: `sectoriadb-metastore` — `MetaStoreFaults`
(имитация сбоя диска для тестов сервера), `sectoriadb-engine` — `CrashSimulation` (потеря несброшенных записей) и
`SourceGuard` (разбор импортов для тестов-стражей). `Dockerfile` копирует `pom.xml` и `src/` всех четырёх модулей;
исполняемый jar по-прежнему `sectoriadb-server/target/SectoriaDB-<версия>.jar`.

### Как нижние модули сообщают о событиях

Раньше метахранилище и движок знали интерфейс `StorageMetrics` из ядра. Теперь у каждого свой интерфейс с пустыми
методами по умолчанию, а ядро подключает метрики переходниками; имена и значения метрик не изменились.

| Модуль | Интерфейс | Переходник в ядре |
|---|---|---|
| metastore | `MetaStoreListener` (`onPageRead`, `onPageWrite`, `onFsync`, `onChecksumFailure`, `onWriterLockWait`, `onCommit`, `onRecovery`, `onGroupBatch`, `onGroupQueueWait`, `onGroupBodyRollback`, `NOOP`); `MetaStoreOptions.listener(...)` | `catalog.metastore.StorageMetricsMetaStoreListener` |
| engine | `engine.metrics.EngineListener` (чтение, запись, `fsync` по `Target.CUCKOO/SMALL`, путь вытеснений, переполнение таблицы, дедуп, перекей, ожидание блокировок, сбои контрольных сумм по `ChecksumKind`, групповой `fsync`, барьер) | `metrics.StorageMetricsEngineListener` |

### Тестовые хуки метахранилища

Публичные `injectFaultsForTesting` и строковый `commitHook` убраны. Вместо них пакетно-приватные `FaultInjector`
(сбой диска на N записей и `fsync`, перехват коммита) и перечисление `CommitStage` (`BEFORE_COMMIT`, `AFTER_DATA`,
`AFTER_META`). Из другого модуля до них можно добраться только через `MetaStoreFaults` из test-jar.

## 2. Карта пакетов (старое → новое)

### sectoriadb-metastore

| Было | Стало |
|---|---|
| `metastore.*` (ядро) | `metastore.*` (модуль `sectoriadb-metastore`), имя пакета то же |
| — | `metastore.MetaStoreListener`, `FaultInjector`, `CommitStage`, `MetaPage` (вынесено из `MetaStore`) |

### sectoriadb-engine

| Было | Стало |
|---|---|
| `service.impl.CuckooHashTable`, `SmallObjectBlob`, `InsertResult`; `model.SlotStateMap` | `engine.blob.*` (+ `EvictionPlanner`, `ForceBarrier`) |
| `service.StorageIOEngine`, `service.impl.FileChannelStorageIOEngine`; `model.BlobFile` | `engine.io.*` (+ `PositionalIo`: `readFully`/`writeFully`) |
| `format.BlobLayout`, `format.SmallBlobLayout` | `engine.format.*` |
| `tools.BytesHasher`, `tools.XxHash64BytesHasher` | `engine.hash.*` (+ общие `Mix64`, `Crc32c`) |
| `placement.RendezvousPlacement` | `engine.placement.RendezvousPlacement` |
| `service.impl.ChunkCorruptedException`, `TableFullException`, `KeyCollisionException`, `SmallObjectCorruptedException`; `format.InvalidBlobHeaderException` | `engine.error.*`, все наследуют новый `StorageException extends IOException` |
| (метрики через `StorageMetrics`) | `engine.metrics.EngineListener` |
| `model.Bucket`, `model.ChunkLocation`, `model.CuckooTable` | удалены |

### sectoriadb-core

| Было | Стало |
|---|---|
| `model.{ManifestEntity, PoolEntity, BlobFileEntity, BlobKind, StorageKind, ChunkEntry, PlacedChunk, UploadHold}` | `catalog.*` |
| `model.CredentialEntity`; `repository.{CredentialRepository, JsonCredentialRepository}`; `service.CredentialService` | `security.*` |
| `model.OperationLogEntity`; `repository.{OperationLogRepository, JsonOperationLogRepository}`; `service.OperationLogService` | `audit.*` |
| `repository.{ManifestRepository, ChunkRepository, BlobFileRepository, PoolRepository, GcRepository}` | `catalog.*` |
| `repository.metastore.*` | `catalog.metastore.*` (единственный пакет, который импортирует модуль `metastore`) |
| `config.MetaStoreConfig` | `catalog.metastore.MetaStoreHolder` (ленивое открытие, закрытие) и бины в `config.CoreConfiguration` |
| `metrics.MetricsConfig` | бин `noopStorageMetrics` в `config.CoreConfiguration` |
| `service.{ChunkStore, BlobService, PoolService, HashTableCache, SmallBlobCache, ResidentCache, ResidentHandle, UploadGate, ObjectVerificationService, ChunkNotFoundException, ObjectCorruptedException}` | `storage.*` |
| `service.FileStorageService` | `storage.ObjectWriter`, `storage.ObjectReader`, `storage.ObjectCatalog` |
| `HashTableCache.{freeze, unfreeze, isFrozen, writerGate, redirect, isReplaced, resolveRedirect}` | `storage.BlobGate` |
| `service.{ResizeService, BlobFileReaper, StartupReconciler}` | `maintenance.*` |
| `service.gc.{GarbageCollector, GcReports, SmallBlobCompactor}` | `maintenance.gc.*` |
| `config.StorageProperties` | там же; в неё перенесены `sectoriadb.metastore.*` (`lazy`, `recovery-backoff`, `group.*`) и `sectoriadb.reconcile-on-start`, прежде читавшиеся через `@Value` |
| `checksum`, `metrics.StorageMetrics` | без изменений |
| `service`, `service.impl`, `tools`, `model`, `repository`, `format`, `placement` | пакетов больше нет |

### sectoriadb-server

| Было | Стало |
|---|---|
| `s3.{S3ObjectController, S3BucketController, S3AclController, S3RootController, S3Lookup, RequestBodies}` | `s3.api.*` (+ `ObjectHeaders`) |
| `s3.S3MultipartController` | `s3.multipart.*`, см. п. 4 |
| `s3.{S3Exception, S3ExceptionHandler, S3MvcErrors, S3ErrorController}`; `s3.auth.PayloadVerificationException` | `s3.error.*` (+ `ResponseAbortedException`) |
| `s3.{S3Support, S3Checksums, LimitedInputStream, MinRateInputStream}` | `s3.http.*`: `S3Request`, `UrlCodec`, `HttpDates`, `S3Headers`, `CopySource`, `ByteRange`, `S3Checksums`, потоки; `XmlSupport.escape` вместо `S3Support.xmlEscape`, `RequestIds.current()` вместо `S3Support.requestId()` |
| `s3.XmlSupport` | `s3.xml.XmlSupport` |
| `s3.auth.AuthFailure` | `observability.AuthFailure` |
| `s3.auth`, `s3.access`, `observability`, `shell`, `scheduler` | без смены пакета |

## 3. Правила зависимостей и как они проверяются

Проверка без ArchUnit: в каждом модуле тест `ModuleDependencyGuardTest` читает `import` из `src/main/java`
(помощник `SourceGuard` из test-jar модуля `engine`; `metastore` проверяет себя сам, чтобы не зависеть от `engine`).
Нарушение ломает `mvn test`.

| Модуль | Что проверяется |
|---|---|
| metastore | импортируются только `java.*`, `javax.*`, `org.slf4j` и сам модуль |
| engine | только JDK, slf4j и `engine.*`; нет `metastore`, `catalog`, `storage`, `config`, `s3` и т. д.; пакеты `error`, `hash`, `metrics` ни от чего внутри движка не зависят; циклов между пакетами нет |
| core | модуль `metastore` импортирует только `catalog.metastore`; реализации `catalog.metastore` подключает только `config`; Spring — только в `config`; `catalog` не знает `storage`, `maintenance`, `security`, `audit`; `audit` и `security` не знают `storage` и `maintenance`; `storage` не знает `maintenance`; ядро не импортирует `s3`, `observability`, `shell`, `scheduler`; циклов нет (кроме пакета `config`, где лежат `StorageProperties`, читаемые всеми, и `CoreConfiguration`, которая собирает всех) |
| server | пакеты `s3.*` образуют слои `xml` < `error` < `http` < `access` < `auth` < `api` < `multipart`, каждый пакет импортирует только нижние (новый пакет требует правила в тесте); `observability`, `shell`, `scheduler` не импортируют `s3`; циклов нет; модуль `metastore` виден только в двух местах (`StorageGauges` и `S3ExceptionHandler`) |

## 4. Что разбито

| Класс до | Что стало | Строк было / стало |
|---|---|---|
| `FileStorageService` (5 задач) | `ObjectWriter` (шелл-`store`, `stageStream`, `commitObject`, `abortStaged`, повторная привязка чанков после расширения), `ObjectReader` (потоковое чтение и диапазоны с проверкой CRC, `restore`), `ObjectCatalog` (поиск, листинг, удаление); общего фасада нет, вызывающие получают нужные классы | 690 → 3 класса (359, 313, 70) |
| `HashTableCache` | остался кэш (владение, вытеснение, статистика заполнения); состояние «заморожен / заменён / затвор записи» вынесено в `BlobGate` | 257 → 202 + 83 |
| `CuckooHashTable` | поиск пути вытеснений — `EvictionPlanner`, групповой `fsync` — `ForceBarrier` | 956 → 732 + 142 + 78 |
| `MetaStore` | выбор и кодирование мета-страниц — `MetaPage` | 750 → 649 + 100 |
| `S3MultipartController` | `S3MultipartController` (только HTTP), `MultipartUploadStore` (каталог `.multipart`, блокировки загрузок, состояние, части, листинг), `CompleteMultipartService` (автомат OPEN → COMPLETING → COMPLETED, сборка объекта), `CompleteRequestParser` и `ParsedParts`, `UploadMeta`, `MultipartCleanup`, `MultipartXml`, `LazySequenceInputStream` | 1210 → контроллер 399 + 8 классов (по 38–359 строк) |
| `S3Support` | `S3Request`, `UrlCodec`, `HttpDates`, `XmlSupport.escape`, `RequestIds.current` | — |

Формат файлов `.multipart` остался прежним (`meta.properties`, `state.properties`, `etags.properties`,
`checksums.properties`, `part-NNNNN`): перенос в метахранилище — этап 13.

Бины ядра теперь описаны методами `@Bean` в `config.CoreConfiguration`; в остальных классах ядра нет ни `@Service`,
ни `@Component`, ни `@Autowired`. Конструкторы, существовавшие только ради тестов (`HashTableCache(props)`,
`SmallBlobCache(props)`, `FileStorageService` без метрик, `ChunkStore` без свойств), удалены: тесты передают зависимости
явно (`StorageRig` собирает граф так же, как `CoreConfiguration`). Расширение метахранилища (`lazy`, `group.*`) читается из
`StorageProperties`, поэтому имена свойств остались прежними.

Общий код вместо дублей: один `Mix64` (splitmix64) вместо двух копий в `BlobLayout` и `RendezvousPlacement`; один
`Crc32c` вместо ручных `new CRC32C()` в `BlobLayout`, `SmallBlobLayout` и `CuckooHashTable`; `CopySource` (разбор `x-amz-copy-source` был в двух контроллерах),
`ByteRange` (диапазон GET и диапазон UploadPartCopy), `ObjectHeaders` (`Cache-Control`, `Content-Disposition`,
`Content-Encoding`, `Content-Language` и `x-amz-meta-*` читались, записывались и копировались в пяти местах),
`S3Exception.notImplemented`.

Заголовки `x-amz-request-id` и `Server: SectoriaDB` больше не ставятся вручную в десятках мест контроллеров: `x-amz-request-id` ставит
фильтр наблюдаемости (как и раньше), `Server` — новый `s3.http.S3CommonHeadersFilter`.

## 5. Что удалено

* Устаревший API `CuckooHashTable`, жившие только в тестах: `lookup`, `readChunk(ChunkLocation, …)`, `getBucket`,
  `lockForWrite` и `WriteGuard`, `forEachActiveChunk` (остался пакетный `forEachActiveChunkInBatches`), поле
  `InsertResult.location`, классы `Bucket`, `ChunkLocation`, `CuckooTable`. Тесты переведены на `contains`,
  `readChunkByKey` и пакетно-приватные `slotOf`/`dataOffsetOf` (нужны только чтобы испортить слот).
* Пустые остатки пакетов `service`, `service.impl`, `tools`, `model`, `repository`.
* Неиспользуемое: `crc32FromBytes`, `BlobService.toEngineBlob`, `ObjectCatalog.updateObject` и `listByBlobFileId`,
  `UploadGate.poolsInFlight` и `Sweep.revokedHolds`, `SlotStateMap.fromValue`, `SmallBlobLayout.alignUp`,
  `ShellTable.kindLabel`, `S3Exception.accessDenied`, `SigV4Filter.SHORT_DATE_FMT`, неиспользуемый `log` в
  `MetaStoreGcRepository`, поля `poolService` в `S3ObjectController`, `MetaStoreConfig`/`MetricsConfig`.
* Тест, пропавший вместе с мёртвым кодом: `CuckooInsertSafetyTest.lockForWrite_isReentrantAndReleased`.

## 6. Что видно снаружи

Бинарный и дисковый формат, имена метрик и свойств, коды ошибок и тела ответов не менялись. Отличия в заголовках:

* заголовок `Server: SectoriaDB` теперь есть у каждого ответа S3 (раньше только у части, у multipart и ACL его не было);
* `x-amz-request-id` — ровно один на ответ (контроллеры больше не добавляют второй);
* сообщение об ошибке разбора `x-amz-copy-source` в UploadPartCopy теперь то же, что у CopyObject:
  `x-amz-copy-source must be in format /bucket/key` (код `InvalidArgument` прежний).

## 7. Тесты

Всего 552 теста, все зелёные (было 539; +14 тестов-стражей, −1 тест удалённого мёртвого API).

| Модуль | Тестов |
|---|---|
| sectoriadb-metastore | 77 |
| sectoriadb-engine | 91 |
| sectoriadb-core | 162 |
| sectoriadb-server | 222 |

Тесты движка переехали в `sectoriadb-engine`, тесты метахранилища — в `sectoriadb-metastore`; `EngineMetricsTest` разделён:
события движка проверяются в движке (`RecordingEngineListener`), события метахранилища — в ядре
(`MetaStoreMetricsTest`, через переходник).

## 8. Отложено

* Единый общий помощник группового `fsync` для кукушки и журнала малых объектов. Кукушка (`ForceBarrier`) считает
  записи и ждёт следующего `fsync`; журнал малых объектов ведёт поколение сбоев, отбрасывает несброшенные записи и
  пишет контрольные точки. Объединение изменило бы поведение при сбое, а этап чисто структурный.
* Вынос `FreeSpace` (списки свободных страниц) и коммита/восстановления из `MetaStore`: они делят состояние под
  одним разрешением писателя, разрезать их безопасно можно только вместе с этапом 13 (там меняется коммит).
* `StorageGauges` и `S3ExceptionHandler` всё ещё импортируют `MetaStore.Stats` и `MetaStoreUnavailableException`
  напрямую. Развязка потребует собственного типа ошибки в ядре и поменяет перехватываемые исключения.
* `S3MultipartController` по-прежнему копирует данные частей при Complete; перенос состояния в метахранилище и сборка
  без копирования — этап 13.
* Документы 01–11 остаются в прежних именах классов (см. таблицу в п. 2).
