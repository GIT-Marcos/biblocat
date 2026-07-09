# ADR 0001: Soft Delete and Surrogate ID for Metadata Preservation

**Tipo:** 🏛️ Decisión

## Status

Accepted

## Context

The system is designed around the principle that the **filesystem is the source of truth** — the
database is a reflection of what exists on disk. When a file is deleted from the filesystem, the
corresponding database record is physically deleted. This means the catalog has **no memory** of
what was once in the library.

This creates a problem: if files are lost due to accidental deletion, disk corruption, or
ransomware, all metadata (tags, URL, year, edition, author associations) is permanently lost.
The system cannot serve as an inventory or "insurance policy" for the user's library.

Additionally, a `path`-based primary key (as originally considered for the `sources` table)
creates issues with:

- **URL encoding**: special characters in file paths must be encoded in REST URLs.
- **Path changes**: a rename changes the natural identifier, requiring cascade updates to FKs.
- **Soft delete conflicts**: if two consecutive files occupy the same path (e.g., rename from
  `/a.pdf`, then a new file `/a.pdf` is created), the natural key conflicts with a soft-deleted
  record.

The content-hash rename detection approach defines the `content_hash` column on
`sources` for correlation. Soft delete naturally complements it by preserving state between
deletions, renames, and reactivations.

## Decision

We will implement **soft delete** with a **surrogate primary key** (`id`).

### Schema Changes

#### sources

| Change                   | Detail                                                                                                                                                                                                                                          |
|--------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Drop PK on `path`**    | Remove PRIMARY KEY constraint from `path`.                                                                                                                                                                                                      |
| **Add `id`**             | `id UUID PRIMARY KEY DEFAULT gen_random_uuid()` — new surrogate identifier.                                                                                                                                                                     |
| **Add `deleted_at`**     | `deleted_at TIMESTAMP NULL` — set when a file is removed from FS.                                                                                                                                                                               |
| **Partial unique index** | `CREATE UNIQUE INDEX idx_sources_active_path ON sources(path) WHERE deleted_at IS NULL` — ensures no two active sources share the same path.                                                                                                    |
| **Metadata cleaning**    | When metadata is transferred during a rename (see content-hash rename detection), the old soft-deleted record's metadata fields (`year`, `edition`, `url`, and `source_tags` associations) are cleared so reactivation produces a clean record. |

#### source_tags

| Change        | Detail                                                             |
|---------------|--------------------------------------------------------------------|
| **FK change** | `source_path VARCHAR` → `source_id UUID`                           |
| **New FK**    | `FOREIGN KEY (source_id) REFERENCES sources(id) ON DELETE CASCADE` |
| **New PK**    | `PRIMARY KEY (source_id, tag_id)`                                  |

The `ON DELETE CASCADE` is only triggered during **purge** (physical delete of a soft-deleted
record). Soft delete (`UPDATE deleted_at SET ...`) does not cascade, so tag associations are
preserved.

### Behavior Rules

#### 1. Soft Delete (File removed from FS)

When the reconciliation scan detects a file that is in the database but no longer on the
filesystem:

| Physical delete                          | Soft delete (this ADR)                                                            |
|------------------------------------------|-----------------------------------------------------------------------------------|
| `DELETE FROM sources WHERE path = :path` | `UPDATE sources SET deleted_at = now() WHERE path = :path AND deleted_at IS NULL` |

- The record remains in the database with all metadata preserved.
- `source_tags` associations are preserved (UPDATE does not cascade).
- Only files with `deleted_at IS NULL` are affected — the operation is idempotent.
- The scan **never reactivates** a soft-deleted record.

#### 2. Reactivation (File reappears in FS)

When the Agent detects a file at a path that has a soft-deleted record (during a reconciliation scan):

> **Nota:** El término "ENTRY_CREATE" se usa aquí como concepto lógico. El Agent no utiliza WatchService; detecta
> archivos mediante escaneos periódicos con `walkFileTree` y un `SimpleFileVisitor`.

```
POST /api/sources { path: /a.pdf, contentHash: H, ... }
  → API searches for a source with path = '/a.pdf' AND deleted_at IS NOT NULL
  → If found AND content_hash matches H → reactivate:
      UPDATE sources SET deleted_at = NULL, content_hash = H WHERE id = :id
      Metadata (tags, URL, year, edition) is preserved from the original record.
  → If found AND content_hash differs H → insert NEW source (no reactivation).
  → If not found → normal insert or safe-save/rename logic.
```

Reactivation requires **both** path and hash to match. This prevents inheriting metadata from a
different file that happens to use the same path.

#### 3. Rename Metadata Transfer (content-hash rename interaction)

When a rename is detected (same hash, different path):

1. API creates a new source record at the new path, transferring metadata from the old record.
2. API **clears** metadata fields (`year`, `edition`, `url`) on the old record and dissociates
   its tags.
3. API soft-deletes the old record (`SET deleted_at = now()`).
4. If the old record is later reactivated (file reappears at old path with same hash), it starts
   with clean metadata — no conflict with the transferred data.

This prevents a scenario where reactivating /a.pdf (which was renamed to /b.pdf) incorrectly
inherits metadata that now belongs to /b.pdf.

#### 4. Purge (User action)

The user can permanently delete a soft-deleted record from the UI:

```
DELETE /api/sources/{id}
  → 409 Conflict if deleted_at IS NULL (record is active)
  → 204 No Content if deleted_at IS NOT NULL → physical DELETE with CASCADE
```

Purge is the only operation that performs a physical delete. It requires the source to already
be in soft-deleted state.

#### 5. No Soft Delete from UI

There is no user-facing "delete source" action for active records. The only way to soft-delete a
source is to remove the corresponding file from the filesystem. This ensures consistency with the
"filesystem as source of truth" principle and avoids the ambiguity of a record that represents a
file that still exists but is hidden from the catalog.

Users can, however:

- **View soft-deleted sources** via `?includeDeleted=true` query parameter.
- **Purge** soft-deleted sources permanently.
- **Reactivation** happens automatically when the file reappears in the FS with the same content hash (automatic via
  reconciliation).

### REST API Changes

| Endpoint                   | Change                                                                              |
|----------------------------|-------------------------------------------------------------------------------------|
| `GET /api/sources`         | Add query param `?includeDeleted=false` — when true, includes soft-deleted sources. |
| `GET /api/sources/{id}`    | Uses UUID surrogate ID instead of path-based URL.                                   |
| `PATCH /api/sources/{id}`  | Uses UUID surrogate ID instead of path-based URL.                                   |
| `DELETE /api/sources/{id}` | Uses UUID. **Purge-only**: returns 409 if source is active.                         |

### Flyway Migration

```sql
-- V202607020002__add_soft_delete_and_surrogate_id.sql

-- 1. Add surrogate ID and deleted_at to sources
ALTER TABLE sources
    ADD COLUMN id UUID DEFAULT gen_random_uuid() FIRST,
    ADD COLUMN deleted_at TIMESTAMP NULL;

-- 2. Add partial unique index on active paths
CREATE UNIQUE INDEX idx_sources_active_path
    ON sources (path) WHERE deleted_at IS NULL;

-- 3. Migrate source_tags to use source_id
ALTER TABLE source_tags
    ADD COLUMN source_id UUID;

UPDATE source_tags st
SET source_id = s.id FROM sources s
WHERE st.source_path = s.path;

ALTER TABLE source_tags
    ALTER COLUMN source_id SET NOT NULL;

-- 4. Drop old FK and PK on source_tags
ALTER TABLE source_tags
DROP
CONSTRAINT source_tags_pkey,
    DROP
CONSTRAINT source_tags_source_path_fkey,
    DROP
COLUMN source_path;

-- 5. Add new FK and PK on source_tags
ALTER TABLE source_tags
    ADD PRIMARY KEY (source_id, tag_id),
    ADD FOREIGN KEY (source_id) REFERENCES sources(id) ON
DELETE
CASCADE;

-- 6. Make path non-null (it was the PK, so it already is)
--    Drop the old PK constraint on sources
ALTER TABLE sources
    DROP CONSTRAINT sources_pkey CASCADE;
```

## Consequences

### Positive

- **Metadata preserved** across accidental deletions, disk issues, and ransomware.
- **Reactivation** automatically restores tags, author, and other metadata when a file reappears.
- **Purge** gives the user explicit control over permanent removal.
- **Surrogate ID** eliminates URL encoding issues, path conflicts on soft-deleted records, and
  simplifies REST API design.
- **Consistent with content-hash rename detection** — rename detection via content_hash remains unchanged; only the
  final step switches from physical DELETE to soft DELETE + metadata clearing.

### Negative

- **All read queries** must filter by `deleted_at IS NULL` (except when the user explicitly asks
  for deleted sources). Mitigated by Hibernate `@Where(clause = "deleted_at IS NULL")`.
- **Larger table** — soft-deleted records accumulate indefinitely. Mitigated by the personal-use
  scope (thousands of records is negligible).
- **Migration complexity** — changing PK from `path` to `id` requires a multi-step Flyway
  migration with data backfill.
- **REST API uses `{id}` instead of `{path}`** — endpoints are designed with UUIDs from the
  start, avoiding the encoding and stability issues of path-based URLs.

### Limitations

- **No undo for purge** — once purged, metadata is permanently lost. The user is warned before
  purging.
- **Tags of purged sources** are deleted via CASCADE. The tags themselves (in the `tags` table)
  are preserved — only the association is dropped.
- **Reactivation only via CREATE** — if a file reappears but the Agent misses the CREATE event
  (e.g., Agent was down), the next full scan will find the file on disk and... what? The scan
  should detect that the file exists and the record is soft-deleted, and reactivate it. This
  is handled by adding a reactivation pass to the sync reconciliation logic.

## Alternatives Considered

### Alternative 1: Separate History Table

Instead of soft delete, move deleted records to a `source_deletions` history table with a
snapshot of the metadata at deletion time.
**Rejected**: Queries across active and deleted records require UNIONs. Reactivation requires
copying data back. Increased complexity.

### Alternative 2: Soft Delete with Path as PK

Keep `path` as the natural PK. When a file is deleted, set `deleted_at`. When a new file needs
the same path, fail on PK conflict.
**Rejected**: Rename detection may need to create a record at a path that has a
soft-deleted record. Surrogate ID avoids this entirely.

### Alternative 3: Physical Delete (no history)

Delete records permanently when files are removed from the filesystem.
**Rejected**: The system cannot serve as an inventory. Metadata loss on FS failure is
unacceptable for the stated purpose.

## References

- `../architecture.md` §6.1 — Full Scan & Reconciliation flow
- `../architecture.md` §6.1.3 — File Deletion flow (soft delete)
- `../api.md` §4.2 — Entity model (sources table)
- `../api.md` §2.1 — REST endpoints (Sources)
- Moved from `ADR-0002` to `ADR-0001` on 2026-07-08 — sole ADR in the repo, renumbered for consistency
