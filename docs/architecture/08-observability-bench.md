# 08. Наблюдаемость и бенчмарки

Этап состоит из двух частей:

* **A. Метрики** (раздел «Метрики» ниже): серверные метрики в формате Prometheus, идентификатор запроса, MDC и лог
  медленных запросов.
* **B. Стенд и бенчмарки**: Prometheus, Grafana, InfluxDB, `warp`, тесты совместимости и отказов (разделы «Стенд и
  бенчмарки», «Совместимость», «Отказы и безопасность», «Результаты прогонов в песочнице»).

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
| `sectoriadb_metastore_group_batch_size` | Г | нет | шт. | сколько тел записи (`submit`/`writeGrouped`) разделили один коммит. Граница `le="1"` это «без группировки». Среднее: `rate(..._sum) / rate(..._count)` | `histogram_quantile(0.99, sum by (le)(rate(sectoriadb_metastore_group_batch_size_bucket[5m])))` |
| `sectoriadb_metastore_group_batch_pages` | Г | нет | шт. | страниц, записанных одним групповым коммитом (предел `max-batch-pages`) | `histogram_quantile(0.5, sum by (le)(rate(sectoriadb_metastore_group_batch_pages_bucket[5m])))` |
| `sectoriadb_metastore_group_queue_wait_seconds` | Г | нет | с | ожидание тела записи в очереди группового коммита до начала выполнения (замена «ожидания писателя» для запросов: само ожидание слота теперь делает один коммиттер) | `histogram_quantile(0.99, sum by (le)(rate(sectoriadb_metastore_group_queue_wait_seconds_bucket[5m])))` |
| `sectoriadb_metastore_group_body_rollbacks_total` | счётчик | нет | шт. | тела, бросившие исключение и откатанные к точке сохранения без влияния на остальные тела пачки | `rate(sectoriadb_metastore_group_body_rollbacks_total[5m])` |
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
| `sectoriadb_metastore_last_txid` | gauge | нет | номер | последняя зафиксированная транзакция | коммитов в секунду: `sum(rate(sectoriadb_metastore_commit_seconds_count[1m]))`; сам `last_txid` кэшируется на 15 с и растёт ступенями, `rate()` по нему даёт неточный результат на коротких окнах |
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

Часть B этапа: стенд наблюдаемости, нагрузочные сценарии `warp`, проверки совместимости с S3 и проверки отказов и
безопасности. Все файлы лежат в каталоге [`bench/`](../../bench/README.md); здесь описаны устройство стенда, методика
и результаты, снятые в песочнице (см. оговорку о железе в разделе «Результаты прогонов в песочнице»).

### 14. Схема стенда

```
                                    ┌──────────── bench/observability/docker-compose.yml (сеть sectoria-bench) ───────────┐
 warp (minio/warp) ── S3 :8080 ───► │ sectoriadb ──:9464/actuator/prometheus──► prometheus ──► grafana ◄── influxdb       │
   │                                │      ▲                                        ▲   (PromQL)    │ (Flux)     ▲          │
   │  --influxdb (живые итоги)      │      └── диск, JVM, Tomcat                    │               │            │          │
   └────────────────────────────────┼──────────────────────────────────────────────┼───────────────┘            │          │
                                    │ node_exporter ───:9100────────────────────────┘ (хост: диск, CPU, сеть)    │          │
                                    └──────────────────────────────────────────────────────────────────────────────────────┘
 run.sh ── HTTP API Grafana ──► аннотации «начало/конец прогона» (теги mode, size, conc, commit)
 run.sh ── bench/results/<прогон>/ ──► warp.json.zst, analyze.txt, meta.json  (локальные файлы)
```

Хранилищ ровно три, по одному на вид данных:

| Компонент | Что хранит | Почему именно он |
|---|---|---|
| **Prometheus** | метрики сервера (глава «Метрики») и хоста (node_exporter), опрос раз в 5 с, хранение 30 дней / 5 ГБ | pull-модель, `histogram_quantile` по гистограммам сервера, один источник правды о том, что видит сервер |
| **InfluxDB v2** | живые итоги клиента warp (`--influxdb`): число запросов, байты, ошибки, суммарное время запросов за секунду | единственный формат, в котором warp умеет стримить результаты; даёт клиентскую картину, независимую от сервера |
| **Grafana** | ничего (дашборды и источники данных описаны в файлах и подключаются при старте) | один экран: серверная и клиентская стороны рядом, аннотации прогонов поверх графиков |

Не используются Loki, GitLab и другие хранилища. Логи сервера содержат идентификатор запроса и операцию (MDC), их можно
смотреть `docker logs`; Loki для логов остаётся необязательным улучшением на будущее.

Версии (зафиксированы digest-ами в `docker-compose.yml` и `bench/warp/lib.sh`, это те версии, на которых сняты результаты):
Prometheus 3.15.0, Grafana 13.2.3, InfluxDB 2.9.1, node_exporter 1.12.1, warp 1.3.1, minio/mint `edge`.

#### 14.1. Запуск

```bash
cd bench/observability
cp .env.example .env     # пароли Grafana и InfluxDB, токен InfluxDB, ключи доступа SectoriaDB (два аккаунта)
docker compose up -d
```

* `sectoriadb` (профиль `server`, включён по умолчанию через `COMPOSE_PROFILES=server`) собирается из корневого
  `Dockerfile`. Два аккаунта (`main` и `alt`) нужны тестам ceph/s3-tests. Журналы приглушены до `INFO`/`WARN`: уровень
  `DEBUG` для `org.springframework.web` из `application.properties` на нагрузке съедает заметную долю CPU.
  Чтобы направить стенд на сервер, запущенный где-то ещё, уберите профиль (`COMPOSE_PROFILES=`) и поправьте `targets` в
  `prometheus/prometheus.yml`.
* Prometheus опрашивает `sectoriadb:9464/actuator/prometheus` и `node-exporter:9100` каждые 5 с (`scrape_timeout` 4 с),
  собственные метрики Prometheus раз в 15 с. 5 секунд нужны, чтобы короткие прогоны (десятки секунд) оставили на
  графиках больше одной точки; для долгих наблюдений значение можно поднять до 15 с.
* Порты Prometheus (9090), Grafana (3000), InfluxDB (8086) и метрик SectoriaDB (9464) опубликованы только на
  `127.0.0.1` хоста; порт S3 `8080` на всех интерфейсах, как в основном `docker-compose.yml`.
* InfluxDB настраивается при первом старте переменными `DOCKER_INFLUXDB_INIT_*`: организация `sectoria`, корзина `warp`
  (хранение 90 дней), токен администратора из `.env`. Grafana получает источники данных `Prometheus` и
  `InfluxDB-warp` (Flux) из `grafana/provisioning/datasources/`, дашборды из `grafana/dashboards/*.json`.
* node_exporter читает хост через `/:/host:ro` и `pid: host`. На хостах с общим корневым монтированием можно использовать
  `/:/host:ro,rslave`, тогда видны вложенные монтирования.

#### 14.2. Дашборды Grafana

Папка **SectoriaDB**. JSON-файлы порождает скрипт `bench/observability/grafana/gen_dashboards.py` (его и нужно
править, затем `python3 -I gen_dashboards.py`); пересборка не требует Grafana.

**«SectoriaDB — server»** (Prometheus; переменная `operation` фильтрует графики по операциям):

| Ряд | Содержимое |
|---|---|
| Overview | запросов/с, доля ошибок, 5xx/с, p99 по всем операциям, запросов в обработке, заполнение кукушки |
| Rate / Errors / Duration | запросов/с по операциям; p50, p95, p99 по операциям `histogram_quantile(q, sum by (le, operation) (rate(sectoriadb_s3_requests_seconds_bucket[$__rate_interval])))`; доля ошибок по классу статуса; ошибки по коду S3 и операции; отказы аутентификации по причинам; в обработке; байты in/out; TTFB GET; медленные и оборванные запросы |
| Saturation | p99 `fsync` по целям; задержки чтения/записи диска движка; пропускная способность движка; ожидание блокировки писателя метахранилища и p99 коммита; коммитов/с; ожидания блокировок малых объектов и кукушки; потоки и соединения Tomcat; паузы GC; куча; CPU; файловые дескрипторы; потоки JVM и скорость аллокаций |
| Engine internals | длина пути вытеснений (p50, p99), доля вставок без вытеснений; `table full`, rekeys, dedup; CRC-сбои по видам (любой > 0 это инцидент); слоты в карантине; очередь GC; расширения блоба; слоты; блобы |
| Capacity | заполнение кукушки %; свободное место диска %; прогноз «часов до заполнения диска»; объектов; логические и занятые байты; живые и мёртвые байты малых объектов; размер и страницы метахранилища |
| Host (node_exporter) | CPU по режимам, загрузка диска (`io_time`, аналог `%util`), задержка диска (await чтения/записи/flush), пропускная способность и IOPS, сеть, память, load |

Аннотации прогонов `warp` (тег `warp`) показываются поверх всех графиков обоих дашбордов.

**«warp — client (InfluxDB)»** (Flux). Что именно пишет warp 1.3.1 (проверено запросом к InfluxDB после прогона):

| Измерение | Теги | Поля | Смысл |
|---|---|---|---|
| `warp` | `op`, `endpoint`, `warp_id` | `requests`, `objects`, `bytes_total`, `errors`, `request_total_secs`, `request_ttfb_total_secs` | **нарастающие итоги** с начала прогона, точка раз в секунду |
| `warp_run_summary` | `op`, `warp_id` | то же + `request_avg_secs`, `request_min_secs`, `request_max_secs`, `request_ttfb_*` | одна точка в конце прогона |

Следствия для запросов: скорости получаются как `derivative(unit: 1s, nonNegative: true)` по нарастающему полю; средняя
задержка интервала равна `Δrequest_total_secs / Δrequests` (`difference` + `pivot`); **перцентилей и максимума в
InfluxDB нет** (максимум и минимум только в итоговой точке `warp_run_summary`), поэтому p99 берётся из `warp analyze`
(файл `analyze.txt` результата) и из серверных гистограмм. Панели: операций/с, пропускная способность, ошибок/с, средняя
задержка и средний TTFB по интервалам, нарастающие итоги, таблица итогов прогонов. Переменные `op` и `warp_id`
позволяют выбрать один прогон.

Проверка дашбордов: `bench/observability/check_dashboards.py` загружает каждый дашборд через API Grafana и выполняет
запрос каждой панели через `/api/ds/query` (переменные заменяются на «все»); результат `OK` / `EMPTY` / `ERROR` по панелям
(пустыми бывают только панели событий, которых не было, например `5xx/с` на здоровой системе). Результат в разделе
«Результаты прогонов».

#### 14.3. Сценарии warp (`bench/warp/`)

| Файл | Назначение |
|---|---|
| `run.sh` | один прогон: подготовка, нагрузка, сохранение результатов, аннотации |
| `matrix.sh` | матрица «режим x размер x параллелизм», строго последовательно, сводка в `matrix_<UTC>.tsv` |
| `compare.sh` | обёртка над `warp cmp до после`, печатает коммит и железо обоих прогонов |
| `preload.sh` | одноразовая загрузка большого набора данных и прогоны с `--list-existing` |
| `validate_metrics.py` | сверка итогов warp с счётчиками Prometheus за то же окно |
| `lib.sh`, `meta.py` | общие функции; сбор `meta.json` |

Режимы: `get put mixed delete list stat multipart multipart-put`. Классы размеров (`--size`):

| Класс | Флаги warp | Примечание |
|---|---|---|
| `small` | `--obj.size 64KiB --obj.randsize` | warp 1.3.1 **не имеет** синтаксиса «от и до» или взвешенных размеров: `--obj.randsize` даёт случайный размер от 1 байта до заданного (в среднем около 12 КиБ). Это покрывает и путь малых объектов (< `default-chunk-size` = 16 КиБ, файлы `.sob`), и путь чанков. Диапазон 4-64 КиБ точно получить нельзя |
| `s4k`, `s64k` | `--obj.size 4KiB` / `64KiB` | фиксированные размеры |
| `medium` | `--obj.size 1MiB` | |
| `large` | `--obj.size 16MiB` | |
| `<N>KiB`, `<N>MiB` | `--obj.size <N>...` | любой фиксированный размер |

Режимы `multipart` и `multipart-put` используют `--part-size` (по умолчанию `5MiB`) и `--parts` (по умолчанию 20) вместо размера
объекта. Параллелизм `--conc` (по умолчанию 20; в матрице шаги 20, 50, 100, настраиваются `CONCS`), длительность
`--duration` (по умолчанию 30 с: это проверка стенда, а не измерение; для измерений от 5 минут, для дрейфа часы),
`--objects` (число подготовленных объектов, по умолчанию зависит от размера).

* **Свой бакет на каждый прогон:** `warp-bench-<режим>-<размер>-c<N>-<ЧЧММСС>`. warp **стирает объекты** бакета перед
  прогоном и после него, поэтому использовать чужой бакет нельзя. Для данных, которые стирать нельзя, есть
  `--list-existing` (`preload.sh run`). Сам бакет warp не удаляет, а у SectoriaDB каждый бакет держит свои файлы блобов и
  загруженные в память таблицы (около 9 МиБ кучи на блоб) до удаления бакета: поэтому `run.sh` удаляет созданный им бакет
  по окончании (`--keep-bucket` отключает). Без этого длинная матрица исчерпывает кучу сервера (п. 24).
* `--influx` включает `--influxdb http://<токен>@influxdb:8086/warp/sectoria` (токен берётся из `.env`; в `meta.json`
  он заменяется на `***`).
* `--autoterm` останавливает прогон, когда warp считает скорость установившейся. Такие прогоны разной длины
  сравнивайте **по медианам** (`50% Median` в `analyze.txt`), а не по среднему.
* Код возврата `3` значит, что warp сообщил об ошибках запросов: прогон помечается `error_free: false` в `meta.json`.
* Расположение результатов и поля `meta.json`: [bench/results/README.md](../../bench/results/README.md).

Данные warp 1.3.1 хранятся в `warp.json.zst` (а не `.csv.zst`, как в более ранних версиях); `warp analyze`, `warp cmp` и
`warp merge` читают его одинаково.

#### 14.4. Методика

Правила, без которых цифры бессмысленны:

1. **Смотреть p99 и максимум, а не среднее.** Среднее скрывает хвост; пользователь видит самые медленные запросы.
   Источники: `warp analyze` (p50/p90/p99 по размерам запросов и TTFB), серверные гистограммы (`histogram_quantile`).
   Границы гистограмм сервера (п. 6) ограничивают разрешение: значение выше последней границы показывается как граница.
2. **Только прогоны без ошибок.** Прогон с ошибками (`error_free: false`, код 3) измеряет не то: упавшие запросы быстрые
   и «улучшают» задержки. Такие прогоны не сравниваются и не публикуются.
3. **Менять одну переменную за раз** (версия, `-Xmx`, `fsync`, размер чанка, число потоков Tomcat, параллелизм). Остальное
   неизменно, включая версию Docker-образа и состояние диска.
4. **Записывать коммит, конфигурацию и железо.** Это делает `meta.json` (git sha и признак `dirty`, переменные окружения
   сервера, `application.properties`, версия JVM, CPU, RAM, ядро, тип диска). Прогон без этих данных нельзя сравнивать.
5. **Холодный и тёплый кэш.** Первый `get` после перезапуска читает с диска, следующие из кэша страниц ОС. Для холодного
   измерения: перезапустить сервер и сбросить кэш (`sync; echo 3 > /proc/sys/vm/drop_caches` на хосте), для тёплого:
   прогнать данные заранее. Указывайте в отчёте, какой режим измерен. Если набор данных помещается в RAM хоста, `get`
   измеряет не диск, а память и CPU.
6. **Поведение при заполнении 70-90 %.** Кукушкина таблица замедляется по мере заполнения (удлиняются пути вытеснений:
   панель «Cuckoo eviction path length»), а у `table full` вставки отклоняются. Измеряйте при 10 %, 50 %, 70 %, 80 %, 90 %
   (`preload.sh load` с нужным числом объектов, затем `preload.sh run`). Кривая задержки от заполнения важнее одного числа.
7. **Долгие прогоны для дрейфа.** Часы, а не минуты: рост очереди GC, расширения блобов, фрагментация малых объектов
   (`small dead bytes`), рост метахранилища, паузы GC. Признак дрейфа: p99 или `fsync` растут на графике при постоянной
   нагрузке. Для долгих прогонов `--autoterm` не используйте: нужен именно фиксированный интервал, а не остановка по стабилизации.
8. **Колено.** Увеличивайте параллелизм (20, 50, 100, 200...), пока задержка не начнёт расти быстрее пропускной
   способности: до колена добавление клиентов даёт скорость, после него только очередь (время ожидания писателя
   метахранилища и занятость потоков Tomcat растут, `fsync` p99 тоже). Рабочая точка ниже колена.
9. **Распределённый режим.** Один клиент warp часто упирается в себя (CPU, сеть, 1 GbE). Запуск `warp client` на нескольких
   машинах и `--warp-client host1,host2` на управляющей; на всех машинах должно быть синхронизировано время (NTP/chrony,
   расхождение под 100 мс), иначе итоги интервалов не складываются. `--syncstart hh:mm` задаёт общий старт. Каждый клиент
   создаёт свою нагрузку `--concurrent` потоков; не забудьте `--noclear` при ручном запуске нескольких клиентов.
10. **Хост не должен быть занят.** Не запускайте две нагрузки одновременно (в том числе `mvn test` или mint рядом с
    замером). Следите по дашборду «Host», что диск не используется чем-то ещё.
11. **Сверять сервер с клиентом.** Скорость по warp и по `rate(sectoriadb_s3_requests_seconds_count)` должны совпасть;
    расхождение больше примерно 5 % значит, что врут метрики, клиент или есть сетевые потери (`validate_metrics.py`).

Большие наборы: `preload.sh`. Порядок для 1 ТБ (в песочнице не выполнялся):

```bash
# 1 ТБ = 1 000 000 объектов по 1 МиБ; 10 загрузок по 100 000, каждая со своим префиксом
for i in $(seq 1 10); do bench/warp/preload.sh load --bucket warp-1tb --size medium --objects 100000 --conc 50 --prefix part$i; done
# прогоны на готовых данных (без подготовки и без стирания):
bench/warp/preload.sh run --bucket warp-1tb --size medium --mode get --conc 50 --duration 30m
```

Во время загрузки следите за панелью Capacity (заполнение кукушки, свободное место диска, размер метахранилища); измерения
повторяйте при 70, 80 и 90 % заполнения. Нужны место на диске с запасом под файлы блобов (разреженные файлы по 8 ГиБ
по умолчанию) и время: при 100-500 МиБ/с загрузка занимает 1-3 часа. Удаление объектов **не возвращает место на диске**
до этапа `10-gc-and-resize`, поэтому между сериями измерений каталог данных лучше пересоздавать.

#### 14.5. Пороги алертов

Правила лежат в `bench/observability/prometheus/alerts.yml` (20 правил, проверены `promtool check rules`, Prometheus
вычисляет их и показывает на `/alerts`; доставка требует Alertmanager, которого в стенде нет). Пороги предложены до
измерений на целевом железе: после первых долгих прогонов их нужно подправить.

| Группа | Правило | Условие | Серьёзность |
|---|---|---|---|
| Целостность | `SectoriaIntegrityFailure` | любой прирост `integrity_crc_failures_total` за 10 мин | critical |
| | `SectoriaQuarantinedSlots` | `slots_quarantined > 0` | critical |
| | `SectoriaTableFull` | прирост `cuckoo_table_full_total` | critical |
| Доступность | `SectoriaDown` | `up == 0` 1 мин | critical |
| | `SectoriaServerErrors` | доля 5xx > 1 % 5 мин | critical |
| | `SectoriaAbortedRequests` | оборванные ответы за 10 мин | warning |
| Задержка и насыщение | `SectoriaPutLatencyHigh` | p99 `PutObject` > 1 с 10 мин | warning |
| | `SectoriaGetTtfbHigh` | p99 TTFB GET > 500 мс | warning |
| | `SectoriaFsyncSlow` | p99 `fsync` > 100 мс | warning |
| | `SectoriaWriterQueue` | p99 ожидания писателя метахранилища > 1 с | warning |
| | `SectoriaTomcatThreadsBusy` | занято > 90 % потоков | warning |
| | `SectoriaFileDescriptors` | > 80 % дескрипторов | warning |
| | `SectoriaGcPauses` | p99 паузы GC > 500 мс | warning |
| Ёмкость | `SectoriaFillHigh` / `SectoriaFillCritical` | заполнение кукушки > 80 % / > 90 % | warning / critical |
| | `SectoriaDiskFreeLow` | свободно < 15 % | warning |
| | `SectoriaDiskFullSoon` | `predict_linear` по 6 ч: диск данных закончится за 24 ч | warning |
| | `SectoriaGcQueueGrowing` | очередь GC > 100 000 и растёт час | warning |
| Безопасность | `SectoriaAuthFailureBurst` | > 5 отказов/с по `signature_mismatch`, `unknown_key`, `disabled_key` | warning |
| | `SectoriaClockSkew` | > 10 `RequestTimeTooSkewed` за 15 мин | warning |

Опорные цифры для выбора порогов (песочница, раздел «Результаты прогонов»): `fsync` p99 около 4 мс, коммит p99 6 мс,
ожидание писателя p99 около 0,5 с при 50 потоках записи на 4 ядрах. В песочнице сработало бы `SectoriaDiskFreeLow`:
диск хоста почти заполнен.

## Совместимость

Два независимых набора тестов запускаются скриптами из `bench/compat/` против работающего SectoriaDB. Цель не «зелёная
таблица», а поиск настоящих ошибок: у каждого красного теста выяснялась причина, мелкие ошибки исправлены (с регрессионным
тестом), крупные записаны в списки ниже.

### 15. ceph/s3-tests

`bench/compat/s3tests.sh` клонирует `ceph/s3-tests` (коммит `5522d1c`), создаёт virtualenv, генерирует `s3tests.conf` из
шаблона (`s3tests.conf.template`) с **двумя аккаунтами**, `main` и `alt` (в SectoriaDB это два обычных ключа:
`SECTORIADB_S3_CREDENTIALS=AK1:SK1,AK2:SK2`; IAM, STS и SNS нет, поэтому разделы `[iam*]` указывают на те же ключи), и
запускает `pytest` на `s3tests/functional/test_s3.py` и `test_headers.py` с таймаутом 60 с на тест.

Заранее отсеяны по маркерам (`-m`) тесты возможностей, которых в SectoriaDB нет сознательно: шифрование, теги и
жизненный цикл объектов, IAM/STS/SNS, S3 Select, веб-сайты, журналирование, облачные переходы, классы хранения,
checksum-режимы AWS, SigV2. Из 886 собранных тестов запущено **422**; остальные 464 в таблице не участвуют.

**Результат** (20261007, сервер на коммите этой ветки; 3 минуты на сервере после матрицы warp; предыдущие прогоны на сервере с накопленными бакетами и блобами занимали 15-22 минуты, причину не выясняли):

| | Тестов | Доля |
|---|---:|---:|
| Пройдено | 205 | 49 % |
| Не пройдено | 217 | |
| Запущено | 422 | |

Основные причины отказов (непройденные тесты сгруппированы по теме; группы не пересекаются):

| Группа | Тестов | Причина |
|---|---:|---|
| versioning | 37 | версионирования нет (`PutBucketVersioning` отвечает 501) |
| object lock / retention / legal hold | 38 | блокировок объектов нет (501) |
| ACL, grants, access by canned ACL (no owner model) | 41 | нет модели владельца: канонические ACL и гранты ведут себя не как в S3, любой ключ видит всё |
| POST Object (browser form) | 25 | загрузка HTML-формой (`POST /bucket` с политикой) не реализована |
| bucket policy, public access block | 16 | условия политик (`NotPrincipal`, `IfExists`, тенанты), `PutPublicAccessBlock`, `policyStatus` не реализованы |
| CORS | 12 | `PutBucketCors` не реализован (501) |
| multipart details | 13 | идемпотентность `Complete`, `partNumber`, `EntityTooSmall`, `Initiator`, копирование с диапазоном |
| presigned URL / x-amz-expires / expired | 7 | проверка диапазона `X-Amz-Expires`, коды отказов |
| ownership controls | 7 | `BucketOwnershipControls` не реализованы (501) |
| conditional writes (If-Match/If-None-Match on PUT/Copy) | 5 | условные PUT и Copy не реализованы |
| GetObjectAttributes / torrent | 3 | не реализованы (501) |
| other | 13 | разное, см. ниже |

Остальные (13 «прочих») это: `Expires` не сохраняется и не возвращается; не-ASCII значения пользовательских метаданных дают
`SignatureDoesNotMatch` (заголовок канонизируется не по тем байтам); `Content-Encoding: aws-chunked` в списке кодировок;
`GetBucketLocation` для `us-east-1` отдаёт пустое значение (так и должно быть для AWS, тест рассчитан на RGW);
`ListBuckets` без постраничности (`max-buckets`, `continuation-token`) и с чужими бакетами других тестов; `100-continue`
получает `100` раньше решения об аутентификации; `ListObjects` с «нечитаемым» префиксом.

#### Исправлено по итогам s3-tests (с регрессионными тестами в `S3ApiRegressionTest`)

* Приёмка и очистка: `ListObjectVersions` для бакета без версионирования возвращал пустой листинг, поэтому «очистка»
  бакета в SDK и в самих тестах не находила объекты, а все последующие тесты падали в `BucketNotEmpty`. Теперь объекты
  перечисляются как версия `null` (`GET /bucket?versions`, с `key-marker` и разделителем).
* `DeleteObjects`: ключ из одних пробелов (`" "`, `"_ "`) обрезался до пустого и не удалялся; `<Quiet>true</Quiet>`
  игнорировался; больше 1000 ключей принимались.
* Листинг: `max-keys=0` возвращал всё (должен пустую страницу); в ответ V1 не попадали `Marker` и `Owner`, в ответ V2
  `StartAfter`; не было `encoding-type=url`; недопустимые `max-keys`, `list-type`, `encoding-type` давали 500.
* Ошибки Spring MVC (405, 400 на неверном параметре, 415, 404 на неизвестный путь) превращались в `500 InternalError`
  или в JSON страницу Spring; теперь это S3 XML: `MethodNotAllowed`, `InvalidArgument`, `UnsupportedMediaType`, `NotFound`.
  Добавлен `S3ErrorController` (путь `/.s3-error`, не `/error`: это допустимое имя бакета).
* Имена бакетов: код `InvalidBucketName` вместо `InvalidArgument`.
* Неподдерживаемые подресурсы `GET ?object-lock|?ownershipControls|?website|?logging|?notification|?policyStatus` у
  бакета и `?attributes|?retention|?legal-hold|?torrent` у объекта отвечали 200 листингом бакета или **байтами объекта**;
  теперь `501 NotImplemented`.
* Пустой или неразборчивый XML в `CompleteMultipartUpload` давал 500, теперь `400 MalformedXML`.

#### Опасные ошибки, найденные mint (п. 16) и исправленные

* **`DELETE /bucket?tagging` (а также `?lifecycle`, `?cors`, `?encryption`, ...) удалял пустой бакет**, `DELETE
  /bucket/key?retention` удалял объект, `PUT /bucket/key?retention` или `?uploadId=...` без `partNumber` перезаписывал
  объект телом запроса. Запросы с подресурсом, который никто не обрабатывает, теперь отвечают `501`. Разрешены `x-id`
  (его добавляют AWS SDK для JavaScript и Go ко всем запросам) и параметры подписи presigned-URL (`X-Amz-*`).
* `GET`/`HEAD` с `?partNumber=N` для multipart-объекта возвращали **весь** объект с кодом 200 (warp в режиме `multipart`
  принимал его за часть: 204 «unexpected download size»). Манифест не хранит границы частей, поэтому теперь `501`; для
  обычного объекта допустим только `partNumber=1`, иначе `416 InvalidPartNumber`.
* Multipart-загрузка теряла пользовательские метаданные (`x-amz-meta-*`) и `Cache-Control`, `Content-Disposition`,
  `Content-Encoding`, `Content-Language`, заданные при `CreateMultipartUpload` (теперь сохраняются и применяются при
  завершении). Находка minio-py `test_put_object` (объекты больше 5 МиБ).

#### Не исправлено (кандидаты на этапы 09-11)

| Проблема | Свидетельство | Этап |
|---|---|---|
| Повторный `CompleteMultipartUpload` не идемпотентен: второй вызов получает `NoSuchUpload` (S3 вернёт тот же результат). SDK повторяет `Complete` при обрыве ответа и получит ошибку, хотя объект создан | `test_multipart_upload`, `test_multipart_upload_small` | 10 |
| `partNumber` для multipart-объектов не поддерживается (границы частей не сохраняются в манифесте), режим `warp multipart` не работает | warp: 204 ошибки `unexpected download size`; `test_multipart_get_part` | 09 |
| Нет модели владельца и ACL: `ListBuckets` и доступ видят все ключи, канонические ACL (`public-read`, `authenticated-read`, `bucket-owner-*`) и гранты не соответствуют S3; бакет «чужого» аккаунта доступен | 41 тест ACL, раздел «Отказы и безопасность» | 02 (дополнение) |
| Версионирование, блокировки объектов, CORS, public access block, ownership controls, `GetObjectAttributes`, условные записи (`If-Match` / `If-None-Match` в PUT и Copy), POST-форма | 37 + 38 + 12 + 16 + 7 + 5 + 3 + 25 тестов | вне плана |
| `EntityTooSmall` не проверяется (часть меньше 5 МиБ, не последняя, принимается); в `ListMultipartUploads` нет `Initiator` / `Owner`; метаданные больше 2 КБ принимаются | `test_multipart_upload_size_too_small`, `test_list_multipart_upload_owner` | |
| `x-amz-storage-class` не возвращается в `HeadObject` | mint: `awscli`, `mc` | |
| Presigned URL: проверка диапазона `X-Amz-Expires` и коды ошибок отличаются от S3 | 7 тестов | 02 |

### 16. minio/mint

`bench/compat/mint.sh` запускает образ `minio/mint:edge` (по digest) в режиме `core` по одному набору за раз с
таймаутом, против `sectoriadb:8080` внутри сети стенда. Сводка `bench/compat/work/mint-*/summary.txt`
строится из `log.json` каждого набора. Mint у части инструментов (awscli, mc, aws-sdk-go-v2) прекращает набор на первой
ошибке, поэтому числа PASS это нижняя граница.

| Набор | PASS | FAIL | NA |
|---|---:|---:|---:|
| aws-sdk-go-v2 | 3 | 1 | 0 |
| aws-sdk-java-v2 | 0 | 0 | 0 |
| aws-sdk-php | 10 | 1 | 0 |
| aws-sdk-ruby | 12 | 1 | 0 |
| awscli | 8 | 1 | 0 |
| mc | 8 | 1 | 0 |
| minio-go | 1 | 1 | 0 |
| minio-java | 31 | 3 | 28 |
| minio-py | 10 | 1 | 2 |
| s3cmd | 8 | 0 | 0 |
| minio-js | таймаут 7 мин (в предыдущем прогоне) | | |
| **Итого** | **91** | **10** | **30** |

Разбор отказов:

* **`minio-go`: `MakeBucket ... Access Denied`**: тест `testFunctionalV2` использует подпись SigV2, которой в SectoriaDB нет
  (только SigV4); отказ ожидаемый. Остальные тесты набора проходят.
* **awscli, mc: класс хранения** (`StorageClass was not applied`): `x-amz-storage-class` принимается, но не возвращается.
* **aws-sdk-go-v2 `PresignedPut`**: SDK сообщает `unmarshalling xml failed: EOF`; присланный через boto3 presigned PUT
  работает, причина в особенностях Go SDK не выяснена.
* **aws-sdk-ruby `presignedPost`**, **minio-java `getPresignedPostFormData`**: POST-форма с подписью не поддерживается
  (25 тестов s3-tests).
* **aws-sdk-php `uploadPart`**: тест в конце вызывает `AbortMultipartUpload` для загрузки, которой уже нет, и получает
  `404 NoSuchUpload` (S3 для уже завершённой или прерванной загрузки тоже отвечает `NoSuchUpload`, поэтому, вероятно, дело в
  порядке вызовов теста; не расследовано).
* **minio-java `listenBucketNotification`, `selectObjectContent`** и 28 пропущенных (NA) тестов: уведомления, S3 Select,
  шифрование, теги, блокировки, версионирование (нет в SectoriaDB).
* **minio-py `test_select_object_content`**: S3 Select.
* **minio-js**: набор не завершился за 7 минут (таймаут; зависание не расследовано).
* **aws-sdk-java-v2** выполнился без записей в журнале: набор не сообщил ни одного результата (не расследовано).

## Отказы и безопасность

Скрипты лежат в `bench/faults/`; каждый печатает `PASS` / `FAIL` / `INFO` и возвращает ненулевой код при `FAIL`. Все они
поднимают собственный контейнер `sectoriadb-fault` (порт 8090, каталоги данных в `bench/faults/work/`), поэтому стенд
на 8080 не затрагивают. `bench/faults/run-all.sh` запускает всё подряд.

Проверка целостности после отказа выполняется **офлайн**: контейнер останавливается и запускается
`java -jar sectoriadb.jar verify-all` (CLI-режим: без веб-сервера, JVM завершается сам; метахранилище однопроцессное,
поэтому сервер при этом должен быть остановлен). Вывод: число проверенных объектов и `RESULT: OK` или `RESULT: FAILED`.
Для этого `verify` и `verify-all` добавлены в список CLI-команд `Application` (раньше они работали только в интерактивной
оболочке).

### 17. Отказы

| Скрипт | Что делает | Ожидание | Результат в песочнице |
|---|---|---|---|
| `kill-put.sh` | `kill -9` контейнера во время `PutObject` объекта 800 МиБ, три раза: когда на диск записано 10 %, 50 %, 90 % | объект отсутствует или цел; повторный PUT работает; `verify-all` чист; счётчик CRC-сбоев 0 | 10 PASS, 0 FAIL. При 10, 50 и 90 % записи в блоб объект после перезапуска отсутствует, повторный PUT проходит и читается; счётчик CRC-сбоев 0; `verify-all`: `RESULT: OK` |
| `kill-multipart.sh` | (а) `kill -9` между частями multipart; (б) `kill -9` во время `CompleteMultipartUpload` | загрузка видна после перезапуска и достраивается (а); объект отсутствует или цел, загрузка не потеряна (б); `verify-all` чист | 5 PASS, 0 FAIL. (а) загрузка сохранилась и видна в `ListMultipartUploads`, 5 загруженных частей на месте, докачка и `Complete` дают целый объект (SHA-256 совпал); (б) объект отсутствует, загрузка осталась и может быть завершена; `verify-all`: `RESULT: OK` |
| `full-disk.sh` | каталог данных (или метаданных, `TARGET=meta`) на ext4-образе 64 МиБ через loop-устройство; PUT пока не кончится место | чистая 5xx-ошибка без зависания и падения; прочитанное ранее цело; после освобождения места PUT снова работает; `verify-all` чист | каталог данных: 7 PASS, 0 FAIL (507, см. ниже); каталог метаданных: 6 PASS, 1 FAIL (чтение недоступно до перезапуска, см. ниже и п. 24) |
| `corrupt-chunk.sh` | при остановленном сервере переворачивает байты в чанке большого объекта и в записи малого | `verify-all` находит оба; GET не отдаёт испорченные байты; счётчик `integrity_crc_failures_total` растёт | 5 PASS, 0 FAIL: `verify-all` выдаёт `CORRUPT` для всех трёх объектов, GET `500`, `integrity_crc_failures_total` = 6 |
| `network-faults.sh` | `tc netem` (если ядро позволяет) и userspace-прокси `slowproxy.py`: обрыв соединения посреди PUT, задержка и ограничение скорости | частичного объекта нет, запрос не «висит» в `inflight`, медленная линия не портит данные | 4 PASS, 0 FAIL; `tc netem` недоступен (ядро без `sch_netem`) |
| `security.sh` / `security.py` | проверки безопасности (п. 18) | | 69 PASS, 0 FAIL, 7 INFO (поведение «по замыслу»: нет IAM, метаданные 4 КиБ, медленные тела) |

**Окна отказа при PUT.** Тело запроса сначала сохраняется во временный файл (контрольные суммы проверяются до того,
как что-либо попадёт в блоб), затем чанки пишутся в блоб, затем **одна** транзакция метахранилища делает объект видимым.
Поэтому:

1. отказ при приёме тела: ничего не записано;
2. отказ при записи чанков: в блобе остаются чанки, на которые не ссылается ни один манифест. Они безвредны (объекта нет,
   чтение не затронуто), но занимают место до сборщика мусора (этап `10-gc-and-resize`); так же ведёт себя отказ между
   записью и коммитом;
3. отказ во время коммита: метахранилище копирует страницы при записи и переключает мета-страницу: транзакция либо
   зафиксирована целиком, либо её нет.

Все три `kill -9` при записи 800 МиБ (10, 50, 90 %) пришлись на окно 2: после перезапуска объекта нет, повторный PUT проходит,
`verify-all` чист.

**Полный диск.** Подробности для каталога данных (ext4 на 64 МиБ, свободно 15 МиБ, объекты по 2 МиБ из случайных данных):
первые 6 PUT проходят, седьмой получает `507 InsufficientStorage` (`The server has run out of storage space...`); сервис
остаётся живым (`/actuator/health` = UP), GET раньше записанных объектов работает, следующие PUT отвечают 507 до
освобождения места, после удаления постороннего файла с этого диска PUT проходит **без перезапуска**, `verify-all` чист.
До исправления, сделанного на этом этапе, ответ был `500 InternalError` без отличия от случайного сбоя. Метрики:
`sectoriadb_s3_errors_total{code="InsufficientStorage"}`, `sectoriadb_disk_free_bytes{dir="data"}` падает до 0, в логе
`ERROR ... the disk is full`. Освободить место сам сервер не может: удаление объектов до этапа GC место не возвращает.

Если заканчивается место на диске **метаданных** (`TARGET=meta`): первая ошибка записи тоже `507`, но после неё
метахранилище объявляет себя неработоспособным (`store failed during a commit and must be reopened`) и отказывает во
**всех** запросах, включая GET (теперь `503 ServiceUnavailable`, раньше ошибочно `409 BucketNotEmpty`); оправиться можно только
перезапуском, после которого `verify-all` чист (6088 объектов; до 507 успели пройти 6087 PUT по 1 КиБ). Это заметный недостаток: чтение могло бы продолжать работать
по последнему зафиксированному состоянию. Вынесено в список проблем.

**Порча данных.** При порче байтов в чанке и в записи малого объекта: `verify-all` печатает `CORRUPT` с причиной
(`Chunk corrupted: ... CRC32C mismatch`, `Small object corrupted: ... data CRC32C mismatch`) и завершается `RESULT: FAILED`;
GET отвечает `500 InternalError` (заголовки ещё не отправлены), тело с неверными байтами не отдаётся; счётчик
`sectoriadb_integrity_crc_failures_total{kind="chunk"}` и `{kind="small_record"}` растёт. Объекты с общими чанками
(дедупликация) портятся вместе: это ожидаемо.

**Сеть.** `tc netem` в песочнице недоступен: в ядре нет `CONFIG_NET_SCH_NETEM` (`Error: Specified qdisc kind is unknown`).
На обычном хосте: `docker run --rm --cap-add NET_ADMIN --network container:<сервер> nicolaka/netshoot tc qdisc add dev eth0
root netem delay 100ms 20ms loss 1%` (убрать: `tc qdisc del dev eth0 root`). Вместо него `slowproxy.py` (асинхронный TCP-прокси:
задержка, ограничение скорости, обрыв с RST после N байт) покрывает основное: обрыв PUT на 15 МиБ из 40 не оставляет
частичного объекта и не оставляет «зависших» запросов (`sectoriadb_s3_requests_inflight` возвращается к 0; сервер считает
обрыв как `Http400` для `PutObject`), PUT через медленную линию проходит и читается без искажений.

### 18. Безопасность

`security.py` подписывает запросы собственной реализацией SigV4 и посылает «сырой» HTTP (без нормализации SDK):

| Группа | Проверки | Результат |
|---|---|---|
| Аутентификация | запрос без подписи, неверный секрет, неизвестный ключ, подмена пути у подписанного запроса, тело не совпадает с подписанным sha256 (и объект не сохраняется), вставка лишней строки заголовка (CRLF) | все отклонены (`AccessDenied`, `SignatureDoesNotMatch`, `InvalidAccessKeyId`, `XAmzContentSHA256Mismatch`), лишний заголовок не отражается |
| Часы | сдвиг времени подписи -14 / +14 мин (принимается), -16 / +16 мин и -24 ч (отклоняется) | окно 15 минут работает, `RequestTimeTooSkewed`, метрика `auth_failures_total{reason="clock_skew"}` растёт |
| Ключи | `../x`, `a/../../x`, `./x`, `a//b`, `dir/`, `%2e%2e/x`, `a\b`, пробелы, `+`, `%`, NFC и NFD, эмодзи, RTL, CJK, табуляция, ведущий `/`, NUL, 1025 байт | S3 не нормализует путь: `../x` это обычный ключ, сохраняется и читается как есть; за пределы бакета выйти нельзя (ключи лежат в метахранилище, а не в файловой системе); NFC и NFD это разные ключи; 1025 байт `400 KeyTooLongError` |
| Имена бакетов | `..`, `a`, `UPPER`, `a_b`, `-lead`, `1.2.3.4`, `a..b`, 64 символа | `400 InvalidBucketName` |
| Размеры | заголовок 70 КБ, 400 заголовков метаданных, `Content-Length` 6 ТиБ без тела, `partNumber` 0 / 10001, `Complete` с 10001 частью и с XML 18 МБ, `DeleteObjects` на 5000 ключей, `max-keys=2147483647` | все отклонены кодом 4xx или ограничены; `Content-Length` больше 5 ГиБ теперь отклоняется сразу (`EntityTooLarge`), раньше запрос держал поток до таймаута 10 минут. Метаданные 4 КиБ принимаются (лимит S3 2 КБ): INFO |
| XXE | внешняя сущность и «миллиард смеха» в `DeleteObjects`, `CompleteMultipartUpload`, `PutBucketAcl`, `PutBucketPolicy` | `400 MalformedXML` (DOCTYPE запрещён), содержимое `/etc/passwd` не отражается, раздувание сущностей отклонено за миллисекунды. `PutBucketAcl` с телом XML игнорирует тело и отвечает 200: поддерживаются только канонические ACL в заголовках |
| Межаккаунтный доступ | ключ `alt` читает, пишет и удаляет в бакете ключа `main`, видит его в `ListBuckets` | **доступ есть**: IAM и владельца бакета в SectoriaDB нет, любой действующий ключ владеет всем; ACL и политики бакета могут только дополнительно открыть доступ анонимным. Это ожидаемое поведение текущей версии (INFO), для изоляции нужны отдельные инстансы |
| List | 20 000 объектов с общим префиксом 700 байт: префикс + разделитель, без разделителя, префикс 1500 байт, пустой результат, полный обход по токену | 6-83 мс на страницу, 20 000 из 20 000 ключей за 20 страниц и 1,0 с, ошибок нет |
| Медленные клиенты | 220 соединений с недописанными заголовками; 220 авторизованных PUT с телом по 1 байту | недописанные заголовки не мешают (обычный запрос 93 мс). **Медленные тела авторизованных клиентов исчерпывают 200 потоков Tomcat**, новые запросы не обслуживаются, пока соединения не закрыты (`server.tomcat.connection-timeout=10m` выбран для долгих загрузок и пауз видеоплееров). Любой владелец ключа может остановить сервис. Меры: обратный прокси с таймаутами чтения тела (nginx `client_body_timeout`), ограничение числа соединений на адрес, уменьшение `connection-timeout`, увеличение `server.tomcat.threads.max`. Вынесено в список проблем |

Исправлено по итогам проверок: ключи с эмодзи и другими символами вне BMP не проходили проверку подписи (канонический URI
кодировал половины суррогатной пары по отдельности); `Content-Length` > 5 ГиБ отклоняется сразу (см. выше).

## Результаты прогонов в песочнице

> **Оговорка о железе.** Всё ниже измерено в виртуальной машине-песочнице: 4 vCPU Intel Xeon 2,1 ГГц, 16 ГиБ RAM,
> виртуальный диск `virtio` (флаг «вращающийся» у него ничего не значит, тип носителя неизвестен), ядро 6.18, Docker.
> **Клиент warp, сервер, Prometheus, Grafana и InfluxDB работают на одних и тех же четырёх ядрах**, сеть это мост
> Docker на том же хосте. SectoriaDB в контейнере с `-Xmx1g`, `fsync=true`. Эти цифры доказывают, что конвейер измерений
> работает и показывают форму кривых, но **не годятся для планирования ёмкости**: на отдельных машинах с NVMe они будут
> другими. Образ SectoriaDB в песочнице собирался из готового jar на базе того же `eclipse-temurin:21-jre-alpine`
> (шаг `apk add curl` в `Dockerfile` не проходит через прокси песочницы; в образе curl нужен только закомментированному
> `HEALTHCHECK`). Результаты сохранены локально (`bench/results/`, в git не попадают), `meta.json` каждого прогона содержит
> коммит, конфигурацию и железо.

### 19. Сверка метрик сервера с warp (обязательная проверка)

Метод: `bench/warp/validate_metrics.py <каталог прогона>` берёт из `analyze.txt` окно анализа warp (время начала и
длительность) и итоги по операциям, затем спрашивает у Prometheus
`increase(sectoriadb_s3_requests_seconds_count{operation=...}[окно])` и `increase(sectoriadb_s3_request_bytes_total /
sectoriadb_s3_response_bytes_total)` на конец окна. Порог: расхождение больше 5 % значит, что метрики врут.

Для сравнения число запросов warp считается как «obj/s x окно»: число в скобках `(N reqs)` в отчёте warp относится ко
всему прогону, а окно анализа короче (warp отбрасывает первые и последние секунды, когда работают не все потоки).

**`warp mixed`, объекты `small` (1 Б - 64 КиБ), 50 потоков, 4 минуты** (окно 237 с, 00:18:08 UTC):

| Операция | Величина | warp | сервер (Prometheus) | Разница |
|---|---|---:|---:|---:|
| GET | запросов | 202 566 | 203 493 | +0,46 % |
| GET | байт | 2 368 324 239 | 2 379 250 486 | +0,46 % |
| PUT | запросов | 67 521 | 67 829 | +0,46 % |
| PUT | байт | 787 784 663 | 803 381 182 | +1,98 % |
| STAT (`HeadObject`) | запросов | 135 035 | 135 661 | +0,46 % |
| DELETE | запросов | 45 023 | 45 223 | +0,44 % |

Короткие прогоны по 60 с (окно 57 с):

| Прогон | Величина | warp | сервер | Разница |
|---|---|---:|---:|---:|
| `put small`, 50 потоков | запросов | 36 795 | 36 884 | +0,24 % |
| `put small` | байт | 433 324 032 | 441 311 504 | +1,84 % |
| `get small`, 50 потоков | запросов | 339 603 | 340 266 | +0,20 % |
| `get small` | байт | 3 926 812 262 | 3 939 409 580 | +0,32 % |
| `put medium` (1 МиБ), 20 потоков | запросов | 17 359 | 17 629 | +1,55 % |
| `put medium` | байт | 18 202 597 786 | 18 506 329 401 | +1,67 % |
| `get medium`, 20 потоков | запросов | 53 811 | 53 728 | -0,15 % |
| `get medium` | байт | 56 424 765 850 | 56 307 021 251 | -0,21 % |

**Вывод: метрики не врут.** Число запросов совпадает в пределах 0,5 % (в одном случае 1,6 %; это граница окна: scrape раз в 5 с
и экстраполяция `increase`). Байты GET совпадают (до 0,5 %). Байты PUT у сервера на 1,7-2,0 % больше: сервер считает байты
**запроса вместе с обрамлением `aws-chunked`** (подписи чанков и заголовки), а warp считает полезные данные. Это
соответствует описанию метрики в каталоге (п. 4.1), исправлять нечего.

Задержки с двух сторон согласуются, разницу объясняет клиентская часть (сеть, Go, 50 горутин на 4 ядрах). Медиана и p99
в том же прогоне `mixed`:

| Операция | warp, медиана / p99 | сервер (`histogram_quantile`), p50 / p99 |
|---|---|---|
| STAT (`HeadObject`) | 1,4 мс / 5,8 мс | 0,57 мс / 3,1 мс |
| GET, время до первого байта | 1 мс / 7 мс | 0,56 мс / 3,1 мс |
| PUT, DELETE | в отчёте warp только по размерам | 121 / 247 мс (PUT), 106 / 247 мс (DELETE) |

Сервер измеряет от входа запроса до последнего записанного байта ответа, warp ещё и время клиента и сети: поэтому серверные
значения чуть меньше. Границы гистограмм (п. 6) ограничивают p99 сверху: 247 мс это граница ведра, а не точное значение.

### 20. Что показывают дашборды в этом прогоне

Смешанная нагрузка `mixed small c50` упирается в **единственного писателя метахранилища**, а не в диск и не в CPU:

| Показатель за 237 с | Значение |
|---|---|
| Коммитов метахранилища | 477 в секунду (задержка коммита p50 1,65 мс, p99 6,2 мс) |
| Ожидание блокировки писателя | p50 91,7 мс, p99 490 мс |
| `fsync` метахранилища | p50 0,61 мс, p99 4,1 мс, 954 `fsync` в секунду |
| Запросов в обработке (максимум) | 50 (все потоки warp) |
| CPU процесса | 49 % от четырёх ядер (среднее), загрузка диска по `node_exporter` 47 % |
| Куча | максимум 297 МиБ из 1 ГиБ, p99 паузы GC 7 мс |

Задержки записи (PUT p50 около 120 мс) это почти целиком очередь к писателю (p50 ожидания 92 мс при p50 коммита 1,65 мс):
записи сериализуются, предел около 500-600 коммитов в секунду на этом хосте, чтения (HEAD p50 0,6 мс, GET p50 0,6 мс) от
очереди не зависят. Признак для дашборда: «ожидание блокировки писателя» растёт быстрее, чем «коммитов/с». Это и есть колено
из методики (п. 14.4, пункт 8): добавление потоков после него увеличивает только задержку.

### 21. Прогоны по ступеням параллелизма

Свежий сервер, 30 секунд на прогон (это проверка формы кривых, а не измерение: для измерений нужны минуты и повторы), объекты
`small` (1 Б - 64 КиБ) и `medium` (1 МиБ). Задержки взяты с сервера (`histogram_quantile` по тому же окну, что у warp), потому что warp для
случайных размеров не печатает общего p99. Колонка «ожидание писателя» и «коммитов/с» показывает насыщение метахранилища.

| Режим | Размер | Потоков | obj/s | МиБ/с | p50 / p99 на сервере, мс | Ожидание писателя p99, мс | Коммитов/с | Без ошибок |
|---|---|---:|---:|---:|---|---:|---:|---|
| get | small | 10 | 4475,65 | 50,4 | Get 0,7 / 7,0 | - | 0 | да |
| get | small | 50 | 6969,74 | 77,5 | Get 0,6 / 22 | - | 0 | да |
| get | small | 200 | 7281,65 | 81,1 | Get 0,6 / 48 | - | 0 | да |
| get | medium | 20 | 930,79 | 930,8 | Get 9,3 / 71 | - | 0 | да |
| get | medium | 100 | 981,43 | 981,4 | Get 26 / 479 | - | 0 | да |
| put | small | 10 | 744,30 | 8,4 | Put 17 / 25 | 49 | 749 | да |
| put | small | 50 | 486,69 | 5,4 | Put 157 / 248 | 495 | 481 | да |
| put | small | 200 | 480,25 | 5,5 | Put 394 / 977 | 973 | 480 | да |
| put | medium | 20 | 293,48 | 293,5 | Put 73 / 100 | 99 | 295 | да |
| put | medium | 100 | 267,74 | 267,7 | Put 367 / 498 | 496 | 290 | да |
| mixed | small | 10 | 1560,58 | 10,58 | Get 0,6 / 2,6; Put 19 / 49 | 50 | 389 | да |
| mixed | small | 50 | 1536,54 | 9,85 | Get 0,5 / 2,8; Put 170 / 248 | 496 | 383 | да |
| mixed | small | 200 | 1604,47 | 10,69 | Get 0,5 / 2,5; Put 724 / 994 | 994 | 401 | да |

Что видно (одиночные прогоны по 30 с, повторы не делались, разброс между прогонами до 30 %: например, `put small` на 50 потоках
в сверке выше дал 645 obj/s, здесь 487):

* **Чтение масштабируется до насыщения CPU**: `get small` 4476, 6970, 7282 obj/s на 10, 50, 200 потоках, p99 на сервере растёт
  с 7 до 22 и 48 мс (очередь к четырём ядрам, которые делят с warp). `get medium` упирается в память и loopback
  (около 930-980 МиБ/с, диск не участвует: данные в кэше страниц).
* **Запись ограничена единственным писателем и не масштабируется**: `put small` даёт максимум 744 obj/s уже на 10 потоках
  (p50 17 мс), на 50 и 200 потоках 487 и 480 obj/s, при этом p50 растёт 17, 157, 394 мс, а ожидание писателя p99 49, 495, 973 мс. Число коммитов
  в секунду тоже падает (749, 481, 480): конкуренция за единственный слот записи уменьшает полезную работу. Колено
  в этом окружении лежит **ниже 10 потоков записи**; 50 и 200 потоков добавляют только задержку. Рабочая точка для записи:
  несколько потоков на клиент (или несколько клиентов с малым `--concurrent`) и ограничение числа одновременных записей.
* **`mixed`** упирается в то же: около 1550 obj/s суммарно при любом параллелизме (75 % из них чтения: GET и STAT), чтение остаётся быстрым
  (p99 2,5-2,8 мс), запись и удаление растягиваются (PUT p50 19, 170, 724 мс).
* **`put medium` (1 МиБ)**: 293 МиБ/с на 20 потоках и 268 на 100 (p50 73 и 367 мс). Метахранилище коммитится один раз на
  объект, а чанки пишутся параллельно; ожидание писателя p99 99 и 496 мс показывает, что очередь есть и здесь, но вклады записи
  данных (`fsync` чанков) и коммита по этим прогонам разделить нельзя.

### 22. Дашборды: проверка запросов

`bench/observability/check_dashboards.py` после прогонов выполнил запрос каждой панели через `/api/ds/query`:

| Дашборд | Панелей (без рядов) | `OK` | `EMPTY` | `ERROR` |
|---|---:|---:|---:|---:|
| SectoriaDB — server | 56 | 56 | 0 | 0 |
| warp — client (InfluxDB) | 9 | 9 | 0 | 0 |

`OK` значит, что запрос панели выполнился и вернул хотя бы один ряд данных. На здоровой системе без ошибок панели событий
(`5xx/с`, ошибки по кодам) бывают пустыми: так было в первой проверке, до нагрузочных тестов, когда ошибок ещё не было.
В первой версии дашборда warp три панели падали с `pivot: schema collision detected: column "_value" is both of type int
and float` (задержка, TTFB, таблица итогов); исправлено приведением к `float` (`toFloat()`) перед `pivot`.

### 23. Сводка: что исправлено на этом этапе

Каждая запись, кроме 17 (состояние «метахранилище неработоспособно» в тесте не воспроизвести), имеет регрессионный тест
(`S3ApiRegressionTest`, `S3ExceptionHandlerTest`, `SigV4UtilsTest`, `VerifyCommandsTest`, `SmallObjectStorageTest`).

| # | Найдено | Чем | Исправление |
|---|---|---|---|
| 1 | `DELETE /bucket?tagging` (и другие подресурсы) **удалял пустой бакет**; `DELETE /bucket/key?retention` удалял объект; `PUT ...?retention` / `?uploadId` без `partNumber` перезаписывал объект | mint (minio-java) | `501 NotImplemented` для запросов с неизвестным подресурсом; допустимы `x-id` и `X-Amz-*` |
| 2 | `GET ?partNumber=N` для multipart-объекта отдавал весь объект | warp `multipart` | `501`; для обычного объекта только `partNumber=1` |
| 3 | `GET ?object-lock`, `?retention`, `?attributes` и т. п. отвечали листингом или байтами объекта | s3-tests | `501 NotImplemented` |
| 4 | Ключ из пробелов в `DeleteObjects` обрезался и не удалялся; `Quiet`; лимит 1000 | s3-tests | исправлено |
| 5 | `ListObjectVersions` пуст для бакета без версионирования (очистка бакета SDK не работала) | s3-tests | объекты как версия `null` |
| 6 | Листинг: `max-keys=0`, `Marker`, `StartAfter`, `Owner`, `encoding-type=url`, 500 на плохие параметры | s3-tests | исправлено |
| 7 | Ошибки Spring MVC (405, 400, 415, 404) отдавались как `500 InternalError` или JSON Spring | s3-tests, mint | S3 XML (`S3ExceptionHandler`, `S3ErrorController`) |
| 8 | Multipart терял пользовательские метаданные и `Cache-Control` и т. п. | mint (minio-py) | сохраняются при `CreateMultipartUpload`, применяются при `Complete` |
| 9 | Ключи с эмодзи (символы вне BMP): `SignatureDoesNotMatch` | `security.py` | канонический URI кодирует кодовые точки, а не половины суррогатных пар |
| 10 | `Content-Length` 6 ТиБ держал поток 10 минут | `security.py` | `400 EntityTooLarge` для заявленных больше 5 ГиБ |
| 11 | Заполнение диска: `500 InternalError` | `full-disk.sh` | `507 InsufficientStorage` |
| 12 | После сбоя коммита метахранилища все запросы (даже GET) отвечали `409 BucketNotEmpty` | `full-disk.sh TARGET=meta` | `503 ServiceUnavailable` (сам отказ работать до перезапуска остаётся, п. 24) |
| 13 | Пустой/неверный XML в `CompleteMultipartUpload`: 500 | s3-tests | `400 MalformedXML` |
| 14 | `verify` и `verify-all` работали только в интерактивной оболочке | kill-тесты | CLI-режим `java -jar sectoriadb.jar verify-all` (офлайн) |
| 15 | Имена бакетов: код `InvalidArgument` | s3-tests | `InvalidBucketName` |
| 16 | **Гонка при первой записи в свежий бакет**: каждый из параллельных PUT создавал собственный блоб (разреженный файл 8 ГиБ и таблица в памяти около 9 МиБ), в бакете warp при 200 потоках оказывалось 15 блобов | матрица warp, OOM сервера | блоб создаётся под блокировкой, остальные писатели получают его (`BlobService.chooseBlobFileForWrite`, тест `concurrentFirstWritesCreateExactlyOneCuckooBlob`) |
| 17 | `DELETE /bucket` отвечал `409 BucketNotEmpty` на любое `IllegalStateException`, в том числе на «store must be reopened» | матрица warp | только настоящий конфликт даёт `BucketNotEmpty`, остальное `503` |

### 24. Открытые проблемы (кандидаты на этапы 09-11)

| Проблема | Свидетельство | Куда |
|---|---|---|
| **Метахранилище после ошибки коммита (нет места на диске метаданных) отказывает во всех запросах, включая чтение, до перезапуска** | `full-disk.sh TARGET=meta`: 6087 PUT по 1 КиБ, затем 507, дальше 503 на всём, после перезапуска `verify-all` чист | 06/07 |
| **Медленные тела авторизованных клиентов исчерпывают 200 потоков Tomcat** (таймаут соединения 10 мин) | `security.py`: 220 соединений с телом по 1 байту, новые запросы не обслуживаются | 11 (конфигурация, лимиты) |
| **Таблица каждого блоба остаётся в памяти навсегда** (около 9 МиБ: `17 байт x 524 288 слотов`), кэш `HashTableCache` ничего не вытесняет; каждый бакет это минимум один блоб. При `-Xmx1g` хватает примерно на 100 блобов: на матрице warp (по бакету на прогон, до исправления гонки 81 блоб на 20 бакетов) сервер получил `OutOfMemoryError`, после чего **метахранилище осталось неработоспособным** (все запросы 503) до перезапуска; в стенде добавлены `-XX:+ExitOnOutOfMemoryError` и `restart: unless-stopped` | сбой в `CuckooHashTable.<init>`, `sectoriadb_cuckoo_tables_loaded` = 81, `jvm_gc_overhead` 0,47 перед отказом | 09 (индекс блобов, ленивая загрузка и вытеснение таблиц) |
| **Запись ограничена единственным писателем метахранилища**: около 480 коммитов/с, ожидание p99 около 0,5 с при 50 потоках | раздел 20, дашборд Saturation | 09/10 |
| Удаление объектов не возвращает место; чанки, записанные до обрыва PUT, остаются мусором | `kill-put.sh`; GC не реализован | 10 |
| Тело PUT сначала целиком пишется во временный файл (двойная запись, нужен том под `java.io.tmpdir` не меньше самого большого объекта, 5 ГиБ) | `FileStorageService.stageStream` | 10/11 |
| Повторный `CompleteMultipartUpload` не идемпотентен | `test_multipart_upload` | 10 |
| Границы частей multipart не сохраняются: нет `partNumber` при чтении, нет `PartsCount` | warp `multipart`, `test_multipart_get_part` | 09 |
| Нет модели владельца, IAM, ACL как в S3 | 41 тест ACL, межаккаунтные проверки | отдельно |
| Нет версионирования, блокировок объектов, CORS, POST-формы, условных записей, `GetObjectAttributes` | таблица в разделе «Совместимость» | вне плана |
| Не-ASCII значения метаданных ломают подпись; `Expires`, `x-amz-storage-class` не хранятся/не возвращаются | `test_object_set_get_unicode_metadata`, mint | 11 |
| minio-js не завершает набор; aws-sdk-java-v2 не печатает результатов; aws-sdk-go-v2 `PresignedPut` | mint | расследовать |

#### Что не делалось

* Загрузка 1 ТБ (процедура описана в п. 14.4, `preload.sh`).
* Сравнение холодного и тёплого кэша и поведение при заполнении 70-90 %: нет места на диске песочницы (свободно около 17 ГБ);
  методика и дашборды готовы.
* Прогоны длительностью в часы для поиска дрейфа и распределённый режим `warp client` (один хост).
* `tc netem` (ядро без `sch_netem`), вместо него userspace-прокси.
