# API Module — Software Design Description

## Document Control

| Property        | Value                                         |
|-----------------|-----------------------------------------------|
| **Version**     | 1.0                                           |
| **Status**      | Draft — initial definition for implementation |
| **Last update** | 2026-07-01                                    |
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
  "authorName": "Kant"
}
```

| Field            | Type   | Required | Description                                                              |
|------------------|--------|----------|--------------------------------------------------------------------------|
| `path`           | string | yes      | Absolute filesystem path. Must be unique.                                |
| `name`           | string | yes      | File name without path.                                                  |
| `sourceFormatId` | long   | yes      | ID of the format (1=PDF, 2=EPUB, 3=MHTML).                               |
| `authorName`     | string | no       | Author name. If provided and no existing author matches, one is created. |

**Response `201 Created`:**

```json
{
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

| Status            | Condition                                         |
|-------------------|---------------------------------------------------|
| `400 Bad Request` | Missing required fields, invalid `sourceFormatId` |
| `409 Conflict`    | Source with the same `path` already exists        |

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
GET /api/sources/{path}
```

**Path parameter:** `path` — URL-encoded absolute filesystem path.

**Response `200 OK`:** Same shape as a single item in the list response.

**Error responses:**

| Status          | Condition                      |
|-----------------|--------------------------------|
| `404 Not Found` | No source with the given path. |

---

#### 3.1.4 Update Source Metadata

Updates editable metadata on an existing source. Only the Frontend uses this endpoint.

```
PATCH /api/sources/{path}
```

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
| `404 Not Found`   | No source with the given path          |

---

#### 3.1.5 Delete Source

Physically deletes a source and its tag associations (cascade). Only the Agent uses this endpoint.

```
DELETE /api/sources/{path}
```

**Response `204 No Content`** — empty body.

**Error responses:**

| Status          | Condition                     |
|-----------------|-------------------------------|
| `404 Not Found` | No source with the given path |

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
    "authorName": "Kant"
  }
]
```

| Field        | Type   | Required | Description                               |
|--------------|--------|----------|-------------------------------------------|
| `path`       | string | yes      | Absolute filesystem path.                 |
| `name`       | string | yes      | File name.                                |
| `format`     | string | yes      | Format name: `PDF`, `EPUB`, or `MHTML`.   |
| `authorName` | string | no       | Inferred author from directory structure. |

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
  delete source record (cascades to source_tags)
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
  "message": "Reconciliation triggered.",
  "statusUrl": "/api/reconcile/status/{id}"
}
```

The API notifies the Agent via an internal HTTP call. The reconciliation result can be polled at the status URL.

---

## 4. Service Layer

### 4.1 SourceService

```
class SourceService {
    SourceResponse create(CreateSourceRequest request);
    Page<SourceResponse> search(String q, Long authorId, Long tagId, Long formatId, Pageable pageable);
    SourceResponse get(String path);
    SourceResponse update(String path, UpdateSourceRequest request);
    void delete(String path);
}
```

- `create`: Validates uniqueness of `path`. Creates or resolves `author` by name. Sets `created_at` and `updated_at` to
  current timestamp.
- `delete`: Throws `SourceNotFoundException` if path does not exist. Performs physical delete (JPA `delete`). Tag
  associations are cascade-deleted.
- `update`: Only modifies provided fields. Re-resolves author if `authorName` is provided.
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
interface SourceRepository extends JpaRepository<Source, String> {
    // Primary key is path (String)
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
    WHERE st.source.path = s.path AND st.tag.id = :tagId
))
```

### 5.2 Flyway Migrations

**Naming convention:**

```
V<timestamp>__<description>.sql
```

Example:

```
V202607010001__create_sources_table.sql
V202607010002__create_authors_table.sql
V202607010003__create_tags_table.sql
V202607010004__create_source_formats_table.sql
V202607010005__create_source_tags_table.sql
V202607010006__seed_source_formats.sql
```

**Strategy:**

- All migrations are versioned (not repeatable).
- Seed data (source_formats) is a versioned migration with `INSERT` statements.
- Schema changes follow additive-only pattern: new columns are nullable or have defaults. No destructive `ALTER` in V1.

**Initial schema (`V202607010001__create_sources_table.sql`):**

```sql
CREATE TABLE sources
(
    path             VARCHAR(1024) PRIMARY KEY,
    name             VARCHAR(512) NOT NULL,
    author_id        BIGINT REFERENCES authors (id),
    source_format_id BIGINT       NOT NULL REFERENCES source_formats (id),
    year             INTEGER,
    edition          VARCHAR(255),
    url              VARCHAR(2048),
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

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

| Field            | Annotation                     | Rule                      |
|------------------|--------------------------------|---------------------------|
| `path`           | `@NotBlank`, `@Size(max=1024)` | Required, max 1024 chars  |
| `name`           | `@NotBlank`, `@Size(max=512)`  | Required, max 512 chars   |
| `sourceFormatId` | `@NotNull`                     | Must be a valid format ID |
| `authorName`     | `@Size(max=255)`               | Optional, max 255 chars   |

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

### 10.1 Path as Primary Key

|               | Decision                                                                                                                          |
|---------------|-----------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**    | `path` (VARCHAR) is the JPA `@Id` in the `Source` entity.                                                                         |
| **Rationale** | The filesystem path is the natural identifier. No surrogate key needed. Simpler code, fewer joins. The Agent always has the path. |
| **Trade-off** | Rename/move requires a DELETE + CREATE (path changes). URLs in `@PathVariable` must be encoded.                                   |

### 10.2 PATCH for Partial Updates

|                    | Decision                                                                                                                                                                |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**         | `PATCH /api/sources/{path}` for metadata updates.                                                                                                                       |
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
