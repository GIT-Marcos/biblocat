# ADR 0001: Content Hash + Hybrid WatchService/Scan for Rename and Safe-Save Detection

**DEPRECATED** — Este ADR describe un enfoque híbrido con WatchService + escaneo periódico. La implementación actual del
Agent no usa WatchService; la sincronización se realiza únicamente mediante escaneos (inicio, periódico, manual). El
mecanismo de content hash para rename detection sigue vigente, pero sin el componente WatchService. Ver `docs/newDoc.md`
§4 para el diseño actual.

## Status

Accepted → DEPRECATED

## Context

Windows does not expose rename events natively via `ReadDirectoryChangesW`. Java's `WatchService`
(on any platform) maps renames to `ENTRY_DELETE` + `ENTRY_CREATE`, losing the correlation between
the two events. Additionally, many applications (Chrome, VS Code, Adobe Acrobat, etc.) use a
"safe-save" pattern: write content to a temporary file, delete the original, and rename the
temporary to the original name. This also generates `DELETE` + `CREATE` on the **same path**.

In all these cases, the original database record may be lost and a new record created without its
metadata (tags, URL, year, edition). The system must preserve metadata across these operations.

An in-memory buffer that stores recently deleted records was initially considered, but analysis
revealed several issues:

- **Race condition**: WatchService events can arrive out of order (CREATE before DELETE),
  causing the buffer lookup to miss.
- **Buffer loss**: In-memory state is lost on API restart, causing metadata loss even within
  a short TTL window.
- **Concurrency**: Buffer requires synchronization (`ConcurrentHashMap`) and scheduled eviction.
- **No persistent correlation**: The buffer cannot survive restarts and cannot cross-reference
  with database state for files that existed before implementation.

A different approach is needed: one that uses the database itself as the persistent,
concurrency-safe look-up structure, eliminating the buffer entirely.

## Decision

We will implement a **hybrid approach** combining:

1. **Instant CREATE detection via WatchService** — files added to the library are reflected in
   the catalog within seconds.
2. **Periodic full reconciliation scan** (every 5 minutes + on Agent startup) — detects deletions,
   corrects hash mismatches, and serves as a fallback for rename detection.
3. **Database-based rename detection** — the API searches the `sources` table by `content_hash`
   to correlate renames. No in-memory buffer.
4. **ENTRY_DELETE events are ignored** — the WatchService is used only for instantaneous file
   creation notification.

### Mechanism

#### Agent side

##### ENTRY_CREATE (WatchService — instant)

1. The Agent detects `ENTRY_CREATE` for a supported file extension (.pdf, .epub, .mhtml).
2. It infers the author from the parent directory name.
3. It computes the **SHA-256 hash** of the file content.
4. It sends `POST /api/sources` with `{path, name, format, author, contentHash}` immediately.
5. The debounce window is kept at **500ms** (no need to pair DELETE+CREATE events).

##### ENTRY_DELETE (WatchService — ignored)

The WatchService `ENTRY_DELETE` event is **intentionally ignored**. Deletions are only detected
and processed during the periodic reconciliation scan. This eliminates the race condition between
DELETE and CREATE events entirely.

##### ENTRY_MODIFY (WatchService — ignored)

`ENTRY_MODIFY` is ignored. Content changes that use the safe-save pattern are handled through
the CREATE they generate on the same path (see safe-save logic above).

##### Scheduled Full Scan (every 5 minutes + on Agent startup)

1. The Agent walks the entire library directory, collecting all current files.
2. For each file:
    - If the file is **new or changed** (no matching record in DB from last scan), compute
      SHA-256 hash.
    - If the file is **unchanged** (matching hash cached from previous scan), skip hashing.
3. The Agent sends `POST /api/sync` with the complete file list, including `contentHash` for
   each entry.
4. The Agent receives a summary of changes applied (inserts, deletes, renames).

This design ensures that even if the WatchService misses an event (Agent restart, transient error),
the next scan catches up and corrects the state.

#### API side

##### POST /api/sources (with contentHash)

When a creation request arrives with a `contentHash`, the API follows this priority:

1. **Safe-save (same path)**: If a source with the same `path` already exists in the database:
    - Update `content_hash` on the existing record with the new hash.
    - **All metadata (tags, URL, year, edition, author) is preserved** because the record was
      never deleted (DELETE events are ignored).
    - Return the existing source (idempotent).

2. **Rename (same hash, different path)**: If no source exists with the same path, search the
   `sources` table for a record with the same `content_hash`:
    - `SELECT * FROM sources WHERE content_hash = :hash`
    - If found and the path is different → treat as **rename/move**:
        - Create a new source record at the new path, transferring all metadata (tags, URL, year,
          edition, author) from the old record.
        - Clear metadata fields on the old record and dissociate its tags.
        - Soft-delete the old source record (`SET deleted_at = now()`).
    - If not found → proceed as a normal INSERT (new source).

3. **Normal insert**: If neither condition matches, insert as a new source with no metadata
   (aside from the inferred author and file attributes).

The database itself serves as the persistent, concurrency-safe look-up structure. No in-memory
buffer is involved.

##### POST /api/sync (full reconciliation)

1. **Files in the sync list but not in DB** → insert as new sources (with hash, no metadata).
2. **Files in DB but not in the sync list** → soft delete (`SET deleted_at = now()`). Metadata is preserved for
   reactivation (see ADR 0002).
3. **Files in both → skip** (the instant CREATE already handled any updates).
4. **Rename fallback**: After the main reconciliation, the API runs a hash-matching pass:
    - Collect all "missing" records (DB but not FS) that have a non-null `content_hash`.
    - Collect all "new" records (FS but not DB) that have a `content_hash`.
    - If any missing hash matches a new hash → transfer metadata from the old record to the new
      one and delete the old record.
    - This handles edge cases where the rename was not detected at CREATE time (e.g., Agent was
      down during the CREATE event).

##### DELETE /api/sources/{id}

Physical delete (purge). Only for soft-deleted records (returns 409 if active). Called by:

- User purge action via Frontend.
- Internal sync cleanup (only for records that were already soft-deleted and the user wants to permanently remove).

### Schema

A nullable column `content_hash` is added to the `sources` table:

```sql
ALTER TABLE sources
    ADD COLUMN content_hash VARCHAR(64);
```

The `content_hash` is used for:

- Rename detection via DB lookup on CREATE.
- Rename fallback detection during sync reconciliation.
- Future deduplication or integrity verification features.

### Agent changes

- `FileEventHandler`: compute SHA-256 on `ENTRY_CREATE` for supported file types.
- `ScheduledScanService`: new component with a scheduled executor (default: 5-minute interval).
  Walks the filesystem, computes hashes for new/changed files, sends `POST /api/sync`.
- `AgentConfig`: scan interval (default 5 min), startup scan enabled/disabled, hash algorithm,
  hash size threshold.
- `ApiClient`: include `contentHash` in `POST /api/sources` and `POST /api/sync`.
- Debounce window: **500ms** (reduced from 3 seconds — no need to pair DELETE+CREATE).

### API changes

- `CreateSourceRequest`: add optional `contentHash` field (same as before).
- `SyncRequest`: add optional `contentHash` field per file (same as before).
- **Removed**: `DeletedSourceBuffer` component entirely.
- **Changed**: `SourceService.create()`: check for existing path (safe-save) → update hash;
  check for existing hash (rename) → transfer metadata; else insert.
- **Changed**: `SourceService.purge()`: physical delete only for soft-deleted records (409 if active).
  Added `SourceService.markDeleted()` for sync reconciliation (sets `deleted_at = now()`).
- **Changed**: `SourceService.sync()`: reconciliation with hash-based rename fallback pass.
- New Flyway migration: `V202607010007__add_content_hash_to_sources.sql`.

## Consequences

### Positive

- **No in-memory buffer** — eliminates TTL management, race conditions, concurrency complexity,
  and metadata loss on API restart.
- **Safe-save is trivial** — the source record is never deleted (DELETE ignored), so metadata
  is always preserved without any special logic.
- **Rename detected instantly** — via DB hash lookup at CREATE time. No delay, no buffer.
- **All platforms supported** — no native code or platform-specific APIs required.
- **Backward-compatible** — `contentHash` remains optional in request contracts.
- **Fault-tolerant** — if WatchService misses a CREATE (e.g., Agent restart), the periodic scan
  corrects the state within minutes.
- **Debounce simplified** — reduced to 500ms (just deduplication), no event-pairing logic.

### Negative

- **DELETE latency** — deletions are only reflected at the next scan interval (up to 5 minutes).
  Acceptable for a personal library catalog.
- **Hash computation on CREATE** — same CPU/I/O cost as the original approach.
- **Scan CPU/I/O load** — periodic full walk adds load every 5 minutes. Mitigated by only
  hashing new/changed files (detected via file size + mtime comparison against cached metadata).
- **DB lookup cost on CREATE** — `SELECT * FROM sources WHERE content_hash = :hash` adds a
  query per CREATE. Mitigated by a database index on `content_hash`.

### Limitations

- **Rename with simultaneous content change** — if a file is renamed AND modified between scans,
  the hash differs. Treated as delete + create. Metadata lost. This is inherent to any
  content-hash-based approach.
- **CREATE event missed** — if the Agent is down when a file is created, the instant detection
  is lost. The periodic scan detects it as a new file (no metadata). Acceptable — the scan
  interval is 5 minutes.
- **First scan after Agent install** — existing sources have no `content_hash`. The initial
  scan populates hashes for all files. Until then, rename detection for those files requires
  the hash-matching fallback during sync, which only works if the old record still has its
  hash populated from a previous scan.

## Alternatives Considered

### Alternative 1: In-Memory Buffer with TTL

Store metadata of recently deleted sources in an in-memory cache with a 5-minute TTL,
keyed by path and content hash. On CREATE, check the buffer for matches.
**Rejected**: Race conditions (out-of-order events), data loss on API restart,
concurrency complexity, and no support for pre-existing files.

### Alternative 2: NTFS USN Journal via JNI

Access the NTFS Change Journal directly to get authoritative rename events with
`FileReferenceNumber`. **Rejected**: Windows-only, requires native code (JNI), significantly
more complex, maintenance burden.

### Alternative 3: JNotify (JNI-based file monitoring)

Use a Java library that exposes `FILE_RENAMED` natively via JNI calls to the OS.
**Rejected**: Last release 2012, unmaintained, requires platform-specific native DLLs/SOs,
incompatible risk with Java 21.

### Alternative 4: Pure Scheduled Scan (no WatchService)

Eliminate WatchService entirely; rely solely on periodic scans (every 5 minutes).
**Rejected**: Acceptable for deletions but adds too much latency for file creation — the
primary interaction point for users.

## References

- ADR 0002 — Soft Delete and Surrogate ID (metadata preservation across deletions)
- System SDD §5.1 — File Creation
- System SDD §5.2 — File Deletion
- System SDD §5.3 — File Rename / Move
- System SDD §5.4 — Full Scan & Reconciliation
- System SDD §7.6 — Content Hash + DB Lookup for Rename and Safe-Save Detection
- Agent SDD §4.2 — Event Handling
- Agent SDD §4.4 — Debouncing
- Agent SDD §4.5 — Hash Computation
- [Resilio Sync — What happens when file is renamed](https://help.resilio.com/hc/en-us/articles/209606526-What-happens-when-file-is-renamed)
- [Syncthing — Renaming files and folders](https://forum.syncthing.net/t/renaming-files-and-folders/23836)
- [Dropbox — Content Hash](https://www.dropbox.com/developers/reference/content-hash)
- [Microsoft — ReadDirectoryChangesExW with FileId](https://devblogs.microsoft.com/oldnewthing/20260508-00?p=112310)
