# SectoriaDB review: core service / repository / config / metrics / model

Scope: `sectoriadb-core/src/main/java/org/example/sectoriadb/{service,service/gc,repository,repository/metastore,config,metrics,model}`.
Read-only review of branch `11-review-refactor`. Paths below are relative to `sectoriadb-core/src/main/java/org/example/sectoriadb/`.
Not covered in depth: `service/impl/CuckooHashTable`, `SmallObjectBlob`, `metastore/*` (B+tree), server module.

Size facts: FileStorageService 658 lines, ChunkStore 532, GarbageCollector 452, BlobService 421, SmallBlobCompactor 383,
MetaStoreManifestRepository 360, MetaStoreGcRepository 321, Trees 320, Chunks 310, ResizeService 301, HashTableCache 255.

---------------------------------------------------------------------------------------------------------------------
## A. Correctness / concurrency / performance-under-concurrency

### A1 (HIGH, data loss, only via shell misuse) Reaper deletes other pools' blobs when two pools share a `basePath`
- `service/BlobFileReaper.java:103-134` (`deleteUnregistered`), `:143` (`registeredNames(pool)`).
- For each pool it lists `blob_*.raw` in `pool.getBasePath()` and treats a file as garbage when the name is not among
  THIS pool's registered names. `reconcileAtStartup()` (`startup=true`) ignores age, so at every start any `blob_*.raw`
  in that directory that belongs to another pool is deleted.
- `PoolService.create(name, basePath)` (`service/PoolService.java:43`) does not reject a `basePath` already used by another
  pool (shell `pool-create`; S3 buckets get a unique `dataDir/<bucket>` and are not affected).
  `SmallBlobCompactor.deleteUnregisteredFiles` (`gc/SmallBlobCompactor.java:350-380`) has the same per-pool logic for `small_*.sob`.
- Fix: build the registered set once across ALL pools (`blobRepo.findAll()`), and reject duplicate/nested base paths in
  `PoolService.create`. Do one reaper for both file kinds (see B4).

### A2 (MED) GC frees the slot inside the metastore transaction; a failed commit leaves an index entry with a freed slot
- `repository/metastore/MetaStoreGcRepository.java:105-160` (`collectChunks`), `freer.free` at :137, `tx.commit()` at :158;
  `service/gc/GarbageCollector.java:204` (`freeSlot`) and `:171` (`collectChunks` is NOT run under an `UploadGate.Sweep`).
- `freeSlot` writes DELETED into the table metadata before the transaction commits. If `commit()` throws (disk full, fsync failure,
  store goes read-only), the rows (`chunks`, `chunks_by_blob`, `chunk_gc`) stay with refcount 0 but the slot is gone.
  An upload that already deduplicated against that entry (verified bytes before, not committed yet) then commits `addRefs`
  successfully (entry exists, refcount 0 -> n) and the object points at a freed slot. Later dedup heals it (`verifyExisting` -> `heal`)
  but a plain GET before that fails with ChunkNotFound/corrupt. Normal (successful) commit is safe because `addRefs` throws COLLECTED.
- Fix (small): run each `collectChunks` batch under `chunkStore.gate().tryBegin(pool, ...)` like `freeStrays` does; the drain
  guarantees no in-flight upload relies on a chunk of the batch, so a failed commit is repaired by the existing ABSENT path on
  the next pass. Alternative: collect `FREED` keys and, if `commit()` throws, mark them in an in-memory "suspect" set that `addRefs` callers consult.

### A3 (MED, latency of the whole metastore) Slot free, table load and gate lock run while the exclusive writer slot is held
- `GarbageCollector.freeSlot` (`:204-230`) is the callback executed inside `beginWrite()` (`MetaStoreGcRepository:117`).
  Inside it: `blobs.acquireTable(blobId)` may do `blobRepo.findById` and a full table load from disk (~9 MiB, `HashTableCache.load`),
  then `cache.writerGate(blobId).lock()` can wait on a pending resize `freeze`. All commits of the whole server (group committer shares the slot) wait.
- Same for `replaceBlob` -> `Chunks.moveBlob` (`repository/metastore/Chunks.java:231`): rewrites 2 B+tree rows per chunk of the blob
  (up to ~500k) in one exclusive transaction at every resize, and `Trees.purgeManifests` (`Trees.java:246`) scans and DECODES the whole
  `deleted_manifests` queue inside the exclusive transaction of every blob delete / bucket delete / small-blob compaction swap (O(queue size)).
- Fix: before `beginWrite()` pre-acquire (pin) the tables of the batch (`ResidentHandle`s) and pass them to the freer; use `gate.tryLock()`
  and return DEFERRED. For `purgeManifests` store `deletedTxId` on the tombstone manifest (or add a `deleted_by_manifest` index) so rows are
  deleted by key, no scan. For resize see E2 (stable logical blob id removes `moveBlob` altogether).

### A4 (MED, throughput) `ChunkStore.place` does metastore reads and fill-stat lookups for every new chunk
- `service/ChunkStore.java:358-410`: per chunk -> `blobs.cuckooBlobsOf(pool)` (`BlobService.java:229`) -> `blobRepo.findByPoolId`
  (`MetaStoreBlobFileRepository.findByPoolId`: one read txn, decodes every blob record AND re-reads the pool record for each one via
  `Trees.blob(tx, id, true)`), then `blobs.placementWeight(b)` per blob (-> `cache.fillStats` -> may load a table that was never loaded).
  A 1 GiB upload of new data = 65k chunks = 65k read transactions. `ensureInitialBlobs` + `growIfOverThreshold` add two more per upload.
- Fix: keep an in-memory per-pool snapshot of the cuckoo blob entities in `BlobService` (copy-on-write list, invalidated by
  create/replace/delete which all go through this process), refresh weights once per `WINDOW`, not per chunk. Drop `resolvePool=true`
  from `findByPoolId` (callers only need ids / paths).

### A5 (MED, throughput) Small-object write path: global lock + metastore scan + sort for every PUT
- `service/BlobService.java:137-158` (`chooseSmallBlobForWrite`): `synchronized(smallChooseLock)` is ONE lock for all pools; under it:
  `blobRepo.findByPoolId` (read txn), sort by `createdAt`, `smallCache.acquire` (may open file + recover log tail), and on rollover
  `createSmall` -> `blobRepo.save` (waits for a group commit) while every other small PUT of every pool queues.
  `smallAppendTarget` (`:162`) duplicates the selection logic.
- Fix: per-pool `AtomicReference<BlobFileEntity> appendTarget` (+ per-pool lock only for rollover), selection logic shared by both methods.

### A6 (MED, audit log cost) STORE op-log entry contains the full chunk-key list, written synchronously on every PUT
- `service/FileStorageService.java:192-210` (`logStored`): `details.put("chunkKeys", ... "%016x" ...)` for CHUNKED manifests =
  ~18 bytes/chunk, ~1.2 MB JSON line per GiB per PUT, appended under a global write lock (`repository/JsonOperationLogRepository.java:44`),
  no fsync, rolled at 100 MB with no retention. `OperationLogEntity` javadoc says it exists "to reconstruct the manifest if the manifest file is lost":
  obsolete since stage 07 (CoW B+tree with CRC + atomic meta-page switch; manifests are not files any more).
  `findAll()` re-reads and re-parses the whole file for every query (`StatusCommands` -> `oplog`).
- See B3 for relevance; minimum fix: drop `chunkKeys` from details, make the write asynchronous or only for admin ops (BLOB_*, POOL_*, RESIZE, GC), not per object PUT/DELETE.

### A7 (MED) `compactEligible` / `eligible` open every small blob on every pass
- `gc/SmallBlobCompactor.java:110-127`: `compactEligible` iterates ALL pools and ALL small blobs and `eligible()` does `smallCache.acquire(blob)`
  (file open + tail recovery) just to read `stats()`. With thousands of buckets and `max-open-small-blobs=256` the scheduler (every `compactInterval`)
  thrashes the LRU and file descriptors. `strayHeavy` (`:138`) additionally issues `manifestRepo.countLiveByBlobId` (full manifest decode per record).
- Fix: keep dead/live byte counters per small blob in memory on the open instance and in the blob record (updated by `markDeleted` + GC),
  consult them without opening; compact only blobs with counters over threshold.

### A8 (LOW-MED, security flavour) `JsonCredentialRepository`: cache mutated before the write, shared mutable list, data race
- `repository/JsonCredentialRepository.java:46-62, 104-125`: `save`/`delete` call `readAll()` (returns the cached list itself), mutate it (`removeIf`/`add`),
  then `writeAll`. If `writeAll` throws, `invalidateCache()` is never reached; the next `readAll()` sees unchanged mtime/size and returns the mutated cache:
  a credential whose deletion failed to persist is gone in memory (or a not-persisted one is usable) until restart.
  `findAll()` hands out the live cache list (caller iteration vs. a later `save` = ConcurrentModificationException).
  `readAll()` assigns `cache`/`cachedModifiedTime`/`cachedFileSize` while only the READ lock is held (non-volatile fields, torn state).
  Staleness key (mtime ms + size) can miss an edit by the separate admin CLI process that keeps the same size within the same ms.
- Fix: immutable `volatile List<CredentialEntity>` snapshot, copy-on-write in `save/delete`, publish only after a successful atomic move; reload on mtime change under the write lock.

### A9 (LOW) Partial output / missing validation in the shell and range paths
- `FileStorageService.restoreRange` (`:273`) leaves a partial file on failure (full `restore` deletes it), non-object kinds likewise (`:285-292`).
  `streamRange` (`:558`) does not validate `startByte/lengthBytes` for CHUNKED (SMALL does); `lengthBytes==0` or a range past EOF reads/throws arbitrarily.
  `out.write(ByteBuffer)` on a FileChannel (restore/restoreRange) is one call, not a write-fully loop (engine has `FileChannelStorageIOEngine.writeFully`); a short write silently truncates the restore.
  `streamToOutput`/`streamRange` rely on `chunk.array()` (heap buffer) returned by `CuckooHashTable.readChunkByKey`: an implicit contract that will break if the table ever returns direct/read-only buffers.
- Fix: validate once in `ObjectReader.range(...)`, delete partial output, use a `writeFully` helper, expose `byte[]`/`ByteBuffer` explicitly in the table API.

### A10 (LOW) `gc run --grace X` (manual override) releases ALL pending old files immediately
- `GarbageCollector.java:148-150`: `releaseAt = o.graceMillis() != null ? Long.MAX_VALUE : now`. Any explicit grace (even 10 min) deletes every
  replaced blob / compacted small file right now, under readers that still rely on the grace period. Documented for `--grace 0`, surprising otherwise.
  Fix: `releaseAt = now + (defaultGrace - overrideGrace)` or release all only when override == 0.

### A11 (LOW) Unbounded in-memory registries
- `HashTableCache.gates`, `frozen` (a replaced blob stays frozen forever by design), `redirects` (`:123-126`, one entry per resize, never removed),
  `BlobService.poolLocks`, `UploadGate.pools` (one `PoolGate` per pool ever seen). `resolveRedirect` follows at most 16 hops (`HashTableCache.java:171`):
  a chain longer than 16 returns an id that is itself replaced -> BlobGone. Practically harmless (index entries are repointed) but make the cleanup explicit (remove when the blob record is deleted / on pool deletion).

### A12 (LOW) Replaced-blob race in `HashTableCache.acquire`
- `HashTableCache.java:178-190`: `isReplaced` check then `cache.acquire(...)` is not atomic with `redirect()+evict()`. A late acquire can re-load the old file
  (about to be deleted) into the cache (never evicted explicitly again), or get `NoSuchFileException` (an IOException that callers catching only
  `BlobGoneException|ClosedChannelException` do not map to "stale, retry"). Fix: re-check `isReplaced` after load inside the loader, or map
  `NoSuchFileException` to BlobGone in `ChunkStore.tryRead/verifyExisting/place/heal`.

### A13 (LOW) `sweep` counts a resize race as an error
- `GarbageCollector.java:326-333`: `cache.acquire(b)` throwing `BlobGoneException` (blob replaced between listing and page) is reported as `errors++ "cannot open blob"` and `metrics.gcError()`. Skip it as benign.

### A14 (LOW) Upload temp file location
- `FileStorageService.stageStream:359` `Files.createTempFile("sectoriadb-upload-", ".tmp")` in `java.io.tmpdir`: on many distros `/tmp` is tmpfs (RAM) or small;
  N concurrent multi-GB uploads can exhaust it, and a crash leaks files forever. The data is then read a second time by `ChunkStore.stage`.
  Fix: `sectoriadb.tmp-dir` (default `${data-dir}/.tmp`), clean on start in `StartupReconciler`.

### A15 (LOW) Inputs not defended in core
- `PoolService.createBucket:84`: `Path.of(dataDir).resolve(bucketName)` relies on the server's `validateBucketName` (`S3Lookup`). Core should validate (`..`, separators) itself. `create(name, basePath)`: no duplicate/nested path check (A1).
- `PoolService.deleteBucket:151`: pool dir removal swallows `IOException`; fine, but it deletes a user-supplied `basePath` if empty.

### A16 (INFO) Things I traced and found correct (no action)
- Hold lifecycle: `stageSmall`/`ChunkStore.stage` release the hold on every failure path before returning the entity; `committing()` releases in `finally`
  and nulls the field; `abortStaged` is idempotent; `UploadGate.Hold.release` is idempotent and revoked holds are removed from the set once.
- Lock order is acyclic: metastore writer slot -> blob gate (read) -> table lock; writers: blob gate (read) -> table lock (no metastore access inside);
  `UploadGate.enter` is taken before any other lock; `freeze` waits only for inserts that never wait on the metastore. No deadlock found.
- Resize protocol (freeze -> migrate -> single-txn commit -> redirect/install/evict -> deferred delete) leaves no chunk behind; exception paths close the new table, delete the new file, unfreeze.
- `commitObject` refcount ordering (addRefs before retire) keeps shared chunks off `chunk_gc`.

---------------------------------------------------------------------------------------------------------------------
## B. Dead code, leftovers, duplication

B1 Dead production code (no references in `src/main` of core or server; tests only or nothing):
- `service/ChunkingService.java`, `service/FileRestoreService.java`, `service/FileWriteService.java`,
  `service/impl/BlobFileRestoreService.java`, `service/impl/BlobFileWriteService.java`, `model/FileManifest.java`:
  the pre-stage-03 file store; nothing instantiates the two impls. `service/impl/DefaultChunkingService.java` only has `ChunkingServiceTest`
  (and an unused import in `FileStorageService`). `service/impl/FileChannelWrites.java` is a one-line forwarder used only by `SmallObjectBlob`.
- `model/AllocationPointer`, `BlobFileMetaData`, `StoragePool`, `StorageDevice`, `StoredFileInfo`, `PoolStats`: zero references anywhere
  (Russian javadocs on `StoragePool`, `StorageDevice`, `BlobFile`). `model/CuckooTable` and `model/SlotStateMap` and `model/Bucket` are engine types parked in `model`.
- Methods without a main caller: `FileStorageService.listByBlobFileId`, `updateObject`, `findObject` (tests only), `BlobService.chooseBlobFileForWrite` (tests only; comment
  says "view used by tools and tests", the placement uses HRW), `ManifestRepository.existsById/hasObjects` (tests), `UploadGate.poolsInFlight`,
  `HashTableCache.setEngineFactory` + `ResizeService.setStageHook` (test hooks in production classes), `ChunkStore.cachedLocations`,
  `SmallBlobCache.openCount` (tests). `GarbageCollector.compactor()` accessor unused.
- `BlobFileRepository.findAll/count/existsById`, `PoolRepository.existsById/count` mostly tests/shell: keep the small ones, but audit `existsById` x3.
- Unused local: `MetaStoreManifestRepository.listObjects:185` `BTree manifests` (the loop re-fetches `tx.tree(MANIFESTS)` at :275).
- Unused constants/state: `OperationLogEntity` status `STARTED` is never written.

B2 Duplicated logic
- Shell paths vs stream paths in `FileStorageService`: `restore` (:212-270) and `restoreRange` (:273-330) re-implement the chunk loop,
  CRC check and trim arithmetic of `streamToOutput` (:506) / `streamRange` (:558). Implement them as `streamToOutput(entity, Files.newOutputStream(out))`
  plus a "delete partial output" wrapper. `store(Path)` (:86) duplicates `stageStream` (hash pass, then stage); implement as `stageStream(Files.newInputStream(path), pool, null, null, none())` + commit.
- `BlobFileReaper` and `SmallBlobCompactor` both own: a "pending old files" queue (`Pending` / `PendingFile`), `releaseDue/releaseOldFiles`, `deleteUnregistered*`,
  and the same `MIN_FILE_AGE_MILLIS = 60_000` constant (:42 / :71). Merge into one `BlobFileReaper` parameterized by glob/kind; this also fixes A1 and the path-vs-name inconsistency
  (reaper compares by file NAME to survive a moved data dir, compactor by absolute path and would treat everything as garbage after a move).
- `ChunkRepository.orphans()` (`MetaStoreChunkRepository.java:90`) and `GcRepository.orphans()` (`MetaStoreGcRepository.java:166`) are the same code; `ChunkRepository.gcQueue` vs `GcRepository.dueChunks` overlap; `ManifestRepository.gcQueue` vs `GcRepository.dueTombstones`.
- `BlobService.chooseSmallBlobForWrite` vs `smallAppendTarget` (same listing/sort/hasRoom).
- `new ChunkEntry(e.blobId(), e.dataLength(), e.crc32c(), x)` x4 in `Chunks`: add `ChunkEntry.withRefcount/withBlob`. `Keys.builder().string(blobId).longUnsigned(k).build()` x10: `Chunks.blobChunkKey(...)`.
  `ByteBuffer.allocate(8).putLong(System.currentTimeMillis())` in `Chunks.millis` and `Trees.enqueueDeleted`.
- `PoolService.delete(name)` (shell) vs `deleteBucket` (S3): two delete paths with different checks (blob count vs. object count); one should call the other.
- `MIN_*`/retry magic numbers: see C5.

B3 OperationLog relevance
- The only reader is the shell `oplog` command (`StatusCommands`); writers: STORE/DELETE/RESTORE*/RESIZE/BLOB_*/POOL_*/BUCKET_DELETE.
  Its recovery purpose is obsolete (A6). It lives in a different file (`oplogs.jsonl`) than the metastore, so it is neither transactional nor ordered with the data.
  Decide: (a) keep as an admin-event log only (BLOB_*, POOL_*, RESIZE, BUCKET_*, GC) and drop per-object STORE/DELETE, or (b) delete it and rely on structured logging + metrics.
  Either way remove the 5 query methods that differ only by filter (one `find(Query)`), and the full-file read per query.

B4 Duplicate "create file then register" gaps
- `BlobService.create` (`:62-89`): `BlobLayout.createFile` (multi-GiB sparse file) then `blobRepo.save`; if `save` throws the file leaks until the reaper's next sweep (fine, but also the in-memory table is not created, so OK). `createSmall(pool, sealed=true)` opens/seals the blob in the cache BEFORE `save`; if `save` fails the sealed instance stays in `smallCache` (never evictable because sealed) until restart. Evict it in a `catch`.

---------------------------------------------------------------------------------------------------------------------
## C. Clean code

C1 God classes / proposed split
- `service/FileStorageService.java` (658 lines, 8 collaborators, 2 constructors) mixes five jobs:
  1. shell file ops: `store` :86, `restore` :212, `restoreRange` :273 (-> move to the server `shell` package or a `LocalFileTransfer` helper over ObjectReader/Writer);
  2. write pipeline: `stage*` :96-190, `storeStream/stageStream` :338-395, `commitObject/abortStaged/committing/withRedirects/redirectStagedChunks` :398-470 (-> `ObjectWriter`);
  3. read pipeline: `streamToOutput/streamRange/checkWholeObjectCrc/readerOf/readSmall` :506-620 (-> `ObjectReader`);
  4. catalog: `findObject/updateObject/listAll/listByBlobFileId/getActiveManifest/deleteObject/delete/afterDelete` :470-658 (-> `ObjectCatalog`, thin over `ManifestRepository`);
  5. audit logging `logStored/afterDelete` (-> an event listener, see A6).
  Rename the facade `ObjectStore` (it is the S3 object layer, not "files").
- `service/BlobService.java` (421): provisioning (`create/createSmall/delete`), selection (`chooseSmall*`, `cuckooBlobsOf`), pool growth (`ensureInitialBlobs/growIf*`, `Growth`, `PoolFill`),
  weights, table pinning (`acquireTable`). Split: `BlobRegistry` (cached per-pool blob list, A4), `PoolGrowthPolicy`, `BlobProvisioner`.
- `service/ChunkStore.java` (532): write path (`stage/storeChunk/verifyExisting/heal/place`), `LocationCache`, read `Reader`, barrier. Extract `LocationCache` (top-level class), `ChunkPlacer` (place+growth), keep ChunkStore as the facade.
- `HashTableCache` (255) is a cache AND the resize coordination state (write gates, `frozen`, `redirects`). Extract `BlobWriteCoordinator` (gate/freeze/redirect), leave the cache a cache.
- `repository/metastore/Trees.java` + `Chunks.java`: static bags with unrelated aggregates; acceptable as package-private helpers but split by aggregate (`PoolTrees`, `BlobTrees`, `ManifestTrees`) when touched.

C2 Long methods / deep nesting (candidates)
- `ResizeService.resize` :136-246 (~110 lines, 3 flags `frozen/committed/newTable`): extract `ResizeJob` class with `prepare/freeze/migrate/commit/switchOver/rollback`.
- `ChunkStore.place` :358-410 (nesting 6: for/for/try/try/lock/try) and `storeChunk` :222-262: extract `rankCandidates`, `tryInsert`.
- `MetaStoreManifestRepository.listObjects` :150-250 (~100 lines, cursor re-assignment); `GarbageCollector.sweep` :303-370; `compactOpened` :198-260; `FileStorageService.restore/restoreRange`.

C3 ManifestEntity is a mutable bag (238 lines, ~35 fields, setters everywhere, JSON annotations + transient upload state)
- Mixed concerns: identity (id, createdAt, deleted), layout (storageKind, chunkSize, totalChunks, lastChunkSize, chunkKeys, smallBlobId/Offset/Length/Crc32c, resolved `smallBlob`),
  S3 attributes (key, bucket, contentType, etag, acl, userMetadata, 4 content-* headers), checksums (crc32c, algorithm, value, type),
  and non-persistent upload state (`stagedChunks`, `uploadHold`). `totalChunks` is redundant with `chunkKeys.length`; `getPhysicalBlob()` is an alias of `getSmallBlob()`;
  `bucketName` == pool name duplicated; `sourceFileName` doubles as the shell name.
- Proposal: immutable records + `with*` copies:
  `ObjectManifest(id, createdAt, state, ObjectLayout layout, ObjectAttributes attrs, ObjectChecksums sums)` with
  `sealed interface ObjectLayout { Empty; Small(blobId, offset, length, crc); Chunked(poolId, chunkSize, lastChunkSize, long[] keys) }`;
  `ObjectAttributes(bucket, key, contentType, etag, acl, userMetadata, headers)`; `ObjectChecksums(crc32c, algorithm, value, type)`.
  Staging data goes to a separate `StagedUpload(manifest, placed, hold)` returned by `ObjectWriter.stage` and passed to `commit` (removes the transient fields,
  the `@JsonIgnore` noise and the "redirectStagedChunks mutates the entity" pattern). Codec stays `ManifestCodec` (JSON for attrs + binary key array).
  `updateCurrent(mutator)` becomes `updateAttributes(Function<ObjectAttributes,ObjectAttributes>)` which also fixes the "mutator runs on the committer thread" surprise.
- Same, smaller: `BlobFileEntity` (kind-dependent fields: numBuckets/chunkSize are 0 for SMALL) -> `sealed BlobRecord { CuckooBlob; SmallBlob }`; `PoolEntity` is fine as a record-like with `withAcl/withPolicy`.
  Model classes carry Jackson annotations: move serialization to the codecs (`@JsonIgnore` on a `transient` field in ManifestEntity is redundant).

C4 Stale narrative comments / language
- References to project history instead of behaviour: "the race of stage 01" (`ResidentCache.java:23`), "Stage 10 calls forget..." (`ChunkStore.java:110`), "contract for stage 10" (`GarbageCollector.java:50`, `GcRepository.java:27`, `PlacedChunk.java:10`),
  "(doc 09)" / "(doc 10, part B)" (30 occurrences), "Old registry files / older manifests / legacy rows" in model comments (`ManifestEntity` "Absent in old manifests", `BlobFileEntity` "old registry files", `MetaStoreGcRepository.dueTombstones` "legacy rows").
  Rewrite as present-tense invariants; keep a link to the doc, drop the stage numbers.
- Stale class docs: `OperationLogService`/`OperationLogEntity` (recovery claim), `StorageProperties.AutoResize` ("old in-place expansion is now manual", class now only grows pools: rename `PoolGrowth`), `FileStorageService.store` javadoc "Shell store".
- Russian/English mix: javadoc in `model/BlobFile.java`, `StoragePool.java`, `StorageDevice.java` is Russian, all code/logs/exceptions English. Pick one (English) for code comments; docs/architecture can stay Russian.
- Mixed `—` (em dash) in exception messages (`BlobService.delete`, `PoolService.delete`); user-facing text should be ASCII.

C5 Magic numbers to name/configure
- `8` attempts for small blob append (`FileStorageService.stageSmall:~133`) and `8` placement rounds (`ChunkStore.place:360`); `50` x `20 ms` BLOB_GONE retry (`withRedirects`); `60 s` heal deadline (`ChunkStore.heal:314`);
  `16` redirect hops; `100_000` status scan limit (`GarbageCollector.status:127` loads up to 100k `ChunkRef`s and tombstones per `gc status`/gauge call: use counts, not lists);
  `getBatchSize()*8` (`collectTombstones:387`); `COPY_THREADS=8`; `60_000` x4 (retry-in-a-minute in reapers/compactor, `MIN_FILE_AGE_MILLIS` x2); `20L` quarantine ratio (`BlobService.placementWeight`: ">5 %");
  `0.70` is named (`MAX_SAFE_FILL`) but duplicated in two messages; `Thread.sleep(20)` polling in `heal`.
- Config style is inconsistent: `AutoResize.checkIntervalMs` (long ms) vs `Gc.*` (`Duration`); group-commit/recovery/lazy/reconcile options are read with raw `@Value` in `MetaStoreConfig`/`StartupReconciler`
  and are invisible in `StorageProperties` (no metadata, no validation). Add `StorageProperties.Metastore { lazy, reconcileOnStart, group { ... }, recoveryBackoff }`.
- `StorageProperties.Gc.enabled=false` by default while the roadmap marks H4 as fixed: confirm the intended default (a default deployment never frees space).

C6 Logging
- INFO on routine/hot events: `HashTableCache.evict` ("Evicted table from cache") on every resize/delete, "Loading CuckooHashTable" per load, `SmallBlobCache` "Opening small-object blob" (uses an inline `org.slf4j.LoggerFactory.getLogger` call per open),
  "Storing file ..." (`stage`) and "Stored: ..." per PUT. Use DEBUG for per-object/per-load, keep INFO for lifecycle (resize, GC pass summaries, blob create/delete).
- `log.error(..., e.toString(), e)` plus metrics in GC is good; `OperationLogService` swallows to `log.error` without the exception (loses stack trace).
- `GarbageCollector` and compactor log via `{}` with `e.toString()`: fine, but `ResizeService` swallows `IOException` on cleanup with empty catch (`:225-228, 240`): log at DEBUG.

C7 Naming
- `service/impl` holds the storage ENGINE (CuckooHashTable 922 lines, SmallObjectBlob 704, IO engine, exceptions); `service` holds application services AND caches AND the gate. "impl" is a smell: see E1.
- `BlobFileEntity` (both cuckoo and small), `PoolEntity` (= bucket), `FileStorageService` (= S3 object store), `HashTableCache` (also coordination), `ChunkStore.index` field (a `ChunkRepository`), `ResidentCache.Entry.refs`, `Staged` vs `PlacedChunk`.

---------------------------------------------------------------------------------------------------------------------
## D. Imports

Unused imports (verified by script over all non-metastore core files):
- `service/FileStorageService.java:17` FileManifest, `:19` BlobFileWriteService, `:20` CuckooHashTable, `:21` ChunkNotFoundException, `:22` DefaultChunkingService, `:24` XxHash64BytesHasher.
- `service/BlobService.java:18` java.nio.ByteBuffer, `:19` FileChannel, `:22` StandardOpenOption.
- `service/ResizeService.java:10` FileChannelStorageIOEngine.
Wildcard: `service/impl/SmallObjectBlob.java:22` `import static ...SmallBlobLayout.*;`.
Inconsistent ordering (project convention elsewhere: org.example sorted, then third-party, then java.*): `BlobService`, `ChunkStore`, `FileStorageService`, `GarbageCollector`, `SmallBlobCompactor`, `SmallObjectBlob` have unsorted `org.example` groups
(e.g. `metrics.StorageMetrics` before `checksum.*`, `service.impl.SmallObjectBlob` between `model.*`); `HashTableCache` puts `jakarta.annotation.PreDestroy` after `org.example`.
Fully qualified names used inline instead of imports: `FileStorageService` 18x (`java.util.function.Supplier`, `java.util.ArrayList`, `org.example...ChunkRepository.ChunkPlacementException` x6, `java.util.Optional`, `java.nio.file.StandardCopyOption`, `java.io.UncheckedIOException`...),
`BlobService` 10x, `HashTableCache` 6x, `ChunkStore` 5x, `Trees` 5x (`java.nio.ByteBuffer`), `PoolService` 3x, `BlobFileRestoreService/WriteService`, `SmallBlobCompactor` 3x (`java.util.concurrent.ExecutionException`).
Suggested: enable `maven-checkstyle` (UnusedImports, AvoidStarImport, CustomImportOrder, IllegalImport) or `spotless:apply` with an import order, and run it once in this branch.

---------------------------------------------------------------------------------------------------------------------
## E. Architecture / modularity

E1 Package structure (today: `service`, `service/impl`, `service/gc`, `repository`, `repository/metastore`, `model`, `format`, `placement`, `checksum`, `metastore`, `tools`, `config`, `metrics`)
Problems: `model` mixes persisted entities, engine value types (`CuckooTable`, `SlotStateMap`, `Bucket`, `BlobFile`, `ChunkLocation`) and dead leftovers; `service/impl` is the engine; `UploadHold` (model) is implemented by `UploadGate` (service) -
the entity layer depends on a service concept; exceptions thrown by the engine live in `service.impl`; repository interfaces nest domain exceptions (`ChunkRepository.ChunkPlacementException`, `ManifestRepository.PoolNotFoundException`, `BlobFileRepository.BlobInUseException`).
Proposed layout (dependencies point downward; each box testable without Spring):
```
org.example.sectoriadb
  checksum/            (unchanged, pure)
  metastore/           (B+tree, unchanged, pure; MetaStoreOptions)
  engine/              CuckooHashTable, SmallObjectBlob, IO engine, format/BlobLayout+SmallBlobLayout, placement/RendezvousPlacement,
                       CuckooTable/SlotStateMap/Bucket, engine exceptions (Chunk*Exception, Table*Exception, ...). No Spring, no repository.
  catalog/             ObjectManifest + layout/attrs/checksum records, PoolRecord, BlobRecord, ChunkEntry, PlacedChunk;
                       repository interfaces (ManifestRepository, PoolRepository, BlobRepository, ChunkIndex) + catalog exceptions;
    catalog/metastore  MetaStore*Repository, codecs, Trees/Chunks (package-private; the ONLY place that imports org.example.sectoriadb.metastore)
  storage/             ChunkStore (+LocationCache, ChunkPlacer), BlobRegistry/BlobProvisioner, PoolService, ObjectWriter/ObjectReader/ObjectCatalog (facade ObjectStore),
                       cache/ (ResidentCache, TableCache, SmallBlobCache, BlobWriteCoordinator), UploadGate
  maintenance/         gc/ (GarbageCollector, SmallBlobCompactor, BlobFileReaper, StartupReconciler), resize/ (ResizeService)
  security/            CredentialService, CredentialRepository (+Json impl)
  audit/               OperationLogService (+ repository), ObjectVerificationService may go to maintenance/
  config/              StorageProperties, CoreConfiguration (@Configuration wiring), MetaStoreConfig
  metrics/             StorageMetrics SPI
```
Move: `tools/*` (hashers) next to `engine`; delete `service/impl/{BlobFile*Service,DefaultChunkingService,FileChannelWrites}`, the three interfaces, dead model types.

E2 Simplify resize by making the blob id stable (large simplification, recommended)
- Today resize creates a NEW blob id and then needs: `HashTableCache.redirect/isReplaced/resolveRedirect/frozen forever`, `BlobGoneException` handling in 7 call sites,
  `FileStorageService.withRedirects` retry loop (50 x 20 ms) + `redirectStagedChunks` mutating staged chunks, `Chunks.moveBlob` rewriting every `chunks`/`chunks_by_blob`/`chunk_orphans` row in one exclusive transaction (A3),
  `ChunkRepository.ChunkPlacementException.Reason.BLOB_GONE`, the grace-delayed old file in `BlobFileReaper`.
- Alternative: the logical blob id (what the index stores) stays; only the blob record's `filePath/numBuckets/totalBytes` change in an O(1) transaction. The old table is evicted and the new one installed under the same id;
  staged uploads and cached `LocationCache` hints stay valid; readers with an old handle keep the old (unlinked) file. The reaper still deletes the old file after the grace period (by path, queued in memory). Removes ~300 lines and the BLOB_GONE retry class of bugs.

E3 Spring coupling in core (24 annotated classes; `spring-boot-starter` is a core dependency)
- Annotated classes that are plain Java and should be wired in ONE `@Configuration` (`CoreConfiguration`, or in the server): `ChunkStore`, `BlobService`, `FileStorageService`, `HashTableCache`, `SmallBlobCache`, `ResizeService`, `PoolService`,
  `CredentialService`, `OperationLogService`, `ObjectVerificationService`, `BlobFileReaper`, `SmallBlobCompactor`, `GarbageCollector`, all `MetaStore*Repository`, `Json*Repository`.
  Benefits: constructors lose `@Autowired` and the test-only overloads (`FileStorageService` x2, `ChunkStore` x4 with `new StorageProperties()` defaults, `HashTableCache`/`SmallBlobCache` `NOOP` overloads),
  `@PreDestroy` hooks become explicit `close()` (`AutoCloseable` beans), the engine can be embedded/tested without a context (the tests already build it by hand: `StorageRig`).
- Keep Spring only for: `StorageProperties` (`@ConfigurationProperties`), `MetaStoreConfig`, `StartupReconciler` (`SmartInitializingSingleton`), and the wiring class. `MetricsConfig`: use `@ConditionalOnMissingBean` instead of relying on server `@Primary`.
- `MetaStoreConfig:73` detects "another process" by `e.getCause().getMessage().contains("already open")`: introduce a typed `MetaStoreLockedException` in `metastore`.

E4 Interface-per-implementation noise
- Dead: `ChunkingService`, `FileWriteService`, `FileRestoreService` (+impls). One impl each, no alternative: `StorageIOEngine` (keep: real test seam, but then drop `HashTableCache.setEngineFactory` in favour of constructor injection), `ChunkRepository`, `GcRepository`, `ManifestRepository`, `PoolRepository`, `BlobFileRepository` (all backed by MetaStore only;
  keep as the catalog boundary but name implementations by role, not `MetaStoreXxx`, and have `GcRepository` be part of the metastore package since it is transaction-shaped, not a repository: its `SlotFreer` callback runs ENGINE code inside a metastore transaction (A2/A3)).
  `CredentialRepository`/`OperationLogRepository` are fine (Json impls), but see B3.

E5 Where repository abstractions leak MetaStore/transaction concerns
- `MetaStoreProvider` is public in `repository.metastore` and used by the server (`StorageGauges` imports `MetaStore` + `MetaStoreProvider`); gauges should read from a `CatalogStats`/`StorageStats` API (`metaFileBytes`, `pages`, ...).
- `ManifestRepository.updateCurrent(Consumer<ManifestEntity>)`, `PoolRepository.updateByName(Consumer<PoolEntity>)`: callbacks execute on the committer thread inside the group-commit batch (long mutators stall everybody; a throwing mutator rolls back only its savepoint).
  Replace with pure `Function<T,T>` on immutable records and document "must be fast, side-effect free".
- `GcRepository` exposes `SlotFreer`/`SlotFree` (engine semantics) and returns IO errors as fields (`ChunkBatchResult.error`), mixing checked IO with transactional results: separate "decide" (repository: returns the keys to free, pins) from "free" (service) and "finalize" (repository) where possible, see A2.
- Repository methods that return fully resolved entities (`findByPoolId(..., resolvePool=true)`, `Trees.resolve`) do extra reads per row; callers that only need ids pay for it (A4).
- `ManifestRepository.save` (plain upsert "for shell and maintenance") bypasses refcounts: only used by tests/shell paths; remove or make package-private to prevent corrupting `chunks` refcounts.
- Performance footguns exposed at the API: `findAllLive`, `listAll` (loads every manifest with all chunk keys; `ObjectVerificationService.verifyAll` and shell `ls` -> OOM on large stores): replace with a paged/streaming `forEachLive(Consumer)`.
