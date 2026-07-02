# Agent Module — Software Design Description

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

This document describes the detailed design of the Agent module. It covers startup lifecycle, filesystem monitoring with
WatchService, file event handling, author inference from directory structure, full scan procedure, HTTP communication
with the API, and reconciliation trigger handling.

It is the implementation guide for the `agent/` module. It assumes familiarity with the system-level design documented
in `docs/SDD.md`.

### 1.2 Scope

This document covers:

- Module architecture and package structure
- Startup and shutdown lifecycle
- WatchService setup and event loop
- File event processing (create, delete)
- File filtering (supported extensions)
- Author inference from directory hierarchy
- Full scan implementation (recursive walk)
- HTTP client design and API communication
- Reconciliation trigger mechanism
- Configuration and profiles
- Testing approach

It does **not** cover:

- System architecture principles → see `docs/SDD.md`
- REST API contracts → see `docs/api/SDD.md`
- Frontend behaviour → see `docs/front/SDD.md`

---

## 2. Module Architecture

### 2.1 Component Diagram

```
┌─────────────────────────────────────────────────────┐
│                   Agent                              │
│  ┌──────────────┐  ┌────────────┐  ┌─────────────┐  │
│  │  WatchService │  │ Directory  │  │   HttpClient │  │
│  │    Monitor    │──│   Walker   │──│ (Java 21     │  │
│  │  (event loop) │  │ (full scan)│  │  HttpClent)  │  │
│  └──────┬───────┘  └────────────┘  └──────┬──────┘  │
│         │                                  │         │
│         ▼                                  ▼         │
│  ┌─────────────────────────────────────────────┐     │
│  │            Event Dispatcher                  │     │
│  │  (routes events → API calls)                │     │
│  └─────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────┘
                           │
                           ▼ HTTP
                    ┌──────────────┐
                    │     API      │
                    └──────────────┘
```

### 2.2 Package Structure

```
com.biblocat
├── App.java                          ← Main entry point
├── config/
│   └── AgentConfig.java              ← Configuration loader
├── monitor/
│   ├── DirectoryMonitor.java         ← WatchService setup + event loop
│   └── FileEventHandler.java         ← Per-event processing logic
├── scan/
│   ├── DirectoryWalker.java          ← Recursive filesystem walk
│   └── FileCollector.java            ← Collects supported files with metadata
├── parser/
│   └── AuthorParser.java             ← Infers author from directory path
├── client/
│   └── ApiClient.java                ← HTTP client (Java 21 HttpClient)
├── model/
│   ├── SourceFile.java               ← Internal representation of a discovered file
│   └── SyncResult.java               ← Result of a sync request
└── sync/
    └── SyncService.java              ← Orchestrates full scan + sync
```

### 2.3 Dependencies

The current `pom.xml` needs a complete rewrite. The Agent is a standalone Java 21 application with minimal external
dependencies:

```xml

<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.biblocat</groupId>
    <artifactId>agent</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <name>Agent - BiblioCat filesystem monitor</name>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- JSON processing -->
        <dependency>
            <groupId>com.google.code.gson</groupId>
            <artifactId>gson</artifactId>
            <version>2.11.0</version>
        </dependency>

        <!-- Logging -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-simple</artifactId>
            <version>2.0.16</version>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.11.4</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>5.14.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- JAR with dependencies (fat JAR) -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-assembly-plugin</artifactId>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>com.biblocat.App</mainClass>
                        </manifest>
                    </archive>
                    <descriptorRefs>
                        <descriptorRef>jar-with-dependencies</descriptorRef>
                    </descriptorRefs>
                </configuration>
                <executions>
                    <execution>
                        <id>make-assembly</id>
                        <phase>package</phase>
                        <goals>
                            <goal>single</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

No Spring Boot is used. The Agent is a lightweight Java daemon with explicit dependency on Gson for JSON serialization
and SLF4J for logging.

---

## 3. Startup & Lifecycle

### 3.1 Startup Sequence

```
App.main()
  │
  ├─ 1. Load AgentConfig from CLI args / config file
  │     - library path (required)
  │     - API base URL (required)
  │     - scan interval (optional, default: 30 min)
  │
  ├─ 2. Validate config
  │     - library directory exists and is readable
  │     - API URL is reachable (health check)
  │
  ├─ 3. Run initial full scan
  │     - DirectoryWalker walks the library
  │     - SyncService sends complete file list to API (POST /api/sync)
  │
  ├─ 4. Start WatchService event loop
  │     - Register library directory (and subdirectories) with WatchService
  │     - Begin blocking event loop in a dedicated thread
  │
  └─ 5. Register shutdown hook
        - Close WatchService
        - Flush pending events
```

### 3.2 Shutdown Handling

A `Runtime.getRuntime().addShutdownHook(...)` ensures clean shutdown:

1. Stop the WatchService event loop (interrupt the monitor thread).
2. Close the WatchService (release native resources).
3. Log shutdown completion.

The Agent does **not** perform a final sync on shutdown. Unprocessed events are handled on the next startup by the
initial full scan.

### 3.3 WatchService Event Loop

The event loop runs in a dedicated daemon thread:

```
while (not interrupted) {
    WatchKey key = watchService.poll(timeout, SECONDS);
    if (key == null) {
        continue;  // timeout reached, check interruption flag
    }

    for (WatchEvent<?> event : key.pollEvents()) {
        if (event.kind() == OVERFLOW) {
            continue;  // skip overflow events
        }

        Path filePath = (Path) event.context();
        Path fullPath = (Path) key.watchable().resolve(filePath);

        handleEvent(event.kind(), fullPath);
    }

    if (!key.reset()) {
        break;  // directory is no longer accessible
    }
}
```

Key details:

- **Polling timeout**: 5 seconds (allows interruption check).
- **Thread model**: Single event loop thread. Processing is fast (HTTP calls are blocking but acceptable for single-user
  scope).
- **Overflow handling**: `OVERFLOW` events are ignored. The full scan at startup covers missed events.

---

## 4. Filesystem Monitoring

### 4.1 WatchService Setup

The Agent registers the library root directory and all its subdirectories with `WatchService`:

```java
WatchService watchService = FileSystems.getDefault().newWatchService();

// Register root and all subdirectories
Files.

walkFileTree(libraryPath, new SimpleFileVisitor<>() {
    @Override
    public FileVisitResult preVisitDirectory (Path dir, BasicFileAttributes attrs){
        dir.register(watchService,
                ENTRY_CREATE,
                ENTRY_DELETE,
                ENTRY_MODIFY);
        return FileVisitResult.CONTINUE;
    }
});
```

**Why recursive registration?** WatchService does not monitor subdirectories automatically. Each directory must be
registered individually. The initial registration walks the entire tree.

**Dynamic registration**: New directories created inside the library are detected via `ENTRY_CREATE` and registered on
the fly:

```
ENTRY_CREATE for a directory → register it with WatchService
ENTRY_CREATE for a file     → process it as a new source
```

### 4.2 Event Handling

#### ENTRY_CREATE

| Event target                    | Action                                                                                                  |
|---------------------------------|---------------------------------------------------------------------------------------------------------|
| File with supported extension   | Parse author from parent directory → compute SHA-256 hash → send `POST /api/sources` with `contentHash` |
| File with unsupported extension | Ignore                                                                                                  |
| Directory                       | Register with WatchService for future events                                                            |

The SHA-256 hash is computed immediately on detection (see §4.5). This hash enables the API to
detect renames by looking up the `sources` table in the database: if another source with the
same hash exists at a different path, the API transfers metadata from the old record to the
new one. No in-memory buffer is used (see
`docs/decisions/0001-content-hash-rename-detection.md`).

#### ENTRY_DELETE

| Event target | Action                                                                                                |
|--------------|-------------------------------------------------------------------------------------------------------|
| File         | **Ignored**. Deletions are detected only via the periodic reconciliation scan (see §4.6).             |
| Directory    | Remove from WatchService (no explicit action needed as all contained files are deleted individually). |

#### ENTRY_MODIFY

| Event target | Action                                                                                                                        |
|--------------|-------------------------------------------------------------------------------------------------------------------------------|
| File         | **Ignored directly**. Content changes are detected indirectly via safe-save (CREATE on the same path). See §4.4 and ADR 0001. |
| Directory    | Ignored (timestamp changes on the directory itself are irrelevant).                                                           |

While `ENTRY_MODIFY` is ignored, file modifications that use the safe-save pattern (write temp,
delete original, rename temp) generate a `ENTRY_CREATE` on the same path. The API checks the
database by path and handles it as a safe-save: metadata is preserved because the record was
never deleted (DELETE is ignored). No in-memory buffer is involved (see
`docs/decisions/0001-content-hash-rename-detection.md`).

### 4.3 File Filtering

Only files with the following extensions are processed:

| Extension | Format ID | Notes                    |
|-----------|-----------|--------------------------|
| `.pdf`    | 1         | Portable Document Format |
| `.epub`   | 2         | Electronic Publication   |
| `.mhtml`  | 3         | MIME HTML Archive        |

The comparison is **case-insensitive** (`.PDF`, `.Epub`, `.MHTML` are also matched).

All other files are silently ignored. Hidden files (names starting with `.`) are also ignored.

### 4.4 Debouncing

On some platforms (notably Windows), a single file operation can fire multiple WatchService events.
The Agent deduplicates events within a short window to avoid sending duplicate requests.

```
Within FileEventHandler:
1. Collect events into a map keyed by path
2. For each path, keep only the last event within a 500ms window
3. Process deduplicated events
```

Debounce window: **500ms** (only for deduplication — no need to pair DELETE+CREATE events, as
ENTRY_DELETE is ignored entirely).

Additionally, `ENTRY_CREATE` on a file that already exists in the database is handled by the API
as a safe-save (update `content_hash`, preserve metadata). The Agent does not need special
logic for this case.

### 4.5 Hash Computation

A SHA-256 hash is computed for every supported file on `ENTRY_CREATE`:

```java
// Within FileEventHandler:
MessageDigest digest = MessageDigest.getInstance("SHA-256");
try(
InputStream is = Files.newInputStream(filePath)){
byte[] buffer = new byte[8192];
int bytesRead;
    while((bytesRead =is.

read(buffer))!=-1){
        digest.

update(buffer, 0,bytesRead);
    }
            }
String contentHash = HexFormat.of().formatHex(digest.digest());
```

**When NOT to hash:**

- Directories (not applicable)
- Files with unsupported extensions (ignored)
- Files below a configurable size threshold (optional optimization)

The hash is sent as `contentHash` in `POST /api/sources` and `POST /api/sync` requests. Hash is
also computed during the periodic reconciliation scan (§4.6) for new or changed files (detected
via file size or last-modified-time changes). It is never computed on `ENTRY_DELETE`.

See `docs/decisions/0001-content-hash-rename-detection.md` for the full rationale and design.

### 4.6 Periodic Reconciliation Scan

The Agent runs a scheduled scan of the entire library directory to detect changes missed by
WatchService (e.g., Agent restart, transient errors) and to handle deletions (which are never
reported in real time).

**Trigger conditions:**

- On Agent startup (initial scan).
- Every **30 minutes** thereafter (configurable interval, default `scanIntervalMinutes = 30`).
- On demand via `POST /api/reconcile` request from the Frontend (the API then triggers the Agent's internal
  `POST /agent/reconcile`).

**Procedure:**

1. Walk the entire library directory recursively.
2. For each supported file:
    - If the file is **new** (not in the previous scan's result set) → compute SHA-256 hash.
    - If the file's size or last-modified time differs from the previous scan → compute SHA-256 hash.
    - Otherwise → skip hashing (use cached hash from the last scan or database).
3. Collect all file entries into a list: `[{path, name, format, author, contentHash}, ...]`.
4. Send `POST /api/sync` with the complete list to the API.
5. Process the API response (summary of inserted, deleted, and renamed records).

**Optimizations:**

- Only new or changed files are hashed (mitigates CPU/I/O cost).
- File size and mtime are compared against the last scan's snapshot to detect changes.
- The scan result is cached in memory for the next scan's change detection.

**Interaction with WatchService:**

- Files already created via `POST /api/sources` (from WatchService) appear in the scan list as
  existing records. The API skips them during reconciliation.
- Files deleted from the filesystem since the last scan are absent from the scan list. The API
  soft-deletes their database records (sets `deleted_at`). Metadata is preserved for
  potential reactivation (see ADR 0002).
- Renames missed by WatchService (e.g., Agent was down) are detected via hash matching: the API
  compares hashes of "missing" records against "new" records and transfers metadata.

---

## 5. Author Inference

### 5.1 Directory Structure Parsing

The Agent infers the author from the file's parent directory:

```
Biblioteca/Kant/Critica.pdf
  → parent directory: "Kant" → authorName = "Kant"

Biblioteca/documento.pdf
  → parent directory is the library root → authorName = null
```

```java
class AuthorParser {
    /**
     * Infers the author name from the file path.
     *
     * @param filePath    absolute path to the file
     * @param libraryPath absolute path to the library root
     * @return author name if file is one level deep from root, null otherwise
     */
    static String infer(Path filePath, Path libraryPath) {
        Path relativePath = libraryPath.relativize(filePath);
        if (relativePath.getNameCount() >= 2) {
            // File is inside a subdirectory → first component is the author
            return relativePath.getName(0).toString();
        }
        // File is at the root level → no author
        return null;
    }
}
```

### 5.2 Root-Level Files

Files placed directly in the library root directory are sent with `authorName = null`. The API stores them without an
author association.

### 5.3 Empty Directories

If a directory exists at the author level but contains no supported files, the Agent does **not** send any creation
event for the directory itself. Author records for empty directories are created during the full scan (see section 5.4 —
Full Scan & Reconciliation — of the system SDD).

---

## 6. Full Scan

### 6.1 Recursive Directory Walk

The `DirectoryWalker` traverses the entire library directory tree using `Files.walk()`:

```java
class DirectoryWalker {
    List<SourceFile> walk(Path libraryPath) {
        List<SourceFile> files = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(libraryPath)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedFormat)
                    .forEach(path -> {
                        SourceFile file = new SourceFile();
                        file.path = path.toString();
                        file.name = path.getFileName().toString();
                        file.format = detectFormat(path);
                        file.authorName = AuthorParser.infer(path, libraryPath);
                        files.add(file);
                    });
        }

        return files;
    }

    private boolean isSupportedFormat(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".pdf")
                || name.endsWith(".epub")
                || name.endsWith(".mhtml");
    }
}
```

### 6.2 SyncResult

```java
class SyncResult {
    int created;   // number of new sources inserted
    int deleted;   // number of sources removed
    int skipped;   // number of sources already in sync
}
```

### 6.3 Performance Considerations

- `Files.walk()` uses a lazy `Stream` — suitable for library directories with thousands of files.
- Hidden directories (names starting with `.`) are excluded from the walk.
- The walk collects all files into memory before sending to the API. For personal libraries (typically < 10,000 files),
  this is acceptable.

---

## 7. HTTP Communication

### 7.1 Client Design

Uses `java.net.http.HttpClient` (Java 21 built-in, no external dependency needed):

```java
class ApiClient {
    private final HttpClient client;
    private final String baseUrl;

    ApiClient(String baseUrl) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.baseUrl = baseUrl;
    }

    void createSource(CreateSourceRequest request) { ...}

    SyncResult sync(List<SourceFile> files) { ...}
}
```

All methods:

- Set `Content-Type: application/json`
- Serialize request bodies with Gson
- Deserialize response bodies with Gson
- Throw `ApiException` on non-success status codes (with status code and body)

### 7.2 API Endpoints Used

| Agent action      | HTTP method | API endpoint                 | Payload                                      |
|-------------------|-------------|------------------------------|----------------------------------------------|
| New file detected | `POST`      | `/api/sources`               | `{ path, name, sourceFormatId, authorName }` |
| Full sync         | `POST`      | `/api/sync`                  | `[{ path, name, format, authorName }]`       |
| Health check      | `GET`       | `/api/sources?page=0&size=1` | none                                         |

### 7.3 Error Handling and Retries

| Error                         | Behaviour                                                                                                                           |
|-------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| Connection refused (API down) | Log error, retry on next event. Events are NOT queued — they are lost if the API is unavailable. The next full scan will reconcile. |
| `409 Conflict` on CREATE      | Ignore (source already exists or was reactivated).                                                                                  |
| `5xx` Server error            | Log error, do not retry.                                                                                                            |

**Retry policy**: No automatic retry for individual events. Simpler than implementing a retry queue, and acceptable
because:

- The initial full scan covers any missed state.
- Reconciliation can be triggered manually.

---

## 8. Reconciliation

### 8.1 Trigger Mechanism

The Agent exposes a lightweight HTTP server (or a simple signal mechanism) to accept reconciliation triggers from the
API.

**Option A (V1 — recommended)**: The Agent runs a trivial HTTP server on a separate port (e.g., `9090`) with a single
endpoint:

```
POST /agent/reconcile → triggers a full scan → sync → returns SyncResult
```

This is implemented using Java's built-in `com.sun.net.httpserver.HttpServer` (no additional dependency):

```java
HttpServer server = HttpServer.create(new InetSocketAddress(9090), 0);
server.

createContext("/agent/reconcile",exchange ->{
        if("POST".

equals(exchange.getRequestMethod())){
SyncResult result = syncService.fullSync();
String json = gson.toJson(result);
// send 200 with JSON body
    }
            });
```

**Option B (V1 alternative) — File-based signal**: The Agent watches a marker file. When the API writes to it, the Agent
detects the change via WatchService and triggers reconciliation. Less reliable, not recommended.

### 8.2 Full Scan + Sync Flow

When reconciliation is triggered (startup or manual):

```
1. DirectoryWalker walks the entire library
2. FileCollector builds a List<SourceFile> with author metadata
3. ApiClient.sync(files) → POST /api/sync
4. API returns SyncResult { created, deleted, skipped }
5. Agent logs the result
```

The scan and sync are **synchronous**. The Agent blocks until the API responds.

---

## 9. Configuration

### 9.1 Configuration Model

```java
class AgentConfig {
    Path libraryPath;         // required: absolute path to the library directory
    String apiBaseUrl;        // required: e.g., "http://localhost:8080"
    int reconcilePort;        // optional: default 9090
    int scanIntervalMinutes;  // optional: default 30 (periodic full scan)
    boolean scanOnStartup;    // optional: default true
}
```

### 9.2 Configuration Sources

Configuration is loaded in priority order:

1. **CLI arguments** (highest priority):
   ```
   java -jar agent.jar --library=/home/user/Biblioteca --api=http://localhost:8080
   ```

2. **Environment variables**:
   ```
   BIBLOCAT_LIBRARY_PATH=/home/user/Biblioteca
   BIBLOCAT_API_URL=http://localhost:8080
   ```

3. **Configuration file** (`agent.properties` in the working directory):
   ```properties
   library.path=/home/user/Biblioteca
   api.url=http://localhost:8080
   reconcile.port=9090
   scan.interval.minutes=30
   scan.on.startup=true
   ```

### 9.3 Validation on Startup

```
- library path: must exist, must be a directory, must be readable
- API URL: must be a valid HTTP URL
- reconcile port: must be between 1024 and 65535
```

If validation fails, the Agent logs the error and exits with code 1.

---

## 10. Testing Strategy

### 10.1 Unit Tests

| Class           | What to test                                                                           |
|-----------------|----------------------------------------------------------------------------------------|
| `AuthorParser`  | Root-level file → null. One level deep → author name. Nested deeper → first component. |
| `FileCollector` | Filters only .pdf, .epub, .mhtml. Case-insensitive. Ignores hidden files.              |
| `AgentConfig`   | CLI args override env vars. Env vars override config file. Validation failure.         |
| `ApiClient`     | Request serialization. URL encoding of path. Response deserialization.                 |

### 10.2 Integration Tests

| Scenario                        | Approach                                                                                                                |
|---------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| WatchService detects a new file | Create a temp file in the watched directory, verify API call would be made (mock the client).                           |
| WatchService detects a deletion | Delete a temp file, verify no API call is made (ENTRY_DELETE is ignored). Verify deletion is detected on the next scan. |
| Full scan collects all files    | Create temp files and directories, walk, verify count and metadata.                                                     |
| Reconciliation trigger          | Start the embedded HTTP server, POST to `/agent/reconcile`, verify sync is triggered.                                   |

### 10.3 Test File Structure

Tests use a temporary directory created with `Files.createTempDirectory()`:

```
tempLibrary/
├── Kant/
│   ├── Critica.pdf
│   └── Critica.epub
├── Hegel/
│   └── Fenomenologia.pdf
├── README.txt          ← ignored
├── .hidden.pdf         ← ignored
└── solo.mhtml          ← author = null
```

---

## 11. Agent-specific Design Decisions

### 11.1 No Spring Boot

|                  | Decision                                                                                                                                                                                                                               |
|------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**       | The Agent is a standalone Java 21 application without Spring Boot.                                                                                                                                                                     |
| **Rationale**    | The Agent has no web UI, no DI requirements, no persistence. Adding Spring Boot would increase startup time, JAR size, and complexity without benefit. Java 21 provides all needed APIs: `WatchService`, `HttpClient`, `Files.walk()`. |
| **Dependencies** | Only Gson (JSON) + SLF4J (logging).                                                                                                                                                                                                    |

### 11.2 Single-Thread Event Loop

|               | Decision                                                                                                                         |
|---------------|----------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**    | Single thread for WatchService event processing.                                                                                 |
| **Rationale** | Simplicity. Blocking HTTP calls are acceptable for personal use (single-user, low event frequency). No concurrent access issues. |
| **Trade-off** | A slow API call blocks event processing. Mitigated by API being local and fast.                                                  |

### 11.3 No Event Queue for API Downtime

|                 | Decision                                                                                                                                         |
|-----------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**      | Events are not queued when the API is unavailable.                                                                                               |
| **Rationale**   | The full scan at startup reconciles any missed state. Queuing adds persistence complexity (file-based or in-memory queue) with marginal benefit. |
| **Consequence** | Files created while the Agent is running but the API is down are discovered on the next full scan (startup or manual reconciliation).            |

### 11.4 Embedded HTTP Server for Reconciliation

|               | Decision                                                                                                                                         |
|---------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**    | A lightweight HTTP server using `com.sun.net.httpserver.HttpServer` for receiving reconciliation triggers.                                       |
| **Rationale** | Simpler than polling, file-based signals, or bidirectional communication. The single endpoint (`POST /agent/reconcile`) is trivial to implement. |
| **Port**      | Configurable, default `9090`. Must not conflict with the API port (`8080`).                                                                      |

### 11.5 Path-Based Author Inference

|                | Decision                                                                                                                                                 |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**     | Author is inferred from the first directory segment below the library root.                                                                              |
| **Rationale**  | Matches the expected filesystem structure (`Autor/obra.pdf`). Simple, deterministic, no external metadata needed.                                        |
| **Limitation** | Only one level of author hierarchy. Nested directories (e.g., `Filosofia/Kant/obra.pdf`) would infer "Filosofia" as the author. This is accepted for V1. |
