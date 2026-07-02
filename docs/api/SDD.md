# API Module — Software Design Description

## Document Control

| Property        | Value                                         |
|-----------------|-----------------------------------------------|
| **Version**     | 1.0                                           |
| **Status**      | Draft — initial definition for implementation |
| **Last update** | 2026-07-02                                    |
| **System SDD**  | `docs/SDD.md`                                 |

---

## 1. Introduction

### 1.1 Purpose

This document describes the detailed design of the API module. It covers package organization, REST endpoint contracts,
service layer design, data access patterns, error handling, validation rules, configuration, and testing strategy.

It is the implementation guide for the `api/` module. It assumes familiarity with the system-level design documented in
`docs/SDD.md`.

### 1.2 Scope

This document covers:

- Module architecture and package structure
- Full REST API specification (request/response bodies, status codes, validation)
- Service layer responsibilities and method contracts
- Repository design and query patterns
- Flyway migration strategy
- Error handling and exception model
- Input validation rules
- Spring configuration and profiles
- Testing approach per layer

It does **not** cover:

- System architecture principles → see `docs/SDD.md`
- The domain model → see `docs/SDD.md` section 4
- Cross-component behavior flows → see `docs/SDD.md` section 5
- Filesystem monitoring logic → see `docs/agent/SDD.md`

---

## 2. Module Architecture

### 2.1 Layer Diagram

```
┌─────────────────────────────────────────────────┐
│                  Controller                      │
│  (HTTP request/response, validation, routing)    │
├─────────────────────────────────────────────────┤
│                   Service                        │
│  (business logic, orchestration, transactions)   │
├─────────────────────────────────────────────────┤
│                 Repository                       │
│  (data access, JPA queries, Flyway migrations)   │
├─────────────────────────────────────────────────┤
│               Domain / Entity                    │
│  (JPA entities, DTOs, enums)                    │
└─────────────────────────────────────────────────┘
```

Dependency direction: **Controller → Service → Repository → Entity**

Cross-cutting concerns (apply to all layers):

- **Validation** — Bean Validation at controller boundary
- **Exception handling** — `@ControllerAdvice` catches all layer exceptions
- **Mapping** — DTO ↔ Entity conversions (manual or via MapStruct)

### 2.2 Package Structure

```
com.biblocat.api
├── ApiApplication.java
├── config/
│   └── WebConfig.java                  ← CORS, Jackson, etc.
├── controller/
│   ├── SourceController.java
│   ├── AuthorController.java
│   ├── TagController.java
│   ├── FormatController.java
│   └── SyncController.java
├── dto/
│   ├── request/
│   │   ├── CreateSourceRequest.java
│   │   ├── UpdateSourceRequest.java
│   │   └── SyncRequest.java
│   └── response/
│       ├── SourceResponse.java
│       ├── AuthorResponse.java
│       ├── TagResponse.java
│       ├── FormatResponse.java
│       ├── ErrorResponse.java
│       └── PagedResponse.java
├── entity/
│   ├── Source.java
│   ├── Author.java
│   ├── Tag.java
│   ├── SourceFormat.java
│   └── SourceTag.java
├── exception/
│   ├── SourceNotFoundException.java
│   ├── AuthorNotFoundException.java
│   ├── TagNotFoundException.java
│   ├── DuplicateSourceException.java
│   ├── InvalidUrlException.java
│   └── GlobalExceptionHandler.java
├── mapper/
│   └── SourceMapper.java               ← Entity ↔ DTO conversions
├── repository/
│   ├── SourceRepository.java
│   ├── AuthorRepository.java
│   ├── TagRepository.java
│   └── SourceFormatRepository.java
└── service/
    ├── SourceService.java
    ├── AuthorService.java
    ├── TagService.java
    └── SyncService.java
```

### 2.3 Dependencies

The current `pom.xml` needs the following additions for full functionality:

```xml
<!-- Web -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

        <!-- JPA + Hibernate -->
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

        <!-- Validation -->
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-validation</artifactId>
</dependency>

        <!-- Database migrations -->
<dependency>
<groupId>org.flywaydb</groupId>
<artifactId>flyway-core</artifactId>
</dependency>
<dependency>
<groupId>org.flywaydb</groupId>
<artifactId>flyway-database-postgresql</artifactId>
</dependency>

        <!-- PostgreSQL driver -->
<dependency>
<groupId>org.postgresql</groupId>
<artifactId>postgresql</artifactId>
<scope>runtime</scope>
</dependency>
```

---

## 3. REST API Specification

### 3.1 Sources

#### 3.1.1 Create Source

Creates a new source record. Used by the Agent (filesystem create) and potentially by the Frontend (manual add).

```
POST /api/sources
```

**Request body:**

```json
{
  "path": "/home/user/Biblioteca/Kant/Critica.pdf",
  "name": "Critica.pdf",
  "sourceFormatId": 1,
  "authorName": "Kant",
  "contentHash": "2a846fa617c3361fc117e1c5c1e1838c336b6a5cef982c1a2d9bdf68f2f1992a"
}
```

| Field            | Type   | Required | Description                                                                  |
|------------------|--------|----------|------------------------------------------------------------------------------|
| `path`           | string | yes      | Absolute filesystem path. Must be unique.                                    |
| `name`           | string | yes      | File name without path.                                                      |
| `sourceFormatId` | long   | yes      | ID of the format (1=PDF, 2=EPUB, 3=MHTML).                                   |
| `authorName`     | string | no       | Author name. If provided and no existing author matches, one is created.     |
| `contentHash`    | string | no       | SHA-256 hex digest of file content. Used for rename and safe-save detection. |

**Response `201 Created`:**

```json
{
  "id": 1,
  "path": "/home/user/Biblioteca/Kant/Critica.pdf",
  "name": "Critica.pdf",
  "author": {
    "id": 1,
    "name": "Kant"
  },
  "format": {
    "id": 1,
    "name": "PDF"
  },
  "year": null,
  "edition": null,
  "url": null,
  "createdAt": "2026-07-01T12:00:00Z",
  "updatedAt": "2026-07-01T12:00:00Z"
}
```

**Error responses:**

| Status            | Condition                                                              |
|-------------------|------------------------------------------------------------------------|
| `400 Bad Request` | Missing required fields, invalid `sourceFormatId`                      |
| `409 Conflict`    | A source with the same `path` is already active (`deleted_at IS NULL`) |

---

#### 3.1.2 List / Search Sources

Returns a paginated, filterable list of sources.

```
GET /api/sources
```

**Query parameters:**

| Parameter  | Type   | Default    | Description                                                               |
|------------|--------|------------|---------------------------------------------------------------------------|
| `q`        | string | —          | Search string. Matches against `name`, `author.name`, `tags.name`, `url`. |
| `authorId` | long   | —          | Filter by author ID.                                                      |
| `tagId`    | long   | —          | Filter by tag ID.                                                         |
| `formatId` | long   | —          | Filter by format ID.                                                      |
| `page`     | int    | 0          | Zero-indexed page number.                                                 |
| `size`     | int    | 20         | Page size (max 100).                                                      |
| `sort`     | string | `name,asc` | Sort field and direction (e.g., `name,desc`, `createdAt,asc`).            |

**Response `200 OK`:**

```json
{
  "content": [
    {
      "id": 1,
      "path": "/home/user/Biblioteca/Kant/Critica.pdf",
      "name": "Critica.pdf",
      "author": {
        "id": 1,
        "name": "Kant"
      },
      "format": {
        "id": 1,
        "name": "PDF"
      },
      "tags": [
        {
          "id": 1,
          "name": "filosofia"
        }
      ],
      "year": 1781,
      "edition": null,
      "url": null,
      "createdAt": "2026-07-01T12:00:00Z",
      "updatedAt": "2026-07-01T12:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

---

#### 3.1.3 Get Single Source

```
GET /api/sources/{id}
```

**Path parameter:** `id` — surrogate primary key of the source.

**Query parameters:** `includeDeleted` — if `true`, returns the source even if `deleted_at` is set (default: `false`).

**Response `200 OK`:** Same shape as a single item in the list response (includes `deletedAt` field when set).

**Error responses:**

| Status          | Condition                    |
|-----------------|------------------------------|
| `404 Not Found` | No source with the given id. |

---

#### 3.1.4 Update Source Metadata

Updates editable metadata on an existing source. Only the Frontend uses this endpoint.

```
PATCH /api/sources/{id}
```

**Path parameter:** `id` — surrogate primary key of the source.

**Request body:**

```json
{
  "year": 1781,
  "edition": "2nd",
  "url": "https://example.com/critica",
  "authorName": "Immanuel Kant"
}
```

| Field        | Type    | Required | Description                                                                                             |
|--------------|---------|----------|---------------------------------------------------------------------------------------------------------|
| `year`       | integer | no       | Publication year. Null clears the value.                                                                |
| `edition`    | string  | no       | Edition description. Null clears the value.                                                             |
| `url`        | string  | no       | Original URL. Must be HTTP/HTTPS if provided. Null clears the value.                                    |
| `authorName` | string  | no       | Author name. If null, author association is removed. If changed, the new author is resolved or created. |

Only provided fields are updated. Omitted fields keep their current values.

**Response `200 OK`:** Full source representation after update.

**Error responses:**

| Status            | Condition                              |
|-------------------|----------------------------------------|
| `400 Bad Request` | Invalid URL format, invalid year range |
| `404 Not Found`   | No source with the given id            |

---

#### 3.1.5 Purge Source

Permanently removes a **soft-deleted** source and its tag associations from the database
(`ON DELETE CASCADE`). This operation is irreversible.

Only sources with `deleted_at IS NOT NULL` can be purged. Active sources return a `409 Conflict`.

```
DELETE /api/sources/{id}
```

**Path parameter:** `id` — surrogate primary key of the source.

**Response `204 No Content`** — empty body.

**Error responses:**

| Status          | Condition                                                                             |
|-----------------|---------------------------------------------------------------------------------------|
| `404 Not Found` | No source with the given id                                                           |
| `409 Conflict`  | Source is active (`deleted_at IS NULL`). Use filesystem removal to soft-delete first. |

---

### 3.2 Authors

#### 3.2.1 List Authors

```
GET /api/authors
```

**Response `200 OK`:**

```json
[
  {
    "id": 1,
    "name": "Kant"
  },
  {
    "id": 2,
    "name": "Hegel"
  }
]
```

**Query parameters:**

| Parameter | Type   | Default | Description                                       |
|-----------|--------|---------|---------------------------------------------------|
| `q`       | string | —       | Search by name (partial match, case-insensitive). |

---

### 3.3 Tags

#### 3.3.1 List Tags

```
GET /api/tags
```

**Response `200 OK`:**

```json
[
  {
    "id": 1,
    "name": "filosofia"
  },
  {
    "id": 2,
    "name": "aleman"
  }
]
```

**Query parameters:**

| Parameter | Type   | Default | Description                                       |
|-----------|--------|---------|---------------------------------------------------|
| `q`       | string | —       | Search by name (partial match, case-insensitive). |

---

### 3.4 Formats

#### 3.4.1 List Formats

```
GET /api/formats
```

**Response `200 OK`:**

```json
[
  {
    "id": 1,
    "name": "PDF"
  },
  {
    "id": 2,
    "name": "EPUB"
  },
  {
    "id": 3,
    "name": "MHTML"
  }
]
```

This endpoint is read-only. Formats are seeded by Flyway and never modified at runtime.

---

### 3.5 Sync / Reconciliation

#### 3.5.1 Full Sync (Agent → API)

Used by the Agent to upload the complete current state of the filesystem. The API performs set reconciliation.

```
POST /api/sync
```

**Request body:**

```json
[
  {
    "path": "/home/user/Biblioteca/Kant/Critica.pdf",
    "name": "Critica.pdf",
    "format": "PDF",
    "authorName": "Kant",
    "contentHash": "2a846fa617c3361fc117e1c5c1e1838c336b6a5cef982c1a2d9bdf68f2f1992a"
  }
]
```

| Field         | Type   | Required | Description                                                    |
|---------------|--------|----------|----------------------------------------------------------------|
| `path`        | string | yes      | Absolute filesystem path.                                      |
| `name`        | string | yes      | File name.                                                     |
| `format`      | string | yes      | Format name: `PDF`, `EPUB`, or `MHTML`.                        |
| `authorName`  | string | no       | Inferred author from directory structure.                      |
| `contentHash` | string | no       | SHA-256 hex digest of file content. Used for rename detection. |

**Response `200 OK`:**

```json
{
  "created": 5,
  "deleted": 2,
  "skipped": 10
}
```

| Field     | Type | Description                              |
|-----------|------|------------------------------------------|
| `created` | int  | Sources inserted (new files).            |
| `deleted` | int  | Sources removed (files no longer in FS). |
| `skipped` | int  | Sources already in sync.                 |

**Algorithm:**

```
for each file in request:
  if path exists in DB:
    skip
  else:
    insert source record

for each path in DB not in request:
  soft delete source record (SET deleted_at = now())
  // source_tags are preserved — soft delete is an UPDATE, not a DELETE
  // ON DELETE CASCADE is NOT triggered
```

#### 3.5.2 Trigger Reconciliation (Frontend → API → Agent)

Used by the Frontend to request a full scan from the Agent.

```
POST /api/reconcile
```

**Request body:** empty.

**Response `202 Accepted`:**

```json
{
  "message": "Reconciliation triggered."
}
```

The API notifies the Agent via an internal HTTP call (`POST /agent/reconcile`). Reconciliation runs asynchronously; the
result is not polled — the user can re-trigger or check the source list after the scan completes.

---

## 4. Service Layer

### 4.1 SourceService

```
class SourceService {
    SourceResponse create(CreateSourceRequest request);
    Page<SourceResponse> search(String q, Long authorId, Long tagId, Long formatId, Pageable pageable);
    SourceResponse get(Long id);
    SourceResponse update(Long id, UpdateSourceRequest request);
    void purge(Long id);                       // physical delete — only if soft-deleted
    void markDeleted(String path);             // called by sync reconciliation
    void reactivate(Long id);                  // called when file reappears
}
```

- `create`: Handles four cases based on `contentHash` if present:
    1. **Reactivation**: If a source with the same `path` AND same `content_hash` has `deleted_at` set → clear
       `deleted_at`, update `content_hash`. Metadata preserved from the original record.
    2. **Safe-save**: If a source with the same `path` and `deleted_at IS NULL` exists → update `content_hash`,
       preserve all metadata.
    3. **Rename**: If a source with the same `content_hash` exists at a different path → transfer metadata
       (tags, URL, year, edition) to the new record, clear metadata on the old record, soft-delete the old
       record (`SET deleted_at = now()`).
    4. **Normal insert**: Otherwise → insert new source with no additional metadata.
       Creates or resolves `author` by name. Sets `created_at` and `updated_at` to current timestamp.
- `purge`: Throws `SourceNotFoundException` if id does not exist. Throws `ConflictException` if
  `deleted_at IS NULL`. Performs physical delete (JPA `delete`). Tag associations are cascade-deleted.
- `markDeleted`: Sets `deleted_at = now()` on the source with the given path. Idempotent — if already
  deleted, does nothing. Used by `SyncService` during reconciliation.
- `reactivate`: Sets `deleted_at = NULL` on a soft-deleted source. Used when a file reappears at the same
  path with matching content_hash.
- `update`: Only modifies provided fields. Re-resolves author if `authorName` is provided. Cannot update
  soft-deleted sources (returns 404).
- `search`: Delegates to `SourceRepository` with Spring Data JPA `Specification` or `@Query`.

### 4.2 AuthorService

```
class AuthorService {
    List<AuthorResponse> findAll(String q);
    Author getOrCreate(String name);
}
```

- `getOrCreate`: Looks up by name (case-insensitive). Creates if not found. Used internally by `SourceService.create()`.

### 4.3 TagService

```
class TagService {
    List<TagResponse> findAll(String q);
    Tag getOrCreate(String name);
}
```

### 4.4 SyncService

```
class SyncService {
    SyncResult sync(List<SyncFile> files);
    void triggerReconciliation();
}
```

- `sync`: Implements the set reconciliation algorithm from section 3.5.1. All operations execute within a single
  transaction.
- `triggerReconciliation`: Sends HTTP request to the Agent's reconciliation endpoint.

---

## 5. Data Access

### 5.1 Repositories

All repositories extend `JpaRepository`:

```
interface SourceRepository extends JpaRepository<Source, Long> {
    // Primary key is id (Long). Active records filtered via @Where on entity.
    Optional<Source> findByPath(String path);
    Optional<Source> findByPathAndDeletedAtIsNull(String path);
    List<Source> findByPathIn(List<String> paths);
}

interface AuthorRepository extends JpaRepository<Author, Long> {
    Optional<Author> findByNameIgnoreCase(String name);
}

interface TagRepository extends JpaRepository<Tag, Long> {
    Optional<Tag> findByNameIgnoreCase(String name);
}

interface SourceFormatRepository extends JpaRepository<SourceFormat, Long> {
    Optional<SourceFormat> findByNameIgnoreCase(String name);
}
```

**Source search query pattern:**

For the search/filter endpoint, use Spring Data JPA `Specification` or a custom `@Query` with dynamic `WHERE` clauses:

```sql
SELECT s
FROM Source s
         LEFT JOIN FETCH s.author
LEFT JOIN FETCH s.sourceFormat
WHERE (:q IS NULL
   OR LOWER (s.name) LIKE LOWER (CONCAT('%'
    , :q
    , '%'))
   OR LOWER (s.author.name) LIKE LOWER (CONCAT('%'
    , :q
    , '%'))
   OR LOWER (s.url) LIKE LOWER (CONCAT('%'
    , :q
    , '%')))
  AND (:authorId IS NULL
   OR s.author.id = :authorId)
  AND (:formatId IS NULL
   OR s.sourceFormat.id = :formatId)
```

Tag filtering requires a subquery or join on `source_tags`:

```sql
AND (:tagId IS NULL OR EXISTS (
    SELECT 1 FROM SourceTag st
    WHERE st.source.id = s.id AND st.tag.id = :tagId
))
```

### 5.2 Flyway Migrations

**Naming convention:**

```
V<timestamp>__<description>.sql
```

Example:

```
V202607010001__create_authors_table.sql
V202607010002__create_source_formats_table.sql
V202607010003__create_sources_table.sql
V202607010004__create_tags_table.sql
V202607010005__create_source_tags_table.sql
V202607010006__seed_source_formats.sql
V202607010007__add_soft_delete_and_surrogate_id.sql
```

**Strategy:**

- All migrations are versioned (not repeatable).
- Seed data (source_formats) is a versioned migration with `INSERT` statements.
- Schema changes follow additive-only pattern: new columns are nullable or have defaults. No destructive `ALTER` in V1.

**Ordering rationale:**

- `authors` and `source_formats` must be created before `sources` because `sources` has foreign keys to both.
- `source_tags` requires both `sources` and `tags` to exist.
- The surrogate ID and soft-delete migration (V7) adds the `id BIGSERIAL` column, `deleted_at`, and the partial unique
  index. This avoids circular dependencies in the initial schema.

**V1 —`V202607010001__create_authors_table.sql`:**

```sql
CREATE TABLE authors
(
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);
```

**V2 —`V202607010002__create_source_formats_table.sql`:**

```sql
CREATE TABLE source_formats
(
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
);
```

**V3 —`V202607010003__create_sources_table.sql`:**

```sql
CREATE TABLE sources
(
    path             VARCHAR(1024) NOT NULL,
    name             VARCHAR(512)  NOT NULL,
    author_id        BIGINT REFERENCES authors (id),
    source_format_id BIGINT        NOT NULL REFERENCES source_formats (id),
    year             INTEGER,
    edition          VARCHAR(255),
    url              VARCHAR(2048),
    content_hash     VARCHAR(64),
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- ID and deleted_at are added in V7 via ALTER to avoid
-- circular dependency with the partial unique index.
```

**V4 —`V202607010004__create_tags_table.sql`:**

```sql
CREATE TABLE tags
(
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);
```

**V5 —`V202607010005__create_source_tags_table.sql`:**

```sql
CREATE TABLE source_tags
(
    source_id BIGINT NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    tag_id    BIGINT NOT NULL REFERENCES tags (id),
    PRIMARY KEY (source_id, tag_id)
);
```

**V6 —`V202607010006__seed_source_formats.sql`:**

```sql
INSERT INTO source_formats (name)
VALUES ('PDF'),
       ('EPUB'),
       ('MHTML');
```

**V7 —`V202607010007__add_soft_delete_and_surrogate_id.sql`:**

See ADR 0002 for the full rationale. This migration:

1. Adds an `id BIGSERIAL` column and sets it as the primary key.
2. Adds `deleted_at TIMESTAMP` (nullable).
3. Drops the old `PRIMARY KEY (path)` constraint.
4. Creates a partial unique index:
   `CREATE UNIQUE INDEX uq_sources_active_path ON sources (path) WHERE deleted_at IS NULL;`.

---

## 6. Error Handling

### 6.1 Exception Hierarchy

```
RuntimeException
├── SourceNotFoundException      (404)
├── AuthorNotFoundException      (404)
├── TagNotFoundException         (404)
├── DuplicateSourceException     (409)
└── InvalidUrlException          (400)
```

All exceptions extend `RuntimeException` (unchecked). Each carries an HTTP status code mapping.

### 6.2 GlobalExceptionHandler

A single `@RestControllerAdvice` class handles all exceptions:

```
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(SourceNotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(RuntimeException ex);
    // → 404

    @ExceptionHandler(DuplicateSourceException.class)
    ResponseEntity<ErrorResponse> handleConflict(RuntimeException ex);
    // → 409

    @ExceptionHandler(InvalidUrlException.class)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex);
    // → 400

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleGeneral(Exception ex);
    // → 500 (generic fallback)
}
```

### 6.3 Error Response Format

All error responses follow a consistent shape:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Source not found: /path/to/file.pdf",
  "timestamp": "2026-07-01T12:00:00Z"
}
```

| Field       | Type   | Description                |
|-------------|--------|----------------------------|
| `status`    | int    | HTTP status code           |
| `error`     | string | HTTP status phrase         |
| `message`   | string | Human-readable description |
| `timestamp` | string | ISO 8601 UTC timestamp     |

---

## 7. Validation

### 7.1 Bean Validation Annotations

Applied at the controller boundary via `@Valid` on request bodies.

**CreateSourceRequest:**

| Field            | Annotation                     | Rule                                                           |
|------------------|--------------------------------|----------------------------------------------------------------|
| `path`           | `@NotBlank`, `@Size(max=1024)` | Required, max 1024 chars                                       |
| `name`           | `@NotBlank`, `@Size(max=512)`  | Required, max 512 chars                                        |
| `sourceFormatId` | `@NotNull`                     | Must be a valid format ID                                      |
| `authorName`     | `@Size(max=255)`               | Optional, max 255 chars                                        |
| `contentHash`    | `@Size(min=64, max=64)`        | Optional. If provided, must be a 64-char hex string (SHA-256). |

**UpdateSourceRequest:**

| Field        | Annotation              | Rule                                                   |
|--------------|-------------------------|--------------------------------------------------------|
| `year`       | `@Min(1)`, `@Max(2099)` | Optional. If provided, must be between 1 and 2099.     |
| `edition`    | `@Size(max=255)`        | Optional, max 255 chars                                |
| `url`        | `@Size(max=2048)`       | Optional. Validated as HTTP/HTTPS by custom validator. |
| `authorName` | `@Size(max=255)`        | Optional, max 255 chars                                |

### 7.2 Custom Validators

**UrlValidator** — validates that if a URL is provided, it must start with `http://` or `https://`:

```java

@Target(FIELD)
@Retention(RUNTIME)
@Constraint(validatedBy = UrlValidatorImpl.class)
@interface ValidUrl {
    String message() default "URL must start with http:// or https://";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
```

This validator is applied to the `url` field in `UpdateSourceRequest`.

---

## 8. Configuration

### 8.1 application.yml Structure

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/biblocat
    username: ${DB_USER:biblocat}
    password: ${DB_PASSWORD:biblocat}
  jpa:
    hibernate:
      ddl-auto: validate    # Flyway manages schema
    open-in-view: false     # Disable OSIV for performance
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8080

biblocat:
  agent:
    url: http://localhost:9090   # Agent HTTP endpoint
```

### 8.2 Profiles

| Profile         | Purpose           | Key differences                                      |
|-----------------|-------------------|------------------------------------------------------|
| `default` (dev) | Local development | H2 or local PostgreSQL, verbose logging              |
| `prod`          | Production        | PostgreSQL only, minimal logging, Flyway strict mode |

---

## 9. Testing Strategy

### 9.1 Unit Tests (Service Layer)

- Test each service method in isolation.
- Mock repositories with Mockito.
- Cover: create (success, duplicate path), delete (success, not found), search (with and without filters).

### 9.2 Integration Tests (Controller Layer)

- Use `@WebMvcTest` for controller slice tests.
- Mock service layer.
- Cover: HTTP status codes, request validation errors, response body shape.

### 9.3 Repository Slice Tests

- Use `@DataJpaTest` with an embedded database (H2 in test scope).
- Cover: custom queries, cascading deletes, pagination.
- Flyway migrations run automatically during `@DataJpaTest`.

---

## 10. API-specific Design Decisions

### 10.1 Surrogate ID over Natural Key

|               | Decision                                                                                                                                                                   |
|---------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**    | `id` (BIGSERIAL) is the JPA `@Id` in the `Source` entity. `path` has a `UNIQUE WHERE deleted_at IS NULL` constraint.                                                       |
| **Rationale** | Soft-deleted records can coexist with new records at the same path. Surrogate ID avoids URL encoding issues, simplifies FKs, and enables stable references across renames. |
| **Trade-off** | Extra join for path-based lookups (minimal). REST paths are numeric instead of human-readable.                                                                             |
| **Reference** | `docs/decisions/0002-soft-delete-and-surrogate-id.md`                                                                                                                      |

### 10.2 PATCH for Partial Updates

|                    | Decision                                                                                                                                                                |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**         | `PATCH /api/sources/{id}` for metadata updates.                                                                                                                         |
| **Rationale**      | Clients should not send full representations for partial changes. PATCH semantics allow sparse JSON — omitted fields are not modified.                                  |
| **Implementation** | `UpdateSourceRequest` uses `Optional<...>` wrapper types or null-checking: a null field means "do not update", an explicit `null` in the JSON means "clear this field". |

### 10.3 Set Reconciliation over Diff-Based Sync

|               | Decision                                                                                                 |
|---------------|----------------------------------------------------------------------------------------------------------|
| **Chosen**    | The Agent sends the complete file list; the API reconciles via set comparison.                           |
| **Rationale** | Simpler than tracking diffs. No risk of missed events. Works even if the Agent was offline.              |
| **Trade-off** | Higher payload size for large libraries. Acceptable for personal use (thousands of files, not millions). |

### 10.4 One Transaction per Sync Request

|               | Decision                                                                                                                                     |
|---------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**    | The entire reconciliation runs in a single `@Transactional` block.                                                                           |
| **Rationale** | Atomicity: either all changes apply or none. Prevents partial sync state if the process fails mid-way.                                       |
| **Risk**      | Long-running transactions on large libraries. Mitigated by the personal-use scope. If needed, can be split into batches in a future version. |

### 10.5 DB-Based Rename and Safe-Save Detection (no in-memory buffer)

|               | Decision                                                                                                                                                                                                                                                                                     |
|---------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**    | No in-memory buffer. On `POST /api/sources`, the API queries the `sources` table by `content_hash`: same **path** → safe-save (update hash, metadata preserved); same **hash** different path → rename (transfer metadata, clear old record, soft-delete). Deletions detected only via sync. |
| **Rationale** | The previous buffer approach (ADR 0001) introduced race conditions, restart vulnerability, and concurrency complexity. Using the database itself as the look-up structure eliminates the buffer entirely: it is persistent, concurrency-safe, and has no TTL.                                |
| **Trade-off** | Adds one `SELECT` per CREATE (indexed on `content_hash`). Deletions have up to 5-minute latency (sync scan only). Soft delete preserves metadata across deletions (see ADR 0002).                                                                                                            |
| **Reference** | `docs/decisions/0001-content-hash-rename-detection.md`                                                                                                                                                                                                                                       |

### 10.6 Soft Delete with Surrogate ID

|               | Decision                                                                                                                                                                                                        |
|---------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**    | Soft delete with surrogate `id` PK. `deleted_at TIMESTAMP` is set when a file disappears from the filesystem. Reactivation requires matching `path` + `content_hash`. Purge only works on soft-deleted records. |
| **Rationale** | The system serves as an inventory "insurance policy" — metadata is preserved even if files are lost. Surrogate ID avoids conflicts between active and soft-deleted records sharing the same path.               |
| **Trade-off** | All queries need `WHERE deleted_at IS NULL` (mitigated by Hibernate `@Where`). Soft-deleted records accumulate (trivial for personal use). REST API uses `{id}` instead of `{path}` for endpoint stability.     |
| **Reference** | `docs/decisions/0002-soft-delete-and-surrogate-id.md`                                                                                                                                                           |
