# Review: ops, docs, build, bench, imports (SectoriaDB, branch 11-review-refactor)

Scope: sectoriadb-server `observability/*`, `shell/*`, `scheduler/*`, resources; all pom.xml; Dockerfile, docker-compose.yml, .env.example,
.dockerignore; `bench/**`; test-suite structure of both modules; README, docs/S3-GUIDE.md, docs/architecture/*.md; imports of the whole codebase.
Read-only review: no repo file edited, no mvn run. Helper scripts used for the checks are in this scratchpad directory (`imports.py`, outputs `imports_out.txt`).

Mechanical cross-checks performed (results):
* Metric names: every `sectoriadb_*` name in README/docs/bench/alerts/dashboards (115 distinct) was compared with the names registered in code
  (78 base names x Micrometer/Prometheus suffix rules). Result: **no mismatch** (docs, alerts, dashboards and bench scripts only use metrics that exist);
  every metric in code is documented in doc 08. Histogram bucket tables in doc 08 section 6 match `Buckets.java`. Counts in doc 08 (37 operations, 11 auth reasons, 20 alert rules) match code.
* Label cardinality: all label sets are closed enums (`S3Operation` 37, `AuthFailure` 11, `Target` 3, `CrcKind`, `GcDeferral`, status class 5);
  the only lazily created series (`sectoriadb.s3.errors`, key operation x code) use codes from `S3Exception`/handlers (static strings) additionally
  guarded by `[A-Za-z0-9]{1,48}`. **No cardinality leak found.** Dashboards: `gen_dashboards.py` re-generated to a temp dir is byte-identical to the committed JSON.
* Properties: every `sectoriadb.*` property mentioned in README / docs / application.properties / compose exists in code (StorageProperties, ObservabilityProperties, `@Value`);
  every property in code is in application.properties except `sectoriadb.metastore.lazy` (internal, set by the CLI) and `sectoriadb.reconcile-on-start`
  (only in doc 10). Findings about gaps/dead keys are in section B.
* Markdown links: all relative links and `#anchors` in the 15 .md files resolve (0 broken).
* Tests writing to `java.io.tmpdir`: none in test code (all use `@TempDir`); but production `Files.createTempFile(...)` uses the JVM tmpdir (see A6).
* Test time (stale surefire reports in target/): core ~46 s / 290 tests (GroupCommitTest 15.7 s, ObjectListingTest 10.6 s, SmallCompactionTest 3.9 s), server ~24 s / 184 tests. Suite is fast enough; the issue is flakiness risk, not duration.

---------------------------------------------------------------------------------------------------------------------------------

## Top 15 findings (ordered by value / risk)

| # | Sev | Area | One line |
|---|-----|------|----------|
| 1 | High | docs/CLI | `object-acl`, `bucket-acl`, `public-list`, `verify`, `verify-all` are documented as working "next to a running server" via `docker compose exec ... java -jar`, but they need the single-process metastore and fail while the server runs |
| 2 | High | config | Shipped logging defaults are DEBUG (properties override logback's INFO): every request is logged with bucket+key, Spring web DEBUG goes to the file |
| 3 | Med | scheduler | Boot's scheduler has 1 thread: GC pass, 6 h sweep, compaction, pool growth and multipart cleanup block each other (a long sweep stops pool growth) |
| 4 | Med | Docker | `apk add curl` is unused (HEALTHCHECK is commented out and its command would 403 anyway) and breaks builds behind proxies; replace by busybox `wget` healthcheck |
| 5 | Med | Docker/config | Default `-Xmx512m` but `cache.max-resident-tables=64` x ~9 MiB = 576 MiB of slot tables: the shipped defaults can OOM |
| 6 | Med | ops | PUT/Copy spool bodies to `java.io.tmpdir` (container writable layer in Docker), never cleaned after `kill -9`, no property to relocate |
| 7 | Med | logging | Log file path is hard-coded `./sectoriadb-meta/sectoriadb.log` (ignores `sectoriadb.meta-dir`; in Docker it lands in `/app`, not in the volume); `logs` shell command has the same constant |
| 8 | Med | docs | Stale statements: "auth is open / disabled while no keys exist" (.env.example:55, CredentialCommands:77) contradicts the S4 fix; README "automatic blob expansion"; doc 08 section 4.4 "all blobs are loaded" |
| 9 | Med | bug | Credentials from `SECTORIADB_S3_CREDENTIALS` are re-registered on every start: WARN "Skipping malformed credential" for every existing key, secrets never updated, deleted keys resurrect; the legacy ACCESS_KEY/SECRET_KEY pair behaves differently (updates) |
| 10 | Med | config | `StorageProperties.S3.authEnabled/region` are dead (bound to `sectoriadb.s3.auth-enabled`, real keys are read through `@Value`), allow-* flags and `metastore.*` live outside the properties class, defaults are duplicated in 3-4 places, no configuration metadata |
| 11 | Med | observability | `used_bytes/capacity_bytes` and `objects_stored_bytes/used_bytes` ratios mix resident-only and all-blob series; `FillHigh` alert text is stale for multi-blob pools; `gc_last_run_timestamp` is updated on failed passes; "Disk full ETA (1h window)" uses a 15 m window; no alerts for GC/recovery failures |
| 12 | Med | Docker | `.dockerignore` lacks `bench/` (build context 92 MB, 61 MB is a Python venv); jar name hard-coded; container runs as root |
| 13 | Low-Med | bench | Secrets on command lines (`curl --user`, `warp --secret-key`), bench stack publishes S3 on 0.0.0.0 with default keys, `s3tests.sh` clones `master` while doc says commit `5522d1c`, `run-all.sh` loses the first log, no traps |
| 14 | Med | imports | 59 wildcard imports (19 non-static in 12 files), 32 unused imports, 35 files with inconsistent order (two styles), 0 duplicates, 296 inline FQNs in 68 files; no tool enforces anything |
| 15 | Low-Med | tests | 24 `Thread.sleep` sites with wall-clock assertions (GC grace tests have no `Clock`), 16 server test classes = 16 Spring contexts with identical boilerplate, `bytes(n, seed)` copied into ~20 core tests, `RecordingMetrics` hand-written 35 fields |

---------------------------------------------------------------------------------------------------------------------------------

## A. Bugs / correctness

### A1 [High] CLI mode advertises metastore commands as "next to a running server"
* Evidence: `Application.java:53-58` puts `bucket-acl, object-acl, public-list, verify-all, verify` into `CLI_COMMANDS` and the comment at `:29-31` says commands "can run next to an already-running server".
  `docs/S3-GUIDE.md:29` ("работают рядом с запущенным сервером"), `:64-65` (`$X verify-all`, `$X verify --id`), `README.md:87` repeat it with `docker compose exec -T sectoriadb java -jar /app/sectoriadb.jar ...`.
  But `MetaStore.open` takes `FileChannel.tryLock()` (`MetaStore.java:105-111`) and `MetaStoreConfig` throws "is in use by another SectoriaDB process ... use the S3 API or the server's interactive shell instead".
  Only credential commands (`mk-key keys rm-key enable-key disable-key`, JSON file `credentials.json`) really work next to a server. README:324 and doc 08 say `verify-all` needs the server stopped, so the docs contradict each other.
  Not state corruption (the lock protects the file) but a documented workflow that fails.
* Fix (cheap): split `CLI_COMMANDS` into `ONLINE_OK` (credential commands) and `OFFLINE_ONLY` (everything touching the metastore); in offline-only mode fail fast with a clear message before the context starts when the metastore is locked; correct S3-GUIDE/README ("stop the server first, or `docker attach sectoriadb` and use the interactive shell").
  Fix (better): expose the ACL / verify operations on the management port (loopback only) or reuse the S3 API (`put-bucket-acl`), so no second process is needed.
* Related (Low): `credentials.json` has a read-modify-write across processes without a file lock (`JsonCredentialRepository.save`, lock is per-JVM `ReentrantReadWriteLock`): two simultaneous `mk-key` (CLI + server bootstrap) can lose an update. Use `FileChannel.lock()` on a sidecar `credentials.lock`.

### A2 [High] Default logging is DEBUG, contradicting logback-spring.xml and doc 08
* Evidence: `application.properties:129-132` `logging.level.org.example.sectoriadb=DEBUG`, `logging.level.org.springframework.web=DEBUG` (+ the redundant `...mvc.method.annotation=DEBUG`).
  Spring Boot applies `logging.level.*` after reading `logback-spring.xml`, so `logger org.example.sectoriadb level="INFO"` (`logback-spring.xml:26`) is overridden; the XML comment "INFO to both" is false.
  Consequences with the shipped config: `RequestObservabilityFilter.finish` (`:117-121`) logs `Request done ... GET /bucket/key` for every request (bucket and key names, which doc 08 section 8 promises are "only DEBUG", but DEBUG is the default),
  and Spring web DEBUG lines go to the file via the root appender (doc 08 section 14.1 admits it "eats noticeable CPU"). The bench compose and fault harness override this with `-Dlogging.level...` — the production image does not.
* Fix: set INFO in `application.properties` (`logging.level.root=INFO`, drop the three DEBUG lines), keep a commented DEBUG block or `application-debug.properties` (`--spring.profiles.active=debug`); remove the `-Dlogging.level` overrides from bench compose / `faults/lib.sh:16`.

### A3 [Med] One scheduler thread for all background work
* Evidence: five `@Scheduled` methods (`GarbageCollectionScheduler.collect/sweep/compact` `:36,45,54`, `AutoResizeScheduler.checkFillRatios` `:43`,
  `S3MultipartController.MultipartCleanup.cleanupStaledUploads` `:710`) and no `spring.task.scheduling.*` setting anywhere; Boot's `ThreadPoolTaskScheduler` defaults to pool size 1.
  The "passes never overlap" claim of the scheduler Javadoc is true only because of this (plus `GarbageCollector.runLock`/`sweepLock` and `SmallBlobCompactor.running`), but it means a long `sweepAll()` (6 h interval, scans all slots of all blobs, waits up to `sweep-gate-wait` per pool) starves the GC pass, compaction and **pool growth** (a pool can hit `TableFullException` while the growth backstop waits behind a sweep).
  A manual shell `gc run` blocks on `runLock` while a scheduled pass runs (acceptable) and shares nothing else.
* Fix: `spring.task.scheduling.pool.size=4`, `spring.task.scheduling.thread-name-prefix=sectoria-sched-`, `spring.task.scheduling.shutdown.await-termination=true`, `...await-termination-period=30s`.
  The three GC tasks are already mutually safe (own locks), so a bigger pool does not create overlap of the same task (fixedDelay is per task). Make `AutoResizeScheduler` `@ConditionalOnProperty(auto-resize.enabled, matchIfMissing=true)` instead of waking up every minute to return.
* Minor: `AutoResizeScheduler.java:49-54` computes `poolFill` before `growIfOverThreshold`, so the log line `fill.blobs() + 1` can be wrong when a writer grew the pool in between.
* Minor: `MultipartCleanup` hard-codes 7 days / 1 h and uses the *initiation* time, so a legitimately long-running multipart upload older than 7 days is deleted underneath its client;
  `Long.parseLong(meta.getProperty("initiatedAt","0"))` (`:727`) throws on a corrupted file and aborts the whole sweep. Make it `sectoriadb.multipart.stale-after` (default 7d), catch `NumberFormatException` per upload, base age on last modified part.

### A4 [Med] `SECTORIADB_S3_CREDENTIALS` bootstrap is not idempotent
* Evidence: `Application.java:73-93` calls `credentialService.register(...)` for every entry on every start (also in CLI mode, i.e. on every `docker compose exec ... mk-key`).
  `register` throws `IllegalArgumentException("Access Key ID already exists")` for persisted keys, caught at `:85-87` and logged as WARN "Skipping malformed credential from env" — every restart, every CLI call, with a wrong word ("malformed").
  The secret of an existing key is never updated (a rotated secret in `.env` is silently ignored), whereas the legacy pair goes through `ensureCredential` (`:96-100`) which does update; an env-defined key removed with `rm-key` comes back at the next start; `split(":", 3)` breaks secrets containing `:`.
* Fix: one `credentialService.ensureCredential(ak, sk, desc)` path for both sources (upsert without touching `enabled`), INFO log "credential from environment unchanged"; skip bootstrap entirely when `cli` is true; document that env credentials are authoritative.

### A5 [Med] Memory defaults inconsistent
* Evidence: `application.properties:39` `max-resident-tables=64`, `:41` `max-resident-table-bytes=0` (no byte budget); doc 09/10 and README say ~9 MiB per table => 576 MiB; `Dockerfile:36` and `.env.example:48`/compose default `JAVA_OPTS=-Xmx512m`. A server with a few buckets x `pool.max-blobs=16` can exceed the heap before the LRU evicts (limits are "soft"). The bench raised to `-Xmx1g -XX:+ExitOnOutOfMemoryError` after OOMs (doc 08 table item 16).
* Fix: default `max-resident-table-bytes` to a derived value (e.g. 25 % of `Runtime.maxMemory()` when 0), and/or use `-XX:MaxRAMPercentage=75` + `-XX:+ExitOnOutOfMemoryError` in the Dockerfile default; document the formula in README "Настройка".

### A6 [Med] PUT body spooling goes to `java.io.tmpdir`
* Evidence: `FileStorageService.java:359` `Files.createTempFile("sectoriadb-upload-", ".tmp")`, `S3ObjectController.java:429` `Files.createTempFile("sectoriadb-copy-", ".tmp")`; no property, no startup cleanup (grep shows no `sectoriadb-upload` pattern deleted anywhere). `finally` deletes only on normal/exception paths: after `kill -9` (the `bench/faults/kill-put.sh` scenario, 800 MiB per kill) the files stay in `/tmp` of the container forever. In the image `/tmp` is the container's writable layer, not a volume. README "Ограничения" and doc 08 "Открытые проблемы" mention the disk need but not the location problem.
* Fix: `sectoriadb.upload-tmp-dir` (default `${sectoriadb.data-dir}/.tmp`), create it at start and delete `sectoriadb-*.tmp` older than the longest allowed upload at startup (same place as `StartupReconciler`); also point surefire's `java.io.tmpdir` at `target/` (section tests).

### A7 [Med] Log file path ignores `sectoriadb.meta-dir`
* Evidence: `logback-spring.xml:14,16` hard-code `./sectoriadb-meta/sectoriadb.log`; `StatusCommands.java:123` `Path.of("./sectoriadb-meta/sectoriadb.log")`. In Docker the working dir is `/app`, so logs go to `/app/sectoriadb-meta/` in the container layer (500 MB cap x ... lost on recreate) while the volume is `/data/meta`; with a non-default `meta-dir` the `logs` command reports "Log file not found". `logs` additionally reads the whole file line by line into a `LinkedList` (`tailFile`, `:184-194`), and `Files.newBufferedReader` throws `MalformedInputException` on a non-UTF-8 byte.
* Fix: `<springProperty name="LOG_DIR" source="sectoriadb.meta-dir" defaultValue="./sectoriadb-meta"/>` (or `logging.file.path`) in logback; `logs` reads `logging.file.name`/same property and tails with `RandomAccessFile` from the end (decode with REPLACE).

### A8 [Med] Stale user-visible statements (behaviour fixed, text not)
* `.env.example:55` "first run, auth is open while credentials.json is empty" (contradicts `.env.example:24-25`, README Security, ROADMAP S4).
* `CredentialCommands.java:77` prints "Note: when no keys exist, auth is disabled and all requests are allowed." — wrong since stage 02 (default deny; open mode only with `allow-open-setup=true`).
* `README.md` "Эксплуатация": "Автоматическое расширение блоба, когда он заполняется" — since stage 09 pools grow by adding blobs; blob expansion is manual `resize`.
* `doc 08` lines 206-210: "gauges count blobs loaded in memory; the auto-resize scheduler walks all blobs every minute so after a minute these are all blobs" — false since stage 10 B3 (LRU of resident tables, `max-resident-tables`); `slots_active/capacity/used_bytes` cover resident tables only.
* `doc 09:451` points to the doc 08 section "Таблица каждого блоба остаётся в памяти" which is struck through there (fixed in stage 10).
* `doc 08` section 24 "Открытые проблемы (кандидаты на этапы 09-11)" still lists items for 09/10 (partNumber for multipart "09", idempotent Complete "10") without status; `StorageProperties`/Gc javadoc fine.
* `README.md` table of console commands omits the `gc status|run|sweep|compact` group (only described in the GC section); config table omits `sectoriadb.fsync`, `metastore.group.*`, `gc.compact-interval`, `gc.sweep-gate-wait`, `gc.upload-ticket-ttl`, `gc.small-compact-min-dead-bytes` (only in text), `observability.gauge-cache-ms`, `s3.auth.allow-open-setup`/`allow-unsigned-payload` (only in Security section), `s3.credentials`.
* `docs/architecture/04-small-objects.md:43` still names `JsonManifestRepository` (removed in stage 07), `core/pom.xml:15` description says "JSON metadata repositories".

### A9 [Med] Observability: wrong or misleading PromQL / alerts
1. doc 08 lines 160-162/202 give `sectoriadb_cuckoo_used_bytes / sectoriadb_cuckoo_capacity_bytes` and `objects_stored_bytes / cuckoo_used_bytes`. `used_bytes`, `slots_*` are summed over **resident** tables (`StorageGauges.compute` loops `tables.residentTables()`), `capacity_bytes` over **all** registered blobs (`blobs.findAll()`, `:176-186`). As soon as the LRU evicts a table (more than `max-resident-tables` blobs) the ratio under-reports. Dashboard "Bytes stored vs cuckoo used" plots the three series together with the same flaw.
   Fix: compute `used` from the metastore chunk index (`chunks_by_blob` counts) or from `blobs` entities so both sides cover all blobs; keep a separate `resident_*` series.
2. `alerts.yml:74-85` `SectoriaFillHigh/Critical` ("cuckoo table above 80 % full ... expand the blob") use the resident-only ratio and a text that predates multi-blob pools (pool grows at 75 %, so >80 % means `max-blobs` reached or growth failed). Replace by "pool at `max-blobs` and fill >= grow threshold" (needs a `sectoriadb_pool_blobs_limit` gauge from `pool.max-blobs`, it is not exported; only `pool_blobs_max` exists).
3. `MicrometerStorageMetrics.java:217` `gcRun()` sets `gcLastRunSeconds` for failed passes too, so the documented alert `time() - sectoriadb_gc_last_run_timestamp_seconds > 3600` never fires while GC fails every minute. Set it only on success (and keep `sectoriadb_gc_errors_total` for failures). `sweep` and `compact` do not touch the timestamp at all (doc says "last finished pass").
4. Dashboard "Disk full ETA (linear, 1h window)" (`gen_dashboards.py:331`) uses `deriv(...[15m])`; title or expression is wrong. `Resize`, CRC panels use `[$__interval]` for `increase()` (can be shorter than 2 scrapes => empty series at the 5 s scrape) — use `$__rate_interval` everywhere.
5. `Disk latency (await)` divides by `clamp_min(rate(ops), 1)`: for < 1 op/s the denominator is forced to 1 and latency is under-reported; use `... / rate(ops)` with `and rate(ops) > 0`.
6. Missing alerts for signals the docs call incidents: `increase(sectoriadb_metastore_recoveries_total{result="failure"}[5m]) > 0`, `increase(sectoriadb_gc_errors_total[1h]) > 0`, GC staleness (see 3), `increase(sectoriadb_resize_seconds_count{result="failure"}[1d]) > 0`, `sectoriadb_pool_blobs_max >= limit`.
7. `disk_free/total{dir="data"}` measures only `sectoriadb.data-dir` (`StorageGauges.java:191`); pools created with `mkpool --path DIR` live elsewhere and are not measured.
8. `StorageGauges.snapshot()` (`:146-150`) swallows a failing `compute()` at DEBUG and keeps serving the previous (or all-zero `EMPTY`) snapshot forever: a persistently failing refresh is invisible. Log at WARN once per streak and expose `sectoriadb_gauges_refresh_failures_total`; do not publish zeros for the first failed refresh (return NaN).

### A10 [Med] Compose / Docker run-time issues
* `docker-compose.yml:19,21`: `${SECTORIADB_S3_ACCESS_KEY}`/`${SECTORIADB_S3_CREDENTIALS}` without default => compose warns "variable is not set" and passes blanks; use `${VAR:-}`.
* `docker-compose.yml:32` `cloudlena/s3manager:latest` unpinned, `:35` published on all interfaces with `ALLOW_DELETE: "true"` (README warns; make the safe binding the default `127.0.0.1:8081:8080`). `depends_on` has no health condition (see healthcheck proposal).
* `Dockerfile:37` default `SPRING_SHELL_INTERACTIVE_ENABLED=false` but compose `:25` defaults to `true` with `stdin_open/tty`: behaviour differs between `docker run` and `docker compose`; interactive shell is not mentioned in README Docker section (`docker attach` hint missing — that is how the metastore commands of A1 are used in Docker).
* No graceful shutdown: `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=30s` and `stop_grace_period: 40s` are not set, so `docker stop` can cut uploads (data stays consistent by design, but clients see resets).
* Bench stack `bench/observability/docker-compose.yml:25` `"8080:8080"` on all interfaces with the well-known default keys `BENCHACCESSKEY0001/benchsecretkey...` (`:21`) — bind to `127.0.0.1` or require the variables with `:?`. It also collides with the root compose on ports 8080/9464 (cannot run both).

### A11 [Low-Med] bench/ scripts (quality, robustness)
* `warp/lib.sh:54` `curl --user "$KEY:$SECRET"` and `run.sh:254` `--secret-key` / `docker run ... warp --secret-key` put the secret into the process list. Use `curl --netrc-file <(...)`/`--config -` via stdin and pass warp credentials with `WARP_ACCESS_KEY`/`WARP_SECRET_KEY` environment (`docker run -e`, no value on the command line); same for `faults/lib.sh:42` `-e SECTORIADB_S3_CREDENTIALS=` (less sensitive, test keys).
* `compat/s3tests.sh:18` `S3TESTS_REF:=master` — doc 08 line 620 says the run used commit `5522d1c`: results are not reproducible; pin `S3TESTS_REF=5522d1c...` (full SHA) and `git checkout` it. `:197` the sed expression breaks on secrets containing `|`, `&` or `\`; generate the conf with python/envsubst.
* `faults/run-all.sh:8` `tee "work/${t%.sh}.log"` runs before `ensure_venv` creates `work/` (first script's log lost with a tee error); add `mkdir -p work`. `| tail -n 400` buffers all output until the script ends (no progress for 800 MiB kill tests); use `tee` to the terminal and `tail` only for the summary.
* No `trap` cleanup in `warp/run.sh` (annotation stays "running", own bucket not deleted on Ctrl-C, `delete_bucket` is the leak guard the docs rely on: "otherwise a long matrix exhausts the server heap"), `matrix.sh:43` temp file not removed on interrupt, `faults/kill-*.sh`, `network-faults.sh` leave the container and proxy running when interrupted (only `full-disk.sh` has a trap).
* `warp/run.sh:49` `--help` prints `sed -n '2,25p'` which includes the `set -euo pipefail` and `source` lines; `:55-57` SIZE_LABEL is computed twice because `size_args` runs in a subshell (`lib.sh:143-153`, `SIZE_BYTES_HINT` is dead); `:121` error detection greps human-readable text (`[0-9]+ errors`), prefer warp exit code + `warp analyze --json`.
* `warp/preload.sh:47` `drop` only prints advice (misleading name; implement with `aws s3 rm --recursive` or rename to `help-drop`); `${PASS[@]}` with `set -u` fails on bash < 4.4 (macOS 3.2) when empty; `compare.sh:119` `p="$(ls ... | head -1)"` under `set -e/pipefail` exits silently instead of reaching `die`.
* `bench/warp/validate_metrics.py:20,46` uses the date of `started_utc` for the "starting HH:MM:SS UTC" of the report: wrong day when the prepare phase crosses UTC midnight. `meta.py:12` `subprocess.run(cmd, shell=True)` with an f-string containing `$SECTORIADB_CONTAINER` (local only, but use argv lists).
* `.gitignore` (`bench/faults/network-faults.sh`) ignores a file that is tracked and referenced by `run-all.sh` and doc 08 — remove the entry.
* `observability/prometheus/prometheus.yml`: `instance` label forced to `sectoria-1` (doc 08 section 3 says Prometheus sets it); fine for the stand, keep the doc note.

### A12 [Low] Other
* `Application.java:110-115` defines its own `ObjectMapper` bean; Boot's `JacksonAutoConfiguration` backs off, so **`spring.jackson.serialization.write-dates-as-timestamps=false` (`application.properties:87`) is dead** (the bean disables it manually) and all other `spring.jackson.*` keys would be ignored too. Remove the property or build the mapper with `Jackson2ObjectMapperBuilder`.
* `FileCommands.java:82` `info`: `switch (m.getStorageKind())` throws NPE for legacy manifests with `storageKind == null`, while `ShellTable.kindLabel` explicitly handles null.
* Shell commands print progress with `System.out.printf` (`BlobCommands:132`, `FileCommands:130,137`) instead of the shell's terminal writer: output interleaves badly in the interactive shell and is lost to captured output in tests.
* `RequestObservabilityFilter`: correct (MDC cleaned in `finally`, inflight decremented first, ttfb only for 200/206). No leak found. `S3Metrics.ttfbTimer` is a one-element array (`:29`) — plain `final Timer` field.
* `.env.example:13` is the only Russian comment in an English file.

---------------------------------------------------------------------------------------------------------------------------------

## Dockerfile: healthcheck without extra packages (doc 08 note: `apk add curl` fails behind proxies)

Why the existing (commented) check could never work: `curl -sf http://localhost:${SERVER_PORT}/` hits the S3 port; with auth enabled the answer is `403 AccessDenied`, and `-f` turns it into a failure. The health endpoint is on the management port (9464).
`eclipse-temurin:21-jre-alpine` ships busybox, which contains `wget`; no package is needed:

```dockerfile
# (remove: RUN apk add --no-cache curl)
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -q -T 4 -O /dev/null "http://127.0.0.1:${MANAGEMENT_SERVER_PORT:-9464}/actuator/health" || exit 1
```
* busybox `wget` exits non-zero on HTTP 503 (DOWN), so the check reflects actuator status; `HEALTHCHECK CMD` shell form is run by `/bin/sh -c`, so `${MANAGEMENT_SERVER_PORT:-9464}` is expanded at run time.
* Verify once with `docker run --rm eclipse-temurin:21-jre-alpine wget --help` (applet present in all alpine-based Temurin tags).
* Make `/actuator/health` meaningful: add a `HealthIndicator` that reports DOWN when the metastore is poisoned/recovering (`MetaStore.isHealthy()`/recovery state from stage 10 B2), when the data dir is not writable or free space < 1 chunk; Boot's default `diskSpace` checks the working directory (`/app`), not `/data`. Use `management.endpoint.health.group.liveness/readiness` if orchestrated.
* Compose: `healthcheck:` (same command) + `depends_on: sectoriadb: condition: service_healthy` for `s3manager`.
* Fallback if busybox is ever replaced (distroless): a 20-line `HealthProbe` main class in the jar (`java -cp /app/sectoriadb.jar org.example.sectoriadb.HealthProbe`) using `HttpURLConnection` — slower (JVM start) so use `--interval=60s`.

Other Dockerfile fixes: `COPY --from=builder /build/sectoriadb-server/target/SectoriaDB-*.jar sectoriadb.jar` (line 23 hard-codes `1.0-SNAPSHOT` while the pom's `finalName` is `SectoriaDB-${project.version}`); `RUN addgroup -S sectoria && adduser -S -G sectoria sectoria && mkdir -p /data/meta /data/storage /app/work && chown -R sectoria:sectoria /data /app` + `USER sectoria`;
drop the five `-D...` flags in `ENTRYPOINT` (relaxed binding already maps `SECTORIADB_*`/`SERVER_PORT` env vars, and `docker compose exec ... java -jar` — which bypasses ENTRYPOINT — relies on exactly that), i.e. `ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/sectoriadb.jar"]`; `JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC"`; BuildKit cache mount for `~/.m2`: `RUN --mount=type=cache,target=/root/.m2 mvn -B package -DskipTests -q`.
`.dockerignore`: add `bench/`, `docs/`, `**/work/`, `*.md`, `.mvn/wrapper` — the context is 92 MB, of which `bench/faults/work` (venv) is 61 MB, and it is uploaded on every `docker compose build`.

---------------------------------------------------------------------------------------------------------------------------------

## B. Dead / undocumented config, dead code, dependencies

| Item | Evidence | Fix |
|---|---|---|
| `StorageProperties.S3.authEnabled`, `.region` (+ getters/setters) | `StorageProperties.java:198-209`; only `getS3().getCredentials/getAccessKey/getSecretKey` are used (`Application.java:73-97`). Relaxed binding maps the field to `sectoriadb.s3.auth-enabled`, but the real key is `sectoriadb.s3.auth.enabled` read by `@Value` in `SigV4Filter.java:66-69`; `region` also by `@Value` in `S3AclController.java:40` | Move **all** S3 settings into `StorageProperties.S3` with nested `Auth{enabled, allowOpenSetup, allowUnsignedPayload}`; constructor-inject; delete the `@Value`s |
| `sectoriadb.metastore.group.*`, `recovery-backoff`, `lazy`, `sectoriadb.reconcile-on-start` | read by `@Value` in `MetaStoreConfig.java:44-48,80` and `StartupReconciler.java:26-27`; `lazy` and `reconcile-on-start` are missing from application.properties and README (only docs 07/10) | add `Metastore{group{...}, recoveryBackoff}` to `StorageProperties`, list `reconcile-on-start` in properties; mark `metastore.lazy` as internal |
| Default values duplicated 3-4 times | e.g. check interval `60000`: field default (`StorageProperties:227`), `@Scheduled` default (`AutoResizeScheduler:43`), application.properties, README; `gc.enabled` default `false` in the class but `true` in properties | one source of truth (properties class), no defaults in `@Value`/`@Scheduled`; test that application.properties equals class defaults |
| No `spring-boot-configuration-processor` | IDE/doc metadata missing; docs drift (A8) | add as `<optional>true</optional>` in core pom, generate `configuration.md` table from `spring-configuration-metadata.json` |
| Dead property | `spring.jackson.serialization.write-dates-as-timestamps` (A12) | delete |
| Redundant property | `logging.level.org.springframework.web.servlet.mvc.method.annotation=DEBUG` (child of the previous line) and no trailing newline at EOF of application.properties | delete |
| Undocumented Tomcat/DoS knobs | `server.tomcat.connection-timeout=10m`, `keep-alive-timeout`, `max-keep-alive-requests` documented only as comments; `server.tomcat.threads.max`, `max-connections`, `max-swallow-size` not set though doc 08 section 24 lists "slow bodies exhaust the 200 Tomcat threads" as open for stage 11 | document + set sane limits, mention in README "Безопасность" |
| `ROADMAP` / README do not list the "Тесты" count or Java/Maven requirements consistently | README says "JDK 21 и Maven" but there is no `.mvn/wrapper`, no `maven-enforcer` | add `.mvn/` (wrapper + `jvm.config`) and enforcer rule `requireJavaVersion [21,)` |
| Surefire `<exclude>**/*IT.java</exclude>` | root `pom.xml:49-53`; no `*IT` class exists (`SectoriaDBIntegrationTest` does not match) and no failsafe | delete, or rename Spring tests `*IT` and add failsafe (separates the ~25 s of context tests) |
| Redundant dependency | `sectoriadb-server/pom.xml:57-61` `junit-jupiter` already comes with `spring-boot-starter-test` | delete |
| Stale module description | `sectoriadb-core/pom.xml:15` "JSON metadata repositories" | "Storage engine: cuckoo blobs, small-object logs, transactional metastore" |
| `spring-shell.version` as a plain property with an explicit version on the starter | root `pom.xml:29`, server pom `:27` | import `org.springframework.shell:spring-shell-dependencies:${spring-shell.version}` in `dependencyManagement` (or drop the explicit version if Boot 3.3.4 manages it — it does not; keep BOM import) |
| Unused dependencies | checked jackson-databind/jsr310 (used by `JsonValues`, `Application`), actuator + micrometer-registry-prometheus (used), jackson-dataformat-xml (used by `XmlSupport`/xml DTOs) | none found |
| Dead code in scripts | `SIZE_BYTES_HINT` (`lib.sh:145-150`), `bench/faults` `check`-style helpers all used; `S3Metrics.ttfbTimer[]` array | delete |
| Dead imports | 32 unused imports, see appendix and section D | Spotless |

Plugin/test configuration suggestions (pom): add `maven-enforcer-plugin` (`requireJavaVersion [21,)`, `requireMavenVersion [3.9,)`, `dependencyConvergence`, `banDuplicatePomDependencyVersions`); surefire `<argLine>-Xmx1g -Djava.io.tmpdir=${project.build.directory}/tmp</argLine>` plus `<trimStackTrace>false</trimStackTrace>` and `<forkedProcessTimeoutInSeconds>600</forkedProcessTimeoutInSeconds>` (a hung committer thread would otherwise block CI forever); `maven-compiler-plugin` `<compilerArgs><arg>-Xlint:all</arg><arg>-parameters</arg>` (Boot parent sets `-parameters`) and `<failOnWarning>` later; `build-info` goal on the Boot plugin (commit id for `/actuator/info`, also useful to bench `meta.json`). No CI file exists (`.github/` absent): `mvn -B verify` + Spotless check + Checkstyle imports + the doc-consistency test (E) is the minimum.

---------------------------------------------------------------------------------------------------------------------------------

## C. Clean code in these areas

1. **Shell table formatting duplicated.** Header/separator/row formats are hand-written 8 times: `CredentialCommands.java:60-61,81-85,93-97` (two variants of the key table with 80/140/90 dash separators), `PoolCommands.java:55-56,59-61`, `StatusCommands.java:58,167-171`, `ShellTable.java:19-20,58-59,69-81`; every width literal is repeated in header and row.
   Proposal: one tiny `TextTable` (columns with width/alignment, `addRow(Object...)`, `render()`; separator length = sum of widths) used by all commands; Spring Shell 3 already ships `spring-shell-table` (`TableBuilder`/`ArrayTableModel`, in `~/.m2`) — use it instead of hand-rolled `%-36s` strings. Move `humanSize`/`truncate`/`formatDuration` (StatusCommands:196-201) into `ShellFormat`.
2. **Command classes are not long (<= 202 lines) but mixed.** Split by responsibility rather than size: `StatusCommands` -> `StatusCommands` (status), `ConfigCommands` (config), `LogCommands` (logs, audit); `FileCommands` -> `FileCommands` (store/ls/info/get/rm) + `VerifyCommands` (verify/verify-all, matching `VerifyCommandsTest`); `CredentialCommands`/`AccessCommands`/`GcCommands`/`PoolCommands`/`BlobCommands` stay. Rename misnamed methods (`settingsShow` -> `config`, `logsTail` -> `logs`, `logsQuery` -> `audit`).
   Put the "CLI-capable commands" classification next to the commands (`@CliCommand` marker annotation or interface) instead of the hard-coded `Set` in `Application` (A1).
3. **Confirmation boilerplate** `if (!yes) return "Add '--yes' ..."` in `rm`, `rmblob`, `rmpool`, `rm-key` -> `Confirm.required(yes, "deletion of pool 'x'")`.
4. **`config` command output is hand-maintained** (`StatusCommands.java:82-116`) and lists 15 of ~35 properties (no `fsync`, metastore group, gc intervals/grace limits, observability): generate it from `StorageProperties` (Spring `Binder`/metadata) so it cannot drift (A8).
5. **`Application`** mixes bootstrap, CLI detection, credential seeding and bean definitions; extract `CliMode` (system-property switches + command set), `CredentialBootstrap` (ApplicationRunner, A4), keep `Application` at `main`+annotations; no `java.util.Set` FQN (`:53`).
6. **Observability**: `MicrometerStorageMetrics` constructor is 90 lines of near-identical `Counter.builder(...)`; several metrics are registered twice with only a tag changed (`resize`, `gc.run`, `gc.compaction`, `metastore.recoveries`, `gc.freed.*` x3). Introduce `counter(name, desc, tags...)`/`timer(...)` helpers and `EnumMap<Result, Timer>` ("success"/"failure") to cut ~60 lines and the copy-paste description strings; `StorageGauges.Snapshot` has 33 positional `long` components and `EMPTY` is 33 zeros — use a small builder or nested records (`MetaStats`, `SmallStats`, `ChunkStats`).
7. **Scheduler**: `GarbageCollectionScheduler.Schedule` bean exists only to feed SpEL (`#{@gcSchedule...}`): switch to `SchedulingConfigurer` + `TaskScheduler.scheduleWithFixedDelay(Runnable, Instant, Duration)`, which also gives one place for the try/catch/log wrapper repeated in the three methods (`:36-60`).
8. **bench**: `lib.sh` + `run.sh` duplicate size-class parsing; four scripts repeat `HERE=...; source lib.sh`; python scripts duplicate the "read analyze.txt window" logic (`validate_metrics.py:37-47`, `window_metrics.py:21-26`) — move to `bench/warp/analyze_window.py`. Python files use `import a, b, c` one-liners and `ap.add_argument` on one line (fine for scripts, but `ruff`/`shellcheck` in CI would catch real issues: add `shellcheck bench/**/*.sh`).

---------------------------------------------------------------------------------------------------------------------------------

## Tests: structure, helpers, flakiness (both modules)

* **Helper duplication (core)**: `bytes(int n, long seed)` / `content(chunks, seed)` / `data(seed)` / `payload(key)` are re-implemented in ~20 test classes (CuckooDurabilityTest:70, TableResidencyTest:63, ResizeSafetyTest:76, ChunkIndexTest:52,59, GarbageCollectionTest:70,76, SmallCompactionTest:63, MultiBlobPoolTest:66, SmallObjectBlobTest:38, ObjectChecksumStorageTest:87, ChunkIntegrityTest:51, EngineMetricsTest:47, SmallObjectGroupFsyncTest:35, GroupCommitModelTest:429 ...). -> one `TestData` (`bytes(seed, n)`, `chunks(n, seed)`, `randomKeys`).
  `StorageRig` (131 lines, 17 inline FQNs, public mutable fields) is used by 10 classes — good, but `ChunkInvariants`/`MetaInvariants` (assert helpers) live next to it in `repository.metastore`; give the three a `testsupport` package (also `RecordingMetrics`, currently in the root test package).
  `RecordingMetrics` has 35 hand-written counters mirroring the SPI (`StorageMetrics` has default methods); replace by a `Proxy`/small generic `EventCounter` (`count("tableFull")`) or keep but generate. `new StorageProperties()` is built by hand in 3 files.
* **Helper duplication (server)**: 16 `@SpringBootTest` classes each declare `@TempDir static Path tempRoot` + a 6-8 line `@DynamicPropertySource` with the same keys (`meta-dir`, `data-dir`, `auto-resize.enabled=false`, `default-num-buckets`, `default-chunk-size`, `s3.auth.enabled`, `spring.shell.interactive.enabled=false`), `random(n, seed)` x5 (GarbageCollectionS3Test:66, S3ApiRegressionTest:84, S3ChecksumsTest:100, S3SmallObjectsTest:69, S3StreamingTest:59), `sha()`, `upload()`, `uri()`, `send()` x3-4, plus `SigV4TestClient` (165 lines, fine).
  Because the property lambda and temp dir differ per class, each class gets its **own cached Spring context** (Tomcat x2, metastore, committer thread, schedulers incl. GC) that lives until the JVM ends: 16 contexts. Introduce an abstract `S3IntegrationTest` (or a meta-annotation `@S3Test`) holding the static `@TempDir`, shared properties via `@DynamicPropertySource` (subclasses add overrides), `http`, `uri`, `random`, `sha`; where the same configuration is needed, contexts are then shared (`@DirtiesContext` only where needed). Set `sectoriadb.gc.enabled=false` in the test `config/application.properties` (scheduler is irrelevant to those tests, and `S3MetricsTest` calls `gc.run` by hand).
* **Sleep/timing patterns** (24 sites): `GroupCommitTest:71` (`Thread.sleep(100)` "let the committer take them all into one batch" — then asserts exact batch sizes in 7 tests; on a loaded runner the committer may not have dequeued -> flaky), `:383,507` (`sleep(200/300)` + `assertTrue(isAlive)` negative assertions), `ResizeSafetyTest:120,291` (`sleep(300)` + `assertFalse(isDone)`), `GarbageCollectionTest:213-216` (grace 300 ms, `sleep(400)`, assertion "garbage for a few milliseconds only" breaks if > 300 ms pass between delete and run), `:253,426,436`, `ResidentCacheTest:176`, `S3MetricsTest:294` (polling loop, OK), `S3StreamingTest:85` (deliberate 2 s pause, comment "4x the async timeout" is stale), `CuckooDurabilityTest:219`, `CrashSimulation:112` (randomized delays).
  Fixes: (1) inject a `java.time.Clock`/`LongSupplier now` into `GarbageCollector` and `SmallBlobCompactor` (they call `System.currentTimeMillis()` at `GarbageCollector.java:124,140,158,307`, `SmallBlobCompactor.java:179,245,319,355`) so grace tests use a manual clock (this also removes the 400 ms sleep and the 15 min default-grace dependency); (2) replace "let X happen" sleeps by awaiting an observable condition (thread state `WAITING`/`BLOCKED`, `MetaStore` queue depth accessor, `CountDownLatch` set inside the hook); (3) keep sleeps only for *negative* assertions and make them one shared `Await.notDoneFor(future, 200ms)` helper; add Awaitility (`org.awaitility:awaitility`, managed by the Boot BOM) for polling.
* **Stale artifacts**: `target/surefire-reports` of core contains `TmpIndexPerfTest` (9.7 s) that has no source file any more — run `mvn clean` before trusting timings.
* **Tmp usage**: tests are clean (`@TempDir`); only the production spool files go to the JVM tmpdir (A6). Set `-Djava.io.tmpdir=${project.build.directory}/tmp` in surefire `argLine`.
* **Slowest**: GroupCommitTest 15.7 s (32 threads x 500 increments with fsync), ObjectListingTest 10.6 s (20 000 objects; build the fixture once with `@BeforeAll`), SmallCompactionTest 3.9 s. Mark long randomized ones `@Tag("slow")` and run them in the `verify` phase only if the local loop gets slower.

---------------------------------------------------------------------------------------------------------------------------------

## D. Imports: findings and tool-enforced order

Measured over all 223 `.java` files (main+test, both modules) with `imports.py` (outputs in `imports_out.txt`; full lists are in the appendix):

| Check | Result |
|---|---|
| Duplicate imports (exact same import twice) | **0** |
| Redundant imports (same package or `java.lang`) | **0** |
| Wildcard imports | **59 imports in 46 files**: 40 static (`Assertions.*` x39, `SmallBlobLayout.*` x1) + **19 non-static in 12 files** |
| Unused imports | **32 in 20 files** (the check counts a name appearing even in comments/Javadoc as used, so the real number is >= 32) |
| Order violations | **35 files**: 24 with a group out of order (e.g. `java.*` before `org.*` or `jakarta.*` after `org.*`), 11 not alphabetical inside a group |
| Two coexisting layouts | A: `others / blank / java.* / [blank / static]` (117 of 133 files with both groups); B (metastore tests + `ShellTable`, `S3Support`, `S3Checksums`...): static first, then one block, no blank lines, java and org interleaved |
| Inline fully-qualified names instead of imports | **296 occurrences in 68 files** (top: `S3SigV4PayloadTest` 29, `SigV4Filter` 19, `StorageRig` 17, `SmallObjectStorageTest` 16, `FileStorageService` 15) |

Convention to adopt = the existing majority layout A with static last: **all non-`java`/`javax` imports alphabetically (ASCII), blank line, `java`+`javax`, blank line, static imports**; no star imports except `static org.junit.jupiter.api.Assertions.*` (39 test files) and the one `SmallBlobLayout.*` constants import; no unused imports.

Plugin availability (checked read-only): `~/.m2/repository` contains **none** of Spotless, Checkstyle, google-java-format, OpenRewrite (it has only antrun, assembly, clean, compiler 3.13.0, dependency, deploy, install, jar 3.4.2, resources, shade, site, surefire 3.2.5 plugins). Maven 3.9.11 / JDK 21.0.12 are installed. `repo.maven.apache.org` is reachable through the sandbox proxy (HTTP 200 on `com/diffplug/spotless/spotless-maven-plugin/maven-metadata.xml`, latest 3.10.3; `com/puppycrawl/tools/checkstyle` latest 14.3.0; `maven-checkstyle-plugin` 3.6.0 listed; one probe returned 429 = rate limit, not absence), so the plugins **can be resolved with network access but are not available offline**; the first run downloads them (and google-java-format for Spotless).

### Recommended: Spotless (import order + unused imports, no whole-file reformatting) + Checkstyle for star imports

Spotless `importOrder` and `removeUnusedImports` rewrite **only the import block**, so `spotless:apply` produces a reviewable, imports-only diff (do not add a `googleJavaFormat`/`eclipse` formatter step: that would reformat files).
Put in the **parent pom** `<build><plugins>` (inherited by both modules):

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <version>3.10.3</version>              <!-- latest on Central when checked; any 2.4x/3.x works on Java 21 -->
  <configuration>
    <java>
      <includes>
        <include>src/main/java/**/*.java</include>
        <include>src/test/java/**/*.java</include>
      </includes>
      <importOrder>
        <!-- "" = every other import (alphabetical); then java/javax; then static imports -->
        <order>,java|javax,\#</order>
      </importOrder>
      <removeUnusedImports/>
    </java>
  </configuration>
  <executions>
    <execution>
      <id>spotless-check</id>
      <phase>verify</phase>            <!-- use "validate" to fail fast; keep apply manual -->
      <goals><goal>check</goal></goals>
    </execution>
  </executions>
</plugin>
```
* Apply once: `mvn spotless:apply` (rewrites imports only); commit as "chore: normalize imports" **separately** and list the commit in `.git-blame-ignore-revs`. Daily: `mvn spotless:check` (CI), `mvn spotless:apply` before committing.
* If `|` is not accepted by the resolved version use three groups `,java,javax,\#` (javax then forms its own block — only `javax.xml` in 2 server classes). Check the generated block on 2-3 files before committing the bulk change.
* `removeUnusedImports` uses google-java-format internally; on JDK 16+ that needs the `jdk.compiler` exports. If you see `IllegalAccessError`, create `.mvn/jvm.config` containing one line:
  `--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED`
  or use `<removeUnusedImports><engine>CLEANTHAT_JAVAPARSER_UNNECESSARY_IMPORT</engine></removeUnusedImports>` (no exports needed).
  The `.mvn/` directory also anchors `maven.multiModuleProjectDirectory`, needed below.
* Optional gradual mode: `<ratchetFrom>origin/main</ratchetFrom>` checks only files changed relative to the base branch (not needed if the bulk apply is done).

Star imports cannot be auto-fixed by Spotless; enforce them with Checkstyle (also catches unused/redundant imports as a second line of defence):

`config/checkstyle-imports.xml`
```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
  "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
  <property name="severity" value="error"/>
  <module name="TreeWalker">
    <!-- static star imports (JUnit Assertions) are allowed, every other star import is an error -->
    <module name="AvoidStarImport"><property name="allowStaticMemberImports" value="true"/></module>
    <module name="RedundantImport"/>
    <module name="UnusedImports"><property name="processJavadoc" value="true"/></module>
  </module>
</module>
```
parent pom:
```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-checkstyle-plugin</artifactId>
  <version>3.6.0</version>
  <dependencies>
    <dependency><groupId>com.puppycrawl.tools</groupId><artifactId>checkstyle</artifactId>
                <version>10.21.4</version></dependency>   <!-- Java 11+; 13.x/14.x also fine on JDK 21 -->
  </dependencies>
  <configuration>
    <configLocation>${maven.multiModuleProjectDirectory}/config/checkstyle-imports.xml</configLocation>
    <includeTestSourceDirectory>true</includeTestSourceDirectory>
    <failsOnError>true</failsOnError>
    <violationSeverity>error</violationSeverity>
    <consoleOutput>true</consoleOutput>
  </configuration>
  <executions>
    <execution><id>checkstyle-imports</id><phase>validate</phase><goals><goal>check</goal></goals></execution>
  </executions>
</plugin>
```
Fixing the 19 non-static star imports: IntelliJ "Optimize imports" with *Settings > Code Style > Java > Imports*: "Use single class import", "Class count to use import with '*'" = 999 and "Names count to use static import with '*'" = 999 (keep `Assertions.*` by adding it to "Packages to Use Import with '*'"), then Spotless normalizes the order. The set is small and listed in the appendix (12 files).
Inline FQNs (296) are not detected by any of these tools; fix opportunistically (IntelliJ "Replace qualified name with import" over the project, or a one-off script) — highest-value files: `S3SigV4PayloadTest`, `SigV4Filter`, `StorageRig`, `SmallObjectStorageTest`, `FileStorageService`.

### Fallback without new plugins (works fully offline)
A JUnit test in the core module that scans `src/main/java` and `src/test/java` of both modules (`../sectoriadb-server/src/...` via `System.getProperty("user.dir")`) and fails on: a non-static `import x.*;`, an unused import (simple name not found in the rest of the file), a duplicate import, group order violation. It is the same logic as `imports.py` (~60 lines, regex only, runs in < 1 s). Downside: no auto-fix, and it lives in a test run instead of the `validate` phase.

---------------------------------------------------------------------------------------------------------------------------------

## E. Documentation structure for the final state

Problems today: 21 000 words in 12 stage documents written as change logs ("what was broken -> what we did -> results of the sandbox run"), so the *current* behaviour is spread over README + 10 docs and statements contradict each other (A8). Doc 08 alone is 7 200 words and mixes four different things (metric catalog, benchmark stand, compatibility results, fault results + dated sandbox measurements). README is a mix of tutorial, reference and architecture. Stage numbers appear in prose ("до этапа 10", "09a", "doc 09 часть B") which means nothing to a newcomer.

Proposal (current-state documentation + history; keep stage docs verbatim as history):

```
README.md                       <= 150 lines: what it is, 5-minute Docker quick start, keys, AWS CLI, link table. No config table, no internals.
docs/
  README.md                     index + reading order (below)
  guide/
    s3-usage.md                 ex S3-GUIDE (public access, presign, policies); correct which admin commands are online/offline (A1)
    admin-shell.md              every console command, grouped, marked ONLINE (credentials) / OFFLINE-ONLY (metastore) / interactive-only
    operations.md               Docker, volumes, memory sizing (A5), tmp dir, logs, backup, upgrade, healthcheck, ports/security checklist
    configuration.md            ONE table of all properties (generated/verified against StorageProperties), env var names, defaults, "restart needed"
    monitoring.md               ports, how to scrape, dashboards, alert rules and thresholds (14.5), log/MDC/slow-request (doc 08 sections 1-3, 8, 11)
  architecture/
    00-overview.md              target diagram (ROADMAP + README "Как это устроено"), PUT/GET data path, components and packages, invariants, failure model summary, glossary
    storage-engine.md           cuckoo blob format v2, CRC32C, chunking/dedup, XXH64 keys (from 01, 03), engine IO/fsync rules
    small-objects.md            (04)
    metastore.md                COW B+-tree, MVCC, group commit, recovery in place (06 + 09A + 10 B2)
    metadata-model.md           trees/indexes, transactions per S3 operation (07)
    pool-placement.md           HRW, weights, chunk index/refcounts, growth (09 B)
    garbage-collection.md       queues, grace period, upload gate, sweep, compaction, safe resize, table residency (10)
    security.md                 SigV4, policies, open setup, payload checks (02)
    integrity-and-checksums.md  layers + S3 checksums (05)
    observability.md            metric catalog (generated table), labels/cardinality rules, histogram bucket rationale, PromQL cookbook (08 sections 1-13)
  testing/
    benchmarking.md            stand, scripts, methodology (08 sections 14.x)
    compatibility.md          s3-tests / mint procedure + current known gaps (08 sections 15-16)
    fault-injection.md        faults/ scripts, expected results, security probes (08 sections 17-18)
  reports/
    2026-10-sandbox-measurements.md   all dated numbers (08 "Результаты", 09 "Замеры", 10 measurements) with hardware caveat
  history/
    ROADMAP.md                audit table of problems C1..S5 and what fixed them
    01-engine-safety.md ... 10-gc-and-resize.md   unchanged stage documents, each with a banner: "Historical record of stage N; the current description is <link>"
  CHANGELOG.md                one entry per stage 01-11: date, branch, problems closed, behaviour/config changes, link to the history doc
```
What a newcomer reads first: `README.md` -> `docs/guide/s3-usage.md` (use it) -> `docs/guide/operations.md` + `configuration.md` (run it) -> `docs/architecture/00-overview.md` (understand it) -> one subsystem doc on demand -> `CHANGELOG.md` for "what changed when". Contributors: `docs/testing/*` and `docs/history/ROADMAP.md`.
Rules to keep it consistent: (1) one fact in one place, others link; (2) no stage numbers in current-state docs (only in CHANGELOG/history); (3) dated measurements only under `reports/`; (4) generated/verified parts: the metric catalog and `configuration.md` are checked by a test.

Cheap consistency test (offline, I already ran the logic by hand; it found no metric drift but did find the property/doc gaps in A8/B): a `DocsConsistencyTest` in the server module that (a) collects `"sectoriadb\.[a-z0-9.]+"` metric names from main sources, converts them to Prometheus names and asserts each appears in `docs/architecture/observability.md` and each `sectoriadb_*` in docs/dashboards/alerts exists; (b) reflects over `StorageProperties`/`ObservabilityProperties` (or reads `spring-configuration-metadata.json`) and asserts every property appears in `configuration.md` and in `application.properties`; (c) asserts all relative markdown links resolve (the Python in this review did exactly that: 0 broken now).

Minimal first step if there is no time for the restructuring in this stage: add the history banners, fix A8, move doc 08 sections "Стенд и бенчмарки/Совместимость/Отказы/Результаты" into three files, create `CHANGELOG.md` from the ROADMAP stage table, and add `docs/README.md` with the reading order.

---------------------------------------------------------------------------------------------------------------------------------

## Appendix 1. All wildcard imports (59; "static" = static import) - file, line, import

- core/main/java/service/impl/SmallObjectBlob.java 22 static org.example.sectoriadb.format.SmallBlobLayout.*
- core/test/java/ChecksumAlgorithmTest.java 16 static org.junit.jupiter.api.Assertions.*
- core/test/java/ChunkIntegrityTest.java 28 static org.junit.jupiter.api.Assertions.*
- core/test/java/ChunkingServiceTest.java 11 static org.junit.jupiter.api.Assertions.*
- core/test/java/CuckooFreeSlotTest.java 22 static org.junit.jupiter.api.Assertions.*
- core/test/java/CuckooHashTableTest.java 23 static org.junit.jupiter.api.Assertions.*
- core/test/java/CuckooInsertSafetyTest.java 32 static org.junit.jupiter.api.Assertions.*
- core/test/java/EngineMetricsTest.java 28 static org.junit.jupiter.api.Assertions.*
- core/test/java/FileChannelStorageIOEngineTest.java 13 static org.junit.jupiter.api.Assertions.*
- core/test/java/ObjectChecksumStorageTest.java 13 org.example.sectoriadb.repository.*
- core/test/java/ObjectChecksumStorageTest.java 14 org.example.sectoriadb.service.*
- core/test/java/ObjectChecksumStorageTest.java 18 org.example.sectoriadb.repository.metastore.*
- core/test/java/ObjectChecksumStorageTest.java 33 static org.junit.jupiter.api.Assertions.*
- core/test/java/SmallObjectBlobTest.java 16 java.util.concurrent.*
- core/test/java/SmallObjectBlobTest.java 18 static org.junit.jupiter.api.Assertions.*
- core/test/java/SmallObjectGroupFsyncTest.java 16 java.util.concurrent.*
- core/test/java/SmallObjectGroupFsyncTest.java 19 static org.junit.jupiter.api.Assertions.*
- core/test/java/SmallObjectStorageTest.java 10 org.example.sectoriadb.repository.*
- core/test/java/SmallObjectStorageTest.java 11 org.example.sectoriadb.service.*
- core/test/java/SmallObjectStorageTest.java 15 org.example.sectoriadb.repository.metastore.*
- core/test/java/SmallObjectStorageTest.java 27 static org.junit.jupiter.api.Assertions.*
- core/test/java/placement/RendezvousPlacementTest.java 12 static org.junit.jupiter.api.Assertions.*
- core/test/java/repository/metastore/ChunkIndexTest.java 22 java.util.concurrent.*
- core/test/java/repository/metastore/ChunkIndexTest.java 24 static org.junit.jupiter.api.Assertions.*
- core/test/java/repository/metastore/ChunkInvariants.java 16 static org.junit.jupiter.api.Assertions.*
- core/test/java/repository/metastore/CuckooDurabilityTest.java 31 static org.junit.jupiter.api.Assertions.*
- core/test/java/repository/metastore/GarbageCollectionTest.java 37 static org.junit.jupiter.api.Assertions.*
- core/test/java/repository/metastore/MetaStoreRepositoriesTest.java 25 static org.junit.jupiter.api.Assertions.*
- core/test/java/repository/metastore/MultiBlobPoolTest.java 32 java.util.concurrent.*
- core/test/java/repository/metastore/MultiBlobPoolTest.java 35 static org.junit.jupiter.api.Assertions.*
- core/test/java/repository/metastore/ObjectListingTest.java 25 static org.junit.jupiter.api.Assertions.*
- core/test/java/repository/metastore/ResizeSafetyTest.java 33 static org.junit.jupiter.api.Assertions.*
- core/test/java/repository/metastore/SmallCompactionTest.java 33 static org.junit.jupiter.api.Assertions.*
- core/test/java/repository/metastore/StorageIntegrationTest.java 27 static org.junit.jupiter.api.Assertions.*
- core/test/java/repository/metastore/TableResidencyTest.java 27 static org.junit.jupiter.api.Assertions.*
- server/main/java/s3/S3AclController.java 13 org.springframework.web.bind.annotation.*
- server/main/java/s3/S3BucketController.java 14 org.springframework.web.bind.annotation.*
- server/main/java/s3/S3MultipartController.java 24 org.springframework.web.bind.annotation.*
- server/main/java/s3/S3MultipartController.java 31 java.io.*
- server/main/java/s3/S3MultipartController.java 32 java.nio.file.*
- server/main/java/s3/S3MultipartController.java 36 java.util.*
- server/main/java/s3/S3ObjectController.java 23 org.springframework.web.bind.annotation.*
- server/main/java/s3/auth/SigV4Filter.java 27 java.util.*
- server/main/java/s3/auth/SigV4Utils.java 12 java.util.*
- server/test/java/AccessControlServiceTest.java 6 static org.junit.jupiter.api.Assertions.*
- server/test/java/GarbageCollectionS3Test.java 25 static org.junit.jupiter.api.Assertions.*
- server/test/java/MetaStoreRecoveryS3Test.java 23 static org.junit.jupiter.api.Assertions.*
- server/test/java/OperationClassifierTest.java 12 static org.junit.jupiter.api.Assertions.*
- server/test/java/S3ApiRegressionTest.java 28 static org.junit.jupiter.api.Assertions.*
- server/test/java/S3ChecksumsTest.java 38 static org.junit.jupiter.api.Assertions.*
- server/test/java/S3ExceptionHandlerTest.java 10 static org.junit.jupiter.api.Assertions.*
- server/test/java/S3MetricsTest.java 36 static org.junit.jupiter.api.Assertions.*
- server/test/java/S3PublicAccessTest.java 23 static org.junit.jupiter.api.Assertions.*
- server/test/java/S3SecurityHardeningTest.java 21 static org.junit.jupiter.api.Assertions.*
- server/test/java/S3SigV4PayloadTest.java 19 static org.junit.jupiter.api.Assertions.*
- server/test/java/S3SmallObjectsTest.java 25 static org.junit.jupiter.api.Assertions.*
- server/test/java/S3StreamingTest.java 25 static org.junit.jupiter.api.Assertions.*
- server/test/java/SectoriaDBIntegrationTest.java 22 static org.junit.jupiter.api.Assertions.*
- server/test/java/VerifyCommandsTest.java 20 static org.junit.jupiter.api.Assertions.*

## Appendix 2. Duplicate imports

None (0) in the 223 files; also none redundant (same package / java.lang).

## Appendix 3. Unused imports (32)

- core/main/java/service/BlobService.java 18 java.nio.ByteBuffer
- core/main/java/service/BlobService.java 19 java.nio.channels.FileChannel
- core/main/java/service/BlobService.java 22 java.nio.file.StandardOpenOption
- core/main/java/service/FileStorageService.java 17 org.example.sectoriadb.model.FileManifest
- core/main/java/service/FileStorageService.java 19 org.example.sectoriadb.service.impl.BlobFileWriteService
- core/main/java/service/FileStorageService.java 20 org.example.sectoriadb.service.impl.CuckooHashTable
- core/main/java/service/FileStorageService.java 21 org.example.sectoriadb.service.impl.ChunkNotFoundException
- core/main/java/service/FileStorageService.java 22 org.example.sectoriadb.service.impl.DefaultChunkingService
- core/main/java/service/FileStorageService.java 24 org.example.sectoriadb.tools.XxHash64BytesHasher
- core/main/java/service/ResizeService.java 10 org.example.sectoriadb.service.impl.FileChannelStorageIOEngine
- core/test/java/CuckooHashTableTest.java 15 java.nio.channels.FileChannel
- core/test/java/CuckooHashTableTest.java 17 java.nio.file.StandardOpenOption
- core/test/java/CuckooInsertSafetyTest.java 16 java.nio.channels.FileChannel
- core/test/java/CuckooInsertSafetyTest.java 20 java.nio.file.StandardOpenOption
- core/test/java/SmallObjectStorageTest.java 12 org.example.sectoriadb.service.impl.SmallObjectCorruptedException
- core/test/java/repository/metastore/ChunkIndexTest.java 10 org.example.sectoriadb.service.impl.CuckooHashTable
- core/test/java/repository/metastore/CuckooDurabilityTest.java 20 java.util.HashMap
- core/test/java/repository/metastore/GarbageCollectionTest.java 9 org.example.sectoriadb.service.UploadGate
- core/test/java/repository/metastore/GarbageCollectionTest.java 10 org.example.sectoriadb.service.gc.GcReports
- core/test/java/repository/metastore/MultiBlobPoolTest.java 3 org.example.sectoriadb.config.StorageProperties
- core/test/java/repository/metastore/ResizeSafetyTest.java 23 java.util.Set
- server/main/java/s3/S3BucketController.java 19 javax.xml.parsers.DocumentBuilderFactory
- server/main/java/s3/S3MultipartController.java 30 javax.xml.parsers.DocumentBuilderFactory
- server/main/java/s3/S3Support.java 15 java.util.UUID
- server/main/java/shell/GcCommands.java 6 org.example.sectoriadb.service.gc.GcReports
- server/main/java/shell/PoolCommands.java 11 org.example.sectoriadb.service.impl.CuckooHashTable
- server/main/java/shell/StatusCommands.java 5 org.example.sectoriadb.model.BlobKind
- server/main/java/shell/StatusCommands.java 14 org.example.sectoriadb.service.impl.CuckooHashTable
- server/test/java/MetaStoreRecoveryS3Test.java 20 java.util.Arrays
- server/test/java/S3ApiRegressionTest.java 5 org.springframework.beans.factory.annotation.Value
- server/test/java/S3ChecksumsTest.java 28 java.util.ArrayList
- server/test/java/S3ChecksumsTest.java 31 java.util.List

## Appendix 4. Files with inconsistent import order (35)

- core/main/java/service/BlobService.java alphabetical within group
- core/main/java/service/FileStorageService.java alphabetical within group
- core/main/java/service/HashTableCache.java group order (java.* / static not last)
- core/main/java/service/SmallBlobCache.java group order (java.* / static not last)
- core/main/java/service/gc/GarbageCollector.java alphabetical within group
- core/main/java/service/impl/CuckooHashTable.java alphabetical within group
- core/test/java/EngineMetricsTest.java alphabetical within group
- core/test/java/ObjectChecksumStorageTest.java alphabetical within group
- core/test/java/SmallObjectStorageTest.java alphabetical within group
- core/test/java/metastore/CrashSafetyTest.java group order (java.* / static not last)
- core/test/java/metastore/GroupCommitModelTest.java group order (java.* / static not last)
- core/test/java/metastore/GroupCommitTest.java group order (java.* / static not last)
- core/test/java/metastore/IsolationAndConcurrencyTest.java group order (java.* / static not last)
- core/test/java/metastore/KeysTest.java group order (java.* / static not last)
- core/test/java/metastore/MetaStoreModelTest.java group order (java.* / static not last)
- core/test/java/metastore/MetaStoreRecoveryTest.java group order (java.* / static not last)
- core/test/java/metastore/OverflowAndCorruptionTest.java group order (java.* / static not last)
- core/test/java/service/ResidentCacheTest.java group order (java.* / static not last)
- server/main/java/Application.java alphabetical within group
- server/main/java/observability/ObservabilityAttributes.java group order (java.* / static not last)
- server/main/java/observability/RequestObservabilityFilter.java group order (java.* / static not last)
- server/main/java/observability/StorageGauges.java alphabetical within group
- server/main/java/s3/S3AclController.java group order (java.* / static not last)
- server/main/java/s3/S3BucketController.java group order (java.* / static not last)
- server/main/java/s3/S3Checksums.java group order (java.* / static not last)
- server/main/java/s3/S3ErrorController.java group order (java.* / static not last)
- server/main/java/s3/S3ExceptionHandler.java alphabetical within group
- server/main/java/s3/S3MultipartController.java group order (java.* / static not last)
- server/main/java/s3/S3ObjectController.java group order (java.* / static not last)
- server/main/java/s3/S3Support.java group order (java.* / static not last)
- server/main/java/s3/access/AccessControlService.java group order (java.* / static not last)
- server/main/java/s3/auth/SigV4Filter.java group order (java.* / static not last)
- server/main/java/shell/BlobCommands.java alphabetical within group
- server/main/java/shell/ShellTable.java group order (java.* / static not last)
- server/test/java/SectoriaDBIntegrationTest.java group order (java.* / static not last)

## Appendix 5. Inline FQN usage (top, 296 occurrences in 68 files; full list: imports_out.txt)

    FILES WITH INLINE FQN 68 occurrences 296
       29 sectoriadb-server/src/test/java/org/example/sectoriadb/S3SigV4PayloadTest.java
       19 sectoriadb-server/src/main/java/org/example/sectoriadb/s3/auth/SigV4Filter.java
       17 sectoriadb-core/src/test/java/org/example/sectoriadb/repository/metastore/StorageRig.java
       16 sectoriadb-core/src/test/java/org/example/sectoriadb/SmallObjectStorageTest.java
       15 sectoriadb-core/src/main/java/org/example/sectoriadb/service/FileStorageService.java
       10 sectoriadb-core/src/test/java/org/example/sectoriadb/repository/metastore/GarbageCollectionTest.java
       9 sectoriadb-core/src/main/java/org/example/sectoriadb/service/BlobService.java
       9 sectoriadb-core/src/test/java/org/example/sectoriadb/metastore/GroupCommitTest.java
       9 sectoriadb-server/src/main/java/org/example/sectoriadb/s3/S3Support.java
       8 sectoriadb-core/src/test/java/org/example/sectoriadb/repository/metastore/CuckooDurabilityTest.java
       7 sectoriadb-core/src/test/java/org/example/sectoriadb/repository/metastore/MetaStoreRepositoriesTest.java
       6 sectoriadb-core/src/test/java/org/example/sectoriadb/repository/metastore/ChunkIndexTest.java
       6 sectoriadb-core/src/test/java/org/example/sectoriadb/repository/metastore/SmallCompactionTest.java
       6 sectoriadb-server/src/test/java/org/example/sectoriadb/GarbageCollectionS3Test.java
       6 sectoriadb-server/src/test/java/org/example/sectoriadb/S3ChecksumsTest.java
       6 sectoriadb-server/src/test/java/org/example/sectoriadb/S3ExceptionHandlerTest.java
       6 sectoriadb-server/src/test/java/org/example/sectoriadb/S3MetricsTest.java
       5 sectoriadb-core/src/main/java/org/example/sectoriadb/service/HashTableCache.java
       5 sectoriadb-core/src/test/java/org/example/sectoriadb/ObjectChecksumStorageTest.java
       5 sectoriadb-core/src/test/java/org/example/sectoriadb/SmallObjectGroupFsyncTest.java
       5 sectoriadb-server/src/main/java/org/example/sectoriadb/s3/S3ExceptionHandler.java
       5 sectoriadb-server/src/main/java/org/example/sectoriadb/s3/S3MultipartController.java
       4 sectoriadb-core/src/main/java/org/example/sectoriadb/repository/metastore/Trees.java
       4 sectoriadb-core/src/main/java/org/example/sectoriadb/service/ChunkStore.java
       4 sectoriadb-server/src/main/java/org/example/sectoriadb/observability/MicrometerStorageMetrics.java
