# BiblioCat — Software Design Description

## Document Control

| Property        | Value                                         |
|-----------------|-----------------------------------------------|
| **Version**     | 1.0                                           |
| **Status**      | Draft — initial definition for implementation |
| **Last update** | 2026-07-02                                    |

---

## 1. Introduction

### 1.1 Purpose

BiblioCat is a personal library catalog system. It allows users to catalog and manage PDF, EPUB and MHTML files stored
on their local filesystem. The system does **not** store files — it only stores metadata and filesystem references.

This document describes the system-level architecture, component decomposition, data model, behavior flows, interface
contracts, and design rationale. It is the authoritative reference for how BiblioCat is built.

Module-specific design details are covered in separate documents:

| Module   | Document            |
|----------|---------------------|
| API      | `docs/api/SDD.md`   |
| Agent    | `docs/agent/SDD.md` |
| Frontend | `docs/front/SDD.md` |

### 1.2 Scope

This document covers:

- System architecture principles and constraints
- Component responsibilities and boundaries
- Domain model and data rules
- Behavioral flows (CRUD, sync, reconciliation)
- Inter-component communication contracts
- Key design decisions and their rationale
- Evolution roadmap

It does **not** cover:

- Detailed REST endpoint specifications → see `docs/api/SDD.md`
- WatchService implementation details → see `docs/agent/SDD.md`
- UI component trees or state management → see `docs/front/SDD.md`
- Requirements specification → tracked in project issues

### 1.3 Glossary

| Term                | Definition                                                                                             |
|---------------------|--------------------------------------------------------------------------------------------------------|
| **Source**          | A file (PDF, EPUB, or MHTML) discovered in the library directory and represented as a database record. |
| **Agent**           | Java daemon that monitors the filesystem via WatchService and communicates changes to the API.         |
| **API**             | Spring Boot application exposing REST endpoints, managing persistence and domain logic.                |
| **Frontend**        | React application providing the user interface.                                                        |
| **Reconciliation**  | Process of synchronizing the database state with the current filesystem state via a full scan.         |
| **WatchService**    | Java NIO mechanism for detecting filesystem events (create, delete, modify) in real time.              |
| **Filesystem (FS)** | The local directory containing the user's library files. The single source of truth.                   |
| **Source format**   | The file type discriminator: PDF, EPUB, or MHTML.                                                      |
| **Tag**             | User-assigned label for categorizing sources.                                                          |

---

## 2. System Architecture

### 2.1 Architecture Principles

1. **Separation of responsibilities** — Each component has a well-defined concern:
    - API → domain logic, persistence, REST exposure
    - Agent → filesystem monitoring and synchronization
    - Frontend → user interface
    - Database → passive metadata storage

2. **Filesystem as source of truth** — The local filesystem is the authoritative record for which documents exist. The
   database is a reflection, not an independent store.

3. **API as single source of business logic** — All domain logic, validation, and persistence decisions reside in the
   API. No other component may duplicate this logic.

4. **No direct FS access from API or Frontend** — Only the Agent interacts with the filesystem. This prevents locking,
   path confusion, and duplicate detection logic.

5. **Agent as sensor, not actor** — The Agent detects changes and reports them. It does not download, convert, enrich,
   or modify files.

### 2.2 Source of Truth

> **The filesystem is the truth.**  
> **The API is the interpretation.**  
> **The frontend is the visualization.**  
> **The agent is the sensor.**

- When a file is added to the library directory, the system must reflect it.
- When a file is deleted from the library directory, the system must soft-delete its record (preserving metadata).
- When a file is renamed or moved, the system detects it via DELETE + CREATE events and uses
  **content hash matching** to restore metadata on the new path.
- The system never creates, modifies, or deletes files on the filesystem.

### 2.3 System Context

```
┌──────────┐     HTTP      ┌─────────┐     JDBC      ┌──────────┐
│  Agent   │ ──────────▶   │   API   │ ◀──────────▶  │    DB    │
│ (Java)   │ ◀──────────   │ (Spring)│               │ (Postgres)│
└──────────┘               └─────────┘               └──────────┘
                               │ ▲
                               │ │ HTTP
                               ▼ │
                           ┌─────────┐
                           │ Frontend│
                           │ (React) │
                           └─────────┘
```

- Agent ↔ API: HTTP REST (bidirectional)
- Frontend ↔ API: HTTP REST (bidirectional)
- Frontend ↛ Agent: **prohibited**
- API ↛ filesystem: **prohibited**

### 2.4 Technology Stack

| Layer                   | Technology      | Version |
|-------------------------|-----------------|---------|
| API framework           | Spring Boot     | 4.1     |
| API language            | Java            | 21      |
| API build               | Maven (wrapped) | —       |
| API database migrations | Flyway          | —       |
| Agent language          | Java            | 21      |
| Agent build             | Maven (wrapped) | —       |
| Frontend framework      | React           | 19      |
| Frontend language       | TypeScript      | 6       |
| Frontend bundler        | Vite            | 8       |
| Frontend linter         | ESLint          | —       |
| Database                | PostgreSQL      | —       |

### 2.5 Constraints

| Constraint                 | Rationale                                                        |
|----------------------------|------------------------------------------------------------------|
| No microservices           | The system is simple; a monorepo with 3 components is sufficient |
| No full-text search        | File content search is out of scope for V1                       |
| No embeddings or AI        | Not needed for a metadata-only catalog                           |
| No URL downloader          | The Agent is a sensor, not a content fetcher                     |
| Low operational complexity | Target: single-developer maintenance                             |
| Low resource consumption   | Must run on modest hardware                                      |

---

## 3. Component Decomposition

### 3.1 API

The API is a Spring Boot application that exposes REST endpoints, manages database persistence via JPA and Flyway, and
coordinates synchronization events from the Agent.

#### Responsibilities

- Persist and query metadata in PostgreSQL
- Manage the domain model (sources, authors, tags, formats)
- Expose REST endpoints for CRUD and search
- Accept synchronization events from the Agent
- Execute Flyway migrations on startup
- Validate all incoming data

#### Restrictions

- Must not access the filesystem
- Must not run WatchService
- Must not contain local synchronization logic

### 3.2 Agent

The Agent is a standalone Java daemon that monitors the filesystem, detects changes (create, delete, rename), and
communicates them to the API over HTTP.

#### Responsibilities

- Monitor the library directory via WatchService (real time)
- Perform a full scan on first startup
- Detect file creation, deletion, and rename/move events
- Parse the folder structure to infer authors
- Send change notifications to the API
- Accept reconciliation trigger requests

#### Restrictions

- Must not access the database directly
- Must not contain domain logic
- Must not expose a user interface
- Must not download, convert, or generate files

#### Filesystem Structure

```
Biblioteca/                     ← library root
├── Autor/                      ← author level (optional)
│   ├── obra.pdf
│   └── obra.epub
├── OtroAutor/
│   └── documento.pdf
└── documento_sin_autor.mhtml   ← no author
```

- Level 1: author directory (optional). If present, used to infer `author_id`.
- Level 2: files. Only `.pdf`, `.epub`, `.mhtml` are cataloged.
- Empty author directories → create author record without sources.
- Files directly in the library root → author = null.

### 3.3 Frontend

The Frontend is a React application that provides the user interface for browsing, searching, and managing the catalog.

#### Responsibilities

- Display sources with their metadata (author, tags, format, etc.)
- Provide search and filter capabilities
- Allow manual management of authors, tags, and formats
- Trigger manual reconciliation

#### Restrictions

- Must not contain business logic
- Must not access the filesystem
- Must not communicate directly with the Agent

### 3.4 Database

PostgreSQL managed by Flyway within the API component.

- Not an independent project
- Passive storage — no triggers, no application logic
- Managed exclusively by the API's Flyway migrations

---

## 4. Data Model

### 4.1 Entity-Relationship Overview

```
┌──────────┐       ┌───────────┐       ┌──────────┐
│  authors │       │  sources  │       │   tags   │
├──────────┤       ├───────────┤       ├──────────┤
│ id (PK)  │◀──┐   │ id (PK)   │   ┌──▶│ id (PK)  │
│ name     │   │   │ path      │   │   │ name     │
└──────────┘   │   │ name      │   │   └──────────┘
               └───│ author_id │   │
                    │ (FK)      │   │
┌──────────┐       │ source_fmt│   │   ┌──────────────┐
│source_fmts│       │ id (FK)   │   │   │ source_tags  │
├──────────┤       │ year      │   │   ├──────────────┤
│ id (PK)  │◀──────│ edition   │   │   │ source_id    │──┼──┐
│ name     │       │ url       │   │   │ (FK)         │  │  │
└──────────┘       │ deleted_at│   │   │ tag_id (FK)  │──┘  │
                    │ content_ha│   │   └──────────────┘     │
                    │ sh        │   │                        │
                    │ created_at│   │                        │
                    │ updated_at│   │                        │
                    └───────────┘   │                        │
                                    └────────────────────────┘
                                PK: (source_id, tag_id)
```

### 4.2 sources

Primary entity. Represents a file discovered in the library directory.

| Column             | Type      | Constraints                      | Description                                                            |
|--------------------|-----------|----------------------------------|------------------------------------------------------------------------|
| `id`               | BIGSERIAL | PK                               | Surrogate primary key, stable identifier for a source.                 |
| `path`             | VARCHAR   | NOT NULL                         | Absolute filesystem path. Unique among active records.                 |
| `name`             | VARCHAR   | NOT NULL                         | File name without path. Not unique.                                    |
| `author_id`        | BIGINT    | FK → authors.id, nullable        | Inferred from directory structure.                                     |
| `source_format_id` | BIGINT    | FK → source_formats.id, NOT NULL | PDF, EPUB, or MHTML.                                                   |
| `year`             | INT       | nullable                         | User-assigned publication year.                                        |
| `edition`          | VARCHAR   | nullable                         | User-assigned edition string.                                          |
| `url`              | VARCHAR   | nullable                         | Original URL for web articles. Must be valid HTTP/HTTPS if provided.   |
| `content_hash`     | VARCHAR   | nullable                         | SHA-256 hash of file content. Used for rename and safe-save detection. |
| `deleted_at`       | TIMESTAMP | nullable                         | Set when file is removed from FS. Metadata preserved for reactivation. |
| `created_at`       | TIMESTAMP | NOT NULL                         | Record creation timestamp.                                             |
| `updated_at`       | TIMESTAMP | NOT NULL                         | Last modification timestamp.                                           |

#### Business Rules

- `id` (BIGSERIAL) is the primary key and stable identifier for a source.
- `path` has a partial unique index: `UNIQUE WHERE deleted_at IS NULL`. Two active sources cannot share the same path,
  but a soft-deleted source can coexist with a new one at the same path.
- `name` is **not** unique — two files with the same name can exist in different directories.
- If a file is deleted from the filesystem, its database record is **soft deleted** (`deleted_at` is set). Metadata is
  preserved.
- Purging a soft-deleted record performs a **physical delete** (irreversible).
- If `url` is provided, it must be a valid HTTP or HTTPS URL.

### 4.3 authors

| Column | Type    | Constraints        | Description          |
|--------|---------|--------------------|----------------------|
| `id`   | BIGINT  | PK, auto-generated | —                    |
| `name` | VARCHAR | UNIQUE, NOT NULL   | Author display name. |

### 4.4 tags

| Column | Type    | Constraints        | Description       |
|--------|---------|--------------------|-------------------|
| `id`   | BIGINT  | PK, auto-generated | —                 |
| `name` | VARCHAR | UNIQUE, NOT NULL   | Tag display name. |

### 4.5 source_formats

| Column | Type    | Constraints        | Description                    |
|--------|---------|--------------------|--------------------------------|
| `id`   | BIGINT  | PK, auto-generated | —                              |
| `name` | VARCHAR | UNIQUE, NOT NULL   | Format name: PDF, EPUB, MHTML. |

### 4.6 source_tags

Join table for the many-to-many relationship between sources and tags.

| Column      | Type   | Constraints                        | Description |
|-------------|--------|------------------------------------|-------------|
| `source_id` | BIGINT | FK → sources.id, ON DELETE CASCADE | —           |
| `tag_id`    | BIGINT | FK → tags.id                       | —           |

**Primary key**: (`source_id`, `tag_id`)

When a source is **purged** (physically deleted, not soft-deleted), its tag associations are
deleted in cascade. Soft delete (`SET deleted_at`) does **not** cascade, so tags are preserved.

---

## 5. Behavior & Data Flow

### 5.1 File Creation

**Trigger**: A file is created in the library directory (copy, download, move-in).

```
Agent (WatchService)               API
┌──────────────┐                  ┌──────────┐
│ Detects       │                  │          │
│ ENTRY_CREATE  │  POST /sources  │          │
│ for file.pdf  │ ──────────────▶ │ Checks   │
│               │  {path, name,   │ for      │
│ Parses        │   format,       │ existing │
│ author from   │   author,       │ path or  │
│ folder        │   contentHash}  │ hash in  │
│               │                  │ DB       │
│ Computes      │                  │          │
│ SHA-256       │                  │ Safe-    │
│ of file       │                  │ save? →  │
│               │                  │ update   │
│               │                  │ hash     │
│               │                  │ Rename?  │
│               │                  │ → trans- │
│               │                  │ fer meta │
│               │                  │ New? →   │
│               │                  │ insert   │
└──────────────┘                  └──────────┘
```

1. WatchService detects `ENTRY_CREATE` for a supported file extension (.pdf, .epub, .mhtml).
2. Agent infers the author from the parent directory name (if not at root level).
3. Agent computes the SHA-256 hash of the file content.
4. Agent sends a creation request to the API with path, name, format, inferred author, and content hash.
5. API validates the data and handles the request in this priority:
    - **Safe-save**: if a source with the same `path` already exists → updates `content_hash` and
      returns existing record (metadata preserved because record was never deleted).
    - **Rename**: if a source with the same `content_hash` exists at a different `path` → transfers
      all metadata (tags, URL, year, edition) from the old record to the new one, deletes old record.
    - **New**: if neither match → inserts a new source with no additional metadata.
6. If the inferred author does not exist in `authors`, the API creates it.

No in-memory buffer is used. Metadata preservation relies entirely on database lookups.
See `docs/decisions/0001-content-hash-rename-detection.md`.

### 5.2 File Deletion

**Trigger**: A file is deleted from the library directory.

```
Agent (WatchService)               API
┌──────────────┐                  ┌──────────┐
│ Detects       │                  │          │
│ ENTRY_DELETE  │  ── ignored ──▶ │ Nothing  │
│ for file.pdf  │                  │          │
│               │                  │ Deletion │
│ ... 5 min     │                  │ detected │
│ later (scan)  │  POST /api/sync │ during   │
│ Walks entire  │ ──────────────▶ │ reconcil-│
│ library dir   │  [current       │ iation:  │
│               │   file list]    │ record   │
│               │                  │ not in   │
│               │                  │ list →   │
│               │                  │ delete   │
└──────────────┘                  └──────────┘
```

1. **WatchService `ENTRY_DELETE` is intentionally ignored.** No immediate action is taken.
2. On the next scheduled reconciliation scan (every 5 minutes), the Agent walks the entire library
   directory and sends `POST /api/sync` with the complete current file list.
3. API compares the list against the database:
    - Files in the list → skip or insert.
    - Files in DB but **not** in the list → **soft delete** the source record.
      `SET deleted_at = now()`. Tag associations are preserved (soft delete is an UPDATE,
      not a DELETE — `ON DELETE CASCADE` is NOT triggered).
4. Deletions are reflected in the catalog with a maximum latency of one scan interval.
5. If a file reappears later (same path + same hash), the API **reactivates** the record
   (`SET deleted_at = NULL`) and restores all metadata. See ADR 0002 for details.

No in-memory buffer is involved. Metadata is preserved across deletions via soft delete.

### 5.3 File Rename / Move

**Trigger**: A file is renamed or moved within the library directory.

```
Agent (WatchService)               API
┌──────────────┐                  ┌──────────┐
│ Detects       │                  │          │
│ ENTRY_DELETE  │  ── ignored ──▶ │ Nothing  │
│ (old path)    │                  │          │
│ then          │                  │ Searches │
│ ENTRY_CREATE  │  POST /sources  │ DB for   │
│ (new path)    │ ──────────────▶ │ matching │
│               │  {path, name,   │ content_ │
│               │   format,       │ hash     │
│               │   author,       │          │
│               │   contentHash}  │ Found →  │
│               │                  │ transfer │
│               │                  │ metadata │
│               │                  │ Not found│
│               │                  │ → insert │
│               │                  │ new      │
└──────────────┘                  └──────────┘
```

Rename/move is handled as a DELETE + CREATE sequence (Windows WatchService cannot distinguish
renames from delete+create). However, metadata is **preserved** via content hash matching in
the database:

1. **WatchService `ENTRY_DELETE` is ignored.** The old record remains in the database.
2. WatchService detects `ENTRY_CREATE` at the new path. Agent computes the SHA-256 hash and
   sends `POST /api/sources` with `contentHash`.
3. API searches the `sources` table for a record with the same `content_hash` but a different
   path (`SELECT * FROM sources WHERE content_hash = :hash AND path != :path`).
4. If found → treat as **rename/move**:
    - Create a new source record at the new path.
    - Transfer all metadata (tags, URL, year, edition) from the old record.
    - Clear metadata fields (`year`, `edition`, `url`) on the old record and dissociate its
      tags.
    - Soft-delete the old record (`SET deleted_at = now()`).
5. If not found → insert as a new source (no metadata).
6. **Fallback**: If the CREATE event was missed (e.g., Agent was down), the periodic
   reconciliation scan detects the rename via hash matching: the old record is marked as
   "missing" (not in FS list), the new file is "new" (not in DB), and a matching hash
   triggers metadata transfer.

No in-memory buffer is used. Metadata preservation relies entirely on database lookups.
Detailed design: see `docs/decisions/0001-content-hash-rename-detection.md`.

### 5.4 Full Scan & Reconciliation

**Trigger**: Agent startup (first install) and periodically every 5 minutes (scheduled).
Also triggered manually by the user via the Frontend.

```
Agent                              API
┌──────────────┐                  ┌──────────┐
│ Walks entire  │                  │          │
│ library dir   │  POST /sync     │          │
│ Collects all  │ ──────────────▶ │ For each │
│ current files │  [{path, name,  │ file:    │
│ with hashes   │    format,      │ - exists?│
│ for new ones} │    author,      │   skip   │
│               │    contentHash},│ - new?   │
│               │   ...]          │   insert │
│               │                  │          │
│               │                  │ Deletes  │
│               │                  │ records  │
│               │                  │ for paths│
│               │                  │ NOT in   │
│               │                  │ the list │
│               │                  │          │
│               │                  │ Rename   │
│               │                  │ fallback:│
│               │                  │ hash-    │
│               │                  │ match    │
│               │                  │ missing  │
│               │                  │ vs new   │
└──────────────┘                  └──────────┘
```

1. Agent walks the entire library directory, collecting all current files.
   For new or changed files (detected via size/mtime), it computes the SHA-256 hash.
2. Agent sends the complete file list with `contentHash` per file to the API.
3. API performs a **set reconciliation**:
    - Files in the list but not in the DB → insert (with hash, no metadata).
    - Files in the DB but not in the list → soft delete (SET `deleted_at = now()`).
      Tag associations are preserved (soft delete is an UPDATE, not a DELETE).
    - Files in both → skip (already synchronized by instant CREATES).
4. **Rename fallback**: After the main pass, the API matches "missing" records (DB but not FS)
   against "new" records (FS but not DB) by `content_hash`. For each match, metadata is
   transferred from the old to the new record and the old record is soft-deleted.
5. The scan is the only mechanism that detects deletions. It also serves as a safety net for
   any CREATE events missed by WatchService (e.g., Agent was down).

---

## 6. Interface Design

### 6.1 REST API Overview

The API exposes a RESTful HTTP interface. All communication uses JSON.

| Method   | Path                | Purpose                                | Source          |
|----------|---------------------|----------------------------------------|-----------------|
| `POST`   | `/api/sources`      | Create a new source                    | Agent, Frontend |
| `GET`    | `/api/sources`      | List / search sources                  | Frontend        |
| `GET`    | `/api/sources/{id}` | Get single source                      | Frontend        |
| `DELETE` | `/api/sources/{id}` | Purge a soft-deleted source            | Frontend        |
| `PATCH`  | `/api/sources/{id}` | Update source metadata                 | Frontend        |
| `POST`   | `/api/sync`         | Full reconciliation (upload file list) | Agent           |
| `POST`   | `/api/reconcile`    | Trigger reconciliation                 | Frontend        |
| `GET`    | `/api/authors`      | List authors                           | Frontend        |
| `GET`    | `/api/tags`         | List tags                              | Frontend        |
| `GET`    | `/api/formats`      | List source formats                    | Frontend        |

Detailed endpoint specifications (request/response bodies, status codes, validation rules) are documented in
`docs/api/SDD.md`.

### 6.2 Communication Patterns

| Pattern                  | Description                                                                                                                    |
|--------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| **Event notification**   | Agent detects a change and sends a single HTTP request per event (CREATE, DELETE). Simple, reliable, no message broker needed. |
| **Bulk sync**            | Agent collects all current files and sends them in a single request. Used for startup and reconciliation.                      |
| **Request-response**     | Frontend queries the API directly. Standard REST pattern.                                                                      |
| **No events or pub/sub** | Not needed for V1. Direct HTTP is sufficient given the single-user scope.                                                      |

---

## 7. Design Decisions

### 7.1 Physical Delete vs Soft Delete

|               | Decision                                                                                                                                                                                                                             |
|---------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**    | Soft delete — when a file is removed from the filesystem, the database record is **not deleted**. Instead, `deleted_at` is set. Metadata (tags, URL, year, edition) is preserved.                                                    |
| **Rationale** | The system serves as an inventory "insurance policy." If files are lost due to accidental deletion, disk corruption, or ransomware, the catalog preserves what the user had. Reactivation restores metadata when the file reappears. |
| **Trade-off** | All queries require `WHERE deleted_at IS NULL` (mitigated by Hibernate `@Where`). Soft-deleted records accumulate (irrelevant for personal use). Purge is an explicit user action requiring confirmation.                            |
| **Reference** | `docs/decisions/0002-soft-delete-and-surrogate-id.md`                                                                                                                                                                                |

### 7.2 WatchService in a Separate Agent

|                          | Decision                                                                                                                                                                  |
|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**               | Filesystem monitoring runs in a separate Java process (Agent), not embedded in the API.                                                                                   |
| **Rationale**            | WatchService is blocking and platform-specific. Keeping it separate prevents filesystem issues from affecting API availability. The Agent can be restarted independently. |
| **Alternative rejected** | Embedding WatchService in the API would couple persistence to filesystem monitoring and break the principle that the API should not access the FS.                        |

### 7.3 No Full-Text Search (V1)

|               | Decision                                                                                                                                                                              |
|---------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**    | Only metadata search (name, author, tags, format, URL).                                                                                                                               |
| **Rationale** | Full-text search requires indexing file contents (PDF parsing, EPUB extraction), which adds significant complexity. The catalog focuses on metadata organization, not content search. |
| **Future**    | Can be added in V2 if needed, possibly via a sidecar indexer.                                                                                                                         |

### 7.4 No Download Capability

|                   | Decision                                                                                                                                                                                               |
|-------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**        | The system does not download files from URLs.                                                                                                                                                          |
| **Rationale**     | The filesystem is the source of truth. The Agent is a sensor, not an actor. Downloading would require network access, error handling for download failures, and storage management — all out of scope. |
| **User workflow** | Users save files to the library directory manually (browser, extension, external tool). The Agent detects them automatically.                                                                          |

### 7.5 Monorepo with Independent Modules

|               | Decision                                                                                                     |
|---------------|--------------------------------------------------------------------------------------------------------------|
| **Chosen**    | Monorepo with separate build systems (each module has its own Maven wrapper / npm).                          |
| **Rationale** | Independent builds and deployments without a root POM. Each module can be developed and tested in isolation. |

### 7.6 Content Hash + DB Lookup for Rename and Safe-Save Detection

|               | Decision                                                                                                                                                                                                                                                                                                                                                  |
|---------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**    | The Agent computes a SHA-256 hash on every CREATE event. The API searches the `sources` table by `content_hash` in the database: **same path** → safe-save (update hash, metadata preserved), **same hash** different path → rename (transfer metadata). No in-memory buffer. Deletions are detected only via periodic reconciliation scan. See ADR 0001. |
| **Rationale** | An in-memory buffer approach was considered but had race conditions (out-of-order events), vulnerability to restart, and concurrency complexity. Using the DB as the look-up structure eliminates these issues. Safe-save is handled trivially because DELETE events are ignored — the record is never deleted.                                           |
| **Trade-off** | DB lookup adds a query per CREATE (mitigated by index on `content_hash`). Deletions have up to 5-minute latency (detected only via scan). SHA-256 computation cost on every CREATE is unchanged from the hash-on-CREATE approach.                                                                                                                         |

| **Alternatives
** | In-memory buffer with TTL (rejected). NTFS USN Journal (Windows-only, JNI). JNotify (unmaintained). Pure scheduled
scan (high latency for creation). |
---

## 8. Roadmap

### 8.1 V1 (MVP)

- [x] System design (this document)
- [ ] API: database schema, migrations, REST endpoints, source CRUD
- [ ] Agent: WatchService (CREATE only), full scan, HTTP communication with API
- [ ] Agent: SHA-256 hash computation on file creation (rename and safe-save detection)
- [ ] Agent: periodic reconciliation scan (5-minute interval)
- [ ] API: DB-based rename detection via content_hash lookup (no in-memory buffer)
- [ ] Soft delete with surrogate ID (ADR 0002)
- [ ] Frontend: source list, search, metadata editing
- [ ] Manual reconciliation
- [ ] Frontend: deleted sources view and purge

### 8.2 V2 (Candidate)

Features under consideration, not yet committed:

- Genre taxonomy (if tag usage shows need)
- Full-text search (if requirements grow)

---

## 9. References

| Reference                     | Location                                              |
|-------------------------------|-------------------------------------------------------|
| API detailed design           | `docs/api/SDD.md`                                     |
| Agent detailed design         | `docs/agent/SDD.md`                                   |
| Frontend detailed design      | `docs/front/SDD.md`                                   |
| Architecture Decision Records | `docs/decisions/`                                     |
| ADR 0002: Soft Delete         | `docs/decisions/0002-soft-delete-and-surrogate-id.md` |

---

## Appendix A: Repository Conventions

```
biblocat/
├── api/          Spring Boot + Flyway
├── agent/        Java 21 daemon
├── front/        React + Vite + TypeScript
├── docs/         SDD + module SDDs + ADRs
└── infra/        Docker, scripts, CI/CD
```

- Each module is an independent project with its own build system.
- No root POM exists. Each Java module has its own Maven wrapper (`api/mvnw`, `agent/mvnw`).
- Package naming: `com.biblocat.api` (API), `com.biblocat` (Agent).
- Frontend build: `npm run build` runs `tsc -b && vite build`.
- All documentation is in Markdown, version-controlled alongside code.
