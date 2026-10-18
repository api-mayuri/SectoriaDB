# Мониторинг

Метрики в формате Prometheus отдаёт отдельный Tomcat на **порту управления**, а не S3-порт. Подход: RED (rate, errors, duration)
для каждой S3-операции, USE (utilization, saturation, errors) для ресурсов (диск, блокировки, потоки, JVM) и «внутренности»
движка (кукушка, малые объекты, метахранилище, сборщик мусора). Архитектура — [observability.md](../architecture/observability.md).

## Порт управления

| Параметр | По умолчанию | Смысл |
|---|---|---|
| `management.server.port` | `9464` | порт actuator (`MANAGEMENT_SERVER_PORT`) |
| `management.server.address` | все интерфейсы | в продакшене привяжите к внутреннему интерфейсу (`127.0.0.1`) |
| доступные эндпоинты | `health`, `prometheus` | остальное отключено |

```bash
curl -s localhost:9464/actuator/health                                   # {"status":"UP"}
curl -s localhost:9464/actuator/prometheus | grep ^sectoriadb_s3_requests_seconds_count
```

- Порт **не защищён подписью SigV4**. Метрики не содержат секретов, имён бакетов и ключей, но порт нужно закрыть сетевыми правилами
  (`docker-compose.yml` публикует его только на `127.0.0.1`).
- S3-порт `/actuator/*` не отдаёт (`403` анонимно, для подписанного запроса это обычный запрос к бакету `actuator` — `404 NoSuchBucket`).
  Порт управления S3 API не обслуживает.
- Общая метка `application="SectoriaDB"`; метку `instance` ставит Prometheus; своя — `management.metrics.tags.instance=...`.

## Правила именования и меток

Префикс `sectoriadb_`. Micrometer-имя `sectoriadb.s3.requests` публикуется как `sectoriadb_s3_requests_seconds_{bucket,count,sum,max}`,
счётчики получают `_total`. Распределения — гистограммы с явными границами (10–15 штук); перцентиль считает Prometheus:
`histogram_quantile(0.99, sum by (le) (rate(x_bucket[5m])))`. Границы пишутся в формате Java: `le="1.0E-4"` — 100 мкс. Ряды `_max` можно игнорировать.

Метки только из закрытых множеств: `operation` (37 имён S3-операций, неизвестное — `Other`), `status_class` (`2xx`…`5xx`, `other`), `code` (код ошибки S3),
`reason` (11 причин отказа аутентификации), `target` (`cuckoo`, `small`, `metastore`), `kind`, `result` (`success`/`failure`), `dir` (`data`/`meta`).
Имя бакета, ключ, ключ доступа, идентификаторы блобов, `uploadId` и IP **никогда** не становятся метками. Метрики Tomcat и JVM — под обычными именами
(`tomcat_*`, `jvm_*`, `process_*`); `http_server_requests` выключена (дублирует собственные RED-метрики).

## Каталог ключевых метрик

«Г» — гистограмма. Полный список имён можно получить `curl .../actuator/prometheus | grep '^# TYPE sectoriadb'`.

### S3 (RED)

| Метрика | Метки | Смысл |
|---|---|---|
| `sectoriadb_s3_requests_seconds` (Г) | `operation`, `status_class` | задержка запроса от входа до последнего байта ответа; `_count` — rate, `status_class=~"4xx\|5xx"` — ошибки |
| `sectoriadb_s3_errors_total` | `operation`, `code` | ответы с ошибкой по коду S3 |
| `sectoriadb_s3_auth_failures_total` | `reason` | отказы аутентификации: `signature_mismatch`, `clock_skew`, `expired_presign`, `unknown_key`, `disabled_key`, `malformed_header`, `payload_hash_mismatch`, `chunk_signature_mismatch`, `open_setup_denied`, `anonymous_denied`, `unsigned_payload_denied` |
| `sectoriadb_s3_requests_inflight` | `operation` | запросов в обработке |
| `sectoriadb_s3_request_bytes_total`, `sectoriadb_s3_response_bytes_total` | `operation` | байты тел (запрос — с обрамлением `aws-chunked`) |
| `sectoriadb_s3_get_ttfb_seconds` (Г) | — | время до первой записи тела `GetObject` |
| `sectoriadb_s3_requests_aborted_total` | `operation` | запросы, оборванные необработанным исключением (например, GET после ошибки CRC при ушедших заголовках) |
| `sectoriadb_s3_requests_slow_total` | `operation` | запросы дольше `slow-request-ms` |

### Диск, `fsync`, метахранилище (USE)

| Метрика | Смысл |
|---|---|
| `sectoriadb_storage_fsync_seconds` (Г, `target`) | один `FileChannel.force` |
| `sectoriadb_storage_disk_read_seconds`, `..._write_seconds` (Г, `target`) | позиционное чтение / запись (запись — в page cache) |
| `sectoriadb_storage_disk_read_bytes_total`, `..._write_bytes_total` (`target`) | байты движка |
| `sectoriadb_metastore_commit_seconds` (Г) | один коммит (страницы, `fsync`, мета-страница, `fsync`) |
| `sectoriadb_metastore_writer_lock_wait_seconds` (Г) | ожидание единственного слота записи (в норме около нуля: пишет коммиттер) |
| `sectoriadb_metastore_group_batch_size`, `..._group_batch_pages` (Г) | размер групповой пачки (тела, страницы) |
| `sectoriadb_metastore_group_queue_wait_seconds` (Г) | ожидание тела записи в очереди группового коммита — основное «ожидание записи» запроса |
| `sectoriadb_metastore_group_body_rollbacks_total` | тела, откатанные без влияния на соседей |
| `sectoriadb_metastore_recoveries_total` (`result`) | попытки восстановить запись после сбоя коммита |
| `sectoriadb_small_append_lock_wait_seconds`, `sectoriadb_small_durability_wait_seconds` (Г) | добавление в `.sob`: ожидание короткой блокировки / до долговечности |
| `sectoriadb_small_group_fsync_records` (Г) | записей `.sob` на один `fsync` |
| `sectoriadb_cuckoo_lock_wait_seconds`, `sectoriadb_cuckoo_barrier_wait_seconds` (Г) | ожидание блокировки таблицы / время в барьере долговечности перед коммитом |
| `sectoriadb_cuckoo_barrier_inserts` (Г) | вставок на один `fsync` барьера |
| `tomcat_threads_busy_threads`, `tomcat_threads_config_max_threads` | пул потоков S3-порта |
| `jvm_gc_pause_seconds`, `jvm_memory_*`, `process_files_open_files` / `process_files_max_files`, `process_cpu_usage` | JVM и процесс |

### Движок и целостность

| Метрика | Смысл |
|---|---|
| `sectoriadb_integrity_crc_failures_total` (`kind`: `chunk`, `small_record`, `whole_object`, `metastore_page`, `slot_meta`) | **любой прирост — инцидент** |
| `sectoriadb_cuckoo_slots_active`, `..._capacity`, `..._quarantined` | слоты загруженных таблиц: занято, всего, в карантине |
| `sectoriadb_cuckoo_used_bytes`, `sectoriadb_cuckoo_capacity_bytes` | занято / ёмкость (ёмкость — по реестру блобов, всех) |
| `sectoriadb_cuckoo_tables_loaded`, `..._tables_max`, `..._tables_resident_bytes`, `..._tables_evicted_total` | таблицы в памяти сейчас / предел / память / вытеснения |
| `sectoriadb_cuckoo_eviction_path_length` (Г), `..._table_full_total`, `..._dedup_hits_total`, `..._rekeys_total` | длина пути вытеснений; отказы по переполнению; дедупликация; настоящие коллизии 64-битного хэша |
| `sectoriadb_blobs` (`kind`) | зарегистрированные блобы |
| `sectoriadb_small_live_bytes`, `..._dead_bytes`, `..._live_records`, `..._dead_records`, `..._blobs_open` | файлы малых объектов (по открытым) |
| `sectoriadb_metastore_file_bytes`, `..._pages`, `..._free_pages`, `..._last_txid`, `..._live_readers`, `..._page_size_bytes` | состояние `sectoria.db` |
| `sectoriadb_chunks_indexed`, `..._chunks_refs`, `..._chunks_gc_queue_length`, `..._chunks_orphans` | индекс чанков: записи, ссылки, очередь на освобождение, сироты |
| `sectoriadb_pool_blobs_max`, `..._pool_dedup_hits_total`, `..._pool_placement_fallbacks_total`, `..._pool_grown_total` | пулы: наибольшее число блобов, дедупликация, отказы первого выбора HRW, рост |
| `sectoriadb_resize_seconds` (Г, `result`), `sectoriadb_auto_resize_runs_total` | расширение блоба; проходы проверки роста |
| `sectoriadb_multipart_uploads_open` | открытые multipart-загрузки |
| `sectoriadb_objects_stored`, `sectoriadb_objects_stored_bytes` | число и логический размер текущих версий |
| `sectoriadb_disk_free_bytes`, `sectoriadb_disk_total_bytes` (`dir`) | свободное место и размер ФС |

### Сборщик мусора

`sectoriadb_gc_freed_chunks_total` / `sectoriadb_gc_freed_bytes_total` (`kind`: `queue`, `orphan`, `sweep`), `sectoriadb_gc_run_seconds` (Г, `result`),
`sectoriadb_gc_sweep_seconds` (Г), `sectoriadb_gc_sweep_slots_scanned_total`, `sectoriadb_gc_sweep_strays_found_total`, `sectoriadb_gc_compaction_seconds` (Г),
`sectoriadb_gc_compaction_reclaimed_bytes_total`, `sectoriadb_gc_tombstones_collected_total`, `sectoriadb_gc_small_records_marked_total`,
`sectoriadb_gc_deferred_total` (`reason`: `blob_busy`, `uploads_in_flight`, `not_eligible`), `sectoriadb_gc_errors_total`,
`sectoriadb_gc_last_run_timestamp_seconds` (только успешные проходы), `sectoriadb_gc_queue_length` (надгробия манифестов),
`sectoriadb_gc_upload_holds`, `sectoriadb_gc_old_small_files_pending`.

Gauge состояния кэшируются на `gauge-cache-ms` (15 с). Агрегаты слотов считаются по таблицам, **загруженным в память** (вытесненные не входят); ёмкость и число блобов — по реестру.

## Типичные запросы PromQL

```promql
# p50 / p99 задержки по операциям; запросов в секунду; доля ошибок
histogram_quantile(0.99, sum by (operation, le) (rate(sectoriadb_s3_requests_seconds_bucket[5m])))
sum by (operation) (rate(sectoriadb_s3_requests_seconds_count[5m]))
sum(rate(sectoriadb_s3_requests_seconds_count{status_class="5xx"}[5m])) / sum(rate(sectoriadb_s3_requests_seconds_count[5m]))

# ошибки по коду S3, отказы аутентификации
topk(10, sum by (code) (rate(sectoriadb_s3_errors_total[5m])))
sum by (reason) (rate(sectoriadb_s3_auth_failures_total[5m]))

# хвосты записи: fsync, коммит, очередь группового коммита
histogram_quantile(0.99, sum by (target, le) (rate(sectoriadb_storage_fsync_seconds_bucket[5m])))
histogram_quantile(0.99, sum by (le) (rate(sectoriadb_metastore_commit_seconds_bucket[5m])))
histogram_quantile(0.99, sum by (le) (rate(sectoriadb_metastore_group_queue_wait_seconds_bucket[5m])))
rate(sectoriadb_metastore_group_batch_size_sum[5m]) / rate(sectoriadb_metastore_group_batch_size_count[5m])   # средняя пачка

# ёмкость
100 * sectoriadb_cuckoo_slots_active / sectoriadb_cuckoo_slots_capacity
100 * sectoriadb_disk_free_bytes / sectoriadb_disk_total_bytes
predict_linear(sectoriadb_disk_free_bytes{dir="data"}[6h], 24 * 3600) < 0

# насыщение
sum(sectoriadb_s3_requests_inflight)
tomcat_threads_busy_threads / tomcat_threads_config_max_threads
process_files_open_files / process_files_max_files

# целостность: любой прирост — инцидент
sum by (kind) (increase(sectoriadb_integrity_crc_failures_total[1h])) > 0
sectoriadb_cuckoo_slots_quarantined > 0

# сборщик мусора: очередь, скорость освобождения, возраст последнего прохода
sectoriadb_chunks_gc_queue_length
sum by (kind) (rate(sectoriadb_gc_freed_chunks_total[5m]))
time() - sectoriadb_gc_last_run_timestamp_seconds
```

Коммитов метахранилища в секунду считайте по `sectoriadb_metastore_commit_seconds_count`, а не по `last_txid` (он кэшируется и растёт ступенями).
Время запроса измеряется внутри сервера (до последнего байта, отданного контейнеру); медленный клиент удлиняет запрос, установка соединения не входит.

## Идентификатор запроса и лог медленных запросов

Каждый ответ получает `x-amz-request-id` (16 hex-символов) и `x-amz-id-2`; идентификатор попадает в тело ошибки и в MDC логов (`[requestId operation]`).
Запрос не короче `sectoriadb.observability.slow-request-ms` (2000 мс) пишется в `WARN` строкой `Slow request: requestId=… operation=… status=… durationMs=… requestBytes=… responseBytes=…`
(без бакета и ключа). Строка `Request done` с путём — только в профиле `debug`.

## Стенд Prometheus + Grafana + InfluxDB

Каталог [bench/observability](../../bench/observability/docker-compose.yml): `docker compose up -d` поднимает SectoriaDB (профиль `server`), Prometheus (опрос раз в 5 с,
хранение 30 дней / 5 ГБ), Grafana, InfluxDB v2 (живые результаты клиента `warp`) и node_exporter (хост). Порты 9090, 3000, 8086 и 9464 публикуются только на `127.0.0.1`.

```bash
cd bench/observability && cp .env.example .env && docker compose up -d      # Grafana: http://localhost:3000
```

- Grafana: папка **SectoriaDB**, дашборды «SectoriaDB — server» (Overview, Rate/Errors/Duration, Saturation, Engine internals, Capacity, Garbage collection, Host) и
  «warp — client (InfluxDB)». JSON порождает `bench/observability/grafana/gen_dashboards.py` (правьте его, затем `python3 -I gen_dashboards.py`).
  `bench/observability/check_dashboards.py` выполняет запрос каждой панели и печатает `OK`/`EMPTY`/`ERROR`.
- Prometheus: job `sectoriadb` опрашивает `sectoriadb:9464/actuator/prometheus`; правила — `prometheus/alerts.yml`. Доставка уведомлений требует Alertmanager, которого в стенде нет.
- Методика нагрузочных тестов и сценарии `warp` — [benchmarking.md](../testing/benchmarking.md).

## Алерты

24 правила в `bench/observability/prometheus/alerts.yml` (проверены `promtool check rules`). Пороги предложены до измерений на вашем железе — подправьте их.

| Группа | Правило | Условие |
|---|---|---|
| Целостность | `SectoriaIntegrityFailure`, `SectoriaQuarantinedSlots`, `SectoriaTableFull` | любой прирост CRC-сбоев; слоты в карантине; отказы по переполнению таблицы (critical) |
| Хранилище | `SectoriaMetastoreRecoveryFailed`, `SectoriaGcErrors`, `SectoriaGcStale`, `SectoriaResizeFailed` | неудачное восстановление записи; ошибки GC; последний успешный проход GC старше часа; ошибки расширения |
| Доступность | `SectoriaDown`, `SectoriaServerErrors`, `SectoriaAbortedRequests` | `up == 0` 1 мин; доля 5xx > 1 %; оборванные ответы |
| Задержка и насыщение | `SectoriaPutLatencyHigh`, `SectoriaGetTtfbHigh`, `SectoriaFsyncSlow`, `SectoriaWriterQueue`, `SectoriaTomcatThreadsBusy`, `SectoriaFileDescriptors`, `SectoriaGcPauses` | p99 `PutObject` > 1 с; p99 TTFB > 500 мс; p99 `fsync` > 100 мс; p99 ожидания слота писателя > 1 с; > 90 % потоков; > 80 % дескрипторов; p99 паузы GC > 500 мс |
| Ёмкость | `SectoriaFillHigh`, `SectoriaFillCritical`, `SectoriaDiskFreeLow`, `SectoriaDiskFullSoon`, `SectoriaGcQueueGrowing` | заполнение кукушки > 80 % / > 90 %; свободно < 15 %; `predict_linear` по 6 ч: диск данных кончится за 24 ч; очередь надгробий > 100 000 и растёт |
| Безопасность | `SectoriaAuthFailureBurst`, `SectoriaClockSkew` | > 5 отказов/с по `signature_mismatch`/`unknown_key`/`disabled_key`; > 10 `clock_skew` за 15 мин |

Что делать при срабатывании: целостность и карантин — остановить запись в проблемный бакет, выполнить `verify-all`/`scrub`, восстановить из копии
([operations.md](operations.md)); `TableFull`/`FillHigh` — поднять `pool.max-blobs` или проверить рост пула; `GcStale` — проверить `gc.enabled` и `gc status`.
