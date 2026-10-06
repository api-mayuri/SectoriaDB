# 08. Наблюдаемость и бенчмарки

Этап состоит из двух частей:

* **A. Метрики** (раздел «Метрики» ниже): серверные метрики в формате Prometheus, идентификатор запроса, MDC и лог
  медленных запросов.
* **B. Стенд и бенчмарки**: Prometheus, Grafana, InfluxDB, `warp`, тесты совместимости и отказов (раздел «Стенд и
  бенчмарки», ещё не написан).

## Метрики

### 1. Что и как измеряется

Подход: **RED** (Rate, Errors, Duration) для каждой S3-операции, **USE** (Utilization, Saturation, Errors) для
ресурсов (диск, блокировки, потоки, JVM) и «внутренности» движка (кукушка, малые объекты, метахранилище).

```
          клиент ──► :8080  RequestObservabilityFilter ──► SigV4Filter ──► контроллеры ──► движок
                                │ id запроса, MDC, классификация          │ StorageMetrics (SPI)
                                ▼                                          ▼
                       S3Metrics (RED)                       MicrometerStorageMetrics
                                └──────────► MeterRegistry (Micrometer) ◄── StorageGauges (кэш 15 с)
                                                      │
          Prometheus ──► :9464/actuator/prometheus ◄──┘   (отдельный Tomcat, без SigV4)
```

* **Модульность.** `sectoriadb-core` не зависит ни от Micrometer, ни от Prometheus, ни от actuator. В пакете
  `org.example.sectoriadb.metrics` лежит интерфейс `StorageMetrics` (события движка: `fsync`, `diskRead/diskWrite`,
  `evictionPath`, `tableFull`, `dedupHit`, `crcFailure(kind)`, `metaCommit`, ...) и реализация `StorageMetrics.NOOP`
  (используется по умолчанию, в CLI-режиме и тестах). Существующие конструкторы классов движка не изменились, добавлены
  перегрузки с `StorageMetrics`. Сервер реализует интерфейс на Micrometer (`MicrometerStorageMetrics`, бин `@Primary`).
  Micrometer в `core` не нужен, поэтому и не добавлен.
* **Накладные расходы.** Все счётчики и гистограммы создаются один раз в конструкторе. На горячем пути остаётся
  поиск в `EnumMap` и один вызов `record`. Метрики никогда не бросают исключений в движок.
* **Состояние (gauges)** читается на скрейпе, но не чаще одного раза в `sectoriadb.observability.gauge-cache-ms`
  (15 секунд): все значения берутся из одного кэшированного снимка (`StorageGauges`). Ничего не сканируется:
  счётчики слотов ведёт сама кукушка (O(1)), число объектов и их размер ведутся в транзакции коммита
  (см. п. 7), остальное это вызов `MetaStore.stats()`, несколько `statfs` и чтение небольшого дерева `blobs`.

### 2. Порт управления и безопасность

| Параметр | По умолчанию | Описание |
|---|---|---|
| `management.server.port` | **`9464`** | порт actuator; свой Tomcat и свой контекст сервлетов. Переменная окружения `MANAGEMENT_SERVER_PORT` |
| `management.server.address` | все интерфейсы | в продакшене ограничьте внутренним интерфейсом (`127.0.0.1`, адрес сети мониторинга) |
| `management.endpoints.web.exposure.include` | `health,prometheus` | наружу выходят **только** эти два эндпоинта; все остальные отключены (`enabled-by-default=false`) |

Порт 9464 выбран как порт OpenTelemetry-экспортёра Prometheus (чтобы не пересекаться с 9090 Prometheus, 9100 node_exporter,
3000 Grafana, 8086 InfluxDB и 8080 самого сервера).

* `GET http://host:9464/actuator/prometheus`: метрики (`text/plain; version=0.0.4`).
* `GET http://host:9464/actuator/health`: `{"status":"UP"}` (подробности скрыты).
* Порт управления **не защищён подписью**: фильтры `SigV4Filter` и `RequestObservabilityFilter` зарегистрированы только
  в контексте S3-порта, на порт управления они не попадают (проверено тестом `S3MetricsTest`: скрейп работает без
  ключей, хотя ключи доступа созданы). Метрики не содержат ни секретов, ни имён бакетов и ключей, но порт всё равно
  стоит закрыть сетевыми правилами.
* Порт S3 (`8080`) **не отдаёт** `/actuator/*`: с включённой подписью анонимный запрос получает `403 AccessDenied`,
  подписанный запрос трактуется как `GET /actuator/prometheus` в бакете `actuator` и даёт `404 NoSuchBucket`.
  При `sectoriadb.s3.auth.enabled=false` ответ тоже `404`. Бакет с именем `actuator` не даёт доступа к метрикам.
* Порт управления не обслуживает S3 API: любой другой путь даёт `404`.
* В тестах (`sectoriadb-server/src/test/resources/config/application.properties`) порт управления случайный
  (`management.server.port=0`), иначе кэшированные контексты Spring конфликтовали бы на 9464.

### 3. Общие правила

**Имена.** Префикс `sectoriadb_` у всех собственных метрик. Micrometer-имя `sectoriadb.s3.requests` превращается в
`sectoriadb_s3_requests_seconds_{bucket,count,sum,max}`; счётчики получают суффикс `_total`, единицы измерения
(`seconds`, `bytes`) добавляются автоматически. Стандартные метрики JVM, процесса и Tomcat сохраняют общепринятые имена
(`jvm_*`, `process_*`, `system_*`, `tomcat_*`), потому что на них рассчитаны готовые дашборды Grafana.

**Общие метки.** `application="SectoriaDB"` задаётся конфигурацией (`management.metrics.tags.application`).
Метку `instance` Prometheus ставит сам по адресу цели; если нужна собственная, задайте
`management.metrics.tags.instance=...` (только конфигурация, в код ничего не зашито).

**Гистограммы, не summary.** Процентили не считаются в процессе: все распределения это гистограммы с явными границами
(10-15 штук). Перцентиль вычисляется в Prometheus: `histogram_quantile(0.99, sum by (le) (rate(x_bucket[5m])))`.
Граница пишется в формате Java: `le="1.0E-4"` это 100 мкс, `le="0.001"` это 1 мс. Кроме `_bucket/_count/_sum` Micrometer
публикует ещё gauge `_max` (максимум за скользящее окно): его можно игнорировать.

**Правила кардинальности.** Метки только из закрытых множеств:

| Метка | Допустимые значения | Источник |
|---|---|---|
| `operation` | 37 значений `S3Operation` (имена действий AWS) | `OperationClassifier` |
| `status_class` | `2xx`, `3xx`, `4xx`, `5xx`, `other` | код ответа |
| `code` | коды ошибок S3, которые порождает сам сервер; значение проходит проверку `[A-Za-z0-9]{1,48}`, иначе `Other`; если ответ об ошибке пришёл без кода: `Http<код>` или `Aborted` | исключения и фильтры сервера |
| `reason` | 11 значений `AuthFailure` | `SigV4Filter`, `S3ExceptionHandler` |
| `target` | `cuckoo`, `small`, `metastore` | `StorageMetrics.Target` |
| `kind` | `chunk`, `small_record`, `whole_object`, `metastore_page`, `slot_meta`; для `sectoriadb_blobs`: `cuckoo`, `small` | |
| `result` | `success`, `failure` | |
| `dir` | `data`, `meta` (не путь) | |

**Никогда** метками не становятся имя бакета, ключ объекта, ключ доступа, идентификатор блоба или манифеста,
`uploadId`, IP клиента, путь запроса. Тест `S3MetricsTest` проверяет, что в выводе скрейпа нет имени бакета, ключа
и ключа доступа и что все имена меток входят в белый список. Оценка числа рядов: `sectoriadb_s3_requests_seconds`
не более 37 x 5 x 18 строк (на практике несколько десятков операций активны, то есть порядка тысячи строк);
`sectoriadb_s3_errors_total` не более 37 x (число кодов, обычно 10-15).

### 4. Каталог метрик

Типичные PromQL приведены для окна `5m`. «Г» означает гистограмму (ряды `_bucket`, `_count`, `_sum`, `_max`).

#### 4.1. S3 API (RED)

| Метрика | Тип | Метки | Ед. | Смысл | Типичный PromQL |
|---|---|---|---|---|---|
| `sectoriadb_s3_requests_seconds` | Г | `operation`, `status_class` | с | задержка запроса от входа в сервер до записи **всего** тела ответа (GET стримится синхронно в потоке запроса, фильтр возвращается после последнего байта). `_count` это rate, `status_class=~"4xx\|5xx"` это ошибки | `histogram_quantile(0.99, sum by (operation, le) (rate(sectoriadb_s3_requests_seconds_bucket[5m])))` |
| `sectoriadb_s3_errors_total` | счётчик | `operation`, `code` | шт. | ответы с ошибкой по коду S3 (`AccessDenied`, `NoSuchKey`, `BadDigest`, `SignatureDoesNotMatch`, `RequestTimeTooSkewed`, ...) | `topk(10, sum by (code)(rate(sectoriadb_s3_errors_total[5m])))` |
| `sectoriadb_s3_auth_failures_total` | счётчик | `reason` | шт. | отказы аутентификации SigV4 по причинам (п. 5). Ряды всех причин существуют с нуля | `sum by (reason)(rate(sectoriadb_s3_auth_failures_total[5m]))` |
| `sectoriadb_s3_requests_inflight` | gauge | `operation` | шт. | запросов в обработке сейчас | `sum(sectoriadb_s3_requests_inflight)` |
| `sectoriadb_s3_request_bytes_total` | счётчик | `operation` | байт | байты тела запроса, прочитанные из соединения (для `aws-chunked` с обрамлением) | `sum by (operation)(rate(sectoriadb_s3_request_bytes_total[1m]))` |
| `sectoriadb_s3_response_bytes_total` | счётчик | `operation` | байт | байты тела ответа (у ошибок это XML) | `sum(rate(sectoriadb_s3_response_bytes_total{operation="GetObject"}[1m]))` |
| `sectoriadb_s3_get_ttfb_seconds` | Г | нет | с | GetObject (200/206): время от начала запроса до первой записи тела в ответ | `histogram_quantile(0.99, sum by (le)(rate(sectoriadb_s3_get_ttfb_seconds_bucket[5m])))` |
| `sectoriadb_s3_requests_aborted_total` | счётчик | `operation` | шт. | запросы, завершившиеся исключением, не обработанным S3-обработчиком (например, GET оборван после отправки заголовков из-за ошибки проверки CRC) | `sum by (operation)(increase(sectoriadb_s3_requests_aborted_total[1h]))` |
| `sectoriadb_s3_requests_slow_total` | счётчик | `operation` | шт. | запросы дольше `sectoriadb.observability.slow-request-ms` (они же попали в WARN-лог) | `sum by (operation)(rate(sectoriadb_s3_requests_slow_total[5m]))` |

#### 4.2. Диск и `fsync` (USE)

| Метрика | Тип | Метки | Ед. | Смысл | Типичный PromQL |
|---|---|---|---|---|---|
| `sectoriadb_storage_fsync_seconds` | Г | `target` | с | один `FileChannel.force` (данные чанка, запись слота, добавление в `.sob`, коммит метахранилища). Не вызывается при `sectoriadb.fsync=false` | `histogram_quantile(0.99, sum by (target, le)(rate(sectoriadb_storage_fsync_seconds_bucket[5m])))` |
| `sectoriadb_storage_disk_read_seconds` | Г | `target` | с | один позиционный `read` (чанк, запись `.sob`, страница метахранилища) | `histogram_quantile(0.99, sum by (target, le)(rate(sectoriadb_storage_disk_read_seconds_bucket[5m])))` |
| `sectoriadb_storage_disk_write_seconds` | Г | `target` | с | один `write` (в page cache, до `fsync`) | аналогично |
| `sectoriadb_storage_disk_read_bytes_total` | счётчик | `target` | байт | прочитано с диска движком | `sum by (target)(rate(sectoriadb_storage_disk_read_bytes_total[1m]))` |
| `sectoriadb_storage_disk_write_bytes_total` | счётчик | `target` | байт | записано на диск движком (включая метаданные слотов и страницы B+-дерева) | `sum by (target)(rate(sectoriadb_storage_disk_write_bytes_total[1m]))` |
| `sectoriadb_metastore_writer_lock_wait_seconds` | Г | нет | с | ожидание единственного слота записывающей транзакции метахранилища. Рост это очередь писателей | `histogram_quantile(0.99, sum by (le)(rate(sectoriadb_metastore_writer_lock_wait_seconds_bucket[5m])))` |
| `sectoriadb_metastore_commit_seconds` | Г | нет | с | один коммит (страницы, `fsync`, мета-страница, `fsync`) | `histogram_quantile(0.99, sum by (le)(rate(sectoriadb_metastore_commit_seconds_bucket[5m])))` |
| `sectoriadb_small_append_lock_wait_seconds` | Г | нет | с | ожидание блокировки добавления в `small_*.sob` | аналогично |
| `sectoriadb_cuckoo_lock_wait_seconds` | Г | нет | с | ожидание блокировки записи кукушкиной таблицы перед вставкой чанка | аналогично |
| `tomcat_threads_busy_threads` / `tomcat_threads_config_max_threads` / `tomcat_threads_current_threads` | gauge | нет | шт. | пул потоков Tomcat S3-порта (включено `server.tomcat.mbeanregistry.enabled=true`) | `tomcat_threads_busy_threads / tomcat_threads_config_max_threads` |
| `jvm_gc_pause_seconds` | Г | `action`, `cause`, `gc` | с | паузы GC (границы заданы свойством `management.metrics.distribution.slo.jvm.gc.pause`) | `histogram_quantile(0.99, sum by (le)(rate(jvm_gc_pause_seconds_bucket[5m])))` |
| `jvm_memory_used_bytes`, `jvm_memory_max_bytes` | gauge | `area`, `id` | байт | куча и не-куча | `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}` |
| `jvm_threads_live_threads`, `jvm_threads_states_threads` | gauge | `state` | шт. | потоки JVM | |
| `process_files_open_files`, `process_files_max_files` | gauge | нет | шт. | открытые файловые дескрипторы | `process_files_open_files / process_files_max_files` |
| `process_cpu_usage`, `system_cpu_usage`, `system_load_average_1m` | gauge | нет | доля | загрузка процессора | |

#### 4.3. Внутренности движка

| Метрика | Тип | Метки | Ед. | Смысл | Типичный PromQL |
|---|---|---|---|---|---|
| `sectoriadb_cuckoo_eviction_path_length` | Г | нет | перемещений | сколько чанков сдвинула одна вставка (0: в корзине-кандидате был свободный слот). Первая граница `le="0.5"` означает «ни одного вытеснения» | `histogram_quantile(0.99, sum by (le)(rate(sectoriadb_cuckoo_eviction_path_length_bucket[5m])))` |
| `sectoriadb_cuckoo_table_full_total` | счётчик | нет | шт. | вставки, отклонённые `TableFullException` | `increase(sectoriadb_cuckoo_table_full_total[1h]) > 0` |
| `sectoriadb_cuckoo_dedup_hits_total` | счётчик | нет | шт. | вставки, нашедшие тот же чанк (дедупликация) | `rate(sectoriadb_cuckoo_dedup_hits_total[5m])` |
| `sectoriadb_cuckoo_rekeys_total` | счётчик | нет | шт. | настоящие коллизии 64-битного хэша: чанк сохранён под ключом с солью | `increase(sectoriadb_cuckoo_rekeys_total[1d])` |
| `sectoriadb_integrity_crc_failures_total` | счётчик | `kind` | шт. | провалы проверки CRC: `chunk` (CRC/длина слота при чтении и при лечении), `small_record` (заголовок/данные записи `.sob`), `whole_object` (CRC32C объекта из манифеста), `metastore_page` (страница `sectoria.db`), `slot_meta` (запись слота при загрузке блоба: слот попадает в карантин). **Любой ненулевой прирост это инцидент** | `sum by (kind)(increase(sectoriadb_integrity_crc_failures_total[1h])) > 0` |
| `sectoriadb_cuckoo_slots_active` | gauge | нет | шт. | занятые слоты, сумма по загруженным блобам | |
| `sectoriadb_cuckoo_slots_capacity` | gauge | нет | шт. | всего слотов у загруженных блобов (в метрике намеренно не `..._total`: Micrometer отрезает такой суффикс у gauge) | `100 * sectoriadb_cuckoo_slots_active / sectoriadb_cuckoo_slots_capacity` |
| `sectoriadb_cuckoo_slots_quarantined` | gauge | нет | шт. | слоты в карантине (запись слота не прошла CRC, чанк недоступен, слот не перезаписывается) | `sectoriadb_cuckoo_slots_quarantined > 0` |
| `sectoriadb_cuckoo_tables_loaded` | gauge | нет | шт. | кукушкины блобы, загруженные в память этого процесса | |
| `sectoriadb_cuckoo_used_bytes` | gauge | нет | байт | активные слоты x размер чанка (загруженные блобы) | |
| `sectoriadb_cuckoo_capacity_bytes` | gauge | нет | байт | ёмкость всех кукушкиных блобов (слоты x размер чанка), загруженных или нет: «общая ёмкость» | `sectoriadb_cuckoo_used_bytes / sectoriadb_cuckoo_capacity_bytes` |
| `sectoriadb_blobs` | gauge | `kind` | шт. | зарегистрированные блоб-файлы | |
| `sectoriadb_small_live_bytes`, `sectoriadb_small_dead_bytes` | gauge | нет | байт | занятое живыми и удалёнными (ещё не возвращёнными) записями `.sob`, открытые файлы | `sectoriadb_small_dead_bytes / (sectoriadb_small_live_bytes + sectoriadb_small_dead_bytes)` |
| `sectoriadb_small_live_records`, `sectoriadb_small_dead_records` | gauge | нет | шт. | то же в записях | |
| `sectoriadb_small_blobs_open` | gauge | нет | шт. | открытые файлы малых объектов | |
| `sectoriadb_metastore_file_bytes` | gauge | нет | байт | размер `sectoria.db` | |
| `sectoriadb_metastore_pages`, `sectoriadb_metastore_free_pages` | gauge | нет | шт. | страниц в файле / свободных (включая ждущие читателей) | `sectoriadb_metastore_free_pages / sectoriadb_metastore_pages` |
| `sectoriadb_metastore_last_txid` | gauge | нет | номер | последняя зафиксированная транзакция | `rate(sectoriadb_metastore_last_txid[1m])` это коммитов в секунду |
| `sectoriadb_metastore_live_readers` | gauge | нет | шт. | открытые читающие транзакции (долгие читатели удерживают страницы от переиспользования) | |
| `sectoriadb_metastore_page_size_bytes` | gauge | нет | байт | размер страницы | |
| `sectoriadb_gc_queue_length` | gauge | нет | шт. | замещённые и удалённые манифесты, ждущие сборщика мусора (`deleted_manifests`); растёт до этапа `10-gc-and-resize` | `sectoriadb_gc_queue_length` |
| `sectoriadb_resize_seconds` | Г | `result` | с | длительность расширения блоба; `_count{result="failure"}` это ошибки (в том числе отказ из-за слишком малого нового размера) | `increase(sectoriadb_resize_seconds_count{result="failure"}[1d])` |
| `sectoriadb_auto_resize_runs_total` | счётчик | нет | шт. | проходы планировщика авторасширения | |

#### 4.4. Ёмкость и объём данных

| Метрика | Тип | Метки | Ед. | Смысл | Типичный PromQL |
|---|---|---|---|---|---|
| `sectoriadb_objects_stored` | gauge | нет | шт. | текущие версии S3-объектов (все бакеты) | |
| `sectoriadb_objects_stored_bytes` | gauge | нет | байт | их суммарный размер (логический, до дедупликации) | `sectoriadb_objects_stored_bytes / sectoriadb_cuckoo_used_bytes` |
| `sectoriadb_disk_free_bytes` | gauge | `dir` | байт | доступное место файловой системы каталога данных / метаданных | `sectoriadb_disk_free_bytes / sectoriadb_disk_total_bytes` |
| `sectoriadb_disk_total_bytes` | gauge | `dir` | байт | размер этой файловой системы | `predict_linear(sectoriadb_disk_free_bytes{dir="data"}[1h], 24*3600) < 0` |

Значения gauge из п. 4.3-4.4 кэшируются на 15 секунд. Заполненность и малые объекты считаются по блобам, **загруженным в
память процесса**: кукушкин блоб загружается при первом обращении, а планировщик авторасширения (включён по умолчанию)
обходит все блобы раз в минуту, так что через минуту после старта это все блобы. Пока блоб не загружен, он не входит
в `slots_active/capacity`; `sectoriadb_cuckoo_capacity_bytes` и `sectoriadb_blobs` считаются по реестру блобов и
не зависят от загрузки.

### 5. Отказы аутентификации по причинам

Причина определяется в месте отказа и передаётся фильтру наблюдаемости через атрибут запроса
(`ObservabilityAttributes`); счётчик увеличивается один раз, когда запрос завершён.

| `reason` | Когда |
|---|---|
| `signature_mismatch` | подпись не совпала (заголовок или presigned URL); дата в области действия ключа не совпала с датой запроса |
| `clock_skew` | время запроса отличается от серверного более чем на 15 минут (или presigned URL подписан «из будущего») |
| `expired_presign` | истёк `X-Amz-Expires` |
| `unknown_key` | идентификатор ключа неизвестен |
| `disabled_key` | ключ существует, но выключен (`disable-key`) |
| `malformed_header` | не разобрать `Authorization`, параметры presign, `x-amz-date`/`Date`, область (`scope`) ключа, `x-amz-content-sha256` |
| `payload_hash_mismatch` | тело не соответствует подписанному `x-amz-content-sha256` (код `XAmzContentSHA256Mismatch`) |
| `chunk_signature_mismatch` | неверная подпись чанка или trailer в `aws-chunked` |
| `open_setup_denied` | ключей доступа нет, режим открытой настройки выключен: отказ всем |
| `anonymous_denied` | запрос без подписи к ресурсу, где анонимный доступ не разрешён |
| `unsigned_payload_denied` | дополнительно к списку из задания: `UNSIGNED-PAYLOAD` при `sectoriadb.s3.auth.allow-unsigned-payload=false` |

Отказ в авторизации (`AccessDenied` от политики или ACL у аутентифицированного пользователя) сюда **не** входит: он виден как
`sectoriadb_s3_errors_total{code="AccessDenied"}`.

### 6. Границы гистограмм и почему такие

Границы заданы явно в `org.example.sectoriadb.observability.Buckets`; везде 10-15 границ (плюс служебная `+Inf`).

| Гистограмма | Границы | Обоснование |
|---|---|---|
| задержка запроса, TTFB | 1, 2.5, 5, 10, 25, 50, 100, 250, 500 мс; 1, 2.5, 5, 10, 30, 60 с (15) | шаг примерно x2.5 покрывает чтение малого объекта из page cache (миллисекунды), запись с `fsync` (единицы-десятки мс), листинги и передачу крупных файлов (секунды, минута). 99-й перцентиль между соседними границами оценивается с погрешностью не более 2.5x, для SLO-порогов (100 мс, 1 с) есть точные границы |
| диск: чтение, запись | 10, 25, 50, 100, 250, 500 мкс; 1, 2.5, 5, 10, 25, 50, 100, 250 мс; 1 с (15) | попадание в page cache (десятки мкс) отличается от чтения с SSD (100-500 мкс) и HDD (5-25 мс); хвост до секунды ловит деградацию устройства |
| `fsync`, коммит метахранилища | 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 25, 50, 100, 250, 500 мс; 1, 2.5, 5 с (15) | NVMe с кэшем: 0.1-1 мс, SATA SSD: 1-5 мс, HDD: 5-30 мс; свыше 100 мс диск перегружен |
| ожидание блокировки | 10, 50, 100, 500 мкс; 1, 5, 10, 50, 100, 500 мс; 1, 5 с (12) | в норме блокировка свободна (десятки мкс); миллисекунды это очередь, секунды это писатель, застрявший на `fsync` |
| длительность расширения блоба | 0.1, 0.5, 1, 5, 10, 30, 60, 120, 300, 600, 1800 с (11) | пересборка блоба до гигабайтов занимает от секунд до десятков минут |
| паузы GC | 1, 5, 10, 25, 50, 100, 250, 500 мс; 1, 2, 5 с (11) | пауза G1 в норме единицы мс; больше сотни это повод для настройки куч |
| длина пути вытеснений | 0.5, 1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32 (14) | `max-evictions` по умолчанию 32. Мелкий шаг в начале: почти все вставки идут за 0-3 перемещения, рост хвоста сигнализирует о подходе к пределу заполнения; `0.5` это «без вытеснений» (Micrometer требует границы больше нуля) |

### 7. Счётчики объектов и размера без сканирования

Число объектов и суммарный размер ведутся в самой транзакции метахранилища. Новое дерево `stats` содержит один ключ
`totals` со значением `(objects u64, bytes u64)`. `commitObject`, `deleteObject` и `deleteManifest` меняют его в той же
транзакции, что и дерево `objects`, поэтому оно всегда согласовано с данными и переживает перезапуск. Метрика
читает один ключ (`ManifestRepository.totals()`). Для хранилища, созданного до этого этапа, дерево создаётся одним
подсчётом дерева `objects` внутри первой же транзакции записи, дальше пересчитываться не будет.
Размер считается логический (сумма `totalBytes` текущих версий), без учёта дедупликации; версии, ждущие сборщика
мусора, в него не входят.

### 8. Идентификатор запроса, MDC и лог медленных запросов

* `RequestObservabilityFilter` стоит снаружи `SigV4Filter` (порядок `HIGHEST_PRECEDENCE + 10`), поэтому видит и
  отказы аутентификации, и ответы контроллеров.
* Для каждого ответа задаются заголовки `x-amz-request-id` (16 символов hex в верхнем регистре, как у S3) и `x-amz-id-2`.
  Обёртка ответа **закрепляет** их: контроллеры по-прежнему вызывают `header("x-amz-request-id", ...)`, но эти вызовы
  игнорируются, так что в ответе ровно одно значение. Тело ошибки (`<RequestId>`) содержит тот же идентификатор
  (`S3Support.requestId()` берёт его из MDC).
* MDC: `requestId` и `operation`. Шаблоны logback (консоль и файл `sectoriadb.log`) содержат `[%X{requestId:--} %X{operation:--}]`:
  `22:43:36 WARN [o.e.s.o.SlowRequests] [A7F76D087C1E90AA PutObject] Slow request: ...`. Строки вне запроса
  (старт, планировщики) показывают `[- -]`.
* **Лог медленных запросов**: если запрос длился не меньше `sectoriadb.observability.slow-request-ms` (по умолчанию
  `2000`, `0` выключает), логгер `org.example.sectoriadb.observability.SlowRequests` пишет на уровне **WARN**:
  `Slow request: requestId=… operation=… status=… durationMs=… requestBytes=… responseBytes=…`. В строку не попадают ни
  имя бакета, ни ключ объекта, ни ключи доступа. Тот же запрос увеличивает `sectoriadb_s3_requests_slow_total`.
* Строка `Request done: ...` с методом и **путём (то есть бакетом и ключом)** пишется только на уровне **DEBUG**
  (логгер `RequestObservabilityFilter`).

### 9. Классификация операций

Единственное место: `OperationClassifier.classify(method, requestUri, queryString, hasCopySourceHeader)`. Она повторяет
маршрутизацию контроллеров (адресация path-style; побеждает самый специфичный под-ресурс запроса) и используется и для
метрик, и для MDC/логов. Читаются только **имена** параметров запроса (и значение `list-type`), тело, имя бакета и
ключ не используются. Тест `OperationClassifierTest` содержит таблицу «запрос → операция» для каждой операции, которую
обслуживают контроллеры, и проверяет, что каждая константа `S3Operation` достижима.

Список операций: `PutObject GetObject HeadObject DeleteObject DeleteObjects CopyObject ListObjects ListObjectsV2
ListBuckets CreateMultipartUpload UploadPart UploadPartCopy CompleteMultipartUpload AbortMultipartUpload ListParts
ListMultipartUploads CreateBucket HeadBucket DeleteBucket GetBucketLocation GetBucketVersioning GetBucketAcl
PutBucketAcl GetObjectAcl PutObjectAcl GetBucketPolicy PutBucketPolicy DeleteBucketPolicy GetBucketTagging
GetObjectTagging PutObjectTagging DeleteObjectTagging GetBucketCors GetBucketLifecycle GetBucketEncryption
GetPublicAccessBlock Other`. Всё неподдерживаемое (прочие методы, неизвестные под-ресурсы, `/favicon.ico`) попадает в `Other`.

### 10. Что значат цифры (оговорки)

* Время запроса измеряется внутри сервера: от входа в фильтр до момента, когда последний байт тела передан контейнеру
  (после чего вызывается `flushBuffer`). Время, которое клиент тратит на получение из сети, измеряется, пока Tomcat
  блокируется на записи в сокет (медленный клиент удлиняет запрос), но не включает установку соединения и
  ожидание в очереди `accept`.
* `request_bytes` считает сырые байты из соединения, то есть для `aws-chunked` вместе с заголовками чанков и подписями.
* TTFB это момент первой записи тела в ответ, а не отправки пакета в сеть.
* `sectoriadb_resize_seconds{result="failure"}` включает отказы проверки (новый размер слишком мал), а не только сбои ввода-вывода.
* Чтение страниц метахранилища (`target="metastore"`) проходит через page cache и происходит часто: его гистограмма
  самая «шумная» по `_count`.
* Метрики Tomcat отражают пул S3-порта (порт управления обслуживает отдельный Tomcat, его потоки сюда не входят).
* Spring-метрика `http_server_requests_*` выключена (`management.metrics.enable.http.server.requests=false`): её метка `uri`
  содержала бы шаблон контроллера, и она дублировала бы `sectoriadb_s3_requests_seconds`.

### 11. Конфигурация (сводка)

```properties
management.server.port=9464
management.endpoints.web.exposure.include=health,prometheus
management.endpoints.enabled-by-default=false
management.endpoint.health.enabled=true
management.endpoint.prometheus.enabled=true
management.metrics.tags.application=${spring.application.name}
server.tomcat.mbeanregistry.enabled=true
management.metrics.enable.http.server.requests=false
management.metrics.distribution.slo.jvm.gc.pause=1ms,5ms,10ms,25ms,50ms,100ms,250ms,500ms,1s,2s,5s
sectoriadb.observability.slow-request-ms=2000
sectoriadb.observability.gauge-cache-ms=15000
```

В Docker: `MANAGEMENT_SERVER_PORT`, `SECTORIADB_OBSERVABILITY_SLOW_REQUEST_MS` и т. д. (relaxed binding Spring Boot);
`docker-compose.yml` публикует порт 9464 только на `127.0.0.1` хоста.

### 12. Примеры PromQL

```promql
# p50 / p95 / p99 задержки по операциям
histogram_quantile(0.50, sum by (operation, le) (rate(sectoriadb_s3_requests_seconds_bucket[5m])))
histogram_quantile(0.95, sum by (operation, le) (rate(sectoriadb_s3_requests_seconds_bucket[5m])))
histogram_quantile(0.99, sum by (operation, le) (rate(sectoriadb_s3_requests_seconds_bucket[5m])))

# запросов в секунду по операциям (rate)
sum by (operation) (rate(sectoriadb_s3_requests_seconds_count[5m]))

# доля ошибок (4xx и 5xx) и отдельно доля 5xx по операциям
sum by (operation) (rate(sectoriadb_s3_requests_seconds_count{status_class=~"4xx|5xx"}[5m]))
  / sum by (operation) (rate(sectoriadb_s3_requests_seconds_count[5m]))
sum(rate(sectoriadb_s3_requests_seconds_count{status_class="5xx"}[5m]))
  / sum(rate(sectoriadb_s3_requests_seconds_count[5m]))

# ошибки по коду S3
sum by (code) (rate(sectoriadb_s3_errors_total[5m]))

# отказы аутентификации по причинам
sum by (reason) (rate(sectoriadb_s3_auth_failures_total[5m]))

# p99 fsync по типам файлов; p99 коммита метахранилища и ожидания писателя
histogram_quantile(0.99, sum by (target, le) (rate(sectoriadb_storage_fsync_seconds_bucket[5m])))
histogram_quantile(0.99, sum by (le) (rate(sectoriadb_metastore_commit_seconds_bucket[5m])))
histogram_quantile(0.99, sum by (le) (rate(sectoriadb_metastore_writer_lock_wait_seconds_bucket[5m])))

# p99 времени до первого байта GetObject
histogram_quantile(0.99, sum by (le) (rate(sectoriadb_s3_get_ttfb_seconds_bucket[5m])))

# заполненность слотов, %; доля занятой ёмкости
100 * sectoriadb_cuckoo_slots_active / sectoriadb_cuckoo_slots_capacity
sectoriadb_cuckoo_used_bytes / sectoriadb_cuckoo_capacity_bytes

# длина пути вытеснений p99 и вставки, отклонённые из-за переполнения
histogram_quantile(0.99, sum by (le) (rate(sectoriadb_cuckoo_eviction_path_length_bucket[5m])))
increase(sectoriadb_cuckoo_table_full_total[1h])

# свободное место диска, %, и прогноз: закончится ли место в данных за сутки
100 * sectoriadb_disk_free_bytes / sectoriadb_disk_total_bytes
predict_linear(sectoriadb_disk_free_bytes{dir="data"}[1h], 24 * 3600) < 0

# пропускная способность клиентов, байт/с
sum(rate(sectoriadb_s3_request_bytes_total[1m]))  # приём
sum(rate(sectoriadb_s3_response_bytes_total[1m])) # отдача

# насыщение: запросы в обработке, занятость потоков Tomcat, дескрипторы файлов
sum(sectoriadb_s3_requests_inflight)
tomcat_threads_busy_threads / tomcat_threads_config_max_threads
process_files_open_files / process_files_max_files

# целостность: любой прирост это инцидент
sum by (kind) (increase(sectoriadb_integrity_crc_failures_total[1h])) > 0
sectoriadb_cuckoo_slots_quarantined > 0
```

### 13. Файлы

| Файл | Назначение |
|---|---|
| `core/.../metrics/StorageMetrics.java`, `MetricsConfig.java` | SPI и пустая реализация |
| `core/.../service/impl/CuckooHashTable.java`, `FileChannelStorageIOEngine.java`, `SmallObjectBlob.java` | события вставки, IO, `fsync`, блокировок, CRC |
| `core/.../metastore/MetaStore.java`, `Pager.java`, `MetaStoreOptions.java` | ожидание писателя, коммит, `fsync`, CRC страниц |
| `core/.../service/ResizeService.java`, `FileStorageService.java` | расширение блоба, CRC объекта |
| `core/.../repository/metastore/Trees.java`, `MetaStoreManifestRepository.java` | дерево `stats` (число и размер объектов) |
| `server/.../observability/*` | операции, классификатор, фильтр, RED-метрики, адаптер Micrometer, gauge состояния, границы гистограмм |
| `server/.../s3/auth/AuthFailure.java`, `SigV4Filter.java` | причины отказов |
| `server/src/main/resources/application.properties`, `logback-spring.xml` | настройки |

## Стенд и бенчмарки

> Раздел для второй части этапа (стенд Prometheus + Grafana + InfluxDB, сценарии `warp`, тесты совместимости и отказов).
> Здесь должны появиться: схема стенда и `docker-compose`, конфигурация `scrape_config` (цель `sectoriadb:9464`,
> путь `/actuator/prometheus`), дашборды Grafana на метриках из каталога выше, сценарии `warp` (PUT/GET/mixed,
> малые и крупные объекты, multipart), результаты, пороги алертов и выводы.
