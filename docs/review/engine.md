# Engine review (sectoriadb-core: service/impl, format, tools, checksum, placement, model, engine interfaces)

Paths are relative to sectoriadb-core/src/main/java/org/example/sectoriadb/. Read-only review; nothing was modified.
The core insert/evict/barrier protocol (CuckooHashTable) and the SmallObjectBlob group-fsync/recovery protocol were traced
and are sound w.r.t. docs 01/04/09/10. The findings below are what remains.

## A. Correctness / concurrency / durability

A1 [high] Interrupted thread permanently kills a table's / blob's file channel.
  service/impl/FileChannelStorageIOEngine.java:86-114,126-141 and service/impl/SmallObjectBlob.java:72 (final channel), 247, 318.
  FileChannel is an InterruptibleChannel: if any thread is interrupted while inside read/write/force, the JDK closes the shared channel
  (class comment line 26 even documents it). The engine then keeps returning the closed channel (`channel != null`, `closed == false`), so
  every later operation on that CuckooHashTable throws ClosedChannelException until the table is evicted from HashTableCache - and a table
  with unforced writes is deliberately NOT evicted (HashTableCache:56), so it can stay dead until restart. SmallObjectBlob has a final channel
  and no reopen at all. Nothing in the repo handles ClosedByInterruptException/ClosedChannelException (grep over both modules: none outside the engine).
  Fix: in the engine catch ClosedChannelException/ClosedByInterruptException when !closed, clear the interrupt flag, reopen the channel and retry once
  (re-assert the flag afterwards); or run all IO in a small dedicated non-request thread pool that is never interrupted. Do the same for SmallObjectBlob
  (make channel non-final / hold it in a reopenable holder). Add a test that interrupts a thread mid-IO.

A2 [medium] BlobLayout.createFile truncates an existing file. format/BlobLayout.java:163-165 uses CREATE + TRUNCATE_EXISTING; callers
  (service/BlobService.java:70 and service/ResizeService.java:180) derive the name from only the first 8 hex chars of a random UUID
  (BlobService:62-63 `blob_<8hex>.raw`). A 32-bit name collision (birthday bound ~65k blobs per pool dir) would silently zero a live blob.
  SmallObjectBlob.create (SmallObjectBlob.java:116) correctly uses CREATE_NEW. Fix: CREATE_NEW in createFile (fail loudly), and/or use the full UUID in file names.

A3 [medium] Eviction BFS is unbounded in work and allocation under the table WRITE lock.
  service/impl/CuckooHashTable.java:714-756. maxEvictions bounds depth (32) but not node count; with branching 4 the whole graph (up to 2*numBuckets
  nodes, each a fresh int[5] in an ArrayList) is explored before TableFullException. A failing insert on a big, nearly full table therefore holds the write
  lock (blocking all readers/dedup lookups of that blob) for O(numBuckets). Also `new BitSet(2*numBuckets)` is allocated on EVERY insert (line 717), even when a root bucket
  has room (256 KB per insert at 1M buckets). Fix: (1) check the two root buckets for a free slot before building any BFS state; (2) cap visited nodes (e.g. 2k-8k)
  and report TableFull; (3) use an IntArrayList / reuse a per-table scratch buffer (safe, we hold the write lock).

A4 [medium] loadMetadataFromDisk does not check the file length. service/impl/CuckooHashTable.java:395-456. The header geometry is verified, but a truncated file
  (e.g. interrupted copy/restore) only fails when a data slot beyond EOF is read, i.e. at runtime on an arbitrary GET/PUT. Fix: compare channel size with
  BlobLayout.requiredSize(numBuckets, chunkSize) at load and fail with InvalidBlobHeaderException. (ioEngine has no size() method; add one.)

A5 [medium] New blob files are not made durable as directory entries. format/BlobLayout.java:171, format/SmallBlobLayout (via SmallObjectBlob.create:119): fc.force(true) syncs the file
  but not the parent directory, so after power loss a blob that the metastore already names (BlobService:70-79 registers right after createFile) may be missing.
  Fix: fsync the directory (open parent with READ, force) in both create paths; same for the rename/swap in ResizeService/compactor (other reviewer's area, check).

A6 [low] SmallObjectBlob non-fsync mode: append() can throw AFTER the record was written and counted. service/impl/SmallObjectBlob.java:262-267 -> maybeCheckpointNoForce():372
  may throw IOException after liveRecords/writeEnd/tail were already advanced; the caller sees a failure but the record exists (orphan until compaction). Only for fsync=false (tests/dev),
  but easy: catch/log the checkpoint failure like lead() does (line 340).

A7 [low] SmallObjectBlob.lead(): `forcing` stays true while the optional checkpoint is written and fsynced (lines 335-349), although followers were already signalled. Every appender arriving in that
  window waits for the checkpoint's extra fsync before it can even lead its own group, and close() waits too. Fix: set forcing=false and signalAll before the checkpoint, serialise checkpoints with a separate
  flag.

A8 [low] markDeleted updates counters before the fsync (SmallObjectBlob.java:433-442): if forceTimed() throws, the DELETED flip is in the page cache and counted, but the caller thinks it failed
  (idempotent retry returns false the next time and skips the fsync that never happened). Fix: on IOException from the force, remember "needs force" (or force again on the "already deleted" path).

A9 [low] CuckooHashTable.writeMeta mutates the in-memory state only after the IO call (lines 845-852). If write succeeds but force() throws (sync=true path: freeSlot, eviction moves), disk may have the new state while
  memory keeps the old one; freeSlot's javadoc ("in-memory state unchanged") is true but the on-disk state is unknown. Harmless for eviction (repairAfterCrash tolerates it) but a later insert may overwrite
  slot data of a slot that disk says is ACTIVE. Document or mark the table "suspect" (reload from disk) after a failed force.

A10 [low] Source bit-flip in the state byte of a small record is undetectable by design: STATE byte excluded from headerCrc (format/SmallBlobLayout.java:349-354, documented). An ACTIVE->DELETED flip
  makes a live object read as "marked DELETED". Acceptable trade-off; mention in doc 04 / have scrub cross-check against the metastore.

A11 [low] Unvalidated sizes: SmallBlobLayout.parseRecordHeader accepts any dataLength >= 0 (line 370) and scan()/resync() then allocate `new byte[dataLength]` (SmallObjectBlob:574,641); bounded only by the
  headerAt `pos+span > end` check, so a CRC-valid header can still request up to file size. Add `len <= MAX_RECORD_DATA`. FileChannelStorageIOEngine.readChunk allocates `length` without validating (negative -> NegativeArraySizeException).

A12 [low] Eviction-move reads the chunk with ioEngine.readChunk and copies it WITHOUT verifying its CRC (CuckooHashTable.java:298-299). A corrupted chunk is moved intact (CRC still detects it later), so
  safe, but it is an opportunity to count/report the corruption (and avoid moving damaged data into a fresh slot).

## B. Dead code / leftovers / duplication / needless public API

B1 [high value] Legacy single-file store is dead. Used only by each other: service/impl/BlobFileWriteService.java (79 l), service/impl/BlobFileRestoreService.java (57 l), service/FileWriteService.java,
   service/FileRestoreService.java, service/ChunkingService.java, service/impl/DefaultChunkingService.java (36 l; only referenced by ChunkingServiceTest), model/FileManifest.java.
   Grep over both modules incl. tests: no instantiation outside tests. FileStorageService imports BlobFileWriteService, DefaultChunkingService, FileManifest but never uses them (unused imports, see D).
   The `chunkingService` field is assigned and never read in BOTH services. BlobFileWriteService also lacks the barrier() call and the PlacedChunk bookkeeping of the real path, so it is not a safe
   fallback. Delete all 7 classes + ChunkingServiceTest (and doc 01/03 mentions, docs/architecture/01-engine-safety.md:68,93, 03:95).
B2 Model classes with zero usage anywhere (main or test, both modules): model/AllocationPointer.java, model/BlobFileMetaData.java, model/StoredFileInfo.java, model/PoolStats.java (60 l),
   model/StoragePool.java, model/StorageDevice.java (only referenced by StoragePool). Delete. These are the Russian-javadoc leftovers of the original design (AllocationPointer, StorageDevice, StoragePool, BlobFile).
B3 Test-only API in production classes (no main caller, only tests): CuckooHashTable.lookup, readChunk(ChunkLocation,int), getBucket, forEachActiveChunk, lockForWrite/WriteGuard, 5- and 6-arg constructors
   (production only uses the 7-arg one: HashTableCache:239, ResizeService:198), InsertResult.location, getNumBuckets, and therefore model/Bucket.java (75 l, zero main users; collides by name with the S3 "bucket" concept),
   model/ChunkLocation, model/CuckooTable, SlotStateMap.fromValue. docs/01 itself says lookup+readChunk(loc) are "only for backward compatibility" (a stale-location API that is unsafe under concurrent insert, see its own javadoc line 333).
   Fix: remove lookup/readChunk(loc)/getBucket/lockForWrite/forEachActiveChunk, make InsertResult {key, deduplicated}, drop Bucket/ChunkLocation/CuckooTable (test via contains()/readChunkByKey()); keep constructors to one (+ maybe a test factory).
B4 FileChannelWrites.java (15 l) is a one-method package-private wrapper over FileChannelStorageIOEngine.writeFully; used only by SmallObjectBlob. SmallObjectBlob also has its own readFully0 (lines 673-685) duplicating
   FileChannelStorageIOEngine.readFully; BlobFileWriteService reuses the static from the engine class. Fix: one `io/ChannelIO` util (readFully/writeFully/force) used by both.
B5 Duplicated hashing/CRC helpers: mix64 in BlobLayout (private, line 79) and RendezvousPlacement (public, 172); `new CRC32C()` helper re-implemented in CuckooHashTable.crc32c:910, BlobLayout:115/134, SmallBlobLayout.crc:266 and :323,
   checksum/ChecksumAlgorithm; ChunkStore and FileStorageService (4x) also hand-roll it. Fix: one `Crc32c` utility in format/hash.
B6 Duplicated group-fsync protocol: CuckooHashTable.barrier (lines 651-687, forcing/forceDone/forcedSeq) and SmallObjectBlob.awaitDurable/lead (282-351) are two hand-written leader/follower implementations of the same idea. Extract `GroupForce`
   (leader election + generation/failure handling) and reuse; this would also have caught A7.
B7 Slot count constant defined three times: BlobLayout.SLOTS_PER_BUCKET, CuckooHashTable.SLOTS_PER_BUCKET (re-exported, used by ResizeService:149 and server StorageGauges:182), Bucket.SLOTS_COUNT. Keep BlobLayout's.
B8 BlobFile.size (model/BlobFile.java) is never read anywhere; every IO method of StorageIOEngine takes a BlobFile although "an engine instance is bound to one blob file" (interface javadoc) and the impl then
   checks path equality at runtime (FileChannelStorageIOEngine:136-139). Bind the engine to the Path at construction and drop the parameter; BlobFile could shrink to (id, path) or disappear from the engine API.
B9 CuckooHashTable.computeRequiredBlobSize (line 459) is a trivial pass-through of BlobLayout.requiredSize; `memoryBytes` hard-codes 17 bytes/slot (line 696) that must be kept in sync with the arrays (line 90 comment): derive from a constant.
B10 forEachActiveChunkInBatches builds `List<long[]>` of one-element arrays (CuckooHashTable.java:497-512): use a LongArrayList/two parallel arrays or a small record.
B11 FillStats secondary constructor (line 126) and KeyPage/ScrubReport/FillStats nested records inside a 922-line class: used from other packages as CuckooHashTable.FillStats etc. -> top-level types.

## C. Clean code

C1 Class sizes: CuckooHashTable.java 922 lines (insertInternal 228-315 = 88 l; loadMetadataFromDisk 395-456 = 62 l; barrier 651-687; findEvictionPath 714-756);
   SmallObjectBlob.java 704 lines (append 220-275 = 56 l; lead 305-351 = 47 l; scan 564-610 = 47 l; recovery+scan+resync+IO helpers ~ 250 l).
   Split proposal CuckooHashTable -> (a) `SlotMetadata` (the four parallel arrays + counters + flatIndex/dataOffset/locationOf), (b) `EvictionPlanner` (BFS, pure, unit-testable, fixes A3),
   (c) `SlotMetaCodec` (32-byte entry encode/decode/CRC, currently split between writeMeta:834 and loadMetadataFromDisk:401-440 with magic offsets 8/12/16/20/28),
   (d) `DurabilityBarrier`/`GroupForce` (B6), (e) result records. insertInternal -> findOrDedup(), planPath(), applyMoves(), writeNew().
   SmallObjectBlob -> `SmallBlobRecovery` (scan/resync/headerAt/checkpoint choice), `GroupForce` (above), keep append/read/markDeleted.
C2 Magic numbers: CuckooHashTable.java: meta offsets 8/12/16/20/28 (lines 420-439, 835-845) vs the layout doc in BlobLayout - define MetaEntry offsets in BlobLayout; default maxEvictions 32 (line 150) duplicated in StorageProperties; `100` problems cap
   duplicated in CuckooHashTable:608 and SmallObjectBlob:553; `1024` in activeKeys (565); 16L<<20 checkpoint default (SmallObjectBlob:144); 20L quarantine ratio in BlobService:~; `0.95/0.70` in RendezvousPlacement are named (good).
   SmallBlobLayout.encodeRecordHeader uses literal at+5/6/7/8/12/16/24/28 and encodeCheckpoint 28.
C3 Comments narrating history / stage references (should be timeless): CuckooHashTable.java:47 "(doc 10, part B)", :517 "(garbage collection, doc 10)", :54 "See docs/...10"; BlobLayout.java:131 "pre-header legacy blob which is no longer supported";
   RendezvousPlacement.java:109 "see doc 03"; model/PlacedChunk.java:10 "contract for stage 10"; model/UploadHold.java:7 "doc 10"; SmallObjectBlob.java:37-38 "hook for a later compaction stage" (compaction exists now - stale);
   BlobFileWriteService/Restore have no javadoc; StorageKind "Default for manifests written before small objects existed" / BlobKind "Default for registry entries without a kind" (migration narration; fine if still read, else drop).
   CuckooHashTable class javadoc (lines 27-61, 35 lines) mixes format, integrity, insert, durability, concurrency - move the protocol text to the doc and keep a short contract.
C4 Mixed languages: Russian only in javadoc of model/AllocationPointer, BlobFile, StorageDevice, StoragePool (all but BlobFile are dead). All exception/log messages in the engine packages are English. Docs 01-10 are Russian (fine). Em dashes
   (U+2014) in messages: CuckooHashTable.java:284 exception text "no eviction path ... — table is too full" and many javadocs: keep ASCII in exception messages.
C5 Inconsistent error handling in the engine: typed exceptions exist (TableFull, ChunkCorrupted, KeyCollision, SmallObjectCorrupted, InvalidBlobHeader) but plain `new IOException(...)` is thrown for
   "Slot not active" (CuckooHashTable:341), "Could not find a collision-free key" (271), "Unexpected end of file" (FileChannelStorageIOEngine:71), "Chunk not found" in BlobFileRestoreService:43 (while ChunkNotFoundException exists), and IllegalArgumentException for bad length (232).
   Introduce `abstract class StorageException extends IOException` (+ BlobCorruptionException base for ChunkCorrupted/SmallObjectCorrupted/InvalidBlobHeader) so callers (S3 layer) can map "corruption" vs "full" vs "IO" with a type check.
   ObjectCorruptedException (manifest-level, whole-object CRC) does not belong in service.impl next to the engine; ChecksumMismatchException lives in checksum but carries S3 wording.
C6 Naming: `SlotStateMap` is an enum, not a map -> `SlotState`; `CuckooTable` {A,B} -> `TableSide`; `Bucket` (cuckoo) collides with S3 buckets; `ChunkConsumer`/`WriteGuard` fine; `BytesHasher` in a package called `tools` (junk drawer) -> `hash`;
   `service.impl` is not a service package (it holds the engine); `StorageIOEngine` is positional file IO, not a "storage engine" -> `BlobIO`/`SlotIO`; `FillStats` is a record with `fillPercent()` returning percent while RendezvousPlacement uses fraction 0..1 (two scales).
   Public getters in CuckooHashTable are `getX` while records/other classes use `x()`.
C7 System.out / printStackTrace: none in either module (grep). Logger style fine (SLF4J placeholders). Section-divider comments with box-drawing characters (SmallObjectBlob, CuckooHashTable, BlobLayout) are a style choice; unify.
C8 SmallObjectBlob exposes a test hook `setBeforeForceHook`/`BeforeForceHook` and `open` has three overloads (lines 124-145); `UploadChecksums` is a mutable fluent builder with overlapping `algorithm(alg, expected)`/`expected()`/static factories: pick one construction style.
C9 SmallObjectBlob fully qualifies java.nio.channels.ClosedChannelException twice (lines 291, 659), BlobFileWriteService/RestoreService fully qualify FileChannel/StandardOpenOption in code - import them.

## D. Imports

Unused imports (main): service/ResizeService.java (FileChannelStorageIOEngine); service/BlobService.java (ByteBuffer, FileChannel, StandardOpenOption);
  service/FileStorageService.java (FileManifest, BlobFileWriteService, CuckooHashTable, ChunkNotFoundException, DefaultChunkingService, XxHash64BytesHasher).
  Server: shell/PoolCommands.java (CuckooHashTable), shell/GcCommands.java (GcReports), shell/StatusCommands.java (BlobKind, CuckooHashTable), s3/S3BucketController.java + s3/S3MultipartController.java (DocumentBuilderFactory), s3/S3Support.java (UUID).
Wildcard imports: main core: service/impl/SmallObjectBlob.java (`import static ...SmallBlobLayout.*`). Server main: s3/S3BucketController, S3MultipartController (also java.io.*, java.nio.file.*, java.util.*), S3AclController, S3ObjectController (`web.bind.annotation.*`), s3/auth/SigV4Filter, SigV4Utils (`java.util.*`).
  Tests (core): ChunkingServiceTest, ChecksumAlgorithmTest, SmallObjectStorageTest (repository.*, service.*, repository.metastore.*), CuckooInsertSafetyTest, EngineMetricsTest, ObjectChecksumStorageTest (3 more wildcards), SmallObjectBlobTest, CuckooFreeSlotTest,
  SmallObjectGroupFsyncTest, CuckooHashTableTest, FileChannelStorageIOEngineTest, ChunkIntegrityTest and every file under repository/metastore + placement test (all `Assertions.*`, plus java.util.concurrent.* in ChunkIndexTest, MultiBlobPoolTest, SmallObjectBlobTest, SmallObjectGroupFsyncTest). Server tests: all `Assertions.*`.
Unused imports (tests): SmallObjectStorageTest (SmallObjectCorruptedException), CuckooInsertSafetyTest/CuckooHashTableTest (FileChannel, StandardOpenOption), CuckooDurabilityTest (HashMap), ResizeSafetyTest (Set), ChunkIndexTest (CuckooHashTable),
  GarbageCollectionTest (UploadGate, GcReports), MultiBlobPoolTest (StorageProperties); server: S3ApiRegressionTest (Value), S3ChecksumsTest (ArrayList, List), MetaStoreRecoveryS3Test (Arrays).
No duplicate imports found. Ordering: groups not alphabetical / org.* not before java.*: service/BlobService, ChunkStore, HashTableCache, FileStorageService (e.g. StorageMetrics before checksum.*, model.SmallObjectBlob in the middle of model.*), service/impl/CuckooHashTable
  (`java.util.zip.CRC32C` before `java.util.concurrent`), service/impl/SmallObjectBlob (3 groups, nested-class imports after the outer class), service/gc/GarbageCollector, service/gc/SmallBlobCompactor; BlobFileWriteService/RestoreService have a stray blank line between com/org groups.
Fix: add a Spotless/Checkstyle (UnusedImports, AvoidStarImport, CustomImportOrder: static last, java, javax, third-party, org.example) or `maven-checkstyle-plugin` to enforce; one mechanical pass.

## E. Modularity / architecture

E1 Dependency direction. The engine is almost framework-free already: format, tools, checksum, placement, metastore (B+tree) have no Spring/Jackson/other-package imports (metastore imports only `metrics`);
   service/impl engine classes depend only on format, model(BlobFile, ChunkLocation, CuckooTable, Bucket, SlotStateMap), tools, metrics.StorageMetrics (an interface, pure Java) and `service.StorageIOEngine`.
   Violations: (1) service/impl -> service (StorageIOEngine/ChunkingService/FileWriteService live in the Spring-bean package `service`, which in turn depends on repository/config); (2) model mixes engine primitives with persisted/Jackson entities
   (BlobFileEntity, ManifestEntity [-> checksum], PoolEntity, ChunkEntry, PlacedChunk) and an upload-lifecycle interface (UploadHold); (3) metrics package contains one Spring class (MetricsConfig) next to the pure interface StorageMetrics.
E2 Proposed package structure (within the current module first):
   engine/blob/     CuckooHashTable (+ EvictionPlanner, SlotMetadata, SlotMetaCodec), SmallObjectBlob (+ SmallBlobRecovery), InsertResult, FillStats, BlobFile
   engine/io/       BlobIO (ex StorageIOEngine), FileChannelBlobIO, ChannelIO (readFully/writeFully), GroupForce, Crc32c
   engine/format/   BlobLayout, SmallBlobLayout, InvalidBlobHeaderException (rename of `format`)
   engine/error/    StorageException, ChunkCorruptedException, SmallObjectCorruptedException, TableFullException, KeyCollisionException, InvalidBlobHeaderException
   engine/placement/ RendezvousPlacement; engine/hash/ BytesHasher, XxHash64
   engine/metrics/  StorageMetrics (+NOOP)
   Remaining `service` = Spring orchestration (ChunkStore, BlobService, FileStorageService, ...), `model` = persisted entities only, `checksum` = S3 integrity (see E4).
E3 Maven module candidates (reactor now: core, server). Suggested:
   * sectoriadb-engine (NEW, deps: slf4j-api only, junit for tests): engine/* above (~3.3k lines: CuckooHashTable 922, SmallObjectBlob 704, layouts 375, placement 131, xxhash 110, IO 160, exceptions). It is already free of Spring/Jackson.
     Moving it needs: StorageIOEngine out of `service`, BlobFile/ChunkLocation/CuckooTable/SlotStateMap out of `model` (or delete per B3), StorageMetrics moved. Hard boundary enforced by the compiler; engine tests (CuckooHashTableTest, CuckooInsertSafetyTest,
     CuckooFreeSlotTest, ChunkIntegrityTest, SmallObjectBlobTest, SmallObjectGroupFsyncTest, FileChannelStorageIOEngineTest, XxHash64Test, EngineMetricsTest, CrashSimulation, RendezvousPlacementTest) move with it and run in seconds without Spring.
   * sectoriadb-metastore (NEW, optional, slf4j only): metastore/* (B+tree, WriteTxn, GroupCommitter, Pager, ~2.4k lines) - no Spring/Jackson, only needs metrics hooks (use the engine's metrics interface or its own).
   * sectoriadb-core keeps: config, model (entities), repository (Jackson codecs), service (Spring), checksum? and depends on engine + metastore.
   * server unchanged (S3, shell, auth, observability), depends on core.
   Also: spring-boot-starter in core pom (pom.xml:17-20) is only needed by config/service; engine/metastore modules would not need it.
E4 checksum/: pure Java but S3-specific (x-amz-checksum-* header names in ChecksumAlgorithm.java:20-24, ChecksumMismatchException S3 wording, MultiDigestInputStream, UploadChecksums). Only core's FileStorageService/ObjectVerificationService/ManifestEntity and server's multipart controller use it.
   Keep it out of the engine module (it is not engine); either leave in core or move to `server` if core's services stop needing the S3 names. ChecksumType (FULL_OBJECT/COMPOSITE) is persisted in ManifestEntity, so core must see it.
E5 Interfaces that add nothing: FileWriteService, FileRestoreService, ChunkingService (single, dead implementations, B1); BytesHasher has one impl (XxHash64BytesHasher) but is justified by the salted re-key hook and tests - keep but move to engine.hash;
   StorageIOEngine has 2 impls (FileChannelStorageIOEngine + test fakes in CrashSimulation, CuckooInsertSafetyTest): justified as the crash-injection seam, keep, but note SmallObjectBlob bypasses it with its own raw FileChannel
   (so crash-injection and metrics wiring exist twice) - a missing abstraction: a common `BlobIO` used by both blob kinds would unify IO, interrupt-safety (A1), metrics and group-force.
E6 Missing abstractions: (1) a `Blob` supertype (id, path, kind, close, scrub, stats) - HashTableCache/SmallBlobCache/BlobService branch on BlobKind everywhere; (2) typed `ChunkKey` (long keys + 8 hex formatting duplicated in message strings 0x+Long.toHexString);
   (3) `StorageMetrics` is a 213-line mega-interface with ~50 default methods mixing engine, GC and S3 concerns (GcDeferral etc.): split into EngineMetrics (cuckoo/small/io) and GcMetrics so the engine module owns only the first.

## Top findings (ranked)
1 A1 channel closed by thread interrupt, no recovery (high)
2 B1 dead legacy store: 7 classes + test (BlobFileWriteService/Restore, File*Service, ChunkingService, DefaultChunkingService, FileManifest)
3 B2 six unused model classes (AllocationPointer, BlobFileMetaData, StoredFileInfo, PoolStats, StoragePool, StorageDevice)
4 B3 test-only/stale-location API in CuckooHashTable (lookup, readChunk(loc), getBucket, lockForWrite, Bucket/ChunkLocation/CuckooTable)
5 A2 BlobLayout.createFile TRUNCATE_EXISTING + 8-hex file names
6 A3 unbounded BFS + per-insert BitSet allocation under the write lock
7 A5 no directory fsync after creating blob files
8 A4 no file-length validation at table load
9 B6 duplicated leader/follower group-fsync (CuckooHashTable.barrier vs SmallObjectBlob.lead) and A7 checkpoint inside `forcing`
10 C1 oversized classes (CuckooHashTable 922, SmallObjectBlob 704) with an 88-line insert method
11 C5 inconsistent exception hierarchy (plain IOException/IAE next to typed ones; no common base)
12 D unused/wildcard imports in ~10 main files and ~35 test files; add Checkstyle/Spotless
13 E1/E3 extract pure engine (+ metastore) into Maven modules; StorageIOEngine/model/metrics are the blockers
14 B4/B5 duplicated IO helpers, mix64, CRC32C helper
15 C3 history-narrating comments (doc N / stage N / "later compaction stage") and C4 Russian javadoc in dead model classes
