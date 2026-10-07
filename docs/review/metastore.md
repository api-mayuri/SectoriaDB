# Review: sectoriadb-core `metastore/*` (branch 11-review-refactor)

Scope: MetaStore, GroupCommitter, WriteTxn, ReadTxn, BTree, Cursor, Node, Pager, Verifier, Keys, LongList, MetaStoreOptions, exceptions, and the 8 test classes in `src/test/.../metastore`. Read-only review. Paths below are relative to `sectoriadb-core/src/main/java/org/example/sectoriadb/metastore/`.
One finding (A1) was confirmed by compiling the package with plain `javac` into the scratchpad and running a 20-line driver. No mvn, no repo files touched.

Overall: the commit protocol, the free-page rules (pending groups `U < minReader`, `chainPending` for the meta fallback window), savepoint epochs and the recovery path are carefully designed. I traced them and found no data-loss path in the main protocol. The findings below are edge cases, error-path behaviour, and structure.

---------------------------------------------------------------------------------------------------

## A. Correctness / concurrency / durability

### A1 [HIGH, confirmed] Empty (or tiny) value with a key >= 1018 bytes crashes `put` (page size 4096)
`BTree.java:153-157` `makeVal`: the inline limit is `5 + key.length + v.length <= (ps-8)/4`. For `v.length == 0` and key >= 1018 (4096 B pages; >= 122 B for 512 B pages) it falls into `w.writeOverflow(v)`. `WriteTxn.java:262-278` computes `n = (0 + per-1)/per = 0`, `ids = new long[0]`, then `return ids[0]` throws `ArrayIndexOutOfBoundsException`. `BTree.put` then sets `w.broken = true`, so the whole write txn (or the whole group-commit batch body, which at least rolls back) is unusable.
Evidence: driver run with `pageSize(4096)` and `put(new byte[len], new byte[0])`: 1016 ok, 1017 ok, 1018..1338 -> `ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0`.
Why it matters: doc 06 promises keys up to 2 KiB ("S3 key 1024 + bucket"). `repository/metastore/Trees.java` uses `Trees.EMPTY` values for index trees keyed by `pairKey(...)`: `MANIFESTS_BY_BLOB`, `MANIFESTS_BY_POOL`, `BLOBS_BY_POOL`, `Chunks` by-blob. Today ids look like UUIDs, so it is latent. Any index keyed by an S3 key with an empty value will hit it.
Fix: in `makeVal`, force inline when `v.length == 0`. Better, make the overflow rule robust: `if (v.length <= INLINE_MAX_VALUE_ALWAYS ...)`. Also make `writeOverflow` throw `IllegalArgumentException` for an empty array. Add a test for `key = maxKeySize(ps)`, `value = 0..N` at 512/4096/65536.

### A2 [MEDIUM] Group-commit thread dies permanently on interrupt, store then reports "store is closed"
`GroupCommitter.java:406-408`: `InterruptedException` from `queue.take()` / `queue.poll(left)` sets `stopping = true`. The thread finishes the queue, sets `terminated = true` and exits. Later `submit` (line 384) sees `terminated`, calls `sweep()`, and fails every write with `IllegalStateException("store is closed")`, although the store is open. The comment says "nobody interrupts this thread on purpose", but bodies run user code on this thread. Any body that swallows an `InterruptedException` and re-sets the flag (very common), or a `Thread.currentThread().interrupt()`, kills grouped writes for the rest of the process lifetime. Plain `beginWrite` keeps working, which hides the failure.
Fix: ignore interrupts unless `closing` is true (clear the flag via `Thread.interrupted()` and continue), and/or restart the thread in `submit` when `thread != null && !thread.isAlive() && !closing`. Add a test: body calls `Thread.currentThread().interrupt()`, then a second `submit` must still succeed.

### A3 [MEDIUM] Metrics callback failure is treated as commit failure (poisons the store, reports a durable commit as failed)
`MetaStore.java:415-417`: `metrics.metaCommit(...)` is inside the `try` that catches `Throwable`, sets `poisoned` and rethrows. `GroupCommitter.java:462-468`: `metrics.metaGroupBatch(...)` is inside the `try` whose `catch` fails all futures with `commitFailure`. By then `committed = ns` is already published and the meta page is fsynced. A buggy or failing StorageMetrics implementation (Micrometer registry error, NPE) makes callers see an error for data that is durable, and (MetaStore path) forces a needless recovery cycle with a 1 s backoff.
Fix: take the metrics calls out of the `try` (call them after it, wrapped in their own try/catch that logs). Same for `metaGroupQueueWait`/`metaGroupBodyRollback`, which sit in the body loop and would abort the batch.

### A4 [MEDIUM] Initial file creation is not crash-atomic: a half-created file can never be opened
`MetaStore.java:116-121`: `writeRaw(0, meta)`, then `writeRaw(pageSize, zeros)`, then `force()`. A crash (or ENOSPC) after the first write leaves a file of exactly one page. `chooseMeta` (lines 182-204) accepts slot 0 but rejects it because `pageCount(2) * ps > fileSize`, throwing `CorruptedPageException(-1, "no valid meta page")` on every later open. The operator must delete the file by hand. There is also no fsync of the parent directory, so a freshly created file may vanish after power loss (benign, but the first commit's durability depends on it).
Fix: write both pages in one `write` of `2*ps` bytes and `force()`; in `open`, treat `size < 2*ps` with a valid slot 0 of `txId == 0` as "uninitialised, re-initialise". Optionally fsync the directory once at creation.

### A5 [MEDIUM] `Cursor.value()`/`key()` do not check for modification; a freed overflow chain can be read after reuse
`Cursor.java:514-522`: only `next()` checks `tx.modCount() != stamp`. In a `WriteTxn`, after `c.next()` a `put`/`delete` that replaces the current entry frees its overflow pages (`WriteTxn.free` puts owned pages into `reusable` for immediate reuse; committed ones go to `pendingFreed`, which is safe). Then `c.value()` -> `tx.readValue(curVal)` walks pages that may already have been overwritten. Result: `CorruptedPageException`, or silently wrong bytes if the reused page is another overflow page with a valid CRC.
Fix: call the same stamp check in `key()`/`value()` (cheap). Document that a cursor in a write txn is read-then-invalidate.

### A6 [MEDIUM] Meta-fallback is erased by recovery; no test or doc covers a crash right after recovery plus damage of the only meta page
`MetaStore.java:626-628` `doRecover`: the dirty slot is zeroed. The dirty slot is `(s+1)%2`, which holds meta `s-1`, i.e. the fallback. Doc 10 mentions the cost. Consequence: from recovery until the next successful commit the store has a single meta page, and the `pending` groups released under the "fallback is s-1" rule (`startWrite` line 302) are reused although the fallback no longer exists. That is harmless, but make it explicit. A smaller point: between the failed commit and the recovery (backoff, possibly minutes while the disk is broken) a process crash resurrects the transaction whose callers were told it failed (the meta page may be on disk even though the fsync reported an error). This is inherent to fsync failure, but should be written into doc 10 as a known limit.
Fix: docs only, plus a test asserting `verify()` and reopen after "recovery then corrupt the surviving meta page" yields `CorruptedPageException` (not a silent wrong state).

### A7 [LOW-MEDIUM] Internal calls that deadlock instead of failing fast
- `MetaStore.beginWrite()/verify()` from inside a grouped body (committer thread holds the single permit; `Semaphore` is not reentrant) block forever. Only `submit` has an `onCommitterThread()` guard (`GroupCommitter.java:364`).
- A thread holding an open `WriteTxn` that calls `writeGrouped` waits for a future that needs the permit it holds. Same for `close()` while the same thread holds a `WriteTxn`.
Fix: in `beginWrite/tryBeginWrite/verify`, throw `IllegalStateException` when `committer.onCommitterThread()`. Document the second case in the `submit` Javadoc (already partly there), optionally track the owner thread in `WriteTxn`.

### A8 [LOW-MEDIUM] `close()` is not safe against concurrent readers and is not atomic
`MetaStore.java:653-668`:
- Open `ReadTxn`s and `Cursor`s are not waited for; after `channel.close()` they fail with `UncheckedIOException(ClosedChannelException)` instead of a clear "store closed".
- `beginRead()` has a check-then-act race with `close()` (`checkOpen()` then use).
- A second concurrent `close()` returns at line 654 before the first one has finished.
- If `fileLock.release()` throws, `channel.close()` is skipped (no try/finally).
Fix: `closed` check inside `synchronized(stateLock)` in `beginRead`; close the channel in `finally`; optionally wait until `readers` is empty with a timeout, or let `ReadTxn` operations map `ClosedChannelException` to `IllegalStateException("store is closed")`.

### A9 [LOW-MEDIUM] No range check on page ids read from nodes
`ReadTxn.node(id)` / `Pager.read(id)` / `readValue` / `freeOverflow` / `freeSubtree` accept any id. A child or overflow pointer that is negative gives `IllegalArgumentException("Negative position")` from `FileChannel.read`, not a `CorruptedPageException` (violates the contract "CorruptedPageException with the page number"). A pointer `>= snap.pageCount()` (but inside the file) reads an unreferenced or newer page whose CRC may be valid. Only `Verifier.mark` and `loadFreelist` range-check.
Fix: in `ReadTxn.node/readValue` and `WriteTxn.freeOverflow`, require `2 <= id < snap.pageCount()` (WriteTxn: `< hwm`); otherwise throw `CorruptedPageException(id, "page id out of range")`. Also cap `ovLen` at `BTree.MAX_VALUE_SIZE` (today a corrupt negative `ovLen` gives `NegativeArraySizeException`; a huge one allocates up to 2 GiB before the chain is checked, `ReadTxn.java:213-217`).

### A10 [LOW] Page CRC does not cover the page id
`Pager.java:75,84`: CRC32C over `[4, pageSize)`. A page written to the wrong offset (misdirected write, stale copy after a lost write) is valid and undetected unless its type differs. Cheap hardening: seed the CRC with the page id (format v2), or store the id in the 2 reserved header bytes plus validate. Needs a format-version bump; list as a future item, not a bug.

### A11 [LOW] `tree(name)` on a missing tree in a WriteTxn creates the tree and forces a commit
`WriteTxn.java:42-48` `missing()` sets `dirty = true`, so a read-only use such as `tx.tree("x").get(k)` or `containsKey` inside a write txn makes `isModified()` true. The commit then writes an empty catalog entry, and `hasTree`/`treeNames` report it. Cost: a pointless fsync pair, and surprising `hasTree` results. Fix: set `dirty` lazily on the first `put`/`delete`; `treeNames()` should only include handles that are dirty or exist in the catalog.

### A12 [LOW] Savepoint cost is O(owned + dirty) per body, quadratic per batch
`WriteTxn.java:199-214`: every `savepoint()` copies `dirty`, `owned`, `reusable`, `dropped`, `handles` (up to `maxBatchPages` = 1024 entries) for each of up to 256 bodies. About 250k element copies per batch plus allocation churn, on the single committer thread. Also `mutable()` copies a node that was already dirty in an earlier body of the same batch (epoch rule), so hot pages (catalog-like roots, counters) cost one page copy per body. Acceptable now; a proper undo log (record `(map, key, previous)` entries) makes it O(changes of the body).

### A13 [LOW] Free-list commit cost and a harmless empty trailing chain page
`MetaStore.java:455-485`: `n` is computed from `total` before the chain pages are removed from `available`, so up to one trailing chain page can be written with `cnt == 0` (page wasted every commit until the next one frees it). Entries are built as `List<long[]>` with one 2-element array per free page (about 40 B each) on every commit, i.e. O(free pages) allocation and O(free pages) IO per commit (documented limit in doc 06, section 7). Fix: size `n` iteratively (`n = ceil((total - n)/per)`), write entries straight into the page buffers from the three sources without the intermediate list.

### A14 [LOW] `maxBatchPages` check counts `owned.size()`
`GroupCommitter.java:447`: `owned` shrinks when pages are freed, so the "pages written" limit is approximate. Fine, but the option doc says "newly written pages"; either rename or count allocations.

### A15 [LOW] Doc/Code mismatches worth fixing while here
- `MetaStore.java:29` Javadoc points to `docs/architecture/04-btree-metastore.md`; the file is `06-...`.
- Doc 06 section 1.1 says the meta write is "< 64 bytes in one sector", but `encodeMeta` returns a full `pageSize` array and `writeRaw` writes the whole page (4-64 KiB). Torn-write safety still holds (the CRC covers the first 48 bytes), but writing only the first 512 B would match the claim and save IO.
- Doc 06 section 4 says a group is released when `min(...) >= U`; code uses strict `U < minReader` (safe, one commit more conservative). Doc 06 does not describe `chainPending` at all.
- Doc 06 header says "no dependencies other than the JDK"; `StorageMetrics` and slf4j are used (see E).

---------------------------------------------------------------------------------------------------

## B. Dead code, duplication, API surface

Dead / unused:
1. `MetaStore.committedSnapshot()` (`:544`) has no callers (main or tests).
2. `Pager(FileChannel,int,boolean)` 3-arg ctor (`Pager.java:22-24`) is unused.
3. `Verifier.walk(..., boolean catalog)` and `walkTree(..., boolean catalog)` pass a parameter that is never read (`Verifier.java:395,408`).
4. `MetaStore.restartPending` (`:81`) is a field used only as a local temp inside `loadFreelist`; make it a local.
5. `BTree.name()` has no callers in the code base (cheap, but check before keeping it public).
6. `BTree.maxKeySize` is public static but used only internally and by tests.
7. `ReadTxn.registered` flag exists only for `Verifier`'s throwaway ReadTxn; a package-private factory would do.

Duplicated logic:
8. Page header offsets/type bytes/count are hand-coded in at least 7 places (see C3).
9. Overflow-page parsing in `ReadTxn.readValue` (`:219-231`) and `Verifier.walkChain` (`:441-457`) and `WriteTxn.freeOverflow` (`:281-290`) each re-implement "read overflow page, check type, read used/next". Extract `Overflow.read(Pager, id) -> {used, next, offset}`.
10. Catalog value codec: `BTree.encodeCatalogValue` (write) vs inline `ByteBuffer.getLong(0)/(8)` in `ReadTxn.tree` and `Verifier.run` (read). Use a `CatalogEntry(root, count)` record with `encode/decode`.
11. `Keys.Builder.string` and `stringPrefix` duplicate the escape loop.
12. `MetaStore.readMetaAt/parseMeta/chooseMeta` return `Object[]{Integer, Snapshot}` with casts (`:160-204`). Replace with a `record MetaCandidate(int pageSize, Snapshot snap)`.
13. `MetaStoreOptions` has 8 private-ctor call sites with 8 positional args each; adopt a record plus `withX` methods, or a Builder.
14. Free-page accounting loops (`available + pending + chainPending`) repeat in `doCommit` (twice), `Verifier.run`, `loadFreelist`.

Needlessly public (candidates for package-private, or for a separate test-support class):
- `MetaStore.injectFaultsForTesting` is public production API; used from `sectoriadb-server` tests (`MetaStoreRecoveryS3Test:72,84,89`) and metastore tests. Move behind a `FaultInjector`/`MetaStoreTestSupport` (test-jar of the new module) or an `Options.faultInjector(...)` hook.
- `MetaStore.commitHook` (package-private, volatile, string-typed stages `"before-commit"/"after-data"/"after-meta"` compared with `equals` in tests). Use an enum `CommitStage`.
- `MetaStore.isReadOnly()` is used only by a server test; keep but document as health probe or expose `state()`.
- `MetaStore.VerifyReport` is public but used only by tests and one S3 test; fine, but `verify()` blocks all writers for the full scan - make that explicit in the Javadoc or offer a snapshot-based verify.
- `MetaStoreOptions` getters `maxBatchSize/maxBatchPages/maxBatchWaitMicros/queueCapacity/recoveryBackoffMillis/metrics` are only used by `MetaStore`/`GroupCommitter`: package-private.
- `ReadTxn` is a public non-final class with a package-private constructor: declare `public sealed class ReadTxn permits WriteTxn` (or an interface).
- `CorruptedPageException`/`MetaStoreUnavailableException`: public is right; `MetaStoreUnavailableException extends IllegalStateException` conflates "disk broken, retry later" with "store closed / bad use". Prefer `RuntimeException` (or a dedicated `MetaStoreClosedException`), so a handler for `IllegalStateException` does not swallow it.

API ergonomics:
- Two write entry points with different semantics (`write` direct vs `writeGrouped` via committer) and a `submit` returning futures. Callers have to know when grouping is legal (never inside a body, never while holding a txn). Consider one `MetaStore.write(...)` that always groups, with `writeExclusive(...)` for the rare direct case.
- `groupCommit(int,int,long,int)` is 4 positional numbers of nearly the same type; introduce `GroupCommitOptions` record.
- `MetaStoreOptions.metrics(null)` is accepted and NPEs later; add `Objects.requireNonNull`. The constructor validates fields in a strange order (backoff assigned before checks).
- `beginWrite()` is uninterruptible and has no timeout; `tryBeginWrite` throws a checked `InterruptedException` and returns `Optional`. Leaked `WriteTxn` (no close) blocks the whole store forever, there is no leak detector. Consider an optional "writer held longer than N seconds" warning.
- `BTree` allows an empty key in named trees (`checkKey` only rejects it for the catalog, `name == null`) - decide and document.
- Everything is `byte[]`/`Optional<byte[]>`; `Cursor.key()` clones on every call. Offer `Cursor.keyView()`/`ByteBuffer` or a `forEach(consumer)` for hot scans.

---------------------------------------------------------------------------------------------------

## C. Clean code

### C1. Long classes / methods (lines)
| Unit | Lines | Proposal |
|---|---|---|
| `MetaStore.java` | 669 (7 responsibilities: lifecycle/open, meta-page codec, freelist state + persistence, txn admission, commit, recovery, stats/verify, test hooks) | Split into: `MetaPage` (encode/parse/choose, ~80 lines), `FreeSpace` (available/pending/chainPending/chainPages, `release(minReader, committed)`, `loadFrom(disk)`, `buildChain()`, `count()`, ~150), `Committer`/`CommitProtocol` (`doCommit`), `Recovery` (`recoverIfPoisoned/doRecover/readSlot`), `MetaStore` keeps lifecycle + admission (~200). |
| `MetaStore.doCommit` | 76 (433-508) | Extract `foldCatalog(w)`, `FreeSpace.buildChain(w)` -> `FreelistChain`, `writeDirtyPages(w)`, `writeMetaAndPublish(...)`. Numbered comments (1..5) become method names. |
| `MetaStore.loadFreelist` | 39 (222-260) | Move into `FreeSpace.load(pager, snapshot, restart)`; use a local TreeMap and `long[]` directly instead of `restartPending` + `LongList` copy. |
| `MetaStore.open` + `chooseMeta` | 36 + 23 | `MetaFile.openOrCreate(...)` with `MetaCandidate` records. |
| `GroupCommitter.runBatch` | 49 (426-474) with 3 nested try blocks | Split into `acquireWriter(local)`, `applyBodies(tx, local) -> taken`, `completeFutures(taken, failure)`. |
| `GroupCommitter.java` | 240 | OK; `Task`/queue part could be its own file if module grows. |
| `BTree.split` | 40 | OK; extract `chooseSplitPoint(n)` (pure function, easy to unit-test). |
| `WriteTxn.java` | 291; `Savepoint` inner class + group-commit fields mixed with core txn state | Extract `SavepointLog`/`PageAllocator` (owned, reusable, fromAvail, hwm, guard, deferred) from the txn. `allocId/free/mutable` + savepoint is the most subtle logic in the package and deserves its own class and unit tests. |

### C2. Stale/narrative comments
- `MetaStore.java:29` wrong doc number (`04-` vs `06-`).
- `MetaStore.java:68-72, 83-88, 299-309` are long explanatory blocks inside field declarations / method bodies; the reasoning ("pages freed by U are reusable when U < minReader", "chain replaced by U is referenced by meta U-1 only") belongs in one `FreeSpace` class Javadoc with the invariant stated once.
- `MetaStore.java:468` "after a restart these come back as ordinary pending groups, which is safe" - state as an invariant on the on-disk format instead.
- `MetaStore.java:61` trailing comment on the `Semaphore` field is a design note; move to Javadoc.
- `MetaStore.java:493` "from here the slot may hold a valid page..." good, keep, but name the field `metaSlotInDoubt`.
- `GroupCommitter.java:407` "nobody interrupts this thread on purpose" is wrong in practice (see A2).
- `Pager.java:12` says "crc(4) type(1) flags(1) count(2) payload"; docs call the byte "reserved"; unify wording.
- Test hooks interleaved with production fields (`Pager.java:33-52`, `MetaStore.java:53-56, 91`) with `// test hook` comments; move behind one `FaultInjector` interface.

### C3. Magic numbers: propose `PageLayout` (package-private final class)
Hand-coded in: `MetaStore` (0/8/12/16/24/32/40/48/52, `p[4]`, `getShort(6)`, `16 + n*16`, `8` next, `(ps-16)/16`), `Pager.HEADER`, `Node` (`Pager.HEADER + 8`, `2 + k + 1`), `WriteTxn.writeOverflow` (`16`, `p[4]`, `6`, `8`), `ReadTxn.readValue` (`p[4]`, `6`, `16`, `8`), `Verifier.walkChain` (`16`, `6`, `8`), `BTree` (`ps/4`, `5 + key`, `16`).
```java
final class PageLayout {
    // common page header
    static final int CRC_OFF = 0, TYPE_OFF = 4, FLAGS_OFF = 5, COUNT_OFF = 6, HEADER = 8;
    static final byte T_LEAF = 1, T_BRANCH = 2, T_OVERFLOW = 3, T_FREELIST = 4;
    // overflow / freelist
    static final int NEXT_OFF = 8, CHAIN_PAYLOAD_OFF = 16;
    static final int OVERFLOW_PAYLOAD = /* pageSize */ - CHAIN_PAYLOAD_OFF;   // per page via method
    static final int FREELIST_ENTRY = 16;                                    // pageId u64 + groupTxId u64
    // meta page
    static final int META_MAGIC = 0, META_VERSION = 8, META_PAGE_SIZE = 12, META_TX = 16,
                     META_CATALOG = 24, META_FREELIST = 32, META_PAGE_COUNT = 40, META_CRC = 48, META_SIZE = 52;
    // leaf encoding
    static final byte VAL_INLINE = 0, VAL_OVERFLOW = 1;
    static final int OVERFLOW_REF_SIZE = 16;                                 // head u64 + length u64
    static int inlineLimit(int pageSize)  { return (pageSize - HEADER) / 4; }
    static int overflowPerPage(int ps)    { return ps - CHAIN_PAYLOAD_OFF; }
    static int freelistPerPage(int ps)    { return (ps - CHAIN_PAYLOAD_OFF) / FREELIST_ENTRY; }
}
```
Put `Pager.T_*` here too, so `Pager` is pure I/O. `BTree.MAX_VALUE_SIZE` and `MAX_KEY_CAP` could live in a `Limits` section of the same class (value cap of 64 MiB is also hard-coded in docs).

### C4. Naming
- `Snapshot` (store) vs `snap` in txns vs "snapshot" in docs for readers: consistent, but `committed`, `committedSnapshot`, `Stats.lastTxId` mix terms; pick "committed snapshot".
- `hwm` vs `pageCount` (same quantity at different times); `fromAvail` -> `takenFromFreeList`; `pendingFreed` -> `freedCommitted`; `reusable` -> `freedOwned`; `owned` -> `allocated`; `guard` -> `ownedBeforeSavepoint`; `deferred` -> `freedGuarded`.
- `Res(id, sep, right)` in BTree -> `SplitResult`.
- `dirtyMetaSlot` -> `metaSlotInDoubt`.
- `stateLock` protects only `readers` + publication; name `readersLock`.

### C5. Other small items
- `ReadTxn.closed` is not volatile but `Verifier`/`close` could be called from other threads in tests; fine per contract, but state "single thread" with an assertion in debug.
- `WriteTxn.treeNames()` builds a `TreeSet` with a comparator that re-encodes UTF-8 on every comparison (`:71-72`), and `hasTree` calls it, so `hasTree` is O(catalog) + allocations. Use `catalog().get(nameKey(name))` plus `handles/dropped` checks.
- `Node.children` is `ArrayList<Long>` (boxing on every descent); `Node.size()` is O(entries) and is recomputed on every insert/delete and in `rebalance`. Cache the size incrementally for a measurable win in bulk loads (not a correctness item).
- `Pager.crc` allocates a `CRC32C` per call; use a `ThreadLocal`/pass one in.
- `LongList` is a "minimal growable stack" used as stack for `available`, fine; give it `toArray()`/`addAll` to remove the copy loops in `loadFreelist` and `doCommit`.

---------------------------------------------------------------------------------------------------

## D. Imports

- No wildcard imports, no duplicates, no unused imports in `metastore/` main or test (scripted check).
- Inconsistent ordering between main and tests: main uses `org.*` first, blank line, then `java.*` (`MetaStore.java:3-25`, `Pager`, `GroupCommitter`); tests use `static` first, then `java.*` and `org.*` directly without blank lines (`KeysTest.java:3-10`, `GroupCommitTest.java`). Pick one (suggest: static, java, javax, third-party, project - one block each) and add Spotless/Checkstyle `ImportOrder`.
- Fully-qualified names inline instead of imports: `WriteTxn.java:71-72` (`java.util.Arrays`, `java.nio.charset.StandardCharsets`), `Cursor.java:56` (`java.util.ConcurrentModificationException`; also in Javadoc, fine).
- `MetaStore.java` imports `StorageMetrics` (see E).

---------------------------------------------------------------------------------------------------

## E. Modularity: extract `sectoriadb-metastore`

Verdict: feasible as a mechanical move. The package depends on nothing in the project except `org.example.sectoriadb.metrics.StorageMetrics` and slf4j.

### Exact coupling today
Imports from other project packages (grep of `import org.example` in `metastore/`): only `org.example.sectoriadb.metrics.StorageMetrics` in `Pager.java:3`, `MetaStoreOptions.java:3`, `MetaStore.java:3`, `GroupCommitter.java:3`. External library: `org.slf4j` (`MetaStore.java:4-5`). Test classes in `src/test/.../metastore` import nothing outside the package (only JUnit); the 8 classes / ~2.8k lines can move as is. `repository/metastore/*` tests and code stay in core (they are the *users* of the store).

`StorageMetrics` members used by the metastore (10 callbacks + 2 enums):
| Call site | Method |
|---|---|
| `Pager.read:73` | `diskRead(Target.METASTORE, nanos, bytes)` |
| `Pager.read:76` | `crcFailure(CrcKind.METASTORE_PAGE)` |
| `Pager.writeRaw:97` | `diskWrite(Target.METASTORE, nanos, bytes)` |
| `Pager.force:109` | `fsync(Target.METASTORE, nanos)` |
| `MetaStore:277,285` | `metaWriterLockWait(nanos)` |
| `MetaStore:417` | `metaCommit(nanos)` |
| `MetaStore:608,613` | `metaRecovery(boolean)` |
| `GroupCommitter:450` | `metaGroupQueueWait(nanos)` |
| `GroupCommitter:458` | `metaGroupBodyRollback()` |
| `GroupCommitter:463` | `metaGroupBatch(bodies, pages)` |

Reverse coupling (core/server -> metastore): classes used outside are `MetaStore` (incl. `Stats`), `MetaStoreOptions`, `Keys`, `ReadTxn`, `WriteTxn`, `BTree`, `Cursor`, `MetaStoreUnavailableException` (server `S3ExceptionHandler`), and test-only `injectFaultsForTesting`, `isReadOnly`, `verify` (server `MetaStoreRecoveryS3Test`). `MetaStoreConfig` (core `config`) builds the store and passes `StorageMetrics`. `StorageMetrics` itself mentions the metastore in its `Target.METASTORE`/`CrcKind.METASTORE_PAGE` enums and in 5 `meta*` methods (they stay in core as the *adapter's* target).

### Decoupling
1. In the new module define:
```java
public interface MetaStoreListener {
    MetaStoreListener NOOP = new MetaStoreListener() {};
    default void onPageRead(long nanos, int bytes) {}
    default void onPageWrite(long nanos, int bytes) {}
    default void onFsync(long nanos) {}
    default void onChecksumFailure() {}
    default void onWriterLockWait(long nanos) {}
    default void onCommit(long nanos) {}
    default void onRecovery(boolean success) {}
    default void onGroupBatch(int bodies, int pages) {}
    default void onGroupQueueWait(long nanos) {}
    default void onGroupBodyRollback() {}
}
```
   `MetaStoreOptions.metrics(StorageMetrics)` becomes `listener(MetaStoreListener)`; `Pager`, `MetaStore`, `GroupCommitter` use the listener. All methods default so `NOOP` is trivial and tests need no recording stub.
2. In core, add `StorageMetricsMetaStoreListener implements MetaStoreListener` (maps to `StorageMetrics` with `Target.METASTORE`/`CrcKind.METASTORE_PAGE`) and use it in `config/MetaStoreConfig`. Remove nothing from `StorageMetrics`: the server's Micrometer adapter keeps working untouched. Wrap listener calls in try/catch (fixes A3 at the same time).
3. slf4j: keep as the single compile dependency (`slf4j-api` only, no logging impl), or replace the two log calls with `listener.onCommitFailed(Throwable)`/`onRecovered(...)`. I recommend keeping slf4j-api.
4. Test-only API: move `injectFaultsForTesting`/`commitHook` behind a small `FaultInjector` interface (`beforeWrite()`, `beforeForce()`, `atStage(CommitStage)`), set via a package-private/`test-jar` factory (`MetaStoreTestSupport.open(file, opts, injector)`). Publish the module's test-jar; `sectoriadb-server` and core tests depend on it with `<type>test-jar</type><scope>test</scope>` instead of calling a public production method.
5. Exceptions `MetaStoreUnavailableException`/`CorruptedPageException` move with the module (no dependencies). Server keeps importing the same FQCNs if the package name `org.example.sectoriadb.metastore` is kept, so the move is source compatible. Keep the package name for the first step.

### Maven layout
```
SectoriaDB (parent)
  <modules>sectoriadb-metastore, sectoriadb-core, sectoriadb-server</modules>   <!-- metastore first -->
sectoriadb-metastore/pom.xml
  deps: org.slf4j:slf4j-api (compile); junit-jupiter (test)
  no Spring, no Jackson. Java 21.
  build: maven-jar-plugin test-jar goal (for fault-injection helper + shared test utils)
sectoriadb-core/pom.xml
  + dependency org.example:sectoriadb-metastore (compile)   // transitive to server
  + test-jar dependency if core tests inject faults
parent dependencyManagement: add sectoriadb-metastore (like sectoriadb-core today)
```
Moves (git mv, history preserved):
- `sectoriadb-core/src/main/java/org/example/sectoriadb/metastore/*` -> `sectoriadb-metastore/src/main/java/...` (same package).
- `sectoriadb-core/src/test/java/org/example/sectoriadb/metastore/*` -> `sectoriadb-metastore/src/test/java/...`.
- Stays in core: `repository/metastore/*` (+ their tests), `config/MetaStoreConfig`, new `StorageMetricsMetaStoreListener`.
- Docs: doc 06 header "no dependencies other than the JDK" becomes "slf4j-api only"; fix the `04-` link; add module line to ROADMAP.
Order of work: (1) introduce `MetaStoreListener` inside core and switch the package to it (compiles, all tests green, no move yet); (2) introduce `FaultInjector`; (3) `git mv` + poms; (4) optional JPMS-style hygiene: make internals package-private (see B), keeping `module` public surface = `MetaStore, MetaStoreOptions, MetaStoreListener, ReadTxn, WriteTxn, BTree, Cursor, Keys, 2 exceptions`.
Benefit: the metastore tests (most of which are the fsync-less, randomised model tests) can run in seconds without Spring on the classpath, and the on-disk format/protocol gets an owner and a version boundary.

---------------------------------------------------------------------------------------------------

## Tests (metastore/ package) - gaps and quality

Good coverage: model-based randomised tests (`MetaStoreModelTest`, `GroupCommitModelTest`), crash simulation with hooks (`CrashSafetyTest`), fault injection and recovery (`MetaStoreRecoveryTest`), isolation/concurrency, overflow + corruption.
Gaps that match findings:
1. No test for key near `maxKeySize` with an empty / tiny value (A1). Add parametrised test over page sizes 512/1024/4096/65536.
2. No test that interrupting the committer thread (A2) or a throwing `StorageMetrics` (A3) keeps the store usable.
3. No test for a half-created file (A4): truncate a fresh store to `pageSize` bytes and reopen.
4. No test that `Cursor.value()` fails after a modification in a write txn (A5).
5. No test for beginWrite/verify called from within a grouped body (A7), nor for `close()` racing `submit`/`beginRead` (A8) (check `GroupCommitTest` first; I saw no interleaved close test).
6. No test for a corrupt child pointer (negative / beyond pageCount) giving `CorruptedPageException` (A9).
Quality:
- Timing-based tests: `GroupCommitTest.java:71` `Thread.sleep(100)` "let the committer take them all into one batch" and `GroupCommitModelTest.java:283` `Thread.sleep(1)`; replace with the existing `submitWhileBlocked` style latch, or a hook that blocks the committer. 10-120 s `future.get` timeouts mask hangs; use a single `@Timeout` per class.
- Tests use `commitHook` with string stage names and set it to `null` manually (`GroupCommitTest:338`, `MetaStoreRecoveryTest:184,213`): leaks into later steps if an assertion fails first; wrap in a helper with try/finally.
- Mostly `fsync(false)`: fine for logic, but nothing exercises the barrier ordering with real `force` calls; a `Pager`-level recording test (`write*, force, write meta, force`) would pin the protocol from doc 06 section 3.

---------------------------------------------------------------------------------------------------

## Top 15 (ordered)
1. A1 HIGH confirmed: `put(key>=1018B, emptyValue)` -> AIOOBE and broken txn (`WriteTxn.writeOverflow`/`BTree.makeVal`).
2. A2 MEDIUM: any interrupt of the committer thread silently disables grouped writes forever ("store is closed").
3. A3 MEDIUM: metrics exception after a successful commit poisons the store / fails durable futures (`MetaStore:417`, `GroupCommitter:463`).
4. A4 MEDIUM: crash during first-time file creation leaves a file that can never be opened.
5. A5 MEDIUM: `Cursor.value()` after a modification reads freed (possibly reused) overflow pages.
6. A9 LOW-MED: unchecked page ids (negative -> `IllegalArgumentException`, beyond `pageCount` -> stale pages; unbounded `ovLen` allocation).
7. A7 LOW-MED: `beginWrite()/verify()` inside a grouped body, or `writeGrouped` while holding a `WriteTxn`, deadlock instead of failing fast.
8. A8 LOW-MED: `close()` races with `beginRead`, ignores live readers, non-atomic, `fileLock.release` failure skips `channel.close`.
9. A6: recovery erases the fallback meta slot; document the window and the resurrect-after-fsync-failure limit.
10. B/C: `MetaStore.java` is 669 lines / 7 responsibilities; `doCommit` is 76 lines. Split into `MetaPage`, `FreeSpace`, `CommitProtocol`, `Recovery`.
11. C3: introduce `PageLayout` constants; header offsets are hand-coded in 7 places.
12. B: test hooks in production API (`injectFaultsForTesting` public, string-typed `commitHook`); replace with `FaultInjector` + test-jar.
13. A11: `tree(name)` on a missing tree in a write txn marks it dirty (extra commit + phantom `hasTree`).
14. A12/A13: savepoint is O(owned) per body, freelist rebuild allocates `long[2]` per free page and may write an empty trailing chain page.
15. D/B: inconsistent import order between main and tests, FQNs inline in `WriteTxn`/`Cursor`; dead code (`committedSnapshot`, 3-arg `Pager` ctor, unused `catalog` param in `Verifier.walk`, field `restartPending`).

Module extraction (E): only coupling is `StorageMetrics` (10 callbacks + `Target`/`CrcKind`) and slf4j. Add `MetaStoreListener` (all-default methods + `NOOP`), adapter `StorageMetricsMetaStoreListener` in core, `FaultInjector` for tests, `git mv` main+test packages to `sectoriadb-metastore` (deps: slf4j-api, junit), core depends on it, keep the package name for source compatibility, publish test-jar for the fault-injection helper used by server tests.
